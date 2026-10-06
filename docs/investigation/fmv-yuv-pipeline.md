# Investigação I1 — Pipeline FMV / YUV / flicker preto / frames pulados

**Task ID:** 3-a · **Agente:** Investigador I1 (FMV/YUV)
**Branch:** `fix/android-rendering-fmv-textures-20261004` · **SDK pin:** `74a1de0` (`thirdparty/shiftglue-sdk`)
**Dispositivo-alvo:** Motorola Edge 30 Fusion, Adreno 660, Turnip Mesa 26.3.0-devel (adrenotools), Android/KGSL.

Todas as referências `arquivo:linha` são relativas a `thirdparty/shiftglue-sdk/`, exceto quando marcadas com `[repo]` (raiz do `pinyon-shift-android`). Análise 100% estática (sem acesso ao aparelho); cada afirmação sobre código tem `arquivo:linha`.

---

## 1. Síntese executiva

O pipeline de vídeo do FH1 tem dois mundos com proteções muito diferentes. O **fast path CPU→GPU** (`texture_cache.cpp:1348-1706`, `TryLoadTextureDataFromCpu`) só existe para texturas `k_8` lineares 2D sem mips (`texture_cache.cpp:1371-1375`); é aí que vivem o snapshot sincronizado, o diff por blocos contra o baseline e a máquina de retenção (retain/skip). As texturas de vídeo 4:2:2 packed (`k_Cr_Y1_Cb_Y0_REP`/`k_Y1_Cr_Y0_Cb_REP` — o comentário em `include/rex/graphics/xenos.h:474-476` diz literalmente "Used for videos in 54540829", o title ID do FH1) **não têm nenhum desses mecanismos**: sobem pelo caminho genérico de shared memory, page-granular, que é exatamente o caminho que rasgava o FMV antes do fast path. No Adreno/Turnip, além disso, os formatos nativos `VK_FORMAT_G8B8G8R8_422_UNORM`/`B8G8R8G8_422_UNORM` não são linear-filterable e caem no fallback RGBA8 + shader de carga `GBGR8ToRGB8` (`texture_cache.cpp:3008-3032`), o que explica o log "format 37 instead of 1000156000" (emitido em `texture_cache.cpp:3311-3316`).

Sobre o **flicker preto**: a retenção de frames tortos, por si só, produz vídeo *congelado*, nunca preto — os ramos de retain devolvem `true` sem tocar na imagem (`texture_cache.cpp:1546-1558`, `1620-1632`, `1638-1653`), deixando na textura o último frame completo. O pulo de apresentação por placeholder ("Skipping Vulkan frame presentation…", `command_processor.cpp:2770-2783`) também não pinta preto: ele **não chama o presenter** (sem acquire, sem present), então o compositor do Android mantém o último buffer do swapchain — o efeito é frame *congelado*/stutter. O preto visível precisa vir de um frame que **foi** apresentado com conteúdo preto, e encontrei quatro caminhos code-grounded que fazem isso: (1) upload de snapshot com conteúdo zero/parcial pelos ramos `reaches_bottom` e `static_zero_top` vacuamente verdadeiro quando `changed_high==0` — a retenção não distingue "frame legitimamente preto (fade/letterbox)" de "plano limpo em reescrita", e um decoder que zera o plano (corte de cena/restart de loop) gera uploads de "faixa nova + resto preto" a cada snapshot (`texture_cache.cpp:1574-1576`, `1597-1605`); (2) **binding nulo preto**: se `FindOrCreateTexture` falhar (alocação VMA sob pressão de memória — o log do aparelho já mostra "AHB 4x4 alloc fail" e o device tem 512 MB de shared memory), o binding fica sem textura e o draw amostra a *null image* limpa para preto (`texture_cache.cpp:1296-1304`, `665-695`, `133-136`, `545-549`; binding nulo em `pipeline/texture/cache.cpp:708/728`); (3) presentes "só clear" do presenter quando o slot consumido da mailbox está inativo (`presenter.cpp:857-907`, `vulkan_presenter.cpp:1624-1648`, `2156-2170` — o render pass do swapchain sempre faz load-op/clear preto); (4) um frame cujo draw de composição foi pulado por placeholder **poderia** apresentar EDRAM limpo se `vulkan_async_skip_incomplete_frames` estivesse desligado no momento (cvar hot-reload — `command_processor.cpp:83-86`), mas com o default ligado o frame não é apresentado.

O funcionamento exato do skip (Q2): qualquer draw do frame cujo pipeline ainda está compilando (modo Android `vulkan_async_pipeline_no_placeholder`, `pipeline_cache.cpp:64-70`) espera até 200 ms (`pipeline_cache.cpp:71-80`, espera em `1292-1343`); se estourar, o draw é **descartado** (`command_processor.cpp:4702-4709`, `return true` sem desenhar) e a flag `frame_used_async_placeholder_pipeline_` fecha o frame sem apresentar (`2770-2783`, `EndSubmission(true)` em `2781` → o frame é submetido ao GPU, o contador avança em `6340-6346`, mas o presenter não é invocado). Isso **derruba frames inteiros do jogo, não só do FMV**, e continua disparando após o pipeline-cache wait (26208bf) porque a espera é limitada a 200 ms — filas longas de PSO (371 no cold boot, dezenas em cena nova) estouram o limite. É a causa do stutter, não do preto.

Para a **faixa no topo dos menus** (Q4), os buracos restantes na malha de retenção são: o upload `reaches_bottom` pós-clear (indistinguível de fade), o `fmv_static_zero_top` vacuoso com `changed_high==0`, as duas escapes de "boundary parado por 4 snapshots" que sobem frames tortos de decoder lento (`1617-1619`, `1638-1639`→`1654-1655`), os bail-outs que limpam o baseline e devolvem a textura ao caminho genérico rasgado (`1361-1368`, `1389-1394`, `1415-1418`, `1423-1435`), a ausência total de proteção para planos de vídeo que não sejam `k_8` (4:2:2 packed!) ou menores que 320x180 (`1371-1375`, `1513`), e resolves pulados no executor nativo (`fh1_native_executor.cpp:2255-2269`). A correção 74a1de0 fechou o caso "zero-tail com banda descendo", mas os caminhos acima ainda sobem conteúdo parcial.

---

## 2. Mapa do pipeline FMV (guest → snapshot → classificação → upload → sample → present)

```
[GUEST CPU] decoder de vídeo (software, WMV) escreve os planos na guest memory
     │  (top→bottom; dispara o watch de página do texture cache)
     ▼
[WATCH/OUTDATE] PrepareTextureLoad arma o watch (WatchPendingLoad) → escrita do decoder
     → WatchCallback → base_outdated_
     pipeline/texture/cache.cpp:455-457 (arma antes do snapshot), 879-892 (WatchMemoryRange),
     951-967 (invalidação)
     ▼
[PREPARE LOAD] a cada draw que usa o plano (ou a cada swap para a frontbuffer):
     TextureCache::PrepareTextureLoad            pipeline/texture/cache.cpp:433-505
       └─ TryLoadTextureDataFromCpu (fast path)  texture_cache.cpp:1348-1706
            ├─ pré-condições: k_8, 2D, sem mips, linear, R8_UNORM   1361-1387
            ├─ staging: upload_buffer_pool().Request (full, não Partial) 1409-1418
            ├─ CopyCpuRange (recusa páginas GPU-written → bail-out)  1423-1435
            ├─ probe de zero-chunks (5×64 B a 0/20/40/60/80%)       1492-1512  [só para LOG]
            ├─ diff por blocos vs baseline (128 blocos, ≥64 B)      1521-1545
            ├─ [SKIP]   idêntico ao frame carregado                 1546-1558
            ├─ [UPLOAD] reaches_bottom (último bloco mudou)         1574-1576
            ├─ [UPLOAD/SKIP] cauda zero: static_zero_top            1577-1633
            │      ├─ upload se topo estático-zero                  1605-1606
            │      └─ retain + escape banda parada (4 reps)         1614-1632
            ├─ [SKIP]    cauda não-zero: boundary moveu → retain    1634-1653
            │      └─ upload se boundary parado (4 reps)            1654-1655
            ├─ set_video_frame_baseline (snapshot vira baseline)    1658-1660
            └─ CmdVkCopyBufferToImage (pitch = guest row pitch)     1670-1705
     ▼
[GENERIC PATH — SEM PROTEÇÃO] CommitPreparedTextureLoad
     pipeline/texture/cache.cpp:507-561 → LoadTextureDataFromResidentMemoryImpl
     texture_cache.cpp:1708-1759+ (compute shader de carga; para 4:2:2:
     kLoadShaderIndexGBGR8ToRGB8/BGRG8ToRGB8 → R8G8B8A8, fallback decidido em 3008-3032)
     ▼
[SAMPLE] draw de composição YUV→RGB do jogo (pipeline do jogo; se placeholder → skip)
     binding: pipeline/texture/cache.cpp:563-780 (FindOrCreateTexture 708/728;
     null → preto)  ·  view: texture_cache.cpp:665-695 GetActiveBindingOrNullImageView
     ▼
[RESOLVE] EDRAM (16_16_16_16) → textura frontbuffer na guest memory
     fh1_native_executor.cpp:2255-2324 (log "FH1 FMV presentation resolve" 2277-2297;
     resolve pulado → LogSkippedResolve 2217-2225)
     ▼
[SWAP] IssueSwap → IssueSwapImpl                     command_processor.cpp:2718-3450
     ├─ BeginSubmission(true)                         2749
     ├─ ★ SKIP de apresentação (placeholder)         2770-2783
     ├─ RequestSwapTexture (frontbuffer 16_16_16_16)  2793-2799 (texture_cache.cpp:1038-1099;
     │    LoadTextureData em 1070; NULL → erro e frame fica ABERTO, ver §3.1-H5)
     ├─ get_active_swap_dimension (região ativa)      2816-2847
     └─ presenter->RefreshGuestOutput                 2902-3445
          ├─ lambda: gamma compute (swap tex → guest output img)  3098-3172
          ├─ EndSubmission(true) [fita do frame entra na fila ANTES do present] 3414
          └─ hook do native guest output renderer     3418-3441  [repo: src/native_renderer/
               guest_output_renderer.cpp — apenas observer de render-test, retorna false]
     ▼
[MAILBOX/PRESENT] Presenter::RefreshGuestOutput      src/ui/presenter.cpp:565-611
     ├─ publica no slot writable → PublishGuestOutput 613-690 (CAS ready/writable)
     └─ (com vulkan_present_on_submission_worker=true, via DeferPresentStep
          command_processor.cpp:6615-6632 — roda no worker DEPOIS da fita do frame)
          ▼
     VulkanPresenter::PaintAndPresentImpl             src/ui/vulkan/vulkan_presenter.cpp:1552+
     ├─ vkAcquireNextImageKHR (falha → kNotPresented, sem present) 1591-1617
     ├─ render pass do swapchain com LOAD-OP/CLEAR PRETO             1624-1648
     ├─ ConsumeGuestOutput (slot inativo → SEM imagem de guest output) 1652-1667,
     │    presenter.cpp:857-907 (UINT32_MAX em 902)
     ├─ clear-only + UI + EndRenderPass                              2156-2182
     └─ vkQueueSubmit + vkQueuePresentKHR                            2206-2266
```

Formatos de vídeo envolvidos: `k_8` (planos luma/croma por byte — cobertos pelo fast path) e `k_Cr_Y1_Cb_Y0_REP`/`k_Y1_Cr_Y0_Cb_REP` (4:2:2 packed macropixel de 32 bits — `xenos.h:474-476`). A decisão de formato host para os REP: nativo `VK_FORMAT_G8B8G8R8_422_UNORM` (=1000156000) / `B8G8R8G8_422_UNORM` (`texture_cache.cpp:190-205`); no Turnip sem linear filter → fallback `kLoadShaderIndexGBGR8ToRGB8`/`BGRG8ToRGB8` + `VK_FORMAT_R8G8B8A8_UNORM` (=37) (`texture_cache.cpp:3008-3032`), log em `3311-3316`; largura ímpar → `kHostFormatGBGRUnaligned` (`422-432`, seleção `3874-3878`).

---

## 3. Hipóteses ranqueadas por sintoma (Q1–Q4)

### 3.1 Q1 — FMV pisca preto entre frames apesar da retenção

A retenção em si **não** produz preto (retain = mantém o último frame completo; skip idêntico = mantém os mesmos bytes). O preto tem que entrar por um destes caminhos, em ordem de plausibilidade:

**H1 (alta) — uploads de snapshot zero/parcial nos ramos "reaches_bottom" e "static_zero_top vacuoso" após o decoder limpar o plano (corte de cena, restart de loop, fade).**
- Evidência: `texture_cache.cpp:1574-1576` — se o último bloco differe do baseline, o snapshot sobe inteiro e vira baseline, **sem checar se a cauda é zero**. Um plano recém-limpo (cauda zero) contra um baseline não-zero differe em *todos* os blocos → `fmv_reaches_bottom=true` → upload de "faixa nova + resto preto" **a cada snapshot** até o baseline ficar zero-tailed. Em seguida o ramo zero-tail com `changed_high==0` (conteúdo novo difere do topo) deixa `fmv_static_zero_top=true` *vacuamente* (`1597-1605`: o loop `for (fmv_byte < fmv_band_start)` com `fmv_band_start==0` não executa) → continua uploadando parciais (`1605-1606`). Resultado na tela: alternância rápida frame-bom → (corte) → frames pretos/parciais → frame-bom = **piscar preto**. O caso legítimo (fade/letterbox) tem a mesma assinatura por-snapshot — o código não os distingue.
- Confirmar com log novo: burst de `fh1 fmv plane load #N … zero chunks ≥1` (o probe de `1492-1512` já conta isso, mas hoje só é logado a cada 30 cargas em `1661-1669`) imediatamente antes dos frames pretos; uploads com `changed_high==0`. Refutar: se os frames pretos não coincidirem com uploads (nem reaches_bottom nem escapes), H1 cai.
- Correção (esboço): usar o probe de zero-chunks (hoje só log) como gate — snapshot com cauda zero E `changed_high==0` E baseline não-zero acima → tratar como clear-em-progresso: retain (a não ser que o *próximo* snapshot confirme o fade completando sem zeros); alternativamente reter quando `fmv_reaches_bottom` mas ≥1 chunk da cauda é zero e o baseline na cauda é não-zero, com escape por repeats como nos outros ramos.

**H2 (média-alta) — binding nulo preto por falha transiente de criação/alocação da textura do plano (pressão de memória).**
- Evidência: `pipeline/texture/cache.cpp:708/728` — `binding.texture = FindOrCreateTexture(key)`; se a criação falhar, fica `nullptr`. A view vem de `GetActiveBindingOrNullImageView` (`texture_cache.cpp:665-695`), que devolve a **null image**, limpa para `{0,0,0,0}` (`133-136`, clear em `545-549`). O draw de composição amostra preto → frame apresentado preto. Bindings são resetados a cada frame (`pipeline/texture/cache.cpp:378-383`), então uma falha de um frame = um flash; a falha é logada por c7c490d: `VulkanTextureCache: Failed to allocate a WxHxD format F mip levels M texture image (ADDR)` (`texture_cache.cpp:1296-1304`). O dispositivo já mostra sinais de pressão ("AHB 4x4 alloc fail", 512 MB shared memory `shared_memory.cpp:102-158` conforme I2).
- Confirmar: presença do erro de alocação acima (ou de `vmaCreateImage`) correlacionado com os frames pretos. Refutar: ausência do log em sessão com flicker.
- Correção (esboço): para formatos/keys de vídeo, não deixar o binding cair no null silencioso — reter a textura antiga (por endereço base) ou repetir o último frame; e/ou retry síncrono da criação no próximo draw com log.

**H3 (média) — presente "só clear" do presenter (mailbox inativa / sem imagem).**
- Evidência: o render pass do swapchain faz load-op/clear preto (`vulkan_presenter.cpp:49-50` cvar `present_render_pass_clear` default true; `1624-1648`; clear explícito `2156-2170`) e o present acontece mesmo sem imagem de guest output (`ConsumeGuestOutput` devolve `UINT32_MAX` quando o slot está inativo, `presenter.cpp:902`; o fluxo continua e apresenta). Mid-FMV isso exige um refresh com `is_active=false` (`presenter.cpp:591-598` publica *blank* quando `!IsActive()`), o que vindo de `IssueSwapImpl` é improvável (dims garantidamente ≥1 em `2837-2847`, `2897-2900`) — mas qualquer outro `PaintAndPresent` (repaint da UI/thread de janela, `presenter.cpp:662-679`) com slot inativo apresenta preto. Esperado sobretudo no cold start ("Black first seconds", doc §Known limits).
- Confirmar: instrumentar `vulkan_presenter.cpp:1659-1662` para logar `mailbox_index==UINT32_MAX` a cada present (contador). Refutar: zero presents inativos durante o FMV.
- Correção (esboço): se o slot consumido está inativo e existe um slot ativo recente, repintar o último ativo em vez de clear-only; ou não apresentar quando não há guest output e o último present foi há <N ms.

**H4 (média) — o skip de placeholder descarta frames inteiros (stutter) e amplifica a percepção de flicker; não é preto por si.**
- Evidência: `command_processor.cpp:4702-4709` (draw pulado), `2770-2783` (frame não apresentado, `EndSubmission(true)` em `2781`, contador avança em `6340-6346`). Sem acquire/present, o compositor mantém o último buffer → frame congelado. Com MAILBOX (`vulkan_presenter.cpp:57-64`, seleção `1405-1419`) não há tear, mas o frame é perdido para sempre (o draw não é re-executado).
- Confirmar: contar skips por sessão (hoje o log é uma vez só — `static bool` em `2774-2776`); correlacionar com timestamps dos frames pretos (screen recording). Refutar: skips raros (contagem baixa) sem coincidência temporal.
- Correção (esboço): (a) aumentar `vulkan_async_pipeline_wait_ms` para o path de swap/FMV; (b) ao invés de descartar o frame, re-apresentar o último guest output publicado (o doc `docs/rendering-fixes-20261004.md` §"Known limits" avaliou e adiou isso — retomar com o dado novo); (c) gerar/atualizar o shader pack `.pnsp` (NP-15.2) para eliminar o warm-up.

**H5 (baixa-média, mas com bug latente) — `RequestSwapTexture` retorna NULL e deixa o frame aberto.**
- Evidência: `command_processor.cpp:2796-2799` retorna **sem** `EndSubmission` e sem apresentar; `frame_open_` continua true, então o próximo `BeginSubmission(true)` não abre frame novo (`5996-6008`: `is_opening_frame = is_guest_command && !frame_open_`) e `frame_used_async_placeholder_pipeline_` **não é resetado** (`6073` só roda no opening) — um flag stale pode pular presents subsequentes; e `texture_cache_->BeginFrame()`/reclaim por frame não roda. O log de erro é `XELOG_GPU PRESENT: swap_texture_view=NULL`.
- Confirmar: presença desse erro no log. Refutar: ausência.
- Correção (esboço): chamar `EndSubmission(true)` também nesse early-return (o comentário de `3447-3449` já prevê "end the frame even if did not present").

**Hipóteses testadas e refutadas contra o código:**
- *Q1(a) "identical-skip deixa textura velha enquanto a apresentação pula" → tela preta*: refutado como mecanismo de preto. O skip idêntico mantém na GPU exatamente os bytes que já estão lá (`1546-1558` devolve `true` → `CompleteLoad` em `pipeline/texture/cache.cpp:476-479`); o pulo de apresentação mantém o último buffer do swapchain. Juntos produzem vídeo congelado, não preto.
- *Q1(b) acquire falha/tira imagem sem apresentar*: refutado — falhas de acquire retornam `kNotPresented*` sem present (`vulkan_presenter.cpp:1596-1616`), sem liberar/preencher imagem; o compositor mantém o buffer atual.
- *Q1(d) race na classificação*: a classificação roda na thread de GPU commands sobre um snapshot síncrono (`CopyCpuRange` em `1423`), sem concorrência interna; o estado (baseline/boundary/repeats) é por-textura e só nessa thread. A "race" relevante é a do decoder vs snapshot — que é exatamente o que H1 endereça (classificação correta, semântica ambígua).

### 3.2 Q2 — O que exatamente dispara "Skipping Vulkan frame presentation due to async placeholder draw usage"

- **Gatilho em cadeia:** (1) draw do jogo pede pipeline; (2) no Android, `vulkan_async_pipeline_no_placeholder=true` (`pipeline_cache.cpp:64-70`, restart) faz o `ConfigurePipeline` **nunca** criar pipeline síncrono nem placeholder visível: a entrada é marcada `is_placeholder` e vai para a fila de workers (`pipeline_cache.cpp:1373-1381`); (3) se o draw re-encontra a entrada ainda não pronta, espera **até `vulkan_async_pipeline_wait_ms` (200 ms default)** (`pipeline_cache.cpp:1292-1343`, log de timeout `1320-1327`); (4) estourou o limite → `pipeline_out=VK_NULL_HANDLE` com handle válido (`1334-1342`); (5) de volta no draw, `GetPipelineAndLayoutByHandle` reporta `is_placeholder=true` (`pipeline_cache.cpp:1461-1478`) e `command_processor.cpp:4707-4709` seta `frame_used_async_placeholder_pipeline_=true` e **descarta o draw** (`return true` — sucesso falso, o draw não acontece); (6) no swap, `async_shader_compilation` (default true, `src/graphics/command_processor.cpp:153-157`) **E** `vulkan_async_skip_incomplete_frames` (default true, hot-reload, `command_processor.cpp:83-86`) **E** a flag → log (uma única vez por processo, `static bool` `2774-2776`) + `EndSubmission(true)` + `return` (`2777-2783`).
- **O que acontece com o frame:** ele **é submetido ao GPU** (os draws que rodaram executam; `EndSubmission` fecha a fita, `command_processor.cpp:6138-6338`), o contador `frame_current_` avança (`6340-6346`), mas `RefreshGuestOutput` não é chamado → sem acquire, sem compute de gamma, sem present → **frame descartado da apresentação** (o buffer anterior permanece na tela; o draw pulado não é recuperado — o PM4 já foi consumido).
- **Por que ainda dispara após 26208bf:** o wait é *limitado* (200 ms). Cold boot (371 PSOs) e cenas novas com permutações inéditas de shader mantêm a fila de workers longa em um celular (2 big cores, Turnip); quando a espera estoura, o par draw-skip + frame-skip volta a ocorrer. A mensagem de companheiro é `Vulkan pipeline still not built after 200 ms, skipping the draw and the frame's present (repeat N)` (`pipeline_cache.cpp:1323-1326`, máx 8 logs).
- **É isso que causa o preto entre frames do FMV?** Não diretamente (mantém o último buffer). Contribui com o stutter e, se combinado com H1/H2 no mesmo intervalo, aumenta a percepção de "piscar". Nota: se o `environment.txt` do aparelho desligar `vulkan_async_skip_incomplete_frames` (hot-reload), os draws pulados passam a ser **apresentados** com EDRAM limpo/stale → aí sim preto visível — verificar a config do device.

### 3.3 Q3 — Fallback YUV 4:2:2: onde converte, está correto, e o papel do "identical snapshot"

- **Quem converte:** ninguém converte YUV→RGB no host. O jogo faz a conversão no shader dele; o host só reempacota os bytes. No formato nativo, `k_Cr_Y1_Cb_Y0_REP` mapeia para `VK_FORMAT_G8B8G8R8_422_UNORM` com shader de carga `32bpb` (cópia bruta do macropixel) e swizzle `XE_GPU_TEXTURE_SWIZZLE_RGBB` (`texture_cache.cpp:190-197`; swizzle definido em `xenos.h:1018-1020`). No Turnip, `vkGetPhysicalDeviceFormatProperties` não reporta linear-filter para o 422 (`texture_cache.cpp:3013-3020`) → fallback: shader `kLoadShaderIndexGBGR8ToRGB8` (idem `BGRG8ToRGB8` para `k_Y1_Cr_Y0_Cb_REP`, `3021-3032`) escrevendo `VK_FORMAT_R8G8B8A8_UNORM` (37). O shader é um compute de carga que expande cada macropixel de 4 B em 2 texels RGBA8 de 8 B (`pipeline/texture/cache.cpp:181-186`: `source_bpe_log2=4`, `dest_bpe_log2=4`, `bytes_per_host_block=8`); SPIR-V gerado em `src/graphics/shaders/vulkan_spirv/texture_load_gbgr8_rgb8_cs.h` (fontes GLSL originais não estão no repo — só bytecode; o D3D12 usa os mesmos índices, `src/graphics/shaders/bytecode/d3d12_5_1/`).
- **Ordem Cb/Cr, range, chroma siting:** o reempacotamento preserva a semântica componente-a-componente entre o caminho nativo e o fallback (ambos usam o mesmo swizzle `RGBB` e o mesmo contrato de amostragem — `texture_cache.cpp:195-197` vs `3016-3019` e `422-432`); não há mudança de range (UNORM↔UNORM) nem de siting (croma duplicada nos dois texels do macropixel equivale ao comportamento de amostragem do 422 sem Ycbcr sampler). A validação real só pode ser visual em device (ver §5). Como o D3D12 no Windows usa os mesmos shaders de expansão e o FMV funciona lá (docs do loop), o risco residual é comportamento Turnip-específico, não a lógica do reempacotamento.
- **Por que "identical snapshot" aparece:** o watch de shared memory dispara com **qualquer escrita** nas páginas do plano (`pipeline/texture/cache.cpp:882-890`, callback `951-985`), inclusive reescritas byte-idênticas — decoder republicando o mesmo frame (30 fps de vídeo em apresentação de 60 Hz com re-memcpy por present, segunda passada de decode, ou planos duplicados). O diff não acha nenhum bloco diferente → skip do upload (`texture_cache.cpp:1546-1558`, log a cada 128 ocorrências).
- **O skip idêntico congela/piora o flicker?** Em condições normais não: a textura já mostra exatamente esses bytes (no-op visual, `MarkAsUsed` em `1549` mantém a textura viva). O risco é se o baseline estiver **envenenado** (igual a um upload parcial/torto anterior — possível via H1/§3.4) e o decoder parar: snapshots futuros idênticos ao veneno seriam pulados e o conteúdo torto ficaria congelado. O código se autocorrige assim que uma reescrita completa chega ao último bloco (upload + re-baseline, `1574-1576`). O skip nunca gera preto.

### 3.4 Q4 — Faixa glitchada no topo em menus: caminhos restantes para snapshot parcial subir

O caso zero-tail com banda descendo foi fechado (05bab12/74a1de0). Verificando o código atual, ainda sobem conteúdo parcial/torto por:

1. **`reaches_bottom` pós-clear (§3.1-H1):** baseline não-zero + cauda zero ⇒ todos os blocos "mudam" ⇒ upload a cada snapshot durante a reescrita (`texture_cache.cpp:1574-1576`). É a fonte clássica da "strip crescente sobre preto" em restart de loop do vídeo de fundo do menu.
2. **`static_zero_top` vacuoso:** com `changed_high==0` (prefixo mudando desde a linha 0), `fmv_band_start==0` e o loop de `1599-1604` não executa ⇒ `fmv_static_zero_top=true` ⇒ upload (`1605-1606`) — mesmo quando o topo do baseline tem conteúdo (não é letterbox).
3. **Escapes de boundary parado:** banda/limiar parado por `kFmvBoundaryRepeatUpload=4` snapshots ⇒ upload torto (`1617-1619` no ramo zero-tail; `1638-1639`→`1654-1655` no ramo não-zero). Decoder lento/starved no menu (CPU disputando com loading) cai aqui.
4. **Bail-outs que limpam o baseline e caem no caminho genérico:** `!load_base || load_mips || resolve_sourced` (`1361-1368`), `!submission_open` (`1389-1394`), falha do staging `Request` (`1415-1418`), recusa de `CopyCpuRange` por páginas GPU-written (`1423-1435`) — todos chamam `clear_video_frame_state()` e devolvem `false` ⇒ upload genérico page-granular (rasgado, o bug original).
5. **Formatos não cobertos:** o fast path exige `k_8` (`1371-1375`). Se o vídeo de fundo do menu usa textura 4:2:2 packed (`k_Y1_Cr_Y0_Cb_REP` — "Used for videos in 54540829", `xenos.h:475-476`), ele não tem **nenhuma** proteção; e planos <320x180 também não (`1513`). Precisa-se do log do device para saber o formato/endereço real do plano do menu (`fh1_texture_reload_probe` já existe para isso, `pipeline/texture/cache.cpp:59-61`, log em `463-475`).
6. **Sem baseline (primeira carga):** qualquer snapshot sobe, inclusive all-zero (`1521-1522`: `fmv_baseline_refresh=retention_active` sem baseline ⇒ upload; comentário `1515-1520` assume isso).
7. **Resolve pulado:** `fh1_native_executor.cpp:2255-2269` pula resolves (log `LogSkippedResolve` `2217-2225`) e deixa stale o frontbuffer — bandas/linhas nas telas de controles (doc §Known limits).
8. **Eviction/recriação:** `CompletedSubmissionUpdated` destrói texturas acima do limite soft/hard (`pipeline/texture/cache.cpp:310-370`; floor de 256 MB sob `RequestMemoryReduction` `300-308`, disparado por `kLowMemory` → `GraphicsSystem::ReduceMemory` `graphics_system.cpp:520-528` ← `rex_app.cpp:344-360`); textura recriada volta ao caso 6.

**Outros candidatos a ruído no topo (não snapshot):** viewport/scissor do swap cobrem a imagem inteira (`command_processor.cpp:3352-3365`), o compute de gamma cobre tudo (`3170-3172`), o upload do fast path respeita pitch do guest (`1691-1704`) — sem off-by-one encontrável; o doc `rendering-fixes-20261004.md` §1 já concluiu que a banda chega *dentro* da imagem de guest output. Restam: tamanho ativo do swap vs alocação (`get_active_swap_dimension`, `2816-2832`) e a região ativa do resolve (item 7).

---

## 4. Pontos de hook para instrumentação da Tarefa A (por-frame FMV)

Formato sugerido por evento: `frame=<frame_current_> sub=<submission> tex=<ptr> key=<base_page> dims=WxH bytes=N` + o campo específico. Todos são logs rate-limited por contador (não `static bool`), hot via `fh1_fmv_debug` onde aplicável.

**Classificação (texture_cache.cpp):**
| Hook | arquivo:linha | O que logar |
|---|---|---|
| Entrada do fast path | `texture_cache.cpp:1348` (após `1377`) | ptr, `base_page`, dims, `size_bytes`, `load_base/mips/resolve_sourced`, `submission_open` |
| Bail-outs (4 casos) | `1366`, `1392`, `1416`, `1433` | motivo + ptr/key (hoje só o de `1429` loga, %16) |
| Fim do diff | após `1545` | `changed_high/low`, `block_count`, `tail_zero`, `zero_chunks` (já computados em `1495-1512` — hoje só entram no log %30 de `1665`) |
| Skip idêntico | `1553` (hoje %128) | reduzir para %8 + incluir `boundary/repeats` |
| Upload reaches_bottom | `1576` | **novo** log: `zero_chunks`, `changed_high==0` (detector H1) |
| static_zero_top vacuoso | `1605` | **novo** log quando `fmv_band_start==0` (detector §3.4-2) |
| Retain zero-tail | `1626` (hoje %32) | incluir `boundary/repeats` |
| Retain torto | `1647` (hoje %32) | idem (o "boundary X/128 repeats N" já traz; adicionar frame) |
| Escapes (upload torto) | `1619` e `1655` | **novo** log explícito "escape upload" |
| Baseline refresh | `1659` | ptr/key/size (detecta re-baseline em veneno) |
| Upload real | `1702` | extent/offset/`bufferRowLength` |

**Apresentação (command_processor.cpp / pipeline_cache.cpp):**
| Hook | arquivo:linha | O que logar |
|---|---|---|
| Skip de apresentação | `2773` (trocar `static bool` de `2774-2776` por contador) | `frame_current_`, contagem acumulada, razão (placeholder) |
| Draw pulado por placeholder | `4708` | `frame_current_`, hash/estado do pipeline (identifica *qual* PSO esquenta) |
| Timeout de wait | `pipeline_cache.cpp:1322` | já loga ≤8×; tornar contador %N |
| Swap FMV | `2810` | por swap: `guest_output_WxH`, `src_scaled/unscaled`, troca de tamanho (detector de recriação de imagem, `vulkan_presenter.cpp:1073-1087`) |
| swap_texture_view NULL | `2797` | já é ERROR; adicionar `frame_current_` (frame fica aberto — H5) |

**Presenter (detector de present preto):**
| Hook | arquivo:linha | O que logar |
|---|---|---|
| Present sem guest output | `vulkan_presenter.cpp:1659` (`mailbox_index==UINT32_MAX`) | **novo** WARN contador: "presented clear-only (black)" — o detector definitivo de H3 |
| Acquire falho | `1596-1616` | já loga; adicionar contagem |
| Recreate de imagem por tamanho | `1074-1079` | **novo** log (tamanho antigo→novo) |
| Publish/drop de ready | `presenter.cpp:626-634` | `replaced_ready != published_acquired` (frame sobreposto/descartado na mailbox) |

**Texture cache global:**
| Hook | arquivo:linha | O que logar |
|---|---|---|
| Eviction | `pipeline/texture/cache.cpp:363-367` | já loga sob `reducing`; adicionar log quando a textura destruída tem dims de plano de vídeo (detector §3.4-8) |
| Falha de alocação | `texture_cache.cpp:1298` | já loga (c7c490d) — correlacionar com frames pretos (H2) |

Com esses hooks, uma sessão de FMV de ~30 s no device responde: (a) quantos presents são pulados/só-clear; (b) quantos uploads acontecem com `zero_chunks>0` e `changed_high==0`; (c) se há falha de alocação/binding nulo; (d) se o plano do menu é `k_8` ou 4:2:2.

---

## 5. Riscos e lacunas de evidência (só o device responde)

1. **Qual formato o vídeo de fundo do menu usa** (`k_8` vs `k_Y1_Cr_Y0_Cb_REP` packed) e seus endereços/dims — decide se a lacuna §3.4-5 é a causa da faixa. Fontes: `fh1_texture_reload_probe=true` (restart) + `fh1_fmv_debug=true`.
2. **O decoder do FH1 limpa os planos entre frames/cortes?** (premissa de H1). Um log com `zero_chunks` por snapshot responde; sem isso H1 é inferência da semântica do código, não observação.
3. **Frequência real do placeholder-skip** — o log atual é 1×/processo; sem contador não sabemos se são 3 ou 3000 eventos por sessão, nem se coincidem com os frames pretos.
4. **Config do aparelho**: `environment.txt`/cvars podem desligar `vulkan_async_skip_incomplete_frames` ou alterar `vulkan_async_pipeline_wait_ms` (ambos hot-reload) — verificar o arquivo de config do state root antes de concluir Q2.
5. **Correção visual do fallback 4:2:2 no Turnip** (ordem Cb/Cr percebida, cores "erradas" no vídeo) — só comparação visual/screencast; os fontes GLSL dos shaders de carga não estão no repo (só SPIR-V/DXIL gerados), então a auditoria byte-a-byte do reempacotamento teria que ser feita por desmontagem.
6. **"AHB 4x4 alloc fail (format 59/56)"** — não existe no código do SDK nem do app (grep negativo em `src/` e `android/`); é mensagem interna do driver Turnip/KGSL. Causalidade com pressão de memória (H2) é plausível mas não provada; correlacionar com os erros de `vmaCreateImage` (§3.1-H2).
7. **VK_ERROR_DEVICE_LOST (tu_knl_kgsl)** — tratado pelo I2 (`docs/investigation/vulkan-device-lost.md`); se ocorrer durante FMV, todos os caminhos aqui descritos degradam (device_lost_ fecha BeginSubmission e o presenter). Não re-derivado aqui.
8. **Mailbox inativa mid-sessão (H3)** — não encontrei caminho code-grounded que inactive um slot durante FMV com `IssueSwapImpl` saudável; se o detector §4 acusar, a causa está fora do mapeado (ex.: interação com o repaint da UI do SDL/Android).

---

## 6. Resumo das correções propostas (esboços, sem implementar)

1. **Gate de zero-chunks na classificação** (H1/§3.4-1,2): usar o probe existente (`texture_cache.cpp:1492-1512`) para reter snapshots com cauda zero quando `changed_high==0` e baseline superior não-zero, com o mesmo escape de repeats dos outros ramos — elimina uploads de "strip+preto" pós-clear preservando fades legítimos (fade completo = todos os chunks zero, caso diferente).
2. **Contador no skip de apresentação** (Q2): substituir o `static bool` de `command_processor.cpp:2774-2786` por log rate-limited com `frame_current_` — pré-requisito para qualquer decisão sobre apresentar o último guest output em vez de descartar.
3. **Reapresentar o último guest output em frames pulados** (Q2/Q1-H4): no lugar de `EndSubmission(true); return;`, publicar o slot ready atual sem re-render (o evaluator do doc §Known limits adiou isso; com os contadores de §4 a decisão fica barata).
4. **EndSubmission no early-return de swap_texture_view NULL** (H5): `command_processor.cpp:2796-2799`.
5. **Estender o fast path/retenção a `k_Y1_Cr_Y0_Cb_REP`/`k_Cr_Y1_Cb_Y0_REP` lineares** (§3.4-5) — se o log do device confirmar que o vídeo do menu é 4:2:2 packed: mesmo snapshot+diff, com `size_bytes` do macropixel (bloco de 4 B) e o upload indo para o caminho do load shader de expansão.
6. **Não deixar binding de vídeo cair no null preto silencioso** (H2): manter a última textura válida por key/base_page quando `FindOrCreateTexture` falha, com WARN.

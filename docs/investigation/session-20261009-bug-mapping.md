# Mapeamento de Bugs de Execução — Forza Horizon (Xbox 360) no Pinyon Shift Android

**Sessão analisada:** `session_20261009_142143` (ZIP: `https://github.com/deivid22srk/pinyon-shift-android/releases/download/Log/session_20261009_142143_1791566657283.zip`)
**Build:** app `80630d06` (feat/forza-style-launcher-ui) · SDK `6cf50bb8` (thirdparty/shiftglue-sdk) — os mesmos commits do header de `crash.log`
**Dispositivo:** Motorola Edge 30 Fusion (tundra), Adreno 660, Turnip Mesa 26.3.0-devel (git-c4eb475c28), Android 14 (SDK 34), KGSL
**Driver:** WN-TURNIP-1.18-P AXXX (`wnturnip118paxxx_1_mv17h37g`) · Vulkan 1.3.363
**Convenção:** `all.log`, `fmv.log`, `gpu.log`, `crash.log` etc. = arquivos do ZIP; `[SDK]` = `thirdparty/shiftglue-sdk/`; `[repo]` = este repositório. Linhas citadas de código são do SDK pin `6cf50bb` (verificadas in-loco, não herdadas dos docs de 2026-10-06).

> Todas as conclusões abaixo têm referência explícita aos logs da sessão. Onde o log não permite fechar a causa, isso é declarado e a correção proposta é de instrumentação ou mitigação segura.

---

## 0. Cronologia da sessão (base para todos os cruzamentos)

Sessão iniciada 17:21:44 (device_info). Correlação estabelecida entre o relógio dos logs e os timestamps da gravação: **gravação 0:00 ≈ 17:22:53** (o retorno ao launcher relatado em "~1:04" bate com o SIGABRT em 17:23:57.524 de `crash.log`).

| Hora do log | Evento | Evidência |
|---|---|---|
| 17:21:44.2 | Boot Vulkan; capability report | `all.log` 19 (`VULKAN_CAPABILITY_REPORT`) |
| 17:21:45.6 | 1º present clear-only (preto de boot) | `all.log` (warn `presenting a clear-only frame`) |
| 17:21:47–55 | Warm-up inicial de PSOs; 4 frames pulados | warns `pipeline still warming` + `Skipping …` frames 2/422/423/503 |
| 17:21:51–17:23:00 | Cinemáticas FMV: 176 plane loads (640x360/1280x720) | `fmv.log` completo |
| 17:22:53–59 | Fim da última cinemática: rajada de torn snapshots (repeats 1→3) | `fmv.log` 6 linhas `torn snapshot retained` |
| 17:23:00→ | Último plane load #5250; sem tráfego de vídeo depois | `fmv.log` |
| 17:23:03–31 | Burst de compilação de PSOs no fim das cinemáticas → **32 frames pulados** (contadores 16 e 32) | `all.log` warns 17:23:16.441 (frame 2802, 98 placeholders) e 17:23:25.544 (frame 2880) |
| 17:23:12.9 | Criada a textura `14120000` — k_8_8_8_8, **512x288 tiled, endian 2, 4 mips** | `all.log` `texture created: 14120000 …` |
| 17:23:33–35 | Tela de controles (rec 0:40) | correlação de tempo |
| 17:23:37–55 | Gameplay (rec 0:44–1:02) — corrupções de textura | correlação de tempo |
| 17:23:56.3 | Frame 3115 pulado com **224 placeholder draws** (contador 48) | `all.log` |
| 17:23:57.4 | Frame 3123 fecha com **20 texturas criadas** | `all.log` `vulkan frame 3123 closed: … 20 textures created` |
| 17:23:57.523 | **GPU faulted or hung (VK_ERROR_DEVICE_LOST)** — Turnip `tu_knl_kgsl.cc:1817` | `all.log` |
| 17:23:57.524 | Breadcrumbs: GPU confirmou até o marcador **#3328683** (`texture load 82208`); CPU gravou até **#3329053** | `crash.log` |
| 17:23:57.5x | Suspeitos: draws 899–1021 do frame 3124, pass `0x38E` — logo após `texture load 82208 … 512x288x1 pitch 16 tiled true … mips 3 packed true base 14120000 mip 141B0000 load base false` | `crash.log` |
| 17:23:57.5x | `Pinyon Shift fatal signal signal=sigabrt` | `crash.log` (última linha) |

---

## 1. Tabela-resumo

| BUG | Causa raiz (status) | Trecho do log (arquivo + hora/linha) | Correção proposta | Prioridade |
|---|---|---|---|---|
| 01 | Dois mecanismos **confirmados**: (M1) frames inteiros descartados por pipeline placeholder — 48 frames na sessão; (M2) rajada de torn snapshots no fim do vídeo | M1: `all.log` warns `Skipping Vulkan frame presentation …` contadores 1,2,3,4,16 (17:23:16.441, frame 2802, 98 placeholders), 32 (17:23:25.544), 48 (17:23:56.336, 224 placeholders); M2: `fmv.log` 17:22:56.937→17:22:59.913 (`torn snapshot retained … boundary 106–126/128 repeats 2–3`) | Re-apresentar o último guest output publicado quando o frame for pulado; elevar `vulkan_async_pipeline_wait_ms` no preset Android | Crítica |
| 02 | Retenção FMV **exonerada** pelos logs (nenhum torn subiu: 0 uploads de escape). Candidatos restantes: resolve de apresentação sub-rect sobre superfície padded 1280x736 e região ativa do swap (requer dump de 1 frame para fechar) | Negativas que exoneram: `all.log` sem `boundary-escape upload`, sem `prefix upload with zero tail`, `skips={}` nos stats do executor (`FH1 native executor … skips={}` 17:21:55→17:23:42); strip visível 0:06–0:42 atravessando trocas de conteúdo | Instrumentar `fh1_resolve_dump_dir` + log da dimensão ativa do swap; barreira entre o resolve de apresentação e o load da swap texture | Alta |
| 03 | Exposição nas cinemáticas consistente com LUT de color grading ausente (quadro clínico documentado); **não refutável nem confirmável por este ZIP** (files.log só registra falhas de VFS e não há tentativa de `colourgradingmaps`) | `files.log` completo (507 linhas, apenas 5 falhas benignas: `\Device\Image`, `d:\DebugOptions.ini`, etc.); `all.log` sem erros de gamma/validação; 3.073 presents estáveis 1280x720 | Verificar/recopiar `media/dynamicpost/colourgradingmaps` no device; injeção de LUT identidade 16³ repo-side em falha de abertura | Média-Alta |
| 04 | Corrupção de conteúdo no caminho de upload de textura tiled com mips packed (sem falha de alocação/formato). Candidata primária: a textura `14120000` 512x288 (o diagrama é 16:9) cujo reload mips-only fecha o breadcrumb do hang | `all.log` 17:23:12.895 `texture created: 14120000 xenos 6 -> vk 37 512x288x1 mips 4 tiled 1 endian 2`; `crash.log` marcador #3328680 `texture load 82208 … 512x288x1 … tiled true … mips 3 packed true base 14120000 mip 141B0000 load base false`; `all.log` `vulkan frame 3123 closed: … 0 texture create failures` | Coalescer loads mips-only em upload base+mips (cvar); ativar `vulkan_texture_log` | Alta |
| 05 | Mesma família do 04 (mips de recorte corrompidos → faixas pretas serrilhadas quando LOD>0 amostro dados tortos) | Sem linha direta (`vulkan_texture_log=false` na sessão — config_dump); indício: padrão LOD-dependente + breadcrumb de loads mips-only em gameplay (crash.log) | Mesma correção do 04 + A/B com `vulkan_force_bc_decode=true` | Alta |
| 06 | Smear/borrado: cadeia de mips com conteúdo stale/garbage (loads mips-only) sobreposta à amostragem anisotrópica (usuário já elevou `anisotropic_override` de 3→5) | `config_dump.txt` `anisotropic_override = 5 | default 3 | source 1` (tentativa de mitigação do usuário); `crash.log` breadcrumb `load base false` | Mesma correção do 04 | Média |
| 07 | Grade verde + xadrez + ruído que muda com a câmera = corrupção dependente de LOD/tile no caminho tiled (família do 04); agravada por fetch constants inválidos serem aceitos | `crash.log` breadcrumb `texture load 82208 … tiled true … packed true … load base false` (o load tiled mips-only era rotina em gameplay); `config_dump.txt` `gpu_allow_invalid_fetch_constants = true` | Coalescer mips-only (04) + teste com `gpu_allow_invalid_fetch_constants=false` | Crítica |
| 08 | Cadeia de sombras: resolve de profundidade k_24_8→R32_SFLOAT (DepthUnorm/DepthFloat) — não fechável por este log; requer dump | `all.log` stats do executor: `clear_depth=10673`, `depth_overwrite_draw=6689`, `skips={}` (17:23:42.086) — sem skips de resolve; nenhum erro de depth | Instrumentar `fh1_resolve_dump_dir` no resolve de sombra; A/B `depth_float24_convert_in_pixel_shader=true` | Alta |
| 09 | Guard-rail teal + sinalização duplicada: família do 04 (atlas roadside) + fetch constants inválidos (idem 07) | idem BUG-07 (`config_dump.txt` `gpu_allow_invalid_fetch_constants = true`; breadcrumb de loads tiled mips-only) | idem BUG-07 | Média-Alta |
| 10 | Três sub-sintomas: interior preto = família 04 (materiais); superexposição = família 03 (LUT); blob amarelo no retrovisor = suspeito documentado de R/B swap no resolve do cubo de reflexão k_2_10_10_10 (pack=1) | Sintomas em rec 1:02; sem erros no log; `FH1 FMV presentation resolve: … fmt 7 bias -4` (`all.log` 17:21:53.742/.788) mostra resolves com bias em uso; docs `textures-formats-resolve.md` §4.5-H1 | Dump do cubo (16px/face) via `fh1_resolve_dump_dir`; LUT (03); textura log (04) | Alta |
| 11 | Pillarbox é a configuração atual funcionando como projetado: `present_letterbox=true` + `pinyon_shift_hor_plus=false` sobre display 2165x1080 (≈20:9) com guest 1280x720 (16:9) | `config_dump.txt` `present_letterbox = true | default true` e `pinyon_shift_hor_plus = false | default false`; 3.073× `XELOG_GPU PRESENT: … 1280x720 …` (guest estável 16:9) | Expor/ativar HOR+ (ultrawide) na UI de settings; opcionalmente default por razão de tela | Média |
| 12 | **Confirmado**: GPU hang do Turnip → `VK_ERROR_DEVICE_LOST` → `rex::FatalError` → `abort()` → SIGABRT. Última op de GPU confirmada: load mips-only tiled (família 04); contexto: tempestade de PSOs (224 placeholders no frame 3115) | `all.log` 17:23:57.523 (warn `tu_knl_kgsl.cc:1817 GPU faulted or hung`); `crash.log` breadcrumbs #3328680–83 done / #3328684+ pending (suspect draws 899–1021 frame 3124); `signal=sigabrt` | Mitigar tempestade de PSOs (cap + shader pack .pnsp); coalescer mips-only (04); converter abort fatal em retorno gracioso ao launcher com mensagem | Crítica |

---

## 2. Análise por bug (causa → log → correção → regressão)

### BUG-01 — Telas pretas prolongadas na intro/cinemáticas

**(a) Causa raiz — dois mecanismos confirmados pelos logs:**

- **M1 — Frames inteiros descartados por pipeline assíncrono (placeholder).** No Android, `vulkan_async_pipeline_no_placeholder=true` faz todo pipeline entrar em compilação em background; um draw cujo pipeline não ficou pronto em `vulkan_async_pipeline_wait_ms` (200 ms) é descartado e o frame **não é apresentado** (`[SDK] src/graphics/vulkan/command_processor.cpp:2794-2811` — `skip_present_due_async_placeholder` → `EndSubmission(true); return;` sem acquire/present; o draw é descartado em `:4741-4749`). O log é rate-limited (`<=4 || %16`): os 7 warnings da sessão representam **48 frames descartados**:
  - `all.log` 17:23:16.441 — `Skipping Vulkan frame presentation … (frame 2802, 98 placeholder draws, 16 frames skipped so far)`
  - `all.log` 17:23:25.544 — `(frame 2880, 1 placeholder draws, 32 frames skipped so far)`
  - `all.log` 17:23:56.336 — `(frame 3115, 224 placeholder draws, 48 frames skipped so far)`
  
  Entre 17:23:03 e 17:23:31 (rec 0:10–0:38) há 111 warns `draw skipped, pipeline still warming` — a janela exata das telas pretas 0:10–0:18 e 0:22–0:38. Às 60 Hz, 32 frames ≈ meio segundo congelado por burst, em rajadas — percepção de "tela preta travada" entre trechos de vídeo.
  
- **M2 — Rajada de torn snapshots no fim da última cinemática (rec 0:00–0:04).** O vídeo termina com o plano sendo reescrito; o snapshot chega rasgado e a retenção segura o último frame completo, mas a troca 640x360→1280x720 (`fmv.log` #5220 em 17:22:57.646) com `boundary 61/128 repeats 3` (17:22:59.913) mostra o decoder reescrevendo tudo — o intervalo sem frame completo coincide com o preto 0:00–0:04.

**(b) Trechos do log:** ver tabela-resumo M1/M2 acima; complemento: `fmv.log` 17:22:56.937/17:22:57.763/17:22:58.620/17:22:59.278/17:22:59.913 (5 torn snapshots em 3 s) e `vulkan.log`/`gpu.log` (mesmos warnings, streams dedicados funcionando).

**(c) Correção mínima (pseudo-patch):**

```
[SDK] src/ui/vulkan/vulkan_presenter.cpp  (PaintAndPresentImpl, ~:1652-1690)
  Hoje: ConsumeGuestOutput devolve slot inativo -> apresenta clear-only (preto).
  Mudança: o presenter mantém o último slot consumido válido (imagem + extent).
  Se o slot consumido está inativo E existe último válido, reusa-o em vez de clear.
  (o warn "presenting a clear-only frame" em :1679 passa a indicar o caso residual)

[SDK] src/graphics/vulkan/command_processor.cpp  (:2794-2811)
  Hoje: if (skip_present_due_async_placeholder) { EndSubmission(true); return; }
  Mudança: além de EndSubmission(true), agendar o present step adiado
  (DeferPresentStep) — com o patch do presenter acima, isso re-apresenta o
  último guest output publicado (comportamento de player de vídeo: frame
  congelado em vez de preto).
  O contador já existe (skipped_incomplete_frames) — manter.

[repo] preset Android (pinyon_shift_app.cpp / environment.txt do device)
  vulkan_async_pipeline_wait_ms: 200 -> 500 (cvar hot-reload; custo: até 500 ms
  de espera por draw em cold boot, trocando descarte por atraso).
```

**(d) Teste de regressão:** boot → cinemáticas completas com gravação de tela; critério: nenhum intervalo >2 frames com present pulado (contador `frames skipped so far` no log) e nenhuma janela preta >100 ms fora fades legítimos do vídeo; comparar com o vídeo da sessão 2026-10-09 (0:00–0:38).

### BUG-02 — Faixa de pixels corrompidos no topo do frame

**(a) Causa raiz — status: retenção FMV exonerada; dois candidatos restantes, fecháveis com 1 frame de dump.**

O que os logs **eliminam**:
- Nenhum upload de snapshot torto: 0 linhas `boundary-escape upload`, 0 `cleared-plane boundary-escape`, 0 `prefix upload with zero tail` em toda a sessão (os 6 `torn snapshot retained` ficaram em repeats ≤3, abaixo do limiar `kFmvBoundaryRepeatUpload=4` — `[SDK] texture_cache.cpp:1519`);
- Nenhum tile sem dono: stats do executor `skips={}` em todos os relatórios (`all.log` 17:21:55.491, 17:22:15.777, 17:22:36.908, 17:23:00.711, 17:23:42.086 — `Skip("resolve_tiles_unowned")` nunca contou);
- Sem warnings de sizing do swap (`swap sizing` — 0 ocorrências).

O que permanece (code-grounded, docs `textures-formats-resolve.md` §4.1-H2/H3):
- **H2': resolve de apresentação sub-rect + superfície temporal 1280x736 (720 ativas)** — o post-chain do jogo atualiza a superfície de apresentação em sub-rects; se a ordenação/barreira entre o compute do resolve e o `LoadTextureData` da swap texture falhar no Turnip, linhas não-resolvidas do topo apresentam mistura de frames;
- **H3': região ativa vs padding** no sampling da swap texture (`get_active_swap_dimension`, `[SDK] command_processor.cpp` IssueSwapImpl).

A persistência da faixa 0:06–0:42 **através** de cinemática → tela de controles → gameplay aponta para o caminho de apresentação (comum a todas as fases), não para texturas por-cena. Os frames pulados (M1 do BUG-01) prolongam visualmente o artefato: o buffer velho com a faixa permanece no display enquanto o frame novo não é apresentado.

**(b) Trechos do log:** negativas acima; `fmv.log` torn snapshots 17:22:56–59 (contaminação conhecida do plano de vídeo nesse intervalo); `all.log` `FH1 FMV presentation resolve: rect (0,0)-(320,184) … fmt 7 bias -4` (17:21:53.742/.788) — resolves de apresentação com sub-rects em uso.

**(c) Correção mínima:**

```
Etapa 1 (diagnóstico, sem mudança de comportamento):
[repo + SDK] ativar em environment.txt: fh1_resolve_dump_dir=<dir>
  -> capturar 2 frames seguidos na tela de controles (rec ~0:40);
  se a faixa já está no dump -> guest/resolve (H2'); se o dump é limpo e a
  tela não -> composição/presenter.
[SDK] command_processor.cpp IssueSwapImpl (~:2810)
  logar por swap: packet_size, src_size, active dimension (hoje só em warn de sizing).

Etapa 2 (patch candidado H2', mínimo e seguro):
[SDK] texture_cache.cpp RequestSwapTexture (:1038-1099) / command_processor.cpp
  Garantir barreira semântica (kComputeWrite -> kTransferRead/compute) entre o
  último resolve de apresentação do frame e o load da swap texture no MESMO
  frame — hoje a ordem na fila cobre, mas o Turnip já mostrou fault no padrão
  compute/copy (BUG-12); explicitar via SubmitBarriers(true) antes do
  CmdVkCopyBufferToImage do swap.

Etapa 3 (mitigação visual imediata):
[SDK] reter linha 0 do último swap completo: se o extent resolvido deste frame
  não cobre row 0, copiar row 0 do frame anterior no load (mesma ideia da
  retenção FMV, para o presentation resolve).
```

**(d) Teste de regressão:** tela de controles + cinemática final, `fh1_resolve_dump_dir` ativo; critério: dump limpo ⇒ faixa ausente na tela; dump com faixa ⇒ patch H2' elimina-a nos dumps e na tela.

### BUG-03 — Brilho/exposição incorretos nas cinemáticas

**(a) Causa raiz — consistente com LUT de color grading ausente (não refutável por este ZIP).** O título aplica grading via LUT 3D 16³ `k_8_8_8_8` de `game:\media\dynamicpost\colourgradingmaps\` (docs `NATIVE_FRAME_CONTRACT.md:162,178-181`); com arquivos ausentes o input da LUT fica vazio e o quadro documentado é exatamente "highlights estourados + cenas escuras ilegíveis" (docs `rendering-fixes-20261004.md:86-92`). Nesta sessão não há erro de abertura desses arquivos (`files.log` só registra 5 falhas benignas) porque os warnings de LUT ficam no log do **picker** (Java, antes da sessão — `[repo] GamePickerActivity.java:198-221`) e o título pode nem tentar abrir quando o diretório não existe. Não há erro de gamma/validação Vulkan em `all.log`; os 3.073 presents são estáveis (1280x720, fmt 7) — o presenter não oscila.

**(b) Trechos do log:** `files.log` completo (ausência de tentativas de `colourgradingmaps`); `config_dump.txt` `gamma_render_target_as_unorm16 = true` (caminho de gamma ativo); `all.log` 3.073× `XELOG_GPU PRESENT … 1280x720 format=7` (saída estável — a oscilação vem do conteúdo).

**(c) Correção mínima:**

```
Etapa 1 (device): conferir no game root /storage/emulated/0/D/xboxx/force:
  media/dynamicpost/colourgradingmaps/* — recopiar do dump original se ausente.

Etapa 2 (repo, injeção de LUT identidade em falha de abertura):
[repo] src/pinyon_shift_runtime_hooks.cpp:583-610 (PinyonShiftObserveGuestFileOpen)
  Ao interceptar NtCreateFile/NtOpenFile FALHANDO sob
  media/dynamicpost/colourgradingmaps/, criar arquivo sintético com LUT
  identidade 16³ k_8_8_8_8 (bytes: v=round(255*i/15) replicado em RGB, alpha 255,
  layout tiling linear do XDS já usado pelo re-encode PNG->storage da UI,
  docs/UI_ASSETS.md:69-72) e devolver sucesso.
  Cvar de gating: pinyon_shift_identity_lut_fallback (default true).
```

**(d) Teste de regressão:** mesma cinemática (fogos ~0:20 e corte ~0:08) com LUT presente vs injetada; critério: sem clipping de highlights (istograma do screencast) e cenas noturnas legíveis; A/B visual com Windows/D3D12 na mesma cena.

### BUG-04 — Textura do diagrama do controle corrompida

**(a) Causa raiz — corrupção de conteúdo no caminho de upload tiled+mips-packed; sem falha de alocação/formato.** O texto da tela é geometria vetorial (por isso renderiza limpo — docs `UI_ASSETS.md:106-126`); o diagrama é textura. A sessão não registra **nenhuma** falha de criação (`0 texture create failures` em todos os frame summaries, inclusive `vulkan frame 3123 closed: … 20 textures created`) nem formato sem suporte (`Unsupported texture formats` — 0 ocorrências). Candidata primária: `14120000` — `texture created: 14120000 xenos 6 -> vk 37 512x288x1 mips 4 tiled 1 endian 2` (`all.log` 17:23:12.895, criada durante a transição para a tela de controles) — dimensão 16:9 de diagrama, tiled com mips packed; seu reload **mips-only** é a última operação de GPU confirmada antes do hang (`crash.log` #3328680). Loads mips-only em texturas tiled exercitam o caminho de offsets per-level do load compute shader (`[SDK] texture_cache.cpp:2190-2196` — `guest_offset += mip_offsets_bytes[level]`) exatamente onde divergências Turnip vs desktop são plausíveis.

**(b) Trechos do log:** `all.log` 17:23:12.895 (criação); `crash.log` marcador #3328680 (load mips-only da mesma base 14120000); `config_dump.txt` `vulkan_texture_log = false` (por que não há log por textura).

**(c) Correção mínima:**

```
[SDK] src/graphics/vulkan/texture_cache.cpp LoadTextureDataFromResidentMemoryUntimed
  Novo cvar vulkan_texture_load_coalesce_mips (default true no Android):
  quando level_first==1 (load mips-only) e texture_key.tiled, reescrever
  level_first=0 e level_last=mip_max_level (upload base+mips de uma vez).
  Custo: re-upload do nível base (~589 KB na 14120000) por load — banda
  insignificante perto do custo do hang.
  Ganho: elimina o caminho suspeito (mips-only tiled) e, com ele, o gatilho
  provável do BUG-12 e o vetor de corrupção dos BUGs 05/06/07/09.

[SDK] vulkan_texture_log=true no device (cvar já existe) para capturar
  created/loaded por textura na próxima sessão e confirmar o formato/endereço
  do diagrama.
```

**(d) Teste de regressão:** tela de controles; com `vulkan_texture_log=true`, identificar a textura do diagrama e verificar que o upload coalescido cobre base+mips; critério visual: diagrama legível (zonas BRAKE/GAS/STEERING…), sem linhas horizontais de ruído.

### BUG-05 — Alfa quebrado na vegetação (faixas pretas serrilhadas)

**(a) Causa raiz — família do BUG-04: cadeia de mips com conteúdo stale/garbage em texturas de recorte (alpha-test).** A vegetação usa texturas com alpha (recorte); quando o LOD>0 amostra mips nunca-uploads/tortos, o alpha-test derruba blocos inteiros → faixas pretas serrilhadas que mudam com a câmera. Sem `vulkan_texture_log` não há linha por textura; o indício direto é o breadcrumb do hang (`crash.log` — loads tiled mips-only em gameplay) e o fato de a corrupção ser LOD-dependente. O Turnip tem BC nativo (`textureCompressionBC: true` no capability report), mas uma divergência no decode BC (ex.: DXT5A máscara de alpha) produz o mesmo sintoma — testável com um cvar existente.

**(b) Trechos do log:** `crash.log` #3328680 (rotina de loads mips-only tiled em gameplay); `all.log` capability report (`BC1_RGBA_UNORM/BC3_UNORM/BC5_UNORM: 1F401` — BC nativo em uso); `config_dump.txt` `vulkan_force_bc_decode = false`.

**(c) Correção mínima:** coalescer mips-only (patch do BUG-04); A/B de diagnóstico: `vulkan_force_bc_decode=true` numa sessão curta — se a vegetação limpar, o decode BC é o vetor e o fallback já existente (`[SDK] texture_cache.cpp:3104-3136`) vira default do Android; se não limpar, permanece o vetor mips.

**(d) Teste de regressão:** câmera fixa na cena do cervo (~rec 0:44) com zoom out/in (varredura de LODs); critério: recorte de alfa limpo em todos os níveis.

### BUG-06 — Texturas de ambiente/animais em resolução baixa / borradas

**(a) Causa raiz — família do BUG-04 + ajuste do usuário já evidenciado nos logs.** A cadeia de mips com conteúdo incorreto/stale faz a GPU amostrar dados de baixa qualidade em LODs médios — smear visível. O usuário já elevou `anisotropic_override` de 3 para 5 (`config_dump.txt: anisotropic_override = 5 | default 3 | source 1`) combatendo o sintoma — a causa não é anisotropia. `force_trilinear_filtering=false` e `texture_mip_lod_bias=0` descartam vieses de sampler configurados.

**(b) Trechos do log:** `config_dump.txt` (`anisotropic_override=5 | source 1`; `force_trilinear_filtering=false`; `texture_mip_lod_bias=0.0`); `crash.log` #3328680 (`load base false` — loads mips-only rotineiros).

**(c) Correção mínima:** coalescer mips-only (BUG-04). Após a correção, re-avaliar: se persistir borrão, A/B `force_trilinear_filtering=true` (cvar existente) para descartar trilinear-vs-bilinear no resolve de superfícies escaladas.

**(d) Teste de regressão:** cena do cervo (rec 0:44–0:48); critério: troncos/terreno nítidos a 2–5 m da câmera; comparação A/B antes/depois do patch na mesma posição.

### BUG-07 — Asfalto com textura corrompida (grade verde, xadrez, ruído)

**(a) Causa raiz — família do BUG-04 (LOD/tile-dependente) agravada por fetch constants inválidos aceitos.** Grade verde fluorescente + xadrez + ruído colorido que **mudam com a câmera** = padrão clássico de mips garbage em textura tiled (o padrão em mips tiled alinhados a 32x32 vira "grade"; blocos não-inicializados viram xadrez). Em gameplay o jogo re-streama mips de terreno (breadcrumb do hang prova loads mips-only tiled rotineiros). Adicionalmente `gpu_allow_invalid_fetch_constants=true` (`config_dump.txt`) permite que fetch constants inválidos do guest sejam processados em vez de neutralizados — vetor secundário de ruído colorido.

**(b) Trechos do log:** `crash.log` #3328680; `config_dump.txt` (`gpu_allow_invalid_fetch_constants = true`; `native_2x_msaa = true`; `draw_resolution_scale_x/y = 1`); `all.log` `vulkan frame 3120/3121/3122/3123 closed: … 0 texture create failures` (não é falha de alocação).

**(c) Correção mínima:** coalescer mips-only (BUG-04) — primária; teste A/B com `gpu_allow_invalid_fetch_constants=false` (cvar existente) numa sessão curta: se a corrupção mudar/sumir, logar os fetch constants rejeitados (hoje o caminho não loga) para mapear o emissor guest.

**(d) Teste de regressão:** câmera na linha de partida com pan lateral (rec 0:50–1:02); critério: asfalto contínuo em todos os ângulos; screenshots A/B antes/depois.

### BUG-08 — Sombras/decals do solo corrompidos (manchas preto-alaranjadas)

**(a) Causa raiz — não fechável por este log; cadeia de profundidade é a suspeita primária.** As estatísticas do executor mostram a cadeia de depth saudável em contadores (`clear_depth=10673`, `depth_overwrite_draw=6689`, `skips={}` — `all.log` 17:23:42.086), ou seja, não há resolve pulado. O resolve de shadow map usa `k_24_8`→`R32_SFLOAT` via shaders DepthUnorm/DepthFloat (docs `textures-formats-resolve.md` §2) — conversão de profundidade em compute, exata no desktop (NVIDIA) e não validada no Turnip. Manchas preto-alaranjadas coerentes com depth decodificado com erro de range/bias. Os decals compartilham a mesma família de resolve.

**(b) Trechos do log:** `all.log` 17:23:42.086 (stats do executor — `resolve_single_owner=80666`, `resolve_empty=13490`, sem skips); `config_dump.txt` (`depth_float24_round=false`, `depth_float24_convert_in_pixel_shader=false` — conversão float24 no resolve, não no PS).

**(c) Correção mínima:**

```
Etapa 1 (diagnóstico): fh1_resolve_dump_dir no resolve de profundidade do
  shadow pass do carro (frame com sombra visível); decodificar o dump como
  R32_SFLOAT DepthUnorm e verificar valores não-físicos (>1, NaN, quantização
  grossa) — se o dump já tem as manchas -> conversão de depth no Turnip;
  se não -> o decal/sombra é consumido errado a jusante (família BUG-04).

Etapa 2 (patch candidado se confirmado no dump): A/B com
  depth_float24_convert_in_pixel_shader=true (cvar existente) — move a
  conversão float24 para o PS do jogo, contornando o shader DepthUnorm do host.
```

**(d) Teste de regressão:** vista de chase cam parada sobre o carro (rec 0:50–0:56); critério: sombra projetada contínua, sem manchas; dump do shadow resolve sem valores anômalos.

### BUG-09 — Texturas de beira de pista erradas (guard-rail teal, sinalização duplicada)

**(a) Causa raiz — família do BUG-04/07 (atlas roadside) + fetch constants inválidos.** Faixas teal/verde-branco no guard-rail e marcas duplicadas/desalinhadas são assinatura de índice de atlas/UV resolvido com dados de textura incorretos (mips/atlas re-streamados) ou de fetch constant com swizzle errado aceito silenciosamente. Mesmas evidências e correções do BUG-07 — mantidas separadas conforme a regra do prompt.

**(b) Trechos do log:** idem BUG-07 (`crash.log` #3328680; `config_dump.txt` `gpu_allow_invalid_fetch_constants = true`).

**(c) Correção mínima:** coalescer mips-only (BUG-04) + A/B `gpu_allow_invalid_fetch_constants=false` — **correções separadas** do BUG-07: aqui o teste decide entre vetor de textura vs vetor de fetch constant pelo resultado visual no guard-rail.

**(d) Teste de regressão:** pan lateral pela beira da pista (rec 0:44–1:02); critério: guard-rail cinza metálico, sinalização única e alinhada.

### BUG-10 — Cockpit: materiais ausentes, superexposição e retrovisor amarelo

**(a) Causa raiz — três sub-mecanismos, dois já ancorados:**
1. **Interior preto sólido** — materiais do interior não amostram conteúdo: família do BUG-04 (mips/tiled garbage ou fetch inválido) — mesmas evidências indiretas;
2. **Superexposição/lens flare atravessando o para-brisa** — família do BUG-03 (LUT de color grading ausente → grading com input vazio; quadro clínico documentado "bloom estourado + cabine escura");
3. **Retrovisor como blob amarelo sólido** — suspeito documentado de troca R/B no resolve do cubo de reflexão `k_2_10_10_10` (pack=1; docs `textures-formats-resolve.md` §4.5-H1): reflexo azulado do céu vira amarelo-laranja com R↔B trocado; o cubo é amostrado pelo material do espelho/painel.

**(b) Trechos do log:** sintomas em rec 1:02 (sem erro de log associado); `all.log` 17:21:53.742/.788 (`FH1 FMV presentation resolve … fmt 7 bias -4` — resolves com exp_bias em uso na superfície de apresentação); capability report (`A2B10G10R10_UNORM: 1FD83` — formato do cubo suportado).

**(c) Correção mínima (separada por sub-sintoma):**

```
1) idem BUG-04 (coalescer mips-only) — materiais do interior;
2) idem BUG-03 (LUT identidade/recópia) — exposição;
3) Dump do cubo: fh1_resolve_dump_dir na vista de cockpit; decodificar as 6
   faces (16px) e conferir canais R/B. Se o dump já está amarelo -> corrigir
   o pack/swizzle no shader de resolve do cubo (regenerar SPIR-V via
   tools/native-shader-pack.py); se o dump é correto e a tela amarela ->
   divergência de amostragem do fetch cube no Turnip (precedente: fix
   9e34a49 de implicit-LOD em cube fetch) — isolar com
   vulkan_capability_report + A/B de LOD explícito no fetch do cubo.
```

**(d) Teste de regressão:** vista de cockpit (rec 1:02); critério: painéis/volante texturizados, exposição controlada, retrovisor com reflexo do céu (azulado) — verificação por dump + screenshot.

### BUG-11 — Viewport/aspect ratio incorreto (barras pretas laterais)

**(a) Causa raiz — configuração atual funcionando como projetado (não é defeito de viewport).** O guest entrega 16:9 estável o tempo todo; o presenter aplica letterbox porque `present_letterbox=true` (default) e o modo ultrawide `pinyon_shift_hor_plus` está desligado; num display 2165x1080 (≈20:9) o 16:9 pilarbox obrigatoriamente. Não há warning de sizing e os 3.073 presents confirmam `1280x720` constante.

**(b) Trechos do log:** `config_dump.txt` (`present_letterbox = true | default true`; `pinyon_shift_hor_plus = false | default false`; `present_safe_area_x/y = 90`); `all.log` 3.073× `XELOG_GPU PRESENT: … packet_size=1280x720 src_size=1280x720 …`.

**(c) Correção mínima:**

```
[repo] src/ui/settings_menu.cpp:307-320 — já existe a linha ASPECT RATIO
  (LETTERBOX/CROP/STRETCH) e ULTRAWIDE (hor+). Ação: expor/ativar ULTRAWIDE
  (pinyon_shift_hor_plus=true) no preset do jogo; opcionalmente mudar o default
  de present_letterbox para auto (letterbox só se razão do display < 16:9).
  Nota de risco documentada: hor+ multiplica a câmera (hook 0x823E22F4,
  docs/NATIVE_PORT_BACKLOG.md NP-4.4) — validar HUD (fh1_hud_squeeze).
```

**(d) Teste de regressão:** menu + gameplay em 2165x1080; critério: conteúdo preenche a largura útil com hor+ ativo sem distorção (stretch) e HUD dentro da área segura; A/B com os três modos de ASPECT RATIO.

### BUG-12 — Retorno abrupto ao launcher (crash silencioso)

**(a) Causa raiz — confirmada ponta a ponta pelos logs: GPU hang do Turnip → VK_ERROR_DEVICE_LOST → FatalError → abort → SIGABRT.** Cadeia completa:
1. `all.log` 17:23:57.523 — `Vulkan Warning ({ General } … tu_knl_kgsl.cc:1817): GPU faulted or hung (VK_ERROR_DEVICE_LOST)` (mensagem do driver Turnip via nosso DebugUtils callback);
2. `crash.log` — breadcrumbs funcionando: `GPU confirmed marker #3328683, last recorded #3329053`; última op confirmada = `texture load 82208 … 512x288x1 pitch 16 tiled true scaled false mips 3 packed true base 14120000 mip 141B0000 load base false` (marcadores #3328680–83 done); suspeitos = draws 899–1021 do frame 3124 (pass `0x38E`);
3. `crash.log` — `Pinyon Shift fatal signal signal=sigabrt fault_address=0x000028B50000444F` — o abort do `rex::FatalError` (`[SDK] graphics_system.cpp:315-330` → `include/rex/assert.h:119-124`), capturado e reportado pelo `[repo] src/crash_reporter_posix.cpp:109-155`;
4. Contexto imediato: frame 3115 pulado com **224 placeholder draws** (17:23:56.336 — tempestade de compilação de PSOs) e frame 3123 com **20 texturas criadas** (streaming de materiais novo).

O gatilho provável do hang é o mesmo caminho do BUG-04 (upload tiled mips-only) sob tempestade de PSOs — mas a correção do BUG-12 é própria: mitigar a tempestade e transformar o abort em saída graciosa.

**(b) Trechos do log:** ver (a) — `all.log` 61333/61335 e `crash.log` completo.

**(c) Correção mínima (três frentes independentes):**

```
F1 — Limitar a tempestade de PSOs (mitigação do gatilho):
[SDK] pipeline_cache.cpp — limitar vkCreateGraphicsPipelines em voo por frame
  (cvar vulkan_pipeline_creation_burst, default 64): o worker de criação só
  enfileira novos pipelines quando os em voo < limite; frame 3115 com 224
  placeholders não se repete. Complemento de médio prazo: .pnsp com os 371
  PSOs do cold boot (infra já existe — pipeline_storage_file_,
  pipeline_cache.cpp:1428-1439) eliminando a compilação runtime.

F2 — Eliminar o caminho suspeito do hang: coalescer mips-only (patch BUG-04).

F3 — Saída graciosa em vez de abort silencioso:
[repo] src/pinyon_shift_app.cpp:461-472 + platform::ExitImmediately(1306)
  O FatalError de GPU loss já passa por ShowFatalError; no Android, antes do
  _exit, escrever a mensagem ("GPU error — returning to launcher") no realtime
  crash.log e sinalizar a Activity via arquivo de estado, para o
  GamePickerActivity exibir toast/dialog "O jogo parou: erro de GPU (sessão
  salva nos logs)" no retorno — hoje o usuário volta sem contexto.
  (O abort nativo permanece como fallback; a mensagem é o mínimo viável.)
```

**(d) Teste de regressão:** mesma rota (boot → cinemáticas → gameplay 2+ min com câmera na linha de partida); critérios: (i) nenhum frame com >64 placeholders no log; (ii) sem `DEVICE_LOST` em 10 min; (iii) induzindo perda (N/A em device) — validar F3 pelo crash.log já existente: nova sessão deve conter a mensagem de retorno.

---

## 3. Causas raiz compartilhadas (correções permanecem separadas)

| Família | Mecanismo (com evidência de log) | Bugs alcançados |
|---|---|---|
| **A — Descarte de frame por pipeline placeholder** | 48 frames descartados (`Skipping Vulkan frame presentation`, contadores 1→48); janelas 17:23:03–31 coincidem com as telas pretas 0:10–0:38 | 01 (principal), 02 (prolonga artefatos), contexto do 12 |
| **B — Caminho de upload tiled + mips packed (loads mips-only)** | Última op de GPU antes do hang = `texture load 82208 … tiled true … packed true … load base false` (`crash.log`); 0 falhas de criação/alocação; padrões de corrupção LOD-dependentes (grade/xadrez/faixas) | 04, 05, 06, 07, 09, 10.1 (materiais), 12 (gatilho provável do hang) |
| **C — Pós-processo guest: LUT de color grading + swizzle do cubo de reflexão** | Quadro clínico documentado (estourado+escuro) sem erros de host; resolve do cubo pack=1 com precedente de swizzle | 03, 10.2 (exposição), 10.3 (retrovisor) |
| **D — Configuração de apresentação (letterbox)** | `present_letterbox=true` + `hor_plus=false` + display 20:9; guest 16:9 estável | 11 |

---

## 4. Ordem de implementação sugerida (maior impacto por esforço)

1. **Família B** — cvar `vulkan_texture_load_coalesce_mips` (patch pequeno no SDK; ataca 5 bugs visuais + provável gatilho do hang).
2. **Família A** — re-apresentar último guest output + `vulkan_async_pipeline_wait_ms=500` (mata as telas pretas).
3. **BUG-12 F1** — burst limit de criação de pipelines (+ .pnsp depois).
4. **Família C** — LUT identidade repo-side (falta confirmar ausência no device).
5. **BUG-02/08/10.3** — instrumentação (`fh1_resolve_dump_dir`, `vulkan_texture_log=true`) para fechar as 3 causas abertas com 1 sessão nova.
6. **BUG-12 F3** — mensagem de retorno ao launcher.
7. **BUG-11** — default/exposição do hor+ na UI de settings.

## 5. Lacunas de evidência (o que a próxima sessão deve capturar)

1. `vulkan_texture_log=true` + `fh1_texture_reload_probe=true` → mapa textura-a-textura do diagrama (04) e da vegetação (05).
2. `fh1_resolve_dump_dir` em 2 frames da tela de controles (02) e no shadow pass do carro (08) + vista de cockpit (10.3).
3. A/B curtos: `vulkan_force_bc_decode=true` (05), `gpu_allow_invalid_fetch_constants=false` (07/09), `depth_float24_convert_in_pixel_shader=true` (08), `vulkan_async_pipeline_wait_ms=500` (01).
4. Conferência no device de `media/dynamicpost/colourgradingmaps/` no game root (03/10.2) — e o log do picker com os 3 avisos de LUT.

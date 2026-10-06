# Investigação I3 — Texturas, formatos, resolve/EDRAM e pós-processamento

Task ID: 3-c · Agente: Investigador I3 · Data: 2026-10-06
Branch: `fix/android-rendering-fmv-textures-20261004` (SDK pin `74a1de0`)
Device: Motorola Edge 30 Fusion, Adreno 660, Turnip Mesa 26.3.0-devel (via adrenotools), escala de resolução padrão 1x (`src/ui/settings_menu.cpp:258`).

Convenção de caminhos: `SDK/` = `thirdparty/shiftglue-sdk/`. Toda afirmação de código tem `arquivo:linha`.

---

## 1. Síntese executiva

1. **O caminho ativo no Android não é o render_target_cache genérico**: `vulkan_fh1_native_executor` e `vulkan_dynamic_rendering` ligam por padrão (`SDK/src/graphics/vulkan/command_processor.cpp:115,125`) e o caminho de host render targets é mantido no Turnip porque R16G16B16A16_SNORM como attachment cai no fallback SFLOAT em vez de trocar para fragment shader interlock (`SDK/src/graphics/vulkan/render_target_cache.cpp:289-299`). Todos os resolves do jogo passam por `Fh1NativeExecutor::NativeResolve` (`command_processor.cpp:5282-5285`), que usa **posse de tiles EDRAM** (`SDK/include/rex/graphics/fh1_edram_tiles.h:17-223`) e resolve por compute direto na memória compartilhada (`SDK/src/graphics/vulkan/fh1_native_executor.cpp:2014-2085`).
2. **O texto de menu NÃO é textura de fonte**: FH1 desenha glifos como malhas vetoriais (um draw por glifo, shader VS `9C19E3CACBE2E342` / PS `D7524D5CA740AAE5`, "no glyph cache texture" — `docs/UI_ASSETS.md:106-126`). A hipótese "corrupção de decode de textura de fonte (A8/DXT)" está **refutada** para o texto do menu; a corrupção deve vir do RT onde o texto é compsitalizado (UI target 1280×720 formato 10 = `k_2_10_10_10`, `docs/native-renderer/XENOS_RETIREMENT_BACKLOG.md:876-878`) ou da tradução do shader de cobertura vetorial no Turnip.
3. **A LUT de color grading é uma textura 3D `k_8_8_8_8` 16³** lida de `game:\media\dynamicpost\colourgradingmaps\` (`docs/native-renderer/NATIVE_FRAME_CONTRACT.md:162,178-181`). Com os arquivos ausentes, o jogo continua renderizando com o input de LUT vazio — o quadro clínico documentado (highlights lavados/estourados + painéis quase pretos, `docs/rendering-fixes-20261004.md:86-92`) casa com "bloom/HDR estourado + cabine escura", mas **não** explica amarelo sólido localizado no espelho/painel.
4. **"Partial updates" do resolve `k_16_16_16_16`** é real e documentado: o pós-processo temporal usa uma superfície padded 1280×736 atualizada **256 linhas por frame** com destinos históricos alternantes (`docs/native-renderer/XENOS_RETIREMENT_BACKLOG.md:866-869`), e a cena 3D principal renderiza em **3 bandas EDRAM de 256/256/208 linhas** com viewport/scissor por banda (`:855-862`). Sub-rects de resolve + tiles não possuídos (`Skip("resolve_tiles_unowned")`, `fh1_native_executor.cpp:2340-2343`) são os suspeitos estruturais para faixa do topo, listras inferiores e linhas da esquerda.
5. **AHB 4x4 / format 59/56 / "AdrenoUtils: Unknown Format 0" não vêm do nosso código**: não existe nenhuma alocação de `AHardwareBuffer` no repo nem no SDK (único uso de ANativeWindow é leitura de width/height: `SDK/src/ui/surface_android.cpp:23-24`, `SDK/src/ui/window_sdl.cpp:471`). As tags são da stack Android/EGL (libui `GraphicBufferAllocator`, `Gralloc4`, `libadreno_utils`). Formatos 56/59 são inteiros de PixelFormat do gralloc (não Vulkan). Ruído de probe do stack de driver; não correlaciona com nenhum sintoma visual.
6. O fallback de **YUV 4:2:2 (formato 37 em vez de 1000156000)** do log é o caminho esperado no Turnip (sem sampling 422 linearmente filtrável → decode para RGBA8 no load; `SDK/src/graphics/vulkan/texture_cache.cpp:3008-3032`), mesmo mecanismo do log "Format k_5_6_5 ... fallback" etc. (log de startup em `:3304-3342`).

---

## 2. Tabela de mapeamento de formatos Xenos → Vulkan

Tabela-mestre: `kBestHostFormats[64]` em `SDK/src/graphics/vulkan/texture_cache.cpp:143-417`. Fallbacks decididos em `Initialize()` (`:2957-3372`). Formatos relevantes para o FH1 (censo em `docs/native-renderer/NATIVE_FRAME_CONTRACT.md:143-168`):

| Xenos (fetch) | Vulkan preferido | Fallback (condição) | Onde decide | Implicação |
|---|---|---|---|---|
| `k_8` (planos de vídeo FMV) | `R8_UNORM` (shader 8bpb, swizzle RRRR) | — | `:148-152` | Caminho rápido CPU (FMV) em `:1348-1706` |
| `k_8_8_8_8` / `k_8_8_8_8_AS_16_16_16_16` | `R8G8B8A8_UNORM` (32bpb) | — (sempre suportado) | `:168-172`, `:355-359` | Texturas XDS de UI (2.671 de 4.373, `docs/UI_ASSETS.md:60`); endian 8-in-32 e swizzle ZYXW tratados no load compute (`:2049-2052`) |
| `k_DXT1`(+`_AS_16_16_16_16`) | `BC1_RGBA_UNORM_BLOCK` (64bpb, BC) | decode `DXT1ToRGBA8`→RGBA8 se BC não filtrável ou `vulkan_force_bc_decode` | `:228-232`, `:3104-3114` | 237 texturas UI (`docs/UI_ASSETS.md:62`) |
| `k_DXT2_3`(+AS) | `BC2_UNORM_BLOCK` (128bpb) | decode `DXT3ToRGBA8` | `:233-237`, `:3115-3125` | 7 texturas UI |
| `k_DXT4_5`(+AS) | `BC3_UNORM_BLOCK` (128bpb) | decode `DXT5ToRGBA8` | `:238-242`, `:3126-3136` | 1.251 texturas UI (`docs/UI_ASSETS.md:61`) — maior grupo BC |
| `k_DXT5A` | `BC4_UNORM_BLOCK` | decode `DXT5AToR8` | `:398-402`, `:3147-3156` | 47 (map masks, um canal replicado RRRR) |
| `k_DXT3A`(+AS_1_1_1_1) | `R8_UNORM` / `B4G4R4A4` via shader `DXT3A` | RGBA4→RGBA8 se B4G4R4A4 não filtrável (MoltenVK) | `:394-397`, `:407-410`, `:3044-3056` | 6 texturas de máscara |
| `k_DXN` | `BC5_UNORM_BLOCK` (128bpb) | decode `DXNToRG8` | `:350-354`, `:3137-3146` | 703.883 fetches (normal maps) |
| `k_2_10_10_10`(+AS_16_16_16_16) | `A2B10G10R10_UNORM_PACK32` (32bpb) | — (assume suporte) | `:173-177`, `:375-379` | Cubo de reflexão (5,8 M fetches cube, `NATIVE_FRAME_CONTRACT.md:151-152`) e cena final |
| `k_24_8` / `k_24_8_FLOAT` | `R32_SFLOAT` via shader DepthUnorm/DepthFloat (paridade D3D12) | — | `:246-259` | Shadow maps resolvidos |
| `k_32_FLOAT` | `R32_SFLOAT` | — | `:315-319` | post chain |
| `k_16_16_16_16_FLOAT` | `R16G16B16A16_SFLOAT` | — | `:304-308` | RTs HDR |
| `k_16_16_16_16` (textura) | `R16G16B16A16_UNORM`/`SNORM` (64bpb) | → `R16G16B16A16_SFLOAT` com conversão two-pass (UNORM/SNORM não filtráveis) | `:272-278`, `:3229-3247`, two-pass `:1760-1785` | **Frontbuffer do FMV/presentation** (resolve pack=5, `SDK/src/graphics/fh1_edram_resolve.cpp:131-137`) |
| `k_10_11_11`/`k_11_11_10`(+AS) | `R16G16B16A16_UNORM/SNORM` via shader | → SFLOAT two-pass | `:216-227`, `:3057-3091` | — |
| `k_Cr_Y1_Cb_Y0_REP` | `G8B8G8R8_422_UNORM` (=1000156000) | → decode `GBGR8ToRGB8`/RGBA8 (37) se 422 não filtrável | `:190-197`, `:3008-3020` | **Linha do log do device** |
| `k_5_6_5` / `k_4_4_4_4` | `R5G6B5` / `B4G4R4A4` | → expandir p/ RGBA8 no load | `:158-162`, `:211-215`, `:3033-3056` | — |
| Sem suporte (`k_1_REVERSE`, `k_32`, MPEG*, EDRAM*, `k_32_AS_8`...) | `VK_FORMAT_UNDEFINED` | binding nulo → **fallback de fetch inválido: imagem R8G8B8A8 zerada** | `:144-147`, `:310-313`..., `:131-136` | Elemento some/fica preto (sintoma 3) |

Formatos de **RT** (render target), caminho separado (`SDK/src/graphics/vulkan/render_target_cache.cpp:1665-1698`): `k_2_10_10_10`→`A2B10G10R10_UNORM_PACK32` (`:1673-1676`), `k_16_16_16_16`→SNORM com **fallback SFLOAT no Turnip** (`:1683-1685` + aviso `:289-299`), `k_8_8_8_8_GAMMA`→`R16G16B16A16_UNORM` se `gamma_render_target_as_unorm16` (`:1670-1672`, `:320-335`).

**Onde swizzle/tiling/endian são tratados no upload**: não há "swizzle CPU" — o upload é um compute shader por formato que lê a memória guest crua (storage buffer) e escreve um scratch buffer decodificado; tiling/endian/escala vão em push constants (`is_tiled_3d_endian_scale`, `SDK/src/graphics/vulkan/texture_cache.cpp:2049-2052`), pitch em blocos (`:2076-2085`), offsets por nível/mip empacotado (`:2069-2073`, `:2088-2099`), e o swizzle de componentes é aplicado no **image view** (`GetViewUncached :2550-2553`). A cópia scratch→imagem é `vkCmdCopyBufferToImage` por nível (`:2339-2391`; um comando por nível para evitar mosaic multi-região no Turnip — comentário `:2333-2338`). No Android, `vulkan_texture_load_compute_copy` é **off** (evita perder UBWC com storage writes; `:39-46`), então o caminho é sempre o copy engine.

**Textura de "fonte" do menu**: inexistente (vetorial, §1.2). O logo "FORZA HORIZON PRESS START" limpo e o texto ruidoso diferem porque o logo é outra passada/outro RT (ou desenhado antes da janela de corrupção), não porque uma textura decodifica e outra não.

---

## 3. Mapa do fluxo de resolve / EDRAM (arquivo:linha)

Constantes: tiles EDRAM de **80×16 samples**, **2048 tiles**, 10 MB (`SDK/include/rex/graphics/xenos.h:411-415`); pitch em tiles via `GetSurfacePitchTiles` (`:423-431`).

```
PM4/XE_GPU_PACKET_TYPE_COPY (resolve do guest)
└─ VulkanCommandProcessor::IssueCopy           SDK/src/graphics/vulkan/command_processor.cpp:5269-5295
   ├─ se fh1_native_executor_ (default ON, :115):
   │  └─ Fh1NativeExecutor::NativeResolve      SDK/src/graphics/vulkan/fh1_native_executor.cpp:2098-2113
   │     ├─ Resolve(...)                        :2246-2468
   │     │  ├─ Fh1PlanResolve                  SDK/src/graphics/fh1_edram_resolve.cpp:75-169
   │     │  │  ├─ Fh1ResolveRectangle          :11-53   (rect do draw, clamp scissor/pitch, align 8px)
   │     │  │  ├─ GetResolveInfo               SDK/src/graphics/util/draw.cpp:~950-1188
   │     │  │  │   (extent destino tiled 2D/3D :1022-1087, exp_bias :1111-1154, TRACE :1177-1185)
   │     │  │  └─ packs: depth=4; 8_8_8_8=0; 2_10_10_10=1; 32_FLOAT=2; 16f=3; 16_16_16_16=5  :112-143
   │     │  ├─ GetResolveSources               fh1_native_executor.cpp:1411-1429
   │     │  │  └─ Fh1EdramTiles::SplitByOwner  SDK/include/rex/graphics/fh1_edram_tiles.h:146-192
   │     │  │     (rect de pixels por DONO de tile; kNoOwner → source.unowned)
   │     │  ├─ por source: ResolveToMemory     fh1_native_executor.cpp:2014-2085
   │     │  │  (compute fh1_native_resolve_memory_*_cs, dispatch 8×8, escreve memória guest
   │     │  │   em dest_base/dest_pitch com dest_info: pack|endian|swap|bias  :157-163 do plan)
   │     │  │  ├─ unowned → Skip("resolve_tiles_unowned")  :2340-2343
   │     │  │  └─ owner formato não suportado → Skip("resolve_owner_format")  :2346-2351
   │     │  ├─ MarkRangeAsResolved(extent)     :2388  → invalida texturas no range
   │     │  │                                    SDK/src/graphics/pipeline/texture/cache.cpp:385-414
   │     │  │                                    (RangeWrittenByGpu :413 — marca páginas como GPU-written)
   │     │  └─ clears: ClaimTiles + ClearSurfaceRect  :2416-2466 (Claim/CaimRect: fh1_edram_tiles.h:44-133)
   │     ├─ readbacks one-off (car thumbnails)  :2104-2106, 2153-2212
   │     └─ dump fh1_resolve_dump_dir           :2107-2111, 2115-2151
   └─ senão: VulkanRenderTargetCache::Resolve   render_target_cache.cpp:1080-1320
      ├─ DumpRenderTargets (RT→edram_buffer_)   :1119-1131, 5900-6031
      ├─ resolve_copy_pipelines_[shader]        dispatch :1205-1228
      ├─ MarkRangeAsResolved                    :1231-1232
      └─ clears (host RT ou FSI)                :1243-1317

Presentation (swap)
└─ VulkanCommandProcessor::...Swap              command_processor.cpp:2740-2900+
   ├─ texture_cache_->RequestSwapTexture        SDK/src/graphics/vulkan/texture_cache.cpp:1035-1099
   │  (FindOrCreateTexture + LoadTextureData do frontbuffer guest; FMV swap log command_processor.cpp:2800-2812)
   ├─ get_active_swap_dimension (região ativa vs padding) :2816-2847
   └─ passada de gamma/FXAA + composição no presenter :2921-3080
```

**"Partial updates" do `k_16_16_16_16`**: o FMV/intro resolve a renderização float16 para uma textura `k_16_16_16_16` para apresentação (comentário `SDK/src/graphics/fh1_edram_resolve.cpp:131-137`); o rect do resolve vem do draw (clampado ao scissor, `fh1_edram_resolve.cpp:35-52`), e o pós-chain tem uma superfície temporal 1280×736 **atualizada 256 linhas por frame, republicada a cada 2 frames, com destinos alternantes** (`docs/native-renderer/XENOS_RETIREMENT_BACKLOG.md:866-869`). Ou seja: resolves sub-rect são normais no jogo; cada resolve escreve só o extent calculado (`draw.cpp:1068-1087`) e o resto da memória guest retém o frame anterior. O log dedicado de cada setup distinto do resolve de apresentação é `FH1 FMV presentation resolve: ...` (`fh1_native_executor.cpp:2271-2298`).

---

## 4. Hipóteses ranqueadas por sintoma

### 4.1 Faixa glitchada multicolor no TOPO (menus, controles, jogo)

| # | Hipótese | Evidência (arquivo:linha) | Confirmar / refutar | Correção (esboço) |
|---|---|---|---|---|
| H1 | **Buraco remanescente da retenção de snapshot FMV** (zero-tail/torn) — a faixa do topo é o topo do plano `k_8` re-baselineado; os fixes 05bab12/74a1de0 cobriram os casos steady-state e "banda parou de mover", mas o log do device atual precisa mostrar qual classificação dispara | Fast path `k_8`: `SDK/src/graphics/vulkan/texture_cache.cpp:1348-1706`; cvars `fh1_fmv_debug`/`fh1_fmv_retain` `:56-64`; histórico em `docs/rendering-fixes-20261004.md:13-57` | Rodar menus com `fh1_fmv_debug=1` (já default) e procurar: `fh1 fmv partial snapshot with zero tail retained...`, `plane load refused (GPU-written pages)` (`:1424-1432`), `torn snapshot retained ... boundary X/128`. Se a faixa aparece SEM nenhuma dessas linhas → H1 refutada p/ menus | Estender a classificação baseline-diff ao caso que o log revelar; retenção do último frame completo para o caso faltante |
| H2 | **Resolve de apresentação `k_16_16_16_16` sub-rect + superfície temporal 256 linhas/frame**: a textura do swap (1280×736 padded, 720 ativas) é recarregada inteira a cada swap (`RequestSwapTexture` → `LoadTextureData`), mas apenas parte foi resolvida neste frame; linhas não resolvidas retêm conteúdo antigo — se a ordenação/barreira entre o compute do resolve e o load da textura falhar no Turnip, linhas do topo mostram mistura de frames (glitch multicolor) | Resolve sub-rect: `SDK/src/graphics/fh1_edram_resolve.cpp:24-52`; superfície temporal: `docs/native-renderer/XENOS_RETIREMENT_BACKLOG.md:866-869`; load do swap: `command_processor.cpp:2793-2795` + `texture_cache.cpp:1035-1099`; barreiras do load: `texture_cache.cpp:2020-2033` | `fh1_resolve_dump_dir` (`fh1_native_executor.cpp:2107-2111`) em 2 frames seguidos na tela de controles: comparar linhas do topo entre dumps; se o dump já contém a faixa → H2 forte; se o dump está limpo mas a tela não → composição/presenter | Reter o frame completo do swap quando o rect resolvido for parcial (estender a retenção de FMV ao presentation resolve, como o doc já antecipa em `docs/rendering-fixes-20261004.md:131-135`) |
| H3 | **Região ativa vs padding do swap** (736 vs 720): amostragem das linhas de padding/última fileira de tiles da textura de swap | `get_active_swap_dimension` `command_processor.cpp:2816-2847`; warn de sizing `:2867-2876` | Log `Vulkan draw-scale swap sizing: packet=... src_...=... active=...` — conferir packet 1280×736 vs active 1280×720; correlacionar altura da faixa com 16 linhas ou 1 fileira de tile (16 samples) | Clamp de UV/extent na passada de gamma ao active rect |

### 4.2 Texto de menu CORROMPIDO (SINGLE PLAYER/MULTIPLAYER com ruído; logo limpo)

| # | Hipótese | Evidência | Confirmar / refutar | Correção (esboço) |
|---|---|---|---|---|
| H1 | **Corrupção do RT de UI (k_2_10_10_10, 1280×720, ~166 draws; frames sem pass de UI mantêm HUD anterior)** — mesmos mecanismos de 4.1 (resolve sub-rect/tiles) atingindo o alvo de UI; o texto em si é geometria correta | Vetorial: `docs/UI_ASSETS.md:106-126`; UI target: `docs/native-renderer/XENOS_RETIREMENT_BACKLOG.md:876-878`; RT `k_2_10_10_10`→A2B10G10R10: `render_target_cache.cpp:1673-1676` | Dump do resolve que escreve o UI target (`fh1_resolve_dump_dir`); se o dump tem glifos limpos e a tela não → composição; se o dump já tem ruído → draw/RT (H2) | Depende de 4.1 |
| H2 | **Bug do shader de cobertura vetorial no Turnip** (f = u²−v, d = f·s/|∇(f·s)|, coverage = saturate(0.5−d)): precisão/reciprocal do gradiente no Adreno gera ruído dentro dos glifos | Regra de cobertura: `docs/UI_ASSETS.md:119-124`; PS `D7524D5CA740AAE5` `:108-110`; replicação CPU `src/ui/hostui/vector_font.cpp` | Capturar um frame e rodar no Windows/Vulkan (mesmo backend, GPU NVIDIA) — se limpo lá e sujo no Adreno → driver/precisão; ou substituir o PS traduzido por um que compute | Corrigir a tradução SPIR-V (ex.: `inverseSqrt` vs `rcp` de magnitude), ou subir precisão intermediária do gradiente |
| H3 | Interleaving de bandas EDRAM na cena (não aplicável a menus) — refutável porque o sintoma aparece em menu (sem bandas 3D) | — | — | — |

### 4.3 Cantos/regiões pretas e UI faltando (fundo quase preto, setas/ícones ausentes)

| # | Hipótese | Evidência | Confirmar / refutar | Correção (esboço) |
|---|---|---|---|---|
| H1 | **Plano de vídeo `k_8` recusado por páginas GPU-written**: o resolve de apresentação marca as páginas do frontbuffer como escritas por GPU (`MarkRangeAsResolved` → `RangeWrittenByGpu`), e o fast path FMV **recusa** o load (`CopyCpuRange` falha) deixando o plano antigo/preto — fundo do menu quase preto com logo limpo | Recusa: `texture_cache.cpp:1419-1435` (log `fh1 fmv plane load refused (GPU-written pages)`); invalidação: `pipeline/texture/cache.cpp:385-414`; superfícies com "several guest formats per address" no pós-chain: `XENOS_RETIREMENT_BACKLOG.md:866-869` | Contar linhas `plane load refused` no log durante um menu; se 100% recusado → H1 confirmada | Permitir o snapshot CPU de páginas GPU-written quando o range foi resolvido há >1 frame, ou separar ownership por formato/endereço no pós-chain |
| H2 | **Texturas XDS que falham na criação → binding nulo → fallback preto**: formatos não suportados (`k_1_REVERSE` etc.) ou `vmaCreateImage` falho; a UI do jogo usa `k_8_8_8_8_AS_16_16_16_16`/DXT (decode endian+swizzle) | Fallback de fetch inválido (imagem zerada): `texture_cache.cpp:131-136`; nullptr sem formato: `:1195-1198`; log de falha de alocação (c7c490d): `:1293-1305`; relatório de formatos não suportados por frame: `:570-585`; formatos UI: `docs/UI_ASSETS.md:60-66` | Procurar no log do device: `Unsupported texture formats used in the frame`, `VulkanTextureCache: Failed to allocate ... texture image`, e a tabela de fallbacks do startup (`:3304-3342`) | Caso a caso: mapear o formato sem suporte para um decode (como RGBA4→RGBA8) |
| H3 | Setas/ícones em `k_DXT5A`/`k_DXT3A` (um canal RRRR): se o load BC native (BC4) do Turnip divergir no untile, ícones somem | Tabela `:398-410`; decode alternativo existe (`kHostFormatDXT5AUnaligned :460-464`) | `vulkan_force_bc_decode=1` (força decode por compute, `:47-50`) e comparar screenshots | Se o decode resolve, ativar decode BC por padrão no Android (custo de memória) |

### 4.4 Jogo 3D: listras/dither VERDES inferiores + linhas coloridas na borda ESQUERDA

| # | Hipótese | Evidência | Confirmar / refutar | Correção (esboço) |
|---|---|---|---|---|
| H1 | **Tiles não-possuídos nas bordas das 3 bandas EDRAM da cena** (256/256/208 linhas, viewport/scissor por banda, target 1280×512 4xMSAA): a última fileira de tiles (inferior) e a primeira coluna (esquerda) ficam sem dono → `Skip("resolve_tiles_unowned")` → conteúdo stale/zero nessas faixas | Bandas: `XENOS_RETIREMENT_BACKLOG.md:855-862`; SplitByOwner + kNoOwner: `fh1_edram_tiles.h:146-192`; skip: `fh1_native_executor.cpp:2340-2343`; geometria do tile 80×16: `xenos.h:411-412` (a 4x MSAA, 1 tile ≈ 40 px de largura — casa com "linhas na esquerda") | LogStats do executor imprime contadores de skip a cada 600 frames (`fh1_native_executor.cpp:2470-2476`); procurar `resolve_tiles_unowned`; correlacionar largura das linhas esquerdas com 40 px | Claim "speculativo" das tiles do rect da banda na ausência de dono (com clear neutro), ou fallback de leitura do conteúdo anterior do EDRAM |
| H2 | **Padrão "verde" do próprio guest com input de vídeo ausente/zero** (YUV zeros → verde (0,77,0); 0xFF chroma → rosa) — se alguma superfície de vídeo/overlay do jogo fica sem conteúdo (arquivo ausente: `AMB_Redstone.fsb` 0xc000000f no log) | Fenômeno documentado: `docs/NATIVE_PORT_BACKLOG.md` NP-2.9 ("YUV planes of zero convert to green"); FMV_04 loop gap: `:279`; arquivo ausente no log do device (worklog) | Reproduzir com o arquivo presente / comparar com D3D12 Windows na mesma cena; se o verde some com arquivo → guest-side | NP-2.9 (tratar mídia ausente como terminada) + recopia da mídia |
| H3 | Vazamento de leitura no load de textura escalada (não se aplica a 1x default) | Fix prévio de overread 21dfe23 mencionado em NP-12.4 | Confirmar escala=1 no device (`RESOLUTION SCALE` default 1, `src/ui/settings_menu.cpp:258`) | — |

### 4.5 Espelho retrovisor e painel AMARELOS sólidos; bloom/HDR estourado; cabine escura

| # | Hipótese | Evidência | Confirmar / refutar | Correção (esboço) |
|---|---|---|---|---|
| H1 | **Erro de empacotamento/swizzle no resolve do cubo de reflexão `k_2_10_10_10` (pack=1)**: um R/B trocado no pack/unpack (ou `copy_dest_swap` mal aplicado — bit 6 de dest_info) transforma reflexo azulado do céu em amarelo-laranja sólido; o painel compartilha o mesmo env/reflection input. O desktop validou o cubo idêntico ao D3D12 (NP-12.4), mas com driver NVIDIA — Turnip pode expor divergência no sampling `A2B10G10R10` do cubo (3D/cube fetch com derivadas já foi bug histórico: fix 9e34a49 implicit LOD) | Packs: `fh1_edram_resolve.cpp:117-143` (pack=1 p/ `k_2_10_10_10`; bit swap `:158-159`); shader resolve compute pré-compilado: `fh1_native_executor.cpp:256-269`; sampling cube com LOD implícito (fix anterior): NP-12.4 em `docs/NATIVE_PORT_BACKLOG.md:500`; RT/view `A2B10G10R10`: `render_target_cache.cpp:1673-1676` | `fh1_resolve_dump_dir` na cena do espelho: decodificar o dump do cubo (16 px por face) e conferir R↔B (trocar canais no decoder de conferência); amarelo no dump → resolve/pack; dump correto + tela amarela → sampling/tradução do fetchCube no Turnip | Corrigir o pack/swizzle no shader resolve (ou o view do cubo); re-gerar SPIR-V (tools/native-shader-pack.py) |
| H2 | **LUT de color grading ausente** (pasta `colourgradingmaps` vazia — 3 avisos no log): explica **bloom/HDR estourado e cabine escura** (grading com input vazio), mas amarelo *sólido e localizado* é atípico de grading (que é full-screen) | Aviso do picker: `android/app/src/main/java/dev/pinyon/shift/GamePickerActivity.java:198-221`; quadro clínico esperado: `docs/rendering-fixes-20261004.md:86-92`; LUT = 16³ `k_8_8_8_8` 3D: `docs/native-renderer/NATIVE_FRAME_CONTRACT.md:162,181` | **Teste decisivo**: recopiar `media/dynamicpost/colourgradingmaps` e comparar. Se bloom/cabine normalizam e o amarelo persiste → amarelo é H1; se tudo normalizar → H2 explica tudo | Ver 4.5.1 abaixo (fallback de LUT neutra) |
| H3 | Clear mal interpretado no RT de baixa resolução do painel (formato trocado) — menor probabilidade; clear values vêm de RB_COLOR_CLEAR e são aplicados por `ClearSurfaceRect` | `fh1_native_executor.cpp:2416-2466` | Dump do resolve do RT do painel | Corrigir pack do clear |

**4.5.1 Fallback de LUT neutra (onde ficaria)**: o carregamento é inteiramente guest-side (o título abre `game:\media\dynamicpost\colourgradingmaps\*` via NtCreateFile; com arquivo ausente o objeto de textura 3D nunca é preenchido — a memória fica com o que lá estiver). Dois pontos de hook:
- **Repo-side (preferencial)**: o observador de abertura de arquivos `PinyonShiftObserveGuestFileOpen` (`src/pinyon_shift_runtime_hooks.cpp:583-610`, registrado no observer NtCreateFile/NtOpenFile) já vê os caminhos guest; ao detectar abertura **falha** sob `media/dynamicpost/colourgradingmaps/`, injetar um arquivo sintético com LUT identidade 16³ `k_8_8_8_8` usando o caminho de re-encode XDS que já existe (a UI do host já re-encodeia PNG→armazenamento guest, `docs/UI_ASSETS.md:69-72`, NP-10.3). Alternativa: gerar os bytes da LUT identidade e escrever na memória guest no endereço que o jogo alocou (via hook de criação de textura 3D).
- **SDK-side (secundário)**: `VulkanTextureCache::CreateTexture` (`texture_cache.cpp:1172`) poderia detectar textura 3D `k_8_8_8_8` 16³ com memória guest toda-zero e preencher com LUT identidade — heurística frágil (não distingue LUT legítima preta), usar só atrás de cvar de diagnóstico.

### 4.6 (peripheral) "Unknown Format 0" + AHB 4x4 format 59/56 + "Gralloc4 isSupported failed"

- **Nenhuma origem no nosso código**: zero ocorrências de `AHardwareBuffer`/`ahb` no repo e no SDK (verificado por grep em `src/`, `android/`, `SDK/src/`, `SDK/include/`); o único toque em janela nativa é `ANativeWindow_getWidth/getHeight` (`SDK/src/ui/surface_android.cpp:23-24`) e o cast do SDL window (`SDK/src/ui/window_sdl.cpp:471`). O swapchain usa `VK_KHR_android_surface` (`SDK/src/ui/vulkan/vulkan_instance.cpp:169-170`) e o driver custom é carregado por `adrenotools_open_libvulkan` (`SDK/src/ui/vulkan/android_gpu_driver.cpp:130-132`).
- As tags de log pertencem à stack Android/EGL: `GraphicBufferAllocator`/`Gralloc4` são da **libui**; `AdrenoUtils` é a **libadreno_utils.so** (driver GLES do sistema). Formatos "59"/"56" são inteiros de `PixelFormat` do gralloc (não enum Vulkan — VK_FORMAT 59 = `A2R10G10B10_SINT_PACK32`, irrelevante aqui; 1000156000 do outro log é `VK_FORMAT_G8B8G8R8_422_UNORM`). Alocação 4×4 com formato exótico é o padrão de um **probe de suporte de formato** (dummy buffer), não de uma superfície real. Hypothesis mais provável: probe do stack EGL/driver do sistema (a Activity Java/picker renderiza via HWUI→GLES→adreno_utils) ou do WSI. 
- **Como confirmar**: correlacionar timestamp com o início da Activity; verificar se as linhas se repetem por frame (não: one-shot de startup → ruído). Nenhuma ação de renderer é necessária; não correlaciona com os sintomas visuais (a alocação falha e o chamador cai no fallback do probe).

---

## 5. Pontos de hook para a Tarefa A (log por textura na criação)

Objetivo: log único por textura criada com endereço guest, formato Xenos, formato Vulkan escolhido, fallback?, tiling, endian, tamanho, mips, hash.

| O quê | Onde (arquivo:linha) | Notas |
|---|---|---|
| Log de criação (melhor ponto único) | `VulkanTextureCache::CreateTexture` após seleção de formatos (`SDK/src/graphics/vulkan/texture_cache.cpp:1172-1198`, antes do `return` em `:1307-1309`) | Tem: `key` (endereço `key.base_page<<12`, `key.format`, `key.tiled`, `key.endianness`, `key.GetWidth/GetHeight/GetDepthOrArraySize`, `key.mip_max_level`, `key.packed_mips`, `key.pitch`) e `GetHostFormatPair(key)` (`formats[0]/[1]`); fallback = comparar `host_formats_[fmt]` com `kBestHostFormats[fmt]` (ambos acessíveis; a tabela base é `kBestHostFormats` em `:143`); tamanho guest = `texture->GetGuestBaseSize()/GetGuestMipsSize()` (calculados no ctor `pipeline/texture/cache.cpp:828-833`); `allocation_id` já existe (`pipeline/texture/cache.cpp:63,831`) |
| Log existente (elevar/nivelar) | `TextureCache::Texture::LogAction("Created"/"Loaded")` (`SDK/src/graphics/pipeline/texture/cache.cpp:811-821`, chamado de `:1110` e `:558`) | Hoje é `REXGPU_TRACE` e não inclui VkFormat/fallback/endian; transformar em INFO atrás de cvar `fh1_texture_log` e adicionar os campos (host format e fallback vêm do objeto Vulkan — logar no hook de cima é mais simples) |
| Endian/tiling no load (contexto do log) | `LoadTextureDataFromResidentMemoryUntimed` push constants `:2049-2052` (bit 0 tiled, bit 1 3D-tiling, bits 2-3 endian) e pitch em blocos `:2076-2085`; Checkpoint com quase tudo: `:1726-1738` | O checkpoint já carrega format/dimension/WxHxD/pitch/tiled/scaled/mips/packed/base/mip — reutilizar a string para o log da Tarefa A |
| Hash do payload | Hash xxHash já é dependência do SDK (worklog: submódulo xxHash); hash de `guest_layout().base.level_data_extent_bytes` lendo `shared_memory()` no momento do load | Cuidado: snapshot pode estar mid-rewrite (FMV) — logar junto com a classificação do fast path |
| FMV / plano k_8 | `TryLoadTextureDataFromCpu` (`texture_cache.cpp:1348-1706`); logs de recusa `:1424-1432` e classificação adiante na função (cvar `fh1_fmv_debug` já default on `:56-59`) | Já cobre o sintoma 4.3-H1 |
| Falhas de criação | `:1195-1198` (formato sem suporte → contador `unsupported_format_features_used_` + relatório de fim de frame `:570-585`) e `:1293-1305` (`vmaCreateImage` falhou, log c7c490d) | Garantir que o relatório de fim de frame esteja visível no log level do device |
| Tabela de fallbacks do device | `Initialize()` `:3304-3342` (uma linha por formato com fallback/não suportado no startup) | Cruzar com o log do device existente para montar a tabela real do Turnip |
| Resolve-side (complementar) | log de apresentação `fh1_native_executor.cpp:2271-2298`; `LogSkippedResolve` `:2217-2225`; contadores `LogStats` `:2478+`; TRACE de resolve `draw.cpp:1177-1185`; dump `:2107-2151` | Para a Tarefa A adicionar: log one-shot por `kind` de resolve (não só FMV) |

---

## 6. Riscos e lacunas

1. **Não foi possível executar o jogo** (ambiente sem Android SDK/NDK, worklog Task 1) — todas as hipóteses são estáticas; a priorização depende do próximo log do device com `fh1_fmv_debug`, `LogStats` do executor e `fh1_resolve_dump_dir`.
2. **SPIR-V pré-compilado**: os shaders de resolve/transfer do executor são blobs (`fh1_native_executor.cpp:180-269`) gerados offline — a auditoria do pack=1 (`k_2_10_10_10`) e pack=5 (`k_16_16_16_16`) exige disassemblar o SPIR-V (spirv-dis) ou regenerar via `tools/native-shader-pack.py`; não foi feito nesta passada.
3. **Valores exatos dos formatos gralloc 56/59** não são verificáveis neste ambiente (sem headers AOSP/NDK); a classificação "probe da stack EGL/driver" é por eliminação (nenhum código nosso aloca AHB), não por rastreio positivo do chamador.
4. **Comportamento do título com LUT ausente** é inferido da documentação (`docs/rendering-fixes-20261004.md:86-92`) e do fluxo guest; não há código host que intercepte a ausência — o teste decisivo é a recópia da pasta no device.
5. O log `forza06_10-07-03-00_741.log` citado no worklog não está no repo; as citações dele vêm do worklog (sintomas + linhas "colourgradingmaps", "Unknown Format 0", AHB 4x4, VK_ERROR_DEVICE_LOST). Um VK_ERROR_DEVICE_LOST (tu_knl_kgsl) pode corromper frames de forma não determinística e poluir qualquer análise visual — recomenda-se tratar device-lost como variável de confusão (reproduzir após cold boot limpo).
6. Quedas de desempenho/async (`vulkan_async_skip_incomplete_frames`) e pipelines placeholder podem mascarar sintomas intermitentes (menus com frame congelado) — a Tarefa A deve logar também se o frame foi apresentado ou pulado.

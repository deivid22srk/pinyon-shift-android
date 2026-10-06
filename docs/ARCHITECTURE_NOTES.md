# Notas de arquitetura — Pinyon Shift (Android)

Data: 2026-10-06 · Branch: `fix/android-rendering-fmv-textures-20261004` · SDK (submódulo
`thirdparty/shiftglue-sdk`, fork `deivid22srk/shiftglue-sdk`, branch `android-port`): pin `74a1de0`.

Documento escrito mapeando o código real desta branch (não o que o nome da branch sugere).
Referências `arquivo:linha` são relativas à raiz indicada em cada seção: **repo** =
`pinyon-shift-android/`, **SDK** = `thirdparty/shiftglue-sdk/`.

## 1. O que é este projeto

Pinyon Shift roda o *Forza Horizon* de Xbox 360 **nativamente**: o código PowerPC do título é
traduzido ahead-of-time para C++ pelo ShiftGlue (fork do ReXGlue) e compilado para o alvo
(x86-64 no Windows; arm64 no Android). Não há interpretação nem JIT em runtime. O *command
stream* de GPU que o jogo constrói (formado PM4 sobre o Xenos) é executado por um renderer
nativo escrito para este título, em Vulkan (padrão) ou Direct3D 12 (desligado no build Android:
`android/app/build.gradle` passa `-DREXGLUE_USE_D3D12=OFF`).

O repo contém: launcher Windows (C#/WPF, `launcher/`), app Android (`android/`), código host
C++ (`src/`), tooling (`tools/`), config (`config/`), docs e o submódulo SDK. O jogo, assets e
o código traduzido **não** ficam no repo — o build do Android usa codegen congelado
(`PINYON_SHIFT_FROZEN_CODEGEN=ON`).

## 2. Módulos Android (repo)

| Módulo | Papel |
|---|---|
| `GamePickerActivity.java` | Tela-lança: escolha da pasta do jogo (SAF + fallback browser), All Files Access (`MANAGE_EXTERNAL_STORAGE`), seleção de driver GPU custom (Turnip via adrenotools, zip install), e botão Play. Preferências em `SharedPreferences` (`PREFS_NAME`): `game_root`, `game_tree_uri`, `gpu_driver`, `gpu_turbo`. |
| `PinyonActivity.java` | `SDLActivity` (SDL3 estático em `libmain.so`). Antes do `SDL_main`: copia `gamecontrollerdb.txt` dos assets, e via JNI (`nativeSetEnvironment`/`nativeSetGpuDriver`) publica `PINYON_SHIFT_STATE_ROOT` (armazenamento interno), `PINYON_SHIFT_GAME_ROOT` ( seleção do picker), `REX_ANDROID_DRIVERS_DIR/DRIVER/TURBO`. |
| `AndroidManifest.xml` | Permissões: `MANAGE_EXTERNAL_STORAGE`, `READ_EXTERNAL_STORAGE` (≤32), `INTERNET` (sockets do título), `BLUETOOTH_CONNECT` (gamepads), `VIBRATE`. `minSdk 29`, `targetSdk 35`, `compileSdk 35`; release `arm64-v8a` apenas, debug `x86_64` (emulador/CI). |
| `android_host_jni.cpp` (repo/src) | Ponte JNI acima + `environment.txt` no diretório externo do app: `KEY=VALUE` opt-in (não sobrescreve variáveis já fixadas; denylist em `kFixedEnvironmentKeys`) — é o canal documentado para diagnostics on-device (ex.: `PINYON_SHIFT_NATIVE_SHADER_CAPTURE_DIR`). |

## 3. Runtime nativo (SDK) — camadas

Fluxo de inicialização (Android): `SDL_main` → `rex_app.cpp` (`SDK/src/ui/rex_app.cpp:186`
chama `rex::InitLogging(LogConfig)`, `:192` chama o hook do app `OnPostInitLogging()`) →
`PinyonShiftApp` (repo `src/pinyon_shift_app.cpp`) → runtime do SDK (kernel HLE, filesystem,
áudio, input, gráficos).

### 3.1 Kernel / VFS (SDK `src/kernel/`, `src/filesystem/`)
- Caminhos guest (`game:\`, `d:\`) resolvem por symlinks para `\Device\Harddisk0\Partition1` →
  `HostPathDevice(game_data_root)` (`SDK/src/runtime/runtime.cpp:306-323`); resolução
  guest→host em `SDK/src/filesystem/guest_path_file_system/virtual_file_system.cpp:117-166`,
  com fallback case-insensitive (`host_path_device.cpp:59-114`).
- `NtCreateFile` falho loga apenas caminho guest + status NT
  (`SDK/src/kernel/xboxkrnl/xboxkrnl_io.cpp:239-241`); o funil único de abertura (kernel +
  CRT + XContent) é `VirtualFileSystem::OpenFile` (`virtual_file_system.cpp:207`).
- Áudio XMA: `xma_context.cpp:544` avisa "cannot resolve logical packet" (status 4 =
  `kPacketOutOfRange` → payload zerado → dropout, não distorção).

### 3.2 GPU (SDK `src/graphics/`)
- **`command_processor.cpp`** — coração. Thread "GPU Recorder" decoda o stream PM4 e grava
  comandos Vulkan num `DeferredCommandBuffer`; `EndSubmission` (`:6138-6338`) fecha a fita e
  faz `vkQueueSubmit` (`:6433`); `IssueSwapImpl` (`:2726+`) trata o swap: acquire, compute de
  gamma, present. Frames in-flight ≤ 3 (`kMaxFramesInFlight`), waits por fence
  (`vkWaitForFences` `:5868/:5891`).
- **`fh1_native_executor.cpp`** — caminho ativo no Android (default, `command_processor.cpp:115,125`):
  execução *nativa* dos comandos do FH1 com posse de tiles EDRAM (80×16 samples, 2048 tiles) e
  resolve por compute direto na memória guest; o `render_target_cache.cpp` genérico fica
  inativo. Resolves de tiles não possuídos são pulados (`Skip("resolve_tiles_unowned")`
  `:2340-2343`).
- **`texture_cache.cpp`** — cache de texturas com fast path de vídeo (FMV): snapshot CPU do
  plano, classificação torto/completo (baseline diff, boundary tracking, repeats), staging
  `Request` no upload pool, `CopyCpuRange` com page-watches, upload por `vkCmdCopyBufferToImage`
  com pitch do guest. Tabela de formatos Xenos→Vulkan com fallbacks (ex.: YUV 4:2:2 packed →
  `R8G8B8A8` + shader de reempacotamento `GBGR8ToRGB8` `:3008-3032`, porque o Turnip não
  oferece filtro linear no `G8B8G8R8_422_UNORM`).
- **`pipeline_cache.cpp`** — compilação de PSOs traduzidos de ucode → SPIR-V. No Android,
  `vulkan_async_pipeline_no_placeholder=true` (`:64-70`): pipelines compilam em workers; draw
  que não espera (`vulkan_async_pipeline_wait_ms`, 200 ms) é descartado e o frame inteiro
  perde a apresentação (`vulkan_async_skip_incomplete_frames`, ver §6.3).
- **`shared_memory.cpp`** — 512 MB não-sparse (Turnip sem sparse binding) mapeado no espaço
  guest; upload pool com páginas de 2 MiB (`Request`/`RequestPartial`).
- **`vulkan_presenter.cpp`** — swapchain (MAILBOX default no Android, `:57-64`/`1405-1419`),
  mailbox de guest output, acquire/present. Mensagens do driver (Turnip) chegam via
  `VK_EXT_debug_utils` → `DebugUtilsMessengerCallback` (`vulkan_instance.cpp:576-643`,
  "Vulkan Warning …").
- **DEVICE_LOST**: reconhecido em `command_processor.cpp:5880/:5893/:6437` e
  `vulkan_presenter.cpp:1600/:2295`; todos convergem em
  `GraphicsSystem::OnHostGpuLossFromAnyThread` (`graphics_system.cpp:315-330`) →
  `rex::FatalError` → `abort()`. Sem recuperação, e os checkpoints existentes dependem de
  `VK_NV_device_diagnostic_checkpoints` (ausente no Turnip) — ou seja, hoje o DEVICE_LOST é
  mudo no aparelho do usuário.

### 3.3 Diagnóstico host (repo `src/`)
- `pinyon_shift_diagnostics.cpp`: `InitializeEarly()` resolve `PINYON_SHIFT_STATE_ROOT`,
  cria `<state>/{cache,config,crashes,logs,update,user}`, instala o crash reporter, emite
  `process.start` com proveniência de build (`pinyon_shift_build.json`: commits repo+SDK,
  SHA do executável). `RecordEvent` escreve JSONL em `<state>/logs/<session>.jsonl` e
  espelha como `M2_EVENT` no logger.
- `crash_reporter_posix.cpp`: handler de sinais com stack própria. **No Android só captura
  `SIGABRT`** — SIGSEGV/SIGBUS/SIGILL/SIGFPE são consumidos pelo emulador (MMIO write-watch +
  SEH do guest); crashes não tratados viram tombstone (inacessível sem root). O report
  (`<state>/crashes/<session>-<signal>.txt`) é re-logado no boot seguinte.
- `pinyon_shift_app.cpp`: `OnConfigurePaths` (`:410`) fixa paths + `log_file`
  (`<state>/logs/runtime.log`); `OnPostInitLogging` (`:514`) configura CSV de performance.
- Config do host: `config/host_config.cpp` (TOML `pinyon_shift.toml`, schema 28) — mods
  habilitados, cheats, gráficos (aniso 16x default desde schema 28).

### 3.4 Pipeline de FMV (síntese; detalhe em `docs/investigation/fmv-yuv-pipeline.md`)
Decodificador **software do próprio jogo** reescreve os planos YUV na memória guest de cima
para baixo → page-watch dispara o load → snapshot síncrono (`CopyCpuRange`) → classificação
(diff contra baseline em blocos de ~128; zero-chunk probe; boundary/repeats) → upload (fast
path) ou retenção do último frame completo → composição pelo shader do jogo (conversão
YUV→RGB é do jogo; o host só reempacota 4:2:2 → RGBA8 quando o Turnip não suporta o formato
nativo) → resolve de apresentação → swap (acquire/gamma/present).

### 3.5 UI
- **In-game (SDK ImGui)**: settings F6 (`repo/src/ui/settings_menu.cpp`), trainer, conquistas;
  fontes do host no Android vêm do sistema (`host_style.cpp`, Roboto/Noto).
- **UI do jogo**: desenhada pelo próprio título. Textos de menu são **fonte vetorial** (um
  draw por glifo — `docs/UI_ASSETS.md:106-126`); overlays do host leem `Fonts.zip` do disco.
- **Picker**: ver §2. É a superfície natural para as opções de logging da Tarefa A.

## 4. Build & CI
- Android: Gradle + CMake (NDK r27c `27.2.12479018`, alinhamento ELF 16 KiB forçado com gate
  `readelf` no CI). `libmain.so` inclui SDL3 estático + runtime SDK + host + facades de codegen
  congelado.
- CI: `.github/workflows/build.yml` (workflow_dispatch; artefato `pinyon-shift-apk`), `ci.yml`
  (testes de tooling Python + contratos), `release.yml`. O loop anterior usou runs 37–43.
- Testes de tooling: `tools/tests/test_*.py` (contratos de release, config, renderer).
- Testes C++ nativos: `tests/` (hostui, save, config, native_renderer, edram tiles).

## 5. Estado atual dos problemas (evidência em `docs/investigation/`)

| Sintoma no device | Causa raiz estabelecida? | Onde |
|---|---|---|
| FMV pisca preto | Hipóteses H1–H5 ranqueadas; H1 (upload de snapshot pós-clear nos ramos `reaches_bottom`/`static_zero_top` vacuoso) é a mais forte | `investigation/fmv-yuv-pipeline.md` §3.1 |
| "Skipping Vulkan frame presentation … placeholder" | Cadeia completa entendida (espera 200 ms → draw pulado → frame não apresentado); causa stutter, não preto | idem §3.2 |
| Faixa glitchada no topo | 8 caminhos restantes de upload parcial mapeados (§3.4); resolve de tiles não-possuídos é suspeito estrutural | idem §3.4 + `textures-formats-resolve.md` |
| Texto de menu corrompido | Hipótese "textura de fonte" REFUTADA (fonte vetorial); corrupção vem do RT de UI (`k_2_10_10_10`) ou shader de cobertura no Turnip | `textures-formats-resolve.md` |
| Espelho/painel amarelos | LUT ausente explica bloom/cabine, não o amarelo; teoria mais forte: pack/swizzle R/B no resolve do cubo `k_2_10_10_10` | idem |
| colourgradingmaps vazio | Cópia MTP perdeu ARQUIVOS (pasta recriada vazia); aviso existe (10636d1), falta validação de integridade + fallback | `assets-vfs.md` |
| DEVICE_LOST | Sem breadcrumbs no Turnip (checkpoints NV não existem); design de breadcrumbs universais pronto | `vulkan-device-lost.md` §4 |
| AHB 4x4 / Unknown Format 0 | Ruído da stack Android/EGL/libadreno_utils — nenhum `AHardwareBuffer` no nosso código | `textures-formats-resolve.md` |
| AMB_Redstone.fsb 0xC000000F | Arquivo ausente na cópia OU prefixo `\??\` não normalizado no `NtCreateFile` (inconsistência com `xboxkrnl_ob.cpp`) | `assets-vfs.md` |

## 6. Decisões de design tomadas nesta iteração

1. **Tarefa A primeiro** (como pedido): sem observabilidade por frame/draw/RT/formato, os
   sintomas restantes não são separáveis. A infraestrutura alvo:
   - Java cria `session_YYYYMMDD_HHMMSS/` sob a raiz de logs (`/storage/emulated/0/forza/`
     com All Files Access; fallback `Android/data/<pkg>/files/forza_logs/`), escreve
     `device_info.txt` (Android) e captura `logcat --pid` para `logcat.txt`.
   - Nativo: sink multiplexado spdlog anexado a todas as categorias, roteando por categoria
     (gpu/apu/fs/krnl) e por prefixo estável (`fh1 fmv`, `Vulkan`, `breadcrumb`) para
     `all/gpu/vulkan/fmv/files/audio/crash.log`, com flush por linha, dedup com contador e
     rotação por tamanho + retenção de sessões.
   - SDK: breadcrumbs universais (`vkCmdFillBuffer` em buffer host-visible + anel de
     metadados em RAM do host), dump antes do abort em todos os caminhos de DEVICE_LOST;
     stats por frame; log por textura criada; funil de falhas VFS com caminho host.
2. **Fixes prováveis primeiro, comportamentais com escape**: correções que mudam
   comportamento (retenção zero-tail no `reaches_bottom`) seguem o padrão já validado
   (escape por repeats + kill-switch cvar + logs de engajamento). Detectores de hipóteses
   não comprovadas entram como log, não como mudança de comportamento.
3. **Sem gambiarras**: nenhum sintoma será mascarado; todo fallback documenta o motivo e
   loga quando aciona.

## 7. Lacunas conhecidas / não resolvidas aqui
- Pack `.pnsp` (pré-compilação de SPIR-V) exige host Windows — permanece a mitigação real
  para o preto inicial do primeiro vídeo (NP-15.2/P2-LOG do backlog).
- Amarelo do espelho/painel: precisa de dump de resolve no device (`fh1_resolve_dump_dir`)
  para separar resolve × sampling.
- Testes on-device (B1/B2 do backlog) continuam bloqueados no dono do aparelho.

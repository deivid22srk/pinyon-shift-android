# Baseline — 2026-10-03 17:00 UTC

Base: `android-port` @ `8acd180` (Bump submodule: FMV resolve log bit-field copy).
Branch de trabalho: `auto/android-improvements-20261003`.

## Build / CI (GitHub Actions, `Build Android APK`)

- Pipeline: XEX verify → rexglue generator (x86-64) → codegen → NDK arm64-v8a (1181 alvos) → Gradle APK.
- Duração dos runs recentes (success): 26m31s (b1f2786 ant.), 28m17s, 27m40s.
- Run de `b1f2786` FALHOU (17m12s): `fh1_native_executor.cpp:2286 non-const reference cannot bind to bit-field 'color_base'` (SDK) — corrigido pelo bump de submodule `8acd180`.
- **Nenhum cache no workflow**: sem ccache, sem cache Gradle, sem cache de codegen; sem `concurrency` para cancelar runs obsoletos.
- APK `app-device-release.apk`: **53 MB** (run 37129069405).

## Estado funcional (de ANDROID.md / releases / docs)

- Turnip carrega e renderiza no device; intro FMV + menu desenham (build #23).
- Fixes recentes: resolve pack 5 do FMV, permissão INTERNET (crash NetDll_socket),
  SEH crash chain, auto-select driver importado, frames consistentes no warm-up de pipelines.
- Pipeline cache Vulkan persistente: **já implementado no SDK e ativo no Android**
  (`vulkan_pipeline_cache_persist` default true; cadeia `PINYON_SHIFT_STATE_ROOT` →
  `cache_root` → `InitializeShaderStorage` verificada no código em 2026-10-03).

## Métricas disponíveis (sem device)

- Duração do CI (por run), tamanho do APK, contagem de warnings do NDK,
  presença/falta de alinhamento ELF 16 KiB (via readelf no artefato).
- Não mensurável por este loop sem dispositivo: FPS, frame time, draws/frame, memória, térmico.

## Falhas conhecidas abertas (fonte: BUGS.md / NATIVE_PORT_BACKLOG.md / ANDROID.md)

- Car cards com texturas riscadas salvas por builds antigos (NP-0.6 re-render aberto).
- Filme ausente (ex.: `WristbandGet.wmv`) → verde/rosa (NP-2.9, fix não implementado).
- Animações rápidas em HFR (plateia/compra) — PB-9.x aberto.
- Artefatos 2x/3x (flashes rosa, bordas verdes) + sim acelera — NP-4.10 aberto.
- Follow-ups build #23: logs de `NetDll_socket` -1 e `InterlockedPopEntrySList` vazios.

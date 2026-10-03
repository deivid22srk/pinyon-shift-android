# Métricas — antes/depois

Baseline capturado em 2026-10-03 (base 8acd180). Sem dispositivo Android: métricas
on-device (FPS, frame time, memória) ficam como "não mensurável neste loop" e toda
mudança de comportamento será validada por build/CI/análise estática, com evidência
explícita.

| Métrica | Antes (baseline) | Depois | Quando |
|---|---|---|---|
| Duração CI frio (success) | 26m31s–28m17s | — | — |
| Duração CI quente (cache) | n/a | alvo ≤ 15 min | após L1 |
| APK app-device-release.apk | 53 MB | — | — |
| Warnings NDK (repo) | coletar do log | — | L4 |
| ELF p_align arm64 | verificar no artefato | garantido ≥ 16384 | L2 |
| Runs verdes / vermelhos | 0 / 1 (base b1f2786) | — | contínuo |

| p_align arm64 (antes do fix) | presumido 4096 (NDK r27 não alinha por padrão) | confirmar no gate | run 37140676682 |

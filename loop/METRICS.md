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
| p_align arm64 (medido, baseline) | 9/10 libs em 0x1000 | gate garante 0x4000 | ae2b344 |
| Suíte de tooling | 231 testes, 1 falha (pin SDK) | OK no CI a cada push | 3280b0a |
| ELF p_align arm64 (depois) | 10/10 libs = 16384 | gate verde | run 37140676682 |
| Duração CI frio com cache setup | 31 min (37140676682) | aquecido: aguardando | próximo run |
| ccache hit rate | frio 0/1140 (0.5GB salvo) | medir no run aquecido | próximo run |

## 2026-10-03 — Ciclo 3/4 (evidência de device + fixes de render)

| Métrica | Antes | Depois | Como medir |
|---|---|---|---|
| Duração do CI (branch) | 28 min (run 1, ccache frio) | 15 min (run 5, ccache + codegen quentes) | `gh run list`, runs 37140676682 → 37150524858 |
| Compilação NDK incremental | miss total | ccache 1140 TUs (0.5 GB) no 1º run | logs do CI, passo ccache --show-stats |
| Gate 16 KB | 0/10 libs (p_align 0x1000 no APK antigo) | 10/10 libs (p_align 16384) | passo readelf do CI |
| Linhas M2_EVENT ui.record.* no logcat | 193/206 eventos (~170 lin/s burst no menu) | 0 (atrás de PINYON_SHIFT_UI_TRACE) | log4 vs novo log do dono |
| gamecontrollerdb.txt no APK | ausente (task NO-SOURCE) | empacotado (../config) | log do passo copyControllerDb |
| Fonte dos diálogos host | SDK debug font | Roboto/Noto (/system/fonts) | log "Host UI font ..." no boot |
| PSOs traduzidos em runtime (1º boot) | 371 (sem pack) | igual até pack existir; caminho p/ pack agora aberto (environment.txt + captura on-device) | log "Creating graphics pipeline state" |
| Falha de contrato de release | detectada só no release | falha em 2 min no CI (guard M0 funcionou) | run 37152590349 |
| FMV corrompido (Adreno 660/Turnip) | banda repetida + combing | aguardando validação visual do dono com APK do run 7 | vídeo log4 vs novo vídeo |

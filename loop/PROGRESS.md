# Diário de progresso — loop autônomo

## 2026-10-03

- 17:00 UTC — Loop iniciado. Branch `auto/android-improvements-20261003` criada a partir
  de `android-port` e publicada. Telegram avisado.
- 17:03 UTC — Clone em `b1f2786`. CI da base FALHOU (bit-field `color_base` no SDK,
  run 37138085010) → novo commit `8acd180` na base faz bump do submodule.
- 17:14 UTC — Branch rebased/resetada para `8acd180` (sem commits próprios ainda).
  Submodule `shiftglue-sdk` inicializado (shallow) para leitura.
- 17:20 UTC — Fase 0 concluída em linhas gerais:
  - Backlogs NP/PB analisados por subagente (relatório completo no worklog e RESEARCH).
  - **Validado no código**: PB-2.13 (VkPipelineCache persistente) já implementado e
    ativo no Android — cadeia `PINYON_SHIFT_STATE_ROOT` → `cache_root` →
    `InitializeShaderStorage` confirmada. Item NÃO será reimplementado.
  - CI sem cache e sem concurrency → item L1 do backlog.
  - Alinhamento 16 KiB não explícito no repo → item L2 (verificar no artefato).
  - APK baseline: 53 MB. CI baseline: ~26-28 min.
- 17:29 UTC — Ciclo 1 publicado (revisão adversarial aprovada com ressalvas, todas aplicadas):
  - `58ecf9a` ci: cache NDK/Gradle (ccache + actions/cache + concurrency cancel).
  - `ae2b344` build: alinhamento ELF 16 KB obrigatório + gate readelf no CI.
  - Revisor apontou: NDK r27 NÃO alinha 16 KB por padrão (só r28+) → flag é load-bearing;
    APK anterior provavelmente 4 KB-aligned (crash em devices 16 KB).
  - Run 37140676682 disparado na branch (frio). Backlog: L1, L2 aguardando-CI.
- 17:45 UTC — Ciclo 2:
  - `5312f52` feat(android): avisos no picker (Vulkan 1.1 ausente, storage interno <512 MB) —
    revisão adversarial aprovada com ressalvas de estilo, aplicadas.
  - `3280b0a` fix(release): pin rexglue.revision do release-toolchain.json atualizado para o
    SHA do submodule (404fa7ee) + suíte de tooling (231 testes, 0,5s) no CI do APK (fail-fast).
    Origem: workflow de release do tag v0.3.2.0-build24 falhou; 2 das 3 falhas já resolvidas
    no HEAD; a restante corrigida e agora guardada por CI.
  - Evidência APK baseline baixada e inspecionada: 9/10 libs com p_align 0x1000 (4 KB) —
    confirma o bug corrigido por ae2b344; libmain.so 108 MB sem .debug_* (strip já ocorre;
    tamanho é código real do jogo) → item M5 fechado sem ação.
- 18:02 UTC — Run 1 (37140676682) VERDE em 31min: gate 16 KB confirma 10/10 libs em
  p_align=16384; ccache populado (0.5 GB); APK 53M. Run 2 disparado para o HEAD
  (valida L3/M0/M1, aquece ccache, popula cache de codegen).
- 18:20 UTC — Run 2 (37142696303, ba1e332) FALHOU: javac não encontra
  `PackageManager.FEATURE_VULKAN_VERSION` (constante inventada no L3; correta:
  `FEATURE_VULKAN_HARDWARE_VERSION`). Corrigido no working tree; lição registrada.
- 18:20 UTC — Log de device real recebido do dono (log4.zip: logcat + vídeo) —
  Motorola Edge 30 Fusion (tundra), Android 14, Adreno 660, driver custom Turnip
  (Mesa 26.3.0-devel) carregado via adrenotools; sessão saudável ~30 fps.
  Análise completa em artifacts/logs4/LOG_ANALYSIS.md; top achados no BACKLOG (P1*).
- 18:35 UTC — Run 3 (37144257670, 90b896f) VERDE ~11 min: fix FEATURE_VULKAN_HARDWARE_VERSION.
- 18:40 UTC — Ciclo 3 (evidência de device real):
  - `823b324` fix(android): copyControllerDb apontava para fora do repo (../../config
    relativo a android/) → NO-SOURCE perpétuo, gamecontrollerdb.txt nunca foi
    empacotado; corrigido para ../config.
  - `6b07cfd` fix(diagnostics): dumps ui.record.* atrás de PINYON_SHIFT_UI_TRACE
    (193/206 M2_EVENT no log eram desses dumps).
  - feat(ui): fonte do host no Android (/system/fonts Roboto/Noto) — commit a seguir.
  - Run 4 (37150419137) disparado para 823b324.

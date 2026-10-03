# Backlog do loop autônomo — priorizado (impacto × risco × esforço)

Formato: `[estado] ID — título (impacto, risco, esforço)`.
Estados: `aberto`, `em-andamento`, `aguardando-CI`, `feito <hash>`, `revertido`, `BLOQUEADO — precisa do dono`.

## Alta prioridade (repo-side, sem device, risco baixo)

- [aguardando-CI 58ecf9a] L1 — **CI: cache de build** (ccache NDK, Gradle wrapper/deps/build-cache,
  `concurrency` cancel-in-progress). Impacto alto (cada iteração do loop ganha ~8-15 min),
  risco baixo, esforço M. Métrica: duração do run frio vs quente em `loop/METRICS.md`.
- [feito ae2b344] L2 — **Verificação e garantia de alinhamento ELF 16 KiB** (Android 15+ com
  páginas de 16 KiB, exigência Play desde 11/2025). NDK r27c alinha por padrão, mas
  explicitar `-Wl,-z,max-page-size=16384` + passo `readelf` no CI que falha se
  `p_align < 16384`. Impacto alto (compatibilidade de devices novos), risco baixo, esforço S.
- [feito 5312f52] L3 — **UX GamePicker**: leitura do código pendente; candidatos: checagem de
  espaço livre, mensagens de erro mais acionáveis, estado de seleção inválida.
  Impacto médio, risco baixo, esforço S–M.
- [aberto] L4 — **Warnings do NDK no código do repo** (coletar do log do CI e corrigir os
  do repo; os do SDK ficam para o fork do SDK). Impacto médio, risco baixo, esforço S–M.
- [aberto] L5 — **PinyonActivity**: revisar ciclo de vida (pause/resume, surface loss) e
  mensagens de falha Vulkan (ANDROID.md promete "clear log" — validar e melhorar UI se
  a mensagem não chega ao usuário). Impacto médio, risco baixo, esforço S.

## Média prioridade

- [aberto] M1 — **Cache do codegen no CI**: `.local/generated` + binário do rexglue
  chaveados por (SHA do submodule, manifest, supported-dumps.json). Impacto médio
  (~3-6 min/run), risco médio (chave de cache), esforço M.
- [aberto] M2 — **Pipeline cache: pré-compilação em background na primeira execução**
  (Android: `InitializeShaderStorage` blocking já existe; avaliar warm-up no picker antes
  de lançar o jogo). Requer leitura do fluxo; risco médio; M.
- [aberto] M3 — **Orçamento de memória por tier de RAM**: cvars de texture cache
  (PB-8.3: 1 GB soft / 2 GB hard) expostos ao host Android via `pinyon_shift.toml`
  gerado conforme `ActivityManager.getMemoryInfo()`. Risco médio (sem device para medir
  OOM), esforço M. Só publicar com justificativa forte e default conservador.
- [aberto] M4 — **NP-2.9**: tratar filme ausente como terminado (evita verde/rosa em
  discos/instalações sem o vídeo). Precisa de investigação no SDK — o hook pode ser
  repo-side (`PinyonShiftCompleteOpeningMovie`). Esforço M.
- [feito-sem-acao (evidencia: .text 78MB, strip ok)] M5 — **APK**: checar conteúdo (símbolos de debug do RelWithDebInfo são
  embutidos? `useLegacyPackaging=true` é necessário para as libs carregadas por nome).
  Possível ganho de tamanho sem perda. Esforço S–M.

## Baixa prioridade / SDK-side (precisam de push no fork `deivid22srk/shiftglue-sdk`)

- [aberto] S1 — Suporte a kernel 16 KiB (NP-14.3): validar offset físico `0xE0000000`
  (`rex_physical_host_offset_e0`). Teste real impossível sem device 16 KiB; só
  documentar/analisar estático.
- [aberto] S2 — `vulkan_texture_load_compute_copy` é `!REX_PLATFORM_ANDROID` (desligado
  no Android): investigar por quê e se Turnip suporta (ganho de carga de texturas).
- [aberto] S3 — Workarounds Adreno/Mali do NP-14.4 (descriptor-indexing fallback,
  storage-buffer bucketing, MSAA 2x). Grande, risco alto, precisa de logs de device.

## BLOQUEADO — precisa do dono

- [BLOQUEADO] B1 — Acesso a um device/logcat real para validar qualquer mudança de
  comportamento on-device. Enquanto isso: apenas mudanças validáveis por build/CI e
  análise estática serão publicadas.
- [BLOQUEADO] B2 — Testes on-device de perf/térmico (NP-14.6) e rotação de releases.

## Log de device real (log4.zip, Edge 30 Fusion / Adreno 660 / Turnip Mesa 26.3) — 2026-10-03

- [feito 6b07cfd] P1-LOG — **Flood `ui.record.*`**: 193 linhas M2_EVENT em [info] no logcat
  (burst ~170 lin/s ao abrir o pause menu), dump de diagnóstico em
  `src/pinyon_shift_runtime_hooks.cpp` (~:3202) que escapa do gate `PINYON_SHIFT_UI_TRACE`.
  Ação: envolver o dump em `if (UiTraceEnabled())`. Impacto: logcat limpo em release
  (custo de E/S por frame), risco mínimo.
- [aberto] P2-LOG — **Pack .pnsp ausente** (NP-15.2; geração parece exigir Windows — avaliar host-side): "No FH1 precompiled SPIR-V shader pack" →
  371 PSOs compilados em runtime (hitches de entrada em gameplay). Repo-side parcial
  (gerar/pacotar pack; NP-15.2); médio prazo.
- [feito 823b324] P2-LOG — **`gamecontrollerdb.txt` nunca empacotado**: a task Gradle
  `copyControllerDb` resolvia `../../config` (fora do repo) → NO-SOURCE perpétuo.
  Corrigido para `../config`; o arquivo específico do projeto (8BitDo Windows) agora
  embarca. Mapeamentos Android extras: adicionar GUIDs conforme devices aparecerem.
- [documentado] P3-LOG — **avc execstack denied 3×**: todas as 10 libs do APK têm
  GNU_STACK=RW → não é PT_GNU_STACK; provável mprotect PROT_EXEC de fibers/trampolines.
  Benigno (sessão rodou). Documentar em TROUBLESHOOTING.
- [documentado] P3-LOG — **XMA status 4 (kPacketOutOfRange)** 8×/7 contextos em 26,7 s;
  AHardwareBuffer 4×4 probe 8× e props vendor.mesa.* negadas = ruído Turnip/qdgralloc.
  Silenciar counter no SDK (baixa prioridade).
- [aberto] P1-LOG-SDK — **Shared memory 512 MB buffer cheio** (sparseResidencyBuffer:false
  no Turnip) → fallback chunked VMA economiza ~⅓ do device-local pico 1430/2087 MB.
  SDK-side (fork shiftglue-sdk), prioridade alta para devices de 8 GB.
- [re-escopado] NP-4.10 — auditoria 7-d: device Android roda a 1x (sem texturas escaladas) → o mosaico no Adreno 660 NÃO é o NP-4.10 (que é 2x/3x no D3D12 desktop). Mosaico mobile atacado pelo P2 (cópia por nível, f4df688). Re-escopar NP-4.10 para desktop.

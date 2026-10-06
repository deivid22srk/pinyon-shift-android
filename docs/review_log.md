# Registro do loop de revisão adversária

Método: Investigadores → Implementadores → Verificador de build → Críticos
(C1 corretude/causa raiz, C2 observabilidade, C3 regressão/build/performance),
correção de TODAS as objeções (ou refutação com evidência) e novo ciclo.
Critério de saída: os três críticos ≥ 9/10 em todos os itens, veredicto
"impressionado", sem objeções críticas/altas abertas, e build verde.
Mínimo 3 iterações, máximo 10.

## Iteração 1 (2026-10-06)

**Entrada**: SDK `74a1de0..ba0106a` (980116c breadcrumbs, a722c9f EndSubmission,
f7e5bad FMV cleared-plane + detectores, ba0106a texture log + VFS); repo
`ab76c25..ecaed7b` (14c5ef1 UI logs, fb80729 sink nativo, 5cf71aa colourgrading,
1b9489b/ecaed7b docs). Build 37458970888 (1b9489b): **verde**.

**Veredictos**: C1 **não impressionado** (bloqueadora 1, altas 3); C2 **não
impressionado** (bloqueadora 1, altas 2); C3 **não impressionado** (alta 1,
médias 4; nota: C3 refutou com benchmark a própria hipótese de custo por draw —
gates corretos, ~5-30 ns/kDrawEnd).

**Objeções → correções:**

| # | Objeção (gravidade) | Correção | Onde |
|---|---|---|---|
| C1-1/C1-F | Falso ERROR de fence por frame: VK_TIMEOUT é o retorno "não sinalizada" do vkWaitForFences(0) (bloqueadora) | condição `!= VK_TIMEOUT` + comentário corrigido | SDK cp.cpp:5940 |
| C1-2/C3-O1 | Dedup morto: timestamp fazia parte da chave | chave = nível/categoria/payload; timestamp só na 1ª ocorrência; hora vem de msg.time (sem ostringstream) | repo realtime_log.cpp |
| C1-3/C2-O6 | UB no dump de loss via presenter (std::string no anel, serial não-atômico) | detail → char[128] POD; serial → atomic fetch_add/load; strncmp nos prefixos | SDK cp.{h,cpp} |
| C1-4/C2-O1/C3-O3 | Breadcrumbs sem identidade no Turnip (gates só com checkpoints_enabled_) | `recording_checkpoints()` = NV ∨ breadcrumbs nos 4 call sites; novo note "draw bind pass X pipe Y" antes do bind; kDraw mantém hashes vs/ps | SDK cp.cpp/texture_cache.cpp |
| C2-O2 | Rastro do dump não caía em vulkan.log/crash.log | linhas prefixadas "breadcrumb done/pending/destroyed" | SDK cp.cpp |
| C2-O3/C3-O7 | Build indeterminado no device ("commit ?") | task gradle writeBuildManifest (asset) + activity stages em filesDir + LoadBuildSummary lê HOME também | repo build.gradle, PinyonActivity, realtime_log.cpp |
| C2-O4 | Detector de frame preto fora de fmv/vulkan.log | predicado "clear-only frame" roteia para fmv+vulkan | repo realtime_log.cpp |
| C2-O5 | Resumo por frame sem texturas | contadores criadas/falhas por frame (Take-reset) na linha de stats | SDK texture_cache + cp.cpp |
| C2-O7/C1-7 | config_dump único snapshot do startup | 2º append "cvars at shutdown" no FlushRealtimeLogSession | repo realtime_log.cpp |
| C1-5 | vkFreeMemory de handle não-inicializado | `= VK_NULL_HANDLE` | SDK cp.cpp |
| C3-O5 | WARN por-draw de warmup sem limite | primeiros 8 do frame (total fica na linha de stats) | SDK cp.cpp |
| C3-O6 | "FH1 FMV presentation swap" maiúsculo não roteava | mensagem em minúsculas ("fh1 fmv ...") | SDK cp.cpp |
| C3-O2/C2-O12 | Pior caso de disco ~14 GB | budget de sessão 1.5 GB (só crash.log continua) + 3 sessões mantidas + zip sem .old + rotação que falha fecha o stream | repo realtime_log.cpp + LogSessions.java |
| C3-O4 | Timestamp caro por linha | snprintf do msg.time | repo realtime_log.cpp |

**Objeções aceitas com documentação (sem mudança):**
- crash.log sem backtrace no Android (bionic sem execinfo.h): pre-existente; o
  tombstone completo chega via logcat.txt — TEST_PLAN agora diz isso.
- Orfão do processo logcat em crash duro do app: sem mecanismo confiável de
  limpeza no Android; produz zero output adicional (PID morto) e é removido no
  force-stop/reboot. Registrado como limitação.
- Custo SDK do `vulkan_breadcrumbs` default-on no Android independente do
  toggle do picker: decisão deliberada (o DEVICE_LOST é sintoma ativo); custo
  medido desprezível (ring POD + fills inter-pass). Cvar documentado.
- FMV "por frame" com id de frame do decoder e acquire/present por frame:
  parcial (rate-limited com contadores + boundary); o restante precisa de
  evidência de device antes de instrumentar mais (documentado no TEST_PLAN §6).
- Sem CI para o alvo Windows com o TU novo: o contrato de CI do repo é APK
  Android + tooling; risco aceito e anotado.

**Saída**: SDK `b51e37e`, repo `70507a5`, pin atualizado; build despachado.

## Iteração 2 (2026-10-06)

**Entrada**: SDK `ba0106a..b51e37e`, repo `ecaed7b..70507a5`. Build: ver abaixo.

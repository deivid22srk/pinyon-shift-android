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

**Entrada**: SDK `ba0106a..b51e37e` (correções da it. 1), repo `ecaed7b..9a2474e`.
Build 37463780424 (9a2474e): **verde** — a task gradle `writeBuildManifest` foi
exercida pela primeira vez no CI e passou.

**Veredictos**: C1 **não impressionado** (0 bloqueadoras, 0 altas; 2 médias + 3
baixas); C3 **não impressionado** (0 bloqueadoras, 0 altas; 2 médias + 3 baixas;
testes de tooling 232 OK; benchmarks reais: custo por draw ~23× a it. 1, dominado
por fmt::format); C2: **falha do agente** (retorno vazio) — re-executado na
iteração 3 com o escopo cumulativo.

**Objeções → correções:**

| # | Objeção (gravidade) | Correção | Onde |
|---|---|---|---|
| C1-N1/C3 | Budget de 1,5 GB matematicamente inalcançável (7×200 MB correntes = 1,4 GB; contador zerava na rotação) (média) | `session_total_` cumulativo por stream (não zera na rotação); budget 1 GB cumulativo | repo realtime_log.cpp |
| C1-N2 | ANR: zip de centenas de MB na main thread (média) | zip em worker thread + toast + share via runOnUiThread | repo GamePickerActivity |
| C1-N3 | Ordem serial→conteúdo no anel podia reportar identidade errada (baixa) | dump copia o record POD e re-verifica o serial (leitura rasgada vira "no longer recorded") | SDK cp.cpp (describe) |
| C1-N4/C3-M2/L1/L2 | Manifest: UP-TO-DATE com commit velho; rootProject dentro de doLast (config-cache); rexglue_dirty hardcoded (média+baixas) | `outputs.upToDateWhen { false }`; paths resolvidos na configuração; dirty do SDK computado | repo build.gradle |
| C1-N5 | Botão "Open logs folder" morto em API 24+ (baixa) | removido (o diálogo já mostra o caminho) | repo GamePickerActivity/strings |
| C3-M1 | Custo por draw ~23× a it. 1 (fmt×3 por draw; 0,45-0,75 µs/draw; até ~2,2 ms/frame @3000 draws) (média) | identidade binária no record (CheckpointDraw/NoteDrawBind, zero fmt no hot path); note "pixel textures" limitado a 8 views + contagem | SDK cp.{h,cpp} |
| C3-L3 | Anel ~10 MB RSS (baixa) | aceito e documentado (device 12 GB; ring de 64 K records é o que dá ±0,7 s de histórico) | review_log |

**Objeções aceitas com documentação (sem mudança):**
- Custo residual do trail por draw (3 escritas POD + fill inter-pass, dezenas de
  ns): default-on deliberado no Android durante a caça ao DEVICE_LOST; cvar
  `vulkan_breadcrumbs` documentado; re-gate planejado quando a causa for fechada.
- `git diff --quiet` não vê mudanças staged; falta de `git` derruba build local —
  CI imune (runner tem git; árvore limpa).
- Wrap do marcador uint32 após ~2³² draws (sessões multihoras): degrada para
  "no longer recorded", sem crash.
- Dump do caminho NV sem prefixo "breadcrumb": alvo declarado é o Turnip.

**Saída**: SDK `1c809cf`, repo `f6c4fd5`, pin atualizado; build 3 despachado.

## Iteração 3 (2026-10-06)

**Entrada**: SDK `b51e37e..1c809cf`, repo `9a2474e..4fcfa11`. Build 37467621505
(f6c4fd5): **verde**.

**Veredictos**: C1 **não impressionado** (0 bloqueadoras, 0 altas, 2 médias + 4
baixas); C2 **IMPRESSIONADO** (A-F ≥ 9; 2 médias classificadas como limites
razoáveis/documentáveis); C3 **IMPRESSIONADO** (0/0/0, A-F ≥ 9; benchmark próprio:
~43 ns/draw pós-fix binário, 10-17× menor que a it. 2; 232 testes OK).

**Médias do C1 → correções (iteração 4):**

| # | Objeção | Correção | Onde |
|---|---|---|---|
| C1-M1 | `Checkpoint()` não zerava os campos binários → identidade fantasma de slot reciclado em draw end/copy/texture load | todos os 4 writers zeram TODOS os campos de identidade | SDK cp.cpp |
| C1-M2 | Janela "pending (suspect)" abria na cabeça e omitia (gpu_reached, serial_now-64] — os suspeitos primários | dump começa em gpu_reached+1 (primeiros 64), nota o trecho omitido, fecha com os últimos 64 | SDK cp.cpp |
| C1-B1 | Formato sem espaço antes do detail | sufixo montado com espaçamento condicional | SDK cp.cpp (describe ×2) |
| C1-B2 | prim/indices perdidos na reescrita binária | campos primitive_type/index_count no record + impressão no dump | SDK cp.{h,cpp} |
| C1-B3/C3-B-N1 | Zip re-entrante corrompia o arquivo | AtomicBoolean + nome único por millis + prune dos 2 mais novos | repo GamePicker/LogSessions |
| C1-B4 | Serial gravado antes do conteúdo (janela de escrita) | serial gravado POR ÚLTIMO em todos os writers | SDK cp.cpp |
| C2-B1 | Linhas "VulkanPresenter" fora do vulkan.log | predicado do sink inclui VulkanPresenter (vulkan.log agora = categoria gpu + presenter + Vulkan/VkResult/breadcrumb) | repo realtime_log.cpp |
| C2-B4 | "colour grading map open" só no all.log | predicado files inclui "colour grading map" | repo realtime_log.cpp |
| C2-B5 | Comentário "newest five" (real: 3) | corrigido + budget 1 GB documentado no header | repo realtime_log.h |
| C2-M2 | Backtrace do tombstone dependente do filtro --pid capturar o crash_dump | segunda captura `logcat -b crash` (logcat_crash.txt, 1 MB rotacionado) | repo PinyonActivity |
| C3-B-N2 | Budget subcontava notas de dedup/rotação | FlushRepeats/rotated/closure notes somam em session_total_ | repo realtime_log.cpp |

**Saída**: SDK `6cf50bb`, repo `43a41ab`, pin atualizado; build 4 despachado.

## Iteração 4 (2026-10-06) — verificação final

**Entrada**: SDK `1c809cf..6cf50bb`, repo `4fcfa11..43a41ab`.



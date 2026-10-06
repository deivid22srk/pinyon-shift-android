# Investigação I2 — Vulkan DEVICE_LOST (Turnip / Adreno 660)

**Task ID:** 3-b · **Agente:** Investigador I2 (Vulkan/DEVICE_LOST/sincronização)
**Branch:** `fix/android-rendering-fmv-textures-20261004` · **SDK pin:** 74a1de0 (`thirdparty/shiftglue-sdk`)
**Dispositito-alvo:** Motorola Edge 30 Fusion, Adreno 660, Turnip Mesa 26.3.0-devel (adrenotools), Vulkan API 1.3.363, Android (KGSL).

Todas as referências `arquivo:linha` são relativas a `thirdparty/shiftglue-sdk/`, exceto quando marcadas como raiz do repo do app. Análise 100% estática (aparelho não acessível).

---

## 1. Síntese executiva

1. **O DEVICE_LOST é detectado e tratado, mas sem diagnóstico.** Existem exatamente 3 pontos que reconhecem `VK_ERROR_DEVICE_LOST` no command processor (`command_processor.cpp:5880`, `:5893`, `:6437`) + 2 no presenter (`vulkan_presenter.cpp:1600`, `:2295`). Em todos, o fluxo termina em `GraphicsSystem::OnHostGpuLossFromAnyThread` → `rex::FatalError` → `std::abort()` (`graphics_system.cpp:315-330`, `include/rex/assert.h:119-124`). Não há recuperação/recriação de device (TODO deixado no código).
2. **A infraestrutura de breadcrumbs JÁ EXISTE, mas depende de `VK_NV_device_diagnostic_checkpoints`**, que o Turnip não expõe: `checkpoints_enabled_ = extensions().ext_NV_device_diagnostic_checkpoints` (`command_processor.cpp:6548`). Com `false`, `Checkpoint()` (6451), `NoteCheckpoint()` (6469) e `LogCheckpoints()` (6482) **viram no-ops** — por isso "sem breadcrumbs é impossível saber qual draw/pipeline causou". O anel de registros e o despejo formatado já estão prontos; falta um marcador que funcione em Mesa/Turnip (§4).
3. **A mensagem "Vulkan Warning … GPU faulted or hung (VK_ERROR_DEVICE_LOST)" vem do driver Turnip**, entregue pelo nosso callback `VK_EXT_debug_utils` (`vulkan_instance.cpp:576-643`, formato "Vulkan {severidade} ({tipo}, ID {id}): {mensagem}" em `:582-591`, logado como `REXGPU_WARN` em `:635`). O texto `tu_knl_kgsl.cc:1817` é do próprio Mesa (mensagens de driver de desenvolvimento incluem arquivo:linha). A captura de mensagens do driver já está completa e ligada por padrão (`vulkan_log_debug_messages=true`, `vulkan_instance.cpp:32`).
4. **Cobertura de `VkResult` é boa nos pontos críticos de submit/present/fence, com 4 lacunas menores**: `vkWaitForPresentKHR` ignorado (`vulkan_presenter.cpp:2287`), `vkInvalidateMappedMemoryRanges` ignorado (`command_processor.cpp:5255`, `:3850`), `vkFlushMappedMemoryRanges` ignorado (`vulkan_util.cpp:45`) e o poll de fences em `:5891-5896` quebra o loop sem log. `vkDeviceWaitIdle` não é usado em lugar nenhum (todo wait é fence-based, `include/rex/graphics/vulkan/command_processor.h:612-615`).
5. **Sincronização CPU↔GPU é fence-por-submission** (não por draw): fences empilhadas em `submissions_in_flight_fences_` (`:6328`), `kMaxFramesInFlight = 3` (`command_processor.h:747`). O caminho FMV (upload de plano Y/UV 8-bit) usa staging do upload pool + `CopyCpuRange` com page-watch + barrier `HOST_WRITE→TRANSFER_READ` + flush não-coerente — a disciplina de barreiras é cuidadosa e não encontramos hazard estrutural óbvio (§5).
6. **512 MB shared memory não-sparse**: sem `sparseBinding` no Turnip/KGSL, o fallback aloca o buffer inteiro de 512 MB device-local dedicado (`shared_memory.cpp:102-158`). Falhas de alocação são logadas e **abortam a inicialização** (não silenciosas); o risco real é pressão de memória (o log do dispositivo já mostra "AHB 4x4 alloc fail"), que é um cofator plausível do hang, não a causa direta.
7. **Hipóteses ranqueadas (§5):** (H1) bug do driver Turnip dev build disparado por SPIR-V específico; (H2) concorrência extrema — até 6 threads "Vulkan Pipelines" compilando enquanto o worker de submit e o UI thread apresentam; (H3) pressão de memória; (H4) hazard no hot-swap de placeholder pipelines / swap compute; (H5) feature não checada (`shaderStorageImageMultisample`).

---

## 2. Mapa do fluxo de frame (decode → record → submit → present)

```
[T1 "GPU Commands"]            [T2 "GPU Recorder" (opcional)]   [T3 "Vulkan Pipelines" ×N]
WorkerThreadMain               RecordThreadMain                  CreationThread
decode PM4, registers          aplica writes + grava draws       compila PSOs async (vkCreateGraphicsPipelines)
        |                                |                                |
        +--------------- IssueDraw / IssueSwap (RecordCall) ----------------+
                                         |
                         deferred_command_buffer_ (tape)
                                         |
        [T4 "GPU Submission" (async, cvar vulkan_async_submission)]
        EndSubmission → ExecuteSubmission → vkQueueSubmit (fence por submission)
                                         |
        [T5 UI thread] VulkanPresenter::PaintLatest
        vkAcquireNextImageKHR → submit blit/gamma/FXAA → vkQueuePresentKHR
```

Estágios, em ordem (arquivo:linha):

| Estágio | Onde | Nota |
|---|---|---|
| Thread "GPU Commands" (loop principal, decode do ring do guest) | `src/graphics/command_processor.cpp:293` (`WorkerThreadMain`), criada em `:214-222` | `ExecutePrimaryBuffer` `:370` |
| Split decode/record opcional | cvar `gpu_record_thread` `src/graphics/command_processor.cpp:51` (default **false**); thread "GPU Recorder" `:387-406` | No dispositivo provavelmente desligado |
| Pacote de swap (VdSwap) → `IssueSwap` | `src/graphics/command_processor.cpp:1553-1560`; `++observation_frame_sequence_`, zera `debug_frame_draw_index_` `:1558-1559` | Contadores de frame/draw usados pelos checkpoints |
| `IssueDraw` → `IssueDrawImpl` (valida estado, memos, pipeline, bindings) | `src/graphics/vulkan/command_processor.cpp:4248` / `:4271` | |
| Gravação do draw no tape | `CmdVkDraw` `:5000`, `CmdVkDrawIndexed` `:5032` (em `deferred_command_buffer_`) | Checkpoint kDraw `:4392`, kDrawEnd `:5035` |
| Split de submission async (a cada N draws) | `:4255-4260` (cvar `vulkan_async_submission_split_draws`) | `IsCreatingPipelines()` evita split durante compilação |
| `IssueSwap` → `IssueSwapImpl` (fecha o frame) | `:2718` / `:2726` | Skip de apresentação com placeholder `:2770-2782` |
| Swap: gamma/FXAA compute na saída do guest | `:2902` (`RefreshGuestOutput`), seleção de pipeline `:2935-2957` | |
| `EndSubmission(is_swap=true)` | `:6138` | sparse binds `:6235-6279`; `SubmitBarriers(true)` `:6281` |
| Caminho async: tape → fila do worker | `:6291-6317` (`submission_jobs_`, `AwaitSubmissionWorker` se present inline `:6335-6337`) | |
| `ExecuteSubmission` → `vkQueueSubmit` | `:6385` / `:6433` | fence `:6286-6290`; submit vazio se gravação falhou no worker `:6414-6418` |
| Thread "GPU Submission" | `SubmissionWorkerMain` `:6578`; start `StartSubmissionWorker` `:6547` (também decide `checkpoints_enabled_` `:6548`) | |
| Present steps adiado no worker | `DeferPresentStep` `:6615`; deferrer registrado em `:2754-2763` | |
| Checagem de fence/DEVICE_LOST por submission | `CheckSubmissionFenceAndDeviceLoss` `:5840`, chamada de `BeginSubmission` `:6016` | Latência de detecção ≈ próximo BeginSubmission |
| Presenter (UI thread): acquire | `src/ui/vulkan/vulkan_presenter.cpp:1593` | timeout `UINT64_MAX` |
| Presenter: submit do blit + UI | `:2206-2256` (`vkQueueSubmit` `:2230`) | acquire/present semaphores `:1591`, `:2205-2215` |
| Presenter: present | `vkQueuePresentKHR` `:2282`; `vkWaitForPresentKHR` `:2287` (throttle ≤1 present em voo) | MAILBOX default no Android (`:54-64`, `:1405-1425`) |
| GPU loss → abort | `graphics_system.cpp:315-330` (`FatalError`), `presenter.cpp:296-299` / `:535-543` / `:683-689` | `host_gpu_loss_reported_` garante 1× |

---

## 3. Tabela de cobertura de VkResult (Q1)

Legenda: **checada** = resultado comparado; o que faz em erro.

| Chamada | Onde (arquivo:linha) | Checada? | Comportamento em erro |
|---|---|---|---|
| `vkQueueSubmit` (command processor) | `src/graphics/vulkan/command_processor.cpp:6433` | **Sim** `:6435` | `REXGPU_ERROR` `:6436`; retorno `false` → submission fica aberta p/ retry (inline) `:6414-6417`. `VK_ERROR_DEVICE_LOST` → `LogCheckpoints()` `:6438`, `device_lost_=true` só no caminho inline `:6440`, `OnHostGpuLossFromAnyThread` `:6443` → **FatalError/abort** |
| `vkQueueSubmit` (presenter, blit swapchain) | `src/ui/vulkan/vulkan_presenter.cpp:2230` | **Sim** `:2238` | `REXGPU_ERROR` `:2239`; rollback dos fences do tracker `:2240-2241`; retorna `kNotPresentedConnectionOutdated` `:2254` (recria swapchain). **DEVICE_LOST não é diferenciado aqui** — cai no genérico |
| `vkQueueSubmit` vazio (fence do UI) | `vulkan_presenter.cpp:2232-2235` | Parcial | `!= VK_SUCCESS` apenas marca `SubmissionSucceededSignalFailed()` `:2234` (sem log próprio) |
| `vkQueuePresentKHR` | `vulkan_presenter.cpp:2282` | **Sim** (switch `:2290`) | `VK_SUCCESS`/`VK_SUBOPTIMAL_KHR` ok; `VK_ERROR_DEVICE_LOST` `:2295-2299` → `kGpuLostResponsible` → callback → **FatalError** (`presenter.cpp:536-538`); OUT_OF_DATE/SURFACE_LOST → info + recria `:2300-2310`; default → erro + recria `:2311-2316` |
| `vkAcquireNextImageKHR` | `vulkan_presenter.cpp:1593` | **Sim** (switch `:1596`) | `VK_ERROR_DEVICE_LOST` `:1600-1604` → `kGpuLostResponsible` → **FatalError**; OUT_OF_DATE/SURFACE_LOST → info `:1605-1613`; default → erro `:1614-1616` |
| `vkWaitForFences` (bloqueante, CP) | `command_processor.cpp:5868-5870` via `VulkanDevice::WaitForFences` (`src/ui/vulkan/device.h:257-265`, retry infinito em `VK_TIMEOUT` — workaround p/ Adreno 830 que retorna TIMEOUT imediato com timeout infinito) | **Sim** `:5875` | `REXGPU_ERROR` `:5878`; `VK_ERROR_DEVICE_LOST` → `device_lost_=true` + `LogCheckpoints()` `:5880-5883` |
| `vkWaitForFences` (poll de progresso, CP) | `command_processor.cpp:5891` | **Sim** `:5892`, porém | `!= VK_SUCCESS` → break **sem log** `:5896`; DEVICE_LOST seta flag `:5893-5895`; dump vem depois em `:5900-5905` |
| `vkWaitForFences` (submission tracker, presenter) | `src/ui/vulkan/vulkan_submission_tracker.cpp:122` | Parcial | Apenas `== VK_SUCCESS` conta; DEVICE_LOST tratado como "não sinalizado" silenciosamente (`:122-129`) |
| `vkDeviceWaitIdle` | — | **Não usada** | Todo wait é por fences (`CheckSubmissionFenceAndDeviceLoss`, `AwaitAllQueueOperationsCompletion` em `command_processor.h:612-615`) |
| `vkWaitForPresentKHR` | `vulkan_presenter.cpp:2287-2288` | **Não** | Retorno descartado (é só throttle com timeout de 50 ms) |
| `vkQueueBindSparse` | `command_processor.cpp:6266-6267` | **Sim** `:6269` | `REXGPU_ERROR` `:6270` + `return false` (submission fica aberta) |
| `vkResetFences` / `vkResetCommandPool` / `vkBegin/EndCommandBuffer` | `:6287` / `:6393` / `:6401` / `:6406` | **Sim** | Logs + `return false` (retry) |
| `vkInvalidateMappedMemoryRanges` (readbacks) | `:5255`, `:3850` | **Não** | Resultado ignorado |
| `vkFlushMappedMemoryRanges` (upload pool) | `src/ui/vulkan/vulkan_util.cpp:45` | **Não** | Resultado ignorado (pular flush de range coerente é correto; falha real seria silenciosa) |
| `vkCreateGraphicsPipelines` (threads de compilação) | `src/graphics/vulkan/pipeline_cache.cpp:3856` | **Sim** `:3859` | Log rico com vs/ps hash, topo, render_pass_key `:3863-3871` → `return false` (pipeline não criada; draw falha, sem crash) |
| `vkGetQueueCheckpointDataNV` | `command_processor.cpp:6493/:6499` | Não (void p/ app) | Irrelevante: só roda com a extensão NV |
| Criações em `SetupContext`/inits (buffers, imagens, memória, descritores) | ex. `shared_memory.cpp:66/:109/:143/:153`; `vulkan_presenter.cpp:2346-2352`; etc. | **Sim** (padrão do arquivo) | Log + `return false` → falha de init → `FatalError("Unable to setup command processor…")` (`src/graphics/command_processor.cpp:294-296`) |

**O que acontece HOJE com `VK_ERROR_DEVICE_LOST` (resumo):**
- CP detecta em fence/submit → `device_lost_ = true` → `BeginSubmission` retorna `false` para sempre (`:6001-6003`) → frames param de ser gravados; `LogCheckpoints()` (no-op no Turnip) → `OnHostGpuLossFromAnyThread` → `rex::FatalError` → `std::abort()` (**SIGABRT** — capturado pelo `crash_reporter_posix.cpp` do app).
- Presenter detecta em acquire/present → `kGpuLostResponsible` → callback de loss → mesmo `FatalError`/abort (`presenter.cpp:296-299`, `:535-543`).
- Ordem importante: no CP, `LogCheckpoints()` roda **antes** do `FatalError` em todos os caminhos (`:5882`, `:5901`, `:6438`) — é a janela onde o dump de breadcrumbs deve acontecer. No caminho do presenter, **não há dump do CP hoje** (gap apontado no §4.5).
- Logcat não perde as últimas linhas: o sink Android do spdlog é síncrono (`src/core/logging.cpp:155-157`, `android_sink_mt`).

---

## 4. Design de breadcrumbs para Turnip/Mesa (Q2)

### 4.1 Por que o mecanismo atual falha no Turnip

- `Checkpoint()` grava `CmdVkSetCheckpointNV` (`command_processor.cpp:6466`) e `LogCheckpoints()` lê `vkGetQueueCheckpointDataNV` (`:6493-6499`); ambos exigem `VK_NV_device_diagnostic_checkpoints`. `checkpoints_enabled_` é derivado da extensão (`:6548-6552`) → **false no Turnip** → todo o pipeline de checkpoints (incluindo o anel `checkpoint_records_` de 64 K registros, `command_processor.h:439-441`) fica inativo.
- `VK_AMD_buffer_marker` também não existe no Mesa/Turnip. A alternativa universal é **core Vulkan 1.0**: `vkCmdFillBuffer` gravando um serial num buffer host-visible persistently mapped + **anel de metadados em RAM do host** (escrito pela CPU no momento do record, nunca pela GPU).

### 4.2 Princípio de funcionamento

- Para cada evento (draw, texture load, copy, resolve, submit, swap), a CPU:
  1. incrementa um serial global e grava os **metadados** num anel POD em RAM do host (imune a device loss);
  2. grava no tape um `vkCmdFillBuffer(marker_buffer, slot, 4, serial)` — quando a GPU **executa** o fill, o valor aparece no buffer mapeado.
- Após `VK_ERROR_DEVICE_LOST`: o mapping continua válido (a spec não invalida `vkMapMemory` em device lost; o conteúdo de escritas **não concluídas** fica indefinido — exatamente a semântica de "último marcador alcançado" que queremos). Varre-se o buffer de marcadores para achar o maior serial presente → esse é o último trabalho **confirmadamente iniciado/concluído**; tudo entre esse serial e o serial atual da CPU estava em voo ou nunca executou → é o conjunto suspeito.

### 4.3 Estruturas (esboço de código)

Em `include/rex/graphics/vulkan/command_processor.h` (ao lado de `CheckpointRecord`, ~linha 430):

```cpp
// Breadcrumbs universais (funcionam sem VK_NV_device_diagnostic_checkpoints):
// anel de metadados em RAM do host + marcadores GPU via vkCmdFillBuffer.
struct BreadcrumbRecord {          // POD fixo — sem string, sem heap
  uint64_t serial;                 // mesmo contador dos checkpoints NV
  uint32_t kind;                   // CheckpointKind (kDraw, kDrawEnd, kCopy, ...)
  uint32_t frame;                  // uint32_t(observation_frame_sequence_)
  uint32_t draw;                   // debug_frame_draw_index_ - 1
  uint32_t submission;             // uint32_t(GetCurrentSubmission())
  uint64_t vs_hash, ps_hash;       // ucode_data_hash() (0 se ausente)
  uint64_t pipeline_hash;          // PipelineDescription::GetHash() (0 p/ não-draw)
  uint32_t rt_color_fmt, rt_depth_fmt;   // VkFormat dos RTs ligados
  uint32_t rt_width, rt_height;
  uint32_t msaa_samples;
  uint32_t flags;                  // bit0 placeholder, bit1 FSI, bit2 scaled, ...
};
static constexpr size_t kBreadcrumbRecords = size_t(1) << 14;   // 16384 × 64 B = 1 MiB
std::vector<BreadcrumbRecord> breadcrumb_ring_;   // alocado uma vez em SetupContext
uint64_t breadcrumb_serial_ = 0;                  // só a thread do CP escreve
// Buffer de marcadores GPU (core 1.0, sem extensão):
static constexpr uint32_t kBreadcrumbMarkerSlots = 4096;
VkBuffer breadcrumb_marker_buffer_ = VK_NULL_HANDLE;             // 4096 × 4 B = 16 KiB
VkDeviceMemory breadcrumb_marker_memory_ = VK_NULL_HANDLE;
uint32_t* breadcrumb_marker_mapped_ = nullptr;   // host-visible + coerente se houver
```

Alocação em `SetupContext()` (`command_processor.cpp:912`, junto aos demais recursos):
- `vkCreateBuffer` usage `VK_BUFFER_USAGE_TRANSFER_DST_BIT`, size `kBreadcrumbMarkerSlots*4`;
- memória: tipo **host-visible && (preferir) host-coherent** (`ChooseHostMemoryType`, como o upload pool faz em `vulkan_upload_buffer_pool.cpp:92-93`); `vkMapMemory` persistente (nunca desmapear);
- zerar o buffer (`vkCmdFillBuffer` 0 num submit inicial ou `memset` no mapping se coerente).

Novo comando no tape — `include/rex/graphics/vulkan/deferred_command_buffer.h` (enum `Command` `:403-436`) e `src/graphics/vulkan/deferred_command_buffer.cpp` (switch do `Execute` `:49`):

```cpp
void CmdVkFillBuffer(VkBuffer buffer, VkDeviceSize offset, VkDeviceSize size, uint32_t data) {
  auto& args = *reinterpret_cast<ArgsVkFillBuffer*>(
      WriteCommand(Command::kVkFillBuffer, sizeof(ArgsVkFillBuffer)));
  args.buffer = buffer; args.offset = offset; args.size = size; args.data = data;
}
// No Execute: dfn.vkCmdFillBuffer(command_buffer, args.buffer, args.offset, args.size, args.data);
```

### 4.4 Onde gravar (hooks exatos, todos já existem como `Checkpoint`/`NoteCheckpoint`)

Funil único — **`VulkanCommandProcessor::Checkpoint()` `command_processor.cpp:6451`** (e `NoteCheckpoint()` `:6469` só no anel host):

```cpp
void VulkanCommandProcessor::Checkpoint(CheckpointKind kind, uint32_t value, std::string detail) {
  if (!checkpoints_enabled_ && !breadcrumbs_enabled_) return;    // novo cvar vulkan_breadcrumbs
  ...
  // (código atual do checkpoint NV permanece, gated em checkpoints_enabled_)
  if (breadcrumbs_enabled_) {
    const uint64_t serial = ++breadcrumb_serial_;                 // compartilhado c/ checkpoint_serial_
    deferred_command_buffer_.CmdVkFillBuffer(
        breadcrumb_marker_buffer_,
        (serial % kBreadcrumbMarkerSlots) * 4, 4, uint32_t(serial));
    BreadcrumbRecord& r = breadcrumb_ring_[serial % kBreadcrumbRecords];
    r = {serial, uint32_t(kind), uint32_t(observation_frame_sequence_),
         value == UINT32_MAX ? debug_frame_draw_index_ - 1 : value,
         uint32_t(GetCurrentSubmission()), vs_hash_, ps_hash_, pipeline_hash_,
         rt_color_fmt_, rt_depth_fmt_, rt_w_, rt_h_, msaa_, flags_};
  }
}
```

Os hashes/formatos já estão disponíveis nos pontos de chamada:
- **draw**: `Checkpoint(kDraw, UINT32_MAX, "vs {:016X} ps {:016X} …")` `:4392-4396` (hashes de ucode) e `pipeline_hash` da `PipelineDescription` em `ConfigurePipeline` (`pipeline_cache.cpp` `description.GetHash()`, usado em `:1435`); RTs: `pipeline_render_pass_key`/`render_target_cache_->last_update_*` (`:4655-4660`); placeholder flag `:4702-4708`.
- **draw end**: `:5035` (`Checkpoint(kDrawEnd)`).
- **copy/resolve**: `:5278` (`kCopy`); adicionar um `Checkpoint(kResolve, …)` em `VulkanRenderTargetCache::Resolve` (`render_target_cache.cpp:1080`, após `GetResolveInfo` `:1090` — hash = `resolve_info` endereço base/extent).
- **texture load**: `texture_cache.cpp:1727-1737` (já chama `Checkpoint` com formato/dimensões) — passa a funcionar também no Turnip.
- **submission**: adicionar `Checkpoint(kTransfer/note)` em `EndSubmission` `:6138` (antes do `vkQueueSubmit`) com `GetCurrentSubmission()`.
- **frame**: `IssueSwapImpl` `:2726` (início) — serial de borda de frame.

**Granularidade e custo:** `vkCmdFillBuffer` por draw é aceitável em modo diagnóstico (o fill é gravado no tape e executado em ordem; em tiler Adreno, fills entre draws não quebram render passes porque os próprios `Checkpoint`s atuais já fazem o equivalente com `vkCmdSetCheckpointNV`). Ainda assim: cvar `vulkan_breadcrumbs` (default **off**; ligar em builds de diagnóstico do dispositivo), com opção `vulkan_breadcrumbs_every` (1 = por draw; 8/32 = amostrado; 0 = só submission/frame).

### 4.5 Leitura segura pós-DEVICE_LOST e despejo

```cpp
void VulkanCommandProcessor::LogBreadcrumbs(const char* reason) {
  if (!breadcrumbs_enabled_) return;
  // (a) último serial alcançado pela GPU — varre o buffer mapeado.
  //     host-coherent: leitura direta. Se o tipo escolhido for não-coerente,
  //     tentar vkInvalidateMappedMemoryRanges (ignorar o VkResult; após
  //     DEVICE_LOST ele próprio pode retornar erro, mas o mapping continua legível).
  uint32_t gpu_reached = 0;
  for (uint32_t i = 0; i < kBreadcrumbMarkerSlots; ++i)
    gpu_reached = std::max(gpu_reached, breadcrumb_marker_mapped_[i]);
  // (b) despeja o anel: últimos ~256 executados + todos os pendentes.
  const uint64_t first = gpu_reached > 256 ? gpu_reached - 256 : 1;
  for (uint64_t s = first; s <= breadcrumb_serial_; ++s) {
    const BreadcrumbRecord& r = breadcrumb_ring_[s % kBreadcrumbRecords];
    if (r.serial != s) continue;                     // slot reciclado
    REXGPU_ERROR("breadcrumb {} #{} frame {} draw {} sub {} vs {:016X} ps {:016X} "
                 "pipe {:016X} rt {}x{} c{} d{} msaa {} flags {:X} [{}]",
                 s <= gpu_reached ? "DONE" : "PENDING", s, r.frame, r.draw, r.submission,
                 r.vs_hash, r.ps_hash, r.pipeline_hash, r.rt_width, r.rt_height,
                 r.rt_color_fmt, r.rt_depth_fmt, r.msaa_samples, r.flags, reason);
  }
}
```

Pontos de chamada do dump (todos no caminho normal de erro, **antes** do `FatalError`/abort — não é signal handler, logo `REXGPU_ERROR`/fmt são seguros; `write(2)` num fd pré-aberto é a opção async-signal-safe se quisermos cobrir também o handler do `crash_reporter`):
1. `CheckSubmissionFenceAndDeviceLoss` — junto de `LogCheckpoints()` em `:5882` e `:5900-5904`.
2. `ExecuteSubmission` — junto de `LogCheckpoints()` em `:6438`.
3. **NOVO (fecha o gap do presenter):** `GraphicsSystem::OnHostGpuLossFromAnyThread` (`src/graphics/graphics_system.cpp:315`) — chamar `command_processor()->DumpBreadcrumbs("presenter-detected loss")` antes do `FatalError` (`:329`); cobre os caminhos `vulkan_presenter.cpp:1600`/`:2295` → `presenter.cpp:535-543` que hoje abortam sem dump do CP. (`graphics_system.h:72` já expõe `command_processor()`.)
4. Opcional: refletir num arquivo `<state>/logs/breadcrumbs-<session>.log` via `write(2)` com fd aberto no `SetupContext` (async-signal-safe para o handler de SIGABRT do app).

**Considerações de correção:**
- O buffer host-visible **sobrevive** ao device lost enquanto não houver `vkDestroyDevice`/`vkFreeMemory` (que nunca rodam antes do abort). Escritas GPU em andamento ficam indefinidas — aceitável: marcador ausente/velho = trabalho não concluído.
- Leitura pós-submit sem device lost pode estar **em cache** se a memória for não-coerente (Turnip: heap de sistema costuma ser `HOST_VISIBLE|HOST_COHERENT`, mas escolher explicitamente o tipo coerente quando existir elimina a dúvida; `FlushMappedMemoryRange` cuida do sentido CPU→GPU, `vkInvalidateMappedMemoryRanges` do GPU→CPU).
- Wrap do serial em 32 bits no marcador: usar `serial & 0xFFFFFFFF` e aceitar ambiguidade > 4 G eventos (irrelevante na prática); ou gravar 8 bytes (2 slots).
- Concorrência: só a thread do CP (e o worker de submission, que só lê) tocam o anel; `breadcrumb_serial_` é exclusivo da thread do CP (igual `checkpoint_serial_` hoje).

---

## 5. Hipóteses ranqueadas para o DEVICE_LOST

### Contexto: sincronização do frame FMV (Q4) — por que hazard estrutural é menos provável

- **Upload FMV (CPU guest → GPU):** `TryLoadTextureDataFromCpu` (`texture_cache.cpp:1348`) pede staging com `Request` (não `RequestPartial`) `:1409-1414` — página contígua garantida (o uso de `RequestPartial` com cópia cheia causava SIGSEGV, comentário `:1401-1408`); `CopyCpuRange` `:1423` recusa páginas GPU-written e habilita watch de escrita CPU durante a cópia (`shared_memory.cpp:276-302`, `EnablePhysicalMemoryAccessCallbacks` `:285`, teste de flags GPU-written `:296`); visibilidade garantida por `FlushWrites`→`vkFlushMappedMemoryRanges` (`vulkan_upload_buffer_pool.cpp:177-181`, coerência checada em `vulkan_util.cpp:30`, flush disparado por `shared_memory_->EndSubmission()` em `command_processor.cpp:6229` → `shared_memory.cpp:196-198`); barrier `HOST_WRITE→TRANSFER_READ` `texture_cache.cpp:1687-1689` + image barrier `:1673-1685` + `SubmitBarriers(true)` `:1690` + `CmdVkCopyBufferToImage` `:1702`.
- **Reuso da staging:** página do pool carrega `last_submission_index_` (`graphics_upload_buffer_pool.cpp:140`) e só volta a gravável após o fence da submission completar (`Reclaim` `:25-42` ← `VulkanSharedMemory::CompletedSubmissionUpdated` `shared_memory.cpp:192-194` ← `CheckSubmissionFenceAndDeviceLoss` `command_processor.cpp:5941`).
- **Granularidade de sync:** fence **por submission** (não por frame/draw): `submissions_in_flight_fences_` `:6328`, `kMaxFramesInFlight=3` (`command_processor.h:747`), semáforos apenas para sparse bind (`:6260-6275`) e presenter (`vulkan_presenter.cpp:1591`, `:2209-2215`).
- **Readback (GPU→CPU):** memexport fast path `:5149` — double-buffer + fence (`submission_written <= submission_completed_` `:5242-5243`) + `vkInvalidateMappedMemoryRanges` `:5255`; full path `:5072` usa `AwaitAllQueueOperationsCompletion` `:5110`. Resolve→shared memory usa barrier `kComputeWrite` com máscara `SHADER_READ|WRITE` (`render_target_cache.cpp:1200-1203`, máscaras `shared_memory.cpp:343-348` — comentário explícito sobre tiled GPUs `:344-345`); invalidação por `RangeWrittenByGpu` (`texture/pipeline/cache.cpp:413`, `shared_memory.cpp:325-341`).
- **Barriers do shared memory:** `VulkanSharedMemory::Use` `:200-228` — barrier de commit (offset/size do write anterior) quando o uso se repete, barrier full-size quando troca de estágio. Não encontramos WAW/WAR estrutural. Pontos de atenção: (a) `Use(kRead)` com `written_range` zero não emite barrier (correto); (b) no split assíncrono de submissions (`:4255-4260`) as dependências entre submissions seguem a ordem da fila — válido na mesma queue.

### Hipóteses (ordem = probabilidade)

| # | Hipótese | Evidência (arquivo:linha) | Como confirmar |
|---|---|---|---|
| **H1** | **Bug do driver Turnip 26.3.0-devel disparado por SPIR-V específico** (ex.: memexport com SSBO writes; FSI interlock; tessellation) em um draw do jogo 3D | Hang ~4 s após save de profile (transição de cena → novo conjunto de PSOs/shaders); 371 pipelines compiladas em runtime sem pack; `vkCreateGraphicsPipelines` concorrentes `pipeline_cache.cpp:3856`; memexport exige `SHADER_READ|WRITE` no buffer 512 MB (`shared_memory.cpp:343-348`) | Breadcrumbs (§4) → hash de pipeline/VS/PS do último draw DONE/PENDING; bisect com `fh1_debug_skip_draws` (`src/graphics/command_processor.cpp:2094-2106`); reproduzir com `vulkan_readback_memexport=off` |
| **H2** | **Concorrência extrema no driver**: até 6 threads "Vulkan Pipelines" (`pipeline_cache.cpp:396-399`: `3/4 × 8 CPUs`) compilando PSOs enquanto o worker "GPU Submission" (`command_processor.cpp:6578`) faz `vkQueueSubmit` e o UI thread apresenta | DEVICE_LOST ocorre justamente sob compilação pesada; dev build do Mesa é sabidamente menos estável sob carga multi-thread | `vulkan_pipeline_creation_threads=1` (e `2`) + `vulkan_async_submission=false` — se o hang desaparecer, é H2; breadcrumbs mostram interleave submit/creation |
| **H3** | **Pressão de memória**: 512 MB device-local não-sparse (`shared_memory.cpp:102-158`) + caches crescendo → falha de alocação interna do driver → fault | Log do dispositivo: "AHB 4x4 alloc fail (format 59/56)", fallback YUV 4:2:2; `LogMemoryBudget` existe (`command_processor.cpp:679`, VK_EXT_memory_budget) | Correlacionar `vulkan_memory_budget_log_seconds` com o timestamp do fault; testar com caches menores/`OnReduceMemory` `:732` |
| **H4** | **Hazard no hot-swap de placeholder pipelines ou no swap gamma/FXAA compute** — draw pendente referenciando layout antigo no tiler | Hot-swap assíncrono `pipeline_cache.cpp:1401-1418`, destruição diferida por submission `:3884-3888`; invalidação de descritores incompatíveis `command_processor.cpp:4724-4748`; swap compute `:2935-2957` | `vulkan_async_pipeline_no_placeholder=false` (usa placeholder + espera) e `vulkan_swap_post_effect=none`; breadcrumbs por estágio do swap |
| **H5** | **Feature assumida sem checagem**: `shaderStorageImageMultisample` — o próprio capability report admite "nothing checks it yet: a candidate for wrong multisampled surfaces" (`vulkan_device.cpp:246-249`) | Adreno 660 suporta, mas se um formato/amostragem cair fora… | `vulkan_capability_report=true` no dispositivo e revisar o relatório contra os usos (`msaa_2x_attachments_supported_` etc. `render_target_cache.cpp:374-385`) |

**Sobre a origem da mensagem (Q3), recapitulando:** `"Vulkan Warning … tu_knl_kgsl.cc:1817: GPU faulted or hung (VK_ERROR_DEVICE_LOST)"` = severidade WARNING do driver Turnip, entregue via `VK_EXT_debug_utils`, formatada pelo nosso `DebugUtilsMessengerCallback` (`vulkan_instance.cpp:576-643`; "Vulkan " + severidade em `:582`; mensagem do driver em `:590-591`; `REXGPU_WARN` `:635`). Messenger criado em `:496-535` quando a extensão existe e `vulkan_log_debug_messages=true` (default, `:32`). **Não é log gerado por nós** — grep por "GPU faulted"/"Vulkan Warning" em `src/` não encontra nada. O que já existe de captura: severidades verbose→error mapeadas aos níveis do logger GPU (`:500-517`), tipos general/validation/performance (`:521-524`), objetos/labels anotados (`:594-625`).

**Recursos e features (Q5) — o que o device pede:** features 1.0 espelhadas "suportado→habilitado" em `vulkan_device.cpp:874-891` (robustBufferAccess, fullDrawIndexUint32, independentBlend, geometryShader, tessellationShader, sampleRateShading, depthClamp, fillModeNonSolid, samplerAnisotropy, occlusionQueryPrecise, vertexPipelineStoresAndAtomics, fragmentStoresAndAtomics, shaderClipDistance, shaderCullDistance, sparseBinding, sparseResidencyBuffer); 1.2 `:895-897` (samplerMirrorClampToEdge, uniformBufferStandardLayout, scalarBlockLayout); 1.3 `:907-908` (shaderDemoteToHelperInvocation, dynamicRendering); EXT `:959-988` (fragmentShaderSampleInterlock/PixelInterlock, nonSeamlessCubeMap, customBorderColors/WithoutFormat, nullDescriptor, presentId/presentWait). Requisitos duros (rejeitam o device): independentBlend `:308`, fragmentStoresAndAtomics `:324`, vertexPipelineStoresAndAtomics `:331`; opcionais por cvar: geometryShader `:338`, fillModeNonSolid `:345`. MSAA/resolve: 2x checado com 6 contadores de amostragem (`render_target_cache.cpp:374-382`), 4x avisado `:367-369`; `k_16_16_16_16` tem fallback SNORM→SFLOAT `:289-299`, formatos UINT de transferência `:336-365`, e transferências bit-exatas mudam para o caminho FSI `:396-407`; 2x não suportado vira 4x no transfer `:1706-1711` e desliga attachments `:1820-1823`. **Falta**: checagem efetiva de `shaderStorageImageMultisample` (`vulkan_device.cpp:246-249`).

**512 MB shared memory (Q6):** `kBufferSizeLog2=29` → 512 MB (`include/rex/graphics/shared_memory.h:29-30`). Com sparse (`shared_memory.cpp:64-98`), aloca em blocos de 4 MB (`kHostGpuMemoryOptimalSparseAllocationLog2=22`, `shared_memory.h:152`) via `vkQueueBindSparse` com semáforo (`command_processor.cpp:6248-6273`). Sem sparse (Turnip/KGSL): buffer único device-local **dedicado** de 512 MB (`shared_memory.cpp:102-158`, log em `:103-107` — a mensagem do log do dispositivo). **Não é um pool que "enche"**: mapeia todo o espaço físico guest; quem enche são o upload pool (páginas host-visible, `Request` falha → `REXGPU_ERROR` `:304` → draw falha sem crash) e os heaps do driver (→ H3). Falhas de criação do buffer/memória são logadas e **derrubam a inicialização com FatalError** (`:109-157` → `Initialize` false → `SetupContext` false → `src/graphics/command_processor.cpp:294-296`) — não há falha silenciosa de criação. Corrupção silenciosa de conteúdo não decorre do buffer em si.

---

## 6. Pontos de hook para a Tarefa A (logs por frame)

Objetivo: por frame — nº de draws, resolves, placeholder draws, tempos CPU/GPU.

| Métrica | Onde hookar (arquivo:linha) | Detalhe |
|---|---|---|
| **Nº de draws por frame** | `pending_draw_calls_` incrementado em `command_processor.cpp:4999` (draw) e `:5031` (draw indexed); zerado/contabilizado no swap `:2729-2730` (`PERF_counter_add(kDrawCalls, …)`); `debug_frame_draw_index_` zerado por swap (`src/graphics/command_processor.cpp:1559`) | Logar `pending_draw_calls_` + `debug_frame_draw_index_` no início de `IssueSwapImpl` (`:2728`, já com `observation_frame_sequence_`) ou em `EndSubmission` no fechamento do frame (`:6340-6347`) |
| **Submissions por frame** | `closed_frame_submissions_[(frame_current_++) % kMaxFramesInFlight] = GetCurrentSubmission() - 1` `:6346`; delta = submissions do frame | Log no mesmo ponto (`:6347`, junto do `LogMemoryBudget()`) |
| **Placeholder draws** | `frame_used_async_placeholder_pipeline_` setado por draw `:4708`, zerado por frame `:6073`; hoje só há um warn único global `:2774-2780` | Trocar por contador `frame_async_placeholder_draws_++` em `:4708` e logar no swap |
| **Resolves** | `VulkanRenderTargetCache::Resolve` `render_target_cache.cpp:1080` (retorno com `written_address/length` `:1233-1234`); chamado de `IssueCopy` `command_processor.cpp:5290`/`:5303`; caminho nativo `fh1_native_executor_->NativeResolve` `:5284` | Contador por frame + endereço/extensão (útil: resolve para memória que o FMV lê) |
| **Texture loads (incl. FMV)** | `LoadTextureDataFromResidentMemoryUntimed` `texture_cache.cpp:1722` (checkpoint já traz formato/dimensões `:1727-1737`); fast path FMV `:1348` com contadores de debug `:1424-1431`, `:1553-1556`, `:1626-1629`, `:1647-1651` | Agregar por frame; `upload_stats_` (UploadKind) já existe (`include/rex/graphics/shared_memory.h:84-107`, `BeginUploadFrame` `:92`) |
| **Tempo GPU por frame** | `BeginFrameGpuTiming` `:6648` / `EndFrameGpuTiming` `:6669` / `ReadFrameGpuTiming` `:6684` (query pool de timestamps, slots por `kMaxFramesInFlight`; design documentado em `command_processor.h:616-621`) | `ReadFrameGpuTiming` roda no próximo `BeginSubmission` (`:6069`) — logar o delta lá |
| **Tempo CPU (commands thread)** | `kGpuDecoderCpuNs` `src/graphics/command_processor.cpp:327`, `kGpuThreadIdleNs` `:356`, `kGpuThreadFenceWaitNs` (CP) `command_processor.cpp:5871`/`:6642`, `kGpuSubmissionBusyNs` (worker) `:6600` | PERF counters já existem — basta incluí-los no log por frame |
| **Fence waits / atraso de fila** | `CheckSubmissionFenceAndDeviceLoss` `:5867-5874` (tempo de wait medido) | Logar quando > 1 ms (indicativo de GPU-bound ou hang iminente — valor diagnóstico para DEVICE_LOST: hangs do Turnip se anunciam com waits crescentes) |
| **Fim de frame consolidado** | `EndSubmission(is_closing_frame)` `:6340-6347` (após `frame_open_ = false` `:6344`) | Ponto ideal do log único por frame: draws, submissions, placeholders, resolves, tempos |
| **Ciclo de vida/pressão** | `LogMemoryBudget` `:679` (chamado por frame em `:6347`); `ClearCaches`/`OnReduceMemory` `:673`/`:732` | Já existem; garantir `vulkan_memory_budget_log_seconds` razoável no dispositivo |

---

## 7. Próximos passos recomendados (para o main agent)

1. **Implementar breadcrumbs (§4)** no SDK: cvar `vulkan_breadcrumbs`, `CmdVkFillBuffer` no `DeferredCommandBuffer`, anel POD, `LogBreadcrumbs()` nos 3 pontos do CP + hook em `OnHostGpuLossFromAnyThread`. É o pré-requisito para qualquer diagnóstico de DEVICE_LOST no Turnip.
2. **Fechar as lacunas de VkResult** (baixo risco): logar o break silencioso em `:5896`; checar `vkInvalidateMappedMemoryRanges`/`vkFlushMappedMemoryRanges`; tratar DEVICE_LOST no submit do presenter com dump.
3. **Experimentos no dispositivo (sem código novo):** `vulkan_pipeline_creation_threads=1`, `vulkan_async_submission=false`, `vulkan_readback_memexport=off`, `vulkan_swap_post_effect=none` — cada um isola H1/H2/H4.
4. **Tarefa A**: inserir o log por frame nos hooks do §6 para correlacionar contadores com o timestamp do fault.
5. Considerar um `.pnsp` (pack de pipelines) para reduzir a compilação runtime em 371→0 no primeiro play (mitiga H2 e melhora o warm-up) — infra já existe (`fh1_shader_pack`, `pipeline_storage_file_` `pipeline_cache.cpp:1428-1439`).

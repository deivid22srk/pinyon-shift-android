# Investigação I4 — Assets / VFS / arquivos faltando / áudio / dumps de config

Task ID: 3-d · Branch: `fix/android-rendering-fmv-textures-20261004` · SDK pin `74a1de0`
Escopo: resolução de caminhos guest→host, falhas de NtCreateFile, `AMB_Redstone.fsb`, XMA, `M2_TRACE slist.pop.empty`, e pontos de hook para os artefatos da Tarefa A (`files.log`, `config_dump.txt`, `device_info.txt`).
Todas as referências `arquivo:linha` são relativas a `thirdparty/shiftglue-sdk/` (prefixo **SDK**) ou à raiz do repo `pinyon-shift-android/` (prefixo **repo**).

---

## 1. Síntese executiva

1. **A cópia do disco do usuário está incompleta (MTP)** — é um fato confirmado por design: o picker recria pastas que o MTP derruba (`repairMtpDroppedDirectories`, repo `android/app/src/main/java/dev/pinyon/shift/GamePickerActivity.java:238-245`) e avisa que `media/dynamicpost/colourgradingmaps` está vazio (**GamePickerActivity.java:198-202**, tag logcat `PinyonShiftPicker` = constante `TAG` em **:55**). O aviso aparece 3× porque `refreshUi()`/`readyDetail()` roda em `onCreate`, `onResume` e ao iniciar o jogo.
2. **A falta de `colourgradingmaps` é a explicação mais provável para parte do sintoma "bloom/HDR estourado"** (sintoma 5 do worklog): sem as LUTs de exposure/colour grading "exposure and color grading can look blown out" (string oficial, repo `android/app/src/main/res/values/strings.xml:13`; doc do método **GamePickerActivity.java:206-215**). Isso não explica blur/listras/espelho amarelo (renderização, times I1–I3), mas **contamina o teste visual** — o usuário deve recopiar a pasta do disco antes do próximo log para isolar a variável.
3. **`AMB_Redstone.fsb`**: banco de som FMOD (ambience) aberto pelo guest via `NtCreateFile`; falha com `0xC000000F` = `X_STATUS_NO_SUCH_FILE` (**SDK** `include/rex/system/xtypes.h:47`). Provável arquivo ausente na cópia MTP (mesma família do colourgradingmaps). O detalhe "device not found" que acompanha a falha no log indica que **o caminho base não casou com nenhum dispositivo montado** — hoje só há duas hipóteses (arquivo/pasta ausente com prefixo não registrado, ou caminho com prefixo `\??\` que `NtCreateFile` **não** normaliza); o `files.log` da Tarefa A fecha essa questão. Severidade: média (som ambiente ausente; não trava).
4. **XMA "cannot resolve logical packet (status 4)"**: `status 4` = `XmaPacketStatus::kPacketOutOfRange` (**SDK** `include/rex/audio/xma/context.h:174-180`). Consequência: payload zerado → **silêncio momentâneo (dropout), não distorção**; log limitado a 1× por status por contexto. Já documentado como ruído esperado no **repo** `docs/TROUBLESHOOTING.md:207-210`.
5. **`M2_TRACE slist.pop.empty`**: vem do **SDK** (kernel export `InterlockedPopEntrySList`, **SDK** `src/kernel/xboxkrnl/xboxkrnl_threading.cpp:1387-1418`, WARN em **:1403**), disparado por comportamento do guest (pop em lista vazia — retorno 0 é o comportamento do console real, comentário **:1397-1399**). Benigno; loga só os 4 primeiros por thread (**:1400-1406**).
6. **A infraestrutura para a Tarefa A quase toda já existe**: funil único de opens no VFS (`VirtualFileSystem::OpenFile`, **SDK** `src/filesystem/virtual_file_system.cpp:207`), registry iterável de cvars (`rex::cvar::GetRegistry()`, **SDK** `include/rex/cvar.h:178`), relatório de capacidades Vulkan já implementado (`VULKAN_CAPABILITY_REPORT`, **SDK** `src/ui/vulkan/vulkan_device.cpp:76-252`) e canal de dump por sessão (`pinyon_shift::diagnostics`, repo `src/pinyon_shift_diagnostics.cpp:172-262`). Faltam apenas o ponto de gravação por arquivo e o enriquecimento (caminho host, motivo, propriedades Android).
7. **Classificação das falhas**: `d:\DebugOptions.ini` e `\Device\Image` são benignas e documentadas (**TROUBLESHOOTING.md:211-214**); `game:\media\db\patch\*` é provavelmente benigno (overlay de patch de DB ausente no retail); `colourgradingmaps` é o único problema real de assets confirmado; `AMB_Redstone.fsb` é médio.

---

## 2. Mapa de resolução de caminhos guest→host (Q1)

### 2.1 Montagem do VFS (runtime setup)

Toda a montagem acontece em `Runtime::SetupVfs()` — **SDK** `src/system/runtime.cpp:294-375`:

| Caminho guest | Symlink | Dispositivo | Raiz host | Linha |
|---|---|---|---|---|
| `game:\...`, `d:\...` | `game:`/`d:` | `\Device\Harddisk0\Partition1` | `game_data_root_` (read-only, exceto se cvar `allow_game_relative_writes`) | runtime.cpp:306-323 |
| `update:\...` | `update:` | `\Device\Harddisk0\PartitionUpdate` | `update_data_root_` (se fornecido) | runtime.cpp:326-337 |
| `cache:\...` | `cache:` | `\Device\Harddisk0\Cache` | `cache_root_` (gravável) | runtime.cpp:343-358 |
| `\Device\Harddisk0\Partition0/Cache0/Cache1` | — | `NullDevice` (IO falso bem-sucedido) | — | runtime.cpp:360-372 |

Extras registrados depois:
- **Mods**: `OverlayDevice` no mesmo mount `\Device\Harddisk0\Partition1`, via `ReplaceDevice` (repo `src/pinyon_shift_app.cpp:784-795`; dispositivo em repo `src/mod/overlay_device.cpp:13-22`, resolução com override de arquivos em **:81-98** — arquivos de mods substituem, diretórios não escondem os do jogo).
- **XContent/DLC**: `\Device\Content\<N>\` + symlink `<root_name>:` por pacote, desmontado no destrutor (**SDK** `src/system/xam/content_manager.cpp:41-60`).
- **Guest pode criar symlinks** via `ObCreateSymbolicLink` (**SDK** `src/kernel/xboxkrnl/xboxkrnl_ob.cpp:173-189** — nota: **:180-182** remove o prefixo `\??\`).

`game_data_root_` no Android vem de `PINYON_SHIFT_GAME_ROOT` (repo `src/pinyon_shift_app.cpp:413-415`), setado pelo JNI a partir da escolha do picker (repo `src/android_host_jni.cpp:122-128`).

### 2.2 Fluxo de um `NtCreateFile` (guest → host)

1. **Tradução do nome**: `NtCreateFile_entry` (**SDK** `src/kernel/xboxkrnl/xboxkrnl_io.cpp:155-245`) lê o `X_ANSI_STRING` dos object attributes e faz `TranslateAnsiPath` (trim) — **SDK** `include/rex/system/util/string_utils.h:36-39`, chamado em **xboxkrnl_io.cpp:177**.
2. **Observer pré-open**: `SetGuestFileOpenObserver` (**xboxkrnl_io.cpp:39-44**) é invocado em **:182-184** com o caminho guest (sem resultado). O app registra `PinyonShiftObserveGuestFileOpen` (repo `src/pinyon_shift_runtime_hooks.cpp:583-610`; registro em repo `src/pinyon_shift_app.cpp:829`).
3. **`VirtualFileSystem::OpenFile`** (**SDK** `src/filesystem/virtual_file_system.cpp:207-355**) — o **funil único** de todos os opens (kernel `NtCreateFile`/`NtOpenFile` em **xboxkrnl_io.cpp:203-206/250-253**, CRT do guest `CreateFileA` em **SDK** `src/kernel/crt/file.cpp:63-85` chama `file_system()->OpenFile` diretamente em **:72**, cópia de arquivos CRT em **:468-481**).
4. **Diretório base**: `base_path = utf8_find_base_guest_path(path)` (**virtual_file_system.cpp:230**) → `ResolvePath(base_path)` (**:232**).
5. **`ResolvePath`** (**virtual_file_system.cpp:117-166**):
   - canonicaliza (remove `.`/`..` preservando o "drive" — **SDK** `src/core/utf8.cpp:681-710`);
   - resolve symlinks iterativamente, case-insensitive (**:98-115**);
   - acha o dispositivo por prefixo (`utf8_starts_with_case`) na ordem de registro (**:131-133**);
   - repassa o caminho relativo ao `Device::ResolvePath`.
   - **Falha "sem dispositivo"**: WARN `VFS: '{}' -> [no device]` (**:135**) + ERROR `ResolvePath({}) failed - device not found` (**:139** — única origem da string "device not found" no código, confirmado por grep).
6. **`HostPathDevice::ResolvePath`** (**SDK** `src/filesystem/devices/host_path_device.cpp:59-114**): árvore de entries em memória + **fallback case-insensitive no host** (stat exato **:81-91**, depois scan do diretório **:93-107**) — logo *case sensitivity não é* a causa provável de arquivos existentes não abrirem.
7. **Validação de existência/disposição** em `OpenFile`: `kOpen`/`kOverwrite` exigem entry existente → sem entry retorna `X_STATUS_NO_SUCH_FILE` (**virtual_file_system.cpp:276-279**); base inexistente → idem (**:233-236**); entries cacheadas ausentes no host são invalidadas (**:259-268**); escrita em read-only é rebaixada para leitura com WARN (**:296-301**).

### 2.3 O que é logado hoje numa falha (formato)

- **Linha de falha do kernel**: **xboxkrnl_io.cpp:239-241** `REXKRNL_IMPORT_FAIL("NtCreateFile", "path='{}' -> {:#x}", target_path, result)` → macro em **SDK** `include/rex/system/kernel_state.h:61` = `REXKRNL_WARN("[NtCreateFile] FAILED: " ...)`. Resultado no log: `[warning] [krnl] [NtCreateFile] FAILED: path='<caminho GUEST>' -> 0xc000000f`.
  - **Tem**: caminho guest, status NT.
  - **Não tem**: caminho host resolvido, motivo (sem dispositivo vs. entry ausente vs. disposição), nem o chamador (LR). Sucesso só aparece em `REXKRNL_NOISY_TRACE` (**:242**), gated por cvar `log_noisy`.
- **Linhas do VFS**: "[no device]"/"device not found" (**virtual_file_system.cpp:135/139**, sempre); "entry not found" apenas em DEBUG (**:158-162**); resolução bem-sucedida apenas em TRACE com caminho host (**:150-155** — `entry->absolute_path()`).
- **`NtQueryFullAttributesFile`** (probe de existência) retorna `X_STATUS_NO_SUCH_FILE` em **xboxkrnl_io.cpp:649-650** **sem WARN** — probes de arquivo "invisíveis" no log atual.
- CRT `CreateFileA` falha só em `REXKRNL_NOISY_DEBUG` (**crt/file.cpp:77**).

### 2.4 Onde entra o hook de `files.log`

Ponto recomendado (único, pega tudo):
- **`VirtualFileSystem::OpenFile`** — **SDK** `src/filesystem/virtual_file_system.cpp:207**: ao final (antes de cada `return` de falha, ou num wrapper no início + captura do resultado), registrar `<caminho guest>` → `<caminho host resolvido>` (resolver `Entry*` via `ResolvePath(path)` quando aplicável) + status + ação + motivo. Vantagem: cobre `NtCreateFile`, `NtOpenFile`, rexcrt e XContent.
- Complementos: **`NtCreateFile_entry`** em **xboxkrnl_io.cpp:239** (para capturar `CurrentGuestLr()` — **:46-53** — e identificar quem pediu) e **`NtQueryFullAttributesFile`** em **:649-650** (probes).
- Canal de saída pronto: `pinyon_shift::diagnostics::RecordEvent` (repo `src/pinyon_shift_diagnostics.h:30-32`; impl **.cpp:238-262**, JSONL `<state>/logs/<session>.jsonl` com mutex e flush — **:254-259**; session/path definidos em **:180-181**). Para um `files.log` dedicado, replicar o padrão (append + flush).
- Cuidado de volume: deduplicar por `(path, status)` com contadores (o jogo re-prova os mesmos caminhos a cada boot).

---

## 3. Classificação das falhas de arquivo observadas no log (Q7)

| Falha no log | Classificação | Evidência / quem pede | Impacto |
|---|---|---|---|
| `media/dynamicpost/colourgradingmaps has no files` (picker, 3×) | **CRÍTICO para qualidade visual** (não trava) | `isColourGradingMapsEmpty` + WARN — **GamePickerActivity.java:198-221**; doc do método **:206-215** ("exposure/bloom grading can look blown out"); string `picker_warning_no_grading_maps` — repo `android/app/src/main/res/values/strings.xml:13` | Bloom/HDR estourado (correlaciona com sintoma 5 do worklog). **Ação: recopiar a pasta do disco.** |
| `[NtCreateFile] FAILED: path='game:\media\dynamicpost\colourgradingmaps\...' -> 0xc000000f` | Crítico p/ visual | Mesma causa: pasta recriada vazia por `repairMtpDroppedDirectories` (**GamePickerActivity.java:238-245**) | LUTs não carregam |
| `AMB_Redstone.fsb → NtCreateFile 0xc000000f (+ device not found)` | **MÉDIO** — som ambiente ausente | Ver §4.1 | Sem áudio ambiente; jogo continua |
| `[NtCreateFile] FAILED: path='d:\DebugOptions.ini'` | **BENIGNO** | Documentado como esperado em todo boot: **docs/TROUBLESHOOTING.md:211-214** ("optional developer-only settings file that the retail dump never ships"); `d:` → mesmo device do jogo (runtime.cpp:322) | Nenhum |
| `[NtCreateFile] FAILED: path='\Device\Image...'` | **BENIGNO** | Mesma linha de doc (**TROUBLESHOOTING.md:211-214** — probe de namespace de device) | Nenhum |
| `game:\media\db\patch\* → NtCreateFile 0xc000000f` | **PROVÁVEL BENIGNO** | `media\db` é o diretório de databases do jogo (ex. `media\db\gamedb.slt` citado em repo `src/mod/overlay_device.cpp:48`); `patch\` é um overlay de patch (title-update) que o retail base não traz — o jogo cai no DB base | Nenhum até prova em contrário; confirmar padrão exato no files.log |
| `game:\media\effects\`, `game:\media\stringtables\en\` (0xc000000f, build 33) | BENIGNO p/ boot | Pastas vazias que o MTP derruba; recriadas por **GamePickerActivity.java:241-242** (comentário **:229-237** cita o log do build 33) | Lacuna: se o jogo lê *arquivos* dessas pastas (efeitos/stringtables), pode faltar conteúdo — não avisado pelo picker |
| `XmaContext N: cannot resolve logical packet (status 4)` | **BENIGNO/limitado** | **TROUBLESHOOTING.md:207-210** ("a few of these right after loading a save … counted, bounded") | Dropout breve de áudio |
| `M2_TRACE slist.pop.empty` | **BENIGNO** | **xboxkrnl_threading.cpp:1397-1406** (retorno 0 = comportamento do console); já listado como ruído em repo `loop/BASELINE.md:35` | Nenhum |

---

## 4. Áudio: `AMB_Redstone.fsb` e XMA (Q2, Q3)

### 4.1 AMB_Redstone.fsb

- **O que é**: `.fsb` = *FMOD Sample Bank*. Prefixo `AMB_` = banco de *ambience* (ambiente sonoro); "Redstone" é o identificador do banco no FH1. Não há nenhum handler específico no SDK (grep por `fsb`/`FMOD` em `src/` sem resultados): é um arquivo de dados comum — o guest abre com `NtCreateFile`, lê com `NtReadFile` e submete o payload XMA ao decoder via `XMAInitializeContext` (**SDK** `src/kernel/xboxkrnl/xboxkrnl_audio_xma.cpp:125-192**, que valida/registra os buffers de entrada física e o buffer de saída).
- **Por que falha (0xC000000F = `X_STATUS_NO_SUCH_FILE`, xtypes.h:47)**:
  1. **Hipótese principal**: arquivo ausente na cópia do disco — o MTP já comprovadamente derrubou pastas/arquivos dessa cópia (colourgradingmaps; o picker só conhece/repara 3 pastas, **GamePickerActivity.java:238-245**). Verificar no host: `<game_root>/media/audio/**/AMB_Redstone.fsb`.
  2. **Hipótese secundária (o par "device not found")**: a linha `ResolvePath({}) failed - device not found` (**virtual_file_system.cpp:139**) só ocorre quando **nenhum dispositivo casa com o prefixo do caminho base**. Como `game:`/`d:` estão sempre registrados (runtime.cpp:321-322), isso aponta para um caminho com prefixo não registrado — ex. `\??\game:\...`: `NtCreateFile` **não** remove `\??\` (a canonicalização não o faz — utf8.cpp:681-710; e os symlinks são keyed por `game:`), enquanto `ObCreateSymbolicLink` (**xboxkrnl_ob.cpp:180-182**) e `NtOpenSymbolicLinkObject` (**xboxkrnl_io.cpp:740-742**) **removem** — precedente inconsistente no próprio código. Se for isso, o arquivo poderia até existir no host e ainda assim falhar.
  3. **Descartado**: *case sensitivity* — existe fallback case-insensitive (**host_path_device.cpp:68-107**).
- **É crítico?** Não para funcionamento: é o banco de som ambiente; o jogo continua (o worklog não relata crash de áudio). Severidade **média**: perda de ambiência sonora. O `files.log` (Tarefa A) capturará o caminho guest exato e o motivo, encerrando a ambiguidade entre (1) e (2).

### 4.2 XMA — "cannot resolve logical packet N (status 4)"

- **Onde é emitido**: `XmaContext::WarnPacketResolution` — **SDK** `src/audio/xma_context.cpp:533-547** (WARN em **:544**), chamado por `AssemblePacketPayloads` (**:585**) e por `Decode` (**:970, :988**).
- **O que status 4 significa**: `XmaPacketStatus::kPacketOutOfRange` — enum em **SDK** `include/rex/audio/xma/context.h:174-180** (0 `kValid`, 1 `kInvalidContext`, 2 `kBufferInvalid`, 3 `kNullAddress`, **4 `kPacketOutOfRange`**). Na resolução (`ResolvePacket`, **xma_context.cpp:127-159**): o pacote lógico excede o `packet_count` do buffer atual, "derrama" para o buffer alternado (**:138-141**) e ainda assim fica fora da contagem dele (**:152-154**).
- **Severidade real**: **dropout/silêncio, não distorção**. Em `AssemblePacketPayloads` o destino é zerado e o status `kPacketUnavailable` retornado (**:581-586**); em `Decode`, `kPacketOutOfRange` (diferente de `kBufferInvalid`) faz `data->error_status = 4` e retorna sem decodificar o frame (**:981, :991, :1015**) — o trecho vira silêncio e a decodificação continua. O log é limitado a 1× por status por contexto (`packet_warning_mask_`, **:539-543**) e documentado como ruído esperado (**docs/TROUBLESHOOTING.md:207-210**).

### 4.3 M2_TRACE slist.pop.empty (Q4)

- **Origem exata**: SDK — export do kernel `InterlockedPopEntrySList_entry`, **SDK** `src/kernel/xboxkrnl/xboxkrnl_threading.cpp:1387-1418**; WARN `M2_TRACE slist.pop.empty plist=… caller lr=…` em **:1403**.
- **Semântica**: o **guest** (código do jogo) fez pop numa SList vazia; o export retorna 0 "the same way the console does" (comentário **:1397-1399**). O log existe porque "a caller not checking for null crashes reading just past the null pointer" — é diagnóstico preventivo, limitado aos 4 primeiros por thread (`thread_local empty_pops`, **:1400-1406**).
- **Benigno?** Sim — espelha hardware real; já catalogado como ruído conhecido desde o build #23 (repo `loop/BASELINE.md:35`).

---

## 5. Pontos de hook para a Tarefa A (Q1, Q5, Q6)

### 5.1 `files.log` — toda falha de NtCreateFile/VFS com caminho resolvido e motivo

| Item | Local (arquivo:linha) |
|---|---|
| Funil único de opens (kernel + CRT + content) | **SDK** `src/filesystem/virtual_file_system.cpp:207` (`VirtualFileSystem::OpenFile`) |
| Falhas atuais sem host path/motivo | **SDK** `src/kernel/xboxkrnl/xboxkrnl_io.cpp:239-241` (formato `path='{}' -> {:#x}`; macro em `include/rex/system/kernel_state.h:61`) |
| "device not found" (prefixo sem device) | **SDK** `src/filesystem/virtual_file_system.cpp:135, 139` |
| Entry não encontrada (hoje DEBUG) | **SDK** `src/filesystem/virtual_file_system.cpp:158-162` |
| Probe de existência sem log de falha | **SDK** `src/kernel/xboxkrnl/xboxkrnl_io.cpp:649-650` (`NtQueryFullAttributesFile`) |
| Observer pré-open existente (sem resultado) | **SDK** `src/kernel/xboxkrnl/xboxkrnl_io.cpp:39-44` (reg. **:182-184**); consumidor repo `src/pinyon_shift_runtime_hooks.cpp:583` |
| Identidade do chamador (LR do guest) | **SDK** `src/kernel/xboxkrnl/xboxkrnl_io.cpp:46-53` (`CurrentGuestLr`) |
| Caminho host de um entry resolvido | `Entry::absolute_path()` (ex. de uso em **virtual_file_system.cpp:150-155**); para XFile: `entry()` — **SDK** `include/rex/system/xfile.h:77` |
| Canal de saída por sessão | repo `src/pinyon_shift_diagnostics.cpp:238-262` (`RecordEvent` → `<state>/logs/<session>.jsonl`, mutex+flush); session id em **:180-181** |

Implementação sugerida: registrar em `OpenFile` (e nos retornos de `ResolvePath` via um observador de falha novo ou log direto) uma linha `guest=<path> host=<resolved|-> status=<hex> action=<n> reason=<no_device|entry_missing|disposition|write_readonly|...>`; deduplicar `(path,status)`; incluir `NtQueryFullAttributesFile`.

### 5.2 `config_dump.txt` — todas as cvars/configs ativas (Q5)

| Item | Local (arquivo:linha) |
|---|---|
| **API de iteração de todas as cvars** | `rex::cvar::GetRegistry()` → `std::vector<FlagEntry>&` — **SDK** `include/rex/cvar.h:178` (impl `src/core/cvar.cpp:220-222`) |
| Campos de `FlagEntry` (nome, tipo, categoria, descrição, getter = valor atual, default, lifecycle, constraints, source) | **SDK** `include/rex/cvar.h:163-176` |
| `Source` (default/config/env/cmdline/runtime) | **SDK** `include/rex/cvar.h:144-150`; consulta `GetFlagSource` **:202** |
| Auxiliares prontos | `ListFlags` **cvar.h:231** (impl **cvar.cpp:400-409**), `ListFlagsByCategory` **:232**, `GetFlagInfo` **:234**, `GetFlagByName` **:199**, `ListModifiedFlags` **:240**, `SerializeToTOML` **:241** (impl **cvar.cpp:546-559** — **só emite flags modificadas**, insuficiente para dump completo) |
| Consumidores existentes (prova de uso) | settings overlay **SDK** `src/ui/overlay/settings_overlay.cpp:160`; console `find` **SDK** `src/ui/overlay/console_commands.cpp:36`; mods repo `src/mod/mod_host.cpp:276` |
| Ordem de init p/ dump | `cvar::LoadConfig` após `OnConfigurePaths` — **SDK** `src/ui/rex_app.cpp:169-170`; `ApplyEnvironment` — **SDK** `src/core/cvar.cpp:662-685` (mapeia cvar→`REX_*` env); env do device vem de `environment.txt` — repo `src/android_host_jni.cpp:67-102` (denylist **:62-65**) |
| **Pontos de gravação recomendados** | (a) `OnPostInitLogging` repo `src/pinyon_shift_app.cpp:514` (pós LoadConfig/ApplyEnvironment); (b) fim de `OnPostSetup` repo `src/pinyon_shift_app.cpp:696` (cvars de **mods** registram depois: repo `src/mod/mod_host.cpp:247-273`, `ApiRegisterCvar`); (c) `OnShutdown` repo `src/pinyon_shift_app.cpp:918` para capturar mudanças de runtime (a UI usa `SetFlagByName`) |
| **HostConfig** (repo `src/config/host_config.h:40-60`) | Guarda o **texto** de `<state>/config/pinyon_shift.toml` (linhas `name = value`), não o registry: `Load/Set/Save` com escrita atômica (repo `src/config/host_config.cpp:133-152`) e backup (**:154-182**, `config/backups/pinyon_shift-<UTC>.toml`). É o mesmo arquivo que o SDK carrega como cvars (`paths.config_path` — repo `src/pinyon_shift_app.cpp:419`; lido em **rex_app.cpp:169-170**) — dois leitores do mesmo TOML. Incluir o texto do HostConfig + conteúdo de `environment.txt` no dump |

Formato sugerido por cvar: `name | category | type | default | current | source | lifecycle`, gerado iterando `GetRegistry()` (não `SerializeToTOML`, que omite defaults).

### 5.3 `device_info.txt` — modelo, SoC, Android, driver Vulkan, extensões, features, limites, formatos (Q6)

| Item | Local (arquivo:linha) |
|---|---|
| **Relatório já existente**: `LogCapabilityReport` | **SDK** `src/ui/vulkan/vulkan_device.cpp:76-252`; cvar `vulkan_capability_report` (**:61-65**, **default true no Android**); emitido em `VulkanDevice::CreateIfSupported` (**:303-305**), antes de aceitar/rejeitar o device |
| Conteúdo atual do relatório | deviceName/vendor_id/device_id/api_version/driver_version (**:97-103**); 20 features (**:106-131**); 11 limits (**:133-147**); **todas** as extensões do device (**:149-160**); memory heaps (**:162-172**); 21 formatos com `optimalTilingFeatures` em hex (**:176-206**); lista "missing" com workarounds (**:208-250**); sai como UMA linha `REXLOG_INFO("VULKAN_CAPABILITY_REPORT …")` (**:251**) → vai ao runtime.log/logcat, não a arquivo |
| Instância (extensões/layers) | enumeradas mas só falhas logadas — **SDK** `src/ui/vulkan/vulkan_instance.cpp:113-118, 186-211, 244-295` |
| Driver custom (Turnip) | carregamento: **SDK** `src/ui/vulkan/android_gpu_driver.cpp:88-142` (log "Custom GPU driver {}: loaded {} through adrenotools" **:140**); cvar `android_gpu_driver` **:30-35**; env `REX_ANDROID_GPU_DRIVER` setado pelo JNI — repo `src/android_host_jni.cpp:142-158`; metadados dos zips no picker — repo `android/.../GpuDrivers.java:99-120` (tag `PinyonShiftDrivers` **:46**) |
| Info Android (modelo/SoC/versão) | **não coletada** no nativo hoje; picker só checa Vulkan 1.1 (`FEATURE_VULKAN_HARDWARE_VERSION` 0x401000) — **GamePickerActivity.java:224-227** |
| Pipeline cache (vendor/device/driver) | **SDK** `src/graphics/vulkan/pipeline_cache.cpp:808` |
| **Ponto de gravação recomendado** | Refatorar `LogCapabilityReport` para devolver a string (ou chamar um writer) e gravar `<state>/logs/<session>.device.txt` no call site **vulkan_device.cpp:303-305** (padrão de sessão: repo `src/pinyon_shift_diagnostics.cpp:180-181`); acrescentar: extensões/layers da instance, propriedades Android (`ro.product.model`, `ro.board.platform`, `ro.build.version.release`, `ro.build.version.sdk`, `ro.hardware` — via `__system_property_get`), driver selecionado + resultado do carregamento, e os dados de **pipeline_cache.cpp:808**. Formato multi-linha (o JSON de uma linha dificulta leitura) |

---

## 6. Riscos e lacunas

1. **O log bruto do dispositivo não está no workspace** (`forza06_10-07-03-00_741.log` citado no worklog): a classificação de `AMB_Redstone.fsb` e `db\patch\*` foi inferida do código + worklog + docs. O `files.log` da Tarefa A é a ferramenta que fecha essa lacuna — priorizar a implementação dele antes de mais um round de teste às cegas.
2. **`NtCreateFile` não normaliza `\??\`** (só `ObCreateSymbolicLink` — xboxkrnl_ob.cpp:180-182 — e `NtOpenSymbolicLinkObject` — xboxkrnl_io.cpp:740-742 o fazem). Se o caminho do `.fsb` vier prefixado assim, arquivos existentes falham com "device not found". Correção candidata (fora do meu escopo de modificação): strip do prefixo na tradução do path ou registro de symlink adicional; **validar com o files.log antes de mudar**.
3. **`NtQueryFullAttributesFile` não loga falha em WARN** (xboxkrnl_io.cpp:649-650) — probes de existência do jogo ficam invisíveis; incluir no files.log.
4. **`SerializeToTOML` só emite cvars modificadas** (cvar.cpp:546-559) — dump completo exige iterar `GetRegistry()`.
5. **Cvars de mods registram tardiamente** (mod_host.cpp:247-273, pós-`LoadConfig`) — dump único cedo perde essas entradas; dump duplo (início + pós-mods) ou dump no shutdown.
6. **Volume do files.log**: `OpenFile` é o funil de *todos* os opens (kernel + CRT + content + saves do jogo, que abrem `ForzaProfile`/`PlayerDatabase` a cada save — traces M5 existem em xboxkrnl_io.cpp:232-238/350-357); sem deduplicação/rotação o arquivo cresce rápido (o logging já tem rotação por cvars `log_max_file_size_mb`/`log_max_files`, ver worklog).
7. **VULKAN_CAPABILITY_REPORT não inclui propriedades Android nem extensões da instance**, e sai como linha única — para `device_info.txt` formatar multi-linha e completar (§5.3).
8. **colourgradingmaps vazio é causa plausível do "bloom estourado"**, mas não explica blur extremo/listras/espelho amarelo (renderização — I1/I3). **Pedir ao usuário que recopie a pasta** (e de preferência `media\effects`, `media\stringtables\en` e o `.fsb`) antes do próximo log, para desconfundir assets de renderer.
9. **`repairMtpDroppedDirectories` só recria 3 pastas** (GamePickerActivity.java:238-245): se o MTP derrubou outras (ex. `media\audio\ambience` inteira), nada avisa — o picker só checa colourgradingmaps. Um checklist de pastas esperadas (ou o files.log) cobriria isso.
10. **`vfs_dump.cpp`** (SDK `src/filesystem/vfs_dump.cpp:32-119`) é uma ferramenta de dump de STFS *standalone* (console app desativado, TODO **:118-119**) — não reutilizável direto para files.log, mas prova o padrão de caminhada BFS no VFS.

---

## Apêndice A — Respostas diretas às perguntas (com atalhos)

- **Q1**: §2 (montagem runtime.cpp:294-375; resolução virtual_file_system.cpp:117-166; NtCreateFile xboxkrnl_io.cpp:155-245; formato de log atual xboxkrnl_io.cpp:239-241 + kernel_state.h:61 — sem caminho host, sem motivo; hook em virtual_file_system.cpp:207).
- **Q2**: §4.1 (FMOD ambience bank; 0xC000000F = arquivo ausente na cópia MTP como hipótese principal; "device not found" = prefixo sem device, hipótese `\??\`; médio, não crítico).
- **Q3**: §4.2 (xma_context.cpp:544; status 4 = kPacketOutOfRange, context.h:174-180; severidade = dropout/silêncio, log limitado 1×/status/contexto).
- **Q4**: §4.3 (xboxkrnl_threading.cpp:1387-1418; benigno, replica o console).
- **Q5**: §5.2 (`rex::cvar::GetRegistry()` cvar.h:178; FlagEntry cvar.h:163-176; HostConfig = texto do pinyon_shift.toml, host_config.h:40-60).
- **Q6**: §5.3 (VULKAN_CAPABILITY_REPORT vulkan_device.cpp:76-252 já cobre features/extensions/limits/heaps/formats; falta Android info; gravar em vulkan_device.cpp:303-305).
- **Q7**: §3 (tabela: colourgradingmaps crítico-visual; AMB_Redstone médio; DebugOptions/\Device\Image benignos documentados; db\patch provável benigno).

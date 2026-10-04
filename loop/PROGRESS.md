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
- 19:05 UTC — Ciclo 4 (fixes de render SDK-side, revisão adversarial completa):
  Auditoria do caminho de texturas (subagente 7-d + revisão do orquestrador, APIs
  verificadas uma a uma) → patch f4df688 no fork do SDK (branch
  auto/android-improvements-20261003):
  (1) P3: fast path de CPU para planos de vídeo (port 1:1 do D3D12; race com o
      decodificador era a causa provável do FMV corrompido);
  (2) P2: um vkCmdCopyBufferToImage por nível (mosaico em LOD distante);
  (3) P1: máscaras trocadas no scratch buffer novo (bug latente).
  Submodule pin atualizado 404fa7ee → f4df688. CI valida a compilação completa.
- 19:35 UTC — Run 7 (37152863464, 346c642) em andamento (SDK recompila ~180 TUs de
  texture_cache + dependentes). Enquanto compila:
  - `76474ae` feat(android): environment.txt no external dir (sem override de paths/
    driver; destrava captura de shaders on-device → pipeline do pack .pnsp).
  - `2b8f699` fix(test): kNull no switch do render test — repo 100% limpo de warnings
    C/C++ próprios (restam ~700 do SDK/thirdparty → S4).
  - Documentação: linhas de log benignas (Turnip/SELinux/XMA) no TROUBLESHOOTING;
    L6 (mensagem de falha de boot) aberto; NP-4.10 re-escopado p/ desktop.
- 21:20 UTC — Run 7 VERDE: patches de render do SDK (fast path de vídeo P3, cópia por
  nível P2, máscaras P1) compilam no NDK e o APK fecha. Este é o candidato para
  validar os bugs do log4/build33 no device.
- 21:25 UTC — build33.zip analisado (7 screenshots + log): confirma FMV corrompido
  (Press Start, cutscenes, tutorial do carro) com frames limpos intercalados → race,
  alvo exato do P3. Novo fix: `e342e7b` (stringtables/en + dynamicpost/colourgradingmaps
  no repair MTP). Run 8 disparado para o HEAD.
- 21:50 UTC — Run 8 (37154598345, fd56120) VERDE ~24 min: **APK candidato pronto** para o
  dono testar no device (FMV corrompido, mosaico em folhagem/LOD, gamepad, diretórios
  MTP). Próxima evidência esperada: novo log/vídeo do dono.
- 22:05 UTC — **Dono reportou crash "abre e fecha" no APK do run 8** (pastebin hE8ApmK5,
  Edge 30 Fusion/Turnip, build com nossas fontes Roboto → confirmado run 8). Duas sessões
  com o MESMO crash: SIGSEGV (SEGV_ACCERR) write na thread "GPU Recorder" (GPU principal),
  em __memcpy_aarch64_simd, "Unhandled fault (write outside guest memory)". Registradores:
  cópia exata de 0xE1000 = 921.600 B = 1280×720 R8 (plano de vídeo FMV) com dst selvagem.
  - Causa raiz (SDK eb22432, fix): TryLoadTextureDataFromCpu (P3, f4df688) usou
    RequestPartial — API incremental que concede SÓ o que cabe na página atual — e copiou
    o tamanho cheio para o grant parcial → overflow do staging mapping quando a página
    estava quase cheia (1º crash: página 0xC0000, grant ~0xBD000, resto x2=0x23FB0; 2º
    crash: grant 0xF000). A referência D3D12 usa Request (grant total ou falha limpa).
  - Fix: trocar RequestPartial → Request (garante tamanho cheio contíguo; nullptr →
    fallback ao load normal) + barreira com size_bytes. eb22432 no fork; pin 66d7d97.
  - Aprendizado: page size do pool = 2 MiB ≥ 921.600 B, então Request sempre resolve para
    planos 720p; degradação graciosa para planos > 2 MiB.
- 22:05 UTC — Run 9 disparado para 66d7d97 (recompila só texture_cache.cpp do SDK via
  ccache quente; APK candidato para o dono re-testar). Docs de loop retidos localmente
  para não cancelar o run (concurrency cancel-in-progress por branch).
- 22:29 UTC — Run 9 (37157374544, 66d7d97) VERDE ~22 min: fix do crash do FMV compilado
  (SDK eb22432). Push do c407d25 (community gamecontrollerdb + docs ruído benigno) e
  docs de loop; run 10 disparado — APK combinado (crash fix + mappings Android).
- 23:15 UTC — Dono testou o APK do run 9 (build #37): **crash do FMV SUMIU** (0 fatal
  signals no log de 404 KB). Novos sintomas: (1) vídeo da intro pisca preto (~75% preto
  por blackdetect; frames pretos = tira de ruído no topo + corpo preto — snapshot parcial
  do decodificador ou starvation de decode; mecanismo a fechar com fh1_fmv_debug);
  (2) "embaçado" na gameplay — FH1 nativo tem DoF/motion blur fortes + aniso 4x default;
  knobs do pinyon_shift.toml documentados no TROUBLESHOOTING (disable DoF/MB, aniso 16x
  hot-reload). build #38 falhou no contrato do 8BitDo (5≠2 — DB da comunidade trouxe
  entradas do mesmo GUID) → teste atualizado; #39 verde; #40 = pin 35811db (instrumentação
  fh1_fmv_debug) + docs.

## 2026-10-04

- 00:30 UTC — Evidência do build #37 re-analisada por quadro (build37.zip: 2 vídeos + log):
  - vídeo 1 (3,5 s): tela preta quase total (brilho médio 1,8/255; conteúdo só no último
    frame) — o FMV da intro;
  - vídeo 2 (82 s): gameplay com sharpness Laplaciana 40–150 na abertura vs 200–430
    depois, e um segmento escuro/embaçado em t=15,5–18,2 s (sharpness 10–13) — confirma
    o pisca-preto no meio do vídeo e o "embaçado" na abertura.
  - Log sem fatal; FMV presentation resolve 320x184 fmt 7 normal; sem refusals de
    CopyCpuRange registrados.
- Diagnóstico fechado em dois mecanismos independentes:
  (1) pisca-preto no MEIO do vídeo: snapshot parcial dos planos YUV (decodificador
      em starvation reescreve o plano de cima para baixo; o jogo compõe o plano
      parcial = tira de ruído + corpo preto). Corrigível no load.
  (2) preto no INÍCIO do vídeo: warm-up de PSOs (371 pipelines sem pack .pnsp,
      P2-LOG) — presenta suprimida até compilar. Permanece; mitigação é o pack
      (médio prazo) — fh1_fmv_debug distingue os dois no próximo log.
- fix no fork do SDK (13a5cfa + 8443bcf + d65408b docs, branch auto/android-improvements-20261004):
  probe always-on de completude (5 chunks de 64 B por snapshot) + retenção do
  último frame COMPLETO quando o snapshot é parcial — decodificador starvation
  congela no último frame bom em vez de piscar preto. Gate para superfícies
  tamanho-plano (máscaras pequenas continuam subindo); textura sem frame completo
  continua best-effort (letterbox nunca congela); watch permanece armado → próximo
  write re-outdata e re-tenta. Máquina de estados validada em harness standalone
  (8 casos: completo/parcial/all-zero/primeiro-parcial/letterbox/máscara/recuperação).
- Blur da gameplay: schema 28 adota aniso 16x como default (migração once-only
  3→5 no app e no set-graphics-experiment; testes de contrato e settings
  atualizados; novo teste de adoção). Regex da migração validada em libstdc++
  (13 casos): `${1}` NÃO é portável (libstdc++ emite literal) e `$15` é ambíguo —
  a linha é reescrita literal com grupos para indentação/comentário. DoF/motion
  blur permanecem nativos (toggles em GRAPHICS; maior causa do "fora de foco",
  decisão artística do dono via menu).
- Revisão adversarial (3 subagentes críticos independentes: semântica Vulkan,
  regressões de guest/produto, build/CI) — 3 achados major corrigidos ANTES do push:
  (1) retenção falsa-positiva em snapshots ALL-ZERO (fade-to-black congelaria o
      frame stale para sempre) → reter apenas snapshots PARCIAIS (1-4 chunks zero;
      all-zero sobe best-effort como antes) + kill-switch hot-reload
      fh1_fmv_retain (default on);
  (2) flag "frame completo" ficava stale quando a textura caía no load pela
      shared-memory (fallback sobrescreve o conteúdo) → flag limpa nos bail-outs;
  (3) tool Apply emitia config schema-28 SEM a chave aniso (runtime cvar default
      4x enquanto o tool reporta 16x) → append-when-absent como o xma.
  Nits aplicados: subject >72 chars, log debug <320 B enganoso, wording do
  TROUBLESHOOTING. Harness comportamental re-validado (11 casos).
- Branch nova auto/android-improvements-20261004 (repo + fork SDK), pin
  rexglue.revision = d65408b, build 42 disparado no workflow (SDK primeiro —
  o checkout do CI resolve o gitlink, que precisa existir no remote).
- 02:52 UTC — Run 42 (37171145460, add7f82) ✅ VERDE ~25 min: APK com retenção parcial do
  FMV (SDK d65408b) + aniso 16x default (schema 28). Codegen cache MISS esperado (SHA novo
  do SDK); ccache quente; gate 16KB ok. Candidato a teste do dono: intro deve congelar no
  último frame bom em vez de piscar preto (fh1_fmv_debug + linha "retained last complete
  frame" confirmam no logcat); gameplay deve mostrar pista/terreno mais nítidos. Se o
  "fora de foco" persistir, o toggle DEPTH OF FIELD em GRAPHICS é o próximo knob.

## 2026-10-04 (ciclo 2)

- 11:35 UTC — Evidência do build 42 recebida do dono (build42.zip: logcat + 3 screenshots):
  - **Piscadeiro PERSISTE no título** (screenshot 7 s após o FMV resolve: 88% preto, tira
    de conteúdo no topo) e o log NÃO tem NENHUMA linha "retained last complete frame" —
    a retenção do build 42 nunca engajou.
  - Diagnóstico fechado: o probe de zero-chunks é cego ao regime permanente. Depois do
    primeiro frame decodificado, a cauda abaixo do cursor do decodificador guarda os
    pixels NÃO-ZERO do frame ANTERIOR; todo snapshot mid-rewrite tem zero chunks zero e
    passa como "completo" → upload torto (linhas novas em cima, linhas velhas embaixo)
    → o próprio piscar. O probe só via o caso dos primeiros frames (memória zerada).
  - Achado adicional: GPU fault `VK_ERROR_DEVICE_LOST` (tu_knl_kgsl) ~4 s após o save,
    sob compilação pesada de PSOs; processo reiniciou. Ocorrência única; registrada no
    BUGS.md como item aberto (precisa reproduzir). XMA "cannot resolve logical packet 1"
    1 s antes — áudio, sem correlação confirmada.
- Correção no fork do SDK (bea41bd, branch auto/android-improvements-20261004-fmv-tearing):
  detecção de frame torto por diff contra o baseline. A textura guarda os bytes do
  último snapshot enviado; cada novo snapshot é diferido bloco a bloco (~128 blocos):
  idêntico → pula o upload (economiza banda em frame re-publicado); mudanças até o fim,
  ou cauda toda zero (letterbox/fade) → upload + novo baseline; prefixo mudado com cauda
  não-zero inalterada e fronteira que ANDOU desde o snapshot anterior → decodificação em
  curso → retém o último frame completo. Fronteira parada por 4 snapshots seguidos =
  fundo estático (não é cursor) → upload para o conteúdo continuar fluindo. Qualquer
  upload plane-sized estabelece o baseline (probe de zero-chunks nunca marca letterbox
  completo) e a classificação se auto-corrige de um baseline torto. Bail-outs limpam o
  estado (o fallback sobrescreve a textura).
- d2db0a7 feat(vulkan): fh1_fmv_debug default ON (rate-limited) — o log do dono veio sem
  nenhuma evidência de classificação; sem isso não distinguimos decoder congelado de
  upload torto no próximo teste. Desligar quando o FMV estiver estável.
- Harness comportamental standalone reescrito (12 casos: completo/tortos/all-zero/
  letterbox-completo/tortado-na-barra/estático/fronteira-parada-3x--upload/fade/
  recuperação-starvation/kill-switch/máscara) — todos passam; clang -fsyntax-only limpo
  na região alterada (warnings restantes são pré-existentes do SDK).
- Pin rexglue.revision = d2db0a7 + gitlink; BUGS.md atualizado (mecanismo novo + GPU fault
  como item aberto). Branch nova auto/android-improvements-20261004-fmv-tearing (repo +
  fork SDK); build 43 disparado no workflow (SDK primeiro — o checkout do CI resolve o
  gitlink, que precisa existir no remote).

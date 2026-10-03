# Pesquisas e achados — com fonte

## 2026-10-03

### A1. Código-fonte do próprio repositório (verificação direta)
- `thirdparty/shiftglue-sdk/src/graphics/vulkan/pipeline_cache.cpp:54` —
  `REXCVAR_DEFINE_BOOL(vulkan_pipeline_cache_persist, true, ...)`; linhas 798-883:
  carrega/salva `VkPipelineCache` em `<cache_root>/shaders/...` com rename atômico.
- `thirdparty/shiftglue-sdk/src/ui/rex_app.cpp:534-541` — chama
  `graphics_system->InitializeShaderStorage(runtime_->cache_root(), title_id, true)`
  quando `cache_root` não vazio (no Android: `files/state/cache`).
- Conclusão: PB-2.13 está ATIVO no port Android. Ação do loop: não reimplementar;
  monitorar warm-up (M2).

### A2. Análise do CI (`.github/workflows/build.yml`)
- Sem `actions/cache`, sem ccache, sem `concurrency`. 1181 alvos NDK por run.
- Baseline medido: 26-28 min por run verde; APK 53 MB.

### A3. 16 KiB pages (documentação pública Android/Google I/O 2024-2025; NDK r27
release notes)
- Apps com código nativo precisam de ELF `p_align >= 16384` para devices com página
  de 16 KiB (Nov 2025+: exigência Google Play para novos apps/updates).
  NDK r27+ aplica `-Wl,-z,max-page-size=16384` por padrão para arm64-v8a; explicitar
  e VERIFICAR no CI elimina regressão silenciosa futura (ex.: bump de NDK/AGP).

### A4. Backlogs do projeto (docs/NATIVE_PORT_BACKLOG.md, docs/PERFORMANCE_BACKLOG.md)
- Subagente de leitura profunda produziu matriz de aplicabilidade ao Android
  (NP-14.1..14.6, PB-1.1, PB-2.13, PB-8.3, PB-4.2 etc.) — ver loop/BACKLOG.md e
  worklog.md (Task 3-a). Fonte: repositório, dados de 2026-09-28/30.

### A5. Exigência 16 KB no Google Play — confirmada (pesquisa web, 2026-10-03)
- Fontes: developer.android.com (guia 16 KB), github.com/Android-AOSP issue "Support 16 KB
  page sizes - Google Play compatibility" (17/07/2025), dev.to (26/09/2025),
  learn.microsoft.com (10/10/2025). Consenso: desde 1º de novembro de 2025, novos apps e
  atualizações enviados ao Play visando Android 15+ exigem suporte a páginas de 16 KB nos
  binários nativos.
- Aplicação: valida o gate do commit ae2b344 (o APK tinha 9/10 libs em 4 KB — evidência
  medida no artefato do run 37129069405).

### A6. Shader cache on-disk em drivers mobile — corroboração da cena de emulação
- Fonte: canal de emulação (t.me, changelog de build Winlator/GameHub-Switch): "Enabled
  on-disk shader cache on Android to reduce shader recompilation" + fix de detecção de
  chip Adreno 830.
- Aplicação: a cena trata cache on-disk como melhoria essencial em Android; o port já tem
  isso ativo (PB-2.13, ver A1). Nenhuma ação adicional; confirma prioridade de não regredir.
- Risco: fonte secundária não verificável em profundidade — tratada como corroboração
  fraca, não como instrução.

### A7. Mali vs Adreno (filament #8028, 09/08/2024)
- Mali em devices low-end não sofre OOM no mesmo conteúdo que trava Adreno em alguns
  cenários — perfis de memória divergem por vendor. Aplicação: reforça M3 (orçamento de
  memória por tier) como pendência que exige medição em device; não adotar defaults
  cegos.

## 2026-10-03 — Análise do log4.zip + vídeo (device real: Edge 30 Fusion, Adreno 660, Turnip Mesa 26.3.0-devel)

**Fonte**: logcat 3 min (03_10-12-55-20_628.log) + vídeo 2:42 com FMV e gameplay.
Análise completa: artifacts/logs4/LOG_ANALYSIS.md (subagente 7-a).

**Estado da sessão**: saudável (~29-32 fps, full speed p/ 30 fps do FH1), boot 0,93 s
até 1ª frame, driver Turnip carregado com sucesso via adrenotools, sem crash/ANR.

**Bugs visuais confirmados no vídeo (frames em artifacts/logs4/frames/)**:
1. FMV corrompido (0:20-1:40; XMediaFacade carregado +16s→+92s no log = janela exata):
   f_003/f_007 = banda de imagem real repetida 4x (720/4=180 linhas) + pontilhado
   vermelho/azul no topo + resto preto; f_009/f_010 = área do carro com linhas
   horizontais interlaced. No Windows/D3D12 o mesmo jogo renderiza FMV limpo →
   bug específico do backend Vulkan (NP-15.6: Vulkan ainda não qualificado em
   nenhuma plataforma).
2. Gameplay (2:05+): artefatos em mosaico/blocos em folhagem e terreno distante
   (f_013, f_016) = NP-4.10 (artefatos 2x/3x).

**Mapa técnico para investigação (texture_cache)**:
- O FMV é desenhado pelo título como TRÊS PLANOS 8-bit (YUV) com VS 7156CE05/PS
  31511D87 (NP-2.9 tem o censo). Sem tratamento YUV especial nos backends.
- Vulkan tem DUAS rotas de upload em texture_cache.cpp: compute copy para texturas
  escaladas (linhas ~1906-1920, pipelines texture_copy_buffer_image_{1,2,4}w_cs) e
  queue copy vkCmdCopyBufferToImage (linha ~1975, bufferRowLength = x_pitch_blocks *
  host_block_width). D3D12 usa compute load path com host_pitch explícito.
- HostLayout (linha 1456) parece correto; auditoria linha a linha das rotas pendente
  (tentativa de subagente falhou 4x por timeout de infra — retomar).
- Pista adicional no log: "MESA Gralloc doesn't support lock_ycbcr (video buffers
  won't be supported)" no boot do SDL.

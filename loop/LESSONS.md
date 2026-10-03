# Lições aprendidas — o que não repetir

## 2026-10-03

1. **Env vars não persistem entre chamadas de shell deste ambiente** — o primeiro
   envio de Telegram falhou silenciosamente (token vazio) e o primeiro push falhou
   (`Invalid username or token`). Solução: `gh auth login --with-token` (armazenamento
   próprio do gh, fora do repo) + redefinir funções/vars a cada comando quando
   necessário. Nunca confiar em estado de shell entre chamadas.
2. **Base móvel**: `android-port` recebe pushes paralelos (outro agente/mantenedor).
   Antes de qualquer commit: `git fetch origin android-port` e rebase/reset se a base
   andou. Nunca dar push na base; nunca reescrever histórico já publicado da MINHA
   branch (reset só permitido sem commits próprios).
3. **Não assumir que um backlog "aberto" precisa de implementação** — PB-2.13 estava
   documentado como "não adotado" (contexto NVIDIA Windows) mas o código do SDK já o
   ativa no Android. Verificar o código antes de planejar.
4. **Bump de submodule exige atualizar config/release-toolchain.json (rexglue.revision)** —
   o teste `test_release_sdk_revision_matches_the_submodule` existe para isso e o release
   v0.3.2.0-build24 falhou por essa deriva. Agora também roda no CI do APK (fail-fast).
5. **Evidência antes de otimizar**: o APK de 53 MB não tem gordura de debug — .text de
   78 MB é o código recompilado do jogo. "Reduzir APK" sem inspecionar o ELF seria perda
   de tempo.
## 2026-10-03 — Run 2 (ba1e332) vermelho
- **Bug**: `PackageManager.FEATURE_VULKAN_VERSION` não existe — o correto é
  `FEATURE_VULKAN_HARDWARE_VERSION` (API 24). A revisão adversarial do L3 validou a
  semântica (`hasSystemFeature(String,int)`, encoding 0x00401000) mas não a existência
  da constante no android.jar.
- **Lição**: nomes de constantes Java precisam ser verificados contra o SDK real
  (android.jar / docs) antes do push; revisão de estilo/semântica não pega isso.
  Sem SDK local, o CI é o gate real — mudanças Java devem ser tratadas como
  "aguardando-CI" obrigatório e preferir compilar antes de empilhar mais commits.
- **Lição 2 (run 6)**: bump do submodule do SDK exige bump do pin
  `config/release-toolchain.json` (rexglue.revision) no mesmo commit — o guard
  M0 falha rápido (2 min) no CI. Rodar `python3 tools/tests/test_release_contract.py`
  localmente antes de empurrar bumps de submodule.
- **Lição 3**: `.gitignore` tinha `BACKLOG.md` global → `loop/BACKLOG.md` nunca foi
  versionado (adds silenciosamente pulados). Sempre conferir `git status` depois de
  `git add` de diretórios.
- **Lição 4 (run 9, crash em produção)**: `GraphicsUploadBufferPool::RequestPartial` é API
  INCREMENTAL — retorna `size_out` ≤ pedido (clamp page_size_ e espaço restante da página),
  para ser chamado em loop; copiar o tamanho cheio para o grant parcial é heap overflow
  (SIGSEGV ACCERR "write outside guest memory", dst ~1 GB fora do mapeamento). Para
  "quero N bytes contíguos agora", usar `Request` (grant total, abre página nova, nullptr
  se N > page_size_) — que é o que a referência D3D12 sempre fez. Sintoma no logcat:
  crash na thread de GPU em __memcpy_aarch64_simd com tamanho EXATO do recurso (0xE1000 =
  1280×720) e dst selvagem → registrar x0..x5 do tombstone dá o tamanho e o grant real.
  Correlacionar fault addr vs fronteira de página do pool (2 MiB alinhado) fecha o caso.
- **Lição 5 (build #40)**: NUNCA digitar/copiar SHA de commit "de cabeça" no pin
  `config/release-toolchain.json` — usar `git rev-parse HEAD` no submodule e colar.
  O guard de contrato pegou (2 min), mas custou um run. Mesma família da Lição 2.

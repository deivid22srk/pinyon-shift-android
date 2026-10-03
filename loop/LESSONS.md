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

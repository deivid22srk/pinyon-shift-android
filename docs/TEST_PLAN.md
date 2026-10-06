# Plano de teste no dispositivo — logs em tempo real + fixes de renderização

Data: 2026-10-06 · Branch: `fix/android-rendering-fmv-textures-20261004` · SDK: `ba0106a`
(branch `fix/android-rendering-logs-20261006` do fork `deivid22srk/shiftglue-sdk`)

Este plano assume o APK produzido pelo workflow **Build Android APK** desta branch
(artefato `pinyon-shift-apk`). Tudo o que ele pede de volta são **arquivos de log** —
nenhuma ferramenta além do próprio celular e de um app de compartilhamento (WhatsApp,
Drive, e-mail) é necessária.

## 1. Preparação (uma vez, ~2 minutos)

1. Instale o APK novo por cima do atual (não precisa desinstalar; a seleção de pasta,
   driver Turnip e permissões são mantidas).
2. Abra o app (tela do seletor). Confirme que há um novo botão **Realtime logs**.
3. Toque em **Realtime logs**:
   - Marque **Save realtime logs**;
   - Escolha o nível **Verbose GPU** para este primeiro teste (é o que separa as
     hipóteses de FMV e de amarelo/painel; `Debug total` só se o armazenamento sobrar);
   - **OK**. O aviso "aplica no próximo início do jogo" é esperado.
4. Confirme o destino mostrado no diálogo:
   - Com **All Files Access** concedido: `/storage/emulated/0/forza/` (aparece como
     "shared storage");
   - Sem a permissão: `Android/data/dev.pinyon.shift/files/forza_logs/` — o diálogo
     mostra o caminho exato; prefira conceder a permissão (o jogo já a usa para ler a
     pasta do disco).
5. **Antes de jogar**: recopie `media/dynamicpost/colourgradingmaps` do disco extraído
   para a pasta do jogo (por USB/gerenciador de arquivos). A cópia por MTP é a causa
   provável da pasta vazia — sem a recópia, o teste do espelho/painel amarelo fica
   inconclusivo. Se não puder recopiar agora, siga mesmo assim: o log vai mostrar
   exatamente o que o jogo tentou abrir nessa pasta.

## 2. Sessão de teste (uma corrida contínua, ~10 minutos)

Jogue nesta ordem, sem fechar o app — o roteiro cobre todos os sintomas relatados:

| Passo | Onde | O que observar na tela | O que o log vai conter |
|---|---|---|---|
| 1 | Título "PRESS START" | A faixa colorida no topo ainda aparece? O vídeo de fundo pisca? | `fh1 fmv ...` (classificação por snapshot), `presenting a clear-only frame` se houver frame preto apresentado |
| 2 | Menu principal | Texto "SINGLE PLAYER/MULTIPLAYER" corrompido? Fundo quase preto? | `fh1 fmv plane load refused (GPU-written pages)` (fundo preto do menu), linhas `texture created: ... xenos N -> vk M` do caminho de UI |
| 3 | Tela de controles | Linhas horizontais na tela de controles | resolves do executor (`fh1_resolve_dump_dir` não precisa estar ligado) |
| 4 | FMV da intro/cutscene | Piscar preto entre frames | `fh1 fmv cleared-plane snapshot retained last complete frame` (o fix novo) e `... boundary-escape upload` se ele escapar |
| 5 | Entrar na corrida | Blur extremo, listras verdes embaixo, linhas coloridas à esquerda | `vulkan frame N closed: ...` (por frame), `texture created:` (formatos/fallbacks reais) |
| 6 | Cabine (visão interna) | Espelho retrovisor e painel amarelos? Bloom estourado? Cabine escura? | `colour grading map open: ...` (quais LUTs o jogo pediu; falha = recópia pendente) |
| 7 | Volte ao menu e saia | — | encerramento limpo (flush) |

Anote (ou tire screenshot) do que mudou em relação aos sintomas antigos — em especial:
faixa do topo, piscar do FMV, amarelo do espelho.

## 3. O que me enviar de volta

Na tela do seletor: **Realtime logs → Share latest session (ZIP)** e me envie o ZIP
(e-mail, Drive, o que for). Ele contém a sessão completa. Se preferir copiar à mão,
a pasta é `/storage/emulated/0/forza/session_<AAAAMMDD_HHMMSS>/` (ou o caminho do
fallback mostrado no diálogo). Arquivos e o que cada um decide:

| Arquivo | Decide o quê |
|---|---|
| `all.log` | visão geral; linha `Realtime log session installed` confirma que a sessão pegou |
| `fmv.log` | **flicker do FMV**: contagem de retains/escapes/skips por plano e o detector `presenting a clear-only frame` (frame preto apresentado) |
| `vulkan.log` | **DEVICE_LOST** (se ocorrer): dump de breadcrumbs — `breadcrumb pending (suspect)` lista os draws suspeitos com hash de shader VS/PS, chave de render pass e handle de pipeline; mensagens do driver Turnip |
| `crash.log` | relatório de sinal fatal + o mesmo rastro de breadcrumbs (o backtrace completo do tombstone do Android fica em `logcat.txt` — o crash.log traz sinal, endereço e PC) |
| `gpu.log` | stats por frame (`vulkan frame N closed`, incluindo texturas criadas/falhas), draws pulados por pipeline aquecendo (com hash dos shaders) |
| `files.log` | **AMB_Redstone.fsb** e qualquer outro arquivo faltando, com caminho guest e host |
| `audio.log` | XMA (dropout conhecido, só confirma) |
| `config_dump.txt` | todas as cvars ativas no início E no fim da sessão (reproduzir a config) |
| `device_info.txt` | modelo/Android/memória/driver |
| `logcat.txt` | ruído do sistema (AdrenoUtils, GraphicBufferAllocator) e tudo que precedeu a instalação do sink |
| `logcat_crash.txt` | buffer de crash do Android: tombstones com o backtrace completo (garantido mesmo quando o filtro de PID perde as linhas do crash_dump) |

**Sessões antigas são apagadas automaticamente além das 3 mais recentes** (cada
arquivo gira em 200 MB com uma geração `.old`, e a sessão inteira fecha ao acumular
1 GB escritos — só `crash.log` continua). Se quiser guardar uma comparação,
compartilhe antes de rodar 3 vezes.

## 4. Experimentos de contra-prova (só se o tempo sobrar)

Estes dois mudam uma variável por vez e fecham hipóteses específicas:

1. **Flicker do FMV sem placeholder-skip**: crie um `environment.txt` na pasta
   `Android/data/dev.pinyon.shift/files/` com a linha
   `vulkan_async_skip_incomplete_frames=false`, rode o FMV de novo e compare o
   `fmv.log`. (Remove a linha depois.)
2. **Resolve do amarelo**: se o espelho continuar amarelo mesmo com os LUTs
   recopiados, o próximo passo é um dump de resolve (`fh1_resolve_dump_dir`) — peça
   que eu prepare antes de pedir isso, é um build de diagnóstico.

## 5. O que já foi corrigido nesta branch (para calibrar expectativas)

- Frame aberto quando a swap texture faltava podia pular presents subsequentes
  (fix de causa raiz no SDK).
- FMV: plano limpo pelo decodificador (corte de cena) subia como "topo novo + rodapé
  preto" — agora retém o último frame completo enquanto a borda de conteúdo desce
  (a hipótese mais forte para o piscar preto no meio do vídeo).
- DEVICE_LOST agora deixa rastro: breadcrumbs universais (vkCmdFillBuffer) + dump
  antes do abort, também nos caminhos do presenter.
- Observabilidade por frame, por textura e por arquivo — o objetivo é que o próximo
  bug seja diagnosticável com UM envio de ZIP.

## 6. O que NÃO foi corrigido ainda (honestidade)

- Preto dos primeiros segundos do primeiro vídeo (warm-up de 371 PSOs sem pack
  `.pnsp`; a mitigação real precisa de um host Windows).
- Texto de menu corrompido: a hipótese de fonte-textura foi refutada (é fonte
  vetorial); os suspeitos agora são o RT de UI `k_2_10_10_10` e o shader de cobertura
  no Turnip — o `gpu.log` desta sessão decide o próximo passo.
- Faixa do topo / listras verdes / linhas à esquerda: suspeito estrutural são tiles
  EDRAM não possuídos no resolve parcial; sem mudança de comportamento até o log
  confirmar.

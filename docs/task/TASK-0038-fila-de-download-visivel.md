# TASK-0038: trazer a fila de download da versão anterior, e fazer o progresso chegar na tela

- **Status:** concluída
- **Criada em:** 2026-08-27
- **Concluída em:** 2026-08-27
- **Feature:** [FEAT-0001](../features/FEAT-0001-sync-upstream-oficial.md)
- **Bugs que resolve:** [fila-download-sem-tela-e-sem-progresso-ao-vivo](../bugs/done/fila-download-sem-tela-e-sem-progresso-ao-vivo_2026-08-27T12-10.md)
- **Commit:** — (o vínculo é o prefixo `TASK-0038:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## Origem

Relato do usuário: *"a fila de download tem que ficar igual a da versão anterior, com as mesmas
funcionalidades e o mesmo padrão."*

O motor da fila veio inteiro no porte — o download roda, retoma e grava. O que não veio foi **a
tela que mostra a fila**, e o progresso não chega nem na tarja do cartão. Diagnóstico completo,
com as provas de DEX, no bug.

## Escopo

**Entra:**

1. **A seção de fila na biblioteca**, com a mesma informação e as mesmas ações da `version1`
   (`res/layout/item_download_queue.xml` + `rebuildQueueViews`):
   - capa, título (2 linhas, elipse no fim);
   - status por estado: `Aguardando…` (QUEUED) / `X,X MB de Y,Y MB (Z%)` (DOWNLOADING) /
     `Pausado` (PAUSED);
   - barra de progresso, escondida em QUEUED como na anterior;
   - botão primário pausar↔retomar, escondido em QUEUED como na anterior;
   - botão cancelar, sempre visível, que também apaga o `.part` (`remove()` já faz as três coisas);
   - a seção inteira some quando a fila esvazia.
2. **O progresso ao vivo**, de verdade: a fila passa a publicar um snapshot **imutável**
   (`DownloadQueueItem`) dentro de `HomeUiState`, e os composables passam a receber valores que
   mudam — em vez de um `CatalogEntry` mutável mais um contador global.
3. **A tarja do cartão** (`↓ / ⋯ / % / ⏸ / ✓`) passa a ler esse snapshot, via um
   `compositionLocalOf` dinâmico, para não trocar a assinatura dos cinco chamadores de `GameCover`.
4. **Registro do listener fora do atalho de cache**: `setRomsDir` + `addListener` saem de dentro do
   `if (CatalogLibrary.entries.isNotEmpty()) return`, e passam a rodar em todo `HomeViewModel`.
5. Chaves de i18n novas em inglês (fonte da verdade) e em `pt-BR`.

**Fica de fora, deliberadamente:**

- **Pedir `POST_NOTIFICATIONS` em runtime.** A notificação do serviço é a *outra* superfície de
  progresso e hoje é invisível no Android 13+; é um defeito real, mas é outro assunto e outra task.
  A tela de fila resolve o relato sem depender de permissão nenhuma.
- Mexer em `DownloadQueueManager`/`RomDownloadManager`: o motor está provado funcionando e não se
  toca nele nesta task.
- Reordenar/retomar automaticamente downloads interrompidos por morte de processo.

## Por que assim

A `version1` não dependia de notificação: o progresso aparecia **dentro do app**, na aba "Saved".
No fork o catálogo foi fundido na biblioteca ([TASK-0025](TASK-0025-grade-unica-catalogo-na-biblioteca.md)),
então a seção passa a viver no topo da própria grade, acima de "Jogados Recentemente" — mesmo
conteúdo, mesmo comportamento, no lugar que corresponde à nova estrutura.

O ponto técnico que não pode ser repetido: **mutar um objeto Java compartilhado e sinalizar por um
contador não é estado observável para o Compose.** Enquanto os parâmetros que o cartão recebe forem
sempre o mesmo objeto, a única esperança de redesenho é a invalidação do contador — que é
justamente o que não está chegando no build publicado. Passar valor imutável faz o redesenho ser
consequência das regras normais do Compose, não de um efeito colateral.

## Como validar

No SM-A127M, com o APK de release instalado:

1. Tocar num jogo do catálogo → **Baixar**.
2. A seção **Baixando** aparece no topo da biblioteca, com capa, título e `0,0 MB de … (0%)`.
3. O status e a barra andam sozinhos, sem tocar em mais nada — comparar com
   `ls -l …/files/roms/*.part` crescendo.
4. A tarja do cartão sai de `↓` e passa a `%`.
5. Pausar → status `Pausado`, o `.part` para de crescer. Retomar → volta a crescer do mesmo ponto.
6. Cancelar → o item some da seção e o `.part` é apagado do disco.
7. Enfileirar um segundo jogo enquanto o primeiro baixa → ele entra como `Aguardando…`, sem barra
   e sem botão primário, e começa sozinho quando o primeiro termina.

## Resultado

Entregue e conferido no aparelho — SM-A127M (Android 13), APK `github/release` **com R8 ligado**,
que é o perfil de build em que o defeito aparecia (o mecanismo antigo até compilava intacto no DEX;
o que não acontecia era a invalidação).

| Passo | Resultado |
|---|---|
| Tocar "Baixar" | Seção **BAIXANDO** em 4 s, com `0,0 MB de 2079,4 MB (0%)` |
| Esperar sem tocar em nada | `0%` → `9%` → `21%` → `30%` → `37%`, batendo com o `.part` no disco |
| Tarja do cartão | Sai de `↓` e acompanha (`21%`, `30%`) |
| Pausar | Status `Pausado`, arquivo parado em 346.799.867 bytes em duas leituras |
| Retomar | Volta a crescer do mesmo ponto (346,8 MB → 386,8 MB) |
| Enfileirar um segundo | Entra como `Aguardando…`, sem barra e sem botão primário; tarja `⋯` |
| Cancelar | Linha some e o `.part` é apagado do disco |

Duas decisões que se afastam da versão anterior, ambas de propósito:

- **Barra indeterminada enquanto a URL é resolvida** (status `Iniciando…`). O downloader faz até
  cinco consultas encadeadas antes de saber o tamanho total, e um `0,0 MB de 0,0 MB (0%)` parado
  por dezenas de segundos é exatamente a leitura de "não aconteceu nada" que abriu este bug. A
  informação é a mesma; o que muda é não mentir que o progresso está travado.
- **Rótulo "Baixando" e não "Salvando".** A `version1` dizia "Saving"; o vocabulário do fork usa
  "Baixar"/"Download" em todo o fluxo (`catalog.confirm.start`), e misturar os dois verbos na mesma
  tela seria pior que a divergência.

O que **não** foi feito, e continua valendo: o app declara `POST_NOTIFICATIONS` mas nunca pede em
runtime, então a notificação de progresso do serviço segue invisível no Android 13+. Não é
regressão do fork (a `version1` nem declarava a permissão) e não afeta este relato, porque a fila
agora aparece dentro do app. Fica como task própria.

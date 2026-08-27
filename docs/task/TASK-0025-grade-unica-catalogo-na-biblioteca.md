# TASK-0025: fundir o catálogo dentro da biblioteca — uma grade só

- **Status:** concluída
- **Criada em:** 2026-08-27
- **Concluída em:** 2026-08-27
- **Feature:** [FEAT-0001](../features/FEAT-0001-sync-upstream-oficial.md)
- **Bugs que resolve:** nenhum
- **Commit:** — (o vínculo é o prefixo `TASK-0025:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## Origem

Decisão de produto do usuário, revertendo o desenho da TASK-0024:

> *"Não tem fundamento algum termos 2 grids, uma para o catálogo e outra para 'meus jogos'. Teremos
> uma só, com o catálogo completo e uma indicação de salvo ou não (igual tínhamos na antiga).
> Podemos pôr um filtro nela para mostrar só os jogos baixados. Esta grid do fork oficial é melhor
> que a antiga, podemos usar ela como base, alimentando-a com o catálogo."*

Na TASK-0024 eu tinha argumentado contra exatamente isto — que fundir mexeria em `HomeScreen`,
`HomeViewModel` e `GameInfo`, o núcleo do upstream, onde o delta do fork precisa ficar pequeno.
O argumento estava certo sobre o custo e **errado sobre o tamanho**: a fusão couberam em ~130 linhas,
quase todas aditivas, porque `buildState` já era um funil único e `launch` também.

## O desenho

A biblioteca do upstream continua sendo a grade — com as três disposições, a busca, a ordenação, a
navegação por controle e as capas que ela já tinha. O que muda é **o que a alimenta**.

```
repository.scan(pastas)  ─┐
                          ├─►  mergeCatalog()  ──►  allGames  ──►  buildState()  ──►  a grade
CatalogParser.parse()   ──┘
```

`mergeCatalog` casa as duas listas **pelo nome do arquivo**. Um jogo baixado aparece uma vez só, e
aparece na sua forma **local** — serial sondado do disco, capa curada, boot funcionando — não como a
linha sintética do manifesto. Ou seja: baixar um jogo não acrescenta um cartão à grade, **converte**
o que já estava lá.

### Os três pontos de costura

| Onde | O quê |
|---|---|
| `GameInfo` | dois campos aditivos: `catalogFileName` (o vínculo com a entrada) e `needsDownload` (não há arquivo). Mais `catalogCoverUrl`, **fallback** de `coverUrl` — assim que o jogo é baixado e sondado, a arte curada do repo volta a mandar |
| `HomeViewModel.launch` | uma linha sem arquivo abre o painel de download em vez de dar boot |
| `GameCover` | a tarja de estado |

A interceptação mora em `launch` e não em cada cartão **de propósito**: `launch` é o funil por onde
passam os sete pontos que iniciam um jogo (grade, lista, prateleira, recentes e o controle). Cobrir
o funil cobre todos; cobrir cartão a cartão teria deixado quatro caminhos dando boot num arquivo
inexistente.

`GameCover` pelo mesmo motivo: é o único desenhador de capa das quatro disposições.

### A tarja

`✓` baixado · `↓` disponível · `n%` baixando · `⋯` na fila · `⏸` pausado · `!` erro.

Fica **sobre** a capa e não abaixo: as disposições desenham a capa em tamanhos diferentes e só uma
tem espaço para um rótulo embaixo. Dentro do quadro, a marca aparece igual nas quatro.

Um arquivo que o usuário trouxe por conta própria — fora do catálogo — não ganha tarja nenhuma. Não
há o que dizer sobre ele, e uma marca em 12.628 cartões vira ruído em vez de informação; por isso o
`↓` também é discreto, com a cor de superfície e não a de destaque.

### O filtro

"Só os baixados", no menu de três pontos, ao lado de "Mostrar ocultos" — que é o item de mesma
natureza. Persistido em `library.onlyDownloaded`, então sobrevive ao fechamento do app.

E o subtítulo passou a contar **o que está na tela** em vez do total: com o catálogo fundido,
"Total de jogos: 12628" seria uma constante que não informa nada, e mentiria assim que a busca ou o
filtro recortasse a lista.

## O que saiu

A tela separada de catálogo inteira: `CatalogScreen.kt`, `CatalogViewModel.kt`, `AppRoute.Catalog`,
o destino em `AppNavigation` e a linha na gaveta. A rota inicial voltou a ser a biblioteca.

Sobrou de lá o painel de download, agora em `ui/catalog/CatalogDownloadModal.kt` — ele não era da
tela, era do jogo — e o `CatalogLibrary`, novo: um índice das entradas por nome de arquivo mais um
`version` que o Compose observa. Sem o `version` a tarja nunca se mexeria, porque o downloader muta
o próprio `CatalogEntry` e mutação de objeto Java é invisível para o Compose. É o mesmo mecanismo
que a TASK-0023 já tinha precisado descobrir uma vez.

## Como validar

No Galaxy A12 (SM-A127M, Android 13), lendo a árvore de UI a cada passo:

| Passo | Resultado |
|---|---|
| Abrir o app | biblioteca com **12.628** jogos e capas |
| O jogo já baixado | `✓`; os demais, `↓` |
| Tocar um não baixado | painel "Baixar este jogo e adicioná-lo à sua biblioteca?" |
| Tocar o baixado | vai para o boot (entrou em "Jogados Recentemente"), **não** abre o painel |
| ⋮ → "Só os baixados" | grade fica com 1 cartão, subtítulo "Total de jogos: 1" |
| Reabrir o app | o filtro continua ligado |
| Desligar o filtro | volta a 12.628 |

Entradas repetidas na grade (três "Agent Under Fire", cinco "Everything or Nothing") foram
conferidas no manifesto: são **regiões diferentes** do mesmo jogo, com a mesma arte. Não é duplicação.

**Não medido:** a busca digitada. A ordenação de 12.628 linhas acontece a cada `buildState`, e a
carga inicial (que faz exatamente essa ordenação) é imperceptível no A12 — mas isso é evidência
sobre uma ordenação, não sobre uma por tecla digitada. Se aparecer atraso, o lugar é decorar a lista
com a chave de ordenação pré-calculada: `sortedBy` chama o seletor a cada comparação, ~340 mil vezes
nesse tamanho.

O boot em si não foi exercitado até o fim porque **não há BIOS instalada neste aparelho** — o jogo
volta para a biblioteca. Isso é estado do aparelho de teste, não desta mudança.

## Resultado

Entregue. Uma grade, o catálogo inteiro dentro dela, o que está salvo marcado e um filtro para ver
só o seu.

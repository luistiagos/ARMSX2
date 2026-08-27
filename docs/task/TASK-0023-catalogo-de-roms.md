# TASK-0023: trazer o catálogo de ROMs para o fork e fechar o ciclo baixar → jogar

- **Status:** concluída
- **Criada em:** 2026-08-26
- **Concluída em:** 2026-08-27
- **Feature:** [FEAT-0001](../features/FEAT-0001-sync-upstream-oficial.md)
- **Bugs que resolve:** nenhum
- **Commit:** — (o vínculo é o prefixo `TASK-0023:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## Origem

O catálogo é a funcionalidade que o RetroSystem PS2 tem e o upstream não: 12.628 jogos com capa,
baixados de dentro do app. Sem ele o fork não substitui a linha anterior. O escopo cresceu durante a
validação em aparelho, porque três defeitos só apareceram com o dedo na tela — estão todos aqui.

## O que entrou

**A lógica veio inteira, em Java, sem reescrita** — ela não depende de UI:
`CatalogEntry`, `CatalogParser`, `RomDownloadManager`, `DownloadQueueManager`,
`DownloadForegroundService`, mais o `catalog_manifest_ps2.txt` com a reordenação já curada.

**A apresentação é nova**, porque a anterior era `RecyclerView` + XML e este app é Compose:
`ui/catalog/CatalogScreen.kt` e `CatalogViewModel.kt`, `AppRoute.Catalog` na gaveta, e as chaves
`catalog.*` em inglês (`I18n.kt`) e português (`assets/i18n/pt-BR.json`).

**No manifesto:** `FOREGROUND_SERVICE` deixou de ser removida (agora há um serviço que a usa de
verdade), mais `FOREGROUND_SERVICE_DATA_SYNC` e `POST_NOTIFICATIONS`. As de microfone e mídia do SDK
do Discord seguem removidas.

## Os quatro defeitos que só a validação em aparelho encontrou

### 1. O cartão não respondia ao toque

`clickable()` e `controllerFocusable()` são **caminhos distintos** — o primeiro é o dedo, o segundo é
o direcional. A primeira versão tinha só o segundo, e o cartão era inerte no toque. Foi o defeito que
o usuário relatou como *"tap to download não funciona"*.

O toque agora abre uma confirmação antes de baixar, como fazia a versão anterior: uma ROM de PS2 tem
entre 1 e 10 GB, e começar no primeiro toque gastaria os dados do usuário por um encostar de dedo.

**A confirmação não é um `AlertDialog`** — o build recusa (`checkNoWindowModals`), e com razão: um
diálogo do Compose é uma janela Android própria e engole as teclas do controle antes de chegarem ao
`dispatchKeyEvent`. Ficaria perfeito no toque e morto no gamepad. É `ConfirmOverlay`, do upstream.
Eu escrevi o `AlertDialog` mesmo assim; **quem pegou foi a trava deles, não eu.**

### 2. O progresso não aparecia — mas o download estava correndo

O arquivo crescia (334 MB no primeiro minuto) e o cartão seguia dizendo "Toque para baixar". Duas
causas empilhadas, e **corrigir só a primeira não teria adiantado**:

- `mutableStateOf` compara por igualdade **estrutural**. Republicar com
  `copy(visible = ArrayList(visible))` produz um estado `equals` ao anterior — lista nova, mesmos
  objetos, e `CatalogEntry` não sobrescreve `equals`. O Compose descartava a atribuição.
  → um campo `tick` que muda de valor a cada republicação.
- Mesmo com o estado novo, o **strong skipping** do Compose pula um composable cujo parâmetro é a
  mesma instância — e `CatalogEntry` é um objeto Java mutado pelo downloader: a instância nunca muda.
  → estado e progresso descem como `enum` e `Float`, e valor o Compose enxerga.

### 3. "Nenhuma pasta de ROMs configurada" numa biblioteca que tinha pasta

O parâmetro do upstream se chama `noFolders`, mas o call-site passa `state.query.isBlank()` — não são
a mesma pergunta. O efeito: **qualquer** biblioteca vazia acusava falta de pasta e oferecia o
assistente de configuração, que é justamente a tela que a TASK-0022 tirou do caminho. Foi o que o
usuário viu ao apertar voltar.

Agora diz "Nenhum jogo ainda" e o botão leva ao catálogo. E a pasta deixou de ser um pré-requisito:
`seedOwnRomsFolder()` semeia a pasta do próprio app quando a lista está vazia — o app anterior não
pedia pasta nenhuma, varria a sua própria `roms/` e pronto.

### 4. O jogo baixado não aparecia na biblioteca

Este só apareceu porque o download foi levado até o fim: 1,39 GB, `.chd` gravado, cartão em
"Baixado" — e a biblioteca em "Total de jogos: 0". Duas causas independentes:

- **`scan()` trata cada entrada como tree URI do SAF.** A pasta semeada é um caminho POSIX puro:
  não tem tree document id (`resolveTreeUriToPosix` devolve `null`) e `DocumentFile.fromTreeUri` não
  tem o que fazer com ela. Ganhou um ramo próprio, que também não depende de
  `MANAGE_EXTERNAL_STORAGE` — é a pasta de arquivos externos do próprio pacote.
- **A varredura é guardada em cache por chave de diretório, e a chave não muda quando só o conteúdo
  da pasta muda.** Ou seja: o catálogo escreve na pasta e ninguém fica sabendo. Ao concluir um
  download o `CatalogViewModel` invalida a chave (vale para o próximo arranque) e chama
  `HomeInputController.refreshLibrary()` (vale para agora).

## Como validar

Ciclo completo executado no Galaxy A12 (SM-A127M, Android 13), aparelho em `pt-BR`:

| Passo | Resultado |
|---|---|
| Abrir o catálogo pelo botão da biblioteca vazia | 12.628 jogos, com capa, em português |
| Tocar um cartão | confirmação "Baixar este jogo e adicioná-lo à sua biblioteca?" |
| Confirmar | serviço em primeiro plano `isForeground=true`, canal `rom_download_channel` |
| Acompanhar | percentual andando na tela, casando com o `.part` no disco |
| Fim | 1.390.684.864 bytes, `.chd` final, cartão "Baixado", serviço encerrado |
| Biblioteca | "Total de jogos: 1", com a capa do jogo |

**Não exercitado:** pausar/retomar pela UI e o serviço sobrevivendo ao fechamento do app. A retomada
por `Range` foi exercitada de lado — o download foi interrompido por um reinstall e continuou do
`.part` de 334 MB em vez de recomeçar — mas isso não é o mesmo que o botão de pausa.

## Resultado

Entregue. O ciclo baixar → aparecer na biblioteca funciona ponta a ponta.

Vale registrar o padrão: **os quatro defeitos passaram pelo build sem um aviso sequer.** Três deles
(toque, progresso, biblioteca) são código que compila, roda e não faz nada visível. Nesta árvore a
prova é a tela e o disco — captura de tela e `ls` na pasta —, nunca o `BUILD SUCCESSFUL`.

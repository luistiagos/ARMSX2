# TASK-0034: campo de busca no topo da biblioteca

- **Status:** concluída
- **Criada em:** 2026-08-27
- **Concluída em:** 2026-08-27
- **Feature:** [FEAT-0001](../features/FEAT-0001-sync-upstream-oficial.md)
- **Bugs que resolve:** nenhum
- **Commit:** — (o vínculo é o prefixo `TASK-0034:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## Origem

Pedido do usuário: *"precisamos de um campo para buscar um título específico no topo (semelhante à
versão antiga)"*.

## Não precisou ser construído

O fork já tem o campo, o filtro e um teclado próprio. Estava **desligado por padrão**:
`LibraryChromePreferences.showSearch = false`. Ligado, o `SearchField` aparece logo abaixo da barra,
antes da grade — exatamente onde a versão anterior o tinha (`activity_catalog.xml`,
`et_catalog_search`, fixo no topo do catálogo).

Lá ele era escondido só na aba de jogos já baixados (`activity_home.xml`: *"Search bar: hidden by
default"*), aba que aqui não existe como tela separada. Com a grade carregando 12.628 entradas
([TASK-0025](TASK-0025-grade-unica-catalogo-na-biblioteca.md)), uma lista desse tamanho sem busca só
se percorre rolando — então o padrão daqui é ligado.

## O teclado é o do app, e isso é de propósito

Tocar no campo abre o `LibraryKeyboard`, não o teclado do sistema. É desenho do upstream, e o
comentário deles diz por quê: o campo **não é um `TextField` editável**, para que o IME do Android
nunca apareça — assim quem joga de controle consegue digitar, o que num IME de sistema não
funcionaria.

## As três teclas em inglês

`Space`, `Clear` e `Done` estavam **cravadas em código** (`LibraryKeyboard.glyphOf`), aparecendo em
inglês num aparelho em português. Viraram `keyboard.space` / `keyboard.clear` / `keyboard.done`, nas
duas línguas.

Via `I18n.get` e não a `str` composable, porque `glyphOf` não é `@Composable`. O teclado é montado a
cada abertura, então pega o idioma corrente; trocar de idioma com ele **aberto** não é um caso real.

## Como validar

No Galaxy A12:

| Passo | Resultado |
|---|---|
| Abrir o app | `⌕ Buscar jogos…` abaixo da barra |
| Tocar o campo | teclado do app abre, com `⇧ Espaço ⌫ Limpar Pronto` |
| Digitar `nig` | `Total de jogos: 134`, com os cinco `007 - Nightfire` e `Blue Wing Knights` entre eles |
| `Limpar` + `Pronto` | volta a 12.628 |

## Nota lateral: o boot foi validado nesta sessão

Durante este teste um toque escapou para um cartão e **lançou o jogo baixado**. Ele rodou:
BIOS → FMV de abertura → tela de título ("Press the START button"), com o menu de emulação
identificando `SLUS-20265 · CRC 79646C72` lido do próprio disco.

Isso fecha a pendência da [TASK-0026](TASK-0026-bios-embarcada.md), que registrava "o boot até a
tela do PS2 não foi exercitado". O ciclo **catálogo → download → biblioteca → jogar** está provado
ponta a ponta.

## Resultado

Entregue.

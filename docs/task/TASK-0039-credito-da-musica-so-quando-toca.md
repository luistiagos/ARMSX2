# TASK-0039: crédito da música da biblioteca só aparece quando ela toca

- **Status:** concluída
- **Criada em:** 2026-08-27
- **Concluída em:** 2026-08-27
- **Feature:** [FEAT-0001](../features/FEAT-0001-sync-upstream-oficial.md)
- **Bugs que resolve:** nenhum
- **Commit:** — (o vínculo é o prefixo `TASK-0039:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## Origem

Pedido do usuário, depois da [TASK-0036](TASK-0036-musica-de-fundo-desligada-por-padrao.md) (música
desligada por padrão): na tela Sobre o aplicativo, o crédito da música — "Music: Calm Ambient 1
(Synthwave 4k)..." — continuava aparecendo embaixo, descrevendo uma faixa que não toca mais.

## A correção

Em `AboutScreen.kt`, o `Text(str("app.credits.music"), ...)` passa a ficar dentro de
`if (com.armsx2.LibraryMusic.enabled.value)` — o mesmo teste que `AppTab.kt` já usa para decidir
se mostra o slider de volume e o botão de importar faixa. Créditar uma música que não está tocando
não faz sentido; assim que o usuário liga o toggle em Configurações, o crédito volta a aparecer.

**Fora do escopo:** o crédito dos efeitos sonoros do menu (`app.credits.sfx`, logo abaixo) não é
sobre a música da biblioteca — é sobre os sons de clique da UI, que continuam tocando sempre — e
o pedido não o menciona. Também não mexi no caso (pré-existente, fora do que foi pedido) de o
crédito citar sempre a faixa padrão mesmo quando o usuário importou uma faixa própria diferente.

## Como validar

`./gradlew :app:compileGithubDebugKotlin` compilou a mudança isolada sem erro — a build completa
do módulo está falhando agora por causa de `HomeScreen.kt`/`DownloadQueueSection.kt`
(TASK-0038, em andamento por outra sessão nesta mesma árvore, símbolos como `LocalDownloadStates`
ainda não resolvidos), não por nada tocado aqui. Na tela: com o toggle desligado (padrão), a tela
Sobre mostra só o crédito dos efeitos sonoros; ligando "Música da biblioteca" em Configurações, o
crédito da música volta a aparecer junto.

## Resultado

Entregue.

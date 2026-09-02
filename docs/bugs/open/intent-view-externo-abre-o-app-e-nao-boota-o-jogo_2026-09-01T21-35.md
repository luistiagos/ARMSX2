# Bug: intent `VIEW` externo abre o app na biblioteca e não boota o jogo, em silêncio

- **Detectado em:** 2026-09-01 21:35
- **Origem:** Galaxy A12 `SM-A127M`. Observado ao automatizar o teste de campo da
  [TASK-0067](../../task/TASK-0067-merge-com-o-upstream.md) — o boot por intent era o caminho
  natural e não funcionou, então o teste passou a tocar a capa na grade.
- **Errors (serviço):** nenhum — não é crash, não gera telemetria
- **Classe:** fail
- **Reincidência:** não registrado antes
- **Feature:** nenhuma
- **Tasks que o resolvem:** [TASK-0073](../../task/TASK-0073-lancamento-externo-entrega-file-uri-cru-ao-core.md)

## Sintoma

Um `VIEW` com a ROM na `data` **abre o app na biblioteca** e para por aí. O jogo não boota, e nada
é dito — nem toast, nem log, nem erro.

```bash
adb shell am start -a android.intent.action.VIEW \
  -n come.nanodata.armsx2/com.armsx2.Main \
  -d "file:///storage/emulated/0/Android/data/come.nanodata.armsx2/files/roms/007%20-%20Everything%20or%20Nothing%20%28USA%29.chd"
```

Com o app previamente parado (`am force-stop`), o `ActivityTaskManager` registra o START com o
`dat=file:///…` correto e o app sobe — mas 25 s depois a tela é a grade "Salvos", e o logcat da
sessão inteira **não tem uma linha de CDVD, `VMManager` ou abertura de imagem**. O `.chd` existe,
está na pasta de dados do próprio app, e boota normalmente pela grade.

## Por que isso importa mais do que parece

Este é o caminho dos **frontends externos**. O comentário do próprio código diz isso, em
[`MainActivityRuntime.kt:5493`](../../../platforms/android/app/src/main/java/com/armsx2/runtime/MainActivityRuntime.kt#L5493):

> *Frontends (Cocoon/Daijisho/ES-DE) list the .cue, since that's the canonical disc descriptor…*

E há trabalho deliberado investido nele: `resolveCueToTrack` mapeia `.cue` para a faixa, e
`externalGameInfo` existe justamente para que o lançamento externo resolva as mesmas configurações
por jogo que o lançamento pela biblioteca resolve. Se o caminho não dispara, tudo isso está morto.

O `HomeShortcuts.kt:45` também depende dele (atalhos da tela inicial passam o extra `path`).

## O que já foi verificado — e o que NÃO foi

Lido, e **aparentemente correto**:

| ponto | estado |
|---|---|
| `Main` no manifesto | `exported=true`, `intent-filter` com `VIEW` + `DEFAULT`/`BROWSABLE` e schemes `content` e `file` |
| `onCreate` | chama `handleExternalLaunchIntent(intent)` (linha 2570) |
| `onNewIntent` | chama `setIntent` e `handleExternalLaunchIntent` (linha 5466) |
| `extractLaunchUri` | `intent.data` é a primeira coisa que ele devolve |
| o gate | `LaunchedEffect(setupComplete, nativeReady, pendingExternalLaunch, pendingLaunch)` chama `launchPendingExternalGameIfReady()` (linha 2636), então a espera por "pronto" deveria destravar sozinha |

Ou seja: **a causa não foi identificada.** Ler o encanamento não explica o sintoma, e é por isso
que este registro existe em vez de uma correção.

Não foi verificado, e precisa ser antes de qualquer conclusão:

1. **Se um frontend real quebra.** O que se observou foi `am start` com `file://` a partir do
   shell. Cocoon/Daijisho/ES-DE podem mandar `content://` com grant, que é outro caminho dentro de
   `extractLaunchUri` e `persistReadGrant`. **O bug pode ser só do `file://` por shell.**
2. **Se é regressão do merge** ([TASK-0067](../../task/TASK-0067-merge-com-o-upstream.md)) ou se já
   era assim antes. Não foi testado na árvore pré-merge.
3. Se `launchGame` chega a ser chamado e falha lá dentro, ou se nem chega.

## Próximo passo sugerido

Instrumentar os três pontos com uma linha de log cada — `handleExternalLaunchIntent` na entrada
(com a URI), `launchPendingExternalGameIfReady` no `return` do gate (com os dois booleanos), e
`launchGame` na entrada. Uma execução responde qual dos três não acontece, e o defeito hoje é
invisível justamente porque nenhum deles fala.

## Causa encontrada (2026-09-02)

A seção acima dizia que a causa não estava identificada e sugeria instrumentar três pontos. **A
instrumentação já existia**: `launchGame` imprime `@@ANDROID_LAUNCH_GAME@@` com a URI em toda
chamada. O log capturado ontem já tinha a resposta.

`launchGame` é chamado. O argumento é que está errado — chega `file:///…/007%20-%20….chd` em vez
do caminho nu `/storage/…/007 - Everything or Nothing (USA).chd`. O core rejeita e volta à
biblioteca em silêncio. Corrigido pela [TASK-0073](../../task/TASK-0073-lancamento-externo-entrega-file-uri-cru-ao-core.md).

Duas coisas que a suspeita de ontem errou, e vale registrar:

1. **Não é específico de `am start` por shell.** Qualquer chamador que mande `file://` cai nisso,
   e `resolveCueToTrack` — escrito para Cocoon/Daijisho/ES-DE — produz `file://` por construção.
2. **Não é regressão do merge.** O idioma de conversão falta no lado externo desde que ele existe.

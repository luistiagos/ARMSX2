# TASK-0069: o laço de quadro do analógico só deve existir quando há analógico

- **Status:** em andamento
- **Criada em:** 2026-09-01
- **Concluída em:** —
- **Feature:** nenhuma
- **Bugs que resolve:** nenhum
- **Backlog:** desdobramento do item 2 de [`desempenho-com-clock-cortado-a55`](../backlog/desempenho-com-clock-cortado-a55.md)
- **Commit:** — (o vínculo é o prefixo `TASK-0069:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## Como isto apareceu

A [TASK-0063](TASK-0063-fundo-da-biblioteca-para-de-animar.md) parou o fundo animado da biblioteca e
levou a tela de **0,94 para 0,15 de um núcleo**. Sobraram 15 pontos na main thread — **com zero
quadros desenhados**, o que já não podia ser o fundo. Ficaram registrados lá como "próximo alvo".

São estes.

## A medição que apontou o culpado

Não foi busca no código: foi a **frequência**. Amostrando as trocas de contexto voluntárias da main
thread na biblioteca parada, sem tocar na tela:

```
voluntary_ctxt_switches:  2048
--- 10 s depois ---
voluntary_ctxt_switches:  2666
```

**618 em 10 s ≈ 62 por segundo.** Isso é o vsync. A main thread acorda uma vez por quadro, faz
alguma coisa e volta a dormir — sem produzir quadro. Com esse número, procurar quem pede callback do
choreographer foi imediato.

## A causa

`HomeScreen.kt:358` — o integrador de rolagem do analógico:

```kotlin
LaunchedEffect(gridState) {
    var lastFrame = withFrameNanos { it }
    while (true) {
        val frame = withFrameNanos { it }
        val dt = ...
        val velocity = HomeInputController.scrollVelocity.floatValue
        if (abs(velocity) > 0.08f) {
            gridState.scrollBy(velocity * pxPerSecond * dt)
        }
    }
}
```

O laço é **infinito e incondicional**. Ele pede um callback de quadro a cada vsync, para sempre,
enquanto a biblioteca estiver na tela — mesmo sem controle conectado, mesmo com o analógico parado.
O `if` lá dentro decide se **faz** trabalho, mas não decide se o laço **acorda**: quando a
velocidade é zero, o custo do `withFrameNanos` já foi pago.

E a velocidade é zero na esmagadora maioria do tempo. `HomeScreen.kt:1865` zera abaixo do limiar:

```kotlin
scrollVelocity.floatValue = if (abs(velocity) > 0.08f) velocity.coerceIn(-1f, 1f) else 0f
```

## A correção

Trocar o laço perpétuo por um que **existe só enquanto existe velocidade**. Um `derivedStateOf`
sobre a velocidade vira a chave do `LaunchedEffect`: quando o analógico é defletido, o efeito começa
e passa a dirigir os quadros; quando volta ao centro, o efeito é cancelado e ninguém mais acorda.

A rolagem em si não muda em nada — enquanto o analógico está sendo usado, o laço roda exatamente
como antes, na mesma taxa e com o mesmo `dt`.

## Escopo

**Entra:**

- `HomeScreen.kt` — o portão sobre o laço de rolagem da grade.

**NÃO entra:**

- **O laço gêmeo em `SettingsWidgets.kt:461`.** É o mesmo padrão e o mesmo defeito, na tela de
  ajustes (rolagem livre do analógico direito). **Não toquei porque o arquivo está modificado por
  outra sessão** que está no meio de um merge com o upstream. Fica registrado aqui para não se
  perder: assim que a árvore estabilizar, é a mesma correção, e vale medir a tela de ajustes do
  mesmo jeito (trocas voluntárias por segundo).
- **Os `withFrameNanos` de disparo único** em `ControllerFocus.kt:78` e `ShaderChainSection.kt:329`.
  São `withFrameNanos {}` uma vez, para esperar um quadro; não são laços.

## Como validar

Biblioteca aberta e parada, sem jogo, sem tocar na tela:

```bash
PID=$(adb shell pidof come.nanodata.armsx2)
adb shell "grep -E '^voluntary' /proc/$PID/task/$PID/status"; sleep 10
adb shell "grep -E '^voluntary' /proc/$PID/task/$PID/status"
adb shell "sh /data/local/tmp/tsample.sh 15"
```

Critérios, contra a linha de base desta medição:

1. **Trocas voluntárias da main muito abaixo de 62/s** (hoje: 618 em 10 s).
2. **Main thread abaixo dos 15% de hoje**, com o total da tela abaixo de 0,15 núcleo.
3. **A rolagem pelo analógico continua funcionando** — deflectir o stick na biblioteca ainda rola a
   grade, na mesma velocidade. Este é o critério que pega a regressão; os dois primeiros só medem
   o ganho.

> ⏳ **Validação pendente.** A árvore está com 222 arquivos modificados por um merge do upstream em
> andamento de outra sessão; construir agora mediria o merge pela metade, não esta mudança. Medir
> quando estabilizar.

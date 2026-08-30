# TASK-0057: limitar o fundo 2D da biblioteca à mesma taxa que o irmão em GL

- **Status:** em andamento
- **Criada em:** 2026-08-30
- **Concluída em:** —
- **Feature:** nenhuma
- **Bugs que resolve:** nenhum
- **Backlog:** item 2 de [`desempenho-com-clock-cortado-a55`](../backlog/desempenho-com-clock-cortado-a55.md)
- **Commit:** — (o vínculo é o prefixo `TASK-0057:` no assunto)
- **Revertida por:** —
- **Publicado em:** —


> **Situação em 2026-08-30:** o código está escrito e compila (`:app:compileGithubDebugKotlin`, BUILD SUCCESSFUL). O status
> segue **em andamento** de propósito: os critérios de "Como validar" abaixo exigem o Galaxy A12, e
> nada aqui foi executado em aparelho. Compilar não é validar.

## Contexto

Os dois fundos animados da biblioteca desenham a mesma cena e escolheram taxas diferentes.

`XmbGlView` (GLES3) limita, e diz por quê (`XmbGlView.kt:113`):

```kotlin
// Cap to ~30 fps. The wave is slow, so 30 looks identical to 60 but roughly
// halves GPU/CPU load — without this the loop ran flat-out at vsync (60) and
// spun the RP6's fans up.
private const val FRAME_TARGET_MS = 33L
```

`LibraryWaveBackground` (Canvas 2D), que é o fundo dos aparelhos **sem** GLES3 utilizável — ou
seja, exatamente o Mali-G52 do A12 — não limita nada (`LibraryWaveBackground.kt:44`):

```kotlin
LaunchedEffect(Unit) {
    var start = 0L
    withInfiniteAnimationFrameNanos { start = it }
    while (true) {
        withInfiniteAnimationFrameNanos { now ->
            timeSec.floatValue = (now - start) / 1_000_000_000f   // <-- todo quadro do painel
        }
    }
}
```

Cada escrita em `timeSec` invalida o `Canvas`, e cada quadro reconstrói 8 `Path` (corpo + crista de
4 camadas, 64 segmentos cada), 4 gradientes verticais e 10 glifos com `Path`/`rotate`. A 60 Hz é o
dobro do trabalho — e da alocação — que o irmão em GL já concluiu ser desnecessário.

O argumento do comentário citado vale igual aqui: a onda é lenta, 30 é indistinguível de 60.

## O que esta task **não** afirma

O backlog atribui a medição da tela "Salvos" (~1,15 núcleo parado, `RenderThread` 60%) a este
fundo. **Isso não se sustenta como está escrito:** `SaveManagerScreen` monta
`ArmsBackdrop { ... }` sem o parâmetro `backgroundLayer` (`SaveManagerScreen.kt:58`), e é esse
parâmetro que carrega o fundo animado — só `HomeScreen` o passa (`HomeScreen.kt:242`).

Fica em aberto **uma** pergunta, que é o que decide: `AppNavigation` troca de tela com um
`AnimatedContent` cujo `exit` é `ExitTransition.None` (`AppNavigation.kt:78`). Se o Compose
descartar o destino que sai, a `HomeScreen` é desmontada, o `LaunchedEffect` da onda é cancelado e
o fundo não pode ser o custo medido em "Salvos". Se ele mantiver o conteúdo antigo composto, pode.
Não foi verificado, e não se escreve correção sobre isso sem verificar.

A medição que responde está em "Como validar". Esta task entrega o limite de taxa, que é ganho
onde a onda **está** na tela — a biblioteca, que é a primeira tela do app e onde o usuário escolhe
o jogo — e não depende da resposta.

## Objetivo

Que o fundo 2D custe metade do que custa hoje na tela da biblioteca, sem mudança visível.

## Escopo

**Entra:**

- `platforms/android/app/src/main/java/com/armsx2/ui/home/LibraryWaveBackground.kt` — o relógio da
  animação só publica um novo valor quando passaram ≥33 ms desde o último, com a constante e a
  justificativa apontando para o par em `XmbGlView`.

**NÃO entra:**

- **Desligar o fundo por padrão em aparelho sem núcleo grande.** É decisão de produto, muda o que
  o usuário vê e precisa da medição de "Como validar" antes.
- **Pausar o fundo quando não há interação.** Só faz sentido depois de responder a pergunta do
  `AnimatedContent` acima; se a `HomeScreen` já é descartada, não há nada a pausar.
- **O `SaverGlView`** (`FRAME_TARGET_MS = 16L`). É opt-in explícito, e uma simulação de partículas
  a 30 fps fica visivelmente pior — o argumento "a onda é lenta" não se transfere.
- **As alocações por quadro** (os 8 `Path`). Reduzir a taxa já corta metade; reaproveitar os
  objetos é outra task, e cabe medir antes.

## Como validar

Com o app aberto **na biblioteca**, parado, sem jogo:

```bash
PID=$(adb shell pidof come.nanodata.armsx2)
adb shell "for t in /proc/$PID/task/*; do \
  echo \"\$(cat \$t/comm) \$(awk '{print \$14+\$15}' \$t/stat)\"; done" \
  | grep -E 'RenderThread|armsx2|hwuiTask'
```

Duas amostras com 5 s entre elas, antes e depois. Critério: a soma dos deltas cai perto da metade.
Forçar o caminho 2D no aparelho que tem GLES3 pela preferência "fundo animado 2D"
(`LibraryBackground.animated2D`), senão mede-se o `XmbGlView`, que já era limitado.

E a pergunta em aberto, que se responde na mesma sessão: repetir a amostragem **na tela "Salvos"**.
Se os números continuarem altos lá depois desta task, a `HomeScreen` sobrevive à troca de rota e o
custo é de outra coisa — abre-se um bug com essa evidência. Se caírem junto com a biblioteca, o
fundo estava mesmo rodando por baixo e o backlog estava certo pelo motivo errado.

# Bug: o véu do teclado virtual mede 0 × 0, então tocar fora nunca fechou

- **Detectado em:** 2026-08-31 (relato do usuário: "ao clicar fora dele, ele deveria sair, isso foi
  requisitado já antes mas pelo jeito não foi implementado")
- **Origem:** `platforms/android/app/src/main/java/com/armsx2/ui/home/LibraryKeyboard.kt::Overlay`
- **Errors (serviço):** nenhum — não lança, não trava, apenas não acontece
- **Classe:** fail
- **Reincidência:** primeira vez registrada; o comportamento nunca funcionou desde que foi escrito
- **Feature:** nenhuma
- **Tasks que o resolvem:** [TASK-0062](../../task/TASK-0062-teclado-virtual-toque-fora-e-latencia.md)

## Sintoma

Com o teclado da biblioteca aberto, tocar em qualquer ponto **fora** do painel de teclas não faz
nada. Só `Done`, `BACK` ou o gesto de voltar fecham. O fundo também não escurece, embora o código
peça 50% de preto.

## Causa raiz

```kotlin
AnimatedVisibility(visible = isVisible, enter = fadeIn(tween(140)), exit = fadeOut(tween(90))) {
    Box(
        Modifier
            .matchParentSize()                                   // <- aqui
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(...) { close() },
    )
}
```

`Modifier.matchParentSize()` não mede nada por si: é **parent data**, lida apenas pela
`MeasurePolicy` do `Box` que a hospeda. O pai imediato deste `Box`, porém, não é o `Box` da raiz —
é o `Layout` interno do `AnimatedVisibility`, cuja política é `AnimatedEnterExitMeasurePolicy`.
Desmontando o `animation.aar` em uso (`androidx.compose.animation:animation-android:1.11.4`):

```
$ javap -p -c androidx/compose/animation/AnimatedEnterExitMeasurePolicy.class
  ...
  invokeinterface androidx/compose/ui/layout/Measurable."measure-BRTryo0":(J)Placeable
  invokevirtual   androidx/compose/ui/layout/Placeable.getWidth:()I   /  Math.max
  invokevirtual   androidx/compose/ui/layout/Placeable.getHeight:()I  /  Math.max
```

Ela mede cada filho com as constraints recebidas e devolve o maior — **não lê `parentData` em
lugar nenhum**. A parent data é descartada sem erro nem aviso.

O `AnimatedVisibility` é filho de um `Box` que não propaga mínimos, então chega com
`min = 0`. O `Box` do véu não tem filho nem modificador de tamanho: com `matchParentSize()`
ignorada, ele envolve o conteúdo, que é nada, e mede **0 × 0**. Sem área desenhada (nada de
escurecer) e sem área de toque (nada de fechar).

É a classe de defeito que compila, linka, roda e não faz nada — o modificador certo aplicado no
lugar onde ninguém o lê.

## Correção

`fillMaxSize()` no lugar de `matchParentSize()`: preenche as constraints máximas, que são
repassadas intactas, e portanto não depende de quem é o pai. E fora do `AnimatedVisibility`, senão
a área de toque sobrevive aos 90 ms da animação de saída e engole o toque seguinte.

O escurecimento de 50% **não** é reposto junto: ele nunca chegou a aparecer em build nenhum, e este
host é composto acima de painéis que desenham o próprio véu (a busca de configurações, o `PadModal`
de nomear preset). Ver o escopo da [TASK-0062](../../task/TASK-0062-teclado-virtual-toque-fora-e-latencia.md).

## Validado em aparelho — 2026-09-03

A correção já estava no código (`fillMaxSize()` num `if (isVisible)` simples, fora do
`AnimatedVisibility`); faltava a prova de interação, que é o que este relatório exigia. Feita em
moto g86 5G, Android 16 (SDK 36), `github/release`:

**1. Tocar fora fecha, e o toque não vaza para o que está embaixo.**
Catálogo aberto → toque no campo "Buscar jogos..." → o teclado sobe → toque bem acima do painel de
teclas. O teclado fecha e **nada mais acontece**: nenhum jogo abre, nenhuma ação de cabeçalho
dispara. É o véu engolindo o toque, que é o comportamento desenhado.

**2. O toque seguinte não é engolido.**
Este é o motivo de o véu ter virado um `if` simples em vez de continuar dentro do
`AnimatedVisibility`: com a animação de saída de 90 ms, a área de toque sobreviveria ao fechamento
e comeria o próximo toque. Testado na sequência, sem pausa: abrir → tocar fora (fecha) → tocar
imediatamente no campo de busca. **O teclado volta a subir**, ou seja, o segundo toque chegou ao
destino.

O escurecimento de 50% continua deliberadamente ausente, pelo motivo registrado na
[TASK-0062](../../task/TASK-0062-teclado-virtual-toque-fora-e-latencia.md): este host é composto
acima de painéis que já desenham o próprio véu, e um véu aqui escureceria a própria lista que o
usuário está filtrando.

> A [TASK-0062](../../task/TASK-0062-teclado-virtual-toque-fora-e-latencia.md) **continua em
> andamento**: ela cobre também a latência de digitação, que tem relatório próprio
> ([digitar-custa-97-a-450ms](../open/armsx2-fork/digitar-custa-97-a-450ms-por-tecla-na-thread-da-ui_2026-08-31T21-30.md))
> e ainda não fechou. Este relatório, o do véu, está resolvido.

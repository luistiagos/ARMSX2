# TASK-0070: a onda XMB em GL também para de animar

- **Status:** em andamento
- **Criada em:** 2026-09-01
- **Concluída em:** —
- **Feature:** nenhuma
- **Bugs que resolve:** nenhum
- **Backlog:** desdobramento do item 2 de [`desempenho-com-clock-cortado-a55`](../backlog/desempenho-com-clock-cortado-a55.md)
- **Commit:** — (o vínculo é o prefixo `TASK-0070:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## A decisão

A [TASK-0063](TASK-0063-fundo-da-biblioteca-para-de-animar.md) parou o fundo **2D** por decisão do
dono do produto. Ficou registrado lá, explicitamente, que o `XmbGlView` — a onda XMB em GLES3, o
caminho dos aparelhos onde o GL sobe — **não** entrava, porque nunca foi medido e derrubá-lo junto
seria decidir sozinho.

A pergunta foi feita e respondida em 2026-09-01: *"vamos deixar parado para todos"*.

## O que muda

`XmbGlView.RenderThread` desenhava um quadro, dormia o resto dos 33 ms e repetia — **para sempre**,
numa thread EGL própria, enquanto a biblioteca estivesse na tela. Agora desenha **um** quadro e
**estaciona** num lock, até que alguma coisa peça outro.

Três coisas podem pedir, e todas avisam o lock:

| quem | quando |
|---|---|
| `resize()` | a janela mudou de tamanho (rotação) |
| `finish()` | a view está sendo destruída |
| `LibraryBackgroundColorPreferences.glRedrawRequest` | o usuário mexeu na cor ou no ciclo RGB |

**Uma thread estacionada está bloqueada, não girando** — custa zero até ser acordada.

## O terceiro item da tabela é a parte que não podia ser esquecida

O seletor de cor da biblioteca promete, no próprio comentário do código, *"Applies live on the next
GL frame"*. Sem timer não existe "próximo quadro": a cor mudaria e a onda só recolheria a mudança na
próxima rotação de tela. Isso seria uma regressão silenciosa do seletor de cor.

Por isso `LibraryBackgroundColorPreferences` ganhou um `glRedrawRequest`: um gancho `@Volatile` que
o `apply()` (funil de `set`, `reset` e `load`) e o `setRgbCycle()` disparam. A thread GL o instala
enquanto está viva e o limpa no teardown; `null` significa que não há onda GL de pé, e aí o caminho
2D já recompõe sozinho.

O **ciclo RGB** vira cor fixa neste caminho, exatamente como já acontecia no 2D depois da TASK-0063 —
um ciclo é uma animação por definição, e a decisão foi não ter animação.

## Escopo

**Entra:**

- `XmbGlView.kt` — desenha em `FROZEN_T` e estaciona; `wake()` em `resize`/`finish`; instala e limpa
  o `glRedrawRequest`. Sai o `FRAME_TARGET_MS`, que não tem mais função.
- `Theme.kt` — o gancho `glRedrawRequest` e as três chamadas que o disparam.

**NÃO entra:**

- **Corrigir o shader que impede a onda GL de compilar no Mali**
  ([bug](../bugs/open/xmb-gl-nao-compila-shader-uniform-chamado-length_2026-09-01T10-45.md): um
  `uniform` chamado `length`). Aquele registro dizia que corrigi-lo seria regressão porque devolveria
  uma animação; **com esta task, esse impedimento cai** — a onda GL agora é parada, então corrigir o
  shader passa a ser uma escolha puramente visual (qual fundo parado o usuário do Mali vê). Continua
  fora daqui porque é decisão de aparência, não de desempenho.
- **O `SaverGlView`** (Flurry e os Really Slick). Opt-in explícito, desligado por padrão.

## Como validar

> ⏳ **Nada disto foi medido, e não dá para medir agora.** Dois motivos, e os dois importam:
>
> 1. **A árvore está com 222 arquivos modificados** por um merge do upstream em andamento de outra
>    sessão, e a compilação Kotlin falha nos arquivos dela (`RecentGamesAccess` não resolvido).
>    Construir agora mediria o merge pela metade.
> 2. **No único aparelho disponível esta onda nunca roda.** O Galaxy A12 cai no fundo 2D por causa
>    do bug do shader acima, e a thread `xmb-gl` sai na falha (conferido: zero no `/proc`). Ou seja,
>    **esta mudança é intestável aqui por construção.**
>
> Está escrita porque a decisão foi tomada e o código é pequeno e isolado; não está validada, e a
> task não finge que está.

Num aparelho onde o GL sobe, com a biblioteca aberta e parada:

```bash
PID=$(adb shell pidof come.nanodata.armsx2)
adb shell "for t in /proc/$PID/task/*; do echo \"\$(cat \$t/comm) \$(awk '{print \$14+\$15}' \$t/stat)\"; done" | grep xmb-gl
```

Critérios:

1. A thread `xmb-gl` **existe** (a onda subiu) e o contador **não cresce** com a tela parada.
2. Mudar a cor da biblioteca nos ajustes **recolore a onda na hora** — é o critério que pega a
   regressão que o `glRedrawRequest` existe para evitar.
3. Girar a tela redesenha na nova resolução.

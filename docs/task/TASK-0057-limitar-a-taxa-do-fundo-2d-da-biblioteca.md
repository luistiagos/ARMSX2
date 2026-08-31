# TASK-0057: o fundo 2D da biblioteca custava 38 ms por quadro

- **Status:** em andamento
- **Criada em:** 2026-08-30
- **Concluída em:** —
- **Feature:** nenhuma
- **Bugs que resolve:** nenhum
- **Backlog:** item 2 de [`desempenho-com-clock-cortado-a55`](../backlog/desempenho-com-clock-cortado-a55.md)
- **Commit:** — (o vínculo é o prefixo `TASK-0057:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## Contexto

Na biblioteca parada, sem jogo nenhum, o app queima quase um núcleo. Medido no Galaxy A12
(`SM-A127M`, Mali-G52), amostra de 15 s com o dedo longe da tela:

| thread | % de um núcleo |
|---|---|
| `RenderThread` | 37 |
| main (`nanodata.armsx2`) | 23 |
| `hwuiTask0` / `hwuiTask1` | 9 cada |
| `mali-cmar-backend` | 7 |
| **total** | **~0,85 núcleo, contínuo** |

E o `gfxinfo` do mesmo processo:

```
Total frames rendered: 1661
Janky frames: 1656 (99.70%)
50th percentile: 38ms   90th: 44ms
Number Slow issue draw commands: 1656
50th gpu percentile: 19ms
HISTOGRAM: ... 29ms=1 31ms=2 32ms=9 34ms=372 36ms=345 38ms=222 40ms=120 42ms=255 44ms=264 46ms=68 48ms=3
```

**Nenhum quadro abaixo de 29 ms.** A tela desenhava a exatos 30,0 fps — não por limite, mas porque
um quadro de 38 ms cai em todo *segundo* vsync de 60 Hz.

## Duas coisas que a medição corrigiu antes de qualquer código

**1. "Salvos" é a biblioteca.** Uma revisão de escritório contestou a atribuição do backlog
procurando a tela em `SaveManagerScreen.kt` — o gerenciador de arquivos de save, que de fato monta
`ArmsBackdrop` sem `backgroundLayer`. Mas "Salvos" é a **aba** da biblioteca (a outra é "Catálogo"),
montada por `HomeScreen`, e ela desenha o fundo. Confirmado por captura de tela: ondas e glifos de
PlayStation, que são o caminho 2D. **O backlog estava certo; a revisão, errada.**

**2. Limitar a taxa não era a correção.** É a primeira opção que o backlog lista, e foi a primeira
escrita — um portão de 33 ms, espelhando o `FRAME_TARGET_MS` do irmão em GL. **No-op:** a tela já
estava presa em 30 fps pelo próprio custo. Pior, um quadro chegando a 32,9 ms seria descartado por
ele: 30 fps com engasgo de 20.

## A causa, e ela está no código

`Number Slow issue draw commands` em 100% dos quadros *sugeriu* o lado de emitir comandos — mas essa
métrica é relativa ao orçamento de 16,7 ms e diz ~100% para qualquer app a 30 fps, então ela aponta
uma direção, não prova uma causa (ver "O que está medido"). A prova está no código:

```kotlin
val baseY = h * (0.40f + 0.16f * f)                 // sem t
val amp   = h * (0.05f + 0.028f * (1f - f))         // sem t
val phase = t * speed + layer * 2.2f                // <- o unico lugar onde o relogio entra
...
brush = Brush.verticalGradient(
    0.00f to tint.copy(alpha = 0f),
    ...
    startY = baseY - amp * 1.6f,                    // sem t
    endY = h,                                       // sem t
)
```

**Os cinco gradientes verticais da cena — o fundo mais um por camada de onda — dependem só da cor e
da altura do canvas.** O relógio entra apenas em `phase`, que move a *curva*, não a *tinta*. Eles
eram reconstruídos trinta vezes por segundo para produzir o objeto idêntico, e cada reconstrução faz
o Skia entregar um shader novo ao driver. Junto com eles, oito `Path` e catorze `Stroke` por quadro.

## Escopo

**Entra:**

- `LibraryWaveBackground.kt` — `WaveScratch`, que constrói uma vez (por cor e por tamanho) tudo o que
  não se move: os cinco `Brush`, as cores e larguras de crista, os raios e traços dos glifos. Os
  `Path` **mudam** todo quadro, então são reusados com `reset()` em vez de cacheados.
- O limite de ~30 fps, com portão em **25 ms** e não 33: a 60 Hz os callbacks chegam em
  0/16,7/33,3 ms e um limite de 33 senta em cima do terceiro, de modo que qualquer jitter abaixo
  dele descarta o quadro — 30 fps com engasgo de 20. Qualquer valor em (16,7 ; 33,3) escolhe o
  segundo callback de forma determinística, e continua dando 30 fps a 90 ou 120 Hz.

**NÃO entra:**

- **Reduzir `WAVE_LAYERS` de 4 para 2.** É a alavanca contra o preenchimento de GPU (as bandas se
  sobrepõem em quase toda a tela), mas muda o que o usuário vê. Se os números abaixo não bastarem,
  é a próxima — e é decisão de produto, não de otimização.
- **Desligar o fundo por padrão** ou **pausá-lo sem interação.** Mesmo motivo.
- **O `SaverGlView`** (`FRAME_TARGET_MS = 16L`). Opt-in explícito, e uma simulação de partículas a
  30 fps fica visivelmente pior.

## O que está medido, e o que NÃO está

**Medido, e sólido:**

| | |
|---|---|
| a tela desenhava a **exatos 30,0 fps** antes de qualquer mudança | 453 quadros / 15,1 s, duas vezes |
| um portão de **33 ms é no-op** nesse estado | 30 fps antes e 30 fps depois |
| com o custo por quadro reduzido, a tela **alcança 60 fps** | 906 quadros / 15,9 s |
| o portão de **25 ms segura em 30 fps** | 454 quadros / 15,1 s |
| o desenho é **visualmente idêntico** | captura de tela antes/depois |

**O A/B rodou, e o ganho é pequeno.** Dois APKs diferindo **só** no `WaveScratch`, ambos limitados
a 30 fps, instalados alternadamente na mesma sessão, com o aparelho ocioso (3 KB de rede em 20 s) e
a mesma grade nos dois braços. Cada braço: 40 s de assentamento, 20 s de amostra.

| | `RenderThread` | main | `hwuiTask0/1` | `mali-cmar` | total | fps |
|---|---|---|---|---|---|---|
| rodada 2, **sem** cache | 41 | 28 | 10 / 10 | 8 | **97** | 30 (363 quadros) |
| rodada 2, **com** cache | 41 | 25 | 10 / 10 | 8 | **94** | 30 (363 quadros) |
| rodada 3, **com** cache | 41 | 25 | 10 / 10 | 8 | **94** | 30 (363 quadros) |
| rodada 3, **sem** cache | 41 | 28 | 9 / 9 | 7 | **94** | 30 (363 quadros) |

O único sinal repetível é a **main thread: 25% com cache contra 28% sem**, nas duas rodadas. É
exatamente onde os `Brush` são alocados (a fase de draw do Compose roda na UI thread; a
`RenderThread` só rasteriza). **~3 pontos de um núcleo.** O total oscila dentro do ruído.

Ou seja: a análise do desperdício estava certa e o ganho é real, mas é **pequeno**, e
**o item 2 continua sem solução**. O custo que resta — `RenderThread` 41 + `hwui` 20 + `mali` 8 —
é rasterização e preenchimento das quatro faixas em alpha, não criação de objetos.

**E o limite de taxa não entrega ganho neste aparelho**, só evita uma regressão: sem o cache a tela
não passava de 30 fps de qualquer modo; com o cache ela alcançaria 60 e gastaria a folga
(medido: 111% de um núcleo). O portão a segura nos mesmos 30.

### Uma afirmação anterior desta task estava errada

Uma versão anterior deste texto apresentou como prova do ganho uma tabela de `gfxinfo`:
`Janky frames` 99,70% → 0,16% e `Number Slow issue draw commands` 100% → 0,16%.

**Essas duas métricas não servem para esta comparação.** Ambas são medidas contra o orçamento de
16,7 ms de um vsync de 60 Hz, então **um app que desenha a 30 fps é 100% "janky" por definição**,
por mais barato que cada quadro seja. O que elas mostraram foi o app ter passado a 60 fps, não o
quadro ter ficado mais barato — e com o limite de volta as duas voltam a ~100% (medido: 603 de 605
quadros).

## O que resta fazer, e por que não fiz

O que sobrou é preenchimento de GPU, e as três opções que o backlog lista para ele **mudam o que o
usuário vê**, então são decisão de produto e não de otimização:

1. **`WAVE_LAYERS` de 4 para 2.** As faixas se sobrepõem em quase toda a tela e cada uma é um
   `drawPath` em alpha de ~50% do painel. É a alavanca mais direta contra os 41% da `RenderThread`.
2. **Cortar a faixa onde o gradiente já é invisível.** Cada banda preenche até o rodapé enquanto o
   próprio gradiente a leva a `alpha = 0` no caminho — a parte de baixo custa preenchimento cheio e
   não aparece. Invisível de verdade, mas mexe na geometria.
3. **Não animar quando ninguém está interagindo**, ou desligar o fundo por padrão em aparelho sem
   núcleo grande.

## Como validar

Com o app aberto **na biblioteca**, parado, sem jogo, e o GOS morto:

```bash
adb shell "sh /data/local/tmp/fps.sh 15"          # quadros realmente desenhados
adb shell "sh /data/local/tmp/tsample.sh 12 'RenderThread|nanodata|hwuiTask|mali-cmar'"
adb shell "dumpsys gfxinfo come.nanodata.armsx2" | grep -E 'Janky|Slow issue|50th'
```

Critério: 30 fps desenhados (**medido**), e o total por thread abaixo do 0,85 núcleo da linha de
base (**não medido** — ver acima).

**Não usar `Janky frames` nem `Number Slow issue draw commands` como critério.** São relativos ao
orçamento de 16,7 ms e dão ~100% para qualquer app que desenhe a 30 fps, independentemente do custo
real do quadro. Foi assim que a primeira leitura desta task saiu errada.

**Armadilhas de medição** que custaram uma amostra cada, para quem repetir:

- `/proc/<tid>/stat` traz `comm` entre parênteses e ele **pode conter espaços** (`CPU Thread`), o que
  desloca todo `awk '{print $14+$15}'`. Contar campos a partir do **último** `)`.
- Em `sh`, use `${12}`: `$12` é `$1` seguido de um `2` literal, e a soma dá zero — a thread parece
  ociosa.
- O laço de amostragem gera ~90 processos por snapshot, então a janela real é bem maior que o
  `sleep` pedido. Dividir por `/proc/uptime`, não pelo `sleep`, senão todo percentual sai ~40% alto.

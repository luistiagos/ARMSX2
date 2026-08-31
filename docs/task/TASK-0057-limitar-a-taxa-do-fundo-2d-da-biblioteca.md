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

**NÃO medido — e uma afirmação anterior desta task estava errada:**

Uma versão anterior deste texto apresentou como prova do ganho do cache uma tabela de `gfxinfo`:
`Janky frames` de 99,70% → 0,16% e `Number Slow issue draw commands` de 100% → 0,16%.

**Essas duas métricas não servem para esta comparação.** Ambas são medidas contra o orçamento de
16,7 ms de um vsync de 60 Hz, então **um app que desenha a 30 fps é 100% "janky" por definição**,
por mais barato que cada quadro seja. A "melhora" que li foi o app ter passado a desenhar a 60 fps,
não o quadro ter ficado mais barato. Com o limite reintroduzido — mesmo custo por quadro, 30 fps de
novo — as duas voltam a ~100%, como tinham de voltar.

A única métrica que compara os dois estados é **CPU por segundo em taxa igual**, e essa medição
**não foi obtida**: o A/B ficou pronto (dois APKs diferindo só no cache, ambos limitados a 30 fps)
mas a rodada foi invalidada porque **outra sessão iniciou um download no mesmo aparelho** no meio
dela — o painel "Downloads em Andamento" mudou o layout da tela sob teste, apareceram
`RenderThread` extras, e o `wlan0` mostrou 44 MB em 20 s de tráfego concorrente.

Portanto: **o cache é mantido por argumento de código, não por medição.** O argumento é forte e
verificável sem aparelho — `baseY`, `amp`, `startY` e `endY` não são função de `t`, logo os cinco
`Brush` eram reconstruídos 30×/s para produzir o objeto idêntico —, mas "faz menos trabalho" e
"custa menos CPU medida" são coisas diferentes, e só a primeira está estabelecida.

## Como fechar esta task

Os dois APKs do A/B estão prontos e a diferença entre eles é **só** o `WaveScratch`; os dois
limitam a 30 fps, então qualquer diferença de CPU é custo por quadro. Repetir com o aparelho
exclusivo:

```bash
# para cada braço: instalar, abrir a biblioteca, deixar assentar 40 s, amostrar 20 s
adb install -r apk_B.apk   # sem cache
adb shell "sleep 40; sh /data/local/tmp/tsample.sh 20"
adb install -r apk_A.apk   # com cache
adb shell "sleep 40; sh /data/local/tmp/tsample.sh 20"
```

**Pré-condições que a rodada invalidada ensinou:** nenhum download em andamento (o painel muda o
layout da tela sob teste), a mesma quantidade de jogos na grade nos dois braços, e conferir por
captura de tela que os dois braços estão na mesma tela antes de comparar número com número.

Critério: o braço com cache abaixo do braço sem cache, na mesma taxa de quadros. Se a diferença for
pequena, a alavanca seguinte é o preenchimento de GPU (`WAVE_LAYERS` de 4 para 2), que é decisão de
produto porque muda o que o usuário vê.

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

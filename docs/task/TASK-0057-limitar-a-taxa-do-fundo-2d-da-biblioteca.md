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

`Number Slow issue draw commands` em **100% dos quadros** aponta para o lado de emitir comandos, não
para preencher pixels. E lendo a cena:

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
- O limite de ~30 fps, **depois** disso e por causa disso — ver "Ordem importa" abaixo.

**NÃO entra:**

- **Reduzir `WAVE_LAYERS` de 4 para 2.** É a alavanca contra o preenchimento de GPU (as bandas se
  sobrepõem em quase toda a tela), mas muda o que o usuário vê. Se os números abaixo não bastarem,
  é a próxima — e é decisão de produto, não de otimização.
- **Desligar o fundo por padrão** ou **pausá-lo sem interação.** Mesmo motivo.
- **O `SaverGlView`** (`FRAME_TARGET_MS = 16L`). Opt-in explícito, e uma simulação de partículas a
  30 fps fica visivelmente pior.

## Ordem importa, e custou uma medição descobrir

O cache sozinho, sem limite de taxa:

| | antes | depois |
|---|---|---|
| `Number Slow issue draw commands` | 1656 (100%) | **3 (0,16%)** |
| Janky frames | 1656 (99,70%) | **3 (0,16%)** |
| GPU p50 | 19 ms | 15 ms |
| quadros desenhados | 30 fps | **60 fps** |
| CPU total | 0,85 núcleo | **1,11 núcleo** |

O custo por quadro caiu tanto que a tela passou a **alcançar** o vsync — e gastou toda a folga
desenhando uma onda lenta com o dobro da frequência. O total **subiu**.

É exatamente aí que o limite de taxa deixa de ser no-op e vira a peça que guarda o ganho em vez de
gastá-lo. Ele voltou, agora com base medida, e com o portão em **25 ms** e não 33: a 60 Hz os
callbacks chegam em 0/16,7/33,3 ms, e um limite de 33 ms senta em cima do terceiro — qualquer
jitter abaixo dele descarta o quadro. Qualquer valor em (16,7 ; 33,3) escolhe o segundo callback de
forma determinística, e continua dando 30 fps a 90 ou 120 Hz.

## Como validar

Com o app aberto **na biblioteca**, parado, sem jogo, e o GOS morto:

```bash
adb shell "sh /data/local/tmp/fps.sh 15"          # quadros realmente desenhados
adb shell "sh /data/local/tmp/tsample.sh 12 'RenderThread|nanodata|hwuiTask|mali-cmar'"
adb shell "dumpsys gfxinfo come.nanodata.armsx2" | grep -E 'Janky|Slow issue|50th'
```

Critério: `Slow issue draw commands` perto de zero (**medido: 100% → 0,16%**), 30 fps desenhados, e
o total por thread abaixo do 0,85 núcleo da linha de base.

> ⏳ **A medição final, com o limite de taxa reintroduzido, está pendente**: o aparelho caiu do ADB
> no meio da bateria de testes. O que está medido é o cache (tabela acima). O esperado do limite é
> metade de 1,11, ou seja ~0,55 núcleo — e é isso que fecha esta task.

**Armadilhas de medição** que custaram uma amostra cada, para quem repetir:

- `/proc/<tid>/stat` traz `comm` entre parênteses e ele **pode conter espaços** (`CPU Thread`), o que
  desloca todo `awk '{print $14+$15}'`. Contar campos a partir do **último** `)`.
- Em `sh`, use `${12}`: `$12` é `$1` seguido de um `2` literal, e a soma dá zero — a thread parece
  ociosa.
- O laço de amostragem gera ~90 processos por snapshot, então a janela real é bem maior que o
  `sleep` pedido. Dividir por `/proc/uptime`, não pelo `sleep`, senão todo percentual sai ~40% alto.

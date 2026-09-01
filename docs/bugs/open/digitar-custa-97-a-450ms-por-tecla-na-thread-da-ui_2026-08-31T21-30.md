# Bug: cada tecla digitada custa de 97 a 450 ms de quadro, na thread da UI

- **Detectado em:** 2026-08-31 21:30 (medição no aparelho, Galaxy A12 `SM-A127M`)
- **Origem:** caminho de digitação da biblioteca — `ui/home/HomeScreen.kt` +
  `ui/home/HomeViewModel.kt::setQuery` + `ui/home/LibraryWaveBackground.kt`
- **Errors (serviço):** nenhum — não lança e não trava; só demora
- **Classe:** fail (desempenho)
- **Reincidência:** primeira vez medido. A [TASK-0062](../../task/TASK-0062-teclado-virtual-toque-fora-e-latencia.md)
  atacou o teclado e reduziu custo real, mas **não** este.
- **Feature:** nenhuma
- **Tasks que o resolvem:** — nenhuma ainda

## Sintoma

Digitar na busca da biblioteca continua lento depois da TASK-0062 (tecla emite na descida) e da
TASK-0063 (fundo parou de animar).

## O que está medido

`gfxinfo` zerado imediatamente antes de digitar, teclas enviadas por `adb shell input tap` a uma
por segundo, APK `githubDebug` vc 38 instalado 2026-08-31 21:16 (contém TASK-0062, 0063 e 0064).

| cenário | quadros | 50º | 90º |
|---|---|---|---|
| Catálogo (12.305 linhas), 8 teclas | 24 | **150 ms** | 450 ms |
| Salvos (7 jogos), 4 teclas | 9 | **97 ms** | 125 ms |

Histograma do caso do catálogo — **nenhum quadro abaixo de 53 ms**:

```
53-65ms: 6    113-133ms: 6    150ms: 4    250-300ms: 4    450ms: 4
```

Decomposição por fase (`framestats`, 14 quadros, mediana / máximo em ms):

| fase | mediana | máximo |
|---|---|---|
| input + recomposição (`AnimationStart`→`PerformTraversalsStart`) | 36,6 | 78,0 |
| medida + layout (`PerformTraversalsStart`→`DrawStart`) | 0,3 | 0,5 |
| **desenho / gravar display list** (`DrawStart`→`SyncQueued`) | 18,8 | **318,7** |
| sync | 0,6 | 1,4 |
| GPU (`IssueDrawCommandsStart`→`SwapBuffers`) | 12,0 | 16,0 |
| apresentar (`SwapBuffers`→`FrameCompleted`) | 31,8 | 51,6 |
| **TOTAL** (`IntendedVsync`→`FrameCompleted`) | **254,8** | 460,1 |

**As fases somam ~100 ms, mas o total mediano é 255 ms.** A diferença — ~155 ms — está *antes* de
`AnimationStart`: quando o vsync chegou, a thread da UI ainda estava ocupada. É fila, não uma fase
cara sozinha: um quadro de desenho de 318 ms empurra os seguintes.

## O que isso corrige do registro anterior

A TASK-0062 concluiu que "o piso de resposta continua sendo o fundo animado", a partir de
`50th percentile: 42ms` medido com o fundo ainda animando. **Estava errado, e o erro é de método:**
aqueles 42 ms eram a mediana de ~280 quadros *baratos* de fundo que diluíam os caros — o 99º
percentil já era 300 ms na mesma amostra, e ninguém olhou. O fundo animado **mascarava** o custo da
digitação nas estatísticas; não era o teto dele.

## Duas causas, e as duas estão medidas como somadas

1. **Um piso de ~97 ms por tecla que não depende do tamanho da biblioteca** — aparece igual com 7
   jogos. Não é o filtro nem a grade.
2. **Um acréscimo que depende do catálogo** — 150 ms de mediana e cauda de 450 ms com 12.305 linhas.

## Hipóteses para o piso, NÃO verificadas

Nenhuma das duas abaixo foi provada; ambas são compatíveis com o pico de 318 ms na fase de desenho:

- **A `HomeScreen` inteira recompõe a cada tecla.** `setQuery` grava `state.copy(query = …)` de
  imediato, deliberadamente ([TASK-0062](../../task/TASK-0062-teclado-virtual-toque-fora-e-latencia.md)
  registrou isso como fora de escopo). Se o `backgroundLayer` recompõe junto, a lambda de desenho do
  fundo é recriada e o nó de desenho é invalidado.
- **A [TASK-0063](../../task/TASK-0063-fundo-da-biblioteca-para-de-animar.md) removeu o `WaveScratch`
  junto com a animação**, com o argumento de que cache não faz sentido para quem desenha uma vez.
  Só que "uma vez" vale enquanto nada invalida o nó. Quando algo invalida — uma tecla —, a cena
  volta a ser reconstruída, agora **sem** o cache que a TASK-0057 mediu como valendo ~3 pontos de
  núcleo. Isto é uma troca possível de custo contínuo por custo por interação, e precisa ser
  medida antes de ser afirmada.

**Como decidir entre elas:** desligar o fundo 2D (escolher uma imagem fixa em Aparência) e repetir a
medição. Se o piso de 97 ms cair, é o fundo; se não cair, é a recomposição da `HomeScreen`.

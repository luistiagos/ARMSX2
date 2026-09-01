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

## As hipóteses abaixo foram testadas no aparelho e as DUAS estão refutadas

Medido em 2026-08-31 21:45, mesmo aparelho e mesmo roteiro.

**Teste 1 — o piso não é a tela da biblioteca.** `ArmsBackdrop` com `backgroundLayer` é usado
**só** pela `HomeScreen` (uma ocorrência em todo o app), então a busca de Configurações abre o
mesmo `LibraryKeyboard` sem onda e sem grade:

| cenário | quadros | 50º | 90º |
|---|---|---|---|
| busca de Configurações (sem onda, sem grade) | 6 | **117 ms** | 250 ms |

Igual ou pior que os 97 ms da biblioteca com 7 jogos. **Nem o fundo nem a grade explicam o piso.**

**Teste 2 — um terço do piso é o próprio grid do teclado.** Tocar a *mesma* tecla não muda
`row`/`col`, e `mutableIntStateOf` não notifica em escrita de valor igual: o `Overlay` não é
invalidado e só o texto recompõe. Tocar teclas *diferentes* invalida o `Overlay` inteiro, com as
~40 `KeyCap`. Os dois braços, na busca de Configurações, 6 toques cada:

| braço | quadros | 50º | 90º |
|---|---|---|---|
| A — sempre a MESMA tecla (grid não invalida) | 8 | **61 ms** | 150 ms |
| B — 6 teclas DIFERENTES (grid invalida) | 8 | **93 ms** | 150 ms |

**Mover o realce de uma tecla custa ~32 ms**: recompor e regravar quarenta `KeyCap` para mudar a
cor de uma. Sobra um piso de ~61 ms mesmo sem isso.

> **Correção de 2026-09-01:** esses ~32 ms vinham de 6 toques numa rodada só. Refeito com 12 toques
> e três rodadas, o custo do realce é **~24 ms**, não 32. A [TASK-0068](../../task/TASK-0068-realce-do-teclado-sem-recompor-o-grid.md)
> derruba ~8 desses (braço B de 85 para 77 ms) e **os outros ~16 continuam sem causa identificada** —
> recompor duas `KeyCap` não explica esse tempo.

## Onde o custo está, ao fim das medições

| parcela | custo | evidência |
|---|---|---|
| realce do teclado (40 `KeyCap` por tecla) | ~32 ms | braço A contra braço B |
| piso residual (texto + o que a tela do host refaz) | ~61 ms | braço A |
| acréscimo do catálogo de 12.305 linhas | +~55 ms na mediana, cauda a 450 ms | catálogo contra Salvos |

## Correção candidata para a parcela do realce

Ler a seleção na **fase de desenho** em vez da de composição — `Modifier.drawBehind` sobre um
`State`, em vez de `selected: Boolean` como parâmetro. Uma leitura de estado em `drawBehind`
invalida só o desenho daquele nó, não a composição. Hoje as quarenta teclas leem `row`/`col`
através do `Overlay`, então todas recompõem para que uma mude de cor. A
[TASK-0062](../../task/TASK-0062-teclado-virtual-toque-fora-e-latencia.md) considerou e descartou
isso como "marginal, ~1 ms" — **estimativa errada por mais de trinta vezes**, e o que a corrigiu foi
medir no aparelho, não reler o código.

## Hipóteses originais, mantidas aqui porque a refutação é o resultado

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

**Este era o teste proposto**, e ele foi executado de forma equivalente e mais barata: em vez de
trocar o fundo por uma imagem fixa (que exigiria o seletor SAF), bastou digitar numa tela que não
desenha o fundo. O piso **não** caiu — logo não é nenhuma das duas.

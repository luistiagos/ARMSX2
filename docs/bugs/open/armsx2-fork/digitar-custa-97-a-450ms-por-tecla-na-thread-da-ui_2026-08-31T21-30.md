# Bug: cada tecla digitada custa de 97 a 450 ms de quadro, na thread da UI

- **Detectado em:** 2026-08-31 21:30 (medição no aparelho, Galaxy A12 `SM-A127M`)
- **Origem:** caminho de digitação da biblioteca — `ui/home/HomeScreen.kt` +
  `ui/home/HomeViewModel.kt::setQuery` + `ui/home/LibraryWaveBackground.kt`
- **Errors (serviço):** nenhum — não lança e não trava; só demora
- **Classe:** fail (desempenho)
- **Complexidade:** alta (investigação profunda de gargalo no ART interpretador/JIT vs AOT em Compose com árvore de nós pesada)
- **Reincidência:** primeira vez medido. A [TASK-0062](../../../task/TASK-0062-teclado-virtual-toque-fora-e-latencia.md)

  atacou o teclado e reduziu custo real, mas **não** este.
- **Feature:** nenhuma
- **Tasks que o resolvem:** nenhuma fecha o relato. Parciais, todas medidas:
  [TASK-0062](../../../task/TASK-0062-teclado-virtual-toque-fora-e-latencia.md) (emitir na descida,
  menos leituras de prefs por tecla),
  [TASK-0068](../../../task/TASK-0068-realce-do-teclado-sem-recompor-o-grid.md) (realce sem
  recompor as 40 teclas, −8 ms) e
  [TASK-0086](../../../task/TASK-0086-eco-da-busca-nao-recompoe-a-biblioteca.md) (o eco da busca
  para de recompor a `HomeScreen`, −20% de CPU na thread da UI por tecla)

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
> e três rodadas, o custo do realce é **~24 ms**, não 32. A [TASK-0068](../../../task/TASK-0068-realce-do-teclado-sem-recompor-o-grid.md)
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
[TASK-0062](../../../task/TASK-0062-teclado-virtual-toque-fora-e-latencia.md) considerou e descartou
isso como "marginal, ~1 ms" — **estimativa errada por mais de trinta vezes**, e o que a corrigiu foi
medir no aparelho, não reler o código.

## Hipóteses originais, mantidas aqui porque a refutação é o resultado

Nenhuma das duas abaixo foi provada; ambas são compatíveis com o pico de 318 ms na fase de desenho:

- **A `HomeScreen` inteira recompõe a cada tecla.** `setQuery` grava `state.copy(query = …)` de
  imediato, deliberadamente ([TASK-0062](../../../task/TASK-0062-teclado-virtual-toque-fora-e-latencia.md)
  registrou isso como fora de escopo). Se o `backgroundLayer` recompõe junto, a lambda de desenho do
  fundo é recriada e o nó de desenho é invalidado.
- **A [TASK-0063](../../../task/TASK-0063-fundo-da-biblioteca-para-de-animar.md) removeu o `WaveScratch`
  junto com a animação**, com o argumento de que cache não faz sentido para quem desenha uma vez.
  Só que "uma vez" vale enquanto nada invalida o nó. Quando algo invalida — uma tecla —, a cena
  volta a ser reconstruída, agora **sem** o cache que a TASK-0057 mediu como valendo ~3 pontos de
  núcleo. Isto é uma troca possível de custo contínuo por custo por interação, e precisa ser
  medida antes de ser afirmada.

**Este era o teste proposto**, e ele foi executado de forma equivalente e mais barata: em vez de
trocar o fundo por uma imagem fixa (que exigiria o seletor SAF), bastou digitar numa tela que não
desenha o fundo. O piso **não** caiu — logo não é nenhuma das duas.

---

# Reperfilamento de 2026-09-05, e ele corrige duas leituras do registro acima

A triagem mandava **reperfilar antes de mexer**. Feito no mesmo Galaxy A12 `SM-A127M` (Android 13,
Mali-G52), `githubDebug` `versionCode 2004`, com dois instrumentos e um braço de controle.

## Protocolo (o mesmo nos três braços, e é ele que torna a comparação válida)

Oito toques, um por segundo, alternando **uma letra e o `⌫`**: `g ⌫ o ⌫ d ⌫ a ⌫`. Alternar mantém a
consulta em 0–1 caractere — onde o filtro menos corta, ou seja, o pior caso — e faz o realce mudar
de tecla a cada toque. `dumpsys gfxinfo <pkg> reset` imediatamente antes; `framestats` depois.

Três rodadas por braço. O catálogo hoje tem **6318** títulos (eram 12.305 quando o relato nasceu),
então os números absolutos abaixo **substituem** os do topo deste arquivo, não os contradizem.

| braço | quadros | quadros/tecla | 50º | 90º |
|---|---|---|---|---|
| busca de **Configurações** (sem grade, sem fundo) | 16 | **2** | 93–97 ms | 133–150 ms |
| **Salvos** (12 jogos) | 24 | **3** | 117–125 ms | 150 ms |
| **Catálogo** (6318 títulos) | 24–28 | **3** | 150 ms | 250–300 ms |

## Correção 1 — "medida + layout: 0,3 ms" está errado; é a maior fase da thread da UI

O Compose faz sua medida e seu layout dentro de `AndroidComposeView.dispatchDraw`, e o `framestats`
contabiliza isso como **desenho**. O que mede 0,3 ms é o `performMeasure`/`performLayout` do
`ViewRootImpl`, que para uma tela Compose não faz quase nada. No `simpleperf`, com a árvore de
chamadas da thread da UI durante a digitação no Catálogo:

| símbolo (inclusivo) | % da thread da UI |
|---|---|
| `Choreographer.doFrame` | 87,9 |
| `ViewRootImpl.performTraversals` → `dispatchDraw` | 51,2 |
| **`AndroidComposeView.measureAndLayout`** (dentro do `dispatchDraw`) | **42,5** |
| `AndroidUiDispatcher…doFrame` (recomposição) | 35,8 |
| `Recomposer.performRecompose` | 27,8 |
| `LazyGridMeasureKt.measureLazyGrid` | 31,6 |
| `CompositionImpl.composeInitial` | 15,1 |

A fase que o relato chamava de "gravar display list" — a que tinha o pico de 318 ms — é, em maioria,
**medida e layout do Compose**, e dentro dela a `LazyVerticalGrid` recompondo do zero os itens
visíveis (`composeInitial` 15%) porque a busca trocou os jogos que estão na tela.

E o teclado, que as TASK-0062 e TASK-0068 atacaram, já é a menor parcela do quadro:
`LibraryKeyboard.KeyCap` responde por **0,91%** da thread da UI, contra 10,7% de
`HomeScreenKt.GameGridCard`. O host é quem custa.

## Correção 2 — a hipótese "a `HomeScreen` inteira recompõe" nunca foi testada, e agora está sustentada

O relato dá as duas hipóteses originais por refutadas com o argumento *"bastou digitar numa tela que
não desenha o fundo; o piso não caiu — logo não é nenhuma das duas"*. Aquele teste refutou o **fundo
e a grade**. Ele não podia testar a primeira hipótese, porque digitar em Configurações **não passa
por `setQuery` nem pela `HomeScreen`**.

Contando os quadros, a diferença aparece: **a biblioteca gasta três quadros por tecla e
Configurações gasta dois.** Linha do tempo do `framestats`, uma tecla:

```
Catálogo:
  t=   0 ms  total=123  recomposicao=40,6  desenho= 19,3   <- eco: state.copy(query=...)
  t= 282 ms  total=278  recomposicao=63,5  desenho=166,6   <- resultado: buildState (debounce 100 ms)
  t= 315 ms  total=275  recomposicao= 7,0  desenho=  0,3   <- quadro vazio

Configurações:
  t=   0 ms  total= 89  recomposicao=28,7  desenho= 20,1   <- texto E resultados no MESMO quadro
  t=  33 ms  total= 72  recomposicao= 0,0  desenho=  0,3   <- quadro vazio
```

O quadro do meio existe porque `HomeViewModel.setQuery` gravava `state.copy(query = value)` de
imediato e a `HomeScreen` lê esse estado no **topo** (`val state = viewModel.state.value`) — a tela
inteira recompunha para atualizar uma string. `SettingsSearch.query` é um `mutableStateOf` lido só
dentro do overlay, e por isso o outro host não tem esse quadro.

## O que a [TASK-0086](../../../task/TASK-0086-eco-da-busca-nao-recompoe-a-biblioteca.md) mediu

O eco passou a viver em `HomeViewModel.liveQuery`, lido dentro do `item { }` da grade. A/B com 24
teclas por braço, APKs em sequência no mesmo aparelho, **com a busca de Configurações como controle**
(código não tocado):

| mediana por quadro da tecla (ms) | Catálogo antes | depois | Salvos antes | depois | **Cfg antes** | **depois** |
|---|---|---|---|---|---|---|
| eco — **recomposição** | 36,5 | **17,3** | 33,7 | **16,6** | 31,0 | 29,4 |
| eco — total | 114,3 | **92,9** | 109,0 | **86,5** | 114,3 | 111,5 |
| resultado — total | 169,7 | 188,2 | 135,2 | 137,9 | 88,3 | 88,2 |
| **soma de quadros por tecla** | 507 | 509 | 415 | **390** | 203 | 201 |

CPU da thread da UI no mesmo roteiro (`simpleperf`, `cpu-clock` 1 kHz): **1982 → 1582 amostras**, ou
seja **248 → 198 ms por tecla (−20%)**. `HomeScreenKt.HomeScreen` cai de 48 para 25 amostras e
`HomeBottomBar` de 68 para 40 — as duas passam a executar uma vez por tecla em vez de duas.

**O controle não se moveu** (114,3 → 111,5; 203 → 201 ms/tecla), o que garante que o aparelho não
derivou entre as medições.

**E os percentis do `gfxinfo` não se moveram:** Catálogo 150 ms nos dois lados, Salvos 117–125 →
117–121. Os ~21 ms tirados do quadro do eco reaparecem no quadro do resultado. A thread da UI está
**saturada** — 198 ms de CPU por tecla para um orçamento de 16,7 ms —, então redistribuir trabalho
entre quadros não muda o que o usuário sente. Só a soma por tecla muda, e ela precisa cair de ~500 ms
para dezenas.

## Um confundidor grande, e ele vale mais que qualquer micro-otimização

**Todo este relato foi medido em `githubDebug`, e o ART se recusa a compilar um pacote `debuggable`.**
Verificado no aparelho:

```
dumpsys package come.nanodata.armsx2  ->  arm64: [status=run-from-apk]
cmd package compile -m speed -f come.nanodata.armsx2  ->  "Success", e o status vira [status=verify]
```

Não é limitação do comando: o `PackageDexOptimizer` força o filtro de *safe mode* para todo pacote
`debuggable`, e o comentário do AOSP diz o porquê — *"the runtime ignores their compiled code"*. Ou
seja, **não existe** APK `debuggable` com código AOT neste aparelho; separar as duas variáveis exige
instalar um APK não-`debuggable`.

O que isso custa, medido: **44,8% da CPU da thread da UI durante a digitação é o interpretador do
ART** — `NterpGetMethod`, `ExecuteNterpImpl`, `NterpGetInstanceFieldOffset`, `NterpGetShorty`,
`Class::FindClassMethod`, `Class::FindInterfaceMethod` e os `nterp_op_*`. É resolução de método e de
campo, não código nosso. O `libart.so` responde por 50% das amostras da thread da UI e o
`[JIT app cache]` por 42%.

A [TASK-0058](../../../task/TASK-0058-medir-release-contra-debug.md) já mediu que `githubRelease`
tira 19% do trabalho da EE em jogo, e já registrou o caminho seguro para instalar um release ao lado
sem desinstalar nada (`-Parmsx2.applicationId=come.nanodata.armsx2.perf`, semear só o necessário,
desinstalar o pacote de teste no fim). **Aplicar esse mesmo caminho aqui é o próximo passo mais
barato deste relato**, e o braço da busca de **Configurações** é o ideal para ele: é o único que não
precisa de ROM, de catálogo nem de dado nenhum do usuário.

## A pista da TASK-0079 respondida: o salto do boot NÃO é proporcional ao catálogo

O `Choreographer: Skipped 104 frames!` do primeiro desenho reproduz, e o `Davey` que o acompanha diz
onde ele está:

```
duration=1893ms   IntendedVsync->HandleInputStart=1,7   ->PerformTraversalsStart=0,30
                  PerformTraversalsStart->DrawStart = 1805 ms
```

1,8 s dentro de medida e layout — a **composição inicial** da tela da biblioteca, na thread da UI.

Trocando a aba inicial e reiniciando o app com tudo o mais igual:

| aba inicial | jogos na lista | `Choreographer: Skipped` | primeiro `Davey` |
|---|---|---|---|
| Catálogo | 6318 | **103 quadros** | 1893 ms |
| Salvos | 12 | **104 quadros** | 1890 ms |

**Idêntico.** O salto do boot é **plano** em relação ao tamanho da biblioteca, o que faz sentido: a
grade é `Lazy` e compõe ~12 células nos dois casos. Ele é o mesmo *território* (composição do Compose
na thread da UI, num aparelho fraco, sem código AOT) mas **não** é a parcela "proporcional ao
catálogo" deste relato. Não fundir os dois.

## O que continua sem prova

- **Por que o quadro do resultado do Catálogo absorve os 21 ms** que saíram do quadro do eco
  (169,7 → 188,2), enquanto o de Salvos não absorve (135,2 → 137,9). Suspeita: a fila da thread da
  UI, que está saturada. Não medido.
- **O quadro vazio** que fecha cada tecla nos dois hosts (recomposição ~5 ms, desenho 0,3 ms) —
  custa uma vaga de quadro por tecla e não tem causa identificada. A suspeita é um `LaunchedEffect`
  cuja chave muda por tecla agendando um callback no `AndroidUiDispatcher`; não foi provado.
- **De onde vem exatamente o acréscimo do catálogo** (Salvos 390 → Catálogo 509 ms/tecla). O que se
  sabe: `buildState` roda em `Dispatchers.Default` e custa **~91 ms de CPU por tecla** com 6318
  títulos (19% em `GameInfo.sortKey` → `CustomNames.nameFor` → `SharedPreferences.getString`, 9% em
  `HiddenGames.isHidden`), contra praticamente zero com 12 jogos. Não está provado que essa CPU de
  fundo é o que aparece na thread da UI — o caminho plausível é GC (`HeapTaskDaemon` a ~5%) e
  disputa de núcleo, e nenhum dos dois foi isolado.
- **Quanto disso sobrevive num APK de release.** Ver o confundidor acima. Enquanto não for medido,
  todo número deste relato é o piso do que o cliente vê, não o que ele vê.

## Estado do aparelho durante e depois desta sessão

Registrado porque a medição mexeu no aparelho e tudo foi devolvido:

- `cmd package compile -m speed -f` foi executado para testar a hipótese do AOT; parou em
  `verify` e a reinstalação do APK devolveu o estado a `run-from-apk`. Conferido.
- Um teste de toque inválido caiu na linha **Idioma** de Configurações e trocou `ui.language` de
  `system` para `it`. **Devolvido para `system`** (app parado, XML editado em Python, `run-as` de
  volta) e conferido na tela: a interface voltou ao português do sistema.
- `ui.home.currentTab` voltou a `Saved`, que era o valor de origem.
- `config.game.SLUS-20751` (`{"renderer":"vulkan","upscaleFloat":1.25}`) e
  `library.background.animated2d=true` **não foram tocados** — conferidos ao fim.
- Nenhum arquivo criado em `files/gamesettings`; os arquivos de perfil deixados em
  `/data/local/tmp` foram apagados.

> **O que desbloqueia esta medição:** a [TASK-0088](../../../task/TASK-0088-medir-sem-o-interpretador-do-art.md)
> acrescentou `-Parmsx2.debug.debuggable=false`, que produz um APK de debug **não-`debuggable`** —
> assinado com a chave de debug, então instala neste aparelho sem desinstalar nada. É o que tira o
> interpretador do ART do caminho e permite refazer os números sem esse confundidor.

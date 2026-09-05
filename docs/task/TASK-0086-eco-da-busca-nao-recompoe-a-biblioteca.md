# TASK-0086: o eco da busca deixa de recompor a biblioteca inteira

- **Status:** em andamento
- **Criada em:** 2026-09-04
- **Concluída em:** —
- **Feature:** nenhuma
- **Bugs que resolve:** [digitar-custa-97-a-450ms-por-tecla-na-thread-da-ui](../bugs/open/armsx2-fork/digitar-custa-97-a-450ms-por-tecla-na-thread-da-ui_2026-08-31T21-30.md)
  (uma das parcelas; ver "NÃO entra")
- **Commit:** — (o vínculo é o prefixo `TASK-0086:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## Contexto: o reperfilamento que a triagem pediu

A triagem mandava **reperfilar antes de mexer**. Feito em 2026-09-04 no Galaxy A12 `SM-A127M`
(Android 13), `githubDebug` `versionCode 2004`, com dois instrumentos: `dumpsys gfxinfo framestats`
(decomposição por fase) e `simpleperf record --app` (onde a CPU vai de fato).

**Protocolo, igual nos três braços:** 8 toques, um por segundo, alternando uma letra e o
`⌫` — `g ⌫ o ⌫ d ⌫ a ⌫`. Alternar mantém a consulta em 0–1 caractere, que é onde o filtro menos
corta (pior caso), e faz o realce mudar de tecla a cada toque. `gfxinfo reset` imediatamente antes.

| braço | quadros | quadros/tecla | 50º | 90º |
|---|---|---|---|---|
| busca de **Configurações** (sem grade, sem fundo) | 16 | **2** | 93–97 ms | 150 ms |
| **Salvos** (12 jogos) | 24 | **3** | 117–125 ms | 150 ms |
| **Catálogo** (6318 títulos) | 24–28 | **3** | 150 ms | 250–300 ms |

Três rodadas por braço, estáveis ao milissegundo de percentil.

### O achado que decide esta task: a biblioteca gasta TRÊS quadros por tecla, Configurações gasta DOIS

A linha do tempo de `framestats` (cada quadro, quando começou e o que fez):

```
Catálogo, uma tecla:
  t=   0 ms  total=123  recomposicao=40,6  desenho= 19,3   <- eco: state.copy(query=...)
  t= 282 ms  total=278  recomposicao=63,5  desenho=166,6   <- resultado: buildState com debounce
  t= 315 ms  total=275  recomposicao= 7,0  desenho=  0,3   <- quadro vazio

Configurações, uma tecla:
  t=   0 ms  total= 89  recomposicao=28,7  desenho= 20,1   <- texto E resultados no MESMO quadro
  t=  33 ms  total= 72  recomposicao= 0,0  desenho=  0,3   <- quadro vazio
```

O quadro do meio da biblioteca **não existe** em Configurações. A diferença entre os dois hosts não
é a grade nem o fundo: é que `SettingsSearch.query` é um `mutableStateOf` lido **só dentro do
overlay**, enquanto `HomeViewModel.state` é um estado único lido no **topo** da `HomeScreen`
(`val state = viewModel.state.value`, linha 154). `setQuery` grava `state.copy(query = value)` de
imediato para ecoar o texto no campo de busca, e essa gravação recompõe a `HomeScreen` inteira —
2200 linhas de estrutura, mais tudo que recebe lambda dela.

O `simpleperf` confirma que o corpo da `HomeScreen` **executa** durante a digitação (ele não
apareceria no perfil se não executasse), e mostra o que ele arrasta junto:

| símbolo (inclusivo, thread da UI, braço do Catálogo) | % |
|---|---|
| `HomeScreenKt$HomeScreen$...$inlined$itemsIndexed$...` (conteúdo da grade) | 11,15 |
| `HomeScreenKt.GameGridCard` | 10,70 |
| `HomeScreenKt.HomeScreen$lambda$148$lambda$147$lambda$146` (lambda de conteúdo do `LazyVerticalGrid`) | 5,40 |
| `HomeScreenKt.HomeBottomBar` | 3,43 |
| `HomeScreenKt.HomeScreen` (o corpo) | 2,42 |
| `ArmsComponentsKt.ArmsTopBar` | 1,01 |
| **`LibraryKeyboard.KeyCap`** | **0,91** |

O teclado — o que as TASK-0062 e TASK-0068 atacaram — já é a menor parcela do quadro. O host é
quem custa.

### Duas correções ao registro do relatório

1. **"medida + layout: 0,3 ms" está errado, e o erro é de leitura do instrumento.** O Compose faz
   sua medida e seu layout dentro de `AndroidComposeView.dispatchDraw`, que o `framestats`
   contabiliza como **desenho**. No perfil, `measureAndLayout` é **42,5%** da thread da UI e
   `ViewRootImpl.performMeasure/performLayout` é o que mede 0,3 ms. A fase cara chamada "gravar
   display list" é, em maioria, medida e layout do Compose.
2. **O teste que o relatório deu como refutando a hipótese "a `HomeScreen` inteira recompõe" não a
   testou.** Digitar na busca de Configurações não passa por `setQuery` nem pela `HomeScreen`; o que
   aquele teste refutou foi o fundo e a grade, não isto. Medido agora com quadros contados, a
   hipótese fica **sustentada**: um quadro inteiro por tecla existe na biblioteca e não existe no
   host sem `HomeUiState`.

## Escopo

**Entra:**

- `HomeViewModel.liveQuery` — um `mutableStateOf<String>` novo, só para o **eco**: o texto que o
  campo de busca mostra enquanto se digita. `setQuery` passa a gravá-lo em vez de gravar
  `state.copy(query = …)` de imediato.
- `HomeUiState.query` passa a ser a consulta **aplicada** — a que gerou `visibleGames` —, escrita
  uma vez só, junto com o resultado de `buildState`. Nenhum campo novo, nenhum removido.
- `HomeScreen`: o `SearchField` lê `viewModel.liveQuery.value` **dentro do `item { }}` do
  `LazyVerticalGrid`**, que é um escopo reiniciável próprio (`ComposableLambdaImpl` abre
  `startRestartGroup`), então o eco invalida esse item e nada mais. As três outras leituras de
  `query` que não estão em composição (semear o teclado ao abrir, e o `setQuery("")` de quando a
  barra some) passam a usar `liveQuery`, que é o texto de verdade — hoje elas podem ler um valor
  até 100 ms atrasado.
- `setTab` zera `liveQuery` junto com `query`, que é o único outro lugar que limpa a busca.

**NÃO entra:**

- **O quadro do resultado.** `visibleGames` muda de verdade a cada tecla, a `LazyVerticalGrid`
  precisa recompor os itens visíveis, e isso é o trabalho, não desperdício. É o quadro de
  63–195 ms de "desenho" na tabela acima e ele continua.
- **O quadro vazio** (recomposição 0,0 ms, desenho 0,3 ms) que fecha cada tecla nos **dois** hosts.
  Ele custa uma vaga de quadro por tecla e não tem causa identificada — a suspeita é um
  `LaunchedEffect` cuja chave muda por tecla agendando um callback no `AndroidUiDispatcher`, mas
  isso **não** foi provado e não se conserta às cegas.
- **O custo de `buildState`** (`Dispatchers.Default`, ~91 ms de CPU por tecla com 6318 títulos, dos
  quais 19% em `sortKey` e 9% em `HiddenGames.isHidden`). É proporcional ao catálogo, roda fora da
  thread da UI, e memorizar a chave de ordenação é outra task.
- **O eco em si.** Continua imediato e continua visível: o que muda é o custo, não o comportamento.
- **Trocar o `HomeUiState` por estados separados.** Seria a correção de raiz e é uma refatoração de
  um arquivo de 2200 linhas; esta task tira só a gravação que acontece **por tecla**.

## Como validar

1. **Comportamento idêntico:** digitar no Catálogo e em Salvos — o campo de busca ecoa cada
   caractere na hora, a lista filtra, fechar e reabrir o teclado traz o texto digitado, trocar de
   aba limpa a busca.
2. **A/B no aparelho**, mesmo protocolo dos três braços acima, mesmo A12, APKs em sequência. O
   previsto é o braço do Catálogo cair de **3 para 2 quadros por tecla** e a mediana cair junto. Se
   o número de quadros não cair, a hipótese está errada e a task não se justifica.
3. `:app:compileGithubDebugKotlin` e `:app:testGithubDebugUnitTest`.

## Resultado medido, e ele contradiz metade da previsão

A/B no A12 `SM-A127M`, `githubDebug`, APKs em sequência no mesmo aparelho e na mesma sessão, **24
teclas por braço** (três rodadas de oito), com a busca de Configurações como **braço de controle** —
ela não passa por `HomeUiState` e o código dela não foi tocado.

### 1. Comportamento: passou

Digitado `g`,`o`,`d` no Catálogo: o campo ecoa "god", a lista filtra para 22 títulos, o realce vai
para a tecla certa. `Pronto` e reabrir o teclado: "god" continua lá (agora vindo de `liveQuery`, que
é o valor certo — antes vinha de `state.query`, que podia estar 100 ms atrasado). Trocar para Salvos:
a busca limpa e a lista volta aos 12. `:app:compileGithubDebugKotlin` e
`:app:testGithubDebugUnitTest` verdes.

### 2. Quadros por tecla: a previsão estava ERRADA

Continua em **3** no Catálogo e em Salvos, e em **2** em Configurações. Os percentis do `gfxinfo`
não se mexeram: Catálogo 150 ms nos dois lados, Salvos 117–125 → 117–121, Configurações 93–97 → 93.

O raciocínio que gerou a previsão confundiu duas coisas. O quadro a mais **não** existe porque a
gravação é larga; existe porque há um **debounce de 100 ms** entre o eco e o resultado. O caractere
tem de aparecer na hora (quadro 1) e a lista chega depois (quadro 2) — nenhuma economia de
recomposição junta os dois.

### 3. O que mudou de verdade: o quadro do eco custa metade

Mediana por posição na rajada, 24 teclas de cada lado (ms):

| | Catálogo antes | Catálogo depois | Salvos antes | Salvos depois | **Configurações antes** | **depois** |
|---|---|---|---|---|---|---|
| eco — **recomposição** | 36,5 | **17,3** | 33,7 | **16,6** | 31,0 | 29,4 |
| eco — desenho | 19,0 | 14,5 | 15,5 | 11,5 | 39,3 | 40,2 |
| eco — total | 114,3 | **92,9** | 109,0 | **86,5** | 114,3 | 111,5 |
| resultado — total | 169,7 | 188,2 | 135,2 | 137,9 | 88,3 | 88,2 |
| vazio — total | 178,4 | 189,1 | 139,7 | 136,7 | — | — |
| **soma de quadros por tecla** | 507 | 509 | 415 | **390** | 203 | 201 |

**O braço de controle não se moveu em nenhuma coluna** (114,3 → 111,5; 203 → 201 ms/tecla), o que
diz que o aparelho não derivou entre as medições e que o protocolo repete.

**A recomposição do quadro do eco caiu pela metade nos dois braços da biblioteca** (36,5 → 17,3 e
33,7 → 16,6). É o mecanismo previsto, confirmado: metade daquele quadro era a `HomeScreen` inteira
sendo reexecutada para trocar uma string.

### 4. CPU da thread da UI: −20%

`simpleperf record --app`, mesmo roteiro de 8 teclas, `cpu-clock` a 1 kHz:

| | antes | depois |
|---|---|---|
| amostras na thread da UI | 1982 | **1582** |
| **CPU da thread da UI por tecla** | 248 ms | **198 ms** |
| `HomeScreenKt.HomeScreen` (corpo, inclusivo) | 48 amostras | **25** |
| `HomeScreenKt.HomeBottomBar` | 68 | **40** |
| `ArmsComponentsKt.SearchField` | 12 | 14 |

`HomeScreen` e `HomeBottomBar` caem pela metade em valor absoluto — exatamente o previsto: passam a
executar **uma** vez por tecla (o resultado) em vez de duas (o eco e o resultado). O `SearchField`
fica igual, que é o ponto: ele continua ecoando.

### 5. O que NÃO melhorou, e é o resultado honesto

**O total por tecla no Catálogo não se mexeu: 507 → 509 ms.** Os ~21 ms tirados do quadro do eco
reaparecem no quadro do resultado (169,7 → 188,2). Em Salvos a mesma economia sobrevive (415 → 390,
−6%), porque lá o quadro do resultado é menor e não absorve a folga.

Por que o Catálogo absorve não está provado. O que dá para afirmar: a thread da UI está **saturada**
— 198 ms de CPU por tecla para um orçamento de 16,7 ms — então tirar trabalho de um quadro adianta o
começo do seguinte, e o gargalo continua sendo o total, não a distribuição. Enquanto a soma por
tecla não cair de 500 para dezenas, o percentil que o usuário sente não muda.

**A task é mantida** porque o ganho é medido (−20% de CPU na thread da UI, recomposição do eco pela
metade), é de mecanismo confirmado, não muda comportamento e ainda corrige uma leitura atrasada. Não
é mantida como tendo resolvido o relato — não resolveu.

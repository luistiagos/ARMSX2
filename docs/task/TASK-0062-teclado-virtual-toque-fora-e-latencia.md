# TASK-0062: o toque fora do teclado nunca fechou, e cada tecla resortava a biblioteca inteira

- **Status:** em andamento
- **Criada em:** 2026-08-31
- **Concluída em:** —
- **Feature:** nenhuma
- **Bugs que resolve:** `teclado-virtual-scrim-de-tamanho-zero`
- **Commit:** — (o vínculo é o prefixo `TASK-0062:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## Contexto

Dois pedidos sobre o mesmo teclado (`LibraryKeyboard`, o grid de teclas que serve busca da
biblioteca, renome, campos de rede e cartão de memória):

1. **tocar fora não fecha** — e o pedido já tinha sido feito antes;
2. **digitar é lento.**

### 1. O véu existe no código e mede 0 × 0

O fechamento por toque fora **foi implementado**. Está em `LibraryKeyboard.Overlay`:

```kotlin
AnimatedVisibility(visible = isVisible, enter = fadeIn(...), exit = fadeOut(...)) {
    Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.5f))
        .clickable(...) { close() })
}
```

`matchParentSize()` é **parent data**, e só a `MeasurePolicy` do `Box` a lê. Dentro de um
`AnimatedVisibility` o pai imediato não é um `Box`: é um `Layout` com
`AnimatedEnterExitMeasurePolicy`. Desmontado o `animation.aar` da versão em uso (1.11.4), o
`measure` dela é:

```
measure-BRTryo0(J)   <- mede cada filho com as constraints que chegaram
Placeable.getWidth / Math.max
Placeable.getHeight / Math.max
```

Nenhuma leitura de `parentData`, nenhum `BoxChildDataNode`. A parent data é **ignorada em
silêncio**. O `Box` do véu não tem filho nem modificador de tamanho, chega com `min = 0`, e
portanto mede **0 × 0**: não escurece nada e não tem área de toque. Compila, roda, e a
funcionalidade simplesmente não existe.

### 2. A digitação ressorteia a biblioteca a cada tecla

`HomeViewModel.buildState` ordena com

```kotlin
filtered.sortedBy { it.sortKey(forceEn).lowercase() }
```

`sortedBy` monta um `Comparator` que chama o seletor **em toda comparação**, para os dois lados —
O(n log n) chamadas, não O(n). E `sortKey` não é um getter de campo:

```kotlin
fun sortKey(forceEn: Boolean) = when {
    !CustomNames.nameFor(settingsKey).isNullOrBlank() -> CustomNames.nameFor(settingsKey)!!   // 2×
    ...
}
```

`CustomNames.nameFor` → `MainActivityRuntime.prefs.getString(KEY_PREFIX + key, null)`, ou seja
**uma leitura de `SharedPreferences` (sincronizada, e o mesmo lock que a UI toma para desenhar cada
capa) mais uma concatenação de string**, duas vezes por chamada. `settingsKey` também é um getter
com `substringAfterLast`/`substringBeforeLast`.

Com o catálogo em 12.305 linhas e a busca ainda curta (é onde o filtro quase não corta), uma tecla
custa da ordem de **3 × 10⁵ leituras de prefs, 3 × 10⁵ concatenações e 1,6 × 10⁵
`lowercase()`** — em `Dispatchers.Default`, mas disputando o mesmo lock de prefs e o mesmo GC da
thread que desenha, num aparelho que já gasta ~0,9 núcleo só com o fundo animado
([TASK-0057](TASK-0057-limitar-a-taxa-do-fundo-2d-da-biblioteca.md)).

Junto disso, `buildState` chama `repository.recentGames(allGames)`, que faz um
`associateBy { it.uri.toString() }` sobre **todos** os jogos — um `HashMap` de 12 mil entradas
reconstruído a cada tecla, sem que nada dos recentes dependa da busca.

## Escopo

**Entra:**

- `LibraryKeyboard.kt`
  - o apanhador de toque: `matchParentSize()` → `fillMaxSize()`, e fora do `AnimatedVisibility`
    (dentro dele ele sobreviveria aos 90 ms da animação de saída, e o toque que fechou o teclado
    seria seguido de um segundo toque morto). O porquê fica registrado no código, para ninguém
    "simplificar" de volta;
  - **e ele fica transparente, sem os 50% de preto que o código pedia.** Aquele escurecimento
    nunca chegou a existir — é o mesmo `Box` de tamanho zero —, então repô-lo não seria conserto,
    seria mudança de aparência em todas as telas que usam o teclado. Pior: os dois hosts que
    entregam a digitação a ele (a busca de configurações e o `PadModal` que nomeia um preset)
    desenham o próprio véu sob o próprio painel, e este host é composto **acima** dos dois — um
    véu aqui escureceria justamente a lista que o usuário está filtrando enquanto digita. Quem
    quer o ambiente escuro escurece por conta própria, como a busca de configurações já faz;
  - **a tecla emite na descida do dedo, não na subida.** `Modifier.clickable` dispara no
    *release*: cada caractere esperava o tempo que o dedo ficasse pousado — 60 a 120 ms de tempo
    humano, mais um quadro — antes que *qualquer coisa* acontecesse na tela, inclusive o realce.
    É a maior parcela do "está lento" e é exatamente por isso que o IME do sistema parece
    instantâneo: teclado de Android comita no *down*. Não há o que desambiguar aqui — o painel não
    rola e as teclas não arrastam. Feito com `detectTapGestures(onPress = …)`, que consome o
    *down*, mais um bloco `semantics` que devolve o papel de botão e a ação de clique que o
    `clickable` dava ao TalkBack;
  - o texto digitado sai para um `@Composable` próprio, para que uma tecla invalide só a linha do
    texto e não as ~40 teclas do grid.
- `GameInfo.sortKey` — uma chamada a `CustomNames.nameFor` em vez de duas.
- `HomeViewModel.buildState`
  - decorate–sort–undecorate: a chave de ordenação é calculada **uma vez por jogo** (n chamadas em
    vez de O(n log n));
  - índice `uri → jogo` dos recentes memorizado pela identidade de `allGames`.
- `GameLibraryRepository.recentUris()` — a lista crua de URIs, para o índice acima. `recentGames`
  continua existindo com o mesmo contrato.

**NÃO entra:**

- **Pausar o fundo animado enquanto o teclado está aberto.** É a maior alavanca de quadro que
  sobra (~0,9 núcleo), e é decisão de produto — a TASK-0057 já registrou "pausá-lo sem interação"
  como fora de escopo pelo mesmo motivo.
- **Tirar o eco imediato da busca** (`state.copy(query = …)` a cada tecla, que recompõe a
  `HomeScreen` inteira). É o que mantém a barra de busca em dia com o que se digita, foi escolha
  deliberada de quem escreveu `setQuery`, e mexer nisso muda comportamento visível.
- Trocar o teclado pelo IME do sistema. Já existe como opção (`ui.useSystemKeyboard`).

## Como validar

0. **A tecla responde na descida:** manter o dedo pousado numa letra — o caractere entra e o
   realce pula para ela **antes** de soltar. Arrastar para fora depois disso não desfaz nada (é o
   comportamento de qualquer IME).
1. **Toque fora fecha:** abrir a busca da biblioteca, tocar acima do painel → o teclado fecha e a
   consulta digitada permanece (mesmo caminho de `DONE` e de `BACK`). Repetir com a opção "usar o
   teclado do sistema" ligada.
2. **Nada muda de aparência:** com o teclado aberto, o fundo continua com o mesmo brilho de hoje —
   em especial a lista de resultados da busca de configurações, que fica acima do painel.
3. **O grid não pisca por tecla:** com o inspetor de recomposição, digitar uma letra invalida a
   linha do texto, não as teclas.
4. **Ordenação idêntica:** a lista com busca vazia, em cada um dos três critérios, sai na mesma
   ordem de antes (a chave é a mesma; só passou a ser calculada uma vez por item).
5. **`:app:compileGithubDebugKotlin`** — feito, `BUILD SUCCESSFUL`, com o `checkNoWindowModals`
   passando.

## Verificado no aparelho (2026-08-31, Galaxy A12 `SM_A127M`, `githubDebug` vc 38)

APK reconstruído (`assembleGithubDebug`, 1 min 50 s) e instalado por cima do que já estava — mesma
assinatura de debug, mesmo `versionCode`, `adb install -r` → `Success`.

| o quê | como foi provado |
|---|---|
| **toque fora fecha** | teclado aberto na aba Salvos, `input tap 360 480` — em cima da capa de God of War II. O teclado fechou **e o jogo não foi lançado**: o apanhador consumiu o toque. |
| **nada escureceu** | captura antes/depois: o fundo e a grade seguem com o mesmo brilho. |
| **a tecla emite na descida** | `adb shell input motionevent DOWN 361 1317` **sem o `UP`**: com o dedo ainda pousado, o `g` entrou (`god` → `godg`), a barra de busca ecoou e a lista refiltrou. Antes, nada acontecia até soltar. |
| **a busca do catálogo funciona** | digitar `g`,`o`,`d` no Catálogo filtrou para 22 títulos, incluindo linhas só-de-catálogo. |
| **sem regressão de runtime** | `logcat` sem `FATAL` nem exceção do pacote durante toda a sessão. |

### O que a medição de quadros diz, e o que ela NÃO diz

`gfxinfo` zerado antes de digitar, lido depois de três teclas no Catálogo:

```
Total frames rendered: 291
Janky frames: 289 (99.31%)
50th percentile: 42ms   90th: 48ms   95th: 77ms   99th: 300ms
```

**Isto não mede esta task.** É o mesmo teto da [TASK-0057](TASK-0057-limitar-a-taxa-do-fundo-2d-da-biblioteca.md):
a tela da biblioteca custa ~38 ms por quadro parada, e com o painel do teclado por cima vai a 42 ms.
O aparelho entrega um quadro a cada 42 ms **faça o teclado o que fizer** — então o ganho de emitir
no *down* (um toque humano inteiro, 60–120 ms) e o de cortar as leituras de prefs por tecla são
reais, mas o piso de resposta continua sendo o fundo animado. Enquanto a decisão de produto da
TASK-0057 não for tomada, é este piso que sobra.

**Não medido:** o custo de `buildState` antes/depois. O APK anterior foi sobrescrito pelo novo, não
há A/B, e sem os dois braços o número não prova nada — o ganho de ordenação segue sendo raciocínio
sobre o código (n chamadas em vez de O(n log n), cada uma com duas leituras de `SharedPreferences`),
não medição.

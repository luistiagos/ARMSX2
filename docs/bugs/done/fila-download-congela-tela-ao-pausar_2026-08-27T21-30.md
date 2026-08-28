# Bug: pausar na tela de Downloads congela o app sob um véu escuro

- **Detectado em:** 2026-08-27 21:30 (durante a validação da [TASK-0040](../../task/TASK-0040-fila-de-download-em-tela-propria.md))
- **Origem:** `ui/catalog/DownloadQueueSection.kt` (o botão primário da linha da fila), introduzido pela
  [TASK-0038](../../task/TASK-0038-fila-de-download-visivel.md)
- **Errors (serviço):** nenhum — não é crash; o processo continua vivo, só não responde
- **Classe:** loop de recomposição (Compose)
- **Reincidência:** primeira vez
- **Feature:** [FEAT-0001](../../features/FEAT-0001-sync-upstream-oficial.md)
- **Tasks que o resolvem:** [TASK-0040](../../task/TASK-0040-fila-de-download-em-tela-propria.md)

## Sintoma

Tocar **pausar** na tela de Downloads. A transferência para de verdade (o `.part` congela no mesmo
tamanho em leituras sucessivas), mas a tela:

- escurece, como se houvesse um modal por cima, sem modal nenhum;
- não atualiza o status para `Pausado` — fica no último quadro, com o `⏸` e a porcentagem antiga;
- não responde a toque nem a BACK.

No logcat, um GC a cada ~250 ms:

```
I/nanodata.armsx2: NativeAlloc concurrent copying GC freed 722492(41MB) AllocSpace objects, ... total 152.353ms
I/nanodata.armsx2: Background concurrent copying GC freed 457325(12MB) ... total 211.999ms
I/nanodata.armsx2: Background concurrent copying GC freed 531465(13MB) ... total 208.480ms
I/Choreographer: Skipped 34 frames!  The application may be doing too much work on its main thread.
I/OpenGLRenderer: Davey! duration=874ms
```

## Causa raiz

A linha da fila desenhava o botão primário em **dois ramos de `when`** — um com `⏸`, outro com `▶` —
e os dois passavam o **mesmo `controllerId`**:

```kotlin
when (item.state) {
    DOWNLOADING -> RoundAction(glyph = "⏸", …, controllerId = "home.queue.$file.action")
    PAUSED, ERROR -> RoundAction(glyph = "▶", …, controllerId = "home.queue.$file.action")
    else -> Unit
}
```

Ao pausar, o estado muda e o Compose troca de ramo: são dois pontos de composição distintos, então
o antigo é **destruído** e o novo **criado**. `controllerFocusable` registra no `onDispose` e no
`SideEffect`:

```kotlin
SideEffect { SettingsControllerNav.register(id, …) }
DisposableEffect(controllerId) { onDispose { SettingsControllerNav.unregister(controllerId) } }
```

E as duas operações **escrevem estado que a composição lê** (`SettingsWidgets.kt`):

```kotlin
fun register(id, …)   { registry[id] = …; if (selectedId.value == id) selectedIndex.intValue = orderedIds().indexOf(id) }
fun unregister(id)    { registry.remove(id); if (selectedId.value == id) selectedId.value = orderedIds().firstOrNull()
                        selectedIndex.intValue = orderedIds().indexOf(selectedId.value) }
```

`selectedId`/`selectedIndex` são lidos durante a composição, para desenhar o anel de foco. Logo:
desregistrar escreve → invalida → recompõe → `SideEffect` registra → escreve → invalida → **sem fim**.

**Por que só nesta tela.** A mesma seção rodou minutos na biblioteca, na TASK-0038, sem congelar: lá
o foco do controle vivia na grade ou na barra, nunca num botão da fila, e a guarda
`if (selectedId.value == id)` fazia de `unregister` um no-op. Na tela de Downloads os botões da fila
são quase os únicos controles, o foco pousa num deles, e a guarda passa a valer.

## A correção

Um único ponto de composição, com os parâmetros mudando:

```kotlin
val pausable = item.state == DOWNLOADING
val resumable = item.state == PAUSED || item.state == ERROR
if (pausable || resumable) {
    RoundAction(
        glyph = if (pausable) "⏸" else "▶",
        description = if (pausable) str("catalog.action.pause") else str("catalog.action.resume"),
        onClick = { if (pausable) onPause(item.fileName) else onResume(item.fileName) },
        controllerId = "home.queue.${item.fileName}.action",
    )
}
```

A identidade do slot — e portanto o registro do `controllerId` — sobrevive à troca de estado.

## Validação (device físico)

SM-A127M (Android 13), APK `github/release`:

| Antes | Depois |
|---|---|
| Pausar → tela escura, travada, surda a toque e BACK | Pausar → status `Pausado`, botão `▶`, tela viva |
| ~4 GCs de 12–41 MB por segundo | **0 GCs em 12 s** de download correndo |
| Retomar impossível (tela morta) | Retomar volta do mesmo ponto (216 MB → 246 MB) |

## Lição

Dois ramos de `when` que desenham "o mesmo botão" com rótulos diferentes **não são** o mesmo botão
para o Compose. Quando esse botão carrega identidade registrada num índice global — foco de
controle, âncora de rolagem, o que for — a troca de ramo destrói e recria essa identidade. Se o
registro escreve estado lido na composição, isso fecha um ciclo. Um ponto de composição, parâmetros
variáveis.

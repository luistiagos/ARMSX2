# TASK-0073: o lançamento externo entrega `file://` cru ao core, e o jogo não boota

- **Status:** concluída
- **Criada em:** 2026-09-02
- **Concluída em:** 2026-09-02
- **Feature:** —
- **Bugs que resolve:** [intent-view-externo-abre-o-app-e-nao-boota-o-jogo](../bugs/open/intent-view-externo-abre-o-app-e-nao-boota-o-jogo_2026-09-01T21-35.md)
- **Commit:** — (o vínculo é o prefixo `TASK-0073:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## De onde vem

O bug foi registrado ontem com a causa **não** identificada e um próximo passo sugerido:
instrumentar três pontos do caminho. Ao ir fazer isso, a instrumentação **já existia** — o
`launchGame` imprime `@@ANDROID_LAUNCH_GAME@@` em toda chamada, com a URI. Bastava ler o log que já
estava capturado.

## O defeito

`launchGame` **é chamado**. O que está errado é o argumento:

| caminho | `uri=` registrado no `@@ANDROID_LAUNCH_GAME@@` |
|---|---|
| pela grade | `/storage/emulated/0/…/007 - Everything or Nothing (USA).chd` |
| por intent externo | `file:///storage/emulated/0/…/007%20-%20Everything%20or%20Nothing%20%28USA%29.chd` |

O core espera o **caminho nu** para `file://`, e recebe a URI inteira, ainda percent-encoded. Não
abre, e não reclama — daí o sintoma de "abre a biblioteca e não faz nada".

A conversão existe e está repetida em **quatro** call sites, sempre no mesmo idioma:

```kotlin
val launchPath = if (game.uri.scheme == "file") game.uri.path ?: game.uri.toString()
                 else game.uri.toString()
```

- [`HomeViewModel.kt:337`](../../platforms/android/app/src/main/java/com/armsx2/ui/home/HomeViewModel.kt#L337)
- [`MainActivityRuntime.kt:1450`](../../platforms/android/app/src/main/java/com/armsx2/runtime/MainActivityRuntime.kt#L1450) (`launchGameFromSaveSlot`)
- [`HomeShortcuts.kt:45`](../../platforms/android/app/src/main/java/com/armsx2/HomeShortcuts.kt#L45), que ainda documenta o porquê: *"Raw `file://` games hand the core the bare /storage path; SAF games pass the `content://` URI string."*
- [`SettingsScreen.kt:121`](../../platforms/android/app/src/main/java/com/armsx2/ui/settingshub/SettingsScreen.kt#L121), cujo comentário descreve o sintoma **palavra por palavra**, três linhas acima:
  > *"bare filesystem path, not `file:///…`, or the native boot rejects the path and kicks straight
  > back to the library (the flash-then-library symptom)."*

  Ou seja: isto não é dedução a partir do log. O defeito já era conhecido e já foi corrigido quatro
  vezes; o caminho externo foi simplesmente esquecido nas quatro.

**O caminho externo é o único dos cinco que não a tem** — `launchPendingExternalGameIfReady`
([linha 1060](../../platforms/android/app/src/main/java/com/armsx2/runtime/MainActivityRuntime.kt#L1060))
passa `queued` direto, e `queued` veio de `pendingExternalLaunch.value = uri.toString()`.

### O alcance é maior do que o sintoma relatado

`resolveCueToTrack` — o mapeamento de `.cue` para a faixa, escrito exatamente para
Cocoon/Daijisho/ES-DE — termina em `siblingOf`, que constrói o resultado com
`java.io.File(parent, fileName).absolutePath.toUri()`, ou seja **`file://`**. Então o recurso
inteiro cai no mesmo defeito: resolve o cue corretamente e entrega ao core uma URI que ele não abre.

## O que entra

Uma conversão, no único ponto que despacha o lançamento externo
(`launchPendingExternalGameIfReady`), com o mesmo idioma dos outros quatro call sites: `file://`
vira `uri.path`; `content://` e caminho sem esquema seguem como estão.

`externalGameInfo` continua recebendo a **string original**, para que `GameInfo.uri` fique sendo a
URI `file://` de verdade — igual ao que a biblioteca guarda. Só o argumento de lançamento muda.

## O que NÃO entra

- **Não** unifico as cinco cópias do idioma num helper. Seria a correção "certa", mas mexe em
  quatro arquivos e no caminho de boot que funciona hoje, para consertar um que não funciona. Fica
  registrado como dívida, não como escopo.
- **Não** normalizo dentro do `launchGame`. Ele recebe `String`, não `Uri`; farejar prefixo `file://`
  ali esconderia o contrato em vez de cumpri-lo, e mudaria o comportamento dos quatro chamadores que
  já estão corretos.
- **Não** investigo se é regressão do merge. O defeito não depende dos 72 commits: o idioma faltante
  está no lado externo desde que ele existe.

## Como foi validado

1. `:app:compileGithubDebugKotlin` com `-Pkotlin.incremental=false`. ✅ BUILD SUCCESSFUL.
2. **O mesmo `am start` que falhou**, no A12, com APK novo. ✅ passou nos dois backends:

| | `uri=` no `@@ANDROID_LAUNCH_GAME@@` | o jogo apareceu? |
|---|---|---|
| **antes** (01/09) | `file:///…/007%20-%20Everything%20or%20Nothing%20%28USA%29.chd` | não — ficava na biblioteca |
| **depois**, OpenGL (`renderer=12`) | `/storage/…/007 - Everything or Nothing (USA).chd` | VM subiu — FMV, 40,5 fps, quadro 1226 |
| **depois**, Vulkan (`renderer=14`) | `/storage/…/007 - Everything or Nothing (USA).chd` | **sim** — logo da MGM Interactive na tela |

   A conferência visual foi feita em **Vulkan de propósito**: em OpenGL este título cai na tela
   preta do [outro bug](../bugs/open/gl-mali-g52-r38-tela-preta-contornada-nao-corrigida_2026-08-31T19-00.md)
   logo depois do FMV, e a captura preta não distinguiria "não bootou" de "bootou e a tela é preta".
   No lado GL a prova é o log: a VM subiu e produziu quadros, o que antes não acontecia.
3. **Boot pela grade continua funcionando.** ✅ `@@ANDROID_LAUNCH_GAME@@` com o mesmo caminho nu, e
   a tela renderizando. Era esperado por construção — a grade chama `launchGame` direto e nunca
   entra em `launchPendingExternalGameIfReady` — mas foi verificado no aparelho, não deduzido.

## Nota de processo

Duas tentativas de APK foram descartadas antes da que valeu, e a primeira quase virou teste falso:
o Gradle morreu com `Gradle build daemon has been stopped` e o `adb install` da mesma linha rodou
assim mesmo, instalando o APK **anterior à correção**. Daí em diante a instalação passou a ser
guardada por `if [ $RC -eq 0 ]`. A segunda tentativa caiu num link de `demangler_test` sem
`libdemanglegnu.a` — corrida no grafo do ninja, não código quebrado: o arquivo existia ao final da
própria corrida, e a re-execução passou.

# TASK-0079: tirar da thread da UI a carga do `.so` e a resolução do data root no boot

- **Status:** em andamento
- **Criada em:** 2026-09-03
- **Concluída em:** —
- **Feature:** nenhuma
- **Bugs que resolve:**
  [app-anr-loadlibrary-emucore-ui-thread](../bugs/open/armsx2-fork/app-anr-loadlibrary-emucore-ui-thread_2026-08-20T20-15.md),
  [datadirectorymanager-anr-getexternalfilesdir-a07](../bugs/open/armsx2-fork/datadirectorymanager-anr-getexternalfilesdir-a07_2026-08-20T14-17.md)
- **Commit:** — (o vínculo é o prefixo `TASK-0079:` no assunto)
- **Revertida por:** —
- **Publicado em:** —

## Objetivo

`kickoffEmucoreInit()` nasce de `onCreate`/`LaunchedEffect` e faz **todo o trabalho pesado do boot
na thread que a chamou** antes de despachar qualquer coisa ao worker. Depois desta task, o único
trabalho que sobra na UI é ligar o latch; tudo o mais acontece no `eScope`.

## O que está na UI hoje, medido no código

Tudo isto está **antes** do `invoke { }` em [`MainActivityRuntime.kt:1973`](../../platforms/android/app/src/main/java/com/armsx2/runtime/MainActivityRuntime.kt#L1973):

| Linha | O que faz | Por que dói |
|---|---|---|
| `lastInitDataRoot = assetCopyRoot(...)` | `validateSystemDirWritable` (`mkdirs` + `createNewFile` + `delete`) e `getExternalFilesDir(null)` | é literalmente o stack do ANR do A07: `getExternalFilesDir` → `ensureExternalDirsExistOrFilter` → `mkdirs` |
| `ConfigStore.reconcileReusedFolder()` e as três migrações | leitura/escrita de `.ini` e prefs | I/O de arquivo |
| `NativeApp.setAutoRendererGpuStrings(...)` | **primeiro toque em `NativeApp`** | dispara o `static {}` da classe → `System.loadLibrary("emucore_*")`. É o stack do ANR do OnePlus 8 Pro |
| `GpuInfo.glStrings()` | cria display/contexto/pbuffer EGL e lê `GL_VENDOR/RENDERER/VERSION` | inicialização de driver gráfico |
| `copyAssetAll(..., "bios")` e `(..., "resources")` | copia a árvore de assets para o data root | I/O pesado, e o comentário três linhas abaixo já diz que a cópia do BIOS "must not block first paint / risk an ANR on slow SD cards" |
| limpeza do cache de shader no update | `deleteRecursively` | I/O pesado |
| migração do BIOS para o interno | `copyFileViaTemp` | I/O pesado |

Os dois relatórios são **o mesmo caminho de execução**, por isso uma task só.

## Escopo

**Entra:**

- **O corpo de `kickoffEmucoreInit` passa inteiro para dentro do `invoke { }`**, na mesma ordem.
  Fora dele fica só o latch `emucoreInitDone`, que é o que impede despacho duplo. A ordem importa e
  é preservada: `setAutoRendererGpuStrings` continua antes de `initializeOnce`, `reconcileReusedFolder`
  continua antes de o core reescrever o `.ini`, e a migração do BIOS continua antes do pin.
  `eScope` é executor de **uma thread só**, então "mesma ordem" continua significando o mesmo.
- **`assetCopyRoot()` passa a memoizar**, com a chave sendo `systemDir.value`. É o que o relatório do
  A07 pede em "Próximos passos": resolver uma vez, publicar o resultado, e os callers de UI leem o
  caminho já resolvido. Hoje cada chamada refaz `mkdirs` + `createNewFile` + `delete`, e há **nove
  callers**, vários em caminho de tela (`GameInfo.coversRoot`, `MemoryCardBackup`, `OverlayRepo`,
  `RaLibrary`, `CoverRegionIndex`, `BackupManager`, `Armsx2DocumentsProvider`).

**NÃO entra:**

- Reescrever o portão de boot / splash. O gate por estado (`nativeReady`) já existe e funciona.
- Mudar `NativeApp` — o `static {}` continua chamando `System.loadLibrary`. O que muda é **quem o
  toca primeiro**, que é o que decide a thread onde a carga acontece.
- Os outros `getExternalFilesDir` do app (`Pasx2Application` logs, `GameLibraryRepository`,
  `inputProfilesDir`, `hostfsDir`). Não estão neste caminho de boot; se aparecerem em telemetria,
  viram outra task.

## Como validar

Em aparelho, `githubRelease`:

1. `adb shell am force-stop come.nanodata.armsx2 && adb logcat -c`, abrir o app frio e confirmar que
   `PCSX2_LOAD` e `PCSX2_INIT` saem numa thread que **não** é a main.
2. Nenhum ANR e nenhum aviso de `StrictMode`/`Choreographer` "Skipped N frames" acima do normal no
   arranque.
3. O app boota, a biblioteca aparece e um jogo roda — a prova de que a reordenação não quebrou a
   sequência que `initializeOnce` exige.

## Resultado

Preenchido ao concluir.

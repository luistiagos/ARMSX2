# TASK-0079: tirar da thread da UI a carga do `.so` e a resolução do data root no boot

- **Status:** concluída
- **Criada em:** 2026-09-03
- **Concluída em:** 2026-09-03
- **Feature:** nenhuma
- **Bugs que resolve:**
  [app-anr-loadlibrary-emucore-ui-thread](../bugs/done/app-anr-loadlibrary-emucore-ui-thread_2026-08-20T20-15.md),
  [datadirectorymanager-anr-getexternalfilesdir-a07](../bugs/done/datadirectorymanager-anr-getexternalfilesdir-a07_2026-08-20T14-17.md)
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

Validado em aparelho. **E a primeira medição derrubou a primeira versão da correção.**

### O que a medição mostrou, e o escopo não previa

Com o corpo de `kickoffEmucoreInit` já inteiro no worker, o log do arranque continuou assim:

```
09-03 19:18:28.978 12722 12722 I System.out: PCSX2_LOAD emucore_4k pageSize=4096
09-03 19:18:29.743 12722 12782 I System.out: PCSX2_INIT
```

`PCSX2_INIT` no worker (tid 12782), mas **`PCSX2_LOAD` com tid igual ao pid — a thread principal**.
Havia dois toques em `NativeApp` **antes** de `kickoffEmucoreInit`, ambos no `onCreate`:

- `NativeApp.sRumbleEnabled = ControllerMappings.rumbleEnabled()` — escrever em campo estático
  **inicializa a classe**, o que é suficiente para disparar o `static {}` e o `System.loadLibrary`.
  Era o mais cedo dos dois, e o mais fácil de não ver: parece uma atribuição, não uma chamada.
- `NativeApp.setAdpfEnabled(...)`, cujo comentário **já dizia** *"Referencing NativeApp also loads
  the native lib (static init)"* — e a linha estava na thread da UI mesmo assim.

Os dois, mais `syncHapticIntensity()` e `syncSoundVolume()` (que escrevem em `sHapticScale` e no
gate de som), foram para `seedNativeGates()`, chamada como primeira coisa do worker. A garantia que
o comentário original pedia — "antes de um jogo rodar" — continua valendo: nada disso pode ser
exercitado antes de a biblioteca existir, e a biblioteca só aparece depois deste worker.

### Medição depois da correção completa

moto g86 5G, Android 16 (SDK 36), `arm64-v8a`, `github/release` `versionCode 2000`:

```
09-03 19:26:37.045 16382 16441 I System.out: PCSX2_LOAD emucore_4k pageSize=4096
09-03 19:26:37.476 16382 16441 I ARMSX2  : @@ANGLE@@ off renderer=opengl ...
09-03 19:26:37.516 16382 16441 I System.out: PCSX2_INIT
```

pid **16382**, tudo na tid **16441**. A carga do `.so` saiu da thread da UI.

- `ANR in come.nanodata.armsx2` no logcat: **0**.
- Nenhum `Choreographer: Skipped` atribuído ao nosso pid.
- O app abre, o catálogo aparece com 6313 títulos e as capas carregam — a prova de que a reordenação
  não quebrou a sequência que `initializeOnce` exige.

### O que fica registrado como não medido

O ANR original do A07 (`getExternalFilesDir` → `mkdirs`) depende de armazenamento externo lento sob
pressão; num moto g86 com armazenamento rápido ele não reproduzia nem antes. O que se prova aqui é
que **a chamada saiu da thread da UI**, que é a causa. A confirmação de campo é telemetria limpa
para `armsx2/anr` nessa assinatura.

### Confirmado também no Galaxy A12, e um achado que não é desta task

O A12 `SM-A127M` (Android 13, SDK 33) é o aparelho fraco, onde ANR de boot de fato acontece. Medido
nele, com `githubDebug`:

| | `1.0.24` instalado (antes) | esta branch (depois) |
|---|---|---|
| `PCSX2_LOAD` | pid 10786, tid **10786** — a thread principal | pid 12558, tid **12631** — worker |
| `ANR in come.nanodata.armsx2` | — | **0** |

**E um `Choreographer: Skipped 104 frames!` continua saindo na thread principal do nosso processo**,
cerca de 2 s depois do `PCSX2_LOAD`, já no primeiro desenho do catálogo de 6317 títulos.

Isso **não** é regressão nem sobra desta correção: a carga do `.so` e a resolução do data root saíram
da UI, e é o que esta task prometia. O que aquele salto mostra é outro custo, no caminho do
catálogo — o mesmo território do relato
[digitar-custa-97-a-450ms](../bugs/open/armsx2-fork/digitar-custa-97-a-450ms-por-tecla-na-thread-da-ui_2026-08-31T21-30.md),
que segue aberto. Fica registrado aqui para não ser confundido com o que esta task fechou.

### E confirmado em RELEASE, com o R8 ligado — 2026-09-06

As medições anteriores foram em `githubDebug`. O `CLAUDE.md` avisa que **o R8 está ligado no
release** e que tudo alcançado por nome precisa de regra de `keep` — uma falha assim só aparece em
runtime, num build de release. Então a correção foi refeita nesse regime.

moto g86 5G, `githubRelease` 2.0.5, `pkgFlags` **sem** `DEBUGGABLE`:

```
09-06 01:18:31.319 22004 22039 I System.out: PCSX2_LOAD emucore_4k pageSize=4096
09-06 01:18:31.684 22004 22039 I System.out: PCSX2_INIT
```

pid **22004**, tudo na tid **22039**. A carga do `.so` continua fora da thread da UI depois do R8, e
o arranque não produziu **nenhum** ANR nem exceção fatal — o `seedNativeGates` e o corpo movido para
o worker sobreviveram ao encolhimento.

**O que não foi verificado nesta passada:** o campo `GPU` do `PerfLog`
([TASK-0085](TASK-0085-tempo-de-gpu-do-gl-usa-os-entry-points-da-extensao.md)) e o rótulo do degrau
do ANGLE ([TASK-0087](TASK-0087-angle-entra-na-escada-de-recuperacao.md)) em release. Os dois exigem
um jogo aberto, e o aparelho entrou em **bloqueio de tela seguro** antes disso — sem PIN nem
biometria não há como seguir. Fica para a próxima sessão com o aparelho destravado.

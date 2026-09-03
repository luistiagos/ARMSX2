# Bug: A07 — `getExternalFilesDir()` cria diretórios na UI thread e causa ANR

- **Detectado em:** 2026-08-20 14:17 (telemetria de produção)
- **Origem:** telemetria `armsx2/anr` (`UnixFileSystem.java::createDirectory0`)
- **Errors (serviço):** 1510, 1514, 1574, 1577 (4 ocorrências)
- **Classe:** fail (ANR)
- **Reincidência:** quatro execuções no Samsung `SM-A075M`, app 1.0.16
- **Tasks que o resolvem:** [TASK-0079](../../task/TASK-0079-boot-nao-toca-o-nativo-nem-o-disco-na-ui.md)

## Sintoma

A main thread fica mais de 5 segundos dentro da criação dos diretórios externos do Android:

```text
java.io.UnixFileSystem.createDirectory0
java.io.File.mkdirs
android.app.ContextImpl.ensureExternalDirsExistOrFilter
android.app.ContextImpl.getExternalFilesDir
DataDirectoryManager.getDefaultDataRoot(DataDirectoryManager.java:70)
```

Os caminhos de entrada observados foram `MainActivity.onCreate`/`copyAssetAll` (1510 e 1574),
`SettingsActivity.updateDataDirSummary` (1514) e `HomeActivity.onCreate` (1577).

## Causa raiz

[`DataDirectoryManager.getDefaultDataRoot`](../../../app/src/main/java/kr/co/iefriends/pcsx2/utils/DataDirectoryManager.java#L70)
chama `Context.getExternalFilesDir(null)` sincronamente. Esse método pode entrar em
`ensureExternalDirsExistOrFilter()` e fazer I/O/mkdir. Vários callers executam em `onCreate`, na
main thread, inclusive [`HomeActivity.java:98`](../../../app/src/main/java/kr/co/iefriends/pcsx2/activities/HomeActivity.java#L98)
e [`SettingsActivity.java:2873`](../../../app/src/main/java/kr/co/iefriends/pcsx2/activities/SettingsActivity.java#L2873).

A mudança recente de `copyAssetAll` para `startAssetCopyAsync` remove o I/O do asset do caminho
síncrono de `MainActivity`, mas não elimina as demais resoluções do data root na UI.

## Como reproduzir

No Galaxy A07, iniciar o processo com o armazenamento externo lento/ocupado e abrir Home,
MainActivity ou Configurações. O watchdog captura a main thread em `createDirectory0`.

## Próximos passos

Resolver e preparar o diretório em worker durante o splash, publicar o resultado imutável em cache
e só liberar as Activities depois. Callers de UI devem ler o caminho já resolvido, sem executar
`getExternalFilesDir()` ou `mkdirs()`.

## Correção implementada — 2026-08-22

`App` agora aquece o data root padrão, o root efetivo, `roms` e o diretório de capas no worker
`ARMSX2-NativeInit`. `BootSplashActivity` só libera Home/Main depois desse worker. O
`DataDirectoryManager` mantém caches sincronizados do root padrão e do efetivo, invalidados quando
o usuário muda a pasta. Assim, os callers de UI apenas leem o `File` já resolvido.

`assembleUnrestrictedDebug` passou. Aguardando reteste no A07/telemetria limpa.

## Auditoria no ARMSX2-fork — 2026-09-03

A classe `DataDirectoryManager` da ocorrência original não existe nesta árvore, mas a mesma chamada
de risco reapareceu. [`kickoffEmucoreInit`](../../../platforms/android/app/src/main/java/com/armsx2/runtime/MainActivityRuntime.kt#L1973)
resolve `lastInitDataRoot = assetCopyRoot(applicationContext)` antes do despacho ao worker, e
[`assetCopyRoot`](../../../platforms/android/app/src/main/java/com/armsx2/runtime/MainActivityRuntime.kt#L1742)
chama `getExternalFilesDir(null)`. Assim, mudar o nome da classe não removeu o I/O/mkdir potencial
da thread da UI.

Este relatório fica entre os bugs do fork atual por equivalência de causa confirmada no código,
embora ainda não haja nova ocorrência de telemetria atribuída a esta versão. Severidade **alta**;
correção possível resolvendo e cacheando o diretório no worker antes de liberar a inicialização.

## Correção no fork — 2026-09-03 ([TASK-0079](../../task/TASK-0079-boot-nao-toca-o-nativo-nem-o-disco-na-ui.md))

Os "próximos passos" deste relato pediam duas coisas: resolver o diretório em worker, e os callers
de UI lerem o caminho já resolvido. As duas entraram.

**1. `lastInitDataRoot = assetCopyRoot(...)` saiu da thread da UI.** Era a primeira linha de
`kickoffEmucoreInit`, que nasce de `onCreate`. O corpo inteiro da função passou para o `invoke { }`.

**2. `assetCopyRoot()` passou a memoizar**, com chave `systemDir.value` — a invalidação acontece
sozinha quando o usuário troca a pasta, sem depender de alguém lembrar de limpar cache em cada um
dos pontos que escrevem a preferência.

Isso importa mais do que parece. Uma resolução custa `validateSystemDirWritable` (`mkdirs` +
`createNewFile` + `delete`) **mais**, no caminho de fallback, o `getExternalFilesDir(null)` que este
relato acusa. E havia **nove chamadores**, vários em caminho de tela: capas (`GameInfo.coversRoot`),
memory cards, overlays, catálogo (`RaLibrary`, `CoverRegionIndex`), backup e o
`Armsx2DocumentsProvider`. Cada um refazia a sonda de escrita.

De quebra, a raiz ficou **estável durante o processo**, o que é mais correto que antes: com a sonda
refeita a cada chamada, um cartão SD que desmontasse no meio da sessão mudava a raiz por baixo e
espalhava arquivos por duas pastas.

### O que foi medido, e o que não foi

Medido em moto g86 5G (Android 16, SDK 36, `github/release`): o boot inteiro sai da thread da UI —
`PCSX2_LOAD` e `PCSX2_INIT` na tid 16441 com pid 16382 —, `ANR in come.nanodata.armsx2` = 0, e o
catálogo carrega.

**Não medido:** o ANR original depende de armazenamento externo lento sob pressão, e não reproduz num
aparelho com armazenamento rápido — não reproduzia nem antes da correção. O que está provado é que a
chamada saiu da thread da UI, que é a causa. A confirmação de campo é telemetria limpa para
`armsx2/anr` nesta assinatura.

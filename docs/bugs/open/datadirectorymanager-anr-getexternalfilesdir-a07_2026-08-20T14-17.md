# Bug: A07 — `getExternalFilesDir()` cria diretórios na UI thread e causa ANR

- **Detectado em:** 2026-08-20 14:17 (telemetria de produção)
- **Origem:** telemetria `armsx2/anr` (`UnixFileSystem.java::createDirectory0`)
- **Errors (serviço):** 1510, 1514, 1574, 1577 (4 ocorrências)
- **Classe:** fail (ANR)
- **Reincidência:** quatro execuções no Samsung `SM-A075M`, app 1.0.16

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

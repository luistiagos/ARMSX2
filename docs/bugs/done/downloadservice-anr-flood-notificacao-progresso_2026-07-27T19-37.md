# Bug: ANR — `DownloadForegroundService` reconstrói e reposta a notificação a cada callback de progresso

- **Detectado em:** 2026-07-21 → 2026-07-27 19:37 (telemetria de produção)
- **Origem:** telemetria `armsx2/anr` (`BinderProxy.java::android.os.BinderProxy.transactNative`)
- **Errors (serviço):** 685, 570, 560 (**3 ocorrências**)
- **Classe:** fail (ANR — `main thread unresponsive >5000ms`)
- **Reincidência:** 3 execuções; Android 14 e 16, `app_version 1.0.8`

## Sintoma

ANR com a main thread parada numa transação binder disparada pelo update de notificação de download:

```
at android.os.BinderProxy.transactNative(Native Method)
...
at kr.co.iefriends.pcsx2.catalog.DownloadForegroundService.notify(DownloadForegroundService.java:106)
at kr.co.iefriends.pcsx2.catalog.DownloadForegroundService.onProgress(DownloadForegroundService.java:99)
at kr.co.iefriends.pcsx2.catalog.DownloadQueueManager.lambda$notifyProgress$1(DownloadQueueManager.java:202)
at android.os.Handler.handleCallback(...)
```

## Causa raiz (CONFIRMADA no código)

Cada callback de progresso do download vira um `NotificationManager.notify()` completo, **postado na
main thread**, sem nenhum throttle.

[`DownloadQueueManager.notifyProgress`](../../../app/src/main/java/kr/co/iefriends/pcsx2/catalog/DownloadQueueManager.java#L200)
empurra todo progresso para o main looper:
```java
private void notifyProgress(CatalogEntry entry) {
    mainHandler.post(() -> {
        for (QueueListener l : new ArrayList<>(listeners)) l.onProgress(entry);
    });
}
```

[`DownloadForegroundService`](../../../app/src/main/java/kr/co/iefriends/pcsx2/catalog/DownloadForegroundService.java#L98)
responde reconstruindo a notificação inteira e repostando:
```java
@Override
public void onProgress(CatalogEntry entry) {
    notify(entry.title, (int) (entry.downloadProgress * 100));   // :99
}

private void notify(String contentText, int progress) {
    NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(contentText, progress));  // :106
}
```

`buildNotification` refaz o `PendingIntent` e o `NotificationCompat.Builder` do zero a cada chamada,
e `nm.notify()` é uma transação binder síncrona para o `NotificationManagerService`. Num download
rápido o callback dispara dezenas de vezes por segundo: o binder do processo satura, as transações
começam a enfileirar e a main thread fica presa em `transactNative` além dos 5 s.

Detalhe agravante: `onProgress` recebe o progresso já como `int` de 0–100, então a esmagadora
maioria dos posts **não muda nada visível** na notificação — é trabalho puro de binder desperdiçado.

## Como reproduzir

1. Home → Catálogo → baixar uma ROM grande numa conexão rápida (ou com o arquivo em cache local).
2. Manter o app em primeiro plano durante o download.
3. Com progresso subindo rápido, a UI engasga; em rede/CPU ruins passa dos 5 s e vira ANR.

## Próximos passos

1. **Throttle no `notify`**: só repostar quando o inteiro de porcentagem mudar **e** tiver passado um
   mínimo de tempo (ex.: 500 ms–1 s) desde o último post. É o fix mínimo e resolve o caso.
2. Reutilizar o `NotificationCompat.Builder` e o `PendingIntent` entre updates em vez de reconstruir
   tudo em `buildNotification`.
3. Considerar não postar todo progresso no main looper em `DownloadQueueManager.notifyProgress` —
   coalescer no produtor, já que os listeners de UI também não precisam dessa frequência.

## Resolução (CONFIRMADA e corrigida — 2026-08-19)

1. **Throttling e Deduplicação no `notify` (`DownloadForegroundService.java`):**
   Implementado controle temporal (`MIN_NOTIFY_INTERVAL_MS = 500`) e comparação de estado (`lastProgress`, `lastTitle`). O serviço agora só emite transações binder para o `NotificationManagerService` quando o percentual/título realmente mudou e respeitou o intervalo mínimo de 500ms. Transições de estado da fila (início, pausa, finalização) forçam a atualização imediata (`force = true`).
2. **Reutilização de `NotificationCompat.Builder` e `PendingIntent`:**
   O builder e o intent de toque agora são instanciados uma única vez no `onCreate`/`initNotificationBuilder()` e reutilizados a cada atualização de progresso (`mBuilder.setContentText` / `mBuilder.setProgress`), eliminando a alocação e recriação de objetos por callback.

Status: **Corrigido no código local (2026-08-19).**

---

> **Rastreabilidade:** `sem-task-legado` — bug fechado antes de o sistema de tasks existir (ver [`docs/README.md`](../../README.md)). Não há task associada.

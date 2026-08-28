package com.armsx2.catalog;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.armsx2.R;
import com.armsx2.Main;
import com.armsx2.i18n.I18n;

/**
 * Foreground service that keeps downloads alive when the screen locks.
 * Hooks into DownloadQueueManager as a QueueListener — no download logic here.
 * Auto-stops when the queue empties.
 */
public class DownloadForegroundService extends Service implements DownloadQueueManager.QueueListener {

    private static final String TAG = "DownloadForegroundSvc";
    private static final String CHANNEL_ID    = "rom_download_channel";
    private static final int    NOTIFICATION_ID = 1001;

    public static void start(Context ctx) {
        Intent intent = new Intent(ctx, DownloadForegroundService.class);
        try {
            ContextCompat.startForegroundService(ctx, intent);
        } catch (IllegalStateException | SecurityException e) {
            Log.w(TAG, "Unable to start download foreground service; continuing in app process.", e);
        }
    }

    // -------------------------------------------------------------------------

    private NotificationCompat.Builder mBuilder;
    private PendingIntent mPendingIntent;
    private String mLastTitle = "";
    private int mLastProgress = -2;
    private long mLastNotifyTime = 0;
    private static final long MIN_NOTIFY_INTERVAL_MS = 500;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        initNotificationBuilder();
        DownloadQueueManager.get().addListener(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification =
                buildNotification(I18n.INSTANCE.get("catalog.queue.notification.starting"), -1);
        try {
            startForeground(NOTIFICATION_ID, notification);
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to promote download service to foreground.", e);
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (DownloadQueueManager.get().getActiveQueue().isEmpty()) {
            stopForeground(true);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        DownloadQueueManager.get().removeListener(this);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    // -------------------------------------------------------------------------
    // QueueListener

    @Override
    public void onQueueChanged() {
        if (DownloadQueueManager.get().getActiveQueue().isEmpty()) {
            stopForeground(true);
            stopSelf();
            return;
        }
        // Update notification immediately to reflect current state (paused, queued, etc.)
        for (CatalogEntry e : DownloadQueueManager.get().getActiveQueue()) {
            if (e.queueState == DownloadQueueManager.State.DOWNLOADING) {
                notify(e.title, (int) (e.downloadProgress * 100), true);
                return;
            }
        }
        notify(I18n.INSTANCE.get("catalog.queue.notification.queued"), -1, true);
    }

    @Override
    public void onProgress(CatalogEntry entry) {
        notify(entry.title, (int) (entry.downloadProgress * 100), false);
    }

    // -------------------------------------------------------------------------

    private void notify(String contentText, int progress, boolean force) {
        if (contentText == null) contentText = "";
        long now = System.currentTimeMillis();

        if (!force) {
            if (progress == mLastProgress && contentText.equals(mLastTitle)) {
                return;
            }
            if (now - mLastNotifyTime < MIN_NOTIFY_INTERVAL_MS && progress >= 0 && progress < 100 && contentText.equals(mLastTitle)) {
                return;
            }
        }

        mLastTitle = contentText;
        mLastProgress = progress;
        mLastNotifyTime = now;

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(contentText, progress));
        }
    }

    private void initNotificationBuilder() {
        Intent tapIntent = new Intent(this, Main.class);
        tapIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        mPendingIntent = PendingIntent.getActivity(
                this, 0, tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        mBuilder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_download)
                // De `R.string.app_name`, e não de uma literal: UM lugar decide o nome do produto
                // (TASK-0017). Enquanto a notificação era invisível — a permissão nunca foi pedida
                // — isto dizia "ARMSX2 — Download", o nome do upstream, e ninguém via.
                .setContentTitle(getString(R.string.app_name))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setContentIntent(mPendingIntent);
    }

    private Notification buildNotification(String contentText, int progress) {
        if (mBuilder == null) {
            initNotificationBuilder();
        }

        mBuilder.setContentText(contentText);
        if (progress >= 0) {
            mBuilder.setProgress(100, progress, false);
        } else {
            mBuilder.setProgress(0, 0, true); // indeterminate while resolving URL
        }

        return mBuilder.build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Nome e descrição saem do i18n. Os dois são regraváveis: chamar
            // createNotificationChannel de novo com o mesmo id atualiza ambos (só a importância é
            // que fica presa), então quem já tem o app instalado também vê o texto corrigido.
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    I18n.INSTANCE.get("catalog.queue.notification.channel"),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(I18n.INSTANCE.get("catalog.queue.notification.channelDesc"));
            channel.setSound(null, null);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }
}

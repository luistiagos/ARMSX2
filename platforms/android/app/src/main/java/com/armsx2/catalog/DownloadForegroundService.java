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
        Notification notification = buildNotification("Iniciando download...", -1);
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
        notify("Download em fila...", -1, true);
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
                .setContentTitle("ARMSX2 — Download")
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
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "ROM Downloads",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Downloads de ROMs em segundo plano");
            channel.setSound(null, null);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }
}

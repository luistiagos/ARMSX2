package com.armsx2.catalog;

import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.armsx2.Pasx2Application;

/**
 * Singleton download queue. Processes one ROM at a time; others wait as QUEUED.
 * All listener callbacks fire on the main thread.
 */
public class DownloadQueueManager {

    public enum State { QUEUED, DOWNLOADING, PAUSED, DONE, ERROR }

    public interface QueueListener {
        void onQueueChanged();
        void onProgress(CatalogEntry entry);
    }

    private static volatile DownloadQueueManager sInstance;

    public static DownloadQueueManager get() {
        if (sInstance == null) {
            synchronized (DownloadQueueManager.class) {
                if (sInstance == null) sInstance = new DownloadQueueManager();
            }
        }
        return sInstance;
    }

    private final List<CatalogEntry> queue = new ArrayList<>();
    private final List<QueueListener> listeners = new ArrayList<>();
    private final RomDownloadManager romDownloadManager = new RomDownloadManager();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private CatalogEntry activeEntry;
    private File romsDir;

    private DownloadQueueManager() {}

    public void setRomsDir(File dir) { this.romsDir = dir; }

    // -------------------------------------------------------------------------
    // Public API

    /** Adds entry to queue (QUEUED state). No-op if already queued/downloading/paused/done. */
    public void enqueue(CatalogEntry entry) {
        if (entry.queueState == State.DOWNLOADING
                || entry.queueState == State.QUEUED
                || entry.queueState == State.PAUSED
                || entry.queueState == State.DONE) return;

        entry.queueState = State.QUEUED;
        entry.downloadProgress = 0f;
        entry.downloadedBytes = 0;
        synchronized (queue) { queue.add(entry); }
        DownloadForegroundService.start(Pasx2Application.appContext());
        notifyQueueChanged();
        drainQueue();
    }

    /** Pause active download (no-op if not downloading). */
    public void pause(CatalogEntry entry) {
        if (entry == activeEntry && romDownloadManager.isRunning()) {
            romDownloadManager.pause();
            entry.queueState = State.PAUSED;
            entry.isPaused = true;
            notifyQueueChanged();
        }
    }

    /** Resume a paused entry. */
    public void resume(CatalogEntry entry) {
        if (entry == activeEntry && entry.queueState == State.PAUSED) {
            romDownloadManager.resume();
            entry.queueState = State.DOWNLOADING;
            entry.isPaused = false;
            notifyQueueChanged();
        } else if (entry.queueState == State.PAUSED) {
            // Interrupted by network error / process death: re-queue and let drain restart it.
            entry.queueState = State.QUEUED;
            entry.isPaused = false;
            notifyQueueChanged();
            drainQueue();
        }
    }

    /** Remove entry from queue (cancel if active). Deletes any leftover .part file. */
    public void remove(CatalogEntry entry) {
        if (entry == activeEntry) {
            romDownloadManager.cancel();
            activeEntry = null;
        }
        synchronized (queue) { queue.remove(entry); }
        entry.queueState = null;
        entry.isDownloading = false;
        entry.isPaused = false;
        entry.downloadProgress = 0f;
        entry.downloadedBytes = 0;
        entry.totalBytes = 0;

        if (romsDir != null) {
            RomDownloadManager.discardPart(entry.getPartFile(romsDir));
        }

        notifyQueueChanged();
        drainQueue();
    }

    /** Snapshot of current queue (QUEUED + DOWNLOADING + PAUSED), in order. */
    public List<CatalogEntry> getActiveQueue() {
        synchronized (queue) { return new ArrayList<>(queue); }
    }

    public void addListener(QueueListener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(QueueListener l) { listeners.remove(l); }

    // -------------------------------------------------------------------------

    private void drainQueue() {
        if (romDownloadManager.isRunning()) return;
        if (romsDir == null) return;

        CatalogEntry next = null;
        synchronized (queue) {
            for (CatalogEntry e : queue) {
                if (e.queueState == State.QUEUED) { next = e; break; }
            }
        }
        if (next == null) return;

        activeEntry = next;
        activeEntry.queueState = State.DOWNLOADING;
        activeEntry.isDownloading = true;
        activeEntry.isPaused = false;

        notifyQueueChanged();

        final CatalogEntry current = activeEntry;
        romDownloadManager.startDownload(current, romsDir, new RomDownloadManager.DownloadCallback() {
            @Override
            public void onProgress(long bytesDownloaded, long totalBytes) {
                current.downloadedBytes = bytesDownloaded;
                current.totalBytes = totalBytes;
                current.downloadProgress = totalBytes > 0
                        ? (float) bytesDownloaded / totalBytes : 0f;
                notifyProgress(current);
            }

            @Override
            public void onComplete(File romFile) {
                current.isDownloaded = true;
                current.isDownloading = false;
                current.queueState = State.DONE;
                current.downloadProgress = 1f;
                synchronized (queue) { queue.remove(current); }
                activeEntry = null;
                notifyQueueChanged();
                drainQueue();
            }

            @Override
            public void onError(String message) {
                // Keep entry in queue as PAUSED so user can retry from where it stopped (.part preserved).
                current.isDownloading = false;
                current.isPaused = true;
                current.queueState = State.PAUSED;
                activeEntry = null;
                notifyQueueChanged();
                drainQueue();
            }

            @Override
            public void onCancelled() {
                current.isDownloading = false;
                synchronized (queue) { queue.remove(current); }
                if (activeEntry == current) activeEntry = null;
                notifyQueueChanged();
                drainQueue();
            }
        });
    }

    private void notifyQueueChanged() {
        mainHandler.post(() -> {
            for (QueueListener l : new ArrayList<>(listeners)) l.onQueueChanged();
        });
    }

    private void notifyProgress(CatalogEntry entry) {
        mainHandler.post(() -> {
            for (QueueListener l : new ArrayList<>(listeners)) l.onProgress(entry);
        });
    }
}

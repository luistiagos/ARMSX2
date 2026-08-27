package com.armsx2.catalog;

import java.io.File;

public class CatalogEntry {
    public final String fileName;   // "God of War (USA).chd"
    public final String title;      // "God of War"
    public final String coverUrl;   // URL da capa (pode ser vazio)
    public final String downloadUrl;// URL de download da ROM

    // Estado local — não vem do manifesto
    public boolean isDownloaded = false;
    public boolean isDownloading = false;
    public boolean isPaused = false;
    public float downloadProgress = 0f;   // 0.0–1.0
    public long downloadedBytes = 0;
    public long totalBytes = 0;
    public long savedAtMs = 0; // timestamp when download completed (for sort order)

    /** Queue state — null means not in queue */
    public DownloadQueueManager.State queueState = null;

    public CatalogEntry(String fileName, String title, String coverUrl, String downloadUrl) {
        this.fileName = fileName;
        this.title = title;
        this.coverUrl = coverUrl;
        this.downloadUrl = downloadUrl;
    }

    public File getLocalFile(File romsDir) {
        return new File(romsDir, fileName);
    }

    public File getPartFile(File romsDir) {
        return new File(romsDir, fileName + ".part");
    }
}

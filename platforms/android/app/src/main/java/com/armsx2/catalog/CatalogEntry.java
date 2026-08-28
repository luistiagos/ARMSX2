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

    /**
     * O arquivo parcial do download em curso.
     *
     * <b>Não existe um {@code getLocalFile}.</b> O arquivo pronto pode ter extensão diferente da
     * que esta entrada pede -- a fonte só tinha {@code .chd} para uma linha {@code .iso}, e é a
     * extensão que decide o leitor do CDVD --, então o nome final sai de
     * {@link RomDownloadManager#localFileName} e não daqui. Quem quer saber se já está no disco
     * usa {@link CatalogParser#markDownloaded}, que casa pelo nome sem extensão.
     *
     * O {@code .part}, esse sim, conserva o nome do manifesto: é o que o resume procura e o que
     * {@link DownloadQueueManager#remove} apaga.
     */
    public File getPartFile(File romsDir) {
        return new File(romsDir, fileName + ".part");
    }
}

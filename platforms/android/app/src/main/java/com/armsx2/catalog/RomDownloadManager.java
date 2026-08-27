package com.armsx2.catalog;

import android.content.Context;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

import com.armsx2.Pasx2Application;

/**
 * Gerencia o download de uma ROM por vez.
 * Suporta pause, resume (via Range header HTTP 206) e cancel.
 * Callbacks sempre entregues na thread principal.
 */
public class RomDownloadManager {

    public interface DownloadCallback {
        void onProgress(long bytesDownloaded, long totalBytes);
        void onComplete(File romFile);
        void onError(String message);
        void onCancelled();
    }

    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final int READ_TIMEOUT_MS    = 120_000;
    private static final int BUFFER_SIZE        = 65_536; // 64 KB
    private static final int MAX_RETRIES        = 3;

    private static final String DOWNLOAD_SOURCES_ENDPOINT =
            "https://emuladores.pythonanywhere.com/api/roms/download_sources";
    private static final String BY_ALIAS_ENDPOINT =
            "https://emuladores.pythonanywhere.com/api/roms/by_alias";
    private static final String FIND_BY_FILE_ENDPOINT =
            "https://emuladores.pythonanywhere.com/find_by_file";
    private static final String HUGGINGFACE_BASE =
            "https://huggingface.co/datasets/luisluis123/lemusets/resolve/main/roms";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Android) RetroSystemPS2/1.0";

    private static final long PROGRESS_THROTTLE_MS = 500; // max 2 UI updates/sec

    private volatile boolean isPaused    = false;
    private volatile boolean isCancelled = false;
    private Thread downloadThread;
    private Handler mainHandler;
    private long lastProgressMs = 0;

    private Handler getMainHandler() {
        if (mainHandler == null) {
            try {
                Looper looper = Looper.getMainLooper();
                if (looper != null) {
                    mainHandler = new Handler(looper);
                }
            } catch (Throwable ignored) {}
        }
        return mainHandler;
    }

    private void postToMain(Runnable r) {
        Handler h = getMainHandler();
        if (h != null) {
            h.post(r);
        } else {
            r.run();
        }
    }

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    /** Inicia download de entry para destDir. Qualquer download anterior é cancelado. */
    public void startDownload(CatalogEntry entry, File destDir, DownloadCallback callback) {
        cancel(); // cancela download anterior se houver

        isPaused    = false;
        isCancelled = false;

        downloadThread = new Thread(() -> doDownload(entry, destDir, callback), "RomDownload");
        downloadThread.start();
    }

    public void pause()  { isPaused = true; }

    public void resume() { isPaused = false; }

    public void cancel() {
        isCancelled = true;
        isPaused    = false; // desbloqueia loop de pause para thread encerrar
        if (downloadThread != null) {
            downloadThread.interrupt();
            downloadThread = null;
        }
    }

    public boolean isPaused()    { return isPaused; }
    public boolean isRunning()   { return downloadThread != null && downloadThread.isAlive(); }

    // -------------------------------------------------------------------------

    private static String urlEncode(String s) {
        if (s == null) return "";
        try {
            return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            return s;
        }
    }

    /**
     * Resolves download URL using the multi-source romsrepository architecture (same as retrobatnew):
     * 1. GET /api/roms/download_sources?system=ps2&path=<fileName>
     * 2. Variant extensions retry (.7z, .zip, .rar, .chd, .iso)
     * 3. GET /api/roms/by_alias?system=ps2&path=<fileName>
     * 4. Legacy find_by_file?source_id=1
     * 5. HuggingFace direct dataset fallback
     */
    public String resolveDownloadUrl(CatalogEntry entry) throws IOException {
        // If manifest has explicit URL, use it directly
        if (entry.downloadUrl != null && !entry.downloadUrl.isEmpty()) {
            return entry.downloadUrl;
        }

        String system = "ps2";
        String fileName = entry.fileName;

        // 1. romsrepository download_sources (exact name)
        String url = queryDownloadSources(system, fileName);
        if (url != null) return url;

        // 2. romsrepository download_sources (variants: .7z, .zip, .rar, .chd, .iso)
        int dotIdx = fileName.lastIndexOf('.');
        String baseName = (dotIdx > 0) ? fileName.substring(0, dotIdx) : fileName;
        String[] variants = new String[]{ ".7z", ".zip", ".rar", ".chd", ".iso" };
        for (String ext : variants) {
            String variantName = baseName + ext;
            if (variantName.equalsIgnoreCase(fileName)) continue;
            url = queryDownloadSources(system, variantName);
            if (url != null) return url;
        }

        // 3. romsrepository by_alias
        url = queryByAlias(system, fileName);
        if (url != null) return url;

        // 4. Legacy find_by_file (source_id=1)
        url = queryLegacyFindByFile(system, fileName);
        if (url != null) return url;

        // 5. HuggingFace fallback
        return HUGGINGFACE_BASE + "/ps2/" + urlEncode(entry.fileName);
    }

    private String queryDownloadSources(String system, String path) {
        String urlStr = DOWNLOAD_SOURCES_ENDPOINT
                + "?system=" + urlEncode(system)
                + "&path=" + urlEncode(path);
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(15_000);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.connect();
            if (conn.getResponseCode() == 200) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    JSONObject json = new JSONObject(sb.toString());
                    JSONArray sources = json.optJSONArray("sources");
                    if (sources != null && sources.length() > 0) {
                        for (int i = 0; i < sources.length(); i++) {
                            JSONObject s = sources.optJSONObject(i);
                            if (s != null) {
                                String link = s.optString("link", "").trim();
                                if (!link.isEmpty() && (link.startsWith("http://") || link.startsWith("https://"))) {
                                    return link;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String queryByAlias(String system, String path) {
        String urlStr = BY_ALIAS_ENDPOINT
                + "?system=" + urlEncode(system)
                + "&path=" + urlEncode(path);
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(15_000);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.connect();
            if (conn.getResponseCode() == 200) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    JSONObject json = new JSONObject(sb.toString());
                    JSONArray roms = json.optJSONArray("roms");
                    if (roms != null && roms.length() > 0) {
                        for (int i = 0; i < roms.length(); i++) {
                            JSONObject r = roms.optJSONObject(i);
                            if (r != null) {
                                String link = r.optString("link", "").trim();
                                if (!link.isEmpty() && (link.startsWith("http://") || link.startsWith("https://"))) {
                                    return link;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String queryLegacyFindByFile(String system, String path) {
        String urlStr = FIND_BY_FILE_ENDPOINT
                + "?path=" + urlEncode(path)
                + "&source_id=1&system=" + urlEncode(system);
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(15_000);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.connect();
            if (conn.getResponseCode() == 200) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                    String line = reader.readLine();
                    if (line != null && !line.trim().isEmpty() && (line.startsWith("http://") || line.startsWith("https://"))) {
                        return line.trim();
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void doDownload(CatalogEntry entry, File destDir, DownloadCallback callback) {
        acquireLocks();
        try {
            doDownloadLocked(entry, destDir, callback);
        } finally {
            releaseLocks();
        }
    }

    private void doDownloadLocked(CatalogEntry entry, File destDir, DownloadCallback callback) {
        if (!destDir.exists()) destDir.mkdirs();

        File partFile  = entry.getPartFile(destDir);
        File finalFile = entry.getLocalFile(destDir);

        // Resolve actual download URL (endpoint lookup + HuggingFace fallback)
        String downloadUrl;
        try {
            downloadUrl = resolveDownloadUrl(entry);
        } catch (IOException e) {
            notifyError(callback, "URL lookup failed: " + e.getMessage());
            return;
        }

        if (downloadUrl == null || downloadUrl.isEmpty()) {
            notifyError(callback, "No download URL available");
            return;
        }

        int attempt = 0;
        while (attempt < MAX_RETRIES) {
            attempt++;
            try {
                long existingBytes = partFile.exists() ? partFile.length() : 0;
                String currentUrl = downloadUrl;
                HttpURLConnection conn = null;
                int responseCode = 0;
                int redirectCount = 0;
                final int MAX_REDIRECTS = 10;

                while (redirectCount < MAX_REDIRECTS) {
                    URL url = new URL(currentUrl);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                    conn.setReadTimeout(READ_TIMEOUT_MS);
                    conn.setInstanceFollowRedirects(false);
                    conn.setRequestMethod("GET");
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) RetroSystemPS2/1.0");

                    // Resume support
                    if (existingBytes > 0) {
                        conn.setRequestProperty("Range", "bytes=" + existingBytes + "-");
                    }

                    conn.connect();
                    responseCode = conn.getResponseCode();

                    // Check for redirect (301, 302, 303, 307, 308)
                    if (responseCode == HttpURLConnection.HTTP_MOVED_PERM
                            || responseCode == HttpURLConnection.HTTP_MOVED_TEMP
                            || responseCode == HttpURLConnection.HTTP_SEE_OTHER
                            || responseCode == 307
                            || responseCode == 308) {
                        String location = conn.getHeaderField("Location");
                        conn.disconnect();
                        if (location == null || location.isEmpty()) {
                            break;
                        }
                        URL base = new URL(currentUrl);
                        currentUrl = new URL(base, location).toExternalForm();
                        redirectCount++;
                        continue;
                    }
                    break;
                }

                if (conn == null) {
                    throw new IOException("Failed to establish connection");
                }

                long contentLength = conn.getContentLengthLong();
                long totalBytes = 0;
                long startOffset = 0;
                boolean resuming = false;

                if (responseCode == HttpURLConnection.HTTP_PARTIAL) {
                    // Servidor aceitou resume (206)
                    startOffset = existingBytes;
                    resuming    = true;
                    String cr = conn.getHeaderField("Content-Range");
                    if (cr != null && cr.contains("/")) {
                        String totalStr = cr.substring(cr.lastIndexOf('/') + 1).trim();
                        try {
                            totalBytes = Long.parseLong(totalStr);
                        } catch (NumberFormatException ignored) {
                            totalBytes = existingBytes + (contentLength > 0 ? contentLength : 0);
                        }
                    } else {
                        totalBytes = existingBytes + (contentLength > 0 ? contentLength : 0);
                    }
                } else if (responseCode == HttpURLConnection.HTTP_OK) {
                    // Servidor ignorou Range ou início do zero
                    startOffset = 0;
                    totalBytes  = contentLength > 0 ? contentLength : 0;
                    if (partFile.exists()) partFile.delete();
                } else {
                    conn.disconnect();
                    if (attempt >= MAX_RETRIES) {
                        notifyError(callback, "HTTP " + responseCode);
                        return;
                    }
                    sleepBeforeRetry(attempt);
                    continue;
                }

                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(partFile, resuming)) {

                    byte[] buf       = new byte[BUFFER_SIZE];
                    long bytesWritten = startOffset;
                    int read;

                    while ((read = in.read(buf)) != -1) {
                        // Pause: bloqueia até resume ou cancel
                        while (isPaused && !isCancelled) {
                            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                        }

                        if (isCancelled || Thread.currentThread().isInterrupted()) {
                            conn.disconnect();
                            notifyCancelled(callback);
                            return;
                        }

                        out.write(buf, 0, read);
                        bytesWritten += read;

                        long now = System.currentTimeMillis();
                        if (now - lastProgressMs >= PROGRESS_THROTTLE_MS) {
                            lastProgressMs = now;
                            final long bw = bytesWritten;
                            final long tb = totalBytes;
                            postToMain(() -> callback.onProgress(bw, tb));
                        }
                    }

                    out.flush();
                }

                conn.disconnect();

                // Move .part → arquivo final
                if (finalFile.exists()) finalFile.delete();
                if (!partFile.renameTo(finalFile)) {
                    // Fallback: copia manualmente se rename falhar (cross-device)
                    copyAndDelete(partFile, finalFile);
                }

                notifyComplete(callback, finalFile);
                return;

            } catch (IOException e) {
                if (isCancelled || Thread.currentThread().isInterrupted()) {
                    notifyCancelled(callback);
                    return;
                }
                if (attempt >= MAX_RETRIES) {
                    notifyError(callback, e.getMessage());
                    return;
                }
                sleepBeforeRetry(attempt);
            }
        }

        notifyError(callback, "Falha após " + MAX_RETRIES + " tentativas");
    }

    private void sleepBeforeRetry(int attempt) {
        try { Thread.sleep(1000L * attempt); } catch (InterruptedException ignored) {}
    }

    private void acquireLocks() {
        Context ctx = Pasx2Application.appContext();
        if (ctx == null) return;
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ARMSX2:RomDownload");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire(60 * 60 * 1000L); // 1h timeout safety
            }
        } catch (Exception ignored) {}
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(
                        WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ARMSX2:RomDownload");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Exception ignored) {}
    }

    private void releaseLocks() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Exception ignored) {}
        wakeLock = null;
        wifiLock = null;
    }

    private void copyAndDelete(File src, File dst) throws IOException {
        try (java.io.FileInputStream in  = new java.io.FileInputStream(src);
             FileOutputStream        out = new FileOutputStream(dst)) {
            byte[] buf = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buf)) != -1) out.write(buf, 0, read);
            out.flush();
        }
        src.delete();
    }

    private void notifyComplete(DownloadCallback cb, File file) {
        postToMain(() -> cb.onComplete(file));
    }

    private void notifyError(DownloadCallback cb, String msg) {
        postToMain(() -> cb.onError(msg != null ? msg : "Erro desconhecido"));
    }

    private void notifyCancelled(DownloadCallback cb) {
        postToMain(cb::onCancelled);
    }
}

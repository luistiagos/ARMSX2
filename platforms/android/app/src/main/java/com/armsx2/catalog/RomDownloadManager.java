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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

    /**
     * Extensoes que o CDVD sabe abrir.
     *
     * A escolha do leitor e pela EXTENSAO do arquivo -- `GetFileReader`, em
     * `pcsx2/CDVD/InputIsoFile.cpp`. Um CHD gravado como `.iso` cai no `FlatFileReader` e falha
     * como se estivesse corrompido; um `.7z`, que nao e disco nenhum, idem. Daqui saem duas
     * regras: nao aceitar fonte de formato fora desta lista, e gravar o arquivo com a extensao do
     * conteudo que chegou.
     */
    private static final String[] PLAYABLE_EXTENSIONS =
            { "chd", "iso", "cso", "zso", "gz", "bin", "img", "mdf", "nrg", "dump" };

    /**
     * Extensoes tentadas quando o nome exato do manifesto nao existe no repositorio.
     *
     * `.7z`, `.zip` e `.rar` sairam da lista: o app nao descompacta nada. Estavam ANTES de `.chd`,
     * o que fazia preferir o formato que nao roda mesmo quando existia um `.chd` do mesmo jogo --
     * ver o bug `catalogo-download-entrega-formato-nao-bootavel`.
     */
    private static final String[] VARIANT_EXTENSIONS = { ".chd", ".iso", ".cso", ".zso" };

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

    /** Onde baixar, e com que nome gravar. As duas coisas saem juntas da resolucao. */
    public static final class Source {
        public final String url;
        /** Nome do manifesto, com a extensao do que a URL entrega de verdade. */
        public final String fileName;

        Source(String url, String fileName) {
            this.url = url;
            this.fileName = fileName;
        }
    }

    /** Extensao do arquivo apontado pela URL: minuscula, sem ponto, "" quando nao ha. */
    static String extensionOf(String url) {
        if (url == null) return "";
        String path = url;
        int cut = path.indexOf('?');
        if (cut >= 0) path = path.substring(0, cut);
        cut = path.indexOf('#');
        if (cut >= 0) path = path.substring(0, cut);
        int slash = path.lastIndexOf('/');
        String name = (slash >= 0) ? path.substring(slash + 1) : path;
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        String ext = name.substring(dot + 1).toLowerCase();
        return ext.matches("[a-z0-9]{1,5}") ? ext : "";
    }

    /** true quando o CDVD abre um arquivo com essa extensao. Ver PLAYABLE_EXTENSIONS. */
    static boolean isPlayable(String extension) {
        for (String e : PLAYABLE_EXTENSIONS) {
            if (e.equals(extension)) return true;
        }
        return false;
    }

    /**
     * O nome com que gravar o que a URL entrega: o do manifesto, com a extensao do conteudo.
     *
     * Uma linha `.iso` do manifesto resolvida para um CHD e gravada `.chd` -- so assim o emulador
     * escolhe o leitor certo. Quando a URL nao tem extensao utilizavel, o nome do manifesto fica
     * como esta: quem decide se aquilo presta e o chamador, com {@link #isPlayable}.
     */
    static String localFileName(String manifestName, String url) {
        String ext = extensionOf(url);
        if (!isPlayable(ext)) return manifestName;
        int dot = manifestName.lastIndexOf('.');
        String base = (dot > 0) ? manifestName.substring(0, dot) : manifestName;
        return base + "." + ext;
    }

    private static Source firstPlayable(String manifestName, List<String> links) {
        for (String link : links) {
            if (isPlayable(extensionOf(link))) {
                return new Source(link, localFileName(manifestName, link));
            }
        }
        return null;
    }

    /**
     * Resolve onde baixar, na arquitetura multi-fonte do romsrepository (igual ao retrobatnew):
     * 1. URL explicita do manifesto
     * 2. GET /api/roms/download_sources?system=ps2&path=<fileName>
     * 3. Variantes de extensao (.chd, .iso, .cso, .zso)
     * 4. GET /api/roms/by_alias?system=ps2&path=<fileName>
     * 5. Legado find_by_file?source_id=1
     * 6. HuggingFace direto
     *
     * Em qualquer passo, link de formato que o emulador nao abre e descartado -- e por isso o
     * resultado pode ser **null**: e melhor falhar aqui do que baixar 2 GB de um `.7z` que vai
     * ficar no disco sem rodar.
     */
    public Source resolveSource(CatalogEntry entry) {
        String fileName = entry.fileName;

        if (entry.downloadUrl != null && !entry.downloadUrl.isEmpty()) {
            // URL curada a mao no manifesto: vale como esta. O formato, nao -- nem vindo do
            // manifesto o app sabe abrir um arquivo comprimido.
            String name = localFileName(fileName, entry.downloadUrl);
            return isPlayable(extensionOf(name)) ? new Source(entry.downloadUrl, name) : null;
        }

        String system = "ps2";

        Source source = firstPlayable(fileName, queryDownloadSources(system, fileName));
        if (source != null) return source;

        int dotIdx = fileName.lastIndexOf('.');
        String baseName = (dotIdx > 0) ? fileName.substring(0, dotIdx) : fileName;
        for (String ext : VARIANT_EXTENSIONS) {
            String variantName = baseName + ext;
            if (variantName.equalsIgnoreCase(fileName)) continue;
            source = firstPlayable(fileName, queryDownloadSources(system, variantName));
            if (source != null) return source;
        }

        source = firstPlayable(fileName, queryByAlias(system, fileName));
        if (source != null) return source;

        source = firstPlayable(fileName, queryLegacyFindByFile(system, fileName));
        if (source != null) return source;

        // HuggingFace: o dataset guarda o arquivo com o nome do manifesto, entao o formato aqui e
        // o da propria entrada -- uma linha `.7z` do catalogo nao tem o que tentar.
        if (!isPlayable(extensionOf(fileName))) return null;
        return new Source(HUGGINGFACE_BASE + "/ps2/" + urlEncode(fileName), fileName);
    }

    /** Todos os links da resposta, na ordem em que vieram. Lista vazia quando nao ha resposta. */
    private List<String> queryDownloadSources(String system, String path) {
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
                    if (sources != null) {
                        List<String> links = new ArrayList<>(sources.length());
                        for (int i = 0; i < sources.length(); i++) {
                            JSONObject s = sources.optJSONObject(i);
                            if (s != null) {
                                String link = s.optString("link", "").trim();
                                if (link.startsWith("http://") || link.startsWith("https://")) {
                                    links.add(link);
                                }
                            }
                        }
                        return links;
                    }
                }
            }
        } catch (Exception ignored) {}
        return Collections.emptyList();
    }

    /**
     * Idem, para o /by_alias.
     *
     * Cuidado: ele resolve por TITULO. Os links podem ser de outra regiao e de outro formato que o
     * pedido -- foi assim que uma linha `(Europe).iso` virou o CHD da versao USA gravado com o
     * nome europeu. Quem decide o que serve e a filtragem por extensao de {@link #firstPlayable}.
     */
    private List<String> queryByAlias(String system, String path) {
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
                    if (roms != null) {
                        List<String> links = new ArrayList<>(roms.length());
                        for (int i = 0; i < roms.length(); i++) {
                            JSONObject r = roms.optJSONObject(i);
                            if (r != null) {
                                String link = r.optString("link", "").trim();
                                if (link.startsWith("http://") || link.startsWith("https://")) {
                                    links.add(link);
                                }
                            }
                        }
                        return links;
                    }
                }
            }
        } catch (Exception ignored) {}
        return Collections.emptyList();
    }

    private List<String> queryLegacyFindByFile(String system, String path) {
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
                    if (line != null && (line.startsWith("http://") || line.startsWith("https://"))) {
                        return Collections.singletonList(line.trim());
                    }
                }
            }
        } catch (Exception ignored) {}
        return Collections.emptyList();
    }

    /**
     * A URL de onde os bytes do `.part` vieram, anotada ao lado dele.
     *
     * Sem isto o resume e cego: ele manda `Range: bytes=<tamanho do .part>-` para a fonte que a
     * resolucao devolver AGORA, que pode ser outra -- e passou a ser outra para muita entrada, com
     * a recusa dos formatos comprimidos. Colar a segunda metade de um arquivo na primeira metade
     * de outro nao produz erro nenhum: produz um arquivo do tamanho certo que nao e nenhum dos
     * dois.
     */
    private static File sourceMarker(File partFile) {
        return new File(partFile.getParentFile(), partFile.getName() + ".src");
    }

    private static String readSourceMarker(File partFile) {
        File marker = sourceMarker(partFile);
        if (!marker.exists()) return null;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new java.io.FileInputStream(marker), "UTF-8"))) {
            String line = reader.readLine();
            return (line == null) ? null : line.trim();
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeSourceMarker(File partFile, String url) {
        try (FileOutputStream out = new FileOutputStream(sourceMarker(partFile))) {
            out.write(url.getBytes("UTF-8"));
        } catch (Exception ignored) {}
    }

    /** Descarta um download pela metade: o `.part` e a anotacao de origem que anda com ele. */
    public static void discardPart(File partFile) {
        if (partFile == null) return;
        if (partFile.exists()) partFile.delete();
        File marker = sourceMarker(partFile);
        if (marker.exists()) marker.delete();
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

        // O `.part` conserva o nome do manifesto mesmo quando o arquivo final muda de extensao:
        // e ele que o resume procura e que `DownloadQueueManager.remove` apaga. A troca de nome
        // acontece so no rename final.
        File partFile = entry.getPartFile(destDir);

        Source source = resolveSource(entry);
        if (source == null) {
            notifyError(callback, "Sem fonte compativel: o emulador nao abre o formato disponivel");
            return;
        }

        File finalFile = new File(destDir, source.fileName);
        String downloadUrl = source.url;

        // So retoma o que veio da MESMA URL -- ver sourceMarker.
        if (partFile.exists() && !downloadUrl.equals(readSourceMarker(partFile))) {
            partFile.delete();
        }
        writeSourceMarker(partFile, downloadUrl);

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
                    // 404 e resposta definitiva: o arquivo nao existe naquela fonte, e insistir tres
                    // vezes so faz o usuario esperar. E o caso comum das entradas que so existem em
                    // formato comprimido -- recusadas na resolucao, elas caem no HuggingFace, que
                    // nao as tem.
                    if (responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
                        notifyError(callback, "Sem fonte disponivel para este arquivo (404)");
                        return;
                    }
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

                // Move .part → arquivo final, com a extensao do conteudo que chegou
                if (finalFile.exists()) finalFile.delete();
                if (!partFile.renameTo(finalFile)) {
                    // Fallback: copia manualmente se rename falhar (cross-device)
                    copyAndDelete(partFile, finalFile);
                }
                sourceMarker(partFile).delete();

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

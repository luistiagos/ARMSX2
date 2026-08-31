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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.armsx2.Pasx2Application;

/**
 * Gerencia o download de uma ROM por vez.
 * Suporta pause, resume (via Range header HTTP 206) e cancel.
 * Callbacks sempre entregues na thread principal.
 */
public class RomDownloadManager {

    public interface DownloadCallback {
        void onProgress(long bytesDownloaded, long totalBytes);
        /**
         * O download acabou e o que chegou era um `.7z`/`.zip` que esta sendo aberto.
         *
         * Chega com {@code totalBytes == 0} na primeira vez -- o tamanho descomprimido so aparece
         * depois de abrir o cabecalho do arquivo, e ate la a unica coisa honesta a mostrar e uma
         * barra indeterminada. Ver a TASK-0048.
         */
        void onExtracting(long bytesExtracted, long totalBytes);
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
     * `.7z` e `.zip` voltaram na TASK-0048, mas <b>no fim</b> e nao no comeco. Estavam ANTES de
     * `.chd` originalmente, o que fazia preferir o formato que nao roda mesmo quando existia um
     * `.chd` do mesmo jogo -- ver o bug `catalogo-download-entrega-formato-nao-bootavel`. A ordem
     * daqui e so metade da garantia; a outra metade e {@link #resolveSource}, que so usa um
     * comprimido depois de a cascata inteira falhar. `.rar` continua fora: nao sabemos abrir.
     */
    private static final String[] VARIANT_EXTENSIONS =
            { ".chd", ".iso", ".cso", ".zso", ".7z", ".zip" };

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
            return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace("%2F", "/");
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

    /** O nome do manifesto com outra extensao, preservando pontos que fazem parte do titulo. */
    static String withExtension(String manifestName, String extension) {
        int dot = manifestName.lastIndexOf('.');
        String base = (dot > 0) ? manifestName.substring(0, dot) : manifestName;
        return base + "." + extension;
    }

    /**
     * O nome com que gravar o que a URL entrega: o do manifesto, com a extensao do conteudo.
     *
     * Uma linha `.iso` do manifesto resolvida para um CHD e gravada `.chd` -- so assim o emulador
     * escolhe o leitor certo. Um comprimido tambem e gravado com a extensao dele, para que
     * {@link RomArchiveExtractor} saiba qual leitor de arquivo usar; a extensao final so aparece
     * depois da extracao. Quando a URL nao tem extensao utilizavel, o nome do manifesto fica como
     * esta: quem decide se aquilo presta e o chamador.
     */
    static String localFileName(String manifestName, String url) {
        String ext = extensionOf(url);
        if (!isPlayable(ext) && !RomArchiveExtractor.isArchive(ext)) return manifestName;
        return withExtension(manifestName, ext);
    }

    private static Source firstPlayable(String manifestName, List<String> links) {
        for (String link : links) {
            if (isPlayable(extensionOf(link))) {
                return new Source(link, localFileName(manifestName, link));
            }
        }
        return null;
    }

    /** Idem, para os formatos comprimidos. So e consultado quando nao ha formato direto nenhum. */
    private static Source firstArchive(String manifestName, List<String> links) {
        for (String link : links) {
            if (RomArchiveExtractor.isArchive(extensionOf(link))) {
                return new Source(link, localFileName(manifestName, link));
            }
        }
        return null;
    }

    static class IAFileEntry {
        final String name;
        final boolean isOriginal;
        final boolean isPrivate;
        final long size;

        IAFileEntry(String name, boolean isOriginal, boolean isPrivate, long size) {
            this.name = name;
            this.isOriginal = isOriginal;
            this.isPrivate = isPrivate;
            this.size = size;
        }
    }

    private static final Map<String, List<IAFileEntry>> IA_COLLECTION_CACHE = new ConcurrentHashMap<>();

    private static String getIACookieHeader() {
        try {
            android.content.SharedPreferences prefs = com.armsx2.runtime.MainActivityRuntime.Companion.getPrefs();
            if (prefs != null) {
                return prefs.getString("ia_cookie_header", null);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static void applyArchiveOrgHeaders(HttpURLConnection conn, String url) {
        if (conn == null || url == null) return;
        if (url.toLowerCase().contains("archive.org")) {
            String cookie = getIACookieHeader();
            if (cookie != null && !cookie.trim().isEmpty()) {
                conn.setRequestProperty("Cookie", cookie.trim());
            }
        }
    }

    static String cleanFileName(String path) {
        if (path == null) return "";
        path = path.trim();
        if (path.contains("%")) {
            try {
                path = java.net.URLDecoder.decode(path, "UTF-8");
            } catch (Throwable ignored) {}
        }
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        if (slash >= 0 && slash < path.length() - 1) {
            path = path.substring(slash + 1).trim();
        }
        return path;
    }

    /**
     * Resolve todas as fontes onde baixar, seguindo a arquitetura multi-fonte com priorização de CDN:
     * 1. URL explícita do manifesto (se houver)
     * 2. GET /api/roms/by_alias?system=ps2&path=<fileName> (PythonAnywhere)
     * 3. GET /api/roms/download_sources?system=ps2&path=<fileName> (PythonAnywhere + variantes de extensão)
     * 4. Espelhos diretos no HuggingFace (ps2chd e ps2mod)
     * 5. IACache (Internet Archive): Coleções estruturadas e LiveSearch
     *
     * Prioridade de entrega:
     * - Formatos bootáveis (.chd, .iso, .cso) no HuggingFace (CDN veloz e sem rate-limit)
     * - Formatos bootáveis em outras fontes (Archive.org, espelhos)
     * - Formatos compactados (.7z, .zip) no HuggingFace
     * - Formatos compactados em outras fontes (.7z, .zip)
     */
    public List<Source> resolveSources(CatalogEntry entry) {
        String rawFileName = (entry != null && entry.fileName != null) ? entry.fileName : "";
        String fileName = cleanFileName(rawFileName);

        List<Source> hfPlayable = new ArrayList<>();
        List<Source> otherPlayable = new ArrayList<>();
        List<Source> hfArchive = new ArrayList<>();
        List<Source> otherArchive = new ArrayList<>();
        java.util.Set<String> seenUrls = new java.util.HashSet<>();

        java.util.List<String> directCandidates = new ArrayList<>();

        if (entry != null && entry.downloadUrl != null && !entry.downloadUrl.trim().isEmpty()) {
            directCandidates.add(entry.downloadUrl.trim());
        }

        String system = "ps2";

        // 2. by_alias (PythonAnywhere)
        List<String> aliasLinks = queryByAlias(system, fileName);
        directCandidates.addAll(aliasLinks);

        // 3. download_sources (PythonAnywhere) + variantes de extensao
        List<String> srcLinks = queryDownloadSources(system, fileName);
        directCandidates.addAll(srcLinks);

        int dotIdx = fileName.lastIndexOf('.');
        String baseName = (dotIdx > 0) ? fileName.substring(0, dotIdx) : fileName;
        for (String ext : VARIANT_EXTENSIONS) {
            String variantName = baseName + ext;
            if (variantName.equalsIgnoreCase(fileName)) continue;
            List<String> vLinks = queryDownloadSources(system, variantName);
            directCandidates.addAll(vLinks);
        }

        List<String> legacyLinks = queryLegacyFindByFile(system, fileName);
        directCandidates.addAll(legacyLinks);

        // 4. HuggingFace direct dataset mirrors
        directCandidates.add("https://huggingface.co/datasets/luisluis123/ps2chd/resolve/main/" + urlEncode(baseName) + ".chd");
        directCandidates.add("https://huggingface.co/datasets/luisluis123/ps2mod/resolve/main/" + urlEncode(fileName));
        directCandidates.add("https://huggingface.co/datasets/luisluis123/ps2mod/resolve/main/" + urlEncode(baseName) + ".7z");
        directCandidates.add("https://huggingface.co/datasets/luisluis123/ps2mod/resolve/main/" + urlEncode(baseName) + ".iso");
        directCandidates.add("https://huggingface.co/luisluis123/ps2mod/resolve/main/" + urlEncode(baseName) + ".7z");
        directCandidates.add("https://huggingface.co/luisluis123/ps2mod/resolve/main/" + urlEncode(fileName));

        // 5. Internet Archive Coleções estruturadas & LiveSearch
        Source iaSource = queryInternetArchive(fileName);
        if (iaSource != null && iaSource.url != null) {
            directCandidates.add(iaSource.url);
        }

        for (String link : directCandidates) {
            if (link == null) continue;
            link = link.trim();
            if (!link.startsWith("http://") && !link.startsWith("https://")) continue;
            if (seenUrls.contains(link)) continue;
            seenUrls.add(link);

            String ext = extensionOf(link);
            boolean playable = isPlayable(ext);
            boolean isArch = RomArchiveExtractor.isArchive(ext);
            if (!playable && !isArch) continue;

            String name = localFileName(fileName, link);
            Source src = new Source(link, name);
            boolean isHf = link.toLowerCase().contains("huggingface.co");

            if (playable) {
                if (isHf) {
                    hfPlayable.add(src);
                } else {
                    otherPlayable.add(src);
                }
            } else {
                if (isHf) {
                    hfArchive.add(src);
                } else {
                    otherArchive.add(src);
                }
            }
        }

        List<Source> result = new ArrayList<>();
        result.addAll(hfPlayable);
        result.addAll(otherPlayable);
        result.addAll(hfArchive);
        result.addAll(otherArchive);
        return result;
    }

    public Source resolveSource(CatalogEntry entry) {
        List<Source> sources = resolveSources(entry);
        return sources.isEmpty() ? null : sources.get(0);
    }

    List<String> getIACollectionsFor(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String clean = (dot > 0) ? fileName.substring(0, dot).trim() : fileName.trim();
        String lower = clean.toLowerCase();

        List<String> res = new ArrayList<>();

        // 1. Coleções regionais públicas irrestritas (Status 200/206 sem necessidade de cookie)
        if (lower.contains("japan") || lower.contains("asia") || lower.contains("(ja") || lower.contains("taikenban")) {
            res.add("PS2-ASIA-ROMS321COM");
            res.add("ps2japanredumpmissing");
            res.add("ps2japanredump1");
            res.add("ps2japanredump2");
            res.add("ps2japanredump3");
        } else if (lower.contains("pt-br") || lower.contains("brasil") || lower.contains("portugues") || lower.contains("dublado") || lower.contains("legendado")) {
            res.add("ps2_pt-br");
        } else if (lower.contains("europe") || lower.contains("(en,") || lower.contains("(fr,") || lower.contains("(de,") || lower.contains("(es,") || lower.contains("(it,")) {
            res.add("ps2-eu-roms321com");
            res.add("playstation2_essentials");
            res.add("playstation2_essentials_part2");
            res.add("playstation-2-game-dumps");
        } else {
            // Default USA e geral
            res.add("roms321-ps2redump");
            res.add("playstation2_essentials");
            res.add("playstation2_essentials_part2");
            res.add("playstation-2-game-dumps");
            res.add("ps2-eu-roms321com");
            res.add("PS2-ASIA-ROMS321COM");
        }

        // Extrai primeiro caractere para coleções particionadas (ps2-redump-usa-chd-part-X e sony_playstation2_X)
        char firstChar = ' ';
        for (int i = 0; i < clean.length(); i++) {
            char c = Character.toLowerCase(clean.charAt(i));
            if (Character.isLetterOrDigit(c)) {
                firstChar = c;
                break;
            }
        }
        if (firstChar >= 'a' && firstChar <= 'z') {
            res.add("ps2-redump-usa-chd-part-" + Character.toUpperCase(firstChar));
        } else if (Character.isDigit(firstChar)) {
            res.add("ps2-redump-usa-chd-part-0");
        }

        // Coleções estruturadas Redump adicionais
        if (Character.isDigit(firstChar)) {
            res.add("sony_playstation2_numberssymbols");
        } else if (firstChar >= 'a' && firstChar <= 'z') {
            if (firstChar == 'd') {
                res.add("sony_playstation2_d_part1");
                res.add("sony_playstation2_d_part2");
            } else if (firstChar == 'm') {
                res.add("sony_playstation2_m_part1");
                res.add("sony_playstation2_m_part2");
            } else if (firstChar == 'o') {
                res.add("sony_playstation2_o_part1");
                res.add("sony_playstation2_o_part2");
            } else if (firstChar == 's') {
                res.add("sony_playstation2_s_part1");
                res.add("sony_playstation2_s_part2");
                res.add("sony_playstation2_s_part3");
                res.add("sony_playstation2_s_part4");
            } else {
                res.add("sony_playstation2_" + firstChar);
            }
        }
        return res;
    }

    private Source queryInternetArchive(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String clean = (dot > 0) ? fileName.substring(0, dot).trim() : fileName.trim();
        String lowerClean = clean.toLowerCase();

        boolean hasCookie = (getIACookieHeader() != null);
        Source bestArchive = null;

        // 1. Varredura nas coleções estruturadas (como no PS2Companion)
        List<String> colls = getIACollectionsFor(fileName);
        for (String coll : colls) {
            List<IAFileEntry> entries = IA_COLLECTION_CACHE.get(coll);
            if (entries == null) {
                entries = fetchIACollectionFiles(coll);
                if (entries != null && !entries.isEmpty()) {
                    IA_COLLECTION_CACHE.put(coll, entries);
                }
            }
            if (entries == null || entries.isEmpty()) continue;

            Source match = matchIAEntry(fileName, clean, lowerClean, coll, entries, hasCookie);
            if (match != null) {
                String ext = extensionOf(match.fileName);
                if (isPlayable(ext)) {
                    return match;
                } else if (bestArchive == null && RomArchiveExtractor.isArchive(ext)) {
                    bestArchive = match;
                }
            }
        }

        // 2. LiveSearch dinâmico via API do Internet Archive (para itens avulsos ou coleções fechadas)
        Source liveMatch = liveSearchIA(fileName, clean, hasCookie);
        if (liveMatch != null) {
            String ext = extensionOf(liveMatch.fileName);
            if (isPlayable(ext)) {
                return liveMatch;
            } else if (bestArchive == null && RomArchiveExtractor.isArchive(ext)) {
                bestArchive = liveMatch;
            }
        }

        return bestArchive;
    }

    Source matchIAEntry(String originalFileName, String clean, String lowerClean,
                        String collOrIdentifier, List<IAFileEntry> entries, boolean hasCookie) {
        String baseTarget = clean.replaceAll("[\\(\\[].*?[\\)\\]]", "").trim().toLowerCase();
        String baseTargetPrefix = baseTarget;
        int dashIdx = baseTarget.indexOf(" - ");
        if (dashIdx > 0) {
            baseTargetPrefix = baseTarget.substring(0, dashIdx).trim();
        }

        // Pass 1: Correspondência exata (mesmo título e região, considerando prefixos de pasta)
        for (IAFileEntry entry : entries) {
            if (entry.isPrivate && !hasCookie) continue;
            String fName = entry.name;
            String rawName = cleanFileName(fName);
            int fDot = rawName.lastIndexOf('.');
            String fClean = (fDot > 0) ? rawName.substring(0, fDot).trim().toLowerCase() : rawName.trim().toLowerCase();
            String ext = extensionOf(rawName);
            if (!isPlayable(ext) && !RomArchiveExtractor.isArchive(ext)) continue;

            if (fClean.equals(lowerClean)) {
                String downloadUrl = "https://archive.org/download/" + collOrIdentifier + "/" + urlEncode(fName);
                return new Source(downloadUrl, localFileName(originalFileName, rawName));
            }
        }

        // Pass 2: Correspondência por baseName / prefixo / subtítulo
        for (IAFileEntry entry : entries) {
            if (entry.isPrivate && !hasCookie) continue;
            String fName = entry.name;
            String rawName = cleanFileName(fName);
            int fDot = rawName.lastIndexOf('.');
            String fClean = (fDot > 0) ? rawName.substring(0, fDot).trim().toLowerCase() : rawName.trim().toLowerCase();
            String ext = extensionOf(rawName);
            if (!isPlayable(ext) && !RomArchiveExtractor.isArchive(ext)) continue;

            String baseEntry = fClean.replaceAll("[\\(\\[].*?[\\)\\]]", "").trim();
            String baseEntryPrefix = baseEntry;
            int eDash = baseEntry.indexOf(" - ");
            if (eDash > 0) {
                baseEntryPrefix = baseEntry.substring(0, eDash).trim();
            }

            boolean matches = (!baseTarget.isEmpty() && (baseEntry.equals(baseTarget) || fClean.contains(lowerClean) || lowerClean.contains(fClean)))
                    || (!baseTargetPrefix.isEmpty() && (baseEntryPrefix.equals(baseTargetPrefix) || baseEntry.startsWith(baseTargetPrefix) || baseTarget.startsWith(baseEntryPrefix)));

            if (matches) {
                String downloadUrl = "https://archive.org/download/" + collOrIdentifier + "/" + urlEncode(fName);
                return new Source(downloadUrl, localFileName(originalFileName, rawName));
            }
        }
        return null;
    }

    private Source liveSearchIA(String originalFileName, String clean, boolean hasCookie) {
        try {
            String cleanWithoutTags = clean.replaceAll("[\\(\\[].*?[\\)\\]]", "").trim();
            String[] words = cleanWithoutTags.replaceAll("[^a-zA-Z0-9]+", " ").trim().split("\\s+");
            if (words.length == 0 || (words.length == 1 && words[0].isEmpty())) return null;

            StringBuilder terms = new StringBuilder();
            int maxWords = Math.min(words.length, 5);
            for (int i = 0; i < maxWords; i++) {
                if (terms.length() > 0) terms.append(" ");
                terms.append(words[i]);
            }

            String query = "mediatype:(software) AND (" + terms.toString() + ")";
            String searchUrl = "https://archive.org/advancedsearch.php?q=" + urlEncode(query)
                    + "&fl[]=identifier,title,mediatype&rows=6&output=json";

            HttpURLConnection conn = (HttpURLConnection) new URL(searchUrl).openConnection();
            conn.setConnectTimeout(6_000);
            conn.setReadTimeout(10_000);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            applyArchiveOrgHeaders(conn, searchUrl);
            conn.connect();

            if (conn.getResponseCode() == 200) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);

                    JSONObject json = new JSONObject(sb.toString());
                    JSONObject responseObj = json.optJSONObject("response");
                    if (responseObj != null) {
                        JSONArray docs = responseObj.optJSONArray("docs");
                        if (docs != null) {
                            Source bestArchive = null;
                            for (int i = 0; i < docs.length(); i++) {
                                JSONObject doc = docs.optJSONObject(i);
                                if (doc == null) continue;
                                String identifier = doc.optString("identifier", "").trim();
                                if (identifier.isEmpty()) continue;

                                List<IAFileEntry> files = fetchIACollectionFiles(identifier);
                                if (files.isEmpty()) continue;

                                Source match = matchIAEntry(originalFileName, clean, clean.toLowerCase(), identifier, files, hasCookie);
                                if (match != null) {
                                    String ext = extensionOf(match.fileName);
                                    if (isPlayable(ext)) {
                                        return match;
                                    } else if (bestArchive == null && RomArchiveExtractor.isArchive(ext)) {
                                        bestArchive = match;
                                    }
                                }
                            }
                            if (bestArchive != null) return bestArchive;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private List<IAFileEntry> fetchIACollectionFiles(String collectionID) {
        List<IAFileEntry> list = new ArrayList<>();
        String urlStr = "https://archive.org/metadata/" + urlEncode(collectionID) + "/files";
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(6_000);
            conn.setReadTimeout(10_000);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            applyArchiveOrgHeaders(conn, urlStr);
            conn.connect();
            int respCode = conn.getResponseCode();

            // Se o sub-endpoint /files falhar, tenta o endpoint principal de metadados
            if (respCode != 200) {
                conn.disconnect();
                urlStr = "https://archive.org/metadata/" + urlEncode(collectionID);
                conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setConnectTimeout(6_000);
                conn.setReadTimeout(10_000);
                conn.setRequestProperty("User-Agent", USER_AGENT);
                applyArchiveOrgHeaders(conn, urlStr);
                conn.connect();
                respCode = conn.getResponseCode();
            }

            if (respCode == 200) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    JSONObject json = new JSONObject(sb.toString());
                    JSONArray result = json.optJSONArray("result");
                    if (result == null) {
                        result = json.optJSONArray("files");
                    }
                    if (result != null) {
                        for (int i = 0; i < result.length(); i++) {
                            JSONObject item = result.optJSONObject(i);
                            if (item != null) {
                                String name = item.optString("name", "").trim();
                                if (name.isEmpty()) continue;
                                String lower = name.toLowerCase();
                                if (lower.endsWith("_meta.xml") || lower.endsWith("_files.xml")
                                        || lower.endsWith("_archive.torrent") || lower.endsWith("_thumb.jpg")
                                        || lower.endsWith(".sqlite")) {
                                    continue;
                                }
                                String source = item.optString("source", "");
                                boolean isOriginal = "original".equalsIgnoreCase(source);
                                boolean isPrivate = item.optBoolean("private", false)
                                        || "true".equalsIgnoreCase(item.optString("private", "false"));
                                long size = 0;
                                try {
                                    size = Long.parseLong(item.optString("size", "0"));
                                } catch (Throwable ignored) {}
                                list.add(new IAFileEntry(name, isOriginal, isPrivate, size));
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return list;
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

        List<Source> candidateSources = resolveSources(entry);
        if (candidateSources.isEmpty()) {
            notifyError(callback, "Sem fonte compatível: o emulador não abre o formato disponível");
            return;
        }

        File partFile = entry.getPartFile(destDir);
        String lastErrorMessage = null;

        for (int srcIdx = 0; srcIdx < candidateSources.size(); srcIdx++) {
            if (isCancelled || Thread.currentThread().isInterrupted()) {
                notifyCancelled(callback);
                return;
            }

            Source source = candidateSources.get(srcIdx);
            File finalFile = new File(destDir, source.fileName);
            String downloadUrl = source.url;
            final boolean isArchiveSource =
                    RomArchiveExtractor.isArchive(extensionOf(source.fileName));

            // So retoma o que veio da MESMA URL -- ver sourceMarker
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
                        conn.setRequestProperty("User-Agent", USER_AGENT);
                        conn.setRequestProperty("Accept-Encoding", "identity");
                        applyArchiveOrgHeaders(conn, currentUrl);

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
                        throw new IOException("Falha ao conectar com " + currentUrl);
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
                        startOffset = 0;
                        totalBytes  = contentLength > 0 ? contentLength : 0;
                        if (partFile.exists()) partFile.delete();
                    } else {
                        conn.disconnect();
                        lastErrorMessage = "HTTP " + responseCode;
                        if (responseCode == HttpURLConnection.HTTP_NOT_FOUND
                                || responseCode == HttpURLConnection.HTTP_FORBIDDEN
                                || responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                            break;
                        }
                        if (attempt >= MAX_RETRIES) {
                            break;
                        }
                        sleepBeforeRetry(attempt);
                        continue;
                    }

                    String contentType = conn.getContentType();
                    if (contentType != null) {
                        String ctLower = contentType.toLowerCase();
                        if (ctLower.startsWith("image/") || ctLower.startsWith("text/html")) {
                            conn.disconnect();
                            lastErrorMessage = "Fonte retornou formato inválido (" + contentType + ")";
                            break;
                        }
                    }

                    if (totalBytes > 0) {
                        long needed = (totalBytes - startOffset)
                                + (isArchiveSource ? totalBytes : 0);
                        String shortfall = RomArchiveExtractor.spaceShortfall(destDir, needed);
                        if (shortfall != null) {
                            conn.disconnect();
                            notifyError(callback, shortfall);
                            return;
                        }
                    }

                    try (InputStream in = conn.getInputStream();
                         FileOutputStream out = new FileOutputStream(partFile, resuming)) {

                        byte[] buf       = new byte[BUFFER_SIZE];
                        long bytesWritten = startOffset;
                        int read;

                        while ((read = in.read(buf)) != -1) {
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
                        copyAndDelete(partFile, finalFile);
                    }
                    sourceMarker(partFile).delete();

                    File ready = finalFile;
                    if (isArchiveSource) {
                        ready = extractOrFail(finalFile, destDir, entry, partFile, callback);
                        if (ready == null) {
                            lastErrorMessage = "Falha ao extrair arquivo compactado";
                            break;
                        }
                    }

                    notifyComplete(callback, ready);
                    return;

                } catch (IOException e) {
                    if (isCancelled || Thread.currentThread().isInterrupted()) {
                        notifyCancelled(callback);
                        return;
                    }
                    lastErrorMessage = e.getMessage();
                    if (attempt >= MAX_RETRIES) {
                        break;
                    }
                    sleepBeforeRetry(attempt);
                }
            }

            discardPart(partFile);
        }

        notifyError(callback, lastErrorMessage != null ? lastErrorMessage : "Falha após tentativas em todas as fontes disponíveis");
    }

    /**
     * Abre o comprimido que acabou de chegar. Devolve o arquivo pronto, ou {@code null} depois de
     * ja ter notificado erro/cancelamento.
     *
     * Roda na thread do download, entre o fim da transferencia e o {@code onComplete} -- por isso
     * `isCancelled` continua sendo a mesma flag do laco de cima.
     *
     * <b>Falha apaga o comprimido.</b> Um `.7z` deixado no disco e contado por
     * {@link CatalogParser#markDownloaded} como jogo baixado -- ele casa por nome sem extensao --
     * e o emulador nao abre aquilo: e o defeito da TASK-0045 de volta por outra porta. O usuario
     * perde a transferencia, mas nao ganha uma linha "baixada" que nao roda.
     */
    private File extractOrFail(File archive, File destDir, CatalogEntry entry, File tempFile,
                               DownloadCallback callback) {
        // Antes de abrir o arquivo: o tamanho descomprimido ainda e desconhecido, e este zero e o
        // que faz a tela trocar para a barra indeterminada de "Extracting…".
        postToMain(() -> callback.onExtracting(0, 0));
        try {
            return RomArchiveExtractor.extract(archive, destDir, entry.fileName, tempFile,
                    new RomArchiveExtractor.Progress() {
                        @Override
                        public void onProgress(long bytesExtracted, long totalBytes) {
                            postToMain(() -> callback.onExtracting(bytesExtracted, totalBytes));
                        }

                        @Override
                        public boolean isCancelled() {
                            return isCancelled || Thread.currentThread().isInterrupted();
                        }
                    });
        } catch (RomArchiveExtractor.CancelledException e) {
            archive.delete();
            discardPart(tempFile);
            notifyCancelled(callback);
            return null;
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            // RuntimeException tambem: um cabecalho corrompido faz o commons-compress estourar
            // IllegalArgumentException/IndexOutOfBounds em vez de IOException. E OutOfMemoryError
            // porque o dicionario do LZMA2 e alocado no heap Java e pode passar de 64 MB. Deixar
            // qualquer um dos tres subir mata a thread do download SEM callback nenhum: a entrada
            // fica DOWNLOADING para sempre, porque so `onError`/`onCancelled` a tiram desse estado.
            archive.delete();
            discardPart(tempFile);
            notifyError(callback, e.getMessage());
            return null;
        }
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

package com.armsx2.updates;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

import com.armsx2.BuildConfig;

/**
 * Atualização in-app: consulta um version.json publicado ao lado do APK,
 * baixa a nova versão e instala.
 *
 * O version.json é gerado e publicado pelo build-and-upload.ps1, então ele
 * nunca fica dessincronizado do APK que está no ar.
 *
 * O SHA-256 vindo do version.json é conferido antes de instalar. Isso é o que
 * impede o cenário que originou esta feature: download grande truncado em rede
 * móvel virando "o pacote parece ser inválido" na cara do usuário. Hash errado
 * = apaga e tenta de novo, o instalador nunca vê um APK corrompido.
 *
 * Callbacks sempre entregues na thread principal.
 */
public class AppUpdateManager {

    private static final String TAG = "AppUpdateManager";

    /** Descreve a versão publicada no servidor. */
    public static class UpdateInfo {
        public final int versionCode;
        public final String versionName;
        public final String apkUrl;
        public final String channel;
        /** SHA-256 esperado do APK; vazio se o servidor não publicou. */
        public final String sha256;
        /** Tamanho esperado em bytes; 0 se o servidor não publicou. */
        public final long size;

        UpdateInfo(int versionCode, String versionName, String apkUrl,
                   String channel, String sha256, long size) {
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.apkUrl      = apkUrl;
            this.channel     = channel;
            this.sha256      = sha256;
            this.size        = size;
        }
    }

    public interface CheckCallback {
        void onUpdateAvailable(UpdateInfo info);
        void onUpToDate();
        void onError(String message);
    }

    public interface InstallCallback {
        void onProgress(long bytesDownloaded, long totalBytes);
        /** Entregue ao instalador; a partir daqui quem manda é o sistema. */
        void onInstallStarted();
        void onError(String message);
    }

    private static final int  CONNECT_TIMEOUT_MS   = 15_000;
    private static final int  READ_TIMEOUT_MS      = 120_000;
    private static final int  BUFFER_SIZE          = 65_536;
    private static final int  MAX_RETRIES          = 3;
    private static final long PROGRESS_THROTTLE_MS = 500;

    private static final String PREFS               = "armsx2";
    private static final String PREF_LAST_CHECK_MS  = "update_last_check_ms";
    private static final String PREF_SKIPPED_CODE   = "update_skipped_version_code";
    /** Não incomoda o usuário mais de uma vez a cada 12h. */
    private static final long   CHECK_INTERVAL_MS   = 12L * 60L * 60L * 1000L;

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private long lastProgressMs = 0;

    public AppUpdateManager(Context context) {
        this.context = context.getApplicationContext();
    }

    // ---------------------------------------------------------------- check

    /**
     * True se já passou tempo suficiente desde a última checagem.
     * A verificação em si é barata, mas evita reabrir o diálogo a cada launch.
     */
    public boolean shouldCheckNow() {
        long last = prefs().getLong(PREF_LAST_CHECK_MS, 0L);
        return System.currentTimeMillis() - last >= CHECK_INTERVAL_MS;
    }

    public void markChecked() {
        prefs().edit().putLong(PREF_LAST_CHECK_MS, System.currentTimeMillis()).apply();
    }

    /** Usuário escolheu "Depois": não perguntar de novo por essa versão. */
    public void skipVersion(int versionCode) {
        prefs().edit().putInt(PREF_SKIPPED_CODE, versionCode).apply();
    }

    public boolean isVersionSkipped(int versionCode) {
        return prefs().getInt(PREF_SKIPPED_CODE, -1) == versionCode;
    }

    /** Consulta o version.json em background. */
    public void checkForUpdate(final CheckCallback callback) {
        new Thread(() -> {
            try {
                UpdateInfo info = fetchVersionInfo();
                markChecked();
                if (info.versionCode <= BuildConfig.VERSION_CODE) {
                    Log.d(TAG, "App atualizado (local=" + BuildConfig.VERSION_CODE
                            + ", remoto=" + info.versionCode + ")");
                    mainHandler.post(callback::onUpToDate);
                } else {
                    Log.d(TAG, "Update disponivel: " + info.versionName
                            + " (code " + info.versionCode + ")");
                    mainHandler.post(() -> callback.onUpdateAvailable(info));
                }
            } catch (Exception e) {
                final String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                Log.w(TAG, "Falha ao checar atualizacao: " + msg);
                mainHandler.post(() -> callback.onError(msg));
            }
        }, "AppUpdateCheck").start();
    }

    private UpdateInfo fetchVersionInfo() throws IOException {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(BuildConfig.APP_UPDATE_ENDPOINT);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(30_000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", userAgent());
            // O version.json é servido pelo mesmo CDN do APK; sem isso um
            // cache intermediário pode devolver a versão anterior por horas.
            conn.setRequestProperty("Cache-Control", "no-cache");
            conn.connect();

            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code);
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
            }

            JSONObject json = new JSONObject(sb.toString());

            String channel = json.optString("channel", BuildConfig.APP_UPDATE_CHANNEL);
            if (!channel.equalsIgnoreCase(BuildConfig.APP_UPDATE_CHANNEL)) {
                throw new IOException("Canal diferente: esperado "
                        + BuildConfig.APP_UPDATE_CHANNEL + ", recebido " + channel);
            }

            return new UpdateInfo(
                    json.getInt("versionCode"),
                    json.getString("versionName"),
                    json.getString("apkUrl"),
                    channel,
                    json.optString("sha256", ""),
                    json.optLong("size", 0L));
        } catch (org.json.JSONException e) {
            throw new IOException("version.json invalido: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ------------------------------------------------------- download+install

    /**
     * True se o usuário já autorizou este app a instalar APKs.
     * Sem isso o Android 8+ recusa a instalação, então checamos antes de baixar
     * 30 MB à toa.
     */
    public boolean canInstallPackages() {
        return context.getPackageManager().canRequestPackageInstalls();
    }

    /** Abre a tela do sistema onde o usuário libera "instalar apps desconhecidos". */
    public Intent buildAllowInstallIntent() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
        intent.setData(Uri.parse("package:" + context.getPackageName()));
        return intent;
    }

    public void downloadAndInstall(final UpdateInfo info, final InstallCallback callback) {
        new Thread(() -> {
            // externalCacheDir, nao cacheDir: e o unico caminho que o FileProvider do flavor
            // github publica (`update_paths.xml` -> <external-cache-path name="updates">). Usar o
            // cache interno faria o getUriForFile do fallback lancar
            // IllegalArgumentException("Failed to find configured root"), e so no fallback -- ou
            // seja, so quando o PackageInstaller ja tivesse falhado, que e o pior momento.
            File cacheRoot = context.getExternalCacheDir();
            if (cacheRoot == null) cacheRoot = context.getCacheDir();
            File apk = new File(cacheRoot, "updates/retrosystem-ps2-update.apk");
            try {
                File dir = apk.getParentFile();
                if (dir != null && !dir.exists() && !dir.mkdirs()) {
                    throw new IOException("Nao foi possivel criar " + dir.getAbsolutePath());
                }

                downloadWithRetries(info, apk, callback);

                mainHandler.post(callback::onInstallStarted);
                installApk(apk);
            } catch (Exception e) {
                if (apk.exists()) apk.delete();
                final String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                Log.w(TAG, "Falha ao atualizar: " + msg);
                mainHandler.post(() -> callback.onError(msg));
            }
        }, "AppUpdateInstall").start();
    }

    private void downloadWithRetries(UpdateInfo info, File dest, InstallCallback callback)
            throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                downloadApk(info, dest, callback);
                verifyApk(info, dest);
                return;
            } catch (IOException e) {
                last = e;
                // Hash/tamanho errado significa que o .part está envenenado —
                // resume só propagaria o lixo, então recomeça do zero.
                if (dest.exists()) dest.delete();
                File part = partFile(dest);
                if (part.exists()) part.delete();
                if (attempt >= MAX_RETRIES) break;
                Log.w(TAG, "Tentativa " + attempt + " falhou (" + e.getMessage() + "), repetindo");
                try { Thread.sleep(1000L * attempt); } catch (InterruptedException ignored) {}
            }
        }
        throw last != null ? last : new IOException("Download falhou");
    }

    private static File partFile(File dest) {
        return new File(dest.getAbsolutePath() + ".part");
    }

    private void downloadApk(UpdateInfo info, File dest, InstallCallback callback)
            throws IOException {
        File part = partFile(dest);
        long existing = part.exists() ? part.length() : 0;

        HttpURLConnection conn = null;
        try {
            URL url = new URL(info.apkUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", userAgent());
            if (existing > 0) {
                conn.setRequestProperty("Range", "bytes=" + existing + "-");
            }
            conn.connect();

            int code = conn.getResponseCode();
            long contentLength = conn.getContentLengthLong();

            boolean resuming;
            long written;
            long total;

            if (code == HttpURLConnection.HTTP_PARTIAL) {
                resuming = true;
                written  = existing;
                total    = existing + (contentLength > 0 ? contentLength : 0);
            } else if (code == HttpURLConnection.HTTP_OK) {
                // Servidor ignorou o Range — recomeça do zero.
                resuming = false;
                written  = 0;
                total    = contentLength > 0 ? contentLength : 0;
                if (part.exists()) part.delete();
            } else {
                throw new IOException("HTTP " + code);
            }

            if (total <= 0 && info.size > 0) total = info.size;

            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(part, resuming)) {
                byte[] buf = new byte[BUFFER_SIZE];
                int read;
                while ((read = in.read(buf)) != -1) {
                    out.write(buf, 0, read);
                    written += read;

                    long now = System.currentTimeMillis();
                    if (now - lastProgressMs >= PROGRESS_THROTTLE_MS) {
                        lastProgressMs = now;
                        final long w = written;
                        final long t = total;
                        mainHandler.post(() -> callback.onProgress(w, t));
                    }
                }
                out.flush();
            }
        } finally {
            if (conn != null) conn.disconnect();
        }

        if (dest.exists() && !dest.delete()) {
            throw new IOException("Nao foi possivel substituir " + dest.getName());
        }
        if (!part.renameTo(dest)) {
            throw new IOException("Nao foi possivel finalizar o download");
        }
    }

    /**
     * Confere tamanho e SHA-256 contra o que o servidor anunciou.
     * É o que garante que um download truncado nunca chega ao instalador.
     */
    private void verifyApk(UpdateInfo info, File apk) throws IOException {
        if (info.size > 0 && apk.length() != info.size) {
            throw new IOException("Download incompleto: " + apk.length()
                    + " bytes, esperado " + info.size);
        }
        if (info.sha256 == null || info.sha256.isEmpty()) {
            Log.w(TAG, "version.json sem sha256 — integridade nao verificada");
            return;
        }
        String actual = sha256Of(apk);
        if (!actual.equalsIgnoreCase(info.sha256)) {
            throw new IOException("Arquivo corrompido (SHA-256 nao confere)");
        }
        Log.d(TAG, "SHA-256 confere: " + actual);
    }

    private static String sha256Of(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new FileInputStream(file)) {
                byte[] buf = new byte[BUFFER_SIZE];
                int read;
                while ((read = in.read(buf)) != -1) digest.update(buf, 0, read);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 indisponivel", e);
        }
    }

    // --------------------------------------------------------------- install

    private void installApk(File apk) throws IOException {
        try {
            installViaPackageInstaller(apk);
        } catch (Exception e) {
            Log.w(TAG, "PackageInstaller falhou (" + e.getMessage() + "), tentando via Intent");
            installViaIntent(apk);
        }
    }

    private void installViaPackageInstaller(File apk) throws IOException {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }

        int sessionId = installer.createSession(params);
        PackageInstaller.Session session = installer.openSession(sessionId);
        try {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = session.openWrite("retrosystem-ps2.apk", 0, apk.length())) {
                byte[] buf = new byte[BUFFER_SIZE];
                int read;
                while ((read = in.read(buf)) != -1) out.write(buf, 0, read);
                out.flush();
                session.fsync(out);
            }

            Intent callbackIntent = new Intent(context, UpdateInstallReceiver.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags |= PendingIntent.FLAG_MUTABLE;
            }
            PendingIntent pending = PendingIntent.getBroadcast(
                    context, sessionId, callbackIntent, flags);
            session.commit(pending.getIntentSender());
            Log.d(TAG, "Sessao de instalacao enviada (id=" + sessionId + ")");
        } finally {
            session.close();
        }
    }

    private void installViaIntent(File apk) {
        Uri uri = FileProvider.getUriForFile(
                context, context.getPackageName() + ".updateprovider", apk);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    // ----------------------------------------------------------------- utils

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String userAgent() {
        return "RetroSystemPS2/" + BuildConfig.VERSION_NAME
                + " (Android; " + BuildConfig.APP_UPDATE_CHANNEL + ")";
    }
}

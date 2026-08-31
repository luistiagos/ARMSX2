/*

By MoonPower (Momo-AUX1) GPLv3 License
   This file is part of ARMSX2.

   ARMSX2 is free software: you can redistribute it and/or modify
   it under the terms of the GNU General Public License as published by
   the Free Software Foundation, either version 3 of the License, or
   (at your option) any later version.

*/

package com.armsx2.telemetry;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.RequiresApi;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CrashReporter {
    private static final String TAG = "CrashReporter";
    private static final String UPLOAD_URL = "https://emuladores.pythonanywhere.com/upload_log";
    private static final String PROJECT_NAME = "ARMSX2";
    private static final String DIR_NAME = "pending_crashes";
    private static final int LOGCAT_LINES = 800;
    private static final long ANR_TIMEOUT_MS = 5000L;
    private static final long ANR_PING_INTERVAL_MS = 1000L;

    // Native-crash telemetry via ApplicationExitInfo (API 30+). Watermark avoids re-reporting.
    // Arquivo de prefs do fork -- caixa alta, como o app do upstream usa. Diferente do
    // "armsx2" da linha anterior, e de proposito: a unica chave aqui e uma marca d'agua de
    // "ate quando ja reportei saidas nativas", e comecar do zero custa, no maximo, um relato
    // repetido de um crash antigo na primeira execucao.
    private static final String PREFS_NAME = "ARMSX2";
    private static final String PREF_LAST_EXIT_TS = "telemetry_last_exit_ts";
    private static final int MAX_TRACE_BYTES = 512 * 1024;
    // First backtrace frame in a tombstone, e.g. "  #00 pc 000abcde  /path/libemucore.so (Sym+12)".
    // The symbol group is anchored to end at "+<offset>)" so C++ names containing their own
    // parentheses (e.g. "recRecompile(unsigned int)+340") aren't truncated at the first ')'.
    private static final Pattern TOMBSTONE_FRAME =
            Pattern.compile("#\\d+\\s+pc\\s+\\w+\\s+(\\S+)(?:\\s+\\((.+?\\+[\\dxa-f]+)\\))?");

    private static volatile Context sAppContext;
    private static volatile Thread.UncaughtExceptionHandler sPrevHandler;
    private static volatile boolean sInstalled = false;
    private static Thread sWatchdogThread;
    private static final AtomicBoolean sAnrInFlight = new AtomicBoolean(false);

    private CrashReporter() {}

    public static synchronized void install(Context context) {
        if (sInstalled || context == null) return;
        sAppContext = context.getApplicationContext();
        sPrevHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try { writeCrash("java", t, e); } catch (Throwable ignored) {}
            if (sPrevHandler != null) sPrevHandler.uncaughtException(t, e);
        });
        sInstalled = true;
    }

    public static synchronized void installAnrWatchdog() {
        if (sWatchdogThread != null && sWatchdogThread.isAlive()) return;
        sWatchdogThread = new Thread(CrashReporter::watchdogLoop, "ARMSX2-AnrWatchdog");
        sWatchdogThread.setDaemon(true);
        sWatchdogThread.start();
    }

    public static void uploadPendingAsync() {
        Thread t = new Thread(CrashReporter::uploadPending, "ARMSX2-CrashUpload");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Reports native crashes from the previous session(s) to the /logErr triage service as
     * {@code armsx2/native}. Native SIGSEGV/SIGABRT in the JIT never reach the Java uncaught handler
     * (the process is killed by the OS), so we recover them on the next boot via the official
     * {@link ApplicationExitInfo} API (API 30+), whose {@code getTraceInputStream()} returns the
     * native tombstone. Best-effort, off the main thread, never throws.
     */
    public static void reportNativeExitsAsync() {
        Thread t = new Thread(CrashReporter::reportNativeExits, "ARMSX2-NativeExitScan");
        t.setDaemon(true);
        t.start();
    }

    private static void reportNativeExits() {
        if (sAppContext == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        try {
            ActivityManager am = (ActivityManager) sAppContext.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return;
            List<ApplicationExitInfo> exits =
                    am.getHistoricalProcessExitReasons(sAppContext.getPackageName(), 0, 0);
            if (exits == null || exits.isEmpty()) return;

            SharedPreferences sp = sAppContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            long watermark = sp.getLong(PREF_LAST_EXIT_TS, 0L);
            long maxTs = watermark;
            for (ApplicationExitInfo info : exits) {
                long ts = info.getTimestamp();
                if (ts > maxTs) maxTs = ts;
                if (ts <= watermark) continue; // already processed in a prior session
                if (info.getReason() != ApplicationExitInfo.REASON_CRASH_NATIVE) continue;
                reportOneNativeExit(info);
            }
            // Advance past everything we saw so old exits are never reprocessed.
            sp.edit().putLong(PREF_LAST_EXIT_TS, maxTs).apply();
        } catch (Throwable t) {
            Log.w(TAG, "reportNativeExits failed", t);
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private static void reportOneNativeExit(ApplicationExitInfo info) {
        try {
            byte[] raw = readTraceBytes(info);

            String message = "Native crash";
            String file = "native";
            String method = "signal " + safeStatus(info);
            String trace;

            TombstoneParser.Result parsed = TombstoneParser.parse(raw);
            if (parsed != null) {
                // Android 12+: decoded protobuf tombstone.
                trace = TombstoneParser.format(parsed);
                message = "Native crash — signal " + parsed.signalNumber
                        + (parsed.signalName.isEmpty() ? "" : " (" + parsed.signalName + ")");
                if (!parsed.abortMessage.isEmpty()) message += " — abort: " + parsed.abortMessage;
                if (!parsed.backtrace.isEmpty()) {
                    TombstoneParser.Frame top = parsed.backtrace.get(0);
                    if (!top.fileName.isEmpty()) {
                        int slash = top.fileName.lastIndexOf('/');
                        file = slash >= 0 ? top.fileName.substring(slash + 1) : top.fileName;
                    }
                    if (!top.functionName.isEmpty()) method = top.functionName + "+" + top.functionOffset;
                }
            } else if (TombstoneParser.looksLikeText(raw)) {
                // Legacy text tombstone (Android 11) — keep the original regex extraction.
                trace = new String(raw, StandardCharsets.UTF_8);
                String signalLine = firstMatch(trace, "signal ");
                if (!signalLine.isEmpty()) message += " — " + signalLine.trim();
                else if (info.getDescription() != null) message += ": " + info.getDescription();
                Matcher m = TOMBSTONE_FRAME.matcher(trace);
                if (m.find()) {
                    String path = m.group(1);
                    if (path != null) {
                        int slash = path.lastIndexOf('/');
                        file = slash >= 0 ? path.substring(slash + 1) : path;
                    }
                    if (m.group(2) != null && !m.group(2).isEmpty()) method = m.group(2);
                }
            } else {
                // Undecodable binary — never ship the raw blob.
                int n = raw == null ? 0 : raw.length;
                message += " — tombstone protobuf não decodificado (" + n + " bytes)";
                if (info.getDescription() != null) message += "; " + info.getDescription();
                trace = "";
            }

            // A linha de boot do GS e da sessao ATUAL, nao da que morreu -- o processo anterior
            // levou a dele consigo. Ainda assim vale mais que nada: o crash nativo e recuperado no
            // boot seguinte, antes de qualquer jogo abrir, entao um valor presente aqui significa
            // que o GS ja reabriu e descreve o mesmo aparelho e o mesmo driver.
            String gsBoot = TelemetryReporter.getGraphicsBootSummary();
            String context = "pid=" + info.getPid()
                    + "; importance=" + info.getImportance()
                    + "; when=" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(info.getTimestamp()))
                    + "; app_version=" + safeVersion()
                    + "; device=" + Build.MANUFACTURER + " " + Build.MODEL
                    + (gsBoot.isEmpty() ? "" : "; gsboot=" + gsBoot);

            String[] logs = trace.isEmpty()
                    ? new String[]{String.valueOf(info.getDescription())}
                    : new String[]{trace};

            // Not a terminal path (previous process already died) — send async so boot isn't blocked.
            TelemetryReporter.report("native", file, method, message, context, logs, /*terminal=*/false);
        } catch (Throwable t) {
            Log.w(TAG, "reportOneNativeExit failed", t);
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private static String safeStatus(ApplicationExitInfo info) {
        try {
            return String.valueOf(info.getStatus());
        } catch (Throwable t) {
            return "?";
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private static byte[] readTraceBytes(ApplicationExitInfo info) {
        try (InputStream is = info.getTraceInputStream()) {
            if (is == null) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            int total = 0;
            while (total < MAX_TRACE_BYTES && (r = is.read(buf)) != -1) {
                bos.write(buf, 0, r);
                total += r;
            }
            return bos.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String firstMatch(String haystack, String needle) {
        if (haystack == null || haystack.isEmpty()) return "";
        for (String line : haystack.split("\n")) {
            if (line.contains(needle)) return line;
        }
        return "";
    }

    private static void watchdogLoop() {
        Handler main = new Handler(Looper.getMainLooper());
        // grace period — let App.onCreate + native init finish before watching
        try {
            Thread.sleep(15000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(ANR_PING_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            final CountDownLatch response = new CountDownLatch(1);
            main.post(response::countDown);
            try {
                if (!response.await(ANR_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    // CountDownLatch.countDown() never waits for the watchdog. If the looper
                    // recovers during capture, its probe cannot become the blocked stack itself.
                    captureAnr(main.getLooper().getThread());
                    // Wait until main recovers before re-arming, preventing repeated reports.
                    response.await();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void captureAnr(Thread mainThread) {
        if (!sAnrInFlight.compareAndSet(false, true)) return;
        try {
            StackTraceElement[] trace = mainThread.getStackTrace();
            Throwable synthetic = new Throwable("ANR: main thread unresponsive >" + ANR_TIMEOUT_MS + "ms");
            synthetic.setStackTrace(trace);
            writeCrash("anr", mainThread, synthetic);
        } catch (Throwable t) {
            Log.w(TAG, "captureAnr failed", t);
        } finally {
            sAnrInFlight.set(false);
        }
    }

    private static File pendingDir() {
        File f = new File(sAppContext.getFilesDir(), DIR_NAME);
        if (!f.exists() && !f.mkdirs()) {
            Log.w(TAG, "mkdir failed: " + f);
        }
        return f;
    }

    private static void writeCrash(String kind, Thread t, Throwable e) {
        if (sAppContext == null || !TelemetryReporter.isEnabled()) return;
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File out = new File(pendingDir(), PROJECT_NAME + "_" + ts + "_" + kind + ".txt");
        String logcatTail = captureLogcat();
        // Report a compact summary to the /logErr triage service (separate from the file upload
        // below). Best-effort, synchronous (terminal path), never throws. Done first so it still
        // fires even if the local file write fails.
        try { TelemetryReporter.reportCrash(kind, t, e, logcatTail); } catch (Throwable ignored) {}
        try (PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(out)))) {
            pw.println("=== " + PROJECT_NAME + " Crash Report ===");
            pw.println("kind: " + kind);
            pw.println("timestamp: " + ts);
            pw.println("thread: " + (t != null ? t.getName() : "<null>"));
            pw.println("app_version: " + safeVersion());
            pw.println("package: " + sAppContext.getPackageName());
            pw.println("device: " + Build.MANUFACTURER + " " + Build.MODEL);
            pw.println("android: " + Build.VERSION.RELEASE + " (sdk " + Build.VERSION.SDK_INT + ")");
            pw.println("abi: " + Arrays.toString(Build.SUPPORTED_ABIS));
            String gsBoot = TelemetryReporter.getGraphicsBootSummary();
            if (!gsBoot.isEmpty()) pw.println("gsboot: " + gsBoot);
            pw.println();
            pw.println("=== Stack ===");
            if (e != null) e.printStackTrace(pw);
            pw.println();
            pw.println("=== All Threads ===");
            for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
                Thread th = entry.getKey();
                pw.println("-- " + th.getName() + " (" + th.getState() + ", prio=" + th.getPriority() + ", daemon=" + th.isDaemon() + ")");
                for (StackTraceElement el : entry.getValue()) pw.println("    at " + el);
            }
            pw.println();
            pw.println("=== Logcat tail ===");
            pw.print(logcatTail);
        } catch (IOException io) {
            Log.e(TAG, "writeCrash failed", io);
        }
    }

    private static String safeVersion() {
        try {
            return sAppContext.getPackageManager().getPackageInfo(sAppContext.getPackageName(), 0).versionName;
        } catch (Throwable e) {
            return "unknown";
        }
    }

    private static String captureLogcat() {
        Process p = null;
        try {
            p = new ProcessBuilder("logcat", "-d", "-t", String.valueOf(LOGCAT_LINES), "-v", "threadtime")
                    .redirectErrorStream(true)
                    .start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            return sb.toString();
        } catch (Throwable e) {
            return "logcat capture failed: " + e + "\n";
        } finally {
            if (p != null) {
                try { p.destroy(); } catch (Throwable ignored) {}
            }
        }
    }

    private static void uploadPending() {
        if (sAppContext == null || !TelemetryReporter.isEnabled()) return;
        File dir = pendingDir();
        File[] files = dir.listFiles((f, name) -> name.endsWith(".txt"));
        if (files == null || files.length == 0) return;
        for (File f : files) {
            if (!TelemetryReporter.isEnabled()) return;
            if (postFile(f)) {
                if (!f.delete()) Log.w(TAG, "delete failed: " + f);
            } else {
                Log.w(TAG, "upload failed, will retry next boot: " + f.getName());
            }
        }
    }

    private static boolean postFile(File f) {
        if (!TelemetryReporter.isEnabled()) return false;
        String boundary = "----CrashReporter" + System.currentTimeMillis();
        HttpURLConnection conn = null;
        try {
            URL url = new URL(UPLOAD_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setUseCaches(false);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            try (DataOutputStream out = new DataOutputStream(conn.getOutputStream())) {
                out.writeBytes("--" + boundary + "\r\n");
                out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"" + f.getName() + "\"\r\n");
                out.writeBytes("Content-Type: text/plain\r\n\r\n");
                try (FileInputStream fis = new FileInputStream(f)) {
                    byte[] buf = new byte[8192];
                    int r;
                    while ((r = fis.read(buf)) != -1) out.write(buf, 0, r);
                }
                out.writeBytes("\r\n--" + boundary + "--\r\n");
            }
            int code = conn.getResponseCode();
            return code >= 200 && code < 300;
        } catch (Throwable e) {
            Log.w(TAG, "postFile failed " + f.getName() + ": " + e);
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}

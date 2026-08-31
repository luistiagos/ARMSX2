/*

By MoonPower (Momo-AUX1) GPLv3 License
   This file is part of ARMSX2.

   ARMSX2 is free software: you can redistribute it and/or modify
   it under the terms of the GNU General Public License as published by
   the Free Software Foundation, either version 3 of the License, or
   (at your option) any later version.

*/

package com.armsx2.telemetry;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Error telemetry for the DigitalStoreGames /logErr triage service.
 *
 * <p>Structured, best-effort error reporting that feeds the shared admin error panel and the
 * {@code triagem-bugs-prod} agent. This is SEPARATE from {@link CrashReporter}, which uploads full
 * crash files to a private endpoint; this class posts compact, deduplicated summaries keyed by the
 * {@code armsx2/<component>} project identifier.</p>
 *
 * <p><b>Inviolable rule:</b> telemetry must never change app behavior. Every path is wrapped in
 * try/catch, network failures are swallowed, timeouts are short, and the same error is not re-sent
 * within a session. Offline is tolerated (the report is simply lost, like the reference client).</p>
 *
 * <p>Kill-switch: SharedPreferences {@code "ARMSX2"} / {@code "telemetry_error_reporting"} (bool,
 * default {@code true}); optional endpoint override in {@code "telemetry_endpoint"}.</p>
 */
public final class TelemetryReporter {
    private static final String PROJECT_BASE = "armsx2";
    private static final String DEFAULT_ENDPOINT = "https://digitalstoregames.pythonanywhere.com/logErr";

    /**
     * O arquivo de SharedPreferences do fork. Repare na CAIXA: o app do upstream usa
     * {@code "ARMSX2"}, enquanto a nossa linha anterior usava {@code "armsx2"}. Nome de
     * SharedPreferences e nome de ARQUIVO, portanto case-sensitive -- sao dois arquivos distintos e
     * nada falha ao trocar, o valor simplesmente aparece como ausente.
     */
    private static final String PREFS_NAME = "ARMSX2";
    /**
     * O arquivo da linha anterior, lido SO para nao ressuscitar telemetria que o usuario desligou.
     * Ver {@link #init(Context)}: um opt-out explicito la vale mais que o default daqui.
     */
    private static final String LEGACY_PREFS_NAME = "armsx2";
    private static final String PREF_ENABLED = "telemetry_error_reporting";
    private static final String PREF_ENDPOINT = "telemetry_endpoint";

    // Client-imposed limits (mirror the reference contract).
    private static final int MAX_MESSAGE = 4000;
    private static final int MAX_LOG_BYTES = 256 * 1024;
    private static final int MAX_LOGS = 20;
    private static final int TIMEOUT_MS = 5000;

    private static volatile Context sAppContext;
    private static volatile boolean sEnabled = true;
    private static volatile String sEndpoint = DEFAULT_ENDPOINT;

    // In-process dedup: hash(component + "|" + message) already sent this session.
    private static final Set<Integer> sSeen = Collections.synchronizedSet(new HashSet<>());

    private TelemetryReporter() {}

    /** Reads the kill-switch/endpoint config. Safe to call before native init. Never throws. */
    public static void init(Context context) {
        try {
            if (context == null) return;
            sAppContext = context.getApplicationContext();
            SharedPreferences sp = sAppContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            sEnabled = sp.getBoolean(PREF_ENABLED, true);
            String ep = sp.getString(PREF_ENDPOINT, "");

            // Um usuario que DESLIGOU a telemetria na 1.0.23 gravou isso no arquivo antigo. Como o
            // fork le outro arquivo, o default (ligado) o ressuscitaria em silencio -- e desligar
            // telemetria e escolha de privacidade, nao preferencia de UI. Entao: se o arquivo do
            // fork ainda nao tem valor explicito e o antigo diz "false", o "false" vence.
            //
            // So nesse sentido. Um "true" antigo nao e propagado, porque ele e indistinguivel do
            // default e nao carrega intencao nenhuma.
            if (!sp.contains(PREF_ENABLED)) {
                SharedPreferences legacy =
                        sAppContext.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE);
                if (legacy.contains(PREF_ENABLED) && !legacy.getBoolean(PREF_ENABLED, true)) {
                    sEnabled = false;
                }
                if (TextUtils.isEmpty(ep)) ep = legacy.getString(PREF_ENDPOINT, "");
            }
            sEndpoint = TextUtils.isEmpty(ep) ? DEFAULT_ENDPOINT : ep;
        } catch (Throwable ignored) {
            // default = on, default endpoint
        }
    }

    public static boolean isEnabled() {
        return sEnabled;
    }

    /** Updates and persists the kill-switch. The in-memory value changes before disk I/O. */
    public static void setEnabled(boolean enabled) {
        sEnabled = enabled;
        try {
            Context context = sAppContext;
            if (context == null) return;
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(PREF_ENABLED, enabled)
                    .apply();
        } catch (Throwable ignored) {
            // Keep the requested in-memory privacy state even if persistence fails.
        }
    }

    /**
     * Ultima linha de diagnostico de boot do GS ({@code Host::ReportGraphicsBootDiagnostics}).
     * Anexada a todo relato de crash: um tombstone sem GPU, driver e renderer nao e diagnosticavel,
     * e foi essa ausencia que fez quatro rodadas de correcao grafica serem feitas as cegas. Fica
     * aqui, e nao em NativeApp, para que o caminho de crash nao dependa da biblioteca nativa ter
     * carregado -- ele roda justamente quando ela morreu.
     */
    private static volatile String sGraphicsBootSummary = "";

    public static void setGraphicsBootSummary(String summary) {
        sGraphicsBootSummary = TextUtils.isEmpty(summary) ? "" : summary;
    }

    /** Vazio ate o GS abrir pela primeira vez nesta sessao. */
    public static String getGraphicsBootSummary() {
        return sGraphicsBootSummary;
    }

    /**
     * Reports a Java crash/ANR to /logErr. {@code kind} becomes the project component
     * ({@code armsx2/<kind>}). Sent synchronously because crash/ANR paths are terminal — a detached
     * thread would be killed before it completes. Never throws.
     */
    public static void reportCrash(String kind, Thread thread, Throwable e, String logcatTail) {
        try {
            String message = describe(e);
            StackTraceElement top = topFrame(e);
            String file = top != null && top.getFileName() != null ? top.getFileName()
                    : (top != null ? top.getClassName() : "unknown");
            String method = top != null ? top.getClassName() + "." + top.getMethodName() : null;
            String gsBoot = sGraphicsBootSummary;
            String context = "thread=" + (thread != null ? thread.getName() : "?")
                    + "; pkg=" + safePackage() + "; ver=" + safeVersion()
                    + "; device=" + Build.MANUFACTURER + " " + Build.MODEL
                    + (TextUtils.isEmpty(gsBoot) ? "" : "; gsboot=" + gsBoot);
            String[] logs = TextUtils.isEmpty(logcatTail)
                    ? new String[]{stackToString(e)}
                    : new String[]{stackToString(e), logcatTail};
            report(kind, file, method, message, context, logs, /*terminal=*/true);
        } catch (Throwable ignored) {
            // telemetry never affects app behavior
        }
    }

    /**
     * General report. {@code component} becomes {@code armsx2/<component>}. {@code terminal} = send
     * synchronously (crash paths — a background thread would be killed); otherwise send on a detached
     * daemon thread so the caller is never blocked. Never throws.
     */
    public static void report(String component, String file, String method, String message,
                              String contextPath, String[] logs, boolean terminal) {
        try {
            if (!sEnabled) return;
            final String comp = TextUtils.isEmpty(component) ? "android" : component;
            final String msg = message == null ? "" : message;
            if (!sSeen.add((comp + "|" + msg).hashCode())) return; // dedup within session

            JSONObject body = new JSONObject();
            body.put("project", PROJECT_BASE + "/" + comp);
            body.put("file", TextUtils.isEmpty(file) ? "unknown" : file);
            body.put("method", TextUtils.isEmpty(method) ? comp : method);
            body.put("message", cap(msg, MAX_MESSAGE));
            body.put("user_agent", "ARMSX2/" + comp + " " + safeVersion());
            body.put("platform", "Android " + Build.VERSION.RELEASE + " (sdk " + Build.VERSION.SDK_INT + ")");
            body.put("screen", "");
            body.put("page_url", contextPath == null ? "" : contextPath);

            JSONArray arr = new JSONArray();
            if (logs != null) {
                int n = Math.min(logs.length, MAX_LOGS);
                for (int i = 0; i < n; i++) {
                    if (TextUtils.isEmpty(logs[i])) continue;
                    arr.put(capTail(logs[i], MAX_LOG_BYTES));
                }
            }
            body.put("logs", arr);

            if (terminal) {
                send(body);
            } else {
                Thread t = new Thread(() -> send(body), "ARMSX2-Telemetry");
                t.setDaemon(true);
                t.start();
            }
        } catch (Throwable ignored) {
            // telemetry never affects app behavior
        }
    }

    private static void send(JSONObject body) {
        // Needs proper device testing: a request already inside HttpURLConnection cannot be revoked.
        if (!sEnabled) return;
        try {
            // Keep adapter enumeration off the caller thread for non-terminal reports.
            body.put("network_adapters", NetworkAdapterCollector.collect(sAppContext));
        } catch (Throwable ignored) {
            // The report remains useful even when a vendor network stack rejects the query.
        }
        if (!sEnabled) return;
        if (postJson(body)) return;
        if (sEnabled) getFallback(body); // POST failed (proxy/old server); retry via GET without logs
    }

    private static boolean postJson(JSONObject body) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(sEndpoint).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setUseCaches(false);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }
            int code = conn.getResponseCode();
            return code >= 200 && code < 300;
        } catch (Throwable e) {
            return false;
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Throwable ignored) {}
            }
        }
    }

    private static void getFallback(JSONObject body) {
        HttpURLConnection conn = null;
        try {
            StringBuilder qs = new StringBuilder(sEndpoint).append('?');
            String[] keys = {"project", "file", "method", "message", "user_agent", "platform", "screen", "page_url"};
            boolean first = true;
            for (String k : keys) {
                String v = body.optString(k, "");
                if (!first) qs.append('&');
                first = false;
                qs.append(k).append('=').append(URLEncoder.encode(v, "UTF-8"));
            }
            conn = (HttpURLConnection) new URL(qs.toString()).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.getResponseCode();
        } catch (Throwable ignored) {
            // offline tolerated
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Throwable ignored) {}
            }
        }
    }

    private static String describe(Throwable e) {
        if (e == null) return "unknown error";
        String msg = e.getMessage();
        return TextUtils.isEmpty(msg) ? e.getClass().getName() : e.getClass().getName() + ": " + msg;
    }

    private static StackTraceElement topFrame(Throwable e) {
        if (e == null) return null;
        StackTraceElement[] st = e.getStackTrace();
        return (st != null && st.length > 0) ? st[0] : null;
    }

    private static String stackToString(Throwable e) {
        if (e == null) return "";
        StringWriter sw = new StringWriter();
        try (PrintWriter pw = new PrintWriter(sw)) {
            e.printStackTrace(pw);
        }
        return sw.toString();
    }

    private static String cap(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** Keep the TAIL — the end of a log is what matters for diagnosis. */
    private static String capTail(String s, int maxBytes) {
        if (s == null) return "";
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        if (b.length <= maxBytes) return s;
        String marker = "...[truncated " + (b.length - maxBytes) + " bytes]...\n";
        return marker + new String(b, b.length - maxBytes, maxBytes, StandardCharsets.UTF_8);
    }

    private static String safeVersion() {
        try {
            return sAppContext.getPackageManager().getPackageInfo(sAppContext.getPackageName(), 0).versionName;
        } catch (Throwable e) {
            return "unknown";
        }
    }

    private static String safePackage() {
        try {
            return sAppContext.getPackageName();
        } catch (Throwable e) {
            return "?";
        }
    }
}

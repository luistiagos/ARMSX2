# GUIA DE IMPLEMENTAÇÃO — Decodificar o tombstone protobuf antes de reportar (armsx2/native)

> **Para o implementador (modelo menor):** este guia é **autocontido**. Você NÃO precisa pesquisar
> nada na internet, no AOSP, nem em outros arquivos além dos citados. Todo o código necessário está
> escrito abaixo, pronto para copiar. Siga as seções na ordem. Não altere comportamento do app fora
> do que está descrito. Trabalhe **apenas** no checkout `E:\projects\play2\ARMSX2`.

---

## 1. O problema (resumo de 30 segundos)

Os 52 maiores erros abertos da telemetria (`armsx2/native`, SIGABRT) chegam com a mensagem e o log
preenchidos com **bytes binários crus** (100–300 KB de lixo por evento), impossíveis de ler.

**Causa raiz (confirmada no código):** em
[`CrashReporter.java`](../../../app/src/main/java/kr/co/iefriends/pcsx2/utils/CrashReporter.java), o
método `readTrace()` lê `ApplicationExitInfo.getTraceInputStream()` e faz `.toString("UTF-8")` **como
se fosse texto**. Para crashes nativos (`REASON_CRASH_NATIVE`) em **Android 12+**, esse stream NÃO é
texto — é um **protobuf serializado** (mensagem `Tombstone` do debuggerd). O regex que extrai o
stacktrace nunca casa, e o blob binário inteiro é enviado como `logs[0]`.

**A correção:** adicionar um parser protobuf mínimo (sem dependência nova) que decodifica o
`Tombstone` e produz **texto legível** (signal + abort message + backtrace), exatamente como o
debuggerd faria. Se o parse falhar, enviar um resumo curto — **nunca mais** o blob binário.

---

## 2. Arquivos que você vai tocar

| Arquivo | Ação |
|---|---|
| `app/src/main/java/kr/co/iefriends/pcsx2/utils/TombstoneParser.java` | **CRIAR** (código completo na seção 4) |
| `app/src/main/java/kr/co/iefriends/pcsx2/utils/CrashReporter.java` | **EDITAR** 3 pontos (seção 5) |

**NÃO** altere `TelemetryReporter.java` nem `App.java` — o ponto de captura e o transporte já estão
corretos; o defeito é só a decodificação.

---

## 3. Como o protobuf do Tombstone está codificado (o que o parser precisa saber)

O formato "protobuf wire format" é simples. O stream é uma sequência de campos. Cada campo é:

```
[tag varint][payload]
```

- **varint**: inteiro em base-128, little-endian. Cada byte usa 7 bits de dado; o bit mais alto
  (0x80) é "continua" (1 = há mais bytes). Ex.: `0x96 0x01` = `(0x16) | (0x01 << 7)` = 150.
- **tag** = um varint onde `field_number = tag >>> 3` e `wire_type = tag & 7`.
- **wire_type**:
  - `0` = varint (int32/int64/uint32/uint64/bool)
  - `1` = 64-bit fixo (8 bytes)
  - `2` = length-delimited: um varint com o comprimento `L`, seguido de `L` bytes (usado por
    `string`, `bytes` e mensagens aninhadas)
  - `5` = 32-bit fixo (4 bytes)
- **Campos desconhecidos**: você lê a tag, olha o `wire_type`, e **pula** o payload conforme o tipo.
  Isso é obrigatório — o Tombstone tem dezenas de campos que ignoramos.
- **map<K,V>** (usado por `threads`): serializado como o campo repetido, onde **cada** ocorrência é
  uma mensagem aninhada (a "map entry") com `key` no campo 1 e `value` no campo 2.
- **Robustez**: qualquer inconsistência (comprimento que estoura o buffer, varint sem fim) deve fazer
  o parser **retornar `null`**, nunca lançar exceção para fora.

### Números de campo (verificados contra `system/core/debuggerd/proto/tombstone.proto` do AOSP)

Estes são os ÚNICOS campos que extraímos. Todos os demais são pulados.

**`Tombstone`** (mensagem raiz):
| Campo | Nº | Wire | Tipo |
|---|---|---|---|
| `build_fingerprint` | 2 | 2 | string |
| `timestamp` | 4 | 2 | string |
| `pid` | 5 | 0 | uint32 |
| `tid` | 6 | 0 | uint32 (tid da thread que crashou) |
| `command_line` | 9 | 2 | repeated string |
| `signal_info` | 10 | 2 | mensagem `Signal` |
| `abort_message` | 14 | 2 | string |
| `causes` | 15 | 2 | repeated mensagem `Cause` |
| `threads` | 16 | 2 | map<uint32, `Thread`> |

**`Signal`** (campo 10 acima):
| Campo | Nº | Wire | Tipo |
|---|---|---|---|
| `number` | 1 | 0 | int32 |
| `name` | 2 | 2 | string (ex.: "SIGABRT") |
| `code` | 3 | 0 | int32 |
| `code_name` | 4 | 2 | string (ex.: "SI_TKILL") |
| `has_fault_address` | 8 | 0 | bool |
| `fault_address` | 9 | 0 | uint64 |

**`Cause`** (campo 15 acima):
| Campo | Nº | Wire | Tipo |
|---|---|---|---|
| `human_readable` | 1 | 2 | string |

**`Thread`** (o *value* do map `threads`):
| Campo | Nº | Wire | Tipo |
|---|---|---|---|
| `id` | 1 | 0 | int32 |
| `name` | 2 | 2 | string |
| `current_backtrace` | 4 | 2 | repeated `BacktraceFrame` |

**`BacktraceFrame`** (campo 4 acima):
| Campo | Nº | Wire | Tipo |
|---|---|---|---|
| `rel_pc` | 1 | 0 | uint64 |
| `function_name` | 4 | 2 | string |
| `function_offset` | 5 | 0 | uint64 |
| `file_name` | 6 | 2 | string (ex.: "libemucore.so") |
| `build_id` | 8 | 2 | string |

> `pc` (campo 2) e `sp` etc. existem mas não usamos — o `rel_pc` (offset dentro da .so) é o que
> importa pra simbolizar depois.

---

## 4. Criar `TombstoneParser.java`

Crie o arquivo `app/src/main/java/kr/co/iefriends/pcsx2/utils/TombstoneParser.java` com **exatamente**
este conteúdo:

```java
/*

By MoonPower (Momo-AUX1) GPLv3 License
   This file is part of ARMSX2.

   ARMSX2 is free software: you can redistribute it and/or modify
   it under the terms of the GNU General Public License as published by
   the Free Software Foundation, either version 3 of the License, or
   (at your option) any later version.

*/

package kr.co.iefriends.pcsx2.utils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal, dependency-free parser for the native crash {@code Tombstone} protobuf returned by
 * {@link android.app.ApplicationExitInfo#getTraceInputStream()} on Android 12+. Extracts just enough
 * (signal, abort message, causes, crashing-thread backtrace) to render a human-readable trace
 * equivalent to the debuggerd text format, so the /logErr telemetry no longer ships a 100-300 KB
 * binary blob. Never throws: any malformed input yields {@code null} from {@link #parse}.
 *
 * <p>Field numbers mirror {@code system/core/debuggerd/proto/tombstone.proto} (AOSP).</p>
 */
final class TombstoneParser {

    private static final int MAX_FRAMES = 64;

    private TombstoneParser() {}

    // ---- Public result types -------------------------------------------------------------------

    static final class Frame {
        long relPc;
        String fileName = "";
        String functionName = "";
        long functionOffset;
        String buildId = "";
    }

    static final class Result {
        String buildFingerprint = "";
        String timestamp = "";
        int pid;
        int tid;
        String commandLine = "";
        int signalNumber;
        String signalName = "";
        int signalCode;
        String signalCodeName = "";
        boolean hasFaultAddress;
        long faultAddress;
        String abortMessage = "";
        final List<String> causes = new ArrayList<>();
        List<Frame> backtrace = new ArrayList<>();
    }

    // ---- Entry point ---------------------------------------------------------------------------

    /** Parses tombstone protobuf bytes. Returns {@code null} for null/empty/malformed input. */
    static Result parse(byte[] data) {
        if (data == null || data.length == 0) return null;
        try {
            Result r = new Result();
            Map<Integer, List<Frame>> threads = new HashMap<>();
            StringBuilder cmd = new StringBuilder();
            Reader reader = new Reader(data, 0, data.length);
            while (reader.hasMore()) {
                int tag = (int) reader.readVarint();
                int field = tag >>> 3;
                int wire = tag & 7;
                switch (field) {
                    case 2:  r.buildFingerprint = str(reader, wire); break;
                    case 4:  r.timestamp = str(reader, wire); break;
                    case 5:  if (wire == 0) r.pid = (int) reader.readVarint(); else reader.skip(wire); break;
                    case 6:  if (wire == 0) r.tid = (int) reader.readVarint(); else reader.skip(wire); break;
                    case 9:  if (wire == 2) { if (cmd.length() > 0) cmd.append(' '); cmd.append(reader.readString()); } else reader.skip(wire); break;
                    case 10: if (wire == 2) parseSignal(reader.readMessage(), r); else reader.skip(wire); break;
                    case 14: r.abortMessage = str(reader, wire); break;
                    case 15: if (wire == 2) parseCause(reader.readMessage(), r); else reader.skip(wire); break;
                    case 16: if (wire == 2) parseThreadEntry(reader.readMessage(), threads); else reader.skip(wire); break;
                    default: reader.skip(wire);
                }
            }
            r.commandLine = cmd.toString();
            List<Frame> bt = threads.get(r.tid);
            if (bt == null && !threads.isEmpty()) bt = threads.values().iterator().next();
            if (bt != null) r.backtrace = bt;
            return r;
        } catch (Throwable t) {
            return null; // malformed / truncated -> treat as not decodable
        }
    }

    // ---- Nested-message parsers ----------------------------------------------------------------

    private static void parseSignal(Reader r, Result out) {
        while (r.hasMore()) {
            int tag = (int) r.readVarint();
            int field = tag >>> 3, wire = tag & 7;
            switch (field) {
                case 1: if (wire == 0) out.signalNumber = (int) r.readVarint(); else r.skip(wire); break;
                case 2: out.signalName = str(r, wire); break;
                case 3: if (wire == 0) out.signalCode = (int) r.readVarint(); else r.skip(wire); break;
                case 4: out.signalCodeName = str(r, wire); break;
                case 8: if (wire == 0) out.hasFaultAddress = r.readVarint() != 0; else r.skip(wire); break;
                case 9: if (wire == 0) out.faultAddress = r.readVarint(); else r.skip(wire); break;
                default: r.skip(wire);
            }
        }
    }

    private static void parseCause(Reader r, Result out) {
        while (r.hasMore()) {
            int tag = (int) r.readVarint();
            int field = tag >>> 3, wire = tag & 7;
            if (field == 1 && wire == 2) out.causes.add(r.readString());
            else r.skip(wire);
        }
    }

    private static void parseThreadEntry(Reader r, Map<Integer, List<Frame>> out) {
        int key = 0;
        Reader value = null;
        while (r.hasMore()) {
            int tag = (int) r.readVarint();
            int field = tag >>> 3, wire = tag & 7;
            if (field == 1 && wire == 0) key = (int) r.readVarint();
            else if (field == 2 && wire == 2) value = r.readMessage();
            else r.skip(wire);
        }
        if (value != null) out.put(key, parseThread(value));
    }

    private static List<Frame> parseThread(Reader r) {
        List<Frame> frames = new ArrayList<>();
        while (r.hasMore()) {
            int tag = (int) r.readVarint();
            int field = tag >>> 3, wire = tag & 7;
            if (field == 4 && wire == 2) {
                Reader frameMsg = r.readMessage();
                if (frames.size() < MAX_FRAMES) frames.add(parseFrame(frameMsg));
            } else {
                r.skip(wire);
            }
        }
        return frames;
    }

    private static Frame parseFrame(Reader r) {
        Frame f = new Frame();
        while (r.hasMore()) {
            int tag = (int) r.readVarint();
            int field = tag >>> 3, wire = tag & 7;
            switch (field) {
                case 1: if (wire == 0) f.relPc = r.readVarint(); else r.skip(wire); break;
                case 4: f.functionName = str(r, wire); break;
                case 5: if (wire == 0) f.functionOffset = r.readVarint(); else r.skip(wire); break;
                case 6: f.fileName = str(r, wire); break;
                case 8: f.buildId = str(r, wire); break;
                default: r.skip(wire);
            }
        }
        return f;
    }

    /** Reads a length-delimited string if wire==2, else skips and returns "". */
    private static String str(Reader r, int wire) {
        if (wire == 2) return r.readString();
        r.skip(wire);
        return "";
    }

    // ---- Human-readable rendering (debuggerd-like; matches CrashReporter.TOMBSTONE_FRAME) -------

    static String format(Result r) {
        StringBuilder sb = new StringBuilder(1024);
        if (!r.buildFingerprint.isEmpty()) sb.append("Build fingerprint: '").append(r.buildFingerprint).append("'\n");
        if (!r.timestamp.isEmpty())        sb.append("Timestamp: ").append(r.timestamp).append('\n');
        if (!r.commandLine.isEmpty())      sb.append("Cmdline: ").append(r.commandLine).append('\n');
        sb.append("pid: ").append(r.pid).append(", tid: ").append(r.tid).append('\n');
        sb.append("signal ").append(r.signalNumber);
        if (!r.signalName.isEmpty()) sb.append(" (").append(r.signalName).append(')');
        sb.append(", code ").append(r.signalCode);
        if (!r.signalCodeName.isEmpty()) sb.append(" (").append(r.signalCodeName).append(')');
        if (r.hasFaultAddress) sb.append(", fault addr 0x").append(Long.toHexString(r.faultAddress));
        sb.append('\n');
        if (!r.abortMessage.isEmpty()) sb.append("Abort message: '").append(r.abortMessage).append("'\n");
        for (String c : r.causes) sb.append("Cause: ").append(c).append('\n');
        if (!r.backtrace.isEmpty()) {
            sb.append("backtrace:\n");
            int i = 0;
            for (Frame f : r.backtrace) {
                sb.append(String.format(Locale.US, "  #%02d pc %016x  %s", i++, f.relPc,
                        f.fileName.isEmpty() ? "<unknown>" : f.fileName));
                if (!f.functionName.isEmpty())
                    sb.append(" (").append(f.functionName).append('+').append(f.functionOffset).append(')');
                if (!f.buildId.isEmpty())
                    sb.append(" (BuildId: ").append(f.buildId).append(')');
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /** Heuristic: are these bytes readable text (legacy Android 11 trace / ANR) rather than protobuf? */
    static boolean looksLikeText(byte[] b) {
        if (b == null || b.length == 0) return false;
        int n = Math.min(b.length, 512);
        int printable = 0;
        for (int i = 0; i < n; i++) {
            int c = b[i] & 0xFF;
            if (c == 0) return false; // NUL => binary
            if (c == 9 || c == 10 || c == 13 || (c >= 32 && c < 127)) printable++;
        }
        return printable * 10 >= n * 9; // >= 90% printable ASCII
    }

    // ---- Bounds-checked byte reader ------------------------------------------------------------

    private static final class Reader {
        private final byte[] buf;
        private int pos;
        private final int end;

        Reader(byte[] buf, int pos, int end) { this.buf = buf; this.pos = pos; this.end = end; }

        boolean hasMore() { return pos < end; }

        long readVarint() {
            long result = 0;
            int shift = 0;
            while (shift < 64) {
                if (pos >= end) throw new IndexOutOfBoundsException("varint");
                byte b = buf[pos++];
                result |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) return result;
                shift += 7;
            }
            throw new IllegalStateException("varint too long");
        }

        String readString() {
            int len = (int) readVarint();
            if (len < 0 || pos + len > end) throw new IndexOutOfBoundsException("string");
            String s = new String(buf, pos, len, StandardCharsets.UTF_8);
            pos += len;
            return s;
        }

        Reader readMessage() {
            int len = (int) readVarint();
            if (len < 0 || pos + len > end) throw new IndexOutOfBoundsException("message");
            Reader sub = new Reader(buf, pos, pos + len);
            pos += len;
            return sub;
        }

        /** Skips one field payload given its wire type. */
        void skip(int wireType) {
            switch (wireType) {
                case 0: readVarint(); break;
                case 1: advance(8); break;
                case 2: advance((int) readVarint()); break;
                case 5: advance(4); break;
                default: throw new IllegalStateException("bad wire type " + wireType);
            }
        }

        private void advance(int n) {
            if (n < 0 || pos + n > end) throw new IndexOutOfBoundsException("advance");
            pos += n;
        }
    }
}
```

> **Por que o formato de saída importa:** a linha de backtrace
> `  #00 pc 0000000000012218  libemucore.so (recRecompile(unsigned int)+340) (BuildId: ...)` imita o
> texto do debuggerd — legível para um humano no painel. No caminho protobuf (seção 5.3) os campos
> `file`/`method` vêm **direto** do frame parseado (`parsed.backtrace.get(0)`), não do regex. Mesmo
> assim o formato foi mantido **compatível** com o regex `TOMBSTONE_FRAME` já existente, para o caso
> de o texto formatado ser re-parseado em algum ponto.

---

## 5. Editar `CrashReporter.java` (3 mudanças cirúrgicas)

### 5.1 — Adicionar 1 import

No bloco de imports, adicione (perto dos outros `java.*`):

```java
import java.nio.charset.StandardCharsets;
```

### 5.2 — Substituir o método `readTrace(...)` por `readTraceBytes(...)`

**REMOVA** o método atual (retorna `String`):

```java
@RequiresApi(Build.VERSION_CODES.R)
private static String readTrace(ApplicationExitInfo info) {
    try (InputStream is = info.getTraceInputStream()) {
        if (is == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        int total = 0;
        while (total < MAX_TRACE_BYTES && (r = is.read(buf)) != -1) {
            bos.write(buf, 0, r);
            total += r;
        }
        return bos.toString("UTF-8");
    } catch (Throwable t) {
        return "";
    }
}
```

**E COLOQUE NO LUGAR** (mesma leitura, mas devolve os bytes crus, sem assumir texto):

```java
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
```

### 5.3 — Substituir o método `reportOneNativeExit(...)`

**REMOVA** o método atual inteiro e **coloque no lugar** esta versão. Ela tenta decodificar o
protobuf; se conseguir, monta mensagem/backtrace legíveis; se for texto legado, mantém o caminho
antigo; se for binário indecifrável, envia um resumo curto **sem** o blob:

```java
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

        String context = "pid=" + info.getPid()
                + "; importance=" + info.getImportance()
                + "; when=" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(info.getTimestamp()))
                + "; app_version=" + safeVersion()
                + "; device=" + Build.MANUFACTURER + " " + Build.MODEL;

        String[] logs = trace.isEmpty()
                ? new String[]{String.valueOf(info.getDescription())}
                : new String[]{trace};

        // Not a terminal path (previous process already died) — send async so boot isn't blocked.
        TelemetryReporter.report("native", file, method, message, context, logs, /*terminal=*/false);
    } catch (Throwable t) {
        Log.w(TAG, "reportOneNativeExit failed", t);
    }
}
```

> Note que `firstMatch`, `TOMBSTONE_FRAME`, `safeStatus`, `safeVersion` já existem no arquivo —
> reaproveite, não recrie. O import `java.io.InputStream` continua sendo usado por `readTraceBytes`;
> não remova nenhum import existente.

---

## 6. Verificação (obrigatória antes de considerar pronto)

### 6.1 — Compilar
Faça o build debug do checkout `E:\projects\play2\ARMSX2` e confirme que compila sem erros. O parser
não usa nenhuma dependência nova.

### 6.2 — Teste unitário rápido do parser (opcional mas recomendado)
Se puder rodar um `main()` de teste ou um teste JUnit local: monte um `Tombstone` de exemplo ou pegue
os bytes de uma amostra real (erros 483 / 469 / 406 do painel têm o blob) e confirme que
`TombstoneParser.parse(bytes)` retorna não-`null` e `format(result)` produz linhas
`#00 pc ... (símbolo+offset)` legíveis.

### 6.3 — Teste end-to-end no device (a prova real)
1. Instale o build debug num **device/emulador Android 12+ (API 31+)**.
2. **Aponte a telemetria para um alvo de teste** para não poluir produção — via adb:
   ```
   adb shell run-as kr.co.iefriends.pcsx2 \
     sh -c 'echo ... ' # (ou) usar o pref telemetry_endpoint
   ```
   Mais simples: sete o pref de endpoint para o seu próprio servidor de teste, ou aceite que o
   evento vai para o painel real e **remova-o depois**. O componente já é `armsx2/native`.
3. Force um crash nativo do processo do app:
   - Emulador com root: `adb root && adb shell kill -6 $(adb shell pidof kr.co.iefriends.pcsx2)`.
   - Device debuggable: `adb shell run-as kr.co.iefriends.pcsx2 kill -6 <pid>`.
4. **Reabra o app** (o crash nativo só é reportado no boot seguinte, via `ApplicationExitInfo`).
5. Confirme no painel `/admin/errors` que o novo evento `armsx2/native` chega com:
   - `message` = `Native crash — signal 6 (SIGABRT) ...` (texto legível, não binário)
   - `logs[0]` contendo `backtrace:` e linhas `#00 pc ... (símbolo+offset)`.

> ⚠️ **Armadilha do watermark:** `telemetry_last_exit_ts` no `SharedPreferences("armsx2")` faz cada
> saída ser reportada **uma única vez**. Entre testes, limpe os dados do app
> (`adb shell pm clear kr.co.iefriends.pcsx2`) ou zere esse pref — senão o segundo `kill -6` não gera
> um novo report.

### 6.4 — Regra inviolável (não quebrar)
A telemetria **nunca** pode alterar o comportamento do app. Todo o caminho novo está sob try/catch e
retorna cedo em erro. Se qualquer coisa no parser lançar, `parse()` devolve `null` e o fluxo cai no
resumo curto — o app segue normal. Não introduza nenhum caminho que possa propagar exceção para o
`App.onCreate()`.

---

## 7. Passos finais (pós-fix)

1. **Bump de versão** em `app/build.gradle`: `versionCode 22 → 23`, `versionName "1.0.8" → "1.0.9"`.
   Isso permite distinguir na telemetria os eventos vindos de builds já corrigidos.
2. **Fechar o bug antigo:** editar
   [`native-crash-reporter-tombstone-binario-nao-decodificado_*.md`](./native-crash-reporter-tombstone-binario-nao-decodificado_2026-07-16T02-49.md)
   anotando que os **52 IDs antigos permanecem ilegíveis** (o watermark impede reprocessá-los) e
   devem ser marcados como "instrumentação corrigida — aguardar recorrência já legível". A causa raiz
   real dos SIGABRT só poderá ser triada depois que crashes novos chegarem decodificados.
3. **Atualizar `docs/features/telemetria.md`:** a tabela diz que `getTraceInputStream()` "traz o
   backtrace nativo simbolizado" — corrigir para deixar claro que em Android 12+ ele traz o
   **protobuf `Tombstone`**, decodificado pelo `TombstoneParser` antes do envio.
```

---

> **Rastreabilidade:** `sem-task-legado` — bug fechado antes de o sistema de tasks existir (ver [`docs/README.md`](../../README.md)). Não há task associada.

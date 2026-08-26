/*

By MoonPower (Momo-AUX1) GPLv3 License
   This file is part of ARMSX2.

   ARMSX2 is free software: you can redistribute it and/or modify
   it under the terms of the GNU General Public License as published by
   the Free Software Foundation, either version 3 of the License, or
   (at your option) any later version.

*/

package com.armsx2.telemetry;

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
 * binary blob. Never throws: truncated input retains any meaningful fields decoded before the cut;
 * input with no usable crash details yields {@code null} from {@link #parse}.
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
        boolean truncated;
        final List<String> causes = new ArrayList<>();
        List<Frame> backtrace = new ArrayList<>();
    }

    // ---- Entry point ---------------------------------------------------------------------------

    /** Parses tombstone protobuf bytes. Returns {@code null} for null/empty/malformed input. */
    static Result parse(byte[] data) {
        if (data == null || data.length == 0) return null;
        Result r = new Result();
        Map<Integer, List<Frame>> threads = new HashMap<>();
        StringBuilder cmd = new StringBuilder();
        try {
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
        } catch (Throwable t) {
            // ApplicationExitInfo traces can be larger than CrashReporter's bounded read. Protobuf
            // fields before the cut are still valid, so retain signal/abort/thread data already
            // decoded instead of throwing the entire tombstone away.
            r.truncated = true;
        }
        r.commandLine = cmd.toString();
        List<Frame> bt = threads.get(r.tid);
        if (bt == null && !threads.isEmpty()) bt = threads.values().iterator().next();
        if (bt != null) r.backtrace = bt;
        boolean meaningful = r.signalNumber != 0 || !r.signalName.isEmpty()
                || !r.abortMessage.isEmpty() || !r.backtrace.isEmpty();
        return meaningful ? r : null;
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
        if (r.truncated) sb.append("Note: tombstone truncated; decoded fields below are partial.\n");
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

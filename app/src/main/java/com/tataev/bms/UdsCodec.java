package com.tataev.bms;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ISO-TP reassembly and UDS response decoding for ELM327 text output.
 *
 * The ELM327 only reassembles multi-frame responses reliably when a single
 * receive filter is set, so we do it ourselves. Ported from the Python decoder
 * that was validated against the car.
 *
 * Every frame here may be malformed: a cheap clone on a noisy RFCOMM link emits
 * truncated lines routinely, so length is checked before every index.
 */
final class UdsCodec {

    private UdsCodec() { }

    private static final String[] JUNK = {
            "BUFFER FULL", "DATA ERROR", "NO DATA", "CAN ERROR", "STOPPED",
            "UNABLE TO CONNECT", "SEARCHING", "OK", "?"
    };

    /** Resolves a DID to its expected payload width, for splitting batched replies. */
    interface WidthLookup {
        /** @return width in bytes, or -1 if this DID is unknown */
        int widthOf(String did);
    }

    /** One decoded UDS reply. */
    static final class Response {
        /** The CAN id this reply arrived on; kept for diagnostics and logging. */
        final String canId;
        final boolean negative;
        final int nrc;          // meaningful only when negative
        final String did;       // e.g. "3402", null for negative replies
        final byte[] data;      // payload after the DID

        Response(String canId, boolean negative, int nrc, String did, byte[] data) {
            this.canId = canId;
            this.negative = negative;
            this.nrc = nrc;
            this.did = did;
            this.data = data;
        }

        boolean isData() {
            return !negative && did != null && data != null;
        }
    }

    private static boolean isJunk(String line) {
        // Locale.ROOT: "Searching..." upper-cases to "SEARCHİNG" in Turkish and
        // would stop being filtered, leaving adapter chatter to be parsed as hex.
        String up = line.toUpperCase(Locale.ROOT);
        for (String j : JUNK) {
            if (up.contains(j)) return true;
        }
        return false;
    }

    /**
     * Did the ADAPTER refuse the command line, rather than the car refusing the
     * request?
     *
     * An ELM327 answers a bare "?" to a line it cannot parse - most often a
     * request too long for one CAN frame. {@link #reassembleAll} filters "?" as
     * junk along with the rest of the adapter's chatter, so by the time a caller
     * has frames this signal is already gone; it has to be read off the raw text.
     */
    static boolean isAdapterReject(String text) {
        if (text == null) return false;
        for (String line : text.replace('\r', '\n').split("\n")) {
            if (line.trim().equals("?")) return true;
        }
        return false;
    }

    /** ASCII hex only - see {@link CommandGuard#isHex} for why that matters. */
    private static boolean isHex(String s) {
        return CommandGuard.isHex(s);
    }

    /** A 7F xx 78 "busy, answer coming" reply must not mask the real one. */
    private static boolean isResponsePending(byte[] p) {
        return p.length >= 3 && (p[0] & 0xFF) == 0x7F && (p[2] & 0xFF) == 0x78;
    }

    /** Reassemble ELM327 output into {can_id: [payload, ...]} in arrival order. */
    static Map<String, List<byte[]>> reassembleAll(String text, int idLen) {
        Map<String, List<byte[]>> out = new HashMap<>();
        Map<String, byte[]> partialBuf = new HashMap<>();
        Map<String, Integer> partialTotal = new HashMap<>();
        Map<String, Integer> nextSeq = new HashMap<>();

        if (text == null) return out;
        for (String rawLine : text.replace('\r', '\n').split("\n")) {
            String line = rawLine.trim().replace(" ", "");
            if (line.isEmpty() || isJunk(line) || line.length() <= idLen) continue;

            String cid = line.substring(0, idLen).toUpperCase(Locale.ROOT);
            String payload = line.substring(idLen).toUpperCase(Locale.ROOT);
            if (!isHex(cid) || !isHex(payload)) continue;
            if (payload.length() % 2 != 0) {
                payload = payload.substring(0, payload.length() - 1); // truncated nibble
            }
            if (payload.isEmpty()) continue;

            byte[] d = new byte[payload.length() / 2];
            for (int i = 0; i < d.length; i++) {
                d[i] = (byte) Integer.parseInt(payload.substring(i * 2, i * 2 + 2), 16);
            }

            int pci = (d[0] & 0xFF) >> 4;
            if (pci == 0x0) {                                    // single frame
                int len = Math.min(d[0] & 0x0F, d.length - 1);
                if (len <= 0) continue;
                byte[] one = new byte[len];
                System.arraycopy(d, 1, one, 0, len);
                addTo(out, cid, one);
            } else if (pci == 0x1) {                             // first frame
                // Needs the length byte plus at least one data byte to be usable.
                if (d.length < 3) continue;
                int total = ((d[0] & 0x0F) << 8) | (d[1] & 0xFF);
                if (total <= 0) continue;
                byte[] head = new byte[d.length - 2];
                System.arraycopy(d, 2, head, 0, head.length);
                // A first frame carrying at least as much as it declares is
                // malformed - a real FF always promises more than it holds, or it
                // would have been a single frame - so trust the declared length
                // and drop the excess, the way the consecutive-frame branch does.
                // It stays a PARTIAL rather than becoming a complete reply: it is
                // still a bad frame, and it must not outrank a good answer that
                // arrived on the same id.
                if (head.length > total) {
                    byte[] trimmed = new byte[total];
                    System.arraycopy(head, 0, trimmed, 0, total);
                    head = trimmed;
                }
                partialBuf.put(cid, head);
                partialTotal.put(cid, total);
                nextSeq.put(cid, 1);          // CFs are numbered from 1, wrapping at 15
            } else if (pci == 0x2) {                             // consecutive frame
                byte[] have = partialBuf.get(cid);
                Integer total = partialTotal.get(cid);
                Integer want = nextSeq.get(cid);
                if (have == null || total == null || want == null || d.length < 2) continue;
                // A dropped or reordered CF would otherwise be concatenated
                // silently, shifting every later byte and yielding plausible but
                // WRONG values. The sequence number is the only way to catch it.
                if ((d[0] & 0x0F) != want) {
                    partialBuf.remove(cid);
                    partialTotal.remove(cid);
                    nextSeq.remove(cid);
                    continue;
                }
                nextSeq.put(cid, (want + 1) & 0x0F);
                byte[] grown = new byte[have.length + d.length - 1];
                System.arraycopy(have, 0, grown, 0, have.length);
                System.arraycopy(d, 1, grown, have.length, d.length - 1);
                if (grown.length >= total) {
                    byte[] done = new byte[total];
                    System.arraycopy(grown, 0, done, 0, total);
                    addTo(out, cid, done);
                    partialBuf.remove(cid);
                    partialTotal.remove(cid);
                    nextSeq.remove(cid);
                } else {
                    partialBuf.put(cid, grown);
                }
            }
        }
        // Surface partial multi-frame data rather than losing it - but only when
        // nothing complete arrived on that id. Appending it unconditionally would
        // put a truncated fragment LAST, and reassemble() keeps the last entry, so
        // the fragment would outrank a perfectly good reply.
        for (Map.Entry<String, byte[]> e : partialBuf.entrySet()) {
            if (e.getValue() == null || e.getValue().length == 0) continue;
            if (out.containsKey(e.getKey())) continue;
            addTo(out, e.getKey(), e.getValue());
        }
        return out;
    }

    /**
     * Reassemble to one payload per CAN ID, preferring the real answer.
     *
     * An ECU may reply 7F xx 78 (responsePending) and then send the actual
     * response on the same ID; the pending frame must not mask the real one.
     */
    static Map<String, byte[]> reassemble(String text, int idLen) {
        Map<String, byte[]> best = new HashMap<>();
        for (Map.Entry<String, List<byte[]>> e : reassembleAll(text, idLen).entrySet()) {
            byte[] pick = null;
            for (byte[] p : e.getValue()) {
                if (!isResponsePending(p)) pick = p;      // last real answer wins
            }
            if (pick == null && !e.getValue().isEmpty()) pick = e.getValue().get(0);
            if (pick != null) best.put(e.getKey(), pick);
        }
        return best;
    }

    private static void addTo(Map<String, List<byte[]>> m, String cid, byte[] payload) {
        List<byte[]> l = m.get(cid);
        if (l == null) {
            l = new ArrayList<>();
            m.put(cid, l);
        }
        l.add(payload);
    }

    /**
     * Decode a service 0x22 reply arriving on {@code expectedId}.
     *
     * Only that exact CAN ID is accepted. Attributing some other ECU's reply to
     * the one we asked would let BMS auto-detection latch onto the wrong address.
     */
    /**
     * As {@link #decode22(String, String)}, but also requires the reply to echo
     * the DID that was asked for.
     *
     * A late answer landing inside the next request's window would otherwise be
     * attributed to the wrong field - a timed-out SOC read followed by the
     * pack-voltage reply would record 345.8 as "SOC 345.8 %".
     */
    static Response decode22(String text, String expectedId, String expectedDid) {
        Response r = decode22(text, expectedId);
        if (r != null && r.isData() && expectedDid != null
                && !expectedDid.equalsIgnoreCase(r.did)) {
            return null;
        }
        return r;
    }

    static Response decode22(String text, String expectedId) {
        byte[] payload = reassemble(text, 3).get(expectedId);
        if (payload == null || payload.length == 0) return null;

        if ((payload[0] & 0xFF) == 0x7F) {
            int nrc = payload.length > 2 ? (payload[2] & 0xFF) : -1;
            return new Response(expectedId, true, nrc, null, null);
        }
        if ((payload[0] & 0xFF) == 0x62 && payload.length >= 3) {
            String did = String.format(Locale.ROOT, "%02X%02X",
                    payload[1] & 0xFF, payload[2] & 0xFF);
            byte[] data = new byte[payload.length - 3];
            System.arraycopy(payload, 3, data, 0, data.length);
            return new Response(expectedId, false, -1, did, data);
        }
        return null;
    }

    /**
     * Split a batched service 0x22 reply into its DIDs.
     *
     * A batched reply is {@code 62 <DID><data><DID><data>...} with NO length
     * fields, and the ECU silently omits DIDs it does not support. That is only
     * unambiguous because the caller can tell us the expected width of every DID
     * it asked for, so each entry is consumed by its own width.
     *
     * Takes an already-reassembled payload rather than raw adapter text: the
     * caller has to hold the payload anyway, because "nothing came back" and "the
     * ECU replied but we cannot split it" mean opposite things - one rides out,
     * the other falls back to single reads.
     *
     * Returns null if anything fails to line up - an unknown DID, a short buffer,
     * or trailing bytes - so the caller can fall back to reading DIDs one at a
     * time rather than trusting a desynced parse.
     */
    static Map<String, byte[]> splitBatch(byte[] payload, WidthLookup widths) {
        if (payload == null || payload.length < 3) return null;
        if ((payload[0] & 0xFF) != 0x62) return null;   // negative or unexpected

        Map<String, byte[]> out = new HashMap<>();
        int pos = 1;
        while (pos + 2 <= payload.length) {
            String did = String.format(Locale.ROOT, "%02X%02X",
                    payload[pos] & 0xFF, payload[pos + 1] & 0xFF);
            int w = widths == null ? -1 : widths.widthOf(did);
            if (w <= 0) return null;                    // desynced: bail out
            if (pos + 2 + w > payload.length) return null;
            byte[] data = new byte[w];
            System.arraycopy(payload, pos + 2, data, 0, w);
            out.put(did, data);
            pos += 2 + w;
        }
        if (pos != payload.length) return null;         // leftover bytes: distrust
        return out.isEmpty() ? null : out;
    }

    static String toHex(byte[] b) {
        if (b == null) return "";
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02X", x & 0xFF));
        return sb.toString();
    }
}

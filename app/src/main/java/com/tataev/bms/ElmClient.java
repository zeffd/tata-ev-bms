package com.tataev.bms;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Talks to a Bluetooth ELM327 over RFCOMM and speaks just enough UDS to read
 * the Nexon EV Max BMS.
 *
 * READ-ONLY: only ELM327 AT setup, UDS 0x22 reads, the 0x10 0x03 session mode
 * switch and the 0x3E keep-alive are ever transmitted. Write, clear, reset,
 * routine, IO-control and SecurityAccess requests are rejected outright.
 */
final class ElmClient {

    private static final String TAG = "ElmClient";
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    /** Whole-detection time budget, so a dead link cannot hang for minutes. */
    private static final long DETECT_BUDGET_MS = 90_000L;
    /** Consecutive silent candidates that mean the link, not the address, is bad. */
    private static final int SILENT_LIMIT = 8;

    // Volatile because abort() is called from the main thread to unstick a
    // connect() blocking on the worker, so both threads must see the same socket.
    private volatile BluetoothSocket socket;
    private volatile InputStream in;
    private volatile OutputStream out;
    private String responseId = "78D";

    boolean isConnected() {
        BluetoothSocket s = socket;
        return s != null && s.isConnected();
    }

    /** Thrown when Bluetooth permission is missing, so the UI can say so exactly. */
    static final class PermissionMissingException extends IOException {
        PermissionMissingException(String m) {
            super(m);
        }
    }

    /**
     * Pick a paired adapter. Prefers a name that looks like an OBD dongle;
     * falls back to the only bonded device if there is just one.
     */
    static BluetoothDevice findAdapter(BluetoothAdapter adapter, String preferredName)
            throws PermissionMissingException {
        if (adapter == null) return null;
        Set<BluetoothDevice> bonded;
        try {
            bonded = adapter.getBondedDevices();
        } catch (SecurityException e) {
            // Reporting this as "no paired adapter" sends the user to Bluetooth
            // settings to fix something that is not broken.
            throw new PermissionMissingException("Bluetooth permission not granted");
        }
        if (bonded == null || bonded.isEmpty()) return null;

        if (preferredName != null && !preferredName.trim().isEmpty()) {
            for (BluetoothDevice d : bonded) {
                if (preferredName.equalsIgnoreCase(safeName(d))) return d;
            }
        }
        for (BluetoothDevice d : bonded) {
            if (looksLikeObd(safeName(d))) return d;
        }
        return bonded.size() == 1 ? bonded.iterator().next() : null;
    }

    /**
     * Locale.ROOT: under a Turkish locale "iCar" upper-cases to "İCAR", which
     * does not contain "ICAR" - the adapter this app is most likely to be paired
     * with would stop being recognised.
     */
    static boolean looksLikeObd(String name) {
        String n = name == null ? "" : name.toUpperCase(Locale.ROOT);
        return n.contains("OBD") || n.contains("ELM") || n.contains("VLINK")
                || n.contains("VGATE") || n.contains("ICAR");
    }

    /**
     * Names of the paired devices that look like OBD adapters. With two or more,
     * the dashboard asks which - once - instead of silently taking the first.
     * Empty when Bluetooth permission is missing; the connect path reports that.
     */
    static java.util.List<String> obdCandidates(BluetoothAdapter adapter) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (adapter == null) return out;
        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded == null) return out;
            for (BluetoothDevice d : bonded) {
                String n = safeName(d);
                if (looksLikeObd(n) && !out.contains(n)) out.add(n);
            }
        } catch (SecurityException ignored) {
            // no permission: nothing to choose between yet
        }
        return out;
    }

    static String safeName(BluetoothDevice d) {
        try {
            String n = d.getName();
            return n == null ? "" : n;
        } catch (SecurityException e) {
            return "";
        }
    }

    /** Set by {@link #abort()}, so an aborted connect does not open a second socket. */
    private volatile boolean aborted;
    /** How many addresses gave a UDS reply in the last detection sweep. */
    private int lastDetectAnswered;
    /** Transcript of the last detection, for the shareable report. */
    private final StringBuilder detectLog = new StringBuilder();

    void connect(BluetoothDevice device) throws IOException {
        close();
        aborted = false;
        try {
            socket = device.createRfcommSocketToServiceRecord(SPP);
            socket.connect();
        } catch (IOException first) {
            // abort() unblocking the connect above lands HERE - and the
            // fallback would then open a SECOND socket and block another
            // uninterruptible 5-20 s connect that nothing can abort, keeping
            // the dead worker and its wake lock alive for that window.
            if (aborted) throw first;
            // Some clones refuse the SDP-advertised channel; the reflective
            // fallback on channel 1 is the long-standing workaround.
            Log.w(TAG, "secure RFCOMM failed, trying fallback: " + first.getMessage());
            // Close the half-open socket before replacing it. Leaking it strands a
            // native fd and an SPP channel every retry - and a stale half-open
            // channel is the classic reason later connects keep failing until the
            // user toggles Bluetooth off and on.
            closeQuietly(socket);
            socket = null;
            try {
                socket = (BluetoothSocket) device.getClass()
                        .getMethod("createRfcommSocket", int.class)
                        .invoke(device, 1);
                if (socket == null) throw first;
                socket.connect();
            } catch (Exception e) {
                throw new IOException("cannot open RFCOMM: " + e.getMessage(), first);
            }
        } catch (SecurityException e) {
            throw new IOException("Bluetooth permission not granted", e);
        }
        in = socket.getInputStream();
        out = socket.getOutputStream();
    }

    /**
     * Raw command, returns the response with the echo and prompt stripped.
     *
     * Refuses anything {@link CommandGuard} does not allow: this is the choke
     * point that enforces read-only, so a typo like 2E or 1002 cannot reach the
     * car, and neither can a second command smuggled in after a CR.
     */
    String raw(String cmd, long timeoutMs) throws IOException {
        // Snapshot the streams: abort() can null them from another thread at any
        // point, and a half-checked field would surface as an NPE on the poll
        // thread rather than the IOException every caller already handles.
        final InputStream in = this.in;
        final OutputStream out = this.out;
        if (out == null || in == null) throw new IOException("not connected");
        if (!CommandGuard.isAllowed(cmd)) {
            throw new IOException("refusing to transmit non-read command: " + cmd);
        }
        // Drain anything stale so a slow previous reply cannot be misread as ours.
        byte[] drain = new byte[512];
        while (in.available() > 0) {
            if (in.read(drain, 0, Math.min(in.available(), drain.length)) <= 0) break;
        }
        out.write((cmd + "\r").getBytes(StandardCharsets.US_ASCII));
        out.flush();

        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[256];
        // Monotonic: a wall-clock step (NTP, timezone-driven correction mid-drive)
        // would otherwise cut a read short or stretch it by the size of the jump.
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            int avail = in.available();
            if (avail > 0) {
                int n = in.read(buf, 0, Math.min(avail, buf.length));
                if (n > 0) {
                    sb.append(new String(buf, 0, n, StandardCharsets.US_ASCII));
                    if (sb.indexOf(">") >= 0) break;
                }
            } else {
                try {
                    Thread.sleep(5);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        String text = sb.toString().replace(">", "");
        StringBuilder cleaned = new StringBuilder();
        for (String line : text.replace('\r', '\n').split("\n")) {
            String t = line.trim();
            if (!t.isEmpty() && !t.equals(cmd)) cleaned.append(t).append('\n');
        }
        return cleaned.toString().trim();
    }

    /** Send a UDS request. The allowlist in {@link #raw} does the vetting. */
    String request(String req, long timeoutMs) throws IOException {
        return raw(req, timeoutMs);
    }

    /** Adapter setup only; addressing is chosen separately. */
    void initAdapter() throws IOException {
        raw("ATZ", 6000);
        raw("ATE0", 2000);
        raw("ATL0", 2000);
        raw("ATS0", 2000);
        raw("ATH1", 2000);      // headers on: we must see which ECU replied
        raw("ATCAF1", 2000);
        raw("ATSP6", 2000);     // ISO 15765-4, CAN 11-bit, 500 kbaud
        raw("ATAT1", 2000);
    }

    /**
     * Point the session at a BMS and open the extended session.
     *
     * Tata EVs use non-standard 0x7xx diagnostic addressing (the ISO 7E0..7E7
     * block is mostly silent), and the address differs between models, so the
     * caller supplies one - normally from {@link #detectBms}.
     */
    void initAndTargetBms(String requestId) throws IOException {
        initAdapter();
        targetAndOpenSession(requestId);
    }

    /**
     * Aim at an address and open the extended session, WITHOUT resetting the
     * adapter. After {@link #detectBms} the adapter is already initialised, and a
     * second ATZ is a hardware reset that discards ATH1/ATSP6/ATCAF1 and costs
     * ~20 s for no benefit.
     */
    void targetAndOpenSession(String requestId) throws IOException {
        target(requestId);
        enterExtendedSession();
    }

    /**
     * Open the extended session. A few DIDs only answer there; it writes nothing
     * and lapses on its own once we stop talking.
     *
     * @return true if the ECU positively acknowledged (0x50). A refusal is not
     *         fatal - the default-session DIDs still work - so the caller decides.
     */
    boolean enterExtendedSession() throws IOException {
        String text = raw("1003", 4000);
        byte[] p = UdsCodec.reassemble(text, 3).get(responseId);
        return p != null && p.length > 0 && (p[0] & 0xFF) == 0x50;
    }

    /** What {@link #detectBms} found. */
    static final class BmsInfo {
        final String requestId;
        final String responseId;
        final String systemName;
        final String supplier;

        BmsInfo(String requestId, String responseId, String systemName, String supplier) {
            this.requestId = requestId;
            this.responseId = responseId;
            this.systemName = systemName;
            this.supplier = supplier;
        }
    }

    /**
     * Find the BMS by asking each candidate address for its own name (DID F197)
     * and keeping the one that calls itself a BMS. Falls back to any ECU whose
     * supplier string mentions a known battery vendor, and then to any ECU whose
     * DATA looks like a battery's - a plausible state of charge and pack voltage
     * on the known DIDs - so a model whose BMS is named something unexpected is
     * still found without anyone typing an address.
     *
     * Pure reads throughout.
     */
    BmsInfo detectBms(java.util.List<String> candidates, ProgressSink progress)
            throws IOException {
        initAdapter();
        detectLog.setLength(0);
        return sweepCandidates(candidates, progress);
    }

    /**
     * The identification sweep alone, for an adapter that is already
     * initialised - broadcast discovery re-runs it over the addresses it heard.
     */
    BmsInfo sweepCandidates(java.util.List<String> candidates, ProgressSink progress)
            throws IOException {
        BmsInfo bySupplier = null;
        BmsInfo byContent = null;
        long deadline = SystemClock.elapsedRealtime() + DETECT_BUDGET_MS;
        int silentInARow = 0;
        int answered = 0;
        lastDetectAnswered = 0;
        for (String req : candidates) {
            // !isConnected(): after abort() every target() throws, and grinding
            // through the rest of the list would end in a false "no BMS".
            if (Thread.currentThread().isInterrupted() || !isConnected()) break;
            if (SystemClock.elapsedRealtime() > deadline) {
                throw new IOException("BMS detection timed out");
            }
            // A dead socket never throws - raw() just spins to its timeout and
            // returns "" - so give up rather than grind through every candidate.
            // A live adapter prints NO DATA for a silent ECU, which is not "",
            // so only the adapter's own silence counts here.
            if (silentInARow >= SILENT_LIMIT) {
                throw new IOException("adapter stopped responding during detection");
            }
            if (progress != null) progress.onProgress("probing " + req + "...");
            try {
                target(req);
            } catch (IOException e) {
                continue;
            }
            String reply;
            try {
                reply = raw("22" + BmsFields.DID_SYSTEM_NAME, 1500);
            } catch (IOException e) {
                reply = "";
            }
            if (reply == null || reply.trim().isEmpty()) {
                silentInARow++;
                detectLog.append(req).append(": adapter silent\n");
                continue;
            }
            silentInARow = 0;
            // Only a UDS frame from this address counts as an answer. NO DATA
            // and CAN ERROR are the adapter talking, not the car.
            UdsCodec.Response idReply = UdsCodec.decode22(reply, responseId);
            if (idReply == null) {
                detectLog.append(req).append(": no UDS reply\n");
                continue;
            }
            answered++;
            lastDetectAnswered = answered;
            // Any reply - a name or a refusal - proves an ECU lives here. One
            // that serves the data DIDs but not F197 (a case establish() accepts
            // for a saved address) still gets the content check below.
            String name = asIdString(UdsCodec.decode22(reply, responseId,
                    BmsFields.DID_SYSTEM_NAME));
            String supplier = name == null ? null : readIdString(BmsFields.DID_SUPPLIER);
            // Locale.ROOT: "Gotion" upper-cases to "GOTİON" in Turkish, so the
            // supplier fallback would never match on a Turkish-locale phone.
            String upperName = name == null ? "" : name.toUpperCase(Locale.ROOT);
            String upperSup = supplier == null ? "" : supplier.toUpperCase(Locale.ROOT);
            detectLog.append(req).append(": answered  name=")
                     .append(name == null ? "-" : name)
                     .append("  supplier=").append(supplier == null ? "-" : supplier)
                     .append('\n');

            if (upperName.contains("BMS") || upperName.contains("BATTERY")) {
                detectLog.append("  ^ identified by name\n");
                return new BmsInfo(req, responseId, name, supplier == null ? "" : supplier);
            }
            if (bySupplier == null && name != null && (upperSup.contains("GOTION")
                    || upperSup.contains("BMS") || upperSup.contains("CATL")
                    || upperSup.contains("LG"))) {
                detectLog.append("  ^ battery-vendor supplier\n");
                bySupplier = new BmsInfo(req, responseId, name, supplier);
            }
            if (bySupplier == null && byContent == null && looksLikeBatteryData()) {
                detectLog.append("  ^ data looks like a battery\n");
                byContent = new BmsInfo(req, responseId, name == null ? "" : name,
                        supplier == null ? "" : supplier);
            }
        }
        return bySupplier != null ? bySupplier : byContent;
    }

    /**
     * After a sweep that found nothing: how many addresses answered at all.
     * Zero means the car is asleep or off, or every ECU lives somewhere this
     * app does not look - not that the car has no battery controller.
     */
    int lastDetectAnswered() {
        return lastDetectAnswered;
    }

    /** What the last detection saw, address by address. */
    String detectionLog() {
        return detectLog.toString();
    }

    /**
     * Ask the WHOLE bus who is there: functional-broadcast probes to 0x7DF
     * with the receive filter open. Every UDS-capable ECU that answers reveals
     * its CAN id, and the ids are the whole yield. Both probes fit a single
     * frame each way (TesterPresent, and a one-byte identification DID), so no
     * ISO-TP flow control is ever involved. Pure reads, same guard as
     * everything else.
     *
     * @return request ids (response - 8) heard, deduplicated; empty on failure
     */
    java.util.List<String> discoverEcus() {
        java.util.List<String> ids = new java.util.ArrayList<>();
        try {
            raw("ATCRA", 2000);          // open the filter: hear everyone
            raw("ATSH7DF", 2000);
            for (String probe : new String[]{"3E00", "22F186"}) {
                String text;
                try {
                    text = raw(probe, 2500);
                } catch (IOException e) {
                    continue;
                }
                for (String resp : UdsCodec.respondingIds(text)) {
                    String req = UdsCodec.requestIdFor(resp);
                    if (req != null && !ids.contains(req)) ids.add(req);
                }
            }
        } catch (IOException ignored) {
            // a failed discovery just means no extra candidates
        }
        detectLog.append("broadcast heard: ")
                 .append(ids.isEmpty() ? "nothing" : ids.toString()).append('\n');
        return ids;
    }

    /**
     * Does this ECU serve a plausible state of charge and pack voltage on the
     * known DIDs? Content, not name: two reads, both harmless.
     */
    private boolean looksLikeBatteryData() {
        try {
            UdsCodec.Response soc = readDid("3402");
            UdsCodec.Response pack = readDid("3400");
            if (soc == null || pack == null || !soc.isData() || !pack.isData()) return false;
            if (soc.data.length != 2 || pack.data.length != 2) return false;
            int s = ((soc.data[0] & 0xFF) << 8) | (soc.data[1] & 0xFF);
            int p = ((pack.data[0] & 0xFF) << 8) | (pack.data[1] & 0xFF);
            // SOC in tenths of a percent; pack volts in tenths, 200-500 V.
            return s <= 1000 && p >= 2000 && p <= 5000;
        } catch (IOException e) {
            return false;
        }
    }

    /** Read an ASCII identification DID, trimmed; null when unsupported. */
    String readIdString(String did) {
        try {
            return asIdString(readDid(did));
        } catch (IOException e) {
            return null;
        }
    }

    private static String asIdString(UdsCodec.Response r) {
        if (r == null || !r.isData()) return null;
        StringBuilder sb = new StringBuilder();
        for (byte b : r.data) {
            int c = b & 0xFF;
            if (c >= 32 && c < 127) sb.append((char) c);
        }
        String s = sb.toString().trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * Does this address respond to UDS at all?
     *
     * Any reply counts, including a negative one: an ECU that serves data DIDs
     * but not F197 is still the right address, so proof-of-life must not require
     * a specific DID to be supported.
     */
    boolean respondsAtAll(String did) {
        try {
            String text = raw("22" + did, 1500);
            return text != null && !text.trim().isEmpty()
                    && UdsCodec.reassemble(text, 3).containsKey(responseId);
        } catch (IOException e) {
            return false;
        }
    }

    /** Lets the UI show which address is being probed during detection. */
    interface ProgressSink {
        void onProgress(String message);
    }

    void target(String requestHeader) throws IOException {
        int req = Integer.parseInt(requestHeader, 16);
        // Locale.ROOT: under a locale with a non-Latin numbering system (ar-EG,
        // for one) %X emits Arabic-Indic digits, and the response id would then
        // never match the ASCII CAN id the adapter prints - every reply dropped.
        responseId = String.format(Locale.ROOT, "%03X", req + 8);
        raw("ATSH " + requestHeader, 2000);
        raw("ATCRA " + responseId, 2000);
        raw("ATFCSH " + requestHeader, 2000);
        raw("ATFCSD 300000", 2000);
        raw("ATFCSM1", 2000);
    }

    /** The CAN id this session's target replies on; used to validate replies. */
    String responseId() {
        return responseId;
    }

    /**
     * Read one DID. Returns null when the ECU declines or stays silent.
     *
     * The DID echoed in the reply must match the one asked for. Without that
     * check a late answer landing in the next request's window is attributed to
     * the wrong field - a timed-out SOC read followed by the pack-voltage reply
     * would record 345.8 as "SOC 345.8 %" on the dashboard and in the CSV.
     */
    UdsCodec.Response readDid(String did) throws IOException {
        String text = request("22" + did, 1500);
        return UdsCodec.decode22(text, responseId, did);
    }

    /**
     * Read up to 3 DIDs in one request. Three is the ceiling: service byte plus
     * 3x2 DID bytes is 7 bytes, the CAN single-frame limit - a fourth makes the
     * ELM327 answer "?".
     *
     * Returns null when the reply cannot be split with confidence, so the caller
     * falls back to individual reads.
     */
    /**
     * Outcome of a batched read, so the caller can tell the three cases apart.
     *
     * They must not be conflated: a clean NRC only means one of the three DIDs is
     * absent on this model, whereas a rejected FORM means the ECU cannot do
     * multi-DID reads at all. Treating the first as the second disables batching
     * on a perfectly healthy ECU and triples Bluetooth traffic.
     */
    static final class BatchResult {
        final java.util.Map<String, byte[]> values;   // null unless splittable
        final boolean formRejected;                   // ECU cannot do multi-DID

        BatchResult(java.util.Map<String, byte[]> values, boolean formRejected) {
            this.values = values;
            this.formRejected = formRejected;
        }
    }

    /**
     * Nothing came back, but the link itself is fine.
     *
     * A dead RFCOMM socket throws from the write or the read immediately; silence
     * means the socket carried the request and the ECU simply did not answer
     * within the window - a momentary condition on a busy vehicle bus. The two
     * must be told apart, because one warrants an instant reconnect and the other
     * warrants riding it out.
     */
    static final class SilentException extends IOException {
        SilentException(String message) {
            super(message);
        }
    }

    BatchResult readDidBatch(java.util.List<String> dids, UdsCodec.WidthLookup widths)
            throws IOException {
        if (dids == null || dids.isEmpty() || dids.size() > 3) {
            return new BatchResult(null, false);
        }
        StringBuilder req = new StringBuilder("22");
        for (String d : dids) req.append(d);
        String text = request(req.toString(), 2000);

        // The adapter itself rejecting the request line, before the car ever sees
        // it. reassemble() filters "?" as junk, so this is the ONLY place it can
        // be seen - and it is the one form rejection the adapter (rather than the
        // ECU) can raise. Without this check such an adapter looked permanently
        // silent and the service reconnected forever instead of falling back to
        // single reads.
        if (UdsCodec.isAdapterReject(text)) return new BatchResult(null, true);

        // raw() returns "" on a silent link rather than throwing, so distinguish
        // "nothing came back" from "the ECU replied but we cannot split it".
        java.util.Map<String, byte[]> frames = UdsCodec.reassemble(text, 3);
        byte[] payload = frames.get(responseId);
        if (payload == null) {
            throw new SilentException("no reply to batched read");
        }
        java.util.Map<String, byte[]> split = UdsCodec.splitBatch(payload, widths);
        if (split != null) return new BatchResult(split, false);

        // ONLY NRC 0x13 (incorrectMessageLengthOrInvalidFormat) says the batched
        // FORM was refused. This used to be "anything that is not 0x31", which
        // swept in every transient outcome - and a 3-DID reply is 13 bytes, so it
        // is always multi-frame, so a single dropped consecutive frame arrives as
        // an unsplittable partial 0x62. Three of those in a row (a busy bus, a
        // 0x21/0x22/0x78 from the ECU, or a lapsed session answering 0x7F) latched
        // batching off for the rest of the link and tripled the round-trips that
        // batching exists to avoid.
        boolean formRejected = payload.length >= 3 && (payload[0] & 0xFF) == 0x7F
                && (payload[2] & 0xFF) == NRC_INVALID_LENGTH;
        return new BatchResult(null, formRejected);
    }

    /** incorrectMessageLengthOrInvalidFormat - the ECU refusing the batched form. */
    private static final int NRC_INVALID_LENGTH = 0x13;

    /**
     * Hold the extended session open, and say whether it worked.
     *
     * TesterPresent writes nothing; it only stops the ECU's S3 timer from
     * expiring. The return value matters because the caller is the only thing
     * that can repair a lapse: the session is opened once at connect, so if it
     * ever does drop, every extended-session-only DID reads blank for the rest of
     * the drive with nothing on screen to say why. Swallowing the outcome here
     * made that failure completely invisible.
     *
     * @return true if the ECU answered TesterPresent positively (0x7E)
     */
    boolean keepAlive() {
        try {
            String text = raw("3E00", 1000);
            byte[] p = UdsCodec.reassemble(text, 3).get(responseId);
            // A lapsed session still answers 3E00 - the default session supports
            // it - so this is not a session-lapse detector. It catches the ECU
            // going quiet or refusing, which is the case that leaves the session
            // unheld and is worth re-opening from.
            return p != null && p.length > 0 && (p[0] & 0xFF) == 0x7E;
        } catch (IOException e) {
            return false;
        }
    }

    private static void closeQuietly(BluetoothSocket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }

    void close() {
        BluetoothSocket s = socket;
        if (s != null) {
            try {
                raw("1001", 800);   // leave the BMS in its default session
            } catch (IOException | RuntimeException ignored) {
                // abort() may have pulled the streams out from under us
            }
        }
        socket = null;
        in = null;
        out = null;
        closeQuietly(s);
    }

    /**
     * Tear the link down NOW, from any thread, sending nothing.
     *
     * {@link BluetoothSocket#connect()} is uninterruptible and routinely blocks
     * for 5-20 s when the dongle is off or out of range - which is exactly when
     * someone presses Cancel. Thread.interrupt() does not touch it; closing the
     * socket from another thread is the only thing that unblocks it.
     *
     * Unlike {@link #close} this cannot send the polite 1001 session reset: that
     * would have to queue behind the very connect we are abandoning. The ECU drops
     * to the default session on its own once we stop talking, so nothing is left
     * in a modified state - which is the only thing the read-only contract cares
     * about.
     */
    void abort() {
        aborted = true;
        BluetoothSocket s = socket;
        socket = null;
        in = null;
        out = null;
        closeQuietly(s);
    }
}

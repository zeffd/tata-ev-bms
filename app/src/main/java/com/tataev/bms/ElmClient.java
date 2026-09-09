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

    // Volatile because abort() is called from the main thread to unstick a
    // connect() blocking on the worker, so both threads must see the same socket.
    private volatile BluetoothSocket socket;
    private volatile InputStream in;
    private volatile OutputStream out;
    private String responseId = "78D";

    /**
     * The one wire this app speaks: ISO 15765-4, CAN 11-bit, 500 kbaud.
     *
     * ATSP and not ATTP deliberately: this is the adapter's own sensible default,
     * it is what every Tata EV uses, and persisting it to the dongle's EEPROM
     * changes nothing about what the next tool sees. Always set explicitly by
     * initAdapter(), so nothing depends on what the adapter remembers.
     */
    private static final String PROTOCOL = "ATSP6";

    String protocol() {
        return PROTOCOL;
    }

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

    /** What the adapter answered during setup, for the report headers. */
    private final AdapterCaps caps = new AdapterCaps();

    /**
     * The last few exchanges, for the poll report.
     *
     * A dashboard that decodes nothing is unexplainable from a screenshot: what
     * matters is which request shape got which reply, and only the adapter saw
     * that. Small and bounded - this runs on every read of every poll.
     *
     * The VIN read's REPLY is never recorded. That is the primary guarantee; the
     * renderer's scrub is the second one.
     */
    private static final int RECENT_MAX = 24;
    private final java.util.ArrayDeque<String> recent = new java.util.ArrayDeque<>();

    /**
     * How the last VIN read and the last session request went - the OUTCOMES, so
     * a report can say whether identification was refused, timed out or simply
     * never asked, without ever carrying the VIN itself.
     */
    private volatile String lastVinReadOutcome = "";
    private volatile String lastSessionOutcome = "";

    String lastVinReadOutcome() {
        return lastVinReadOutcome;
    }

    String lastSessionOutcome() {
        return lastSessionOutcome;
    }

    AdapterCaps caps() {
        return caps;
    }

    /**
     * Send an adapter command and record whether it was accepted. A "?" is a
     * capability the adapter lacks, never a failure: the software already
     * filters replies by CAN id when the receive filter cannot be set, and
     * already surfaces partial multi-frame replies when flow control is missing.
     */
    private String at(String cmd, long timeoutMs) throws IOException {
        String reply = raw(cmd, timeoutMs);
        caps.note(cmd, reply);
        return reply;
    }

    void connect(BluetoothDevice device) throws IOException {
        close();
        synchronized (recent) {
            recent.clear();       // a new link's report is about THIS link
        }
        lastVinReadOutcome = "";
        lastSessionOutcome = "";
        // aborted is NOT cleared here. It is set only by abort(), whose one
        // caller is BmsService.onDestroy - the service is going away and the
        // worker is being joined, so nothing legitimately connects again on this
        // client. Clearing it re-armed the reflective fallback for an abort that
        // landed just BEFORE this call, opening a second uninterruptible socket
        // and keeping the dead worker and its wake lock alive for another
        // 5-20 s. Once set, it stays set.
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
        String reply = cleaned.toString().trim();
        record(cmd, reply);
        return reply;
    }

    /** One exchange into the ring buffer, with the VIN read's answer left out. */
    private void record(String cmd, String reply) {
        // The VIN read's SHAPE is what a report needs - whether it answered, was
        // refused, or timed out - and its bytes are the one thing that must never
        // be written down. Decode it, keep the outcome, drop the text.
        String entry = cmd.toUpperCase(Locale.ROOT).contains("F190")
                ? "> " + cmd + "\n(VIN read: "
                        + outcomeOf(UdsCodec.decode22(reply, responseId)) + ")"
                : "> " + cmd + "\n" + (reply.isEmpty() ? "(no reply)" : reply);
        synchronized (recent) {
            recent.addLast(entry);
            while (recent.size() > RECENT_MAX) recent.removeFirst();
        }
    }

    /** The last {@value #RECENT_MAX} exchanges, oldest first, for the poll report. */
    String recentTraffic() {
        StringBuilder sb = new StringBuilder();
        synchronized (recent) {
            for (String e : recent) {
                if (sb.length() > 0) sb.append("\n\n");
                sb.append(e);
            }
        }
        return sb.toString();
    }

    /** Send a UDS request. The allowlist in {@link #raw} does the vetting. */
    String request(String req, long timeoutMs) throws IOException {
        return raw(req, timeoutMs);
    }

    /** Adapter setup only; addressing is chosen separately. */
    void initAdapter() throws IOException {
        caps.reset();
        caps.noteBanner(raw("ATZ", 6000));   // the banner names the chip - or the clone
        at("ATE0", 2000);
        at("ATL0", 2000);
        at("ATS0", 2000);
        at("ATH1", 2000);      // headers on: we must see which ECU replied
        at("ATCAF1", 2000);
        at(PROTOCOL, 2000);    // ISO 15765-4, CAN 11-bit, 500 kbaud
        at("ATAT1", 2000);
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
        byte[] p = UdsCodec.reassemble(text).get(responseId);
        boolean accepted = p != null && p.length > 0 && (p[0] & 0xFF) == 0x50;
        // Recorded for the reports: an ECU that refuses the extended session is a
        // different problem from one that never answered the request at all.
        if (accepted) {
            lastSessionOutcome = "accepted";
        } else if (p != null && p.length > 2 && (p[0] & 0xFF) == 0x7F) {
            lastSessionOutcome = String.format(Locale.ROOT, "refused NRC %02X", p[2] & 0xFF);
        } else {
            lastSessionOutcome = p == null || p.length == 0 ? "no reply" : "unexpected reply";
        }
        return accepted;
    }

    static final int ID_READ_ATTEMPTS = 3;
    static final long ID_READ_PAUSE_MS = 300;

    /**
     * Identification DIDs are read once per connect and decide which profile a car
     * is, so a busy or pending reply must not stand as the answer. Up to three
     * reads, a short pause between; a final NRC or a positive reply stops early.
     *
     * This is what one owner hit: the ECU had served its VIN before - the
     * profile is named from it - but under load one read came back busy, the
     * scan looked VIN-less, and every apply on the screen was refused.
     */
    UdsCodec.Response readIdResponse(String did) throws IOException {
        UdsCodec.Response last = null;
        for (int attempt = 1; attempt <= ID_READ_ATTEMPTS; attempt++) {
            last = readDid(did);
            if (!UdsCodec.isTransientNegative(last)) break;
            if (attempt < ID_READ_ATTEMPTS) {
                try {
                    Thread.sleep(ID_READ_PAUSE_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (BmsFields.DID_VIN.equalsIgnoreCase(did)) lastVinReadOutcome = outcomeOf(last);
        return last;
    }

    /** What a reply WAS, in one phrase - never what it said. */
    private static String outcomeOf(UdsCodec.Response r) {
        if (r == null) return "no reply";
        if (r.negative) return String.format(Locale.ROOT, "NRC %02X", r.nrc & 0xFF);
        return String.format(Locale.ROOT, "positive, %d bytes", r.data == null ? 0 : r.data.length);
    }

    /** Read an ASCII identification DID, trimmed; null when unsupported. */
    String readIdString(String did) {
        try {
            return asIdString(readIdResponse(did));
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
     * One read of the controller's name at {@link BmsFields#BMS_REQUEST}, classified.
     *
     * A refusal counts as ANSWERED: a controller that serves the data DIDs but not
     * F197 is still the controller. Busy / pending first replies are retried by
     * readIdResponse when the caller asks for the name afterwards.
     *
     * The verdict itself is {@link ConnectPlan#classify} - pure, and tested - and
     * it needs to know whether the adapter proved itself alive at ATZ, because
     * silence from a live adapter is the CAR's silence, not the adapter's.
     */
    ConnectPlan.Probe probeBms() throws IOException {
        target(BmsFields.BMS_REQUEST);
        boolean alive = !caps.banner().isEmpty();
        String text = raw("22" + BmsFields.DID_SYSTEM_NAME, 1500);
        if ((text == null || text.trim().isEmpty()) && alive) {
            // The adapter is alive, so give the car the window the session
            // request gets before calling it silent: a slow clone prints NO DATA
            // only after its own timeout, and 1.5 s is inside it.
            text = raw("22" + BmsFields.DID_SYSTEM_NAME, 4000);
        }
        boolean uds = text != null && UdsCodec.decode22(text, responseId) != null;
        return ConnectPlan.classify(alive, text, uds);
    }

    void target(String requestHeader) throws IOException {
        String resp = UdsCodec.responseIdFor(requestHeader);
        if (resp == null) throw new IOException("bad request id: " + requestHeader);
        responseId = resp;
        setHeader(requestHeader);
        at("ATCRA" + responseId, 2000);
        at("ATFCSH" + requestHeader, 2000);
        at("ATFCSD300000", 2000);
        at("ATFCSM1", 2000);
        // A clone that refused ATCRA may still be sitting behind its factory
        // 7E8-7EF receive window, in which case a 78D reply is never printed and
        // there is nothing for the software id filter to filter. ATCM/ATCF have
        // been in the ELM327 since v1.0 and do the same job one level down.
        if (!caps.supports("ATCRA")) {
            at("ATCM7FF", 2000);
            at("ATCF" + responseId, 2000);
        }
    }

    private void setHeader(String id) throws IOException {
        at("ATSH" + id, 2000);
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
        java.util.Map<String, byte[]> frames = UdsCodec.reassemble(text);
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
            byte[] p = UdsCodec.reassemble(text).get(responseId);
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

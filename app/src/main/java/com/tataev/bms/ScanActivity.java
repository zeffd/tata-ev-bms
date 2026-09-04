package com.tataev.bms;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Scans this vehicle's BMS for readable DIDs, so a Tata EV with a different
 * parameter map can be mapped without a laptop.
 *
 * The polling service must be stopped first: these adapters carry one
 * conversation at a time.
 */
public final class ScanActivity extends Activity {

    // Results outlive the Activity instance so a rotation mid-scan does not throw
    // away a sweep that already ran and already wrote its report to disk.
    private static volatile List<DidScanner.Hit> lastResultHits;
    private static volatile Map<String, String> lastResultSuggestion;
    private static volatile java.util.List<DidScanner.VoltageHypothesis> lastResultHypotheses;
    private static volatile File lastResultReport;
    /** VIN of the car that produced the stored results; "" if it served none. */
    private static volatile String lastResultVin = "";
    /** Truncation note belonging to the stored results. */
    private static volatile String lastResultNote = "";
    /** Final status line for the stored results ("Scan complete" / link lost). */
    private static volatile String lastResultStatus = "Scan complete";
    /** Static so leaving and re-entering the screen cannot start a second scan. */
    private static volatile Thread worker;
    private static volatile boolean cancelledStatic;
    /**
     * Whether the sweep that produced the stored results was cancelled.
     *
     * cancelledStatic cannot answer that on a revisit: onDestroy sets it on the
     * way out, so a scan that ran to completion was re-displayed as "Stopped" the
     * next time the screen was opened.
     */
    private static volatile boolean lastResultCancelled;
    /**
     * Last status line, kept statically so a recreated screen can re-attach.
     *
     * The worker captures the Activity that started it. A configuration change -
     * dark mode, font scale, display size; none of which the portrait lock stops -
     * leaves it posting to a dead TextView, so the new screen sat at "Idle" while
     * a 40-minute sweep ran on and never learned it had finished.
     */
    private static volatile String lastStatus = "Idle";
    /** Bumped whenever a sweep publishes results, so a new screen can notice. */
    private static volatile int resultsSerial;

    /**
     * Is a sweep running right now?
     *
     * BmsService asks, so it can refuse to open the adapter underneath one. The
     * check already existed in the other direction only.
     */
    static boolean isScanning() {
        Thread t = worker;
        return t != null && t.isAlive();
    }

    /** Wake-lock lease for the sweep, renewed on every progress callback. */
    private static final long SCAN_LOCK_MS = 10 * 60 * 1000L;

    private final Handler ui = new Handler(Looper.getMainLooper());
    /** Serial this screen has already rendered; drives the re-attach poll. */
    private int seenSerial;

    /**
     * Keeps a recreated screen in step with a sweep the previous instance began.
     *
     * The worker captured the old Activity, so its own setStatus and showResults
     * calls land on dead views. Rather than thread a live reference through it,
     * everything the worker produces goes to statics and this poll picks it up.
     */
    /**
     * The one place that decides what the button says.
     *
     * A cancelled sweep keeps running until the worker reaches its next progress
     * callback, which can be a couple of seconds on a slow DID. The ticker below
     * used to derive the label from isScanning() alone, so it overwrote the
     * "Stopping..." set by the tap within a second - the button snapped back to
     * "Stop scan" and read as if the tap had been ignored.
     */
    private static String startButtonLabel() {
        if (!isScanning()) return "Start scan";
        return cancelledStatic ? "Stopping..." : "Stop scan";
    }

    private final Runnable attach = new Runnable() {
        @Override
        public void run() {
            boolean running = isScanning();
            if (status != null && running) status.setText(lastStatus);
            if (startBtn != null) {
                startBtn.setText(startButtonLabel());
            }
            if (!running && seenSerial != resultsSerial) {
                seenSerial = resultsSerial;
                restorePreviousResults();
            }
            ui.postDelayed(this, 1000);
        }
    };
    private Prefs prefs;
    private TextView status;
    private TextView results;
    private Button startBtn;
    private EditText fromField;
    private EditText toField;


    // Written on the scan worker, read on the UI thread.
    private volatile Map<String, String> lastSuggestion;
    private volatile java.util.List<DidScanner.VoltageHypothesis> lastHypotheses;
    private LinearLayout hypothesisBox;
    private volatile File lastReport;
    /** Truncation note from this sweep's analysis, or "". */
    private volatile String lastAnalysisNote = "";

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = new Prefs(this);
        getWindow().addFlags(
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        int pad = Math.round(12 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, 0, pad, pad);
        root.setBackgroundColor(Palette.BG);

        root.addView(backHeader("Scan vehicle"));
        root.addView(note("Finds which DIDs this vehicle's BMS will answer, then "
                + "suggests which one is which. Read-only.\n\n"
                + "Stop monitoring on the main screen first - the adapter handles "
                + "one connection at a time. Keep the car awake."));

        fromField = hexField(String.format(Locale.ROOT, "%04X", prefs.scanFrom()));
        toField = hexField(String.format(Locale.ROOT, "%04X", prefs.scanTo()));
        root.addView(labelled("From DID (hex)", fromField));
        root.addView(labelled("To DID (hex)", toField));
        root.addView(note("Default 3400-35FF is where the Nexon EV Max keeps its "
                + "battery data. Widen to 0000-FFFF for an exhaustive scan "
                + "(slow: tens of minutes)."));

        startBtn = new Button(this);
        // Read the real state, not a fixed label. After a recreate this button
        // said "Start scan" while its listener still checked isScanning() first,
        // so tapping it aborted the sweep it appeared to be offering to begin.
        startBtn.setText(startButtonLabel());
        startBtn.setOnClickListener(v -> {
            if (worker != null && worker.isAlive()) {
                cancelledStatic = true;
                startBtn.setText(startButtonLabel());
            } else {
                start();
            }
        });
        root.addView(startBtn);

        status = note(lastStatus);
        status.setTextColor(Palette.SOFT);
        root.addView(status);

        results = new TextView(this);
        results.setTextColor(Palette.TEXT);
        results.setTextSize(12);
        results.setTypeface(Typeface.MONOSPACE);
        root.addView(results);

        hypothesisBox = new LinearLayout(this);
        hypothesisBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(hypothesisBox);

        Button apply = new Button(this);
        apply.setText("Apply suggested mapping");
        apply.setOnClickListener(v -> applySuggestion());
        root.addView(apply);

        Button share = new Button(this);
        share.setText("Share scan report");
        share.setOnClickListener(v -> shareReport());
        root.addView(share);

        Button reset = new Button(this);
        reset.setText("Reset to default DIDs and scales");
        reset.setOnClickListener(v -> {
            // Same gate as the Apply buttons: clearing overrides mid-session
            // remaps the poll while the open CSV's header still names the old
            // DIDs in its raw_<role>_<DID> columns.
            if (refuseWhileLive()) return;
            prefs.clearAllDidOverrides();
            Toast.makeText(this, "Overrides cleared", Toast.LENGTH_SHORT).show();
        });
        root.addView(reset);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        sv.setBackgroundColor(Palette.BG);
        setContentView(sv);
        SystemBars.pad(sv);
        seenSerial = resultsSerial;
        restorePreviousResults();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(attach);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(attach);
    }


    private View backHeader(String title) {
        return Ui.backHeader(this, title, null, null);
    }

    /** Re-attach the last sweep's results after a rotation or a revisit. */
    private void restorePreviousResults() {
        if (lastResultHits == null) return;
        lastSuggestion = lastResultSuggestion;
        lastHypotheses = lastResultHypotheses;
        lastReport = lastResultReport;
        // The note is published with the results; without this line a recreated
        // screen re-rendered them with this instance's empty note, and the
        // truncated shortlist looked exhaustive again.
        lastAnalysisNote = lastResultNote;
        showResults(lastResultHits,
                lastResultSuggestion == null
                        ? new java.util.LinkedHashMap<>() : lastResultSuggestion,
                lastResultCancelled);
    }

    private void start() {
        // The polling service and this scan would fight over an adapter that
        // carries one conversation at a time, so enforce it instead of asking.
        // Must test whether the service EXISTS, not its status: State.ERROR is the
        // reconnect loop, where the worker is very much alive and using the adapter.
        Integer fromV = parseHex(fromField);
        Integer toV = parseHex(toField);
        if (fromV == null || toV == null) {
            // A 5-digit entry would emit malformed DIDs and run for days; a huge
            // one overflows the loop counter and never terminates at all.
            Toast.makeText(this, "DIDs must be 1-4 hex digits (0000-FFFF)",
                    Toast.LENGTH_LONG).show();
            return;
        }
        int from = fromV, to = toV;
        if (to < from) {
            Toast.makeText(this, "'To' must be >= 'From'", Toast.LENGTH_LONG).show();
            return;
        }
        prefs.setScanRange(from, to);

        // Check AND claim under the monitor the service uses. Checking in one
        // step and starting in another is a race: both sides' checks can pass at
        // once and two things then open the same single-conversation adapter.
        // Validation happens above so the lock is held only across the claim.
        synchronized (BmsService.ADAPTER) {
            if (BmsService.isActive()) {
                Toast.makeText(this, "Stop monitoring on the main screen first - "
                        + "the adapter allows one connection", Toast.LENGTH_LONG).show();
                return;
            }
            if (worker != null && worker.isAlive()) {
                Toast.makeText(this, "A scan is already running", Toast.LENGTH_SHORT).show();
                return;
            }
            cancelledStatic = false;
            worker = new Thread(() -> runScan(from, to), "did-scan");
            worker.start();
        }
        startBtn.setText(startButtonLabel());
        results.setText("");
        setStatus("Connecting...");
    }

    private void runScan(int from, int to) {
        ElmClient elm = new ElmClient();
        // A documented 0000-FFFF sweep runs for tens of minutes. Without a wake
        // lock the device suspends when the screen times out, the scan thread
        // stops being scheduled, and every remaining DID is silently recorded as
        // "no answer" - a truncated report that looks like a completed one.
        android.os.PowerManager.WakeLock lock = null;
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                lock = pm.newWakeLock(
                        android.os.PowerManager.PARTIAL_WAKE_LOCK, "tataev:scan");
                // Not reference counted, so renewing extends the lease rather
                // than stacking a fresh acquisition on every progress callback.
                lock.setReferenceCounted(false);
                lock.acquire(SCAN_LOCK_MS);
            }
        } catch (RuntimeException ignored) {
        }
        try {
            BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = bm == null
                    ? BluetoothAdapter.getDefaultAdapter() : bm.getAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                setStatus("Bluetooth is off");
                return;
            }
            BluetoothDevice dev = ElmClient.findAdapter(adapter, prefs.adapterName());
            if (dev == null) {
                setStatus("No paired OBD adapter found");
                return;
            }
            elm.connect(dev);
            if (cancelledStatic) {
                setStatus("Stopped");
                return;
            }

            String bmsId = prefs.bmsRequestId();
            boolean detected = false;
            elm.useProtocol(ProtocolLadder.atspForId(bmsId, prefs.bmsProtocol()));
            if (bmsId == null || bmsId.isEmpty()) {
                setStatus("Locating BMS...");
                ElmClient.BmsInfo info = elm.detectBms(BmsFields.BMS_CANDIDATES,
                        msg -> setStatus(msg));
                if (info == null) {
                    setStatus("Could not find a BMS on 11-bit CAN. Connect from the "
                            + "dashboard first - it searches every protocol and saves "
                            + "what it finds - then scan.");
                    return;
                }
                bmsId = info.requestId;
                detected = true;
                // Saved LATER, after the VIN read below: the scanned car may not
                // be the active profile's car, and saving now would pin its
                // address on the wrong profile.
            }
            if (detected) {
                // detectBms already ran initAdapter(); repeating it is a second
                // ATZ hardware reset that discards the addressing just set up.
                elm.targetAndOpenSession(bmsId);
            } else {
                elm.initAndTargetBms(bmsId);
                // Prove the address before sweeping tens of thousands of DIDs
                // against it. ATSH/ATCRA are adapter-local and answer OK for any
                // address at all, so an address saved from a different vehicle
                // produced a full sweep of silence and then reported "0 DIDs
                // answered / Scan complete" - which reads as "this BMS has no
                // data" rather than "wrong address".
                //
                // Any UDS reply proves it, including a negative one. Deliberately
                // NOT a check that some KNOWN DID answers: a vehicle whose map
                // differs from the Nexon's is precisely the vehicle this screen
                // exists to serve, and it would fail that test while being
                // perfectly scannable.
                //
                // For the same reason the fallback asks for the DID this vehicle
                // actually uses rather than the hardcoded Nexon default. On an ECU
                // that stays silent for a DID it does not serve, probing 3402 on a
                // model already remapped away from it would refuse to scan the one
                // vehicle this screen is for - and the advice it prints, forget the
                // address and scan again, would not help.
                if (!elm.respondsAtAll(BmsFields.DID_SYSTEM_NAME)
                        && !elm.respondsAtAll(
                                BmsFields.effectiveDid(BmsFields.ALL.get(0), prefs))) {
                    setStatus("BMS " + bmsId + " is not answering. Connect from the "
                            + "dashboard first - it finds the battery controller again - "
                            + "then scan.");
                    return;
                }
            }
            if (cancelledStatic) {
                setStatus("Stopped");
                return;
            }
            // Which car is being scanned - so a freshly detected address is not
            // pinned onto a profile belonging to a different vehicle. Held in a
            // LOCAL until the results publish below: the static store carries
            // the PREVIOUS sweep's results for the whole duration of this one,
            // and publishing the new VIN early would pair those old results
            // with this car's identity for tens of minutes.
            final String scannedVin = ProfileMatch.extractVin(
                    elm.readIdString(BmsFields.DID_VIN));
            if (detected && !writeBlockedForActive(scannedVin)) {
                prefs.setBmsRequestId(bmsId);
            }
            final String usedId = bmsId;

            DidScanner scanner = new DidScanner();
            // Give the anchor search the DIDs this vehicle actually uses, so a
            // re-scan of an already-remapped model can still use the pre-filter
            // instead of falling back to single reads for the entire sweep.
            java.util.List<String> anchors = new java.util.ArrayList<>();
            for (BmsFields.Field f : BmsFields.ALL) {
                String did = BmsFields.effectiveDid(f, prefs);
                if (!anchors.contains(did)) anchors.add(did);
            }
            scanner.setAnchorCandidates(anchors);
            final android.os.PowerManager.WakeLock held = lock;
            scanner.scan(elm, from, to, (did, hitCount, pct) -> {
                setStatus(String.format(Locale.ROOT, "Scanning %04X ... %d found (%d%%)",
                        did, hitCount, pct));
                // Renew, do not just hold. A 0000-FFFF sweep is ~21,800 batched
                // probes plus two single reads per hit and realistically outruns
                // one hour; past the original lease the device suspends and every
                // remaining DID records as "no answer" - the silently truncated
                // report the lock exists to prevent.
                if (held != null) {
                    try {
                        held.acquire(SCAN_LOCK_MS);
                    } catch (RuntimeException ignored) {
                    }
                }
                return !cancelledStatic;
            });

            List<DidScanner.Hit> hits = scanner.hits();
            // Compute the hypotheses once and pass them on: the search is
            // O(n^3) over the voltage band and was previously run three times.
            DidScanner.Analysis analysis = DidScanner.analyse(hits);
            java.util.List<DidScanner.VoltageHypothesis> hyp = analysis.hypotheses;
            lastAnalysisNote = analysis.note();
            if (scanner.linkLost()) {
                String warn = String.format(Locale.ROOT, "LINK LOST at DID %04X: "
                        + "the sweep was cut short and every DID after that point "
                        + "was never asked. Treat these results as partial and "
                        + "re-run the scan.", scanner.linkLostAtDid());
                lastAnalysisNote = lastAnalysisNote.isEmpty()
                        ? warn : warn + "\n" + lastAnalysisNote;
            }
            Map<String, String> suggestion = DidScanner.suggest(hits, hyp);
            lastSuggestion = suggestion;
            lastHypotheses = hyp;
            // Publish to the static store FIRST: if this Activity is already
            // gone, showResults() will bail out and only these survive.
            lastResultHits = hits;
            lastResultSuggestion = suggestion;
            lastResultHypotheses = lastHypotheses;
            lastResultNote = lastAnalysisNote;
            lastResultVin = scannedVin;
            lastResultStatus = scanner.linkLost()
                    ? String.format(Locale.ROOT, "Link lost at %04X - partial results",
                            scanner.linkLostAtDid())
                    : "Scan complete";
            try {
                String when = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)
                        .format(new java.util.Date());
                String preamble = String.format(Locale.ROOT, "Scanned: %04X-%04X\nWhen: %s\nApp: %s\n",
                        from, to, when, CsvLogger.appVersion(this)) + elm.caps().report();
                lastReport = DidScanner.writeReport(
                        CsvLogger.logsDir(this), usedId, preamble, hits, suggestion, analysis,
                        scanner.linkLost()
                                ? String.format(Locale.ROOT,
                                        "LINK LOST at DID %04X - PARTIAL RESULTS: every "
                                        + "DID after that point was never asked.",
                                        scanner.linkLostAtDid())
                                : "");
            } catch (Exception e) {
                // A failed report must not lose the on-screen result - but the
                // PREVIOUS sweep's file must not stand in for this one either:
                // "Share scan report" would silently send the wrong scan's data.
                lastReport = null;
            }
            lastResultReport = lastReport;
            lastResultCancelled = cancelledStatic;
            resultsSerial++;
            showResults(hits, suggestion, lastResultCancelled);
        } catch (Exception e) {
            setStatus("Scan failed: " + e.getMessage());
        } finally {
            elm.close();
            if (lock != null && lock.isHeld()) {
                try {
                    lock.release();
                } catch (RuntimeException ignored) {
                }
            }
            ui.post(() -> startBtn.setText(startButtonLabel()));
            // The thread is held in a static field and its body captures this
            // Activity, so a finished thread left in that field pins the whole
            // dead view hierarchy until someone starts another scan. Nothing
            // needs it once it has run.
            if (worker == Thread.currentThread()) worker = null;
        }
    }

    private void showResults(List<DidScanner.Hit> hits, Map<String, String> suggestion,
                             boolean cancelled) {
        // isFinishing() alone misses a recreation (dark mode, font scale, a fold):
        // the old instance is destroyed with isFinishing() false. Same test as
        // MapScreen.gone(); the SDK_INT >= 17 guard it used to carry was dead at
        // minSdk 24.
        if (isFinishing() || isDestroyed()) {
            // The Activity went away mid-sweep. The results are already in the
            // static store, so the next instance picks them up in onCreate.
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(hits.size()).append(" DIDs answered\n");
        if (hits.isEmpty()) {
            sb.append("Nothing answered across the whole range. Either the range "
                    + "holds no data on this model, or the BMS address is wrong - "
                    + "connect from the dashboard once so it is found again, then "
                    + "scan.\n");
        }
        sb.append('\n');
        sb.append("DID   len  sample   moves\n");
        for (DidScanner.Hit h : hits) {
            sb.append(String.format(Locale.ROOT, "%-5s %-4d %-8s %s%n",
                    h.did, h.length, h.firstHex, h.changed() ? "yes" : "-"));
        }
        sb.append("\nSuggested mapping:\n");
        if (suggestion.isEmpty()) {
            sb.append("  nothing confident enough to name automatically.\n"
                    + "  Use the voltage choices below if any are offered, or\n"
                    + "  share the report and map it from a desktop.\n");
        } else {
            for (Map.Entry<String, String> e : suggestion.entrySet()) {
                sb.append("  ").append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
            }
        }
        // What the analysis had to leave out. DidScanner computed this and said in
        // a comment that truncation "is reported rather than silent" - but nothing
        // rendered it, so dropping responders past the band cap WAS silent, and a
        // shortlist built from part of the evidence looked like one built from all
        // of it. The note travels with the stored results so a recreated screen
        // keeps showing it.
        String note = lastAnalysisNote;
        if (note != null && !note.isEmpty()) sb.append('\n').append(note).append('\n');
        if (lastReport != null) {
            sb.append("\nSaved: ").append(lastReport.getName()).append('\n');
        }
        final String text = sb.toString();
        ui.post(() -> {
            results.setText(text);
            showHypotheses();
            setStatus(cancelled ? "Stopped" : lastResultStatus);
        });
    }

    /**
     * Offer the min/max index ORDER as a choice, because a scan cannot measure it.
     *
     * Two static samples cannot separate a minimum index from a maximum one: both
     * move and both sit inside 1..series. Only current settles it - a group with
     * excess resistance sags on discharge and rises on charge, so it swaps
     * indices when the current reverses - and a scan collects no current.
     *
     * suggest() therefore emits the Nexon ordering (higher DID = minimum) as an
     * assumption. This is where a human can overrule it, the same way they pick
     * the voltage reading.
     */
    private void showIndexChoice() {
        if (lastSuggestion == null) return;
        final String minDid = lastSuggestion.get("cell_min_idx");
        final String maxDid = lastSuggestion.get("cell_max_idx");
        if (minDid == null || maxDid == null) return;

        TextView head = new TextView(this);
        head.setText("Cell index order (a guess, not a measurement):");
        head.setTextColor(Palette.OK);
        head.setTextSize(14);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        head.setPadding(0, dp(16), 0, dp(4));
        hypothesisBox.addView(head);

        TextView why = new TextView(this);
        why.setText("A scan sees no current, and without current there is nothing to "
                + "tell a minimum index from a maximum one. This assumes the order "
                + "found on a Nexon EV Max. If your weakest-cell number looks like "
                + "the strongest, swap it.");
        why.setTextColor(Palette.MUTED);
        why.setTextSize(11);
        hypothesisBox.addView(why);

        final TextView state = new TextView(this);
        state.setTextColor(Palette.TEXT);
        state.setTextSize(12);
        state.setTypeface(Typeface.MONOSPACE);
        state.setPadding(0, dp(6), 0, dp(4));
        state.setText("min = " + minDid + "   max = " + maxDid);
        hypothesisBox.addView(state);

        Button swap = new Button(this);
        swap.setAllCaps(false);
        swap.setText("Swap min and max index");
        swap.setTextSize(12);
        swap.setOnClickListener(v -> {
            String curMin = lastSuggestion.get("cell_min_idx");
            String curMax = lastSuggestion.get("cell_max_idx");
            lastSuggestion.put("cell_min_idx", curMax);
            lastSuggestion.put("cell_max_idx", curMin);
            lastResultSuggestion = lastSuggestion;
            state.setText("min = " + curMax + "   max = " + curMin);
            Toast.makeText(this, "Swapped. Tap \"Apply suggested mapping\" to keep it.",
                    Toast.LENGTH_LONG).show();
        });
        hypothesisBox.addView(swap);
    }

    /**
     * Offer the voltage readings as a choice.
     *
     * Pack voltage and cell voltage are numerically indistinguishable on a
     * ~100-cell pack, and rival readings are each self-consistent, so the app must
     * not pick silently. A driver knows their own car's nominal pack voltage, so
     * this is a one-tap decision for a human and an impossible one for a heuristic.
     */
    private void showHypotheses() {
        hypothesisBox.removeAllViews();
        showIndexChoice();
        // BEFORE the early return: a scan that found no voltage reading at all is
        // exactly the case a whole-percent SOC choice exists for.
        showSocChoice();
        if (lastHypotheses == null || lastHypotheses.isEmpty()) return;

        TextView head = new TextView(this);
        head.setText("Pick the reading that matches your car:");
        head.setTextColor(Palette.OK);
        head.setTextSize(14);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        head.setPadding(0, dp(16), 0, dp(4));
        hypothesisBox.addView(head);

        TextView why = new TextView(this);
        why.setText("Pack volts and cell millivolts overlap numerically, so these "
                + "cannot be told apart automatically. Choose the one whose pack "
                + "voltage matches your model (Nexon EV Max is about 350 V).");
        why.setTextColor(Palette.MUTED);
        why.setTextSize(11);
        hypothesisBox.addView(why);

        for (DidScanner.VoltageHypothesis h : lastHypotheses) {
            Button b = new Button(this);
            b.setAllCaps(false);
            b.setText(h.describe());
            b.setTextSize(12);
            b.setOnClickListener(v -> {
                if (refuseWhileLive()) return;
                Map<String, String> m = new java.util.LinkedHashMap<>();
                m.put("pack_v", h.packDid);
                m.put("cell_max_mv", h.cellMaxDid);
                m.put("cell_min_mv", h.cellMinDid);
                if (applyOverrides(m)) {
                    // The unit travels with the choice: a 10 mV controller's
                    // cells decode to millivolts through a scale override, so
                    // every downstream role keeps reading mV.
                    String cellScale = h.cellUnitMv == 10 ? "10;0;2" : "";
                    prefs.setScaleOverride("cell_max_mv", cellScale);
                    prefs.setScaleOverride("cell_min_mv", cellScale);
                    prefs.setScaleOverride("pack_v", "");
                    Toast.makeText(this, String.format(Locale.ROOT,
                            "Set pack=%s, max=%s, min=%s (%.0f groups%s)",
                            h.packDid, h.cellMaxDid, h.cellMinDid, h.series,
                            h.cellUnitMv == 10 ? ", cells in 10 mV" : ""),
                            Toast.LENGTH_LONG).show();
                }
            });
            hypothesisBox.addView(b);
        }
    }

    /**
     * Offer SOC candidates when no tenths SOC was suggested. A parked car's SOC
     * neither moves nor reads in tenths on every model, and the one person who
     * knows the right number is looking at the dash.
     *
     * Two lists, because a 2-byte SOC can be either unit. The whole-percent one
     * is the common case. Under it go the DIDs the voltage search set aside as
     * pack or cell voltages: pack volts, 10 mV cells and a tenths SOC are all
     * 2-byte counts in overlapping ranges, so the rule that stops a moving cell
     * being named "SOC 32.5 %" can also catch a genuine SOC reading like one.
     * Without this second list that owner has no SOC and nothing on the screen
     * able to set it - see DidScanner.socCandidatesTenths.
     */
    private void showSocChoice() {
        if (lastSuggestion == null || lastSuggestion.containsKey("soc_pct")) return;
        if (lastResultHits == null) return;
        List<DidScanner.Hit> soc = DidScanner.socCandidates(lastResultHits, lastSuggestion);
        List<DidScanner.Hit> tenths = DidScanner.socCandidatesTenths(
                lastResultHits, lastHypotheses, lastSuggestion);
        if (soc.isEmpty() && tenths.isEmpty()) return;

        TextView head = new TextView(this);
        head.setText("Which of these is the dash's charge percentage?");
        head.setTextColor(Palette.OK);
        head.setTextSize(14);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        head.setPadding(0, dp(16), 0, dp(4));
        hypothesisBox.addView(head);

        if (!soc.isEmpty()) {
            TextView why = new TextView(this);
            why.setText("No tenths-of-a-percent SOC was found. These read 0-100 and may be "
                    + "the charge in whole percent - pick the one matching the dash.");
            why.setTextColor(Palette.MUTED);
            why.setTextSize(11);
            hypothesisBox.addView(why);
        }

        for (DidScanner.Hit h : soc) {
            Button b = new Button(this);
            b.setAllCaps(false);
            b.setTextSize(12);
            b.setText(h.did + " = " + h.first() + " %" + (h.changed() ? "  (moved)" : ""));
            b.setOnClickListener(v -> {
                if (refuseWhileLive()) return;
                Map<String, String> m = new java.util.LinkedHashMap<>();
                m.put("soc_pct", h.did);
                if (applyOverrides(m)) {
                    prefs.setScaleOverride("soc_pct", "1;0;" + h.length);
                    Toast.makeText(this, "SOC = " + h.did + " in whole percent",
                            Toast.LENGTH_LONG).show();
                }
            });
            hypothesisBox.addView(b);
        }

        if (tenths.isEmpty()) return;
        TextView also = new TextView(this);
        also.setText("Or in tenths of a percent - these were set aside as a pack or cell "
                + "voltage, which reads the same numerically. Pick one only if it "
                + "matches the dash.");
        also.setTextColor(Palette.MUTED);
        also.setTextSize(11);
        also.setPadding(0, dp(8), 0, 0);
        hypothesisBox.addView(also);

        for (DidScanner.Hit h : tenths) {
            Button b = new Button(this);
            b.setAllCaps(false);
            b.setTextSize(12);
            b.setText(String.format(Locale.ROOT, "%s = %.1f %% (tenths)",
                    h.did, h.first() / 10.0));
            b.setOnClickListener(v -> {
                if (refuseWhileLive()) return;
                Map<String, String> m = new java.util.LinkedHashMap<>();
                m.put("soc_pct", h.did);
                if (applyOverrides(m)) {
                    prefs.setScaleOverride("soc_pct", "0.1;0;2");
                    Toast.makeText(this, "SOC = " + h.did + " in tenths of a percent",
                            Toast.LENGTH_LONG).show();
                }
            });
            hypothesisBox.addView(b);
        }
    }

    private void applySuggestion() {
        if (lastSuggestion == null || lastSuggestion.isEmpty()) {
            Toast.makeText(this, "Run a scan first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (refuseWhileLive()) return;
        if (applyOverrides(lastSuggestion)) {
            Toast.makeText(this, lastSuggestion.size() + " DIDs mapped",
                    Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Refuse to remap DIDs while the service is polling.
     *
     * start() already refuses to SCAN while the service is live, but the result
     * buttons wrote overrides whenever they were tapped - and scan results survive
     * statically across visits, so they are tappable long after the sweep. Since
     * Settings stays reachable while RUNNING, the sequence connect -> record ->
     * Settings -> Scan vehicle -> tap a stored hypothesis had the poll thread
     * reading new DIDs while the open CSV's header still named the old ones in its
     * raw_&lt;role&gt;_&lt;DID&gt; columns. Those columns exist precisely so a log
     * always says where each number came from; a mid-file remap breaks that
     * promise silently and the file cannot be repaired afterwards.
     */
    private boolean refuseWhileLive() {
        if (!BmsService.isActive()) return false;
        Toast.makeText(this, "Stop monitoring on the main screen first - remapping "
                + "DIDs mid-session would not match the log already being written",
                Toast.LENGTH_LONG).show();
        return true;
    }

    /**
     * May scan data be written into the ACTIVE profile? Blocked on a known
     * conflict, and - the same policy BmsService.identifyVehicle applies on its
     * unresolved path - when the scanned car returned no VIN while the active
     * profile IS VIN-keyed: this may not be that profile's car at all.
     */
    private boolean writeBlockedForActive(String scanned) {
        if (vinConflictsWithActive(scanned)) return true;
        return (scanned == null || scanned.isEmpty())
                && !prefs.profileVin(prefs.activeProfile()).isEmpty();
    }

    /**
     * Does writing this car's data into the ACTIVE profile conflict with what
     * the app knows? Two ways it can: the active profile is keyed to a
     * different VIN, or the scanned VIN already belongs to another profile.
     * An unknown VIN on both sides is not a conflict - there is nothing to know.
     */
    private boolean vinConflictsWithActive(String scanned) {
        if (scanned == null || scanned.isEmpty()) return false;
        int active = prefs.activeProfile();
        String activeVin = prefs.profileVin(active);
        if (!activeVin.isEmpty()) return !scanned.equals(activeVin);
        int owner = prefs.profileByVin(scanned);
        return owner > 0 && owner != active;
    }

    /**
     * Write a set of overrides, refusing any that would give two roles one DID.
     *
     * pollOnce keys both the width lookup and the batch results by DID string, so
     * two roles resolving to the same DID collapse into a single entry: both roles
     * receive the SAME bytes, get decoded two different ways, and are written to
     * two CSV columns as though independently measured. suggest() can produce this
     * on its own by mapping a role onto a DID that is still another role's
     * default, so checking only the incoming set is not enough - the check has to
     * be against the map that will actually be in force.
     *
     * @return true if everything was written
     */
    private boolean applyOverrides(Map<String, String> wanted) {
        if (isScanning()) {
            // The static store still holds the PREVIOUS sweep's results while a
            // new one runs; applying them mid-sweep would pair old results with
            // the car (and profile) being scanned right now.
            Toast.makeText(this, "Wait for the scan to finish - the results on "
                    + "screen are from the previous sweep", Toast.LENGTH_LONG).show();
            return false;
        }
        if (writeBlockedForActive(lastResultVin)) {
            String why = vinConflictsWithActive(lastResultVin)
                    ? "This scan is from a different vehicle (VIN " + lastResultVin
                            + ") than the active profile."
                    : "This scan read no VIN, but the active profile is bound to "
                            + "one - it may be a different car.";
            Toast.makeText(this, why + " Connect on the main screen first so the "
                    + "app switches to the right profile, then re-scan and apply.",
                    Toast.LENGTH_LONG).show();
            return false;
        }
        Map<String, String> effective = new java.util.LinkedHashMap<>();
        for (BmsFields.Field f : BmsFields.ALL) {
            String did = wanted.containsKey(f.key)
                    ? wanted.get(f.key)
                    : BmsFields.effectiveDid(f, prefs);
            effective.put(f.key, did == null ? "" : did.toUpperCase(Locale.ROOT));
        }
        Map<String, String> owner = new java.util.HashMap<>();
        for (Map.Entry<String, String> e : effective.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            String clash = owner.put(e.getValue(), e.getKey());
            if (clash != null) {
                Toast.makeText(this, "Not applied: " + e.getValue() + " would be read "
                        + "as both " + clash + " and " + e.getKey()
                        + ". Two roles cannot share a DID.", Toast.LENGTH_LONG).show();
                return false;
            }
        }
        for (Map.Entry<String, String> e : wanted.entrySet()) {
            prefs.setDidOverride(e.getKey(), e.getValue());
        }
        return true;
    }

    private void shareReport() {
        if (lastReport == null || !lastReport.exists()) {
            Toast.makeText(this, "No report yet", Toast.LENGTH_SHORT).show();
            return;
        }
        Uri uri = FileSharing.uriFor(this, lastReport);
        if (uri == null) {
            Toast.makeText(this, "Cannot share: " + lastReport.getAbsolutePath(),
                    Toast.LENGTH_LONG).show();
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, "Share scan report"));
    }

    @Override
    protected void onDestroy() {
        // isFinishing(), not unconditionally: a configuration change - dark mode,
        // font scale, a display-size change - destroys and recreates this
        // Activity, and that used to abort a sweep that can run for tens of
        // minutes with no way to tell it had happened.
        if (isFinishing()) cancelledStatic = true;
        super.onDestroy();
    }

    // ------------------------------------------------------------- helpers

    private void setStatus(String s) {
        lastStatus = s;
        ui.post(() -> {
            if (status != null) status.setText(s);
        });
    }

    /** A DID is 16-bit; anything else is rejected rather than silently clamped. */
    private Integer parseHex(EditText e) {
        String t = e.getText().toString().trim();
        if (t.isEmpty() || t.length() > 4) return null;
        try {
            int v = Integer.parseInt(t, 16);
            return (v < 0 || v > 0xFFFF) ? null : v;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private EditText hexField(String value) {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_TEXT);
        e.setText(value);
        e.setTextColor(Palette.TEXT);
        return e;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView note(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Palette.MUTED);
        t.setTextSize(12);
        t.setPadding(0, dp(8), 0, dp(8));
        return t;
    }

    private View labelled(String label, View field) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(Palette.SOFT);
        t.setTextSize(12);
        box.addView(t);
        box.addView(field);
        return box;
    }
}

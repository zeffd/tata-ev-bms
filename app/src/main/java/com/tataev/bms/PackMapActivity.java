package com.tataev.bms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * The pack coverage map, for one saved log or for the session in progress.
 *
 * Live mode reads the map the service has been accumulating rather than building
 * its own, so the picture covers the whole drive rather than only the time this
 * screen happened to be open.
 */
public final class PackMapActivity extends MapScreen {

    static final String EXTRA_LOG_PATH = "log_path";

    private boolean live;
    /** The map the screen is currently showing; the tap readout reads from it. */
    private PackMap current = new PackMap();
    /** What the findings rows were last built from; live mode renders every 1.5 s. */
    private String findingsKey = "";
    /** Named in the shared report: the file, or the live session. */
    private String sourceName = "live session";
    /** The VIN a loaded log recorded; the report must name that car, not the active profile's. */
    private String sourceVin = "";

    private final BroadcastReceiver updates = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            refresh();
        }
    };

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refresh();
            ui.postDelayed(this, 1500);
        }
    };

    private String path() {
        return getIntent() == null ? null : getIntent().getStringExtra(EXTRA_LOG_PATH);
    }

    @Override
    String title() {
        return path() == null ? "Live pack map" : "Pack map";
    }

    @Override
    String[] legendTexts() {
        // Every line claims exactly what the evidence supports - "may", not promises.
        return new String[]{
                "weak module - seen at both ends, high resistance",
                "watch - often weakest under load, a regen drive may settle it",
                "low charge - a full charge may fix it",
                "no sign of trouble",
                "no data yet"};
    }

    @Override
    void onReady() {
        live = path() == null;
        if (live) {
            subtitle.setText("Fills in as you drive. This session only.");
        } else {
            loadFile(new File(path()));
        }
    }

    /**
     * Parse on a worker, never on the main thread.
     *
     * An overnight charge log at ~1 s a sample is thousands of rows across 50
     * columns, and splitting that allocates hundreds of thousands of strings.
     * Doing it inside onCreate is jank at best and an ANR at worst.
     */
    private void loadFile(final File f) {
        sourceName = f.getName();
        subtitle.setText(f.getName());
        readout.setText("Reading " + f.getName() + "...");
        new Thread(() -> {
            LogReader.Result res;
            try {
                res = LogReader.read(f);
            } catch (Exception e) {
                ui.post(() -> {
                    if (gone()) return;
                    Toast.makeText(this, "Cannot read " + f.getName(),
                            Toast.LENGTH_LONG).show();
                    subtitle.setText("Cannot read this log");
                    readout.setText("");
                });
                return;
            }
            final LogReader.Result r = res;
            ui.post(() -> {
                if (gone()) return;
                showLoaded(r);
            });
        }, "log-read").start();
    }

    private void showLoaded(LogReader.Result res) {
        current = res.map;
        sourceVin = res.vin == null ? "" : res.vin;
        mapView.setMap(res.map);
        render(res.map);
        if (res.rows == 0) {
            readout.setText("No usable rows - log is missing columns the map needs "
                    + "(pack V, cell min/max, both indices, current).");
        } else if (res.flipCorrected) {
            // Worth saying out loud rather than silently fixing.
            readout.setText("Read " + res.rows + " rows. Older log with reversed "
                    + "cell indices - corrected automatically.");
        } else {
            readout.setText("Read " + res.rows + " rows. Tap a group.");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateScreenOnFlag();
        if (!live) return;
        IntentFilter f = new IntentFilter(BmsService.ACTION_UPDATE);
        // Signature permission on BOTH ends - see MainActivity.onResume.
        if (Build.VERSION.SDK_INT >= 34) {
            registerReceiver(updates, f, BmsService.PERM_UPDATES, null,
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(updates, f, BmsService.PERM_UPDATES, null);
        }
        ui.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (!live) return;
        try {
            unregisterReceiver(updates);
        } catch (IllegalArgumentException ignored) {
        }
        ui.removeCallbacks(ticker);
    }

    /**
     * Same rule as the dashboard: hold the screen awake only while live data is
     * actually arriving. A saved log is ordinary reading, and a live map whose
     * service has stopped is just a picture - pinning the display on for either
     * would drain a docked phone for nothing.
     */
    private void updateScreenOnFlag() {
        if (live && BmsService.isActive()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void refresh() {
        updateScreenOnFlag();
        PackMap m = BmsService.packMap();
        current = m;
        mapView.setMap(m);
        render(m);
        // >= 0, not > 0: group 0 is a real group on a pack numbered from zero.
        if (mapView.selected() >= 0) readout.setText(describe(mapView.selected()));
    }

    private void render(PackMap m) {
        // ONE read under ONE lock, so the summary describes one instant.
        PackMap.Stats s = m.stats();
        int n = s.seriesCount;
        // Two lines: on one it ran to ~490 dp and wrapped mid-phrase on a phone.
        stats.setText(String.format(Locale.ROOT,
                "%d samples  ·  seen %d/%d\nweak %d  ·  watch %d  ·  low charge %d",
                s.samples, s.seen, n, s.suspect, s.watch, s.balance));

        List<PackMap.Snapshot> notable = m.notable();
        StringBuilder key = new StringBuilder();
        for (PackMap.Snapshot g : notable) {
            // Excess to 0.05 mOhm: at 0.01 the rows were rebuilt every time the
            // fit moved under load, several times a minute.
            key.append(g.index).append(g.state)
               .append(Math.round(g.excessMilliOhm * 20)).append(';');
        }
        // Rebuilt only when the verdicts change: live mode calls this every 1.5 s.
        if (!key.toString().equals(findingsKey)) {
            findingsKey = key.toString();
            findingsBox.removeAllViews();
            for (PackMap.Snapshot g : notable) findingsBox.addView(findingRowFor(g));
        }

        StringBuilder sb = new StringBuilder();
        // Who limits the pack under load and who holds the floor at rest - from
        // the same locked snapshot as everything else on this screen.
        if (s.loadLeader != null || s.restLeader != null) {
            sb.append("Weakest");
            if (s.loadLeader != null) {
                sb.append(String.format(Locale.ROOT, " under load: group %d (%d%%)",
                        s.loadLeader.index, s.loadLeader.loadMinPct));
            }
            if (s.restLeader != null) {
                sb.append(s.loadLeader != null ? "  ·  at rest" : " at rest");
                sb.append(String.format(Locale.ROOT, ": group %d (%d%%)",
                        s.restLeader.index, s.restLeader.restMinPct));
            }
            sb.append('\n');
        }
        double pr = s.packMilliOhm;
        if (!Double.isNaN(pr)) {
            sb.append(String.format(Locale.ROOT,
                    "Pack resistance: %.1f mΩ  (%.2f mΩ/group)\n",
                    pr, pr / Math.max(n, 1)));
        } else {
            sb.append("Pack resistance: not yet - needs a wider current range.\n");
        }
        double rest = s.restSpreadMv, load = s.loadSpreadMv, res = s.resistiveSpreadMv;
        if (!Double.isNaN(res)) {
            sb.append(String.format(Locale.ROOT,
                    "Spread: %.1f mV at rest, %.1f mV under load.\n"
                            + "The extra %.1f mV is resistance - balancing can't fix it.\n",
                    rest, load, res));
        } else if (!Double.isNaN(rest)) {
            sb.append(String.format(Locale.ROOT,
                    "Spread: %.1f mV at rest. Needs more load to split balance from "
                            + "resistance.\n", rest));
        }
        // How low the drive went, and who held the rest floor per SOC band. The
        // fault this app exists for lives near empty, and no log had been there.
        String soc = PackReport.socLines(m);
        if (!soc.isEmpty()) sb.append('\n').append(soc);
        // The verdicts live in the findings rows, the grid colours and the
        // legend; the only verdict sentence here is the reassuring null case.
        if (s.suspect == 0 && s.watch == 0 && s.samples > 0) {
            sb.append("\nNo suspect group yet - keep driving.");
        }
        footnote.setText(sb.toString());
    }

    private View findingRowFor(PackMap.Snapshot g) {
        String verdict;
        String detail;
        switch (g.state) {
            case SUSPECT:
                verdict = "weak module";
                // Percentage first: it does not depend on the current scale.
                detail = String.format(Locale.ROOT,
                        "%+d%% over the pack's average group (%+.2f mΩ)  ·  weakest in %d%% "
                                + "of hard driving",
                        g.excessPct, g.excessMilliOhm, g.loadMinPct);
                break;
            case WATCH:
                verdict = "watch";
                detail = String.format(Locale.ROOT,
                        "weakest in %d%% of hard driving", g.loadMinPct);
                break;
            default:
                verdict = "low charge";
                detail = String.format(Locale.ROOT,
                        "weakest at rest %d%% of the time", g.restMinPct);
        }
        return findingRow(g.state, g.index, verdict, detail);
    }

    /**
     * The tapped group's own numbers. The LEGEND says what each colour means
     * and what to do about it; this deliberately repeats none of that.
     */
    @Override
    String describe(int index) {
        if (index < 0) return "Tap a group.";
        PackMap.Snapshot g = current.snapshot(index);
        String head = describeVerdict(index, g);
        String history = GroupMoments.render(g, java.util.TimeZone.getDefault());
        return history.isEmpty() ? head : head + "\n\n" + history;
    }

    /** Today's one- or two-sentence verdict, unchanged. */
    private String describeVerdict(int index, PackMap.Snapshot g) {
        if (g == null || !g.seen) {
            return "Group " + index + " - never the weakest or strongest in any sample.";
        }
        switch (g.state) {
            case SUSPECT:
                // "vs", not "above": the excess is signed.
                return String.format(Locale.ROOT,
                        "Group %d - weakest in %d%% of hard driving (%dx min, "
                                + "%dx max).\nMeasured %+d%% over the pack's average group "
                                + "(%+.2f mΩ).",
                        g.index, g.loadMinPct, g.minCount, g.maxCount,
                        g.excessPct, g.excessMilliOhm);
            case WATCH:
                return String.format(Locale.ROOT,
                        "Group %d - weakest in %d%% of hard driving (%dx min, "
                                + "%dx max).",
                        g.index, g.loadMinPct, g.minCount, g.maxCount);
            case BALANCE:
                return String.format(Locale.ROOT,
                        "Group %d - weakest at rest %d%% of the time (%dx min, "
                                + "%dx max).",
                        g.index, g.restMinPct, g.minCount, g.maxCount);
            default:
                return "Group " + g.index + " - weakest " + g.minCount
                        + "x, strongest " + g.maxCount + "x.";
        }
    }

    /** The map in words, to a share sheet - the paragraph a service centre needs. */
    @Override
    void share() {
        // A loaded log names the car that wrote it, which need not be the active
        // profile's; a log with no VIN column names none rather than the wrong one.
        String vin;
        if (live) {
            Prefs prefs = new Prefs(this);
            vin = prefs.profileVin(prefs.activeProfile());
        } else {
            vin = sourceVin;
        }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "Tata EV BMS pack findings");
        send.putExtra(Intent.EXTRA_TEXT, PackReport.render(sourceName, vin, current));
        startActivity(Intent.createChooser(send, "Share findings"));
    }
}

package com.tataev.bms;

import android.app.AlertDialog;
import android.content.Intent;
import android.view.View;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every drive at once: each saved log replayed through the same PackMap the
 * live screen uses, and the verdicts tallied per group.
 *
 * One drive naming a group is a finding; three drives naming the same group at
 * the same excess is the strongest evidence this app can produce, and until this
 * screen it lived in three separate pack maps and the user's memory.
 */
public final class PackHistoryActivity extends MapScreen {

    /** Null until every log has been read. */
    private PackHistory.Result history;
    /** The VIN whose drives are on screen; "" for logs that recorded none. */
    private String historyVin = "";
    /** Set in onDestroy so the worker stops between files instead of pinning a dead screen. */
    private volatile boolean cancelled;

    @Override
    protected void onDestroy() {
        cancelled = true;
        super.onDestroy();
    }

    @Override
    String title() {
        return "All drives";
    }

    @Override
    String[] legendTexts() {
        return new String[]{
                "weak module - high resistance in most drives that could measure it",
                "watch - often weakest under load in most drives",
                "low charge - the rest floor in most drives",
                "seen, no sign of trouble",
                "never named in any drive"};
    }

    @Override
    void onReady() {
        subtitle.setText("Reading drives...");
        footnote.setText("Each drive is replayed on its own and its verdicts tallied. "
                + "The number on a square is how many drives agree over how many "
                + "there are. A group is a weak module here when at least half the "
                + "drives that could measure it said so.");
        load();
    }

    /**
     * Replay every log on a worker. A drive is thousands of rows; three or four
     * of them on the main thread would be an ANR. Progress goes to the subtitle
     * so a slow phone does not look hung.
     */
    private void load() {
        new Thread(() -> {
            File dir = CsvLogger.logsDir(this);
            File[] all = dir.listFiles();
            List<File> csvs = new ArrayList<>();
            if (all != null) {
                for (File f : all) {
                    if (f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".csv")) {
                        csvs.add(f);
                    }
                }
            }
            // Oldest first, so "drive 1" is the first drive.
            File[] files = csvs.toArray(new File[0]);
            Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
            // Every readable log, keyed by the VIN it recorded. Two cars' logs on
            // one phone must not be tallied into one pack.
            final Map<String, List<PackMap>> byVin = new LinkedHashMap<>();
            // Logs from before the vin column existed. Placed once every file is
            // read: where they belong depends on how many cars the rest name.
            final List<PackMap> preVin = new ArrayList<>();
            int skipped = 0;
            for (int i = 0; i < files.length; i++) {
                if (cancelled) return;
                final String progress = String.format(Locale.ROOT,
                        "Reading drive %d of %d · %s", i + 1, files.length, files[i].getName());
                ui.post(() -> {
                    if (!gone()) subtitle.setText(progress);
                });
                try {
                    LogReader.Result r = LogReader.read(files[i]);
                    if (r.rows > 0 && !r.hasVinColumn) {
                        preVin.add(r.map);
                    } else if (r.rows > 0) {
                        List<PackMap> l = byVin.get(r.vin);
                        if (l == null) {
                            l = new ArrayList<>();
                            byVin.put(r.vin, l);
                        }
                        l.add(r.map);
                    } else {
                        skipped++;
                    }
                } catch (Exception e) {
                    skipped++;
                }
            }
            // Pre-VIN logs go to the one identified car when there is exactly
            // one; a car whose controller serves no VIN (an empty column, not a
            // missing one) keeps its own entry. The rule is pure and tested.
            PackHistory.placePreVin(byVin, preVin);
            final int unusable = skipped;
            ui.post(() -> {
                // isDestroyed() too: a dark-mode or font-scale recreation during
                // the parse leaves isFinishing() false, and a dialog shown on the
                // dead Activity throws BadTokenException.
                if (gone()) return;
                if (byVin.size() <= 1) {
                    String vin = byVin.isEmpty() ? "" : byVin.keySet().iterator().next();
                    List<PackMap> maps = byVin.isEmpty() ? new ArrayList<PackMap>() : byVin.get(vin);
                    show(vin, PackHistory.aggregate(maps), unusable);
                } else {
                    pickVehicle(byVin, unusable);
                }
            });
        }, "pack-history").start();
    }

    /** More than one car has logs here: ask, defaulting to the active profile's. */
    private void pickVehicle(final Map<String, List<PackMap>> byVin, final int skipped) {
        final List<String> vins = new ArrayList<>(byVin.keySet());
        String[] labels = new String[vins.size()];
        Prefs prefs = new Prefs(this);
        String active = prefs.profileVin(prefs.activeProfile());
        int checked = 0;
        for (int i = 0; i < vins.size(); i++) {
            int n = byVin.get(vins.get(i)).size();
            labels[i] = vehicleLabel(vins.get(i)) + " · " + n + (n == 1 ? " drive" : " drives");
            if (vins.get(i).equals(active)) checked = i;
        }
        subtitle.setText("Logs from " + vins.size() + " vehicles on this phone");
        final int[] choice = {checked};
        new AlertDialog.Builder(this)
                .setTitle("Which vehicle?")
                .setSingleChoiceItems(labels, checked, (d, which) -> choice[0] = which)
                .setPositiveButton("Show", (d, w) -> {
                    String vin = vins.get(choice[0]);
                    show(vin, PackHistory.aggregate(byVin.get(vin)), skipped);
                })
                .setNegativeButton("Back", (d, w) -> finish())
                .setCancelable(false)
                .show();
    }

    /** The profile's name when the VIN belongs to one, else the VIN, else a placeholder. */
    private String vehicleLabel(String vin) {
        if (vin == null || vin.isEmpty()) return "unknown vehicle";
        Prefs prefs = new Prefs(this);
        int id = prefs.profileByVin(vin);
        String name = id > 0 ? prefs.profileName(id) : "";
        return name.isEmpty() ? vin : name;
    }

    private void show(String vin, PackHistory.Result h, int skipped) {
        history = h;
        historyVin = vin == null ? "" : vin;
        subtitle.setText(h.drives == 0
                ? "No drive logs with the columns the map needs."
                : vehicleLabel(historyVin) + " · " + h.drives + (h.drives == 1 ? " drive" : " drives")
                        + (skipped > 0 ? " · " + skipped + " unreadable log"
                                + (skipped == 1 ? "" : "s") + " skipped" : ""));
        stats.setText(String.format(Locale.ROOT,
                "%d drives   ·   %d samples   ·   %d groups", h.drives, h.samples, h.seriesCount));
        findingsBox.removeAllViews();
        List<PackHistory.GroupHistory> notable = h.notable();
        for (PackHistory.GroupHistory g : notable) findingsBox.addView(findingRowFor(g));
        if (h.drives > 0 && notable.isEmpty()) {
            findingsBox.addView(Ui.note(this, "No group stood out in any drive."));
        }
        mapView.setGrid(h.grid(), h.sublabels());
        readout.setText("Tap a group.");
    }

    @Override
    String describe(int index) {
        if (index < 0 || history == null || index >= history.groups.length) return "Tap a group.";
        return PackHistory.line(history.groups[index], history.drives).replace("mOhm", "mΩ");
    }

    private View findingRowFor(PackHistory.GroupHistory g) {
        PackMap.State st = g.display();
        int drives = history == null ? 0 : history.drives;
        String verdict;
        String detail;
        switch (st) {
            case SUSPECT:
                verdict = String.format(Locale.ROOT, "weak module in %d of %d drives",
                        g.suspect, drives);
                detail = String.format(Locale.ROOT,
                        "+%d%% to +%d%% over the pack's average group (%.2f to %.2f mΩ)",
                        g.pctLo, g.pctHi, g.excessLo, g.excessHi)
                        + (g.watch > 0 ? String.format(Locale.ROOT, "  ·  watch in %d", g.watch) : "");
                break;
            case WATCH:
                verdict = String.format(Locale.ROOT, "watch in %d of %d drives", g.watch, drives);
                detail = "often weakest under load, never yet seen at the top";
                break;
            default:
                verdict = String.format(Locale.ROOT, "low charge in %d of %d drives",
                        g.balance, drives);
                detail = "holds the floor at rest";
        }
        return findingRow(st, g.index, verdict, detail);
    }

    @Override
    void share() {
        if (history == null) {
            Toast.makeText(this, "Still reading the drives", Toast.LENGTH_SHORT).show();
            return;
        }
        // The car whose drives are on screen - not necessarily the active profile.
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "Tata EV BMS pack findings across drives");
        send.putExtra(Intent.EXTRA_TEXT, history.render(historyVin));
        startActivity(Intent.createChooser(send, "Share findings"));
    }
}

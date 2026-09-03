package com.tataev.bms;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * Every drive log on the device, newest first.
 *
 * Before this screen existed only the newest CSV was reachable, and the logs live
 * in Android/data where a file manager cannot easily get at them on Android 11+ -
 * so every earlier drive was effectively trapped until uninstall deleted it.
 * Each row opens the pack map for that log, or shares the file.
 */
public final class LogsActivity extends Activity {

    private static final int BG = Palette.BG;
    private static final int TILE = Palette.TILE;
    private static final int TEXT = Palette.TEXT;
    private static final int MUTED = Palette.MUTED;
    private static final int OK = Palette.OK;
    private static final int FAINT = Palette.FAINT;

    private LinearLayout list;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.addView(backHeader("Logs"));

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(16), 0, dp(16), dp(28));

        ScrollView sv = new ScrollView(this);
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        SystemBars.pad(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        build();     // a drive may have finished, or a new file started, while away
    }

    /**
     * One row's worth of facts, gathered on the worker.
     *
     * listFiles() plus a lastModified() and a length() per file are each a
     * filesystem round trip, and they all used to happen on the main thread from
     * onResume. Fine at ten logs; visible jank at a few hundred, on a tool that
     * flushes a CSV row every ~1.8 s.
     */
    private static final class Entry {
        final File file;
        final long modified;
        final long length;
        /** When, how long, SOC range, rows - or null if the file would not parse. */
        final LogSummary summary;

        Entry(File f) {
            this.file = f;
            this.modified = f.lastModified();
            this.length = f.length();
            LogSummary s = null;
            try {
                s = LogSummary.read(f);
            } catch (java.io.IOException | RuntimeException ignored) {
                // an unreadable file still gets a row; it just says less
            }
            this.summary = s;
        }
    }

    private void build() {
        list.removeAllViews();

        TextView loading = new TextView(this);
        loading.setText("Reading...");
        loading.setTextSize(13);
        loading.setTextColor(MUTED);
        loading.setPadding(0, dp(24), 0, 0);
        list.addView(loading);

        new Thread(() -> {
            File dir = CsvLogger.logsDir(this);
            File[] all = dir.listFiles();
            ArrayList<Entry> csvs = new ArrayList<>();
            if (all != null) {
                for (File f : all) {
                    if (f.isFile()
                            && f.getName().toLowerCase(Locale.ROOT).endsWith(".csv")) {
                        csvs.add(new Entry(f));
                    }
                }
            }
            // Newest first: that is the drive you just did. Sorted on the stats
            // already gathered, so the comparator does no IO of its own.
            Entry[] arr = csvs.toArray(new Entry[0]);
            Arrays.sort(arr, (a, b) -> Long.compare(b.modified, a.modified));
            ui.post(() -> {
                if (isFinishing()) return;
                show(arr);
            });
        }, "logs-list").start();
    }

    private void show(Entry[] arr) {
        list.removeAllViews();

        if (BmsService.isActive()) {
            list.addView(liveRow());
        }

        if (arr.length == 0) {
            TextView empty = new TextView(this);
            empty.setText("No logs yet.\n\nTurn on Recording on the main screen and "
                    + "drive. Each session writes one CSV here.");
            empty.setTextSize(13);
            empty.setTextColor(MUTED);
            empty.setLineSpacing(0, 1.4f);
            empty.setPadding(0, dp(24), 0, 0);
            list.addView(empty);
            return;
        }

        TextView hint = new TextView(this);
        hint.setText(arr.length + " log" + (arr.length == 1 ? "" : "s")
                + " · tap to open the pack map, Share to send it, long-press to delete");
        hint.setTextSize(11.5f);
        hint.setTextColor(FAINT);
        hint.setPadding(0, dp(4), 0, dp(10));
        list.addView(hint);

        if (arr.length >= 2) {
            // Every log in one share sheet. The old long-press share-all went
            // away with the button it lived on, leaving one chooser per file.
            TextView all = new TextView(this);
            all.setText("Share all " + arr.length + " logs");
            all.setTextSize(12.5f);
            all.setTextColor(OK);
            all.setPadding(0, dp(2), 0, dp(12));
            all.setOnClickListener(v -> shareAll(arr));
            list.addView(all);
            // Two or more drives can be compared; one cannot.
            list.addView(historyRow(arr.length));
        }
        for (Entry e : arr) list.addView(logRow(e));
    }

    /** Entry point to the cross-drive tally, where the strongest evidence lives. */
    private View historyRow(int count) {
        LinearLayout row = card();
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);

        TextView t = new TextView(this);
        t.setText("All drives");
        t.setTextSize(15);
        t.setTextColor(TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        text.addView(t);

        TextView s = new TextView(this);
        s.setText("every log replayed · verdicts tallied per group · " + count + " drives");
        s.setTextSize(11.5f);
        s.setTextColor(MUTED);
        s.setTypeface(Typeface.MONOSPACE);
        text.addView(s);
        row.addView(text, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView go = new TextView(this);
        go.setText("Open →");
        go.setTextSize(13);
        go.setTextColor(OK);
        row.addView(go);

        row.setOnClickListener(v ->
                startActivity(new Intent(this, PackHistoryActivity.class)));
        return row;
    }

    private View liveRow() {
        LinearLayout row = card();
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);

        TextView t = new TextView(this);
        t.setText("Live session");
        t.setTextSize(15);
        t.setTextColor(OK);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        text.addView(t);

        TextView s = new TextView(this);
        PackMap m = BmsService.packMap();
        s.setText(m.samples() == 0
                ? "connected · waiting for the first complete sample"
                : m.samples() + " samples · " + m.seenCount() + " of "
                        + m.seriesCount() + " groups seen so far");
        s.setTextSize(11.5f);
        s.setTextColor(MUTED);
        s.setTypeface(Typeface.MONOSPACE);
        text.addView(s);
        row.addView(text, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView go = new TextView(this);
        go.setText("Open →");
        go.setTextSize(13);
        go.setTextColor(OK);
        row.addView(go);

        row.setOnClickListener(v ->
                startActivity(new Intent(this, PackMapActivity.class)));
        return row;
    }

    private View logRow(Entry e) {
        final File f = e.file;
        LinearLayout row = card();

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);

        // The drive, not the file: when it started, how long it ran, how far the
        // SOC moved. Rows used to be a filename and a byte count, which says
        // nothing about which drive is which.
        LogSummary s = e.summary;
        long when = s != null && s.startMs > 0 ? s.startMs : e.modified;
        TextView name = new TextView(this);
        name.setText(new SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())
                .format(new Date(when)));
        name.setTextSize(14);
        name.setTextColor(TEXT);
        text.addView(name);

        TextView meta = new TextView(this);
        meta.setText((s == null ? "" : s.describe() + " · ") + size(e.length));
        meta.setTextSize(11.5f);
        meta.setTextColor(MUTED);
        meta.setTypeface(Typeface.MONOSPACE);
        text.addView(meta);

        TextView file = new TextView(this);
        file.setText(f.getName());
        file.setTextSize(10.5f);
        file.setTextColor(FAINT);
        file.setTypeface(Typeface.MONOSPACE);
        text.addView(file);

        row.addView(text, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView share = new TextView(this);
        share.setText("Share");
        share.setTextSize(12.5f);
        share.setTextColor(Palette.SOFT);
        share.setPadding(dp(12), dp(8), dp(4), dp(8));
        share.setOnClickListener(v -> share(f));
        row.addView(share);

        row.setOnClickListener(v -> {
            Intent i = new Intent(this, PackMapActivity.class);
            i.putExtra(PackMapActivity.EXTRA_LOG_PATH, f.getAbsolutePath());
            startActivity(i);
        });
        row.setOnLongClickListener(v -> {
            confirmDelete(f);
            return true;
        });
        return row;
    }

    /**
     * Delete one log, after asking.
     *
     * There was no way to remove a log from inside the app at all - files
     * accumulated until an uninstall took the lot. Confirmed rather than
     * immediate, and it says the deletion is permanent, because these files are
     * the only record of a drive and there is no undo: the pack map is derived
     * from them, not stored.
     */
    private void confirmDelete(File f) {
        // Deleting the file the service holds open unlinks the name while the
        // FileWriter keeps flushing to the orphaned inode: the notification goes
        // on counting rows and the drive ends with nothing on disk.
        if (f.getAbsolutePath().equals(CsvLogger.openPath())) {
            Toast.makeText(this, "This log is being written right now - stop "
                    + "monitoring first", Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Delete this log?")
                .setMessage(f.getName() + "\n\nThis cannot be undone. Share it "
                        + "first if you want to keep the data.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, which) -> {
                    if (f.delete()) {
                        Toast.makeText(this, "Deleted " + f.getName(),
                                Toast.LENGTH_SHORT).show();
                        build();
                    } else {
                        Toast.makeText(this, "Could not delete " + f.getName(),
                                Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    private void shareAll(Entry[] arr) {
        ArrayList<Uri> uris = new ArrayList<>();
        for (Entry e : arr) {
            Uri u = FileSharing.uriFor(this, e.file);
            if (u != null) uris.add(u);
        }
        if (uris.isEmpty()) {
            Toast.makeText(this, "Nothing to share", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND_MULTIPLE);
        send.setType("text/csv");
        send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, "Share " + uris.size() + " logs"));
    }

    private void share(File f) {
        Uri uri = FileSharing.uriFor(this, f);
        if (uri == null) {
            Toast.makeText(this, "Cannot share " + f.getName(), Toast.LENGTH_LONG).show();
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/csv");
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.putExtra(Intent.EXTRA_SUBJECT, f.getName());
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, "Share " + f.getName()));
    }

    private LinearLayout card() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(rounded(TILE, dp(8)));
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(8));
        row.setLayoutParams(lp);
        return row;
    }

    private static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private View backHeader(String title) {
        return Ui.backHeader(this, title, null, null);
    }

    private GradientDrawable rounded(int colour, int radius) {
        return Ui.rounded(colour, radius);
    }

    private int dp(int v) {
        return Ui.dp(this, v);
    }
}

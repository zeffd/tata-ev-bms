package com.tataev.bms;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The skeleton the two map screens share: back row with Share, subtitle, stats
 * line, findings rows, the grid, the tap readout, the legend and a footnote.
 *
 * The live-or-replayed map and the cross-drive history differ only in where
 * their verdicts come from and what the words say, so that is all a subclass
 * supplies. They used to be two near-identical 400-line activities.
 */
abstract class MapScreen extends Activity {

    final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    PackMapView mapView;
    TextView subtitle;
    TextView stats;
    TextView readout;
    /** Pack-level prose under the legend. */
    TextView footnote;
    /** One row per named finding, above the grid. */
    LinearLayout findingsBox;

    /**
     * The header title. Called from onCreate BEFORE onReady(), so it may depend
     * only on the intent, constants and field initialisers - never on anything
     * a subclass sets up in onReady().
     */
    abstract String title();

    /**
     * Legend texts for SUSPECT, WATCH, BALANCE, HEALTHY and UNSEEN, in that
     * order. Same rule as {@link #title()}: called before onReady().
     */
    abstract String[] legendTexts();

    /** The tapped group's own numbers, for the readout. */
    abstract String describe(int index);

    /** Send the findings to a share sheet. */
    abstract void share();

    /** Called once the skeleton exists: load data, start tickers. */
    abstract void onReady();

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Palette.BG);
        root.addView(Ui.backHeader(this, title(), "Share findings", this::share));

        subtitle = Ui.note(this, "");
        subtitle.setPadding(dp(16), 0, dp(16), dp(4));
        root.addView(subtitle);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);

        stats = new TextView(this);
        stats.setTextSize(13);
        stats.setTextColor(Palette.TEXT);
        stats.setLineSpacing(0, 1.35f);
        stats.setPadding(dp(16), dp(6), dp(16), dp(12));
        body.addView(stats);

        // The verdicts in words, before the picture. The map coloured squares
        // and the summary listed pack-level numbers, but nothing named WHICH
        // groups - finding a small red square among 104 was left to the user.
        findingsBox = new LinearLayout(this);
        findingsBox.setOrientation(LinearLayout.VERTICAL);
        findingsBox.setPadding(dp(16), 0, dp(16), dp(6));
        body.addView(findingsBox);

        mapView = new PackMapView(this);
        mapView.setOnSelect(() -> readout.setText(describe(mapView.selected())));
        body.addView(mapView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        readout = new TextView(this);
        readout.setTextSize(12);
        readout.setTextColor(Palette.MUTED);
        readout.setTypeface(Typeface.MONOSPACE);
        readout.setLineSpacing(0, 1.3f);
        readout.setPadding(dp(13), dp(11), dp(13), dp(11));
        readout.setBackground(Ui.rounded(Palette.TILE, dp(6)));
        readout.setText("Tap a group.");
        // A screen reader hears what a tap revealed without having to hunt for it.
        readout.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rl.setMargins(dp(16), dp(10), dp(16), dp(6));
        body.addView(readout, rl);

        body.addView(Ui.legend(this, legendTexts()));

        footnote = new TextView(this);
        footnote.setTextSize(12.5f);
        footnote.setTextColor(Palette.MUTED);
        footnote.setLineSpacing(0, 1.4f);
        footnote.setPadding(dp(16), dp(14), dp(16), dp(28));
        body.addView(footnote);

        ScrollView sv = new ScrollView(this);
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        SystemBars.pad(root);

        onReady();
    }

    /** One finding: a swatch, the verdict, the numbers. Tap to select the square. */
    View findingRow(PackMap.State st, final int index, String verdict, String detail) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(8), dp(10), dp(8));
        row.setBackground(Ui.pressable(Palette.TILE, dp(6)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(6));
        row.setLayoutParams(lp);
        row.addView(Ui.swatch(this, st), Ui.swatchParams(this));

        TextView t = new TextView(this);
        t.setTextSize(12.5f);
        t.setTextColor(Palette.TEXT);
        t.setLineSpacing(0, 1.25f);
        t.setText("Group " + index + "  ·  " + verdict + "\n" + detail);
        row.addView(t, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.setOnClickListener(v -> {
            mapView.select(index);
            readout.setText(describe(index));
        });
        Ui.buttonRole(row);      // TalkBack: a row that acts must announce as one
        return row;
    }

    int dp(int v) {
        return Ui.dp(this, v);
    }

    /**
     * True once this screen is going or gone. isFinishing() alone misses a
     * recreation (dark mode, font scale, a fold): the old instance is destroyed
     * with isFinishing() false, and a worker's posted dialog would then throw.
     */
    boolean gone() {
        return isFinishing() || isDestroyed();
    }
}

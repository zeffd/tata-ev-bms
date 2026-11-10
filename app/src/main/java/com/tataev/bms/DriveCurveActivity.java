package com.tataev.bms;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * One saved log against SOC: the cell voltages above, the groups holding each
 * end below, scrolled sideways through the drive.
 *
 * Opened from that log's pack map. The pack map says who was weakest over the
 * whole drive; this says when in the charge - which is where a group that runs
 * out before the rest gives itself away.
 */
public final class DriveCurveActivity extends Activity {

    private static final int MATCH = LinearLayout.LayoutParams.MATCH_PARENT;
    private static final int WRAP = LinearLayout.LayoutParams.WRAP_CONTENT;

    /**
     * What a recreation (multi-window, font size, locale) must keep. The views
     * are built in code with no ids, so the framework saves none of it, and the
     * log is read again on a worker before any of it can be put back.
     */
    private static final String KEY_STATE = "curve_state";
    private static final String KEY_AT_REST = "at_rest";
    private static final String KEY_FOLLOWED = "followed";
    private static final String KEY_COLUMN = "column";
    /** Scroll as a share of the chart's width, so a new screen width still fits. */
    private static final String KEY_SCROLL = "scroll";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private DriveCurve.Curve curve;
    private PackMap map;
    private boolean atRest;
    private int followed = -1;
    private DriveCurve.Step selected;
    private String sourceName = "";
    /** Saved state not yet put back: the log is still being read. */
    private Bundle restore;

    private TextView subtitle;
    /** "Reading ...", or why this log has no curve. */
    private TextView message;
    private LinearLayout content;
    private TextView allButton;
    private TextView restButton;
    private TextView stats;
    private HorizontalScrollView chipScroll;
    private LinearLayout chipRow;
    private final List<TextView> chips = new ArrayList<>();
    private final List<Integer> chipGroups = new ArrayList<>();
    private DriveCurveView chart;
    private DriveCurveView.Axis axis;
    private DriveCurveView.Overview overview;
    private HorizontalScrollView scroller;
    private TextView readout;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        restore = saved == null ? null : saved.getBundle(KEY_STATE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Palette.BG);
        root.addView(Ui.backHeader(this, "Drive curve", "Share findings", this::share));

        subtitle = Ui.note(this, "");
        subtitle.setPadding(dp(16), 0, dp(16), dp(8));
        root.addView(subtitle);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), 0, dp(16), dp(28));

        message = new TextView(this);
        message.setTextSize(13);
        message.setTextColor(Palette.MUTED);
        message.setLineSpacing(0, 1.35f);
        message.setPadding(0, dp(12), 0, dp(12));
        body.addView(message);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setVisibility(View.GONE);
        buildContent(content);
        body.addView(content);

        ScrollView sv = new ScrollView(this);
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        setContentView(root);
        SystemBars.pad(root);

        String path = getIntent() == null ? null
                : getIntent().getStringExtra(PackMapActivity.EXTRA_LOG_PATH);
        if (path == null) {
            message.setText("No log was given to draw.");
            return;
        }
        load(new File(path));
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (curve == null || curve.problem != DriveCurve.Problem.NONE || selected == null) {
            // Not drawn yet: hand on what the last recreation saved, so two in
            // a row lose nothing.
            if (restore != null) out.putBundle(KEY_STATE, restore);
            return;
        }
        Bundle b = new Bundle();
        b.putBoolean(KEY_AT_REST, atRest);
        b.putInt(KEY_FOLLOWED, followed);
        b.putInt(KEY_COLUMN, selected.column);
        int width = chart.getWidth();
        b.putFloat(KEY_SCROLL, width > 0 ? scroller.getScrollX() / (float) width : -1f);
        out.putBundle(KEY_STATE, b);
    }

    // ------------------------------------------------------------ building

    private void buildContent(LinearLayout box) {
        // Which samples: all of them, or only the ones with the pack parked.
        LinearLayout sw = new LinearLayout(this);
        sw.setOrientation(LinearLayout.HORIZONTAL);
        sw.setPadding(dp(4), dp(4), dp(4), dp(4));
        sw.setBackground(Ui.rounded(Palette.TILE, dp(8)));
        allButton = segment(v -> setAtRest(false));
        restButton = segment(v -> setAtRest(true));
        sw.addView(allButton, segmentParams(0));
        sw.addView(restButton, segmentParams(dp(4)));
        box.addView(sw);

        stats = new TextView(this);
        stats.setTextSize(13);
        stats.setTextColor(Palette.TEXT);
        stats.setLineSpacing(0, 1.35f);
        stats.setPadding(0, dp(10), 0, dp(12));
        box.addView(stats);

        overview = new DriveCurveView.Overview(this);
        box.addView(overview, new LinearLayout.LayoutParams(MATCH, WRAP));

        chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setGravity(Gravity.CENTER_VERTICAL);
        chipRow.setPadding(0, dp(8), 0, dp(4));
        chipScroll.addView(chipRow);
        box.addView(chipScroll);

        // The axis stays pinned while the plot scrolls sideways under it.
        LinearLayout chartRow = new LinearLayout(this);
        chartRow.setOrientation(LinearLayout.HORIZONTAL);
        chart = new DriveCurveView(this);
        axis = new DriveCurveView.Axis(this, chart);
        scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);   // the overview strip says where
        scroller.addView(chart);
        chartRow.addView(axis);
        chartRow.addView(scroller, new LinearLayout.LayoutParams(0, WRAP, 1f));
        LinearLayout.LayoutParams chartLp = new LinearLayout.LayoutParams(MATCH, WRAP);
        chartLp.setMargins(0, dp(6), 0, 0);
        box.addView(chartRow, chartLp);

        readout = new TextView(this);
        readout.setTextSize(12);
        readout.setTextColor(Palette.SOFT);
        readout.setTypeface(Typeface.MONOSPACE);
        readout.setLineSpacing(0, 1.3f);
        readout.setPadding(dp(13), dp(11), dp(13), dp(11));
        readout.setBackground(Ui.rounded(Palette.TILE, dp(6)));
        // Stepping with the buttons reads each step aloud.
        readout.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout.LayoutParams readLp = new LinearLayout.LayoutParams(MATCH, WRAP);
        readLp.setMargins(0, dp(10), 0, 0);
        box.addView(readout, readLp);

        // An 8 dp column is below a comfortable tap target; these are not.
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.addView(navButton("‹  Previous step", -1), new LinearLayout.LayoutParams(0, dp(44), 1f));
        LinearLayout.LayoutParams nextLp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        nextLp.setMargins(dp(8), 0, 0, 0);
        nav.addView(navButton("Next step  ›", 1), nextLp);
        LinearLayout.LayoutParams navLp = new LinearLayout.LayoutParams(MATCH, WRAP);
        navLp.setMargins(0, dp(6), 0, 0);
        box.addView(nav, navLp);

        box.addView(legend());

        TextView foot = Ui.note(this, "A group that held an end in under 10% of a step's "
                + "samples has no dot; tap the column to see every group. Faint marks stand on "
                + "fewer than " + DriveCurve.THIN_SAMPLES + " samples. At rest means under "
                + Math.round(PackMap.REST_AMPS) + " A. SOC falls left to right; a charging log "
                + "runs the other way.");
        foot.setTextSize(11.5f);
        foot.setLineSpacing(0, 1.4f);
        foot.setPadding(0, dp(10), 0, 0);
        box.addView(foot);
    }

    private TextView segment(View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(13);
        t.setOnClickListener(l);
        Ui.buttonRole(t);
        return t;
    }

    private LinearLayout.LayoutParams segmentParams(int left) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1f);
        lp.setMargins(left, 0, 0, 0);
        return lp;
    }

    private TextView navButton(String label, final int direction) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextSize(13);
        b.setTextColor(Palette.SOFT);
        b.setGravity(Gravity.CENTER);
        b.setBackground(Ui.pressable(Palette.TILE, dp(8)));
        b.setContentDescription(direction < 0 ? "Previous step" : "Next step");
        b.setOnClickListener(v -> step(direction));
        Ui.buttonRole(b);
        return b;
    }

    /** Swatch and words, drawn the way the chart draws each mark. */
    private View legend() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(14), 0, 0);
        box.addView(legendRow(bar(Palette.BAD, dp(20), dp(2)), "min cell, typical at that SOC"));
        box.addView(legendRow(bar((Palette.BAD & 0x00FFFFFF) | 0xBF000000,
                Math.max(1, Math.round(1.2f * getResources().getDisplayMetrics().density)),
                dp(11)), "lowest dip at that SOC"));
        box.addView(legendRow(bar(Palette.CURVE_MAX, dp(20), dp(2)), "max cell, typical at that SOC"));
        GradientDrawable band = Ui.rounded((Palette.SOFT & 0x00FFFFFF) | 0x2E000000, dp(2));
        box.addView(legendRow(mark(band, dp(20), dp(10)), "gap between them = spread"));
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(Palette.BAD);
        box.addView(legendRow(mark(dot, dp(9), dp(9)),
                "weakest group - bigger = more often at that SOC"));
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(0);
        ring.setStroke(Math.max(1, Math.round(1.4f * getResources().getDisplayMetrics().density)),
                Palette.CURVE_MAX);
        box.addView(legendRow(mark(ring, dp(9), dp(9)), "strongest group"));
        return box;
    }

    private View bar(int colour, int w, int h) {
        View v = new View(this);
        v.setBackgroundColor(colour);
        return holder(v, w, h);
    }

    private View mark(GradientDrawable g, int w, int h) {
        View v = new View(this);
        v.setBackground(g);
        return holder(v, w, h);
    }

    /** A 22 x 12 dp cell with the mark centred in it, so the words line up. */
    private View holder(View mark, int w, int h) {
        LinearLayout holder = new LinearLayout(this);
        holder.setGravity(Gravity.CENTER);
        holder.addView(mark, new LinearLayout.LayoutParams(w, h));
        return holder;
    }

    private View legendRow(View swatch, String words) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(dp(22), dp(12));
        sl.setMargins(0, 0, dp(10), 0);
        row.addView(swatch, sl);
        TextView t = new TextView(this);
        t.setText(words);
        t.setTextSize(11.5f);
        t.setTextColor(Palette.MUTED);
        row.addView(t);
        return row;
    }

    // ------------------------------------------------------------- loading

    /**
     * Parse on a worker, never on the main thread: a long drive is thousands of
     * rows across fifty columns, the reason the pack map reads off-thread too.
     */
    private void load(final File f) {
        sourceName = f.getName();
        subtitle.setText(f.getName());
        message.setText("Reading " + f.getName() + "...");
        new Thread(() -> {
            DriveCurve builder = new DriveCurve();
            LogReader.Result res;
            try {
                res = LogReader.read(f, builder);
            } catch (Exception e) {
                ui.post(() -> {
                    if (gone()) return;
                    message.setText("Cannot read " + f.getName() + ".");
                });
                return;
            }
            final LogReader.Result r = res;
            final DriveCurve.Curve c = builder.build();
            ui.post(() -> {
                if (gone()) return;
                show(r, c);
            });
        }, "curve-read").start();
    }

    /** Going or gone - a recreation (dark mode, font scale) leaves isFinishing() false. */
    private boolean gone() {
        return isFinishing() || isDestroyed();
    }

    private void show(LogReader.Result res, DriveCurve.Curve c) {
        map = res.map;
        curve = c;
        subtitle.setText(subtitleFor(c));
        if (c.problem != DriveCurve.Problem.NONE) {
            message.setText(c.problemText());
            return;
        }
        message.setVisibility(View.GONE);
        content.setVisibility(View.VISIBLE);

        allButton.setText("All samples · " + c.samples);
        restButton.setText("At rest · " + c.restSamples);
        styleSegments();
        stats.setText(String.format(Locale.ROOT,
                "%d SOC steps · lowest cell %d mV at %s\nwidest typical spread %d mV at %s\n"
                        + "weakest: %s",
                c.all.size(), c.lowest.lowestMin, c.stepLabel(c.lowest),
                c.widest.typicalSpread(), c.stepLabel(c.widest), c.handoverLine()));

        int lo = Math.min(map.firstIndex(), c.lowIndex);
        int hi = Math.max(map.lastIndex(), c.highIndex);
        chart.setCurve(c, lo, hi);
        chart.setContentDescription(String.format(Locale.ROOT,
                "Drive curve from %.1f to %.1f percent charge%s. "
                        + "Lowest cell %d millivolts at %s. Weakest by charge: %s.",
                c.startSoc, c.endSoc, c.legNote.isEmpty() ? "" : ", " + c.legNote,
                c.lowest.lowestMin, c.stepLabel(c.lowest), c.handoverLine()));
        // A tap selects what is under the finger; TalkBack's double-tap steps,
        // and may step off screen.
        chart.setOnSelect(s -> {
            selected = s;
            showReadout();
            keepInView();
        });
        axis.requestLayout();
        axis.invalidate();
        overview.setCurve(c);
        overview.setOnJump(fraction -> scroller.smoothScrollTo(
                Math.round(fraction * chart.getWidth() - scroller.getWidth() / 2f), 0));
        overview.setOnActivate(this::selectLowest);
        scroller.setOnScrollChangeListener((v, x, y, oldX, oldY) ->
                overview.setWindow(x, v.getWidth(), chart.getWidth()));
        buildChips(c);

        selected = c.lowest;
        float scrollShare = -1f;
        if (restore != null) {
            Bundle b = restore;
            restore = null;
            if (b.getBoolean(KEY_AT_REST) && c.restSamples > 0) setAtRest(true);
            int g = b.getInt(KEY_FOLLOWED, -1);
            if (c.chips.contains(g)) follow(g);
            DriveCurve.Step s = c.nearest(currentSet(), b.getInt(KEY_COLUMN, selected.column));
            if (s != null) selected = s;
            scrollShare = b.getFloat(KEY_SCROLL, -1f);
        }
        chart.select(selected.column);
        showReadout();
        // After layout, when the widths are real: where the owner was before a
        // recreation, else open on the drive's low point.
        final float share = scrollShare;
        scroller.post(() -> {
            if (gone() || selected == null) return;
            int x = share >= 0 ? Math.round(share * chart.getWidth())
                    : Math.round(chart.xForColumn(selected.column) - scroller.getWidth() / 2f);
            scroller.scrollTo(Math.max(0, x), 0);
            overview.setWindow(scroller.getScrollX(), scroller.getWidth(), chart.getWidth());
        });
    }

    private String subtitleFor(DriveCurve.Curve c) {
        StringBuilder sb = new StringBuilder(sourceName);
        if (c.startMs > 0) {
            sb.append(" · ").append(new SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())
                    .format(new Date(c.startMs)));
        }
        if (c.problem == DriveCurve.Problem.NONE) {
            sb.append(String.format(Locale.ROOT, " · SOC %.1f%% → %.1f%%", c.startSoc, c.endSoc));
            if (!c.legNote.isEmpty()) sb.append(" · ").append(c.legNote);
            else if (c.charging) sb.append(" · charge");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ choosing

    private void setAtRest(boolean rest) {
        if (curve == null || rest == atRest) return;
        if (rest && curve.restSamples == 0) return;
        atRest = rest;
        chart.setAtRest(rest);
        // Stay where the owner was looking, on the nearest step this set has.
        DriveCurve.Step s = curve.nearest(currentSet(), selected == null ? 0 : selected.column);
        if (s != null) {
            selected = s;
            chart.select(s.column);
        }
        styleSegments();
        showReadout();
        // The nearest at-rest step can be screens away on a sparse set.
        keepInView();
    }

    private List<DriveCurve.Step> currentSet() {
        return atRest ? curve.atRest : curve.all;
    }

    private void styleSegments() {
        styleSegment(allButton, !atRest, true);
        styleSegment(restButton, atRest, curve != null && curve.restSamples > 0);
    }

    private void styleSegment(TextView t, boolean on, boolean enabled) {
        t.setEnabled(enabled);
        t.setSelected(on);
        t.setBackground(on ? Ui.rounded(Palette.TILE_PRESSED, dp(6)) : null);
        t.setTextColor(!enabled ? Palette.FAINT : on ? Palette.TEXT : Palette.MUTED);
    }

    private void buildChips(DriveCurve.Curve c) {
        chipRow.removeAllViews();
        chips.clear();
        chipGroups.clear();
        if (c.chips.isEmpty()) {
            chipScroll.setVisibility(View.GONE);
            return;
        }
        TextView label = new TextView(this);
        label.setText("Follow");
        label.setTextSize(11.5f);
        label.setTextColor(Palette.MUTED);
        label.setPadding(0, 0, dp(8), 0);
        chipRow.addView(label);
        for (int g : c.chips) addChip("g" + g, g);
        addChip("all", -1);
        styleChips(-1);
    }

    private void addChip(String label, final int group) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(12.5f);
        t.setTypeface(Typeface.MONOSPACE);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(dp(44));
        t.setPadding(dp(14), 0, dp(14), 0);
        t.setContentDescription(group < 0 ? "Show every group" : "Follow group " + group);
        t.setOnClickListener(v -> follow(group));
        Ui.buttonRole(t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, dp(40));
        lp.setMargins(0, 0, dp(6), 0);
        chipRow.addView(t, lp);
        chips.add(t);
        chipGroups.add(group);
    }

    private void follow(int group) {
        followed = group;
        chart.follow(group);
        axis.invalidate();
        styleChips(group);
    }

    private void styleChips(int group) {
        for (int i = 0; i < chips.size(); i++) {
            boolean on = chipGroups.get(i) == group;
            TextView t = chips.get(i);
            t.setSelected(on);
            t.setBackground(Ui.rounded(on ? Palette.TILE_PRESSED : Palette.TILE, dp(20)));
            t.setTextColor(on ? Palette.TEXT : Palette.SOFT);
        }
    }

    /** One step along the current set, keeping the choice on screen. */
    private void step(int direction) {
        if (curve == null || selected == null) return;
        List<DriveCurve.Step> set = currentSet();
        int i = set.indexOf(selected);
        if (i < 0) return;
        selected = set.get(Math.max(0, Math.min(set.size() - 1, i + direction)));
        chart.select(selected.column);
        showReadout();
        keepInView();
    }

    /** TalkBack's double-tap on the overview: to the drive's lowest point, as on open. */
    private void selectLowest() {
        if (curve == null || curve.lowest == null) return;
        DriveCurve.Step s = curve.nearest(currentSet(), curve.lowest.column);
        if (s == null) return;
        selected = s;
        chart.select(s.column);
        showReadout();
        keepInView();
    }

    /** Scroll just far enough that the selected column sits clear of the edges. */
    private void keepInView() {
        // Before the first layout there is no view to keep it in, and a smooth
        // scroll started now would run over the scroll show() posts.
        if (selected == null || scroller.getWidth() == 0) return;
        float x = chart.xForColumn(selected.column);
        int left = scroller.getScrollX(), w = scroller.getWidth(), margin = dp(24);
        if (x < left + margin) {
            scroller.smoothScrollTo(Math.round(x - margin), 0);
        } else if (x > left + w - margin) {
            scroller.smoothScrollTo(Math.round(x - w + margin), 0);
        }
    }

    private void showReadout() {
        DriveCurve.Step s = selected;
        if (curve == null || s == null) return;
        DriveCurve.Verdicts v = this::verdict;
        readout.setText("SOC " + curve.stepLabel(s) + " · " + s.n
                + (s.n == 1 ? " sample" : " samples") + (atRest ? " at rest" : "")
                + (s.thin() ? " - thin" : "")
                + String.format(Locale.ROOT, "\nmin     typical %d mV · lowest %d mV",
                        s.typicalMin, s.lowestMin)
                + String.format(Locale.ROOT, "\nmax     typical %d mV · highest %d mV",
                        s.typicalMax, s.highestMax)
                + String.format(Locale.ROOT, "\nspread  typical %d mV", s.typicalSpread())
                + "\n\nweakest    " + DriveCurve.groups(s.weakest, s.n, 3, v)
                + "\nstrongest  " + DriveCurve.groups(s.strongest, s.n, 3, v));
    }

    /** What the same replay's pack map calls a group, in the pack map's words. */
    private String verdict(int group) {
        PackMap m = map;
        if (m == null) return null;
        switch (m.stateOf(group)) {
            case SUSPECT: return "weak module";
            case WATCH: return "watch";
            case BALANCE: return "low charge";
            default: return null;
        }
    }

    // --------------------------------------------------------------- share

    private void share() {
        if (curve == null) {
            Toast.makeText(this, "Still reading the log", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "Tata EV BMS drive curve");
        send.putExtra(Intent.EXTRA_TEXT, DriveCurve.render(sourceName, curve, this::verdict));
        startActivity(Intent.createChooser(send, "Share findings"));
    }

    private int dp(int v) {
        return Ui.dp(this, v);
    }
}

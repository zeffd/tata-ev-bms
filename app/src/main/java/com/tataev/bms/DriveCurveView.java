package com.tataev.bms;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The drive curve: cell voltages over SOC above, the groups holding each end
 * below, one column per SOC step, drawn at full width inside a
 * HorizontalScrollView.
 *
 * Two small companions share its scales: {@link Axis}, the label column pinned
 * beside the scroller, and {@link Overview}, the whole-drive strip above it.
 * The view knows nothing about files or the pack map; the screen hands it a
 * built {@link DriveCurve.Curve}.
 */
final class DriveCurveView extends View {

    static final int STEP_DP = 8;
    static final int HEIGHT_DP = 470;
    static final int AXIS_WIDTH_DP = 48;
    private static final int PAD_LEFT_DP = 8, PAD_RIGHT_DP = 14;
    /** Panel A (cell mV), the gap that holds the GROUP title, panel B, the SOC axis. */
    private static final int TOP_A = 22, HEIGHT_A = 180, TOP_B = 226, HEIGHT_B = 220,
            X_AXIS = 446;
    /** mV tick spacings, finest first; the first that gives five gaps or fewer wins. */
    private static final int[] MV_TICK_STEPS = {10, 20, 25, 40, 50, 100, 200};
    /** Whole percents between SOC labels, finest first. */
    private static final int[] LABEL_EVERY = {1, 2, 5, 10, 20};
    /** "0%" to "100%", so the SOC axis builds no strings while it draws. */
    private static final String[] PCT = new String[101];

    static {
        for (int i = 0; i < PCT.length; i++) PCT[i] = i + "%";
    }

    private static final AccessibilityNodeInfo.AccessibilityAction NEXT_STEP =
            new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK,
                    "select the next step");

    interface OnSelect {
        void selected(DriveCurve.Step step);
    }

    private final float d;
    private final int touchSlop;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    /** Text follows the owner's font size; the plot stays in dp, the label row grows. */
    private final float socLabelY, socLabelGap;
    private final int heightPx;

    private DriveCurve.Curve curve;
    private List<DriveCurve.Step> set = new ArrayList<>();
    private int selectedColumn = -1;
    private int followed = -1;
    private String followedLabel = "";
    private int vBot, vTop = 1, tick = 10;
    private int gLo = 1, gHi = 104;
    /** Worked out once per curve, not per frame. */
    private int[] mvTicks = new int[0], groupTicks = new int[0];
    private String[] mvLabels = new String[0], groupLabels = new String[0];
    private int socLabelEvery = 20;
    private OnSelect onSelect;
    private float downX, downY;
    private boolean moved;

    DriveCurveView(Context ctx) {
        super(ctx);
        d = ctx.getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(ctx).getScaledTouchSlop();
        setBackgroundColor(Palette.BG);
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(sp(ctx, 10.5f));
        text.setColor(Palette.MUTED);
        socLabelY = (X_AXIS + 7) * d - text.ascent();
        socLabelGap = Math.max(40 * d, text.measureText("100%") + 12 * d);
        heightPx = Math.round(Math.max(HEIGHT_DP * d, socLabelY + text.descent() + 3 * d));
        setClickable(true);
    }

    /** A text size in px that follows the owner's font-size setting. */
    static float sp(Context ctx, float sp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp,
                ctx.getResources().getDisplayMetrics());
    }

    /**
     * @param groupLo the first group the axis shows (0 on a zero-based pack)
     * @param groupHi the last
     */
    void setCurve(DriveCurve.Curve c, int groupLo, int groupHi) {
        curve = c;
        set = c.all;
        gLo = groupLo;
        gHi = Math.max(groupHi, groupLo + 1);
        // The mV axis covers the whole drive's lowest dip to highest peak, from
        // All samples, so the at-rest switch never rescales it.
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (DriveCurve.Step s : c.all) {
            lo = Math.min(lo, s.lowestMin);
            hi = Math.max(hi, s.highestMax);
        }
        if (lo > hi) {
            lo = 3000;
            hi = 3400;
        }
        int span = Math.max(1, hi - lo);
        for (int t : MV_TICK_STEPS) {
            tick = t;
            if (span / (double) t <= 5) break;
        }
        vBot = (int) Math.floor((lo - 4) / (double) tick) * tick;
        vTop = (int) Math.ceil((hi + 4) / (double) tick) * tick;
        mvTicks = new int[(vTop - vBot) / tick + 1];
        mvLabels = new String[mvTicks.length];
        for (int i = 0; i < mvTicks.length; i++) {
            mvTicks[i] = vBot + i * tick;
            mvLabels[i] = String.valueOf(mvTicks[i]);
        }
        // The first and last group and the quarters between, bottom to top.
        groupTicks = new int[5];
        groupLabels = new String[5];
        for (int k = 0; k < 5; k++) {
            groupTicks[k] = gLo + Math.round((gHi - gLo) * k / 4f);
            groupLabels[k] = String.valueOf(groupTicks[k]);
        }
        // On a wide bin a percent is only a few dp: labels thin out to stay
        // clear of each other at the owner's font size.
        float pxPerPct = STEP_DP * d * 10f / c.binTenths;
        socLabelEvery = LABEL_EVERY[LABEL_EVERY.length - 1];
        for (int e : LABEL_EVERY) {
            if (pxPerPct * e >= socLabelGap) {
                socLabelEvery = e;
                break;
            }
        }
        requestLayout();
        invalidate();
    }

    void setAtRest(boolean atRest) {
        if (curve == null) return;
        set = atRest ? curve.atRest : curve.all;
        invalidate();
    }

    void select(int column) {
        selectedColumn = column;
        invalidate();
    }

    /** Draw one group's dots at full strength and the rest faint; -1 for all. */
    void follow(int group) {
        followed = group;
        followedLabel = group >= 0 ? "g" + group : "";
        invalidate();
    }

    void setOnSelect(OnSelect l) {
        onSelect = l;
    }

    float xForColumn(int column) {
        return (PAD_LEFT_DP + column * STEP_DP + STEP_DP / 2f) * d;
    }

    float yForMv(double mv) {
        return (float) ((TOP_A + 8 + (vTop - mv) / (vTop - vBot) * (HEIGHT_A - 16)) * d);
    }

    float yForGroup(int g) {
        int c = Math.max(gLo, Math.min(gHi, g));
        return (TOP_B + 8 + (gHi - c) / (float) (gHi - gLo) * (HEIGHT_B - 16)) * d;
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int cols = curve == null ? 0 : curve.columns;
        setMeasuredDimension(Math.round((PAD_LEFT_DP + cols * STEP_DP + PAD_RIGHT_DP) * d),
                heightPx);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (curve == null) return;
        float w = getWidth();
        // Solid hairlines one shade off the ground.
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(1);
        line.setColor(Palette.CURVE_GRID);
        for (int v : mvTicks) {
            float y = yForMv(v);
            c.drawLine(0, y, w, y, line);
        }
        for (int g : groupTicks) {
            float y = yForGroup(g);
            c.drawLine(0, y, w, y, line);
        }
        drawSocAxis(c, w);
        if (selectedColumn >= 0) {
            float x = xForColumn(selectedColumn);
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(0x0DFFFFFF);
            c.drawRect(x - STEP_DP * d / 2, TOP_A * d, x + STEP_DP * d / 2, X_AXIS * d, fill);
        }
        if (followed >= 0) {
            line.setColor(Palette.SOFT);
            line.setAlpha(77);
            float y = yForGroup(followed);
            c.drawLine(0, y, w, y, line);
        }
        drawBand(c);
        drawDips(c);
        drawLine(c, true);
        drawLine(c, false);
        drawDots(c, false);      // strongest rings under the weakest dots
        drawDots(c, true);
        if (selectedColumn >= 0) {
            float x = xForColumn(selectedColumn);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(d);
            line.setColor(Palette.SOFT);
            line.setAlpha(204);
            c.drawLine(x, TOP_A * d, x, (TOP_A + HEIGHT_A) * d, line);
            c.drawLine(x, TOP_B * d, x, X_AXIS * d, line);
            line.setStrokeWidth(1);
        }
    }

    /** Whole-percent labels, every {@code socLabelEvery}, a tick half-way between them. */
    private void drawSocAxis(Canvas c, float w) {
        float y0 = X_AXIS * d;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(1);
        line.setColor(Palette.FAINT);
        c.drawLine(0, y0 + 0.5f, w, y0 + 0.5f, line);
        int every = socLabelEvery;
        int stepT = every * 5;
        int loT = (int) Math.round(curve.loSoc * 10), hiT = (int) Math.round(curve.hiSoc * 10);
        text.setColor(Palette.MUTED);
        for (int t = Math.floorDiv(loT + stepT - 1, stepT) * stepT; t <= hiT; t += stepT) {
            float x = xForColumn(curve.columnOfTenths(t));
            boolean major = t % (every * 10) == 0;
            c.drawLine(x, y0, x, y0 + (major ? 6 : 3) * d, line);
            if (major) c.drawText(PCT[t / 10], x, socLabelY, text);
        }
    }

    /** The spread: the ground between the typical max and min, per unbroken run. */
    private void drawBand(Canvas c) {
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(Palette.SOFT);
        fill.setAlpha(26);
        int i = 0;
        while (i < set.size()) {
            int j = runEnd(i);
            if (j > i) {
                path.reset();
                for (int k = i; k <= j; k++) {
                    DriveCurve.Step s = set.get(k);
                    float x = xForColumn(s.column), y = yForMv(s.typicalMax);
                    if (k == i) path.moveTo(x, y);
                    else path.lineTo(x, y);
                }
                for (int k = j; k >= i; k--) {
                    DriveCurve.Step s = set.get(k);
                    path.lineTo(xForColumn(s.column), yForMv(s.typicalMin));
                }
                path.close();
                c.drawPath(path, fill);
            }
            i = j + 1;
        }
    }

    /** The last index of the unbroken run starting at {@code i}. */
    private int runEnd(int i) {
        int j = i;
        while (j + 1 < set.size() && !DriveCurve.breaksBefore(set.get(j), set.get(j + 1))) j++;
        return j;
    }

    private void drawLine(Canvas c, boolean max) {
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2 * d);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setColor(max ? Palette.CURVE_MAX : Palette.BAD);
        int i = 0;
        while (i < set.size()) {
            int j = runEnd(i);
            path.reset();
            if (j == i) {
                // A lone step: a short dash, or it would not show at all.
                DriveCurve.Step s = set.get(i);
                float x = xForColumn(s.column), y = yForMv(max ? s.typicalMax : s.typicalMin);
                path.moveTo(x - 3 * d, y);
                path.lineTo(x + 3 * d, y);
            } else {
                for (int k = i; k <= j; k++) {
                    DriveCurve.Step s = set.get(k);
                    float x = xForColumn(s.column), y = yForMv(max ? s.typicalMax : s.typicalMin);
                    if (k == i) path.moveTo(x, y);
                    else path.lineTo(x, y);
                }
            }
            c.drawPath(path, line);
            i = j + 1;
        }
        line.setStrokeWidth(1);
        line.setStrokeCap(Paint.Cap.BUTT);
    }

    /** From each step's typical min down to its lowest dip. */
    private void drawDips(Canvas c) {
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(1.2f * d);
        line.setStrokeCap(Paint.Cap.ROUND);
        for (DriveCurve.Step s : set) {
            if (s.lowestMin >= s.typicalMin) continue;
            line.setColor(Palette.BAD);
            line.setAlpha(s.thin() ? 77 : 191);
            float x = xForColumn(s.column);
            c.drawLine(x, yForMv(s.typicalMin), x, yForMv(s.lowestMin), line);
        }
        line.setStrokeWidth(1);
        line.setStrokeCap(Paint.Cap.BUTT);
    }

    private void drawDots(Canvas c, boolean weakest) {
        for (DriveCurve.Step s : set) {
            float x = xForColumn(s.column);
            for (int[] e : weakest ? s.weakest : s.strongest) {
                double share = s.share(e[1]);
                boolean mine = followed >= 0 && e[0] == followed;
                // The followed group shows every appearance; the rest only
                // when they held a real part of the step.
                if (share < DriveCurve.DOT_MIN_SHARE && !mine) continue;
                int alpha = followed >= 0 && !mine ? 38 : 255;
                if (s.thin()) alpha = alpha * 2 / 5;
                float y = yForGroup(e[0]);
                float r = (float) ((weakest ? 1.6 + 3.4 * Math.sqrt(share)
                        : 1.8 + 3.0 * Math.sqrt(share)) * d);
                if (weakest) {
                    fill.setStyle(Paint.Style.FILL);
                    fill.setColor(Palette.BAD);
                    fill.setAlpha(alpha);
                    c.drawCircle(x, y, r, fill);
                    // A ring of ground keeps touching dots apart.
                    line.setStyle(Paint.Style.STROKE);
                    line.setStrokeWidth(d);
                    line.setColor(Palette.BG);
                    line.setAlpha(alpha);
                    c.drawCircle(x, y, r, line);
                } else {
                    line.setStyle(Paint.Style.STROKE);
                    line.setStrokeWidth(1.4f * d);
                    line.setColor(Palette.CURVE_MAX);
                    line.setAlpha(alpha);
                    c.drawCircle(x, y, r, line);
                }
            }
        }
        line.setStrokeWidth(1);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                moved = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(e.getX() - downX) > touchSlop
                        || Math.abs(e.getY() - downY) > touchSlop) {
                    moved = true;
                }
                return true;
            case MotionEvent.ACTION_UP:
                // Select on UP, like the pack map: every sideways scroll starts
                // with a DOWN on some column, and that is not a choice.
                if (!moved) tapAt(e.getX());
                return true;
            case MotionEvent.ACTION_CANCEL:
                moved = true;       // a scroller claimed the gesture
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    private void tapAt(float x) {
        if (curve == null || set.isEmpty()) return;
        int column = Math.round((x / d - PAD_LEFT_DP - STEP_DP / 2f) / STEP_DP);
        DriveCurve.Step s = curve.nearest(set, column);
        if (s == null) return;
        selectedColumn = s.column;
        performClick();
        if (onSelect != null) onSelect.selected(s);
        invalidate();
    }

    /** Overridden so the performClick() in tapAt() reaches accessibility services. */
    @Override
    public boolean performClick() {
        return super.performClick();
    }

    /**
     * TalkBack's double-tap has no column under a finger, so it steps: the next
     * step of the shown set, back to the first after the last. The readout is a
     * live region and reads the new step aloud.
     */
    @Override
    public boolean performAccessibilityAction(int action, Bundle args) {
        if (action == AccessibilityNodeInfo.ACTION_CLICK && curve != null && !set.isEmpty()) {
            int i = 0;
            while (i < set.size() && set.get(i).column <= selectedColumn) i++;
            DriveCurve.Step s = set.get(i < set.size() ? i : 0);
            selectedColumn = s.column;
            if (onSelect != null) onSelect.selected(s);
            invalidate();
            sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED);
            return true;
        }
        return super.performAccessibilityAction(action, args);
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.addAction(NEXT_STEP);     // "double-tap to select the next step"
    }

    /** The labels that stay put while the plot scrolls sideways. */
    static final class Axis extends View {
        private static final float TITLE_SPACING = 0.08f;
        private final DriveCurveView chart;
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float d, titleSize, labelSize;

        Axis(Context ctx, DriveCurveView chart) {
            super(ctx);
            this.chart = chart;
            d = ctx.getResources().getDisplayMetrics().density;
            titleSize = sp(ctx, 9.5f);
            labelSize = sp(ctx, 10f);
            setBackgroundColor(Palette.BG);
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            // Wide enough for the titles and a four-digit label at the owner's
            // font size, never narrower than the design.
            title();
            float need = Math.max(text.measureText("CELL mV"), text.measureText("GROUP"));
            label();
            need = Math.max(need, text.measureText("0000") + 6 * d);
            setMeasuredDimension(Math.round(Math.max(AXIS_WIDTH_DP * d, need + 2 * d)),
                    chart.heightPx);
        }

        private void title() {
            text.setTypeface(Typeface.DEFAULT);
            text.setTextSize(titleSize);
            text.setLetterSpacing(TITLE_SPACING);
            text.setTextAlign(Paint.Align.LEFT);
        }

        private void label() {
            text.setTypeface(Typeface.MONOSPACE);
            text.setTextSize(labelSize);
            text.setLetterSpacing(0);
            text.setTextAlign(Paint.Align.RIGHT);
        }

        @Override
        protected void onDraw(Canvas c) {
            if (chart.curve == null) return;
            text.setColor(Palette.MUTED);
            title();
            c.drawText("CELL mV", 0, Math.max(14 * d, d - text.ascent()), text);
            c.drawText("GROUP", 0, (TOP_B - 8) * d, text);
            label();
            float right = getWidth() - 6 * d;
            float mid = (text.descent() + text.ascent()) / 2f;
            float lineH = text.descent() - text.ascent();
            int[] mv = chart.mvTicks;
            for (int i = 0; i < mv.length; i++) {
                c.drawText(chart.mvLabels[i], right, chart.yForMv(mv[i]) - mid, text);
            }
            int f = chart.followed;
            float fy = f >= 0 ? chart.yForGroup(f) : -1;
            int[] groups = chart.groupTicks;
            for (int i = 0; i < groups.length; i++) {
                float y = chart.yForGroup(groups[i]);
                // The followed group's own label wins over a tick beside it.
                if (f >= 0 && Math.abs(fy - y) < lineH) continue;
                c.drawText(chart.groupLabels[i], right, y - mid, text);
            }
            if (f >= 0) {
                text.setColor(Palette.TEXT);
                c.drawText(chart.followedLabel, right, fy - mid, text);
            }
        }
    }

    /** The whole drive in one strip, with a box for what the plot shows now. */
    static final class Overview extends View {

        interface OnJump {
            /** @param fraction how far along the plot the tap was, 0 to 1 */
            void jumpTo(float fraction);
        }

        private static final String HINT = "scroll sideways →";
        private static final AccessibilityNodeInfo.AccessibilityAction GO_LOWEST =
                new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK,
                        "go to the lowest cell");

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF rect = new RectF();
        private final float d, trackH, labelY, hintW;
        private final int heightPx;
        private final int touchSlop;
        private DriveCurve.Curve curve;
        /** Worked out once per curve, not per frame. */
        private int lo, hi;
        private String leftLabel = "", rightLabel = "";
        private float sideW;
        private float winLeft, winWidth = 1f;
        private boolean atEnd;
        private OnJump onJump;
        private Runnable onActivate;
        private float downX;
        private boolean moved;

        Overview(Context ctx) {
            super(ctx);
            d = ctx.getResources().getDisplayMetrics().density;
            touchSlop = ViewConfiguration.get(ctx).getScaledTouchSlop();
            text.setTypeface(Typeface.MONOSPACE);
            text.setTextSize(sp(ctx, 10.5f));
            text.setColor(Palette.MUTED);
            trackH = 26 * d;
            labelY = trackH + 3 * d - text.ascent();
            heightPx = Math.round(Math.max(44 * d, labelY + text.descent() + 3 * d));
            hintW = text.measureText(HINT);
            setClickable(true);
            setContentDescription("Whole drive overview");
        }

        void setCurve(DriveCurve.Curve c) {
            curve = c;
            lo = Integer.MAX_VALUE;
            hi = Integer.MIN_VALUE;
            for (DriveCurve.Step s : c.all) {
                lo = Math.min(lo, s.typicalMin);
                hi = Math.max(hi, s.typicalMin);
            }
            leftLabel = String.format(Locale.ROOT, "%.1f%%", c.charging ? c.loSoc : c.hiSoc);
            rightLabel = String.format(Locale.ROOT, "%.1f%%", c.charging ? c.hiSoc : c.loSoc);
            sideW = Math.max(text.measureText(leftLabel), text.measureText(rightLabel));
            setContentDescription("Whole drive overview, " + leftLabel + " to " + rightLabel
                    + " charge");
            invalidate();
        }

        void setOnJump(OnJump j) {
            onJump = j;
        }

        /** What TalkBack's double-tap does, there being no point on the strip to tap. */
        void setOnActivate(Runnable r) {
            onActivate = r;
        }

        /** Where the scroller is, in px, so the box follows it. */
        void setWindow(int scrollX, int viewport, int content) {
            if (content <= 0) return;
            winLeft = scrollX / (float) content;
            winWidth = Math.min(1f, viewport / (float) content);
            atEnd = scrollX + viewport >= content - 4 * d;
            invalidate();
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            setMeasuredDimension(MeasureSpec.getSize(wSpec), heightPx);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Palette.TILE);
            rect.set(0, 0, w, trackH);
            c.drawRoundRect(rect, 4 * d, 4 * d, paint);
            if (curve == null || curve.all.isEmpty()) return;

            float span = Math.max(1, hi - lo);
            float cols = Math.max(1, curve.columns - 1);
            path.reset();
            for (int i = 0; i < curve.all.size(); i++) {
                DriveCurve.Step s = curve.all.get(i);
                float x = 4 * d + s.column / cols * (w - 8 * d);
                float y = 4 * d + (hi - s.typicalMin) / span * (trackH - 8 * d);
                if (i == 0) path.moveTo(x, y);
                else path.lineTo(x, y);
            }
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.2f * d);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(Palette.BAD);
            c.drawPath(path, paint);

            float ww = Math.max(18 * d, winWidth * w);
            float l = Math.min(w - ww, winLeft * w);
            paint.setStrokeWidth(1.5f * d);
            paint.setColor(Palette.SOFT);
            rect.set(l + 0.75f * d, 0.75f * d, l + ww - 0.75f * d, trackH - 0.75f * d);
            c.drawRoundRect(rect, 4 * d, 4 * d, paint);

            text.setTextAlign(Paint.Align.LEFT);
            c.drawText(leftLabel, 0, labelY, text);
            text.setTextAlign(Paint.Align.RIGHT);
            c.drawText(rightLabel, w, labelY, text);
            // At a large font on a narrow screen the hint would run into the ends.
            if (!atEnd && winWidth < 1f && hintW + 2 * sideW + 16 * d <= w) {
                text.setTextAlign(Paint.Align.CENTER);
                c.drawText(HINT, w / 2, labelY, text);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(e.getX() - downX) > touchSlop) moved = true;
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!moved && onJump != null && getWidth() > 0) {
                        performClick();
                        onJump.jumpTo(Math.max(0f, Math.min(1f, e.getX() / getWidth())));
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    moved = true;
                    return true;
                default:
                    return super.onTouchEvent(e);
            }
        }

        @Override
        public boolean performClick() {
            return super.performClick();
        }

        @Override
        public boolean performAccessibilityAction(int action, Bundle args) {
            if (action == AccessibilityNodeInfo.ACTION_CLICK && onActivate != null) {
                onActivate.run();
                sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED);
                return true;
            }
            return super.performAccessibilityAction(action, args);
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            if (onActivate != null) info.addAction(GO_LOWEST);
        }
    }
}

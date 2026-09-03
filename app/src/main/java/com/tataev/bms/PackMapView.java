package com.tataev.bms;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.Locale;

/**
 * Draws the pack as a grid of cell groups, coloured by what was observed about
 * each one.
 *
 * The important thing this shows is the gaps. A BMS that names one minimum and
 * one maximum per sample leaves most of the pack unobserved on any short drive,
 * and a map that quietly coloured every group would be inventing coverage it does
 * not have. Unseen groups are drawn as empty outlines and counted out loud.
 *
 * The view knows nothing about WHERE its states come from: a live PackMap, a
 * replayed log and the multi-drive history all hand it a Grid plus an optional
 * second line per cell. The caller owns the words for the tap readout.
 */
final class PackMapView extends View {

    /**
     * Eight across. Thirteen gave ~23 dp cells on a 360 dp phone - under any
     * comfortable tap target and too small for a second line of text.
     */
    private static final int COLS = 8;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sub = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    private PackMap.Grid grid = new PackMap().grid();
    /** Optional second line per cell (e.g. "0.28"), parallel to grid.states. */
    private String[] sublabels = new String[0];
    private int selected = -1;
    private final float d;
    /** Row count the current measurement was made for; drives re-measure. */
    private int measuredRows = -1;
    private final int touchSlop;
    private float downX, downY;
    private boolean moved;
    private Runnable onSelect;

    PackMapView(Context ctx) {
        super(ctx);
        d = ctx.getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(ctx).getScaledTouchSlop();
        setBackgroundColor(Palette.BG);
        stroke.setStyle(Paint.Style.STROKE);
        label.setTypeface(Typeface.MONOSPACE);
        label.setTextAlign(Paint.Align.CENTER);
        sub.setTypeface(Typeface.MONOSPACE);
        sub.setTextAlign(Paint.Align.CENTER);
        setClickable(true);
    }

    /** Runs on the UI thread after a tap changes the selection. */
    void setOnSelect(Runnable r) {
        onSelect = r;
    }

    /** A live or replayed map: SUSPECT cells carry their fitted excess. */
    void setMap(PackMap m) {
        PackMap map = m == null ? new PackMap() : m;
        PackMap.Grid g = map.grid();
        String[] subs = new String[g.size()];
        for (int i = 0; i < subs.length; i++) {
            if (g.states[i] != PackMap.State.SUSPECT) continue;
            PackMap.Snapshot s = map.snapshot(g.firstIndex + i);
            // Percentage first, like the rows and the report: it does not depend
            // on the current scale, and a bare "0.28" carried no unit to read it by.
            if (s != null && s.excessPct != 0) {
                subs[i] = String.format(Locale.ROOT, "%+d%%", s.excessPct);
            } else if (s != null && !Double.isNaN(s.excessMilliOhm)) {
                subs[i] = String.format(Locale.ROOT, "%.2f", s.excessMilliOhm);
            }
        }
        setGrid(g, subs);
    }

    /**
     * Any grid at all, with an optional second line per cell.
     *
     * Re-measures when the row count changes: both callers set the grid AFTER
     * the first layout pass, and the enclosing ScrollView only knows about rows
     * that were measured. Guarded, because live mode calls this every 1.5 s.
     */
    void setGrid(PackMap.Grid g, String[] subs) {
        grid = g == null ? new PackMap().grid() : g;
        sublabels = subs == null ? new String[grid.size()] : subs;
        // The grid is a Canvas, invisible to a screen reader: say what it shows.
        int seen = 0, suspect = 0, watch = 0, balance = 0;
        for (PackMap.State st : grid.states) {
            if (st != PackMap.State.UNSEEN) seen++;
            if (st == PackMap.State.SUSPECT) suspect++;
            else if (st == PackMap.State.WATCH) watch++;
            else if (st == PackMap.State.BALANCE) balance++;
        }
        setContentDescription(String.format(Locale.ROOT,
                "Pack map: %d groups, %d seen, %d weak modules, %d to watch, %d low charge",
                grid.size(), seen, suspect, watch, balance));
        if (rowsFor(grid) != measuredRows) requestLayout();
        invalidate();
    }

    private static int rowsFor(PackMap.Grid g) {
        return (int) Math.ceil(Math.max(g.size(), 1) / (double) COLS);
    }

    /** The group the user last tapped, or -1. */
    int selected() {
        return selected;
    }

    /** Select a group programmatically (a findings line was tapped). */
    void select(int index) {
        int slot = index - grid.firstIndex;
        if (slot < 0 || slot >= grid.size()) return;
        selected = index;
        invalidate();
    }

    private float pad() {
        return 12 * d;
    }

    private float gap() {
        return 4 * d;
    }

    private float gridLeft() {
        return pad();
    }

    private float gridTop() {
        return 12 * d;
    }

    private float cellFor(int width) {
        return (width - pad() * 2 - gap() * (COLS - 1)) / COLS;
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
                // Select on UP, not DOWN. Inside a ScrollView every scroll
                // gesture begins with a DOWN on whatever is under the finger, so
                // selecting there picked a random group on every scroll that
                // started on the grid. The ScrollView sends CANCEL once it claims
                // the gesture; a finger that travelled past the slop without
                // being claimed was still not a tap.
                if (!moved) tapAt(e.getX(), e.getY());
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    private void tapAt(float x, float y) {
        float cell = cellFor(getWidth()), gap = gap();
        // Offset INSIDE the grid, floored: (int) on a negative value truncates
        // towards zero and a tap in the padding selected the first cell.
        float dx = x - gridLeft();
        float dy = y - gridTop();
        if (dx < 0 || dy < 0) return;
        int col = (int) Math.floor(dx / (cell + gap));
        int row = (int) Math.floor(dy / (cell + gap));
        if (col < 0 || col >= COLS || row < 0) return;
        // Land inside the square, not merely in its stride.
        if (dx - col * (cell + gap) > cell || dy - row * (cell + gap) > cell) return;
        int slot = row * COLS + col;
        if (slot >= grid.size()) return;
        selected = grid.firstIndex + slot;
        performClick();
        if (onSelect != null) onSelect.run();
        invalidate();
    }

    /** Overridden so the performClick() in tapAt() reaches accessibility services. */
    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int rows = rowsFor(grid);
        measuredRows = rows;
        float cell = cellFor(w);
        setMeasuredDimension(w, (int) (gridTop() + rows * (cell + gap()) + 10 * d));
    }

    @Override
    protected void onDraw(Canvas c) {
        // One snapshot for the whole frame: the grid starts at 0 on a model that
        // numbers its groups from zero and at 1 on the Nexon.
        PackMap.Grid g = grid;
        int first = g.firstIndex, last = first + g.size() - 1;
        float cell = cellFor(getWidth()), gap = gap(), left = gridLeft(), top = gridTop();
        label.setTextSize(Math.min(cell * 0.36f, 13 * d));
        sub.setTextSize(Math.min(cell * 0.26f, 9.5f * d));

        for (int i = first; i <= last; i++) {
            int slot = i - first;
            int row = slot / COLS, col = slot % COLS;
            float x = left + col * (cell + gap);
            float y = top + row * (cell + gap);
            r.set(x, y, x + cell, y + cell);
            PackMap.State st = g.states[slot];
            fill.setColor(Palette.face(st));
            c.drawRoundRect(r, 3 * d, 3 * d, fill);
            stroke.setColor(i == selected ? Palette.OK : Palette.edge(st));
            stroke.setStrokeWidth((i == selected ? 2.2f : 1.2f) * d);
            c.drawRoundRect(r, 3 * d, 3 * d, stroke);

            // Only label what carries information; 104 faint numbers is noise.
            if (st == PackMap.State.UNSEEN && i != selected) continue;
            label.setColor(Palette.ink(st));
            String s2 = slot < sublabels.length ? sublabels[slot] : null;
            float cy = y + cell / 2f;
            if (s2 == null) {
                c.drawText(String.valueOf(i), x + cell / 2f,
                        cy - (label.descent() + label.ascent()) / 2f, label);
            } else {
                c.drawText(String.valueOf(i), x + cell / 2f, cy - 1.5f * d, label);
                sub.setColor(Palette.ink(st));
                c.drawText(s2, x + cell / 2f, cy + sub.getTextSize() + 0.5f * d, sub);
            }
        }
    }
}

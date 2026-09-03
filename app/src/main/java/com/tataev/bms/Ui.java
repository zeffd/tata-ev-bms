package com.tataev.bms;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.StateSet;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The widgets every screen builds: the back row, rounded tiles, the pack-map
 * legend. Five activities each carried their own copy of these, and the copies
 * had started to drift - Settings and Scan drew the back row with different
 * padding. Static and Context-taking rather than a base class, because the
 * screens share widgets, not behaviour.
 */
final class Ui {

    private Ui() { }

    static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable rounded(int colour, int radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(colour);
        g.setCornerRadius(radiusPx);
        return g;
    }

    /**
     * Same shape, visibly lighter while held. A flat view with a static
     * background is invisible to a finger: nothing moves when it is pressed.
     */
    static Drawable pressable(int colour, int radiusPx) {
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_pressed},
                rounded(Palette.TILE_PRESSED, radiusPx));
        sl.addState(StateSet.WILD_CARD, rounded(colour, radiusPx));
        return sl;
    }

    /**
     * A tappable back row, with an optional action at the right.
     *
     * With Theme.Material.NoActionBar there is no system up-affordance, so each
     * screen draws its own - otherwise the only way out is the device gesture,
     * which is not obvious and was missing entirely.
     */
    static View backHeader(final Activity a, String title, String action,
                           final Runnable onAction) {
        LinearLayout header = new LinearLayout(a);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(a, 16), dp(a, 16), dp(a, 16), dp(a, 12));
        // The arrow is the back control, not the whole row: a row-wide click
        // merged into one "Back <title>" node for TalkBack.
        TextView arrow = new TextView(a);
        arrow.setText("←");
        arrow.setTextSize(22);
        arrow.setTextColor(Palette.TEXT);
        arrow.setMinWidth(dp(a, 44));
        arrow.setMinHeight(dp(a, 44));
        arrow.setGravity(Gravity.CENTER_VERTICAL);
        arrow.setContentDescription("Back");
        arrow.setOnClickListener(v -> a.finish());
        buttonRole(arrow);
        header.addView(arrow);
        TextView t = new TextView(a);
        t.setText(title);
        t.setTextSize(20);
        t.setTextColor(Palette.TEXT);
        header.addView(t, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (action != null && onAction != null) {
            TextView act = new TextView(a);
            act.setText(action);
            act.setTextSize(13);
            act.setTextColor(Palette.OK);
            act.setPadding(dp(a, 12), dp(a, 8), 0, dp(a, 8));
            act.setMinHeight(dp(a, 44));           // a finger-sized target
            act.setGravity(Gravity.CENTER_VERTICAL);
            act.setOnClickListener(v -> onAction.run());
            buttonRole(act);
            header.addView(act);
        }
        return header;
    }

    /**
     * Announce a flat view as a button. A TextView with a click listener is
     * read by TalkBack as static text; this makes it say "button" and offer to
     * activate it.
     */
    static void buttonRole(View v) {
        v.setFocusable(true);
        v.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host,
                                                          AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(android.widget.Button.class.getName());
            }
        });
    }

    static TextView note(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(Palette.MUTED);
        return t;
    }

    /**
     * A swatch drawn the way a pack-map cell is drawn - face plus edge - so the
     * legend actually matches the grid. It used to be a solid square of the ink
     * colour, while a WATCH or BALANCE cell is a dark tile with a coloured edge.
     */
    static View swatch(Context c, PackMap.State st) {
        View sw = new View(c);
        GradientDrawable g = new GradientDrawable();
        g.setColor(Palette.face(st));
        g.setStroke(Math.max(1, dp(c, 1)), Palette.edge(st));
        g.setCornerRadius(dp(c, 3));
        sw.setBackground(g);
        return sw;
    }

    static LinearLayout.LayoutParams swatchParams(Context c) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(c, 14), dp(c, 14));
        lp.setMargins(0, 0, dp(c, 10), 0);
        return lp;
    }

    static View legendRow(Context c, PackMap.State st, String text) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(c, 3), 0, dp(c, 3));
        row.addView(swatch(c, st), swatchParams(c));
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(11.5f);
        t.setTextColor(Palette.MUTED);
        row.addView(t);
        return row;
    }

    /**
     * The legend carries MEANING and REMEDY; tap readouts carry only numbers,
     * so nothing is said twice. Texts are for SUSPECT, WATCH, BALANCE, HEALTHY
     * and UNSEEN, in that order.
     */
    static View legend(Context c, String[] texts) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(c, 16), dp(c, 4), dp(c, 16), 0);
        PackMap.State[] order = {PackMap.State.SUSPECT, PackMap.State.WATCH,
                PackMap.State.BALANCE, PackMap.State.HEALTHY, PackMap.State.UNSEEN};
        for (int i = 0; i < order.length && i < texts.length; i++) {
            box.addView(legendRow(c, order[i], texts[i]));
        }
        return box;
    }
}

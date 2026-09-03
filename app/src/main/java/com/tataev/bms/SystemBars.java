package com.tataev.bms;

import android.graphics.Insets;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;

/**
 * Keeps content clear of the status and navigation bars.
 *
 * Apps targeting Android 15 (SDK 35) are edge-to-edge whether they ask for it or
 * not: the window extends behind the system bars, so anything at the top or
 * bottom of a layout is drawn underneath them unless the app consumes the insets
 * itself. Without this the first row of the dashboard sat behind the status bar.
 *
 * The background still paints edge-to-edge - only the content is inset - which is
 * the intended look.
 */
final class SystemBars {

    private SystemBars() { }

    /**
     * Pad {@code root} by the system bars, preserving whatever padding it already
     * has. Also covers display cutouts, so the layout survives a notch or a
     * punch-hole camera in landscape.
     */
    static void pad(View root) {
        final int left = root.getPaddingLeft();
        final int top = root.getPaddingTop();
        final int right = root.getPaddingRight();
        final int bottom = root.getPaddingBottom();

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int l, t, r, b;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                l = bars.left;
                t = bars.top;
                r = bars.right;
                b = bars.bottom;
            } else {
                l = insets.getSystemWindowInsetLeft();
                t = insets.getSystemWindowInsetTop();
                r = insets.getSystemWindowInsetRight();
                b = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(left + l, top + t, right + r, bottom + b);
            return insets;
        });
        root.requestApplyInsets();
    }
}

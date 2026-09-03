package com.tataev.bms;

/**
 * The one palette. Colour is only ever used to mean something, and five screens
 * each carried their own copy of these constants - Settings and Scan drifted to
 * pure black with cyan headers, so they read as a different app.
 *
 * Pure Java: no Android imports, so the self-test can compile anything that
 * refers to it.
 */
final class Palette {
    static final int BG = 0xFF0D0F12;
    static final int TILE = 0xFF171A1F;
    static final int TILE_PRESSED = 0xFF262B33;
    static final int TEXT = 0xFFEDEFF2;
    /** Secondary labels and flat buttons. */
    static final int SOFT = 0xFFB4BCC5;
    static final int MUTED = 0xFF838C97;
    static final int FAINT = 0xFF4C555F;
    static final int OK = 0xFF35D0BA;
    static final int WARN = 0xFFF2B441;
    static final int BAD = 0xFFF2564B;
    /** The dashboard's alert banner: soft red text on a dark red ground. */
    static final int ALERT_TEXT = 0xFFFF8B82;
    static final int ALERT_BG = 0xFF24100E;

    // Pack map verdicts. Face, edge and ink are drawn the same way in the grid
    // and in the legend, so the legend actually looks like the cells.
    static final int SUSPECT = 0xFFE2564B;
    static final int WATCH = 0xFFD79A3F;
    static final int BALANCE = 0xFF4E7FB8;
    static final int HEALTHY_INK = 0xFF5E7B74;

    static int face(PackMap.State s) {
        switch (s) {
            case SUSPECT: return SUSPECT;
            case WATCH: return 0xFF2C2417;
            case BALANCE: return 0xFF16202C;
            case HEALTHY: return TILE;
            default: return 0xFF14161A;
        }
    }

    static int edge(PackMap.State s) {
        switch (s) {
            case SUSPECT: return SUSPECT;
            case WATCH: return WATCH;
            case BALANCE: return BALANCE;
            case HEALTHY: return 0xFF2A3A36;
            default: return 0xFF20262E;
        }
    }

    static int ink(PackMap.State s) {
        switch (s) {
            case SUSPECT: return 0xFFFFFFFF;
            case WATCH: return WATCH;
            case BALANCE: return BALANCE;
            case HEALTHY: return HEALTHY_INK;
            default: return 0xFF3A424C;
        }
    }

    private Palette() { }
}

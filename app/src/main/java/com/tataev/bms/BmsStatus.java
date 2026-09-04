package com.tataev.bms;

import java.util.Locale;

/**
 * Words for the BMS's own status bytes, and the one-line strip the dashboard
 * shows under the delta bar.
 *
 * The flag bit meanings are the Gotion BMS catalog's, one mask per flag. The
 * relay byte lists three relays without masks, so this only ever claims "all
 * closed", "open" or "pre-charging" - never which bit is which main relay.
 * Pure Java, so every phrase is pinned by the self-test.
 */
final class BmsStatus {

    private BmsStatus() { }

    static final int FLAG_DERATE = 0x01;
    static final int FLAG_BALANCING = 0x02;
    static final int FLAG_LEAKAGE_DETECT = 0x04;
    static final int FLAG_CHARGING = 0x08;
    static final int FLAG_HVIL_DETECT = 0x10;
    static final int FLAG_EQUALISATION = 0x20;

    private static final String SEP = " · ";

    /**
     * The flags that mean something is HAPPENING. The two enables (leakage
     * detect, HVIL detect) are on for the whole life of a healthy car and would
     * only be noise.
     */
    static String flags(int b) {
        StringBuilder sb = new StringBuilder();
        if ((b & FLAG_DERATE) != 0) join(sb, ", ", "derating");
        if ((b & FLAG_BALANCING) != 0) join(sb, ", ", "balancing");
        if ((b & FLAG_CHARGING) != 0) join(sb, ", ", "charging");
        if ((b & FLAG_EQUALISATION) != 0) join(sb, ", ", "equalising");
        return sb.length() == 0 ? "no flags" : sb.toString();
    }

    static String relays(int bits) {
        if (bits == 0) return "relays open";
        boolean mains = (bits & 0x06) == 0x06;
        boolean pre = (bits & 0x01) != 0;
        if (mains) return pre ? "relays closed, pre-charge on" : "relays closed";
        if (pre) return "pre-charging";
        return String.format(Locale.ROOT, "relays 0x%02X", bits);
    }

    static String socCal(int v) {
        switch (v) {
            case 0: return "SOC not calibrated";
            case 1: return "SOC calibrated at 100%";
            case 2: return "SOC calibrated at 99%";
            case 3: return "SOC calibrated at 95%";
            case 4: return "SOC calibrated at 0%";
            default: return "SOC cal state " + v;
        }
    }

    static boolean derating(Reading r) {
        return flagSet(r, FLAG_DERATE);
    }

    static boolean balancing(Reading r) {
        return flagSet(r, FLAG_BALANCING);
    }

    private static boolean flagSet(Reading r, int mask) {
        Double f = r == null ? null : r.get("flags");
        return f != null && (((int) Math.round(f)) & mask) != 0;
    }

    /** The strip; "" when the reading carries none of the status roles. */
    static String line(Reading r) {
        if (r == null) return "";
        StringBuilder sb = new StringBuilder();
        Double ins = r.get("insulation_kohm");
        if (ins != null) join(sb, SEP, String.format(Locale.ROOT, "insulation %.0f kΩ", ins));
        Double dis = r.get("dis_limit_a");
        if (dis != null) join(sb, SEP, String.format(Locale.ROOT, "discharge limit %.0f A", dis));
        Double regen = r.get("regen_peak_kw");
        if (regen != null) join(sb, SEP, String.format(Locale.ROOT, "regen limit %.0f kW", regen));
        Double fl = r.get("flags");
        if (fl != null) {
            String t = flags((int) Math.round(fl));
            if (!"no flags".equals(t)) join(sb, SEP, t);
        }
        Double cal = r.get("soc_cal_state");
        if (cal != null) join(sb, SEP, socCal((int) Math.round(cal)));
        Double rank = r.get("fault_rank");
        if (rank != null && rank > 0) {
            join(sb, SEP, String.format(Locale.ROOT, "fault rank %.0f", rank));
        }
        return sb.toString();
    }

    private static void join(StringBuilder sb, String sep, String s) {
        if (sb.length() > 0) sb.append(sep);
        sb.append(s);
    }
}

package com.tataev.bms;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The tap readout's second half: the last few moments a group was the pack's
 * weakest or strongest, and its mean offset at rest and under load.
 *
 * The BMS names one minimum and one maximum per sample and nothing else, so
 * this is every voltage the app has ever been told about the group. Monospace
 * columns, because the readout is monospace. ASCII only.
 *
 * Pure Java (java.text, not java.time - API 26), so the self-test checks the
 * text exactly.
 */
final class GroupMoments {

    private GroupMoments() { }

    static String render(PackMap.Snapshot g, TimeZone tz) {
        if (g == null || !g.seen) return "";
        StringBuilder sb = new StringBuilder();
        int total = g.minCount + g.maxCount;
        int shown = g.recent.size();
        if (total > shown) {
            sb.append(String.format(Locale.ROOT,
                    "Last %d moments as weakest or strongest (%d in total)%n", shown, total));
        } else {
            sb.append(String.format(Locale.ROOT,
                    "All %d moment%s as weakest or strongest%n", shown, shown == 1 ? "" : "s"));
        }
        SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.ROOT);
        clock.setTimeZone(tz);
        for (PackMap.Moment mo : g.recent) {
            String when = mo.atMs <= 0 ? "--:--:--" : clock.format(new Date(mo.atMs));
            sb.append(String.format(Locale.ROOT, "%s  %.3f V  %+3d mV  %3d A  %s%n",
                    when, mo.mv / 1000.0, mo.deltaMv, Math.round(mo.amps), mo.asMin ? "min" : "max"));
        }
        String rest = split(g.restMinMoments, g.restMinAvgMv, g.restMaxMoments, g.restMaxAvgMv);
        if (!rest.isEmpty()) sb.append("at rest:    ").append(rest).append('\n');
        String load = split(g.loadMinMoments, g.loadMinAvgMv, g.loadMaxMoments, g.loadMaxAvgMv);
        if (!load.isEmpty()) sb.append("under load: ").append(load).append('\n');
        // No trailing newline: the caller places it. A length check rather than a
        // regex - the live map renders one of these per notable group per refresh.
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    /** "-38 mV over 3 as min, +33 mV over 2 as max" - only the sets that have members. */
    private static String split(int minN, double minAvg, int maxN, double maxAvg) {
        StringBuilder sb = new StringBuilder();
        if (minN > 0) {
            sb.append(String.format(Locale.ROOT, "%+d mV over %d as min", Math.round(minAvg), minN));
        }
        if (maxN > 0) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(String.format(Locale.ROOT, "%+d mV over %d as max", Math.round(maxAvg), maxN));
        }
        return sb.toString();
    }
}

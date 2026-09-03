package com.tataev.bms;

import java.util.List;
import java.util.Locale;

/**
 * The pack map in words.
 *
 * The service centre needs a paragraph, not a CSV: which groups, what the
 * evidence is, how much of it there is. Every line claims exactly what the
 * numbers support, and the method note says how they were obtained. ASCII
 * only ("mOhm"), because this goes through a share sheet.
 *
 * Pure Java: no Android imports, so the self-test drives it directly.
 */
final class PackReport {

    private PackReport() { }

    static String render(String source, String vehicle, PackMap m) {
        PackMap.Stats s = m.stats();
        StringBuilder sb = new StringBuilder();
        sb.append("Tata EV BMS - pack findings\n");
        if (vehicle != null && !vehicle.isEmpty()) {
            sb.append("Vehicle: ").append(vehicle).append('\n');
        }
        sb.append("Source: ").append(source).append('\n');
        sb.append(String.format(Locale.ROOT,
                "%d samples, %d of %d groups observed, %d measurable%n%n",
                s.samples, s.seen, s.seriesCount, s.measurable));
        List<PackMap.Snapshot> notable = m.notable();
        if (notable.isEmpty()) {
            sb.append(s.samples == 0 ? "No samples.\n" : "No group stood out.\n");
        }
        for (PackMap.Snapshot g : notable) sb.append(line(g)).append('\n');
        if (!Double.isNaN(s.packMilliOhm)) {
            sb.append(String.format(Locale.ROOT,
                    "%nPack resistance %.1f mOhm (%.2f mOhm per group).%n",
                    s.packMilliOhm, s.packMilliOhm / Math.max(1, s.seriesCount)));
        }
        if (!Double.isNaN(s.resistiveSpreadMv)) {
            sb.append(String.format(Locale.ROOT,
                    "Cell spread %.1f mV at rest, %.1f mV under load.%n",
                    s.restSpreadMv, s.loadSpreadMv));
        }
        String soc = socLines(m);
        if (!soc.isEmpty()) sb.append('\n').append(soc);
        sb.append("\nMethod: the BMS reports only the weakest and strongest group per "
                + "sample. Each naming is one measurement; a resistance is fitted only "
                + "for a group seen at both extremes across a wide current range. "
                + "Read-only UDS 0x22, nothing written to the vehicle.\n");
        return sb.toString();
    }

    /** One finding. */
    static String line(PackMap.Snapshot g) {
        switch (g.state) {
            case SUSPECT:
                // Percentage first: it is the scale-free figure. The mOhm depends
                // on the current scale, which is inferred on most cars.
                return String.format(Locale.ROOT,
                        "Group %d - SUSPECT: %+d%% over the pack's average group "
                                + "(%+.2f mOhm); weakest in %d%% of samples above %.0f A "
                                + "(%dx min, %dx max)",
                        g.index, g.excessPct, g.excessMilliOhm, g.loadMinPct,
                        PackMap.WATCH_AMPS, g.minCount, g.maxCount);
            case WATCH:
                return String.format(Locale.ROOT,
                        "Group %d - WATCH: weakest in %d%% of samples above %.0f A, "
                                + "not yet seen at the top (%dx min, %dx max)",
                        g.index, g.loadMinPct, PackMap.WATCH_AMPS, g.minCount, g.maxCount);
            case BALANCE:
                return String.format(Locale.ROOT,
                        "Group %d - LOW CHARGE: weakest in %d%% of samples at rest "
                                + "(%dx min, %dx max)",
                        g.index, g.restMinPct, g.minCount, g.maxCount);
            case HEALTHY:
                return String.format(Locale.ROOT, "Group %d - weakest %dx, strongest %dx",
                        g.index, g.minCount, g.maxCount);
            default:
                return String.format(Locale.ROOT,
                        "Group %d - never the weakest or strongest in any sample", g.index);
        }
    }

    /**
     * How low the drive went and who held the rest floor on the way down.
     *
     * Weak groups show first near empty: on an LFP pack the rest voltage says
     * almost nothing about state of charge in the flat middle, and a
     * capacity-limited group only falls away at the bottom knee. So the band
     * the fault lives in has to be visible, and its absence said out loud.
     *
     * @return "" when the log carried no SOC
     */
    static String socLines(PackMap m) {
        double lo = m.lowestSoc();
        if (Double.isNaN(lo)) return "";
        StringBuilder sb = new StringBuilder();
        // Floored, not rounded: a drive that reached 89.5% did not reach 90%.
        sb.append(String.format(Locale.ROOT, "Lowest SOC seen: %d%%.", (int) Math.floor(lo)));
        if (lo > 20) {
            sb.append(" Weak groups show first near empty - a drive logged down to "
                    + "about 10% is the one that tells.");
        }
        sb.append('\n');
        List<PackMap.SocBand> bands = m.restFloorBySoc();
        if (!bands.isEmpty()) {
            sb.append("Rest floor by SOC:");
            for (PackMap.SocBand b : bands) {
                sb.append(String.format(Locale.ROOT, " %d-%d%%: group %d (%d%%)",
                        b.lo, b.lo + 9, b.leader, b.leaderPct));
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}

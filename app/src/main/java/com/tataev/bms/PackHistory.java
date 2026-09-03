package com.tataev.bms;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.List;
import java.util.Locale;

/**
 * The same group, drive after drive.
 *
 * One drive naming a group is a finding; three independent drives naming the
 * same group at the same excess is the strongest evidence this app can produce,
 * and until now it lived in three separate screens. Verdicts are tallied per
 * drive rather than the fits merged: each drive pinned its own deviation
 * baseline, so its least-squares sums are not comparable across files, and a
 * tally is honest about disagreement where a merged number would hide it.
 *
 * Pure Java: no Android imports, so the self-test drives it directly.
 */
final class PackHistory {

    private PackHistory() { }

    static final class GroupHistory {
        final int index;
        int drives, measurable, suspect, watch, balance;
        double excessLo = Double.NaN, excessHi = Double.NaN;
        /** Excess as % of the pack's average group, over the suspect drives; -1 until any. */
        int pctLo = -1, pctHi = -1;

        GroupHistory(int index) {
            this.index = index;
        }

        /**
         * SUSPECT when it was fitted as one in at least half the drives that
         * could fit it; WATCH / BALANCE when at least half the drives that saw
         * it said so; HEALTHY when seen and nothing stood out; UNSEEN otherwise.
         */
        PackMap.State display() {
            if (drives == 0) return PackMap.State.UNSEEN;
            if (suspect > 0 && suspect * 2 >= measurable) return PackMap.State.SUSPECT;
            if (watch > 0 && watch * 2 >= drives) return PackMap.State.WATCH;
            if (balance > 0 && balance * 2 >= drives) return PackMap.State.BALANCE;
            return PackMap.State.HEALTHY;
        }

        /** How many drives agree with the displayed verdict. */
        int agreeing() {
            switch (display()) {
                case SUSPECT: return suspect;
                case WATCH: return watch;
                case BALANCE: return balance;
                default: return 0;
            }
        }
    }

    static final class Result {
        final int drives, samples, seriesCount, firstIndex;
        final GroupHistory[] groups = new GroupHistory[PackMap.MAX_GROUPS + 1];

        Result(int drives, int samples, int seriesCount, int firstIndex) {
            this.drives = drives;
            this.samples = samples;
            this.seriesCount = seriesCount;
            this.firstIndex = firstIndex;
            for (int i = 0; i <= PackMap.MAX_GROUPS; i++) groups[i] = new GroupHistory(i);
        }

        int lastIndex() {
            return Math.min(firstIndex + seriesCount - 1, PackMap.MAX_GROUPS);
        }

        PackMap.Grid grid() {
            int n = Math.max(0, lastIndex() - firstIndex + 1);
            PackMap.State[] st = new PackMap.State[n];
            for (int i = 0; i < n; i++) st[i] = groups[firstIndex + i].display();
            return new PackMap.Grid(firstIndex, st);
        }

        /** "2/3" on every cell with a verdict: drives agreeing over drives total. */
        String[] sublabels() {
            int n = Math.max(0, lastIndex() - firstIndex + 1);
            String[] out = new String[n];
            for (int i = 0; i < n; i++) {
                GroupHistory g = groups[firstIndex + i];
                PackMap.State st = g.display();
                if (st == PackMap.State.SUSPECT || st == PackMap.State.WATCH
                        || st == PackMap.State.BALANCE) {
                    out[i] = g.agreeing() + "/" + drives;
                }
            }
            return out;
        }

        /** Groups with a verdict, most serious and most agreed-on first. */
        List<GroupHistory> notable() {
            List<GroupHistory> out = new ArrayList<>();
            for (int i = firstIndex; i <= lastIndex(); i++) {
                PackMap.State st = groups[i].display();
                if (st == PackMap.State.SUSPECT || st == PackMap.State.WATCH
                        || st == PackMap.State.BALANCE) {
                    out.add(groups[i]);
                }
            }
            Collections.sort(out, (a, b) -> {
                int ra = rank(a.display()), rb = rank(b.display());
                if (ra != rb) return ra - rb;
                if (a.agreeing() != b.agreeing()) return b.agreeing() - a.agreeing();
                return Double.compare(nz(b.excessHi), nz(a.excessHi));
            });
            return out;
        }

        String render(String vehicle) {
            StringBuilder sb = new StringBuilder("Tata EV BMS - pack findings across drives\n");
            if (vehicle != null && !vehicle.isEmpty()) {
                sb.append("Vehicle: ").append(vehicle).append('\n');
            }
            sb.append(String.format(Locale.ROOT, "%d drives, %d samples, %d groups%n%n",
                    drives, samples, seriesCount));
            List<GroupHistory> n = notable();
            if (n.isEmpty()) {
                sb.append(drives == 0 ? "No drives.\n" : "No group stood out in any drive.\n");
            }
            for (GroupHistory g : n) sb.append(line(g, drives)).append('\n');
            sb.append("\nMethod: each drive was replayed on its own and its verdicts "
                    + "tallied; a group is SUSPECT here when at least half the drives that "
                    + "could fit its resistance did so. Read-only UDS 0x22, nothing written "
                    + "to the vehicle.\n");
            return sb.toString();
        }
    }

    /** One group's history, in words. */
    static String line(GroupHistory g, int drives) {
        switch (g.display()) {
            case SUSPECT:
                return String.format(Locale.ROOT,
                        "Group %d - SUSPECT in %d of %d drives (+%d%% to +%d%% over the pack's "
                                + "average group, %.2f to %.2f mOhm)%s",
                        g.index, g.suspect, drives, g.pctLo, g.pctHi, g.excessLo, g.excessHi,
                        g.watch > 0 ? String.format(Locale.ROOT, ", watch in %d", g.watch) : "");
            case WATCH:
                return String.format(Locale.ROOT, "Group %d - WATCH in %d of %d drives",
                        g.index, g.watch, drives);
            case BALANCE:
                return String.format(Locale.ROOT, "Group %d - LOW CHARGE in %d of %d drives",
                        g.index, g.balance, drives);
            case HEALTHY:
                return String.format(Locale.ROOT,
                        "Group %d - seen in %d of %d drives, nothing stood out",
                        g.index, g.drives, drives);
            default:
                return String.format(Locale.ROOT, "Group %d - never named in any drive", g.index);
        }
    }

    /**
     * Where the drives from logs written before the VIN column belong.
     *
     * With exactly one identified car and no VIN-less one they are that car's:
     * folding them in beats asking "which vehicle?" for an "unknown vehicle"
     * entry. Otherwise they cannot be assigned and stay their own entry.
     *
     * A car whose battery controller serves no VIN writes an EMPTY vin column
     * on every row. That is not an older log: it is a second car, and it keeps
     * its own drives (the "" bucket) rather than being tallied into the VIN
     * car's pack on the screen that carries the strongest evidence.
     */
    static void placePreVin(Map<String, List<PackMap>> byVin, List<PackMap> preVin) {
        if (preVin.isEmpty()) return;
        String only = null;
        int real = 0;
        for (String k : byVin.keySet()) {
            if (!k.isEmpty()) {
                real++;
                only = k;
            }
        }
        String target = real == 1 && !byVin.containsKey("") ? only : "";
        List<PackMap> l = byVin.get(target);
        if (l == null) {
            l = new ArrayList<>();
            byVin.put(target, l);
        }
        l.addAll(preVin);
    }

    static Result aggregate(List<PackMap> maps) {
        int samples = 0, series = 0, first = 1;
        for (PackMap m : maps) {
            samples += m.samples();
            series = Math.max(series, m.seriesCount());
            first = Math.min(first, m.firstIndex());
        }
        Result r = new Result(maps.size(), samples, maps.isEmpty() ? 0 : series, first);
        for (PackMap m : maps) {
            for (int i = m.firstIndex(); i <= m.lastIndex(); i++) {
                PackMap.Snapshot s = m.snapshot(i);
                if (s == null || !s.seen) continue;
                GroupHistory g = r.groups[i];
                g.drives++;
                if (s.measurable && !Double.isNaN(s.excessMilliOhm)) {
                    g.measurable++;
                    // The quoted range is over the drives that CALLED it a suspect;
                    // a healthy drive's 0.05 must not widen "0.05 to 0.30 mOhm".
                    if (s.state == PackMap.State.SUSPECT) {
                        g.excessLo = Double.isNaN(g.excessLo)
                                ? s.excessMilliOhm : Math.min(g.excessLo, s.excessMilliOhm);
                        g.excessHi = Double.isNaN(g.excessHi)
                                ? s.excessMilliOhm : Math.max(g.excessHi, s.excessMilliOhm);
                        g.pctLo = g.pctLo < 0 ? s.excessPct : Math.min(g.pctLo, s.excessPct);
                        g.pctHi = g.pctHi < 0 ? s.excessPct : Math.max(g.pctHi, s.excessPct);
                    }
                }
                if (s.state == PackMap.State.SUSPECT) g.suspect++;
                else if (s.state == PackMap.State.WATCH) g.watch++;
                else if (s.state == PackMap.State.BALANCE) g.balance++;
            }
        }
        return r;
    }

    private static int rank(PackMap.State s) {
        return s == PackMap.State.SUSPECT ? 0 : s == PackMap.State.WATCH ? 1 : 2;
    }

    private static double nz(double v) {
        return Double.isNaN(v) ? 0 : v;
    }
}

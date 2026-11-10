package com.tataev.bms;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One saved log against SOC instead of time: at each charge step, what the
 * weakest and strongest cells read and which groups held each end.
 *
 * The pack map says who was weakest over a whole drive; this says WHEN in the
 * charge, which is where a group that runs out before the rest gives itself
 * away. Fed by {@link LogReader}'s row sink, so it sees exactly the rows, the
 * carried-forward SOC and the DID-resolved indices the pack-map replay sees.
 *
 * Pure Java: no Android imports, so the self-test drives it directly.
 */
final class DriveCurve implements LogReader.RowSink {

    /** Fewer samples than this and a step's numbers are shown, but called thin. */
    static final int THIN_SAMPLES = 5;
    /** A group that held an end in less of a step than this draws no dot. */
    static final double DOT_MIN_SHARE = 0.10;
    /** Named at both ends at least this often, a group earns a follow chip. */
    static final int BOTH_ENDS_MIN = 5;
    static final int TOP_WEAKEST_CHIPS = 4;
    static final int MAX_CHIPS = 6;
    static final int MAX_HANDOVERS = 4;
    /** A wide range is binned down to about this many columns. */
    static final int MAX_COLUMNS = 200;
    /** Less SOC movement than this, in tenths of a percent, is not a curve. */
    static final int MIN_SPAN_TENTHS = 10;
    static final int MIN_STEPS = 3;
    /**
     * A SOC reading further than this, in tenths, from both its neighbours is
     * a glitch: SOC moves a tenth (a whole percent on some models) at a time,
     * while a wake-up frame reads 0.
     */
    static final int SOC_JUMP_TENTHS = 20;
    /**
     * A log that falls at least this far, in tenths, and then rises at least as
     * far on charging current (or the mirror image) holds a drive and a charge.
     * Never less than two of the steps the BMS reports: one whole-percent step
     * back up is one regen uptick.
     */
    static final int LEG_TENTHS = 10;
    /** How many samples at each end decide which way the log ran. */
    static final int END_SAMPLES = 5;
    /** A line breaks where more steps than this are missing in a row. */
    static final int GAP_STEPS = 3;
    /** The bound PackMap.add applies: 0xFE/0xFF are "not available" sentinels. */
    private static final int MAX_INDEX = 250;
    /** Bin widths, in tenths of a percent, narrowest first. */
    private static final int[] BINS = {1, 2, 5, 10};
    /**
     * Outside this a cell reading is a glitch or a 0xFFFF sentinel, not a cell:
     * one such row would otherwise be the drive's "lowest cell" and stretch the
     * mV axis from 0 to 3300.
     */
    private static final int CELL_MV_MIN = 1000, CELL_MV_MAX = 5000;

    private static final class Sample {
        final long atMs;
        final int socT, minMv, maxMv, minIdx, maxIdx;
        /** Positive discharging, as the log writes it. */
        final double amps;
        final boolean rest;

        Sample(long atMs, int socT, int minMv, int maxMv, int minIdx, int maxIdx, double amps) {
            this.atMs = atMs;
            this.socT = socT;
            this.minMv = minMv;
            this.maxMv = maxMv;
            this.minIdx = minIdx;
            this.maxIdx = maxIdx;
            this.amps = amps;
            this.rest = Math.abs(amps) < PackMap.REST_AMPS;
        }

        /** Whether this sample can belong to a drive (or else to a charge) by its current. */
        boolean fits(boolean drive) {
            return drive ? amps > -PackMap.REST_AMPS : amps < PackMap.REST_AMPS;
        }
    }

    private final List<Sample> samples = new ArrayList<>();
    private int rowsSeen;
    /** Rows that carried a SOC, usable or not: "no SOC" and "no usable rows" differ. */
    private int socRows;

    @Override
    public void row(long epochMs, Double socPct, double packV, double minMv, double maxMv,
                    int minIdx, int maxIdx, double amps) {
        rowsSeen++;
        // Every bound is written "!(in range)" so a NaN, for which every
        // comparison is false, is out of range rather than through.
        if (socPct == null || !(socPct >= 0 && socPct <= 100)) return;
        socRows++;
        if (minIdx < 0 || minIdx > MAX_INDEX || maxIdx < 0 || maxIdx > MAX_INDEX) return;
        // The pack map drops a row with no pack volts too; the cell bounds are
        // this curve's own, because its headline IS the lowest cell.
        if (!(packV > 0)) return;
        if (!(minMv >= CELL_MV_MIN && minMv <= CELL_MV_MAX)
                || !(maxMv >= CELL_MV_MIN && maxMv <= CELL_MV_MAX)) {
            return;
        }
        samples.add(new Sample(epochMs, (int) Math.round(socPct * 10), (int) Math.round(minMv),
                (int) Math.round(maxMv), minIdx, maxIdx, amps));
    }

    /** One column of the chart. */
    static final class Step {
        /** Column, left to right. */
        final int column;
        /** The SOC this step stands for - its bin's lower edge - in percent. */
        final double soc;
        final int n;
        final int typicalMin, typicalMax, lowestMin, highestMax;
        /** {group, count}, most often first; a tie goes to the lower group. */
        final int[][] weakest, strongest;

        Step(int column, double soc, int n, int typicalMin, int typicalMax,
             int lowestMin, int highestMax, int[][] weakest, int[][] strongest) {
            this.column = column;
            this.soc = soc;
            this.n = n;
            this.typicalMin = typicalMin;
            this.typicalMax = typicalMax;
            this.lowestMin = lowestMin;
            this.highestMax = highestMax;
            this.weakest = weakest;
            this.strongest = strongest;
        }

        boolean thin() {
            return n < THIN_SAMPLES;
        }

        double share(int count) {
            return n == 0 ? 0 : count / (double) n;
        }

        int typicalSpread() {
            return typicalMax - typicalMin;
        }
    }

    /** One stretch of the drive with the same leading weakest group. */
    static final class Handover {
        final int group;
        /** Where the stretch starts and ends, in drive order, in percent. */
        final double fromSoc, toSoc;

        Handover(int group, double fromSoc, double toSoc) {
            this.group = group;
            this.fromSoc = fromSoc;
            this.toSoc = toSoc;
        }
    }

    /** Why a log cannot be drawn as a curve. */
    enum Problem { NONE, NO_ROWS, NO_SOC, NO_CELL_ROWS, TOO_LITTLE_SOC, TOO_FEW_STEPS }

    /** What the pack map calls a group - "weak module", "watch", "low charge" - or null. */
    interface Verdicts {
        String of(int group);
    }

    static final class Curve {
        final Problem problem;
        /** How far the SOC moved, in tenths of a percent. */
        final int spanTenths;
        final long startMs;
        /** The first and last SOC of what is drawn, in file order. */
        final double startSoc, endSoc;
        final double hiSoc, loSoc;
        /**
         * A charge, drawn low to high: the leg drawn rises, or the last few
         * samples sit at least 1% above the first few.
         */
        final boolean charging;
        /**
         * "" for one drive or one charge. A log that holds both draws its longer
         * leg only, and this says so for the subtitle: "drive leg only - this
         * log also charges".
         */
        final String legNote;
        final int binTenths;
        final int columns;
        /** Every step with samples, left to right. */
        final List<Step> all, atRest;
        final int samples, restSamples;
        /**
         * In All samples: the lowest dip, and the widest typical spread of the
         * steps that are not thin (of all of them when every step is thin).
         */
        final Step lowest, widest;
        final List<Handover> handovers;
        final boolean handoversTruncated;
        /** Groups worth following: the top weakest, then any seen at both ends. */
        final List<Integer> chips;
        /** {group, count} over the whole log, most often first. */
        final int[][] weakestOverall, strongestOverall;
        /** The smallest and largest group index any sample named. */
        final int lowIndex, highIndex;
        private final int kLo, kHi;

        private Curve(Problem problem, int spanTenths, long startMs, double startSoc,
                      double endSoc, double hiSoc, double loSoc, boolean charging,
                      String legNote, int binTenths, int kLo, int kHi, List<Step> all,
                      List<Step> atRest,
                      int samples, int restSamples, Step lowest, Step widest,
                      List<Handover> handovers, boolean handoversTruncated,
                      List<Integer> chips, int[][] weakestOverall, int[][] strongestOverall,
                      int lowIndex, int highIndex) {
            this.problem = problem;
            this.spanTenths = spanTenths;
            this.startMs = startMs;
            this.startSoc = startSoc;
            this.endSoc = endSoc;
            this.hiSoc = hiSoc;
            this.loSoc = loSoc;
            this.charging = charging;
            this.legNote = legNote;
            this.binTenths = binTenths;
            this.kLo = kLo;
            this.kHi = kHi;
            this.columns = problem == Problem.NONE ? kHi - kLo + 1 : 0;
            this.all = all;
            this.atRest = atRest;
            this.samples = samples;
            this.restSamples = restSamples;
            this.lowest = lowest;
            this.widest = widest;
            this.handovers = handovers;
            this.handoversTruncated = handoversTruncated;
            this.chips = chips;
            this.weakestOverall = weakestOverall;
            this.strongestOverall = strongestOverall;
            this.lowIndex = lowIndex;
            this.highIndex = highIndex;
        }

        private static Curve failed(Problem p, int span) {
            return new Curve(p, span, 0, 0, 0, 0, 0, false, "", 1, 0, 0,
                    new ArrayList<Step>(), new ArrayList<Step>(), 0, 0, null, null,
                    new ArrayList<Handover>(), false, new ArrayList<Integer>(),
                    new int[0][], new int[0][], 1, 1);
        }

        double binPct() {
            return binTenths / 10.0;
        }

        /** The column a SOC reading, in tenths of a percent, falls in. */
        int columnOfTenths(int tenths) {
            int k = Math.floorDiv(tenths, binTenths);
            return charging ? k - kLo : kHi - k;
        }

        /** The step of {@code set} closest to {@code column}, or null when it is empty. */
        Step nearest(List<Step> set, int column) {
            Step best = null;
            for (Step s : set) {
                if (best == null || Math.abs(s.column - column) < Math.abs(best.column - column)) {
                    best = s;
                }
            }
            return best;
        }

        /** "72.4%" at 0.1% steps, "20.0–20.4%" on a wider bin. */
        String stepLabel(Step s) {
            if (binTenths == 1) return String.format(Locale.ROOT, "%.1f%%", s.soc);
            return String.format(Locale.ROOT, "%.1f–%.1f%%", s.soc, s.soc + (binTenths - 1) / 10.0);
        }

        /** "g35 22.4–22.0% · g15 22.0–8.4%", with "…" when cut short. */
        String handoverLine() {
            StringBuilder sb = new StringBuilder();
            for (Handover h : handovers) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(String.format(Locale.ROOT, "g%d %.1f–%.1f%%", h.group, h.fromSoc, h.toSoc));
            }
            if (handoversTruncated) sb.append(" · …");
            return sb.toString();
        }

        String problemText() {
            switch (problem) {
                case NO_ROWS:
                    return "No usable rows - this log is missing the cell columns the curve needs.";
                case NO_SOC:
                    return "This log has no charge readings - the pack map still works.";
                case NO_CELL_ROWS:
                    return "This log has no usable cell rows - it read the charge, but no row "
                            + "had cell voltages and group numbers in range.";
                case TOO_LITTLE_SOC:
                    return String.format(Locale.ROOT, "The charge barely moved on this drive "
                            + "(%.1f%%); the curve needs at least 1%%.", spanTenths / 10.0);
                case TOO_FEW_STEPS:
                    return String.format(Locale.ROOT, "The charge moved %.1f%%, which is fewer "
                            + "than %d SOC steps on this car - too few to draw a curve.",
                            spanTenths / 10.0, MIN_STEPS);
                default:
                    return "";
            }
        }
    }

    Curve build() {
        if (rowsSeen == 0) return Curve.failed(Problem.NO_ROWS, 0);
        if (samples.isEmpty()) {
            return Curve.failed(socRows == 0 ? Problem.NO_SOC : Problem.NO_CELL_ROWS, 0);
        }
        List<Sample> leg = settled(samples);
        int[] turn = turn(leg, reportStep(leg));
        if (turn != null) {
            // A drive and a charge in one log: binned together, each step's
            // medians would be values never read, and the drive's weakest group
            // would vanish from the handovers. Draw the longer leg.
            boolean fellFirst = turn[2] == 1;
            List<Sample> before = new ArrayList<>(leg.subList(0, turn[0]));
            List<Sample> after = new ArrayList<>();
            // From the first reading at the turn to the one that confirms it,
            // SOC cannot tell the legs apart - the drive's end, the stop, the
            // charge's start all read the same - so current does: a loaded
            // sample there must not become the charge's lowest cell.
            for (int i = turn[0]; i < turn[1]; i++) {
                Sample x = leg.get(i);
                if (x.fits(fellFirst)) before.add(x);
                if (x.fits(!fellFirst)) after.add(x);
            }
            after.addAll(leg.subList(turn[1], leg.size()));
            // Neither is empty: the turn is never the first sample, and the
            // confirming one always goes after.
            leg = socSpan(after) > socSpan(before) ? after : before;
        }
        // Which way the leg drawn runs, by its own ends, not by where it sat in
        // the log. From a few samples at each end, not one: one stray first or
        // last reading must not flip a drive into a charge.
        int n = Math.max(1, Math.min(END_SAMPLES, leg.size() / 2));
        boolean charging = endMedian(leg, leg.size() - n, n) - endMedian(leg, 0, n) >= 10;
        String legNote = turn == null ? ""
                : charging ? "charge leg only - this log also drives"
                : "drive leg only - this log also charges";
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        int lowIndex = Integer.MAX_VALUE, highIndex = Integer.MIN_VALUE;
        int rest = 0;
        Map<Integer, Integer> weakAll = new HashMap<>(), strongAll = new HashMap<>();
        for (Sample s : leg) {
            lo = Math.min(lo, s.socT);
            hi = Math.max(hi, s.socT);
            lowIndex = Math.min(lowIndex, Math.min(s.minIdx, s.maxIdx));
            highIndex = Math.max(highIndex, Math.max(s.minIdx, s.maxIdx));
            if (s.rest) rest++;
            bump(weakAll, s.minIdx);
            bump(strongAll, s.maxIdx);
        }
        int span = hi - lo;
        if (span < MIN_SPAN_TENTHS) return Curve.failed(Problem.TOO_LITTLE_SOC, span);
        int bin = BINS[BINS.length - 1];
        for (int b : BINS) {
            if (span <= MAX_COLUMNS * b) {
                bin = b;
                break;
            }
        }
        // Never finer than the BMS reports: a model that gives SOC in whole
        // percents gets whole-percent columns, or nine columns in ten would be
        // empty and every line would break into dashes.
        bin = Math.max(bin, reportStep(leg));
        Sample first = leg.get(0), last = leg.get(leg.size() - 1);
        int kLo = Math.floorDiv(lo, bin), kHi = Math.floorDiv(hi, bin);
        List<Step> all = steps(leg, bin, kLo, kHi, charging, false);
        if (all.size() < MIN_STEPS) return Curve.failed(Problem.TOO_FEW_STEPS, span);
        List<Step> atRest = steps(leg, bin, kLo, kHi, charging, true);

        // A thin step's spread can be one hard-acceleration sample; the widest
        // is the widest a step shows typically, not once.
        Step lowest = null, widest = null, widestAny = null;
        for (Step s : all) {
            if (lowest == null || s.lowestMin < lowest.lowestMin) lowest = s;
            if (widestAny == null || s.typicalSpread() > widestAny.typicalSpread()) widestAny = s;
            if (!s.thin() && (widest == null || s.typicalSpread() > widest.typicalSpread())) {
                widest = s;
            }
        }
        if (widest == null) widest = widestAny;

        double hiSoc = hi / 10.0, loSoc = lo / 10.0;
        List<Handover> handovers = handovers(leg, charging,
                charging ? loSoc : hiSoc, charging ? hiSoc : loSoc);
        boolean truncated = handovers.size() > MAX_HANDOVERS;
        if (truncated) handovers = new ArrayList<>(handovers.subList(0, MAX_HANDOVERS));

        int[][] weakest = ranked(weakAll), strongest = ranked(strongAll);
        List<Integer> chips = new ArrayList<>();
        for (int i = 0; i < weakest.length && chips.size() < TOP_WEAKEST_CHIPS; i++) {
            chips.add(weakest[i][0]);
        }
        for (int[] w : weakest) {
            if (chips.size() >= MAX_CHIPS) break;
            if (chips.contains(w[0])) continue;
            Integer asMax = strongAll.get(w[0]);
            if (w[1] >= BOTH_ENDS_MIN && asMax != null && asMax >= BOTH_ENDS_MIN) chips.add(w[0]);
        }

        return new Curve(Problem.NONE, span, first.atMs, first.socT / 10.0, last.socT / 10.0,
                hiSoc, loSoc, charging, legNote, bin, kLo, kHi, all, atRest, leg.size(), rest,
                lowest, widest, handovers, truncated, chips, weakest, strongest,
                lowIndex, highIndex);
    }

    /**
     * The samples whose SOC holds up. A reading is a glitch only when it is more
     * than {@link #SOC_JUMP_TENTHS} from both its neighbours: the last reading
     * kept, and the next one - or the next one its own next reading confirms,
     * so a glitch straight after the first reading cannot take that one along.
     * A reading here is a run of equal SOC, not a row: the log carries each SOC
     * over the blank rows after it, so a glitch arrives as several identical
     * rows that would otherwise confirm each other.
     */
    private static List<Sample> settled(List<Sample> in) {
        List<int[]> runs = new ArrayList<>();      // {first row, end row, SOC}
        for (int i = 0; i < in.size(); ) {
            int soc = in.get(i).socT, j = i;
            while (j < in.size() && in.get(j).socT == soc) j++;
            runs.add(new int[]{i, j, soc});
            i = j;
        }
        int m = runs.size();
        // The SOC of the first later run that its own next run confirms.
        Integer[] confirmedAhead = new Integer[m];
        Integer ahead = null;
        for (int k = m - 1; k >= 0; k--) {
            confirmedAhead[k] = ahead;
            if (k + 1 < m && near(runs.get(k)[2], runs.get(k + 1)[2])) ahead = runs.get(k)[2];
        }
        List<Sample> out = new ArrayList<>();
        Integer kept = null;
        for (int k = 0; k < m; k++) {
            int[] r = runs.get(k);
            boolean ok = (kept != null && near(r[2], kept))
                    || (k + 1 < m && near(r[2], runs.get(k + 1)[2]))
                    || (confirmedAhead[k] != null && near(r[2], confirmedAhead[k]))
                    // A log of one reading has nothing to weigh it against.
                    || (kept == null && k == m - 1);
            if (ok) {
                out.addAll(in.subList(r[0], r[1]));
                kept = r[2];
            }
        }
        return out;
    }

    private static boolean near(int a, int b) {
        return Math.abs(a - b) <= SOC_JUMP_TENTHS;
    }

    /**
     * Where a log that drives and then charges (or charges and then drives)
     * turns round: {where SOC first reached its turning value, the sample that
     * confirms the turn, 1 when the log fell first and 0 when it rose}, or null.
     *
     * Each leg must move {@link #LEG_TENTHS}, and never less than two of the
     * {@code step}s the BMS reports. The second leg must also be carried by
     * current: from the turn to its far end, the median sample is clearly
     * charging on a rise, clearly discharging on a fall. Regen lifts a drive's
     * SOC in bursts between stretches of load, so a rise it makes fails that,
     * stays part of the drive, and the search goes on past its top.
     */
    private static int[] turn(List<Sample> s, int step) {
        if (s.size() < 2) return null;
        int need = Math.max(LEG_TENTHS, 2 * step);
        int start = s.get(0).socT, i = 1;
        // The first leg runs whichever way SOC first moves a whole leg.
        while (i < s.size() && Math.abs(s.get(i).socT - start) < need) i++;
        if (i >= s.size()) return null;
        boolean fell = s.get(i).socT < start;
        int way = fell ? 1 : -1;    // the second leg's direction: up after a fall
        int from = i;               // where the first leg's far extreme was first read
        for (i++; i < s.size(); i++) {
            int gain = way * (s.get(i).socT - s.get(from).socT);
            if (gain < 0) {
                from = i;
            } else if (gain >= need) {
                // The second leg's far end: its extreme, first read, before
                // SOC goes back a whole leg (or the log ends).
                int end = i;
                for (int j = i + 1; j < s.size(); j++) {
                    int past = way * (s.get(j).socT - s.get(end).socT);
                    if (past > 0) end = j;
                    else if (past <= -need) break;
                }
                double amps = medianAmps(s, from, end);
                if (fell ? amps < -PackMap.REST_AMPS : amps > PackMap.REST_AMPS) {
                    return new int[]{from, i, fell ? 1 : 0};
                }
                from = end;
                i = end;
            }
        }
        return null;
    }

    /** The median current, positive discharging, of samples {@code from}..{@code to}. */
    private static double medianAmps(List<Sample> s, int from, int to) {
        double[] a = new double[to - from + 1];
        for (int i = from; i <= to; i++) a[i - from] = s.get(i).amps;
        java.util.Arrays.sort(a);
        int m = a.length / 2;
        return a.length % 2 == 1 ? a[m] : (a[m - 1] + a[m]) / 2;
    }

    /** The SOC step the BMS reports, in tenths: the widest bin every reading sits on. */
    private static int reportStep(List<Sample> s) {
        for (int r = BINS.length - 1; r > 0; r--) {
            boolean coarse = true;
            for (Sample x : s) {
                if (x.socT % BINS[r] != 0) {
                    coarse = false;
                    break;
                }
            }
            if (coarse) return BINS[r];
        }
        return BINS[0];
    }

    private static int socSpan(List<Sample> s) {
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (Sample x : s) {
            lo = Math.min(lo, x.socT);
            hi = Math.max(hi, x.socT);
        }
        return hi - lo;
    }

    /** The median SOC, in tenths, of {@code n} samples from {@code from}. */
    private static int endMedian(List<Sample> s, int from, int n) {
        List<Integer> socs = new ArrayList<>();
        for (int i = from; i < from + n; i++) socs.add(s.get(i).socT);
        return median(socs);
    }

    private static List<Step> steps(List<Sample> leg, int bin, int kLo, int kHi,
                                    boolean charging, boolean restOnly) {
        Map<Integer, Acc> byK = new HashMap<>();
        for (Sample s : leg) {
            if (restOnly && !s.rest) continue;
            int k = Math.floorDiv(s.socT, bin);
            Acc a = byK.get(k);
            if (a == null) {
                a = new Acc();
                byK.put(k, a);
            }
            a.add(s);
        }
        List<Step> out = new ArrayList<>();
        for (Map.Entry<Integer, Acc> e : byK.entrySet()) {
            int k = e.getKey();
            out.add(e.getValue().toStep(charging ? k - kLo : kHi - k, k * bin / 10.0));
        }
        Collections.sort(out, (x, y) -> Integer.compare(x.column, y.column));
        return out;
    }

    /** Everything one step collects before it is summarised. */
    private static final class Acc {
        final List<Integer> mins = new ArrayList<>(), maxs = new ArrayList<>();
        final Map<Integer, Integer> weak = new HashMap<>(), strong = new HashMap<>();

        void add(Sample s) {
            mins.add(s.minMv);
            maxs.add(s.maxMv);
            bump(weak, s.minIdx);
            bump(strong, s.maxIdx);
        }

        Step toStep(int column, double soc) {
            return new Step(column, soc, mins.size(), median(mins), median(maxs),
                    Collections.min(mins), Collections.max(maxs), ranked(weak), ranked(strong));
        }
    }

    /**
     * Where the leading weakest group changes, per whole-percent band.
     *
     * A leader holding a single band between two bands of another is a flicker
     * and is folded into its neighbours: the line names a handover only when
     * the new group holds on.
     */
    private static List<Handover> handovers(List<Sample> leg, boolean charging,
                                            double startEdge, double endEdge) {
        Map<Integer, Map<Integer, Integer>> byBand = new HashMap<>();
        for (Sample s : leg) {
            int b = Math.floorDiv(s.socT, 10);
            Map<Integer, Integer> m = byBand.get(b);
            if (m == null) {
                m = new HashMap<>();
                byBand.put(b, m);
            }
            bump(m, s.minIdx);
        }
        List<Integer> bands = new ArrayList<>(byBand.keySet());
        Collections.sort(bands);
        if (!charging) Collections.reverse(bands);
        // {leader, first band, last band, band count}, in drive order.
        List<int[]> segs = new ArrayList<>();
        for (int b : bands) {
            int leader = ranked(byBand.get(b))[0][0];
            int[] prev = segs.isEmpty() ? null : segs.get(segs.size() - 1);
            if (prev != null && prev[0] == leader) {
                prev[2] = b;
                prev[3]++;
            } else {
                segs.add(new int[]{leader, b, b, 1});
            }
        }
        boolean folded = true;
        while (folded) {
            folded = false;
            for (int i = 1; i + 1 < segs.size(); i++) {
                int[] a = segs.get(i - 1), m = segs.get(i), z = segs.get(i + 1);
                if (m[3] == 1 && a[0] == z[0]) {
                    a[2] = z[2];
                    a[3] += m[3] + z[3];
                    segs.remove(i + 1);
                    segs.remove(i);
                    folded = true;
                    break;
                }
            }
        }
        List<Handover> out = new ArrayList<>();
        double from = startEdge;
        for (int i = 0; i < segs.size(); i++) {
            int[] s = segs.get(i);
            // Handed over at the far edge of this stretch's last band.
            double to = i == segs.size() - 1 ? endEdge : (charging ? s[2] + 1 : s[2]);
            // A stretch with no width is not a stretch: a drive that starts on
            // exactly 95.0% (or a charge that ends on 100.0%) leaves that band
            // as a single reading, and "g1 95.0–95.0%" would take a slot.
            if (Math.abs(to - from) > 1e-9) out.add(new Handover(s[0], from, to));
            from = to;
        }
        if (out.isEmpty()) return out;
        // Dropping one can leave the ends short and two neighbours sharing a
        // leader: the line still starts and ends where the drive does, and one
        // group's stretch is one stretch.
        List<Handover> joined = new ArrayList<>();
        for (Handover h : out) {
            Handover prev = joined.isEmpty() ? null : joined.get(joined.size() - 1);
            if (prev == null) {
                joined.add(new Handover(h.group, startEdge, h.toSoc));
            } else if (prev.group == h.group) {
                joined.set(joined.size() - 1, new Handover(h.group, prev.fromSoc, h.toSoc));
            } else {
                joined.add(new Handover(h.group, prev.toSoc, h.toSoc));
            }
        }
        Handover last = joined.get(joined.size() - 1);
        joined.set(joined.size() - 1, new Handover(last.group, last.fromSoc, endEdge));
        return joined;
    }

    /** True when more than {@link #GAP_STEPS} steps are missing between the two. */
    static boolean breaksBefore(Step prev, Step next) {
        return next.column - prev.column - 1 > GAP_STEPS;
    }

    /** "g15 44% (low charge) · g35 25% · +9 more": the first {@code max}, shares of {@code n}. */
    static String groups(int[][] ranked, int n, int max, Verdicts v) {
        if (ranked == null || ranked.length == 0 || n <= 0) return "none";
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(max, ranked.length);
        for (int i = 0; i < shown; i++) {
            if (i > 0) sb.append(" · ");
            int g = ranked[i][0];
            sb.append('g').append(g).append(' ')
              .append(Math.round(100.0 * ranked[i][1] / n)).append('%');
            String word = v == null ? null : v.of(g);
            if (word != null && !word.isEmpty()) sb.append(" (").append(word).append(')');
        }
        if (ranked.length > shown) sb.append(" · +").append(ranked.length - shown).append(" more");
        return sb.toString();
    }

    /** The curve in words, for a share sheet. Names no car: the pack-map share does. */
    static String render(String source, Curve c, Verdicts v) {
        StringBuilder sb = new StringBuilder("Tata EV BMS - drive curve\n");
        sb.append("Log: ").append(source == null ? "" : source).append('\n');
        if (c.problem != Problem.NONE) {
            sb.append(c.problemText()).append('\n');
            return sb.toString();
        }
        sb.append(String.format(Locale.ROOT, "SOC %.1f%% → %.1f%% (%s), %d samples, %d at rest\n",
                c.startSoc, c.endSoc,
                !c.legNote.isEmpty() ? c.legNote : c.charging ? "charge" : "drive",
                c.samples, c.restSamples));
        sb.append(String.format(Locale.ROOT, "Lowest cell: %d mV at %s SOC\n",
                c.lowest.lowestMin, c.stepLabel(c.lowest)));
        sb.append(String.format(Locale.ROOT, "Widest typical spread: %d mV at %s SOC\n",
                c.widest.typicalSpread(), c.stepLabel(c.widest)));
        sb.append("Weakest by charge: ").append(c.handoverLine()).append('\n');
        sb.append("Weakest overall: ").append(groups(c.weakestOverall, c.samples, 5, v)).append('\n');
        sb.append("Strongest overall: ").append(groups(c.strongestOverall, c.samples, 5, v))
          .append('\n');
        return sb.toString();
    }

    private static void bump(Map<Integer, Integer> m, int key) {
        Integer c = m.get(key);
        m.put(key, c == null ? 1 : c + 1);
    }

    /** {key, count} most often first; a tie goes to the lower key. */
    private static int[][] ranked(Map<Integer, Integer> m) {
        int[][] out = new int[m.size()][];
        int i = 0;
        for (Map.Entry<Integer, Integer> e : m.entrySet()) out[i++] = new int[]{e.getKey(), e.getValue()};
        java.util.Arrays.sort(out, (a, b) -> a[1] != b[1] ? Integer.compare(b[1], a[1])
                : Integer.compare(a[0], b[0]));
        return out;
    }

    private static int median(List<Integer> xs) {
        int[] a = new int[xs.size()];
        for (int i = 0; i < a.length; i++) a[i] = xs.get(i);
        java.util.Arrays.sort(a);
        int m = a.length / 2;
        return a.length % 2 == 1 ? a[m] : (int) Math.round((a[m - 1] + a[m]) / 2.0);
    }
}

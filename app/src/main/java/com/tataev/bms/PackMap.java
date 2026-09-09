package com.tataev.bms;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Accumulates what a drive reveals about individual cell groups.
 *
 * The BMS never exposes a per-group voltage array - it names one minimum and one
 * maximum per sample and nothing else. Over a drive those names change, and each
 * naming is one measurement of one group: "group g sat d mV from the pack average
 * while I amps flowed".
 *
 * THE TRAP THIS CLASS EXISTS TO AVOID. It is tempting to fit a line through every
 * group's measurements and publish a resistance for all of them. That is wrong,
 * and badly so: a group is only sampled while it IS an extreme, and which group
 * is extreme depends on the current. Group 35 holds the minimum at low load and
 * disappears from the data the moment a higher-resistance group takes over, so
 * its surviving samples are all low-current and the fit reads as "improves under
 * load". Fitting the first real drive log that way returned excess resistances of
 * -0.19, -0.23 and -0.43 mOhm. Negative resistance does not exist.
 *
 * A group observed at BOTH extremes escapes that bias, because a group only
 * reaches both ends by being sampled on both sides of zero current: a
 * higher-resistance group sags under discharge (V = OCV - I*R) and rises under
 * charge (V = OCV + I*R). The slope through that crossing is a real measurement.
 * Every other group reports coverage only, and says so.
 *
 * Pure Java: no Android imports, so the self-test drives it directly.
 */
final class PackMap {

    /** Widest index a 1-byte group index can carry. */
    static final int MAX_GROUPS = 256;

    /** Samples at each extreme before a group's resistance may be fitted. */
    static final int MIN_OBSERVATIONS = 8;
    /** Current span (A) a group must be observed across before fitting. */
    static final double MIN_CURRENT_SPAN = 40.0;
    /** Below this the pack is "at rest" - spread there is charge balance only. */
    static final double REST_AMPS = 5.0;
    /** Above this the pack is working hard enough for resistance to show. */
    static final double LOAD_AMPS = 50.0;
    /**
     * Band boundary for CLASSIFYING minima: on three real drives every minimum
     * above this current belonged to a resistive group and every balance-floor
     * minimum stayed below it.
     */
    static final double WATCH_AMPS = 40.0;
    /** Floor on the excess resistance that turns a measured group into a SUSPECT. */
    static final double SUSPECT_MILLIOHM = 0.10;
    /**
     * ...or this fraction of the per-group resistance, whichever is larger. The
     * fixed 0.10 was tuned on ~120 Ah cells at 0.7 mOhm a group, where it is a
     * 14% anomaly; on a smaller-cell pack at 2 mOhm a group the same 0.10 is 5%,
     * which is noise. Nothing here may assume one pack size.
     */
    static final double SUSPECT_FRACTION = 0.15;
    /** Per-group resistance assumed until the pack's own fit exists. */
    static final double DEFAULT_GROUP_MILLIOHM = 0.70;
    /** Load minima required before a group can be WATCH. */
    static final int WATCH_MIN_SAMPLES = 10;
    /** Rest minima required before a group can be BALANCE. */
    static final int BALANCE_MIN_SAMPLES = 20;

    /** Moments kept per group for the tap readout - the newest few, not a log. */
    static final int HISTORY_KEEP = 4;

    /** One moment a group was the pack's weakest or strongest. */
    static final class Moment {
        /** Reading clock (epoch ms); 0 when the sample carried no clock. */
        final long atMs;
        /** The group's own voltage, mV. */
        final int mv;
        /** Its offset from the pack's average group at that moment, mV (rounded). */
        final int deltaMv;
        /** Pack current, A, positive discharging. */
        final double amps;
        /** True when it was the minimum, false when the maximum. */
        final boolean asMin;

        Moment(long atMs, int mv, int deltaMv, double amps, boolean asMin) {
            this.atMs = atMs;
            this.mv = mv;
            this.deltaMv = deltaMv;
            this.amps = amps;
            this.asMin = asMin;
        }
    }

    /** What one group's measurements add up to. */
    static final class Group {
        int minCount;          // times it held the minimum
        int maxCount;          // times it held the maximum
        int minRest;           // ...minima of those at rest (|I| < REST_AMPS)
        int minLoad;           // ...minima under load (I >= WATCH_AMPS)
        // Running least-squares sums of deviation-from-average (mV) against
        // current (A). Constant memory, exact fit, no sample buffer.
        int n;
        double sx, sy, sxx, sxy;
        double loI = Double.MAX_VALUE, hiI = -Double.MAX_VALUE;
        // The last HISTORY_KEEP moments, as a ring: recent[head] is the newest.
        final Moment[] recent = new Moment[HISTORY_KEEP];
        int head = -1;
        int recentN;
        // Offsets at rest (|I| < REST_AMPS) and under load (I >= WATCH_AMPS), kept
        // SEPARATELY for moments as the minimum and as the maximum: a group that
        // swings both ways is the resistive signature this map exists to find,
        // and one mixed-sign mean would average it into "mildly low".
        int restMinMoments, restMaxMoments, loadMinMoments, loadMaxMoments;
        double restMinSum, restMaxSum, loadMinSum, loadMaxSum;

        void remember(Moment mo) {
            head = (head + 1) % HISTORY_KEEP;
            recent[head] = mo;
            if (recentN < HISTORY_KEEP) recentN++;
        }

        /** Newest first. */
        List<Moment> history() {
            List<Moment> out = new ArrayList<>(recentN);
            for (int i = 0; i < recentN; i++) {
                out.add(recent[(head - i + HISTORY_KEEP) % HISTORY_KEEP]);
            }
            return out;
        }

        boolean seen() {
            return minCount > 0 || maxCount > 0;
        }

        /** Seen at both extremes and across enough current to fit honestly. */
        boolean measurable() {
            return minCount >= MIN_OBSERVATIONS && maxCount >= MIN_OBSERVATIONS
                    && (hiI - loI) >= MIN_CURRENT_SPAN && n >= 2 * MIN_OBSERVATIONS;
        }

        /**
         * Excess internal resistance in milliohms, relative to the pack average.
         *
         * Deviation is in mV and current in A, so the slope is already in
         * milliohms. It is negated because a resistive group deviates DOWNWARD as
         * discharge current rises.
         *
         * @return NaN unless {@link #measurable()}
         */
        double excessMilliOhm() {
            if (!measurable()) return Double.NaN;
            double d = n * sxx - sx * sx;
            if (Math.abs(d) < 1e-9) return Double.NaN;
            return -((n * sxy - sx * sy) / d);
        }
    }

    private final Group[] groups = new Group[MAX_GROUPS + 1];
    private int samples;
    private int highestIndex;
    private int seriesCount = 104;
    /**
     * True once the BMS has named group 0, i.e. this model numbers from zero.
     *
     * The Nexon numbers its groups from 1, and index 0 used to be rejected
     * outright - which on a zero-based model threw away every sample naming the
     * first group, and left group 0 permanently invisible on the one screen built
     * for models whose map differs from the Nexon's.
     */
    private boolean zeroBased;
    /**
     * The series count the deviation baseline is measured against, pinned.
     *
     * seriesCount is re-derived from pack voltage over mean cell voltage on every
     * sample and lands on 104 or 105 depending on rounding. That flip moves the
     * baseline by about 3.3 mV, and if it correlates with load - which it does,
     * because the mean cell voltage it is derived from sags under load - the
     * wobble regresses straight into the fitted milliohms. Pin it once enough
     * samples have voted, so every deviation in a fit is measured against the
     * same reference.
     */
    private int baselineSeries;
    /** Balanced votes before the deviation baseline is pinned. */
    static final int BASELINE_SAMPLES = 20;
    /**
     * Widest spread at which a sample may vote on the group count.
     *
     * Reading derives impliedSeries from pack volts over the MIDPOINT of the
     * extreme cells, not the mean. Under load the minimum sags further than the
     * pack does, so the midpoint sits below the mean and the estimate rises: on
     * three real drives it ranged 102.1 to 105.9 within one drive, and a group
     * collapsing near empty would push it past 109. Rounding every sample made
     * the grid grow phantom squares under load, shrink at rest, and persist
     * whatever the last sample happened to round to as the profile's learned
     * size. Only samples balanced enough for midpoint and mean to agree vote,
     * and the vote is a running mean rather than the latest value.
     */
    static final double SERIES_BALANCED_MV = 20.0;
    /**
     * Widest spread at which a RESTING sample may vote. At rest the spread is
     * charge imbalance - a constant offset, not a load-correlated one - so an
     * imbalanced pack still gets a stable count. Without this an imbalanced pack
     * never voted at all and fell back to the per-sample wobble the balanced rule
     * exists to stop: with a constant 30 mV imbalance the baseline never pinned
     * and a true 0.30 mOhm group fitted 0.25. The cap keeps a group collapsing
     * at the knee - hundreds of millivolts - from voting its 110+ series in.
     */
    static final double SERIES_REST_MV = 100.0;
    /**
     * Hysteresis on the rounded count: a running mean sitting near .5 flipped
     * the count 104 to 105 and back twice on one real drive. It moves only when
     * the mean is clearly past the half.
     */
    static final double SERIES_HYSTERESIS = 0.65;
    private double seriesSum;
    private int seriesVotes;
    private boolean seeded;

    // Whole-pack Ohm's law fit: pack volts against current.
    private int pn;
    private double px, py, pxx, pxy;

    // Spread by load, so balance and resistance can be told apart.
    private double restSpread, loadSpread;
    private int restN, loadN;
    // Sample totals per current band: the denominators for classification.
    private int restSamples, loadSamples;

    // SOC context: how low the drive went, and who held the rest floor in each
    // 10% band. Weak groups show first near empty, so the band the fault lives
    // in must be visible - and its absence must be said out loud.
    private double lowestSoc = Double.NaN;
    private final int[] bandRestSamples = new int[10];
    private final int[][] bandRestMin = new int[10][MAX_GROUPS + 1];
    /** Rest samples a SOC band needs before its floor group is reported. */
    static final int SOC_BAND_MIN_SAMPLES = 10;

    /** The rest floor in one 10% SOC band. */
    static final class SocBand {
        final int lo, samples, leader, leaderPct;

        SocBand(int lo, int samples, int leader, int leaderPct) {
            this.lo = lo;
            this.samples = samples;
            this.leader = leader;
            this.leaderPct = leaderPct;
        }
    }

    PackMap() {
        for (int i = 0; i <= MAX_GROUPS; i++) groups[i] = new Group();
    }

    /**
     * Seed the series count a previous session learned, before any sample.
     *
     * Sizes the grid correctly from the first sample and pins the deviation
     * baseline to the same reference the last drive used. The count stays
     * self-correcting - balanced and resting readings still vote on it - and
     * the bound is 250, the same sentinel bound add() enforces,
     * so a profile poisoned by a pre-corroboration version cannot re-pin a
     * wrecked baseline for another drive.
     *
     * zeroBased is deliberately NOT seeded: it is re-derived within two
     * corroborated sightings, and a falsely stored value that was seeded back
     * in would re-persist itself forever (the persist only writes on change).
     * Un-seeded, the next persist overwrites the stored value with what this
     * session actually observed - stale values heal themselves.
     */
    synchronized void seed(int series) {
        if (samples > 0) return;                       // data always wins
        if (series < 20 || series > 250) return;
        this.seriesCount = series;
        this.baselineSeries = series;
        this.seeded = true;
    }

    synchronized boolean isZeroBased() {
        return zeroBased;
    }

    synchronized int samples() {
        return samples;
    }

    /**
     * How many groups this pack has.
     *
     * Derived, never assumed: taken from the live pack-voltage-over-mean-cell
     * figure when a reading supplies one, otherwise from the highest index the
     * BMS has actually named. A Tiago or Punch will not have 104.
     */
    synchronized int seriesCount() {
        return Math.max(seriesCount, highestIndex - firstIndex() + 1);
    }

    /**
     * The index of the first group: 0 on a model that numbers from zero, else 1.
     *
     * Every caller that walks the pack must start here rather than at 1, or a
     * zero-based model loses its first group off the front of the map.
     */
    synchronized int firstIndex() {
        return zeroBased ? 0 : 1;
    }

    /** The index of the last group, inclusive. Never past the array. */
    synchronized int lastIndex() {
        return Math.min(firstIndex() + seriesCount() - 1, MAX_GROUPS);
    }

    /**
     * The live, MUTABLE accumulator for one group.
     *
     * Never hand the result to another thread - its fields are plain ints and
     * doubles that the poll thread keeps writing, and its own methods are not
     * synchronized. Use {@link #snapshot} for anything off the poll thread. This
     * stays package-private for the single-threaded self-test.
     */
    synchronized Group group(int index) {
        return (index >= 0 && index <= MAX_GROUPS) ? groups[index] : null;
    }

    synchronized int seenCount() {
        int c = 0;
        for (int i = firstIndex(); i <= lastIndex(); i++) if (groups[i].seen()) c++;
        return c;
    }

    synchronized int measurableCount() {
        int c = 0;
        for (int i = firstIndex(); i <= lastIndex(); i++) if (groups[i].measurable()) c++;
        return c;
    }


    /** Whole-pack internal resistance in milliohms, or NaN before it is fittable. */
    synchronized double packMilliOhm() {
        if (pn < 12) return Double.NaN;
        double d = pn * pxx - px * px;
        if (Math.abs(d) < 1e-9) return Double.NaN;
        double slope = (pn * pxy - px * py) / d;      // volts per amp
        if (slope >= 0) return Double.NaN;            // no load spread yet
        return -slope * 1000.0;
    }

    /** Per-group resistance from the whole-pack fit, or the default before one exists. */
    synchronized double groupMilliOhm() {
        double p = packMilliOhm();
        return Double.isNaN(p) ? DEFAULT_GROUP_MILLIOHM : p / Math.max(1, seriesCount());
    }

    /** The excess that makes a measured group a SUSPECT on THIS pack. */
    synchronized double suspectThreshold() {
        return Math.max(SUSPECT_MILLIOHM, SUSPECT_FRACTION * groupMilliOhm());
    }

    /** Mean cell spread while the pack is at rest: charge balance alone. */
    synchronized double restSpreadMv() {
        return restN == 0 ? Double.NaN : restSpread / restN;
    }

    /** Mean cell spread under load: balance plus resistance. */
    synchronized double loadSpreadMv() {
        return loadN == 0 ? Double.NaN : loadSpread / loadN;
    }

    /** The part of the spread that only appears under load - i.e. resistance. */
    synchronized double resistiveSpreadMv() {
        double a = restSpreadMv(), b = loadSpreadMv();
        return (Double.isNaN(a) || Double.isNaN(b)) ? Double.NaN : b - a;
    }

    /** Lowest SOC any counted sample carried, or NaN when SOC was never present. */
    synchronized double lowestSoc() {
        return lowestSoc;
    }

    /**
     * True once enough balanced or resting samples have voted that the group
     * count is the pack's rather than one reading's. The service persists the
     * learned count only then: a provisional count taken under load used to be
     * stored, and then SEEDED the next drive's baseline with the wobble the
     * voting exists to remove.
     */
    synchronized boolean countSettled() {
        return seriesVotes >= BASELINE_SAMPLES;
    }

    /** Rest-floor group per 10% SOC band, highest band first; thin bands omitted. */
    synchronized List<SocBand> restFloorBySoc() {
        List<SocBand> out = new ArrayList<>();
        for (int b = 9; b >= 0; b--) {
            int n = bandRestSamples[b];
            if (n < SOC_BAND_MIN_SAMPLES) continue;
            int best = -1, bestN = 0;
            for (int i = 0; i <= MAX_GROUPS; i++) {
                if (bandRestMin[b][i] > bestN) {
                    bestN = bandRestMin[b][i];
                    best = i;
                }
            }
            out.add(new SocBand(b * 10, n, best, (int) Math.round(100.0 * bestN / n)));
        }
        return out;
    }

    /**
     * The field roles {@link #add(Reading)} refuses to sample without - the
     * contract behind the CSV field selector in Settings, which forces exactly
     * these on so no combination of settings can produce a log this map cannot
     * be rebuilt from. The self-test proves the two stay in agreement.
     */
    static final String[] REQUIRED_KEYS = {
            "pack_v", "cell_min_mv", "cell_max_mv",
            "cell_min_idx", "cell_max_idx", "current_a"
    };

    /** True when the map cannot be built without this field role. */
    static boolean requiredKey(String key) {
        for (String k : REQUIRED_KEYS) if (k.equals(key)) return true;
        return false;
    }

    /** Feed one live reading. Silently ignores anything incomplete. */
    synchronized void add(Reading r) {
        if (r == null) return;
        Double packV = r.get("pack_v");
        Double minMv = r.get("cell_min_mv");
        Double maxMv = r.get("cell_max_mv");
        Double minIx = r.get("cell_min_idx");
        Double maxIx = r.get("cell_max_idx");
        Double amps = r.get("current_a");
        if (packV == null || minMv == null || maxMv == null
                || minIx == null || maxIx == null || amps == null) {
            return;
        }
        if (r.impliedSeries != null && r.impliedSeries > 20 && r.impliedSeries < 250) {
            // A sample votes when the midpoint of the extremes is a fair stand-in
            // for the mean: the pack is balanced, or it is at rest with a spread
            // that is imbalance rather than a collapse.
            double spread = maxMv - minMv;
            boolean balanced = spread <= SERIES_BALANCED_MV;
            boolean resting = Math.abs(amps) < REST_AMPS && spread <= SERIES_REST_MV;
            if (balanced || resting) {
                seriesSum += r.impliedSeries;
                seriesVotes++;
                double mean = seriesSum / seriesVotes;
                // The first vote sets the count outright on an unlearned pack;
                // on a seeded one it must beat the hysteresis like any other, or
                // one 104.6 sample overrode a learned 104 with 105 for the drive.
                if ((seriesVotes == 1 && !seeded)
                        || Math.abs(mean - seriesCount) >= SERIES_HYSTERESIS) {
                    seriesCount = (int) Math.round(mean);
                }
            } else if (seriesVotes == 0 && !seeded) {
                // Provisional: an unseeded pack first seen under load still gets
                // its own size from the first sample rather than the default.
                seriesCount = (int) Math.round(r.impliedSeries);
            }
        }
        Double soc = r.get("soc_pct");
        boolean counted = add(r.timestampMs, packV, minMv, maxMv,
                (int) Math.round(minIx), (int) Math.round(maxIx), amps);
        if (counted && soc != null && soc >= 0 && soc <= 100) {
            lowestSoc = Double.isNaN(lowestSoc) ? soc : Math.min(lowestSoc, soc);
            if (Math.abs(amps) < REST_AMPS) {
                int band = Math.min(9, (int) (soc / 10));
                bandRestSamples[band]++;
                bandRestMin[band][(int) Math.round(minIx)]++;
            }
        }
    }

    /** As {@link #add(long, double, double, double, int, int, double)} with no clock. */
    synchronized boolean add(double packV, double minMv, double maxMv,
                             int minIdx, int maxIdx, double amps) {
        return add(0L, packV, minMv, maxMv, minIdx, maxIdx, amps);
    }

    /**
     * Feed one sample.
     *
     * @param atMs   the reading's clock, epoch ms; 0 when unknown
     * @param packV pack volts
     * @param minMv lowest group voltage, mV
     * @param maxMv highest group voltage, mV
     * @param minIdx which group held the minimum
     * @param maxIdx which group held the maximum
     * @param amps   pack current, positive discharging
     * @return whether the sample was counted
     */
    synchronized boolean add(long atMs, double packV, double minMv, double maxMv,
                             int minIdx, int maxIdx, double amps) {
        // 250, not MAX_GROUPS: 0xFE/0xFF are "signal not available" sentinels on
        // many ECUs, and one such glitch latching into highestIndex would blow
        // seriesCount() up to 255, be persisted to the profile, and seed the
        // NEXT drive's deviation baseline ~2.5x wrong - every fitted excess
        // inflated by ~0.4 mOhm and innocent groups painted red. The same bound
        // impliedSeries already applies to itself.
        if (minIdx < 0 || minIdx > 250 || maxIdx < 0 || maxIdx > 250) return false;
        if (packV <= 0) return false;
        samples++;
        // Index 0 is a real group on a zero-based model, not a sentinel - but
        // one glitch frame must not flip the whole map either: a false
        // zeroBased, once persisted, re-seeds itself every session and never
        // heals. Latch on the SECOND sighting (the counts read here are from
        // previous samples; this sample's are recorded below).
        if ((minIdx == 0 || maxIdx == 0)
                && groups[0].minCount + groups[0].maxCount >= 1) {
            zeroBased = true;
        }
        // Corroborated the same way: a group raises the pack's advertised size
        // only once it has been named twice, so one spurious index cannot latch.
        if (groups[minIdx].minCount + groups[minIdx].maxCount >= 1) {
            highestIndex = Math.max(highestIndex, minIdx);
        }
        if (groups[maxIdx].minCount + groups[maxIdx].maxCount >= 1) {
            highestIndex = Math.max(highestIndex, maxIdx);
        }

        // Every group sags together under load; that common motion says nothing
        // about which group is which, so measure each against the pack average.
        double avgMv = packV * 1000.0 / baselineSeries();
        record(groups[minIdx], atMs, minMv, amps, minMv - avgMv, true);
        groups[minIdx].minCount++;
        // WHEN a group holds the minimum separates low charge from resistance:
        // a low-charge group is lowest at rest, a resistive one under load.
        if (Math.abs(amps) < REST_AMPS) {
            groups[minIdx].minRest++;
            restSamples++;
        }
        if (amps >= WATCH_AMPS) {
            groups[minIdx].minLoad++;
            loadSamples++;
        }
        // A sample naming ONE group as both extremes says the pack is flat, not
        // that this group reached both ends. Counting it twice let a group become
        // measurable() - and be handed a fitted resistance - without ever having
        // been at both extremes, which is the single thing this class exists to
        // prevent.
        if (maxIdx != minIdx) {
            record(groups[maxIdx], atMs, maxMv, amps, maxMv - avgMv, false);
            groups[maxIdx].maxCount++;
        }

        pn++;
        px += amps;
        py += packV;
        pxx += amps * amps;
        pxy += amps * packV;

        double spread = maxMv - minMv;
        if (Math.abs(amps) < REST_AMPS) {
            restSpread += spread;
            restN++;
        } else if (amps > LOAD_AMPS) {
            loadSpread += spread;
            loadN++;
        }
        return true;
    }

    /**
     * The series count every deviation is measured against: the balanced
     * estimate, pinned once {@link #BASELINE_SAMPLES} balanced samples have
     * voted. Before that it tracks the live figure, so an early sample is at
     * worst as good as it was before - and those early samples are the ones least
     * likely to be at both extremes, so they carry the least weight in any fit.
     */
    private int baselineSeries() {
        if (baselineSeries > 0) return baselineSeries;
        if (seriesVotes >= BASELINE_SAMPLES) baselineSeries = seriesCount();
        return seriesCount();
    }

    private static void record(Group g, long atMs, double mv, double amps,
                               double deviationMv, boolean asMin) {
        g.n++;
        g.sx += amps;
        g.sy += deviationMv;
        g.sxx += amps * amps;
        g.sxy += amps * deviationMv;
        g.loI = Math.min(g.loI, amps);
        g.hiI = Math.max(g.hiI, amps);
        g.remember(new Moment(atMs, (int) Math.round(mv),
                (int) Math.round(deviationMv), amps, asMin));
        if (Math.abs(amps) < REST_AMPS) {
            if (asMin) { g.restMinMoments++; g.restMinSum += deviationMv; }
            else       { g.restMaxMoments++; g.restMaxSum += deviationMv; }
        } else if (amps >= WATCH_AMPS) {
            if (asMin) { g.loadMinMoments++; g.loadMinSum += deviationMv; }
            else       { g.loadMaxMoments++; g.loadMaxSum += deviationMv; }
        }
    }

    /**
     * Verdict for one group, for the map to colour by.
     *
     * Coloured by CONSEQUENCE, not by how the evidence was obtained: SUSPECT is
     * a workshop matter, WATCH needs another drive, BALANCE needs a full charge,
     * HEALTHY and UNSEEN need nothing.
     */
    enum State { UNSEEN, HEALTHY, WATCH, BALANCE, SUSPECT }

    // ------------------------------------------------------------- snapshots
    //
    // Everything below exists because Group is MUTABLE and lives on the poll
    // thread. Handing one to the UI thread put its plain int and double fields
    // outside the monitor, and measurable()/excessMilliOhm() are Group methods,
    // so they were not covered by PackMap's synchronization either. A non-volatile
    // double read is not guaranteed atomic, so the screen could fit a resistance
    // from half-updated least-squares sums and present it as a measurement.
    //
    // The snapshots are immutable and taken under one lock, so a reader also sees
    // one consistent instant rather than a torn mix of several.

    /** An immutable read-out of one group, safe to hand to another thread. */
    static final class Snapshot {
        final int index;
        final int minCount, maxCount;
        final boolean seen, measurable;
        /** NaN unless {@link #measurable}. */
        final double excessMilliOhm;
        /**
         * The excess as a percentage of this pack's own average group. The
         * scale-free figure: a wrong current scale moves both numerator and
         * denominator equally, so this is right even when the mOhm is not.
         * 0 unless {@link #measurable}.
         */
        final int excessPct;
        final State state;
        /** Share of rest / high-load samples where this group held the minimum. */
        final int restMinPct, loadMinPct;
        /** Newest first; at most HISTORY_KEEP. Empty for a group never named. */
        final List<Moment> recent;
        /** Moments at rest / under load, split by which extreme the group was. */
        final int restMinMoments, restMaxMoments, loadMinMoments, loadMaxMoments;
        /** Mean offset over each of those four sets (0 when the set is empty). */
        final double restMinAvgMv, restMaxAvgMv, loadMinAvgMv, loadMaxAvgMv;

        Snapshot(int index, Group g, State state, int restMinPct, int loadMinPct,
                 double groupMilliOhm) {
            this.index = index;
            this.minCount = g.minCount;
            this.maxCount = g.maxCount;
            this.seen = g.seen();
            this.measurable = g.measurable();
            this.excessMilliOhm = g.excessMilliOhm();
            this.excessPct = measurable && groupMilliOhm > 0
                    ? (int) Math.round(100.0 * excessMilliOhm / groupMilliOhm) : 0;
            this.state = state;
            this.restMinPct = restMinPct;
            this.loadMinPct = loadMinPct;
            this.recent = g.history();
            this.restMinMoments = g.restMinMoments;
            this.restMaxMoments = g.restMaxMoments;
            this.loadMinMoments = g.loadMinMoments;
            this.loadMaxMoments = g.loadMaxMoments;
            this.restMinAvgMv = g.restMinMoments == 0 ? 0 : g.restMinSum / g.restMinMoments;
            this.restMaxAvgMv = g.restMaxMoments == 0 ? 0 : g.restMaxSum / g.restMaxMoments;
            this.loadMinAvgMv = g.loadMinMoments == 0 ? 0 : g.loadMinSum / g.loadMinMoments;
            this.loadMaxAvgMv = g.loadMaxMoments == 0 ? 0 : g.loadMaxSum / g.loadMaxMoments;
        }
    }

    /** One group, read under the lock. Null for an index outside the pack. */
    synchronized Snapshot snapshot(int index) {
        Group g = group(index);
        if (g == null) return null;
        return new Snapshot(index, g, stateOf(index),
                restSamples == 0 ? 0
                        : (int) Math.round(100.0 * g.minRest / restSamples),
                loadSamples == 0 ? 0
                        : (int) Math.round(100.0 * g.minLoad / loadSamples),
                groupMilliOhm());
    }

    /** The whole grid, read under ONE lock so the picture is self-consistent. */
    static final class Grid {
        final int firstIndex;
        /** states[i] describes group firstIndex + i. */
        final State[] states;

        Grid(int firstIndex, State[] states) {
            this.firstIndex = firstIndex;
            this.states = states;
        }

        int size() {
            return states.length;
        }
    }

    synchronized Grid grid() {
        // Nothing yet, nothing drawn: before the first sample on an unlearned car
        // the default of 104 would be a guess dressed as a grid.
        if (samples == 0 && !seeded) return new Grid(firstIndex(), new State[0]);
        int first = firstIndex(), last = lastIndex();
        State[] st = new State[Math.max(0, last - first + 1)];
        for (int i = 0; i < st.length; i++) st[i] = stateOf(first + i);
        return new Grid(first, st);
    }

    /** Everything the summary shows, read under one lock. */
    static final class Stats {
        final int samples, seriesCount, seen, measurable;
        final int suspect, watch, balance;
        final double packMilliOhm, restSpreadMv, loadSpreadMv, resistiveSpreadMv;
        /** Group holding the minimum most often under load / at rest, or null. */
        final Snapshot loadLeader, restLeader;

        Stats(int samples, int seriesCount, int seen, int measurable,
              int suspect, int watch, int balance,
              double packMilliOhm, double restSpreadMv, double loadSpreadMv,
              double resistiveSpreadMv, Snapshot loadLeader, Snapshot restLeader) {
            this.samples = samples;
            this.seriesCount = seriesCount;
            this.seen = seen;
            this.measurable = measurable;
            this.suspect = suspect;
            this.watch = watch;
            this.balance = balance;
            this.packMilliOhm = packMilliOhm;
            this.restSpreadMv = restSpreadMv;
            this.loadSpreadMv = loadSpreadMv;
            this.resistiveSpreadMv = resistiveSpreadMv;
            this.loadLeader = loadLeader;
            this.restLeader = restLeader;
        }
    }

    synchronized Stats stats() {
        int su = 0, wa = 0, ba = 0;
        for (int i = firstIndex(); i <= lastIndex(); i++) {
            State st = stateOf(i);
            if (st == State.SUSPECT) su++;
            else if (st == State.WATCH) wa++;
            else if (st == State.BALANCE) ba++;
        }
        int l = loadLeader(), r = restLeader();
        return new Stats(samples(), seriesCount(), seenCount(), measurableCount(),
                su, wa, ba,
                packMilliOhm(), restSpreadMv(), loadSpreadMv(), resistiveSpreadMv(),
                l < 0 ? null : snapshot(l), r < 0 ? null : snapshot(r));
    }

    synchronized State stateOf(int index) {
        Group g = group(index);
        if (g == null || !g.seen()) return State.UNSEEN;
        if (g.measurable() && g.excessMilliOhm() >= suspectThreshold()) {
            return State.SUSPECT;
        }
        // BALANCE is tested BEFORE WATCH, deliberately. SOME group always holds
        // the minimum - under load too - so in a pack with no resistive outlier
        // the low-charge floor group would pass a load-share test and sit amber
        // forever on a perfectly healthy pack. The discriminator is rest
        // dominance: a resistive group cannot hold the floor at rest (it needs
        // current to sag), so a group that is minimum at rest too, and
        // essentially never the maximum, reads as charge rather than
        // resistance. "Essentially": the min and max indices are read ~250 ms
        // apart on a moving pack, and ONE skewed sample naming the floor group
        // as maximum must not demote it to amber for the rest of the drive -
        // hence a 5% tolerance instead of an absolute zero.
        if (g.minRest >= BALANCE_MIN_SAMPLES && restSamples > 0
                && g.minRest * 5 >= restSamples && g.maxCount * 20 <= g.minCount) {
            return State.BALANCE;
        }
        // WATCH needs REST evidence as well as load dominance. Some group is
        // always the minimum, under load too, so a healthy pack connected while
        // already driving had its charge-floor group pass this test after ten
        // load minima - and be announced, with a beep, as a watch. A resistive
        // group cannot hold the floor at rest (it needs current to sag), so the
        // discriminator is the same one BALANCE uses, inverted: enough rest
        // samples, and this group NOT among the floor at rest.
        if (g.minLoad >= WATCH_MIN_SAMPLES && loadSamples > 0
                && g.minLoad * 5 >= loadSamples
                && restSamples >= BALANCE_MIN_SAMPLES && g.minRest * 5 < restSamples) {
            return State.WATCH;
        }
        return State.HEALTHY;
    }

    /** The group holding the minimum most often; -1 when nothing has been seen. */
    synchronized int weakestGroup() {
        int best = -1, bestN = 0;
        for (int i = firstIndex(); i <= lastIndex(); i++) {
            if (groups[i].minCount > bestN) {
                bestN = groups[i].minCount;
                best = i;
            }
        }
        return best;
    }

    /** The group holding the minimum most often under load (>= WATCH_AMPS), or -1. */
    synchronized int loadLeader() {
        int best = -1, bestN = 0;
        for (int i = firstIndex(); i <= lastIndex(); i++) {
            if (groups[i].minLoad > bestN) {
                bestN = groups[i].minLoad;
                best = i;
            }
        }
        return best;
    }

    /** The group holding the minimum most often at rest (|I| < REST_AMPS), or -1. */
    synchronized int restLeader() {
        int best = -1, bestN = 0;
        for (int i = firstIndex(); i <= lastIndex(); i++) {
            if (groups[i].minRest > bestN) {
                bestN = groups[i].minRest;
                best = i;
            }
        }
        return best;
    }

    /**
     * One line for the dashboard: who is weakest under load and who at rest.
     *
     * The Alerter's tally counted raw minima over the last 40 samples, which on
     * a gently driven pack names the low-charge floor group - the very group the
     * map calls BALANCE - so the headline and the map disagreed. Split by band,
     * the two numbers say different things and both are true.
     *
     * @return "" until at least one band has samples
     */
    synchronized String leadersText() {
        StringBuilder sb = new StringBuilder();
        int l = loadLeader();
        if (l >= 0) {
            sb.append(String.format(Locale.ROOT, "under load: group %d (%d%%)", l,
                    Math.round(100.0 * groups[l].minLoad / loadSamples)));
        }
        int r = restLeader();
        if (r >= 0) {
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append(String.format(Locale.ROOT, "at rest: group %d (%d%%)", r,
                    Math.round(100.0 * groups[r].minRest / restSamples)));
        }
        return sb.toString();
    }

    /**
     * Groups worth naming, most serious first: SUSPECT by how often it is the
     * limiting group under load and then by fitted excess, WATCH by share of
     * load minima, BALANCE by share of rest minima. HEALTHY and UNSEEN groups
     * are not findings.
     *
     * Load share before excess for suspects: on one real drive a group at
     * +0.30 mOhm that was weakest in 8% of hard driving ranked above the +0.28
     * group that was weakest in 76% of it, and the second is the story.
     */
    synchronized List<Snapshot> notable() {
        List<Snapshot> out = new ArrayList<>();
        for (int i = firstIndex(); i <= lastIndex(); i++) {
            State st = stateOf(i);
            if (st == State.SUSPECT || st == State.WATCH || st == State.BALANCE) {
                out.add(snapshot(i));
            }
        }
        Collections.sort(out, (a, b) -> {
            if (a.state != b.state) return rank(a.state) - rank(b.state);
            switch (a.state) {
                case SUSPECT:
                    if (a.loadMinPct != b.loadMinPct) return b.loadMinPct - a.loadMinPct;
                    return Double.compare(b.excessMilliOhm, a.excessMilliOhm);
                case WATCH:
                    return b.loadMinPct - a.loadMinPct;
                default:
                    return b.restMinPct - a.restMinPct;
            }
        });
        return out;
    }

    private static int rank(State s) {
        return s == State.SUSPECT ? 0 : s == State.WATCH ? 1 : 2;
    }

}

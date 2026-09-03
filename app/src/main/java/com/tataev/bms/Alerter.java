package com.tataev.bms;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Watches for the signatures of a failing cell group and makes noise about
 * them, because you cannot watch a screen while driving.
 *
 * Two kinds of alert:
 *   1. CONDITIONS, re-armed every 20 s while they hold: cell spread over the
 *      limit, weakest cell under the floor, the 12 V rail low while the car is
 *      awake, or the weakest cell falling fast at steady current - the knee an
 *      LFP group hits when it runs out before the others;
 *   2. VERDICTS, announced once each: the pack map naming a group a watch or a
 *      weak module. These used to be a "lock-on" condition that beeped every
 *      20 s whenever one group held the minimum with the spread over 25 mV -
 *      which on a real pack is any long highway pull.
 *
 * Plain Java by design: the tone generator sits behind {@link Beeper} and the
 * clock behind a {@link LongSupplier}, so the self-test can drive this class
 * directly. It is the one piece of logic in the app a driver would rely on.
 */
final class Alerter {

    /** Samples of min-cell index kept for the running tally. */
    private static final int WINDOW = 40;
    /** Don't re-beep a condition more often than this. */
    private static final long REARM_MS = 20000;
    /** How far back a falling weakest cell is compared. */
    static final long KNEE_WINDOW_MS = 60_000L;
    /**
     * A fall only counts against a reference sample within this much current:
     * a group sagging under a throttle press is Ohm's law, not a knee.
     */
    static final double KNEE_STEADY_AMPS = 20.0;
    /** Consecutive samples a fall must hold before it fires. */
    static final int KNEE_CONFIRM = 2;
    /**
     * Below this the pack is idle and the 12 V rail is the small battery's own
     * business; the DC-DC only holds it up while the car is awake.
     */
    static final double AUX_ACTIVE_AMPS = 1.0;

    private final Deque<Integer> recentMinIdx = new ArrayDeque<>();
    private final Beeper beeper;
    /** Monotonic. A wall clock would re-arm early or late across an NTP step. */
    private final LongSupplier clock;
    private long lastAlertMs;
    private boolean armed;

    /** Set when a condition is active, for the UI to colour itself red. */
    private String activeReason;
    /** The most frequent index in the window and how often it held. */
    private int dominantIdx = -1;
    private int dominantCount;

    /** One sample of the weakest cell, for the knee window. */
    private static final class Sample {
        final long t;
        final double minMv, amps;

        Sample(long t, double minMv, double amps) {
            this.t = t;
            this.minMv = minMv;
            this.amps = amps;
        }
    }

    private final Deque<Sample> recent = new ArrayDeque<>();
    private int kneeStreak;
    /**
     * Per-group resistance, mOhm, for taking Ohm's law out of the knee test.
     *
     * The ±20 A window alone admits 20 A x R of legitimate sag: quiet at the
     * Nexon's 0.7 mOhm a group, but a false knee on any regen-to-throttle
     * transition at the 2 mOhm of a smaller-cell pack or a cold one. Each sample
     * is compared as V + I x R - its open-circuit voltage - so a fall is only a
     * fall once the load has been accounted for. The service feeds the pack
     * map's own fit here; before one exists the default stands.
     */
    private double groupMilliOhm = PackMap.DEFAULT_GROUP_MILLIOHM;

    synchronized void setGroupMilliOhm(double r) {
        if (!Double.isNaN(r) && r > 0) groupMilliOhm = r;
    }

    /**
     * This pack's own mean cell spread at rest, from the pack map; NaN until
     * known. The delta alert is measured against it, not only against a fixed
     * number, so the same app fits a pack whose healthy spread is 30 mV as well
     * as one whose spread is 10 - without a threshold field an owner could not
     * have chosen a value for.
     */
    private double restSpreadMv = Double.NaN;
    /** Multiple of the pack's own rest spread that reads as trouble. */
    static final double DELTA_REST_MULTIPLE = 3.0;

    synchronized void setRestSpreadMv(double mv) {
        restSpreadMv = mv;
    }

    /**
     * The delta limit in force: the configured floor, or three times this
     * pack's own rest spread, whichever is larger. On the Nexon (rest spread
     * 9-17 mV) the floor of 50 wins; on a pack resting at 30 mV it is 90.
     */
    synchronized int deltaLimitMv(Prefs prefs) {
        int floor = prefs.deltaLimitMv();
        if (Double.isNaN(restSpreadMv) || restSpreadMv <= 0) return floor;
        return Math.max(floor, (int) Math.round(DELTA_REST_MULTIPLE * restSpreadMv));
    }

    /** The verdict already announced for each group; survives a link reset. */
    private final Map<Integer, PackMap.State> announced = new HashMap<>();

    Alerter(Beeper beeper, LongSupplier clock) {
        this.beeper = beeper;
        this.clock = clock;
    }

    String activeReason() {
        return activeReason;
    }

    /**
     * Forget the evidence of this link; a new connection starts it over.
     *
     * The re-arm timer is cleared along with the window. It used to survive, so a
     * link dropped and re-established mid-drive with the spread still over limit
     * stayed silent for the remainder of the re-arm interval - the banner showed,
     * but the tone that is meant to reach you while you are driving did not.
     *
     * Announced verdicts are NOT cleared: the pack map they came from survives
     * a reconnect to the same car, and re-announcing "group 77 is a weak module"
     * after every Bluetooth hiccup would be noise. {@link #forgetVerdicts} goes
     * with a new map.
     */
    synchronized void reset() {
        recentMinIdx.clear();
        dominantIdx = -1;
        dominantCount = 0;
        activeReason = null;
        armed = false;
        lastAlertMs = 0;
        recent.clear();
        kneeStreak = 0;
    }

    /** A new pack map - a different vehicle - starts the announcements over. */
    synchronized void forgetVerdicts() {
        announced.clear();
    }

    /**
     * The weakest group as it stands, from the last 40 samples.
     *
     * A fallback for the dashboard until the pack map has band leaders to show;
     * it says who is weakest, not why.
     *
     * @return "" while there is nothing to say
     */
    synchronized String weakestSummary() {
        if (dominantIdx < 0 || recentMinIdx.isEmpty()) return "";
        int pct = Math.round(100f * dominantCount / recentMinIdx.size());
        return String.format(Locale.ROOT, "weakest: group %d - %d%% of last %d",
                dominantIdx, pct, recentMinIdx.size());
    }

    /**
     * Feed one reading. Returns a human-readable reason when a fresh condition
     * alert should fire, otherwise null.
     */
    synchronized String evaluate(Reading r, Prefs prefs) {
        Double delta = r.cellDeltaMv;
        Double minMv = r.get("cell_min_mv");
        Double minIdx = r.get("cell_min_idx");
        Double amps = r.get("current_a");
        Double aux = r.get("aux_12v_v");

        // The knee window is fed on every sample, whatever else is going on, or
        // a spread alert would blind it to the fall happening underneath.
        String knee = kneeReason(minMv, amps, minIdx, prefs);

        String reason = null;
        int deltaLimit = deltaLimitMv(prefs);
        if (delta != null && delta > deltaLimit) {
            reason = String.format(Locale.ROOT, "Cell delta %.0f mV (limit %d)",
                    delta, deltaLimit);
        } else if (minMv != null && minMv < prefs.minCellLimitMv()) {
            reason = String.format(Locale.ROOT, "Weakest cell %.0f mV (floor %d)",
                    minMv, prefs.minCellLimitMv());
        } else if (knee != null) {
            reason = knee;
        } else if (aux != null && amps != null && Math.abs(amps) >= AUX_ACTIVE_AMPS
                && aux < prefs.auxLowV()) {
            // Only while the pack is passing current: with the car awake the
            // DC-DC should be holding the rail near 13.7 V, and a rail that low
            // strands the car as surely as a flat pack. Asleep, it is just a
            // resting lead-acid battery.
            reason = String.format(Locale.ROOT, "12 V battery low: %.1f V (floor %.1f)",
                    aux, prefs.auxLowV());
        }

        if (minIdx != null) {
            recentMinIdx.addLast((int) Math.round(minIdx));
            while (recentMinIdx.size() > WINDOW) recentMinIdx.removeFirst();
            tallyDominant();
        }

        activeReason = reason;
        if (reason == null) return null;

        long now = clock.getAsLong();
        // armed guards the very first alert: without it a run starting at
        // elapsedRealtime < REARM_MS (a freshly booted phone) silently swallowed
        // it, because 0 read as "we beeped at time zero".
        if (armed && now - lastAlertMs < REARM_MS) return null;
        armed = true;
        lastAlertMs = now;
        if (prefs.alertsEnabled()) beeper.beep();
        return reason;
    }

    /**
     * The weakest cell falling faster than load explains.
     *
     * The fault this app exists for is an LFP group running out before the rest:
     * its voltage holds in the flat middle and then drops off a knee within a
     * minute, well before the fixed floor is reached. Compared against the
     * highest minimum in the last minute whose current was within
     * {@link #KNEE_STEADY_AMPS} of now - so a sag under a throttle press, which
     * is Ohm's law, does not count - and held for {@link #KNEE_CONFIRM} samples
     * so one skewed batch cannot fire it.
     */
    private String kneeReason(Double minMv, Double amps, Double minIdx, Prefs prefs) {
        if (minMv == null || amps == null) return null;
        long now = clock.getAsLong();
        recent.addLast(new Sample(now, minMv, amps));
        while (!recent.isEmpty() && now - recent.peekFirst().t > KNEE_WINDOW_MS) {
            recent.removeFirst();
        }
        // Compare open-circuit estimates, V + I x R, so a throttle press is not a
        // fall; the current window still bounds what a wrong R can let through.
        double nowOc = minMv + amps * groupMilliOhm;
        Sample ref = null;
        double refOc = 0;
        for (Sample s : recent) {
            if (Math.abs(s.amps - amps) > KNEE_STEADY_AMPS) continue;
            double oc = s.minMv + s.amps * groupMilliOhm;
            if (ref == null || oc > refOc) {
                ref = s;
                refOc = oc;
            }
        }
        double drop = ref == null ? 0 : refOc - nowOc;
        kneeStreak = drop >= prefs.kneeDropMv() ? kneeStreak + 1 : 0;
        if (kneeStreak < KNEE_CONFIRM || ref == null) return null;
        return String.format(Locale.ROOT,
                "Weakest cell falling fast: -%.0f mV in %d s at steady current%s",
                drop, Math.max(1, (now - ref.t) / 1000),
                minIdx == null ? "" : String.format(Locale.ROOT, " (group %d)", Math.round(minIdx)));
    }

    /**
     * Announce a group's verdict once, and again only when it escalates.
     *
     * Called with the pack map's current findings after every sample. A group
     * is announced when it first becomes a WATCH and again when it becomes a
     * SUSPECT; the same verdict is never repeated, and low charge is not
     * announced at all - it is not a fault. At most one announcement per call,
     * most serious first, so two groups turning suspect in one sample are heard
     * on consecutive samples rather than as one garbled toast.
     *
     * @return the announcement, or null when there is nothing new
     */
    synchronized String verdictAlert(List<PackMap.Snapshot> notable, Prefs prefs) {
        if (notable == null) return null;
        for (PackMap.Snapshot g : notable) {
            if (g.state != PackMap.State.WATCH && g.state != PackMap.State.SUSPECT) continue;
            PackMap.State before = announced.get(g.index);
            if (before == g.state || before == PackMap.State.SUSPECT) continue;
            announced.put(g.index, g.state);
            if (prefs.alertsEnabled()) beeper.beep();
            if (g.state == PackMap.State.SUSPECT) {
                return String.format(Locale.ROOT,
                        "Group %d is now a weak module (%+.2f mΩ vs pack average)",
                        g.index, g.excessMilliOhm);
            }
            return String.format(Locale.ROOT,
                    "Group %d is often weakest under load - watch it", g.index);
        }
        return null;
    }

    /** Count the window's most frequent index, for the summary. */
    private void tallyDominant() {
        Map<Integer, Integer> counts = new HashMap<>();
        int best = -1, bestCount = 0;
        for (int v : recentMinIdx) {
            Integer prev = counts.get(v);
            int c = (prev == null ? 0 : prev) + 1;
            counts.put(v, c);
            if (c > bestCount) {
                bestCount = c;
                best = v;
            }
        }
        dominantIdx = best;
        dominantCount = bestCount;
    }

    void release() {
        beeper.release();
    }
}

package com.tataev.bms;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Notices a current zero point that is wrong for this car.
 *
 * The current DID is decoded as (raw - zero) x scale, and the zero is a BMS
 * encoding convention: 32000 on the Nexon EV Max, 0x8000 on much of the world.
 * With the wrong one a PARKED car reads tens of amps, the rest band is never
 * entered, the charge-floor group gets flagged as a watch while parked, and the
 * 12 V rule fires while the car sleeps. So the app should notice, not wait for
 * the owner to read a raw count off a log.
 *
 * The signature of a parked car with a wrong zero is a large decoded current
 * while the pack voltage AND the state of charge stay flat for a while. Driving
 * never holds the pack voltage that steady - on three real drives not one 40 s
 * window did - and DC charging at that current moves the SOC within the window.
 * Only a stationary pack decoded through the wrong zero fits.
 *
 * Pure Java: no Android imports, so the self-test drives it directly.
 */
final class ZeroCheck {

    /** The window must span at least this long before it may judge. */
    static final long WINDOW_MS = 40_000L;
    /** Pack voltage range that counts as steady over the window. */
    static final double STEADY_V = 0.6;
    /** Mean decoded current that a stationary pack cannot honestly show. */
    static final double SUSPECT_AMPS = 25.0;
    /** Fewer samples than this cannot establish steadiness. */
    static final int MIN_SAMPLES = 10;

    private static final class Sample {
        final long t;
        final double packV, amps, soc;

        Sample(long t, double packV, double amps, double soc) {
            this.t = t;
            this.packV = packV;
            this.amps = amps;
            this.soc = soc;
        }
    }

    private final Deque<Sample> window = new ArrayDeque<>();

    /** A new link starts the evidence over. */
    synchronized void reset() {
        window.clear();
    }

    /**
     * Feed one reading. A reading missing any of the three inputs is dropped
     * rather than guessed at: without SOC, charging cannot be told from parked.
     *
     * @return true when the zero point looks wrong for this car
     */
    synchronized boolean feed(long nowMs, Double packV, Double amps, Double soc) {
        if (packV == null || amps == null || soc == null) return false;
        window.addLast(new Sample(nowMs, packV, amps, soc));
        while (!window.isEmpty() && nowMs - window.peekFirst().t > WINDOW_MS) {
            window.removeFirst();
        }
        if (window.size() < MIN_SAMPLES) return false;
        if (nowMs - window.peekFirst().t < WINDOW_MS * 0.9) return false;
        double vLo = Double.MAX_VALUE, vHi = -Double.MAX_VALUE;
        double sLo = Double.MAX_VALUE, sHi = -Double.MAX_VALUE;
        double aSum = 0;
        for (Sample s : window) {
            vLo = Math.min(vLo, s.packV);
            vHi = Math.max(vHi, s.packV);
            sLo = Math.min(sLo, s.soc);
            sHi = Math.max(sHi, s.soc);
            aSum += Math.abs(s.amps);
        }
        boolean steadyVolts = vHi - vLo <= STEADY_V;
        boolean flatSoc = sHi - sLo < 0.05;
        boolean bigCurrent = aSum / window.size() >= SUSPECT_AMPS;
        return steadyVolts && flatSoc && bigCurrent;
    }
}

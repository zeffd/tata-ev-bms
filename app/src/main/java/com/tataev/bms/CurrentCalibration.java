package com.tataev.bms;

import java.util.Locale;

/**
 * Pins the two numbers behind the current reading against things with a
 * display: a parked car and a DC fast charger.
 *
 * The current DID is decoded as (raw - zero) x scale. Both were measured on one
 * Nexon EV Max - the zero when its contactors opened (raw 0x7D00 = 32000), the
 * scale inferred from what a parked car should draw - and another Tata model's
 * BMS may use a different zero (0x8000 is the other common midpoint), a
 * different scale, or count charging upward. Neither number has anything to do
 * with pack capacity; nothing in the app does.
 *
 * Two steps, in order. Parked and awake, the true current is under an amp, so
 * the raw count IS the zero to within a few counts. Then, charging, the true
 * current is the charger's displayed power over the pack volts, and the scale
 * is that over the count from zero - with the sign telling which way this BMS
 * counts. Every mOhm on the pack map scales with the result.
 *
 * Pure Java: no Android imports, so the self-test drives it directly.
 */
final class CurrentCalibration {

    private CurrentCalibration() { }

    /** Below this many counts from zero the reading is noise-level, not a charge. */
    static final int MIN_COUNTS = 20;
    /** Anything under this is not a charger reading worth calibrating against. */
    static final double MIN_KW = 1.0;

    static final class Result {
        /** Signed: negative when this BMS counts charging upward. */
        final double scale, amps;
        final int counts;
        /** Null on success; otherwise what to tell the user. */
        final String error;
        /** A caution that goes with a successful result, or null. */
        final String note;

        Result(double scale, double amps, int counts, String error, String note) {
            this.scale = scale;
            this.amps = amps;
            this.counts = counts;
            this.error = error;
            this.note = note;
        }
    }

    private static Result error(String message) {
        return new Result(Double.NaN, Double.NaN, 0, message, null);
    }

    /**
     * The zero point from a parked, awake car: the raw count as it stands.
     *
     * @return the raw value, or null when there is no reading to take it from
     */
    static Integer zeroFromParked(String rawHex) {
        try {
            return Integer.parseInt(rawHex, 16);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * @param chargerKw the power the charger displays right now
     * @param packV     pack volts from the same moment
     * @param rawHex    the raw current DID payload from the same moment
     * @param zero      the raw value that means zero amps
     */
    static Result fromCharger(double chargerKw, Double packV, String rawHex, int zero) {
        if (Double.isNaN(chargerKw) || chargerKw < MIN_KW) {
            return error("Enter the power the charger is showing, in kW (at least 1)");
        }
        if (packV == null || packV <= 0) return error("No pack voltage reading yet - connect first");
        int raw;
        try {
            raw = Integer.parseInt(rawHex, 16);
        } catch (RuntimeException e) {
            return error("No current reading yet - connect first");
        }
        int counts = raw - zero;
        if (Math.abs(counts) < MIN_COUNTS) {
            return error(String.format(Locale.ROOT,
                    "The current count is only %d from zero - is the car charging, and is "
                            + "the zero point above right for this car?", counts));
        }
        double amps = chargerKw * 1000.0 / packV;
        double magnitude = amps / Math.abs(counts);
        if (counts > 0) {
            // Positive is discharging in this app. A charging car counting UPWARD
            // means this BMS uses the opposite convention - or the zero point is
            // wrong for it. The user said the car is charging, so take them at
            // their word, flip the sign, and say what that assumed.
            return new Result(-magnitude, amps, counts, null,
                    "This BMS counts charging upward, so the scale is negative to flip "
                            + "the sign. If the car was in fact discharging, or the zero "
                            + "point above is wrong for this car (set it from a parked car "
                            + "first), do not save this.");
        }
        return new Result(magnitude, amps, counts, null, null);
    }
}

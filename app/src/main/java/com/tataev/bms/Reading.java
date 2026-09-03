package com.tataev.bms;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * One poll of the BMS: scaled values, raw hex, and the derived diagnostics.
 *
 * Both maps are keyed by field ROLE (BmsFields.Field.key) rather than by DID, so
 * a vehicle with a remapped DID still lands in the same column.
 */
final class Reading {

    final long timestampMs;
    final Map<String, Double> values = new LinkedHashMap<>();
    final Map<String, String> raw = new LinkedHashMap<>();
    /**
     * Roles whose values were carried forward from an earlier poll rather than
     * read this cycle. The service reads the six pack-map roles every cycle and
     * the slow ones (SOC, SOH, temperatures, the 12 V rail, extras) every fifth,
     * filling the gaps so the dashboard, alerter and map always see a complete
     * reading - but the CSV must not pretend those were measured now, so
     * CsvLogger leaves carried roles blank and readers carry forward.
     */
    final Set<String> carried = new HashSet<>();

    /**
     * The current calibration THIS reading was decoded with. Written per CSV row
     * rather than frozen when the file opens: Settings is reachable while
     * logging, and the charger calibration is done while connected, so a file
     * can straddle a change - and the provenance columns must say so row by row
     * or a re-decode from the raw hex uses the wrong label.
     */
    float curScale = Float.NaN;
    int curZero;
    /** max - min cell voltage in mV: the headline pack-health number. */
    Double cellDeltaMv;
    /** pack V / mean cell V: the derived series cell-group count for this pack. */
    Double impliedSeries;

    Reading(long timestampMs) {
        this.timestampMs = timestampMs;
    }

    Double get(String key) {
        return values.get(key);
    }

    void computeDerived() {
        Double mx = values.get("cell_max_mv");
        Double mn = values.get("cell_min_mv");
        if (mx != null && mn != null) {
            cellDeltaMv = mx - mn;
            Double pack = values.get("pack_v");
            double meanCellV = (mx + mn) / 2.0 / 1000.0;
            if (pack != null && meanCellV > 0.01) {
                impliedSeries = pack / meanCellV;
            }
        }
    }

    String fmt(String key, int decimals) {
        Double v = values.get(key);
        if (v == null) return "--";
        if (decimals == 0) return String.valueOf(Math.round(v));
        // Locale.ROOT: this feeds the notification, and a comma decimal
        // separator there reads as a thousands separator to most people.
        return String.format(Locale.ROOT, "%." + decimals + "f", v);
    }

    /** True when at least the core fields came back, i.e. the link is healthy. */
    boolean isUsable() {
        return values.get("cell_min_mv") != null || values.get("soc_pct") != null;
    }
}

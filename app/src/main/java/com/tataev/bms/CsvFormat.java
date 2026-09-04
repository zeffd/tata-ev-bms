package com.tataev.bms;

import java.util.List;
import java.util.Locale;

/**
 * The CSV contract in one pure place: the header CsvLogger writes, and each row.
 *
 * The file is SELF-DESCRIBING. Values are keyed by role, and a role can be
 * remapped to a different DID by Scan vehicle - so a log that recorded only the
 * role name could not be read back with confidence months later. Every raw
 * column therefore carries the DID it came from in its own name, and each row
 * repeats the app version, the BMS address, the current calibration and the
 * VIN that produced it.
 *
 * A role the service CARRIED from an earlier poll rather than measured this
 * cycle is written blank, not as a minutes-old value dressed as a fresh one;
 * readers carry forward where it matters. Extracted from CsvLogger so the
 * self-test can hold that promise to the byte.
 *
 * Pure Java: no Android imports.
 */
final class CsvFormat {

    private CsvFormat() { }

    /**
     * The CSV layout this build writes, in its own column on every row.
     *
     * A reader needs to know one thing the role names cannot tell it: whether
     * the file predates the v3.3 cell-index flip. That used to be inferred from
     * app_ver, which fails - versionName is "1.1" and frozen, so every log this
     * build writes parses as "before 3.3" and a deliberately swapped map gets
     * its swap inverted on replay. The layout number is independent of any
     * version scheme: 1 is "no marker" (anything written before this column
     * existed), 2 is "indices are as the role names say". Bump it only when the
     * MEANING of a column changes, never for a new column - readers key on
     * names, so adding one is not a layout change.
     */
    static final int LAYOUT = 2;

    static String header(List<BmsFields.Field> logged, Prefs prefs) {
        StringBuilder sb = new StringBuilder("epoch_ms,timestamp");
        for (BmsFields.Field f : logged) sb.append(',').append(f.key);
        sb.append(",cell_delta_mv,implied_series");
        // The DID rides in the name, so a Scan-remapped log still says where each
        // number came from: raw_pack_v_3400, or raw_pack_v_3500 on another model.
        for (BmsFields.Field f : logged) {
            sb.append(",raw_").append(f.key).append('_')
              .append(BmsFields.effectiveDid(f, prefs));
        }
        sb.append(",app_ver,layout,bms_id,cur_scale,cur_zero,vin");
        return sb.toString();
    }

    static String row(Reading r, List<BmsFields.Field> logged, String stamp,
                      String appVersion, String bmsId, String vin) {
        StringBuilder sb = new StringBuilder();
        sb.append(r.timestampMs).append(',').append(stamp);
        for (BmsFields.Field f : logged) {
            Double v = r.carried.contains(f.key) ? null : r.values.get(f.key);
            sb.append(',').append(v == null ? "" : trim(v));
        }
        sb.append(',').append(r.cellDeltaMv == null ? "" : trim(r.cellDeltaMv));
        sb.append(',').append(r.impliedSeries == null ? ""
                : String.format(Locale.ROOT, "%.1f", r.impliedSeries));
        for (BmsFields.Field f : logged) {
            String h = r.carried.contains(f.key) ? null : r.raw.get(f.key);
            sb.append(',').append(h == null ? "" : h);
        }
        // The calibration THIS row was decoded with, from the reading itself, so
        // a file that straddles a Settings change stays true row by row.
        sb.append(',').append(appVersion)
          .append(',').append(LAYOUT)
          .append(',').append(bmsId)
          .append(',').append(Float.isNaN(r.curScale) ? ""
                  : String.format(Locale.ROOT, "%.4f", r.curScale))
          .append(',').append(r.curZero)
          .append(',').append(vin == null ? "" : vin);
        return sb.toString();
    }

    /** Whole numbers without a decimal point; everything else to two places. */
    static String trim(double v) {
        if (v == Math.rint(v)) return String.valueOf((long) v);
        return String.format(Locale.ROOT, "%.2f", v);
    }
}

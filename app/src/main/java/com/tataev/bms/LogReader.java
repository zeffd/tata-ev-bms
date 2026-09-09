package com.tataev.bms;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Replays a logged CSV into a {@link PackMap}.
 *
 * Role names alone are not trustworthy across versions. Up to v3.2 this app
 * shipped the cell indices the wrong way round - a column called
 * {@code cell_min_idx} in an older log actually holds the MAXIMUM index. The raw
 * columns carry their DID in the name ({@code raw_cell_min_idx_3419}), and the
 * DID is the thing that cannot drift, so roles are resolved from the DID
 * wherever one is present and only fall back to the column name when it is not.
 * An old log therefore reads correctly rather than silently inverted.
 */
final class LogReader {

    private LogReader() { }

    /** DID that really holds the minimum index, established by sign reversal. */
    private static final String DID_MIN_IDX = "341A";
    private static final String DID_MAX_IDX = "3419";

    /** Outcome of a replay, so the screen can say what it actually read. */
    static final class Result {
        final PackMap map;
        final int rows;
        final boolean flipCorrected;
        final String appVersion;
        /** The VIN the file recorded, upper-cased; "" for a log without one. */
        final String vin;
        /**
         * Whether the file HAS a vin column. A log written before the column
         * existed and a car whose controller serves no VIN both give vin = "";
         * only the first may be folded into the phone's one identified car.
         */
        final boolean hasVinColumn;

        Result(PackMap map, int rows, boolean flipCorrected, String appVersion, String vin,
               boolean hasVinColumn) {
            this.map = map;
            this.rows = rows;
            this.flipCorrected = flipCorrected;
            this.appVersion = appVersion;
            this.vin = vin;
            this.hasVinColumn = hasVinColumn;
        }
    }

    static Result read(File csv) throws IOException {
        PackMap map = new PackMap();
        int rows = 0;
        boolean corrected = false;
        String version = "";
        String vin = "";
        boolean hasVin = false;

        BufferedReader in = new BufferedReader(new FileReader(csv));
        try {
            String header = in.readLine();
            if (header == null) return new Result(map, 0, false, "", "", false);
            String[] cols = header.split(",", -1);
            Map<String, Integer> at = new HashMap<>();
            for (int i = 0; i < cols.length; i++) {
                at.put(cols[i].trim().toLowerCase(Locale.ROOT), i);
            }

            int cPack = col(at, "pack_v");
            // The row's clock, for the group history; absent on very old logs.
            int cEpoch = col(at, "epoch_ms");
            int cMin = col(at, "cell_min_mv");
            int cMax = col(at, "cell_max_mv");
            int cCur = col(at, "current_a");
            int cVer = col(at, "app_ver");
            // What the file says its own layout is; absent on every log written
            // before the column existed, which is exactly "layout 1".
            int cLayout = col(at, "layout");
            // Optional: the map works without it, but with it the screen can
            // say how low the drive went and who held the rest floor per band.
            int cSoc = col(at, "soc_pct");
            // Which car wrote this. Two cars' logs on one phone must not be
            // tallied into one pack by the history screen.
            int cVin = col(at, "vin");
            hasVin = cVin >= 0;

            // Peek at the first data row for the WRITING app's layout and
            // version: whether the DID correction below may apply depends on
            // them, and the columns must be decided before the row loop.
            String firstData = in.readLine();
            String layout = null;
            if (firstData != null) {
                String[] f0 = firstData.split(",", -1);
                if (cVer >= 0 && cVer < f0.length) version = f0[cVer].trim();
                if (cLayout >= 0 && cLayout < f0.length) layout = f0[cLayout].trim();
                if (cVin >= 0 && cVin < f0.length) {
                    vin = f0[cVin].trim().toUpperCase(Locale.ROOT);
                }
            }

            int cMinIdxVal;
            int cMaxIdxVal;
            if (flipEra(version, layout)) {
                // Resolve the two index columns by DID, not by the name they
                // carry - but ONLY for logs written before the v3.3 flip. A
                // newer log's role columns already reflect the effective
                // overrides, including a deliberate "Swap min and max index"
                // applied on a model whose ordering genuinely differs from the
                // Nexon's - and "correcting" such a file would invert a
                // perfectly correct log.
                int cMinIdx = byDid(cols, DID_MIN_IDX);
                int cMaxIdx = byDid(cols, DID_MAX_IDX);
                if (cMinIdx >= 0 || cMaxIdx >= 0) {
                    // The raw column names told us which DID is which. If the
                    // file's OWN role columns disagree, it predates the flip.
                    corrected = disagrees(cols, DID_MIN_IDX, "cell_min_idx");
                }
                cMinIdxVal = roleIndexColumn(at, cols, DID_MIN_IDX, "cell_min_idx");
                cMaxIdxVal = roleIndexColumn(at, cols, DID_MAX_IDX, "cell_max_idx");
            } else {
                cMinIdxVal = col(at, "cell_min_idx");
                cMaxIdxVal = col(at, "cell_max_idx");
            }

            if (cPack < 0 || cMin < 0 || cMax < 0 || cCur < 0
                    || cMinIdxVal < 0 || cMaxIdxVal < 0) {
                return new Result(map, 0, false, version, vin, hasVin);
            }

            // Prioritised polling leaves SOC blank on four rows in five; the last
            // value stands until the next one, so every sample lands in a band.
            Double lastSoc = null;
            String line = firstData;
            while (line != null) {
                String[] f = line.split(",", -1);
                // Advance NOW: the guards below 'continue', and advancing at
                // the loop bottom would spin forever on the first bad row.
                line = in.readLine();
                if (f.length <= Math.max(cPack, Math.max(cMinIdxVal, cMaxIdxVal))) continue;
                Double pack = num(f, cPack);
                Double mn = num(f, cMin);
                Double mx = num(f, cMax);
                Double cur = num(f, cCur);
                Double mnI = num(f, cMinIdxVal);
                Double mxI = num(f, cMaxIdxVal);
                if (pack == null || mn == null || mx == null
                        || cur == null || mnI == null || mxI == null) {
                    continue;
                }
                if (version.isEmpty() && cVer >= 0 && cVer < f.length) version = f[cVer].trim();

                // Go through a Reading, i.e. the SAME path the live service uses.
                //
                // The primitive add() overload cannot derive the pack's group
                // count - only the Reading one reads impliedSeries - so replaying
                // through it left seriesCount at the hardcoded 104 whatever car
                // wrote the file. On a 96-group Tiago that meant the map reported
                // "N of 104 groups seen", drew 8 phantom squares, and divided the
                // deviation baseline by the wrong number, so the same drive fitted
                // a different resistance replayed than it did live. Building a
                // Reading here means there is one code path, not two that have to
                // be kept in agreement.
                //
                // impliedSeries is RECOMPUTED by computeDerived() rather than read
                // from the CSV's implied_series column: the formula is identical,
                // and recomputing also works on a log written before that column
                // existed.
                Long atMs = longAt(f, cEpoch);
                Reading row = new Reading(atMs == null ? 0 : atMs);
                row.values.put("pack_v", pack);
                row.values.put("cell_min_mv", mn);
                row.values.put("cell_max_mv", mx);
                row.values.put("cell_min_idx", (double) Math.round(mnI));
                row.values.put("cell_max_idx", (double) Math.round(mxI));
                row.values.put("current_a", cur);
                Double soc = num(f, cSoc);
                if (soc != null) lastSoc = soc;
                if (lastSoc != null) row.values.put("soc_pct", lastSoc);
                row.computeDerived();
                map.add(row);
                rows++;
            }
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
        return new Result(map, rows, corrected, version, vin, hasVin);
    }

    /**
     * True when this log may carry the pre-v3.3 index flip, so the DID-based
     * correction applies - given what the file says about itself.
     *
     * The layout column decides when the file has one: layout 2 and up says the
     * index role names are as written, whatever the version string reads. This
     * matters because versionName is "1.1" and frozen, so flipEra(version)
     * alone calls every log this build writes a pre-v3.3 one and inverts a
     * deliberate "Swap min and max index" on replay.
     *
     * No layout column, or one this reader cannot parse, falls back to the
     * version exactly as before: every log already on a phone behaves as it
     * always has.
     */
    static boolean flipEra(String version, String layout) {
        if (layoutAtLeast(layout, LAYOUT_POST_FLIP)) return false;
        return flipEra(version);
    }

    /** The first layout number that declares "indices are as the names say". */
    private static final int LAYOUT_POST_FLIP = 2;

    private static boolean layoutAtLeast(String layout, int least) {
        if (layout == null) return false;
        try {
            return Integer.parseInt(layout.trim()) >= least;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * True when this log may carry the pre-v3.3 index flip, so the DID-based
     * correction applies. Unknown or unparseable versions count as OLD: only a
     * file that provably postdates the flip gives up the correction.
     */
    private static boolean flipEra(String version) {
        try {
            String[] p = version.trim().split("\\.");
            int major = Integer.parseInt(p[0].trim());
            int minor = 0;
            if (p.length > 1) {
                String m = p[1].trim();
                int e = 0;
                while (e < m.length() && Character.isDigit(m.charAt(e))) e++;
                if (e > 0) minor = Integer.parseInt(m.substring(0, e));
            }
            return major < 3 || (major == 3 && minor < 3);
        } catch (RuntimeException e) {
            return true;
        }
    }

    /**
     * The VALUE column for a role, chosen by which DID actually carries it.
     *
     * The raw column is named raw_&lt;role&gt;_&lt;DID&gt;, so the DID identifies
     * which role name this file used for that DID - and that role name is where
     * the decoded value sits.
     */
    private static int roleIndexColumn(Map<String, Integer> at, String[] cols,
                                       String did, String fallbackRole) {
        String role = roleForDid(cols, did);
        if (role != null) {
            int i = col(at, role);
            if (i >= 0) return i;
        }
        return col(at, fallbackRole);
    }

    /** Which role name this file gave a DID, read off its raw_ column. */
    private static String roleForDid(String[] cols, String did) {
        String suffix = "_" + did.toLowerCase(Locale.ROOT);
        for (String c : cols) {
            String k = c.trim().toLowerCase(Locale.ROOT);
            if (k.startsWith("raw_") && k.endsWith(suffix)) {
                return k.substring(4, k.length() - suffix.length());
            }
        }
        return null;
    }

    private static boolean disagrees(String[] cols, String did, String expectedRole) {
        String role = roleForDid(cols, did);
        return role != null && !role.equals(expectedRole);
    }

    private static int byDid(String[] cols, String did) {
        String suffix = "_" + did.toLowerCase(Locale.ROOT);
        for (int i = 0; i < cols.length; i++) {
            String k = cols[i].trim().toLowerCase(Locale.ROOT);
            if (k.startsWith("raw_") && k.endsWith(suffix)) return i;
        }
        return -1;
    }

    private static int col(Map<String, Integer> at, String name) {
        Integer i = at.get(name);
        return i == null ? -1 : i;
    }

    private static Long longAt(String[] f, int i) {
        if (i < 0 || i >= f.length) return null;
        try {
            return Long.parseLong(f[i].trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double num(String[] f, int i) {
        if (i < 0 || i >= f.length) return null;
        String s = f[i].trim();
        if (s.isEmpty()) return null;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

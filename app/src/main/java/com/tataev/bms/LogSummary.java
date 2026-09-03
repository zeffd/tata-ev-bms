package com.tataev.bms;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Locale;

/**
 * The facts about a log a list row can show: when, how long, how far the SOC
 * moved, how many rows. Reads the header and then every line once, parsing only
 * two columns; no PackMap replay, so it is cheap enough for a list of logs.
 *
 * Pure Java: no Android imports, so the self-test drives it directly.
 */
final class LogSummary {

    final long startMs, endMs;
    final int rows;
    final Double socStart, socEnd;
    final String appVersion;
    /** The VIN the file recorded, upper-cased; "" for a log without one. */
    final String vin;

    private LogSummary(long startMs, long endMs, int rows, Double socStart, Double socEnd,
                       String appVersion, String vin) {
        this.startMs = startMs;
        this.endMs = endMs;
        this.rows = rows;
        this.socStart = socStart;
        this.socEnd = socEnd;
        this.appVersion = appVersion;
        this.vin = vin;
    }

    static LogSummary read(File f) throws IOException {
        try (BufferedReader in = new BufferedReader(new FileReader(f))) {
            String header = in.readLine();
            if (header == null) return new LogSummary(0, 0, 0, null, null, "", "");
            String[] cols = header.split(",", -1);
            int cEpoch = -1, cSoc = -1, cVer = -1, cVin = -1;
            for (int i = 0; i < cols.length; i++) {
                String k = cols[i].trim().toLowerCase(Locale.ROOT);
                if (k.equals("epoch_ms")) cEpoch = i;
                else if (k.equals("soc_pct")) cSoc = i;
                else if (k.equals("app_ver")) cVer = i;
                else if (k.equals("vin")) cVin = i;
            }
            long start = 0, end = 0;
            Double socA = null, socB = null;
            String ver = "", vin = "";
            int rows = 0;
            String line;
            while ((line = in.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] fld = line.split(",", -1);
                rows++;
                Long t = longAt(fld, cEpoch);
                Double soc = numAt(fld, cSoc);
                if (rows == 1) {
                    if (t != null) start = t;
                    if (cVer >= 0 && cVer < fld.length) ver = fld[cVer].trim();
                    if (cVin >= 0 && cVin < fld.length) {
                        vin = fld[cVin].trim().toUpperCase(Locale.ROOT);
                    }
                }
                if (t != null) end = t;
                if (soc != null) {
                    if (socA == null) socA = soc;
                    socB = soc;
                }
            }
            return new LogSummary(start, end, rows, socA, socB, ver, vin);
        }
    }

    /** "67 min · SOC 76.0 → 68.2% · 3922 rows" - each part only when known. */
    String describe() {
        StringBuilder sb = new StringBuilder();
        if (endMs > startMs) {
            long min = Math.round((endMs - startMs) / 60000.0);
            sb.append(min < 1 ? "under a minute" : min + " min");
        }
        if (socStart != null && socEnd != null) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(String.format(Locale.ROOT, "SOC %.1f → %.1f%%", socStart, socEnd));
        }
        if (sb.length() > 0) sb.append(" · ");
        sb.append(rows).append(rows == 1 ? " row" : " rows");
        return sb.toString();
    }

    private static Long longAt(String[] f, int i) {
        if (i < 0 || i >= f.length) return null;
        try {
            return Long.parseLong(f[i].trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double numAt(String[] f, int i) {
        if (i < 0 || i >= f.length || f[i].trim().isEmpty()) return null;
        try {
            return Double.parseDouble(f[i].trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

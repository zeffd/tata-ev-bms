package com.tataev.bms;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removing what must never leave the phone, in one place.
 *
 * One file is written to be SHARED with a stranger - the poll report - and it
 * carries text this app did not generate: an adapter transcript. A VIN is 17
 * characters of a fixed alphabet (no I, O or Q, so they cannot be mistaken for
 * 1 and 0) - but so is every CAN frame: with spaces off and headers on, a frame is
 * a run of 17 or more hex digits, all of them in that alphabet. So a run is
 * scrubbed when it holds a VIN letter hex never uses, or when it is hex that
 * DECODES to a VIN; frames are left alone, because they are the evidence.
 *
 * The scrub is a SECOND line of defence, never the first: the poll buffer does
 * not record the VIN read at all, which is also what keeps a VIN split across
 * several frames out of it - each frame line on its own holds too little.
 */
final class Redact {

    private Redact() { }

    /** A run long enough to hold a VIN, in its alphabet. */
    private static final Pattern RUN = Pattern.compile("[A-HJ-NPR-Z0-9]{17,}");
    /** A VIN letter that is not a hex digit: hex text never carries one. */
    private static final Pattern NON_HEX = Pattern.compile("[G-HJ-NPR-Z]");
    private static final Pattern VIN = Pattern.compile("[A-HJ-NPR-Z0-9]{17}");

    /** Every run that is or encodes a VIN replaced by an ellipsis; "" for null. */
    static String withoutVin(String text) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder(text.length());
        Matcher m = RUN.matcher(text);
        int at = 0;
        while (m.find()) {
            String run = m.group();
            if (NON_HEX.matcher(run).find() || encodesVin(run)) {
                out.append(text, at, m.start()).append('…');
                at = m.end();
            }
        }
        return out.append(text, at, text.length()).toString();
    }

    /**
     * Does this hex run, read as ASCII bytes, hold a VIN? Both alignments are
     * tried, so a CAN id or length byte in front cannot shift it out of view.
     */
    private static boolean encodesVin(String hex) {
        if (hex.length() < 34) return false;              // 17 bytes, two digits each
        for (int from = 0; from < 2; from++) {
            StringBuilder ascii = new StringBuilder(hex.length() / 2);
            for (int i = from; i + 2 <= hex.length(); i += 2) {
                int c = Integer.parseInt(hex.substring(i, i + 2), 16);
                ascii.append(c >= 32 && c < 127 ? (char) c : ' ');
            }
            if (VIN.matcher(ascii.toString().toUpperCase(Locale.ROOT)).find()) return true;
        }
        return false;
    }
}

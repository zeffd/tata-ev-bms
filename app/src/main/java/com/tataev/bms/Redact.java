package com.tataev.bms;

/**
 * Removing what must never leave the phone, in one place.
 *
 * One file is written to be SHARED with a stranger - the poll report - and it
 * carries text this app did not generate: an adapter transcript. A VIN is 17
 * characters of a fixed alphabet (no I, O or Q, so they cannot be mistaken for
 * 1 and 0), which is a shape nothing else in the file has.
 *
 * The scrub is a SECOND line of defence, never the first: the poll buffer does
 * not record the VIN read at all.
 */
final class Redact {

    private Redact() { }

    /** Any 17-character VIN-shaped run replaced by an ellipsis; "" for null. */
    static String withoutVin(String text) {
        if (text == null) return "";
        return text.replaceAll("[A-HJ-NPR-Z0-9]{17}", "…");
    }
}

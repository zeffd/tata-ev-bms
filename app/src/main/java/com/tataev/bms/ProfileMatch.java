package com.tataev.bms;

import java.util.Locale;

/**
 * Pure decision logic for binding a connection to a vehicle profile.
 *
 * The VIN (DID F190) identifies the car; when the BMS does not serve one, a
 * fingerprint of BMS address + supplier stands in. Neither says anything about
 * the pack itself - group count, calibration and overrides are all measured or
 * configured, and the profile is merely where they are REMEMBERED per car.
 *
 * Pure Java: no Android imports, so the self-test drives it directly.
 */
final class ProfileMatch {

    enum Action {
        /** Identified as the active profile's car, or not identifiable at all. */
        STAY,
        /** Another stored profile owns this identity. */
        SWITCH,
        /**
         * Active profile has no identity yet - attach this one to it. This is
         * what keeps an existing install's calibration on the first connect
         * after the update, instead of exiling it to an orphaned profile.
         */
        ADOPT,
        /** Active profile belongs to a different car - make a new one. */
        CREATE
    }

    static Action decide(String seenId, String activeId, boolean knownElsewhere) {
        if (seenId == null || seenId.isEmpty()) return Action.STAY;
        if (seenId.equals(activeId)) return Action.STAY;
        if (knownElsewhere) return Action.SWITCH;
        if (activeId == null || activeId.isEmpty()) return Action.ADOPT;
        return Action.CREATE;
    }

    /**
     * Extract a Tata VIN from an identity string, or "".
     *
     * F190 on the Nexon EV Max carries more than the VIN - a model-year prefix
     * and trailing zeros - and every character of that junk is VIN-alphabet, so
     * neither "first 17" nor "last 17" of a clean run finds it. Tata's WMI
     * does: every Tata VIN starts with MAT, and this app is Tata-only by
     * scope. A maximal run of exactly 17 VIN characters is still accepted as a
     * fallback, so an ECU that serves a bare VIN with no junk works too.
     */
    static String extractVin(String s) {
        if (s == null) return "";
        String u = s.trim().toUpperCase(Locale.ROOT);
        for (int i = 0; i + 17 <= u.length(); i++) {
            if (u.startsWith("MAT", i) && vinRun(u, i) >= 17) {
                return u.substring(i, i + 17);
            }
        }
        int start = -1;
        for (int i = 0; i <= u.length(); i++) {
            boolean in = i < u.length() && isVinChar(u.charAt(i));
            if (in && start < 0) start = i;
            if (!in && start >= 0) {
                if (i - start == 17) return u.substring(start, i);
                start = -1;
            }
        }
        return "";
    }

    /** Length of the VIN-alphabet run starting at {@code from}. */
    private static int vinRun(String s, int from) {
        int i = from;
        while (i < s.length() && isVinChar(s.charAt(i))) i++;
        return i - from;
    }

    /** VINs never contain I, O or Q - they read as 1 and 0. */
    private static boolean isVinChar(char c) {
        if (c >= '0' && c <= '9') return true;
        if (c < 'A' || c > 'Z') return false;
        return c != 'I' && c != 'O' && c != 'Q';
    }

    /**
     * Stand-in identity when no VIN is served; "" unless BOTH halves are known.
     *
     * Half an identity is none: the address is always known locally, so a failed
     * supplier read would otherwise manufacture the non-empty pseudo-id "785|" -
     * never matching the real fingerprint on file - and one flaky connect would
     * mint a fresh junk profile, silently switching the drive onto default
     * calibration and thresholds.
     */
    static String fingerprint(String bmsAddress, String supplier) {
        String a = bmsAddress == null ? "" : bmsAddress.trim().toUpperCase(Locale.ROOT);
        String s = supplier == null ? "" : supplier.trim().toUpperCase(Locale.ROOT);
        if (a.isEmpty() || s.isEmpty()) return "";
        return a + "|" + s;
    }

    private ProfileMatch() { }
}

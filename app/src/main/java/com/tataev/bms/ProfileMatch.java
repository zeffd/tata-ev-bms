package com.tataev.bms;

import java.util.Locale;

/**
 * Pure decision logic for binding a connection to a vehicle profile.
 *
 * The VIN (DID F190) identifies the car; when the BMS does not serve one, the
 * controller's serial number stands in. Neither says anything about the pack
 * itself - group count, calibration and overrides are all measured or
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
     * Is anything on one profile's battery map set by hand? Kept pure so the
     * self-test can drive the prefix scan directly.
     *
     * {@code prefix} is the profile's settings prefix - "" for profile 1, which
     * keeps the legacy un-prefixed keys, and "p&lt;id&gt;_" otherwise. The scan
     * is by {@code startsWith}, and that is exactly why it needs pinning: for
     * profile 1 the prefix is {@code did_override_}, which "p2_did_override_x"
     * does not start with, and for profile 2 it is {@code p2_did_override_},
     * which "p20_did_override_x" does not start with (index 2 is '0', not '_').
     * A regression either way re-opens the cross-vehicle contamination this
     * scan exists to prevent.
     *
     * Values are checked, not just keys: Scan writes an EMPTY scale override to
     * mean "decode with the built-in kind", which is not a mapping.
     *
     * Scans exactly the key space Prefs.clearAllDidOverrides clears, so the
     * "mapped" question and the "reset" button can never disagree.
     */
    static boolean anyMapping(java.util.Map<String, ?> all, String prefix) {
        if (all == null) return false;
        String p = (prefix == null ? "" : prefix) + "did_override_";
        String s = (prefix == null ? "" : prefix) + "scale_override_";
        for (java.util.Map.Entry<String, ?> e : all.entrySet()) {
            String k = e.getKey();
            if (k == null || (!k.startsWith(p) && !k.startsWith(s))) continue;
            Object v = e.getValue();
            if (v instanceof String && !((String) v).isEmpty()) return true;
        }
        return false;
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
     * Stand-in identity when no VIN is served: the pack's own serial number.
     *
     * The address|supplier form this used to be is kept as the FALLBACK for a
     * controller with no readable serial, and {@link #bind} rekeys a profile on
     * file under it on its car's first serial read. It cannot be the primary
     * form any more: the address is a fixed 785 on every Tata, so what actually
     * identified the car was the supplier string - and two VIN-less Tatas that
     * both answer GOTION_BMS therefore shared one profile, one calibration, one
     * set of thresholds and one pack map.
     *
     * A serial of nothing but F, or nothing but 0, is an unprogrammed field, not an identity.
     * Neither half known is still "" - the address alone is common to every car,
     * so returning it would mint one junk profile for all of them.
     */
    static String fingerprint(String serial, String supplier) {
        String sn = serial == null ? "" : serial.trim().toUpperCase(Locale.ROOT);
        if (!sn.isEmpty() && !sn.matches("F+|0+")) return SERIAL_KEY + sn;
        return legacyKey(supplier);
    }

    /** Prefix of a serial-number fingerprint. */
    static final String SERIAL_KEY = "SN|";

    /** The pre-serial address|supplier form; "" without a supplier. */
    private static String legacyKey(String supplier) {
        String s = supplier == null ? "" : supplier.trim().toUpperCase(Locale.ROOT);
        return s.isEmpty() ? "" : BmsFields.BMS_REQUEST + "|" + s;
    }

    /** What one connect's identification reads bind to. */
    static final class Binding {
        final Action action;
        /** The profile bound: SWITCH's target, else the active one; 0 for CREATE. */
        final int profile;
        /** What to store on the bound profile; "" stores nothing. */
        final String vin, fingerprint;
        /** The reads could not say whether this is the active profile's car. */
        final boolean unresolved;

        Binding(Action action, int profile, String vin, String fingerprint, boolean unresolved) {
            this.action = action;
            this.profile = profile;
            this.vin = vin;
            this.fingerprint = fingerprint;
            this.unresolved = unresolved;
        }
    }

    /**
     * Which profile this connect's identification reads bind to, and what to
     * store on it.
     *
     * @param vin      the VIN extracted from F190, or ""
     * @param serial   F18C as read, null when the read failed; asked only without a VIN
     * @param supplier F18A as read, null when the read failed
     * @param vins     every profile's stored VIN by id, in id order
     * @param fps      every profile's stored fingerprint by id, in id order
     */
    static Binding bind(String vin, String serial, String supplier, int active,
                        java.util.Map<Integer, String> vins,
                        java.util.Map<Integer, String> fps) {
        String activeVin = valueOf(vins, active), activeFp = valueOf(fps, active);
        if (vin != null && !vin.isEmpty()) {
            int match = ownerOf(vins, vin);
            return bound(decide(vin, activeVin, match > 0 && match != active),
                    match, active, vin, "");
        }
        // The active profile is keyed by what this connect failed to read. The
        // same car with a flaky read is far likelier than a different car, and
        // both guesses are destructive: CREATE mints a junk profile on every
        // hiccup, and falling back to the supplier form SWITCHed to an older
        // profile mid-drive, resetting the pack map. STAY, attaching nothing.
        boolean serialFailed = serial == null || serial.trim().isEmpty();
        if (!activeVin.isEmpty() || (serialFailed && activeFp.startsWith(SERIAL_KEY))) {
            return unresolved(active);
        }
        String id = fingerprint(serial, supplier);
        int match = ownerOf(fps, id);
        if (match < 0 && id.startsWith(SERIAL_KEY)) {
            // A profile from before serials were read is on file under the
            // supplier form. The first serial read used to CREATE an empty
            // profile beside it, so the car lost its calibration; adopt it and
            // rekey it, so no later read can fall back to it either.
            String old = legacyKey(supplier);
            int owner = ownerOf(fps, old);
            if (owner > 0) {
                return bound(owner == active ? Action.ADOPT : Action.SWITCH,
                        owner, active, "", id);
            }
            // Without the supplier, any such profile may be this car's.
            if (old.isEmpty() && anyLegacy(fps)) return unresolved(active);
        }
        if (id.isEmpty()) {
            // Nothing readable: a profile bound to some car may not be this one.
            return new Binding(Action.STAY, active, "", "", !activeFp.isEmpty());
        }
        return bound(decide(id, activeFp, match > 0 && match != active), match, active, "", id);
    }

    private static Binding bound(Action a, int match, int active, String vin, String fp) {
        int profile = a == Action.SWITCH ? match : a == Action.CREATE ? 0 : active;
        return new Binding(a, profile, vin, fp, false);
    }

    private static Binding unresolved(int active) {
        return new Binding(Action.STAY, active, "", "", true);
    }

    private static boolean anyLegacy(java.util.Map<Integer, String> fps) {
        if (fps == null) return false;
        for (String v : fps.values()) {
            if (v != null && v.startsWith(BmsFields.BMS_REQUEST + "|")) return true;
        }
        return false;
    }

    private static String valueOf(java.util.Map<Integer, String> m, int id) {
        String v = m == null ? null : m.get(id);
        return v == null ? "" : v;
    }

    /** The first profile, in id order, holding {@code key}; -1 when none. */
    private static int ownerOf(java.util.Map<Integer, String> m, String key) {
        if (m == null || key == null || key.isEmpty()) return -1;
        for (java.util.Map.Entry<Integer, String> e : m.entrySet()) {
            if (key.equals(e.getValue())) return e.getKey();
        }
        return -1;
    }

    /** May scan results be written into the active profile? */
    enum ScanGate {
        /** Same car, or nothing to know: write. */
        ALLOW,
        /**
         * The scan read no VIN but the profile is bound to one. A flaky VIN read on
         * the same car and a different VIN-less car look identical here, so the
         * person holding the phone decides.
         */
        ASK_SAME_CAR,
        /** The VINs disagree, or another profile owns the scanned VIN: never. */
        BLOCK_DIFFERENT_CAR
    }

    static ScanGate scanWriteGate(String scannedVin, String activeVin,
                                  int ownerOfScannedVin, int activeProfile) {
        String scanned = scannedVin == null ? "" : scannedVin.trim();
        String active = activeVin == null ? "" : activeVin.trim();
        if (!scanned.isEmpty()) {
            if (!active.isEmpty()) {
                return scanned.equalsIgnoreCase(active) ? ScanGate.ALLOW
                        : ScanGate.BLOCK_DIFFERENT_CAR;
            }
            return ownerOfScannedVin > 0 && ownerOfScannedVin != activeProfile
                    ? ScanGate.BLOCK_DIFFERENT_CAR : ScanGate.ALLOW;
        }
        return active.isEmpty() ? ScanGate.ALLOW : ScanGate.ASK_SAME_CAR;
    }

    private ProfileMatch() { }
}

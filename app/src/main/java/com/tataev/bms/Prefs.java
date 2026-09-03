package com.tataev.bms;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/** Every user-adjustable setting, in one place. */
final class Prefs {

    private static final String FILE = "tataev_bms";

    static final String KEY_LOGGING = "logging_enabled";
    static final String KEY_ALERTS = "alerts_enabled";
    static final String KEY_DELTA_LIMIT = "delta_limit_mv";
    static final String KEY_MIN_CELL_LIMIT = "min_cell_limit_mv";
    static final String KEY_CURRENT_SCALE = "current_scale";
    static final String KEY_CURRENT_ZERO = "current_zero_raw";
    static final String KEY_BMS_REQUEST = "bms_request_id";
    static final String KEY_ADAPTER_NAME = "adapter_name";

    private final SharedPreferences sp;

    Prefs(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /**
     * Master switch for CSV logging - the toggle on the dashboard. ON by
     * default: every drive logged is what makes the cross-drive tally work, and
     * that tally is the strongest evidence the app produces.
     */
    boolean loggingEnabled() {
        return sp.getBoolean(KEY_LOGGING, true);
    }

    /**
     * Whether the Expert section of Settings is shown. Phone-wide, not per car:
     * it is about who is holding the phone. Unlocked by seven taps on the
     * version line, the way Android unlocks Developer options.
     */
    boolean expertUnlocked() {
        return sp.getBoolean("expert_unlocked", false);
    }

    void setExpertUnlocked(boolean on) {
        sp.edit().putBoolean("expert_unlocked", on).apply();
    }

    /**
     * The Expert values that differ from their defaults on this car, in plain
     * words ("" when none do), so the normal screen can say so in one line:
     * behaviour the visible settings do not explain must never be a mystery.
     * The adapter name is deliberately not one of them - picking a dongle is
     * a choice about the phone's Bluetooth, not about how this car is read.
     */
    String expertDifferences() {
        StringBuilder d = new StringBuilder();
        if (deltaLimitMv() != DEFAULT_DELTA
                || minCellLimitMv() != DEFAULT_MIN_CELL
                || kneeDropMv() != DEFAULT_KNEE
                || Math.abs(auxLowV() - DEFAULT_AUX) > 1e-3f) {
            d.append("alert thresholds");
        }
        if (Math.abs(currentScale() - DEFAULT_SCALE) > 1e-6f || currentZero() != DEFAULT_ZERO) {
            d.append(d.length() > 0 ? ", " : "").append("current calibration");
        }
        if (bmsRequestIdIsUserEntered()) {
            d.append(d.length() > 0 ? ", " : "").append("a typed battery controller address");
        }
        return d.toString();
    }

    void setLoggingEnabled(boolean on) {
        sp.edit().putBoolean(KEY_LOGGING, on).apply();
    }

    /**
     * Connect on open, once per launch. Global: it is about the phone and the
     * adapter, not the car.
     *
     * The poll interval that used to live beside this is gone. It only ever set
     * a FLOOR under a pace the adapter fixes, and the one reason to wait longer
     * than the adapter needs - a clone that misbehaves when polled flat out -
     * has not been seen. The cycle floor is BmsService.MIN_CYCLE_MS.
     */
    boolean autoConnect() {
        return sp.getBoolean("auto_connect", true);
    }

    void setAutoConnect(boolean on) {
        sp.edit().putBoolean("auto_connect", on).apply();
    }

    boolean alertsEnabled() {
        return sp.getBoolean(KEY_ALERTS, true);
    }

    void setAlertsEnabled(boolean on) {
        sp.edit().putBoolean(KEY_ALERTS, on).apply();
    }

    /**
     * Cell spread that counts as trouble. A healthy pack measured ~10 mV at rest,
     * so 50 mV is a deliberately quiet default that still fires well before a
     * genuine weak group would drag the pack down.
     */
    static final int DEFAULT_DELTA = 50, DEFAULT_MIN_CELL = 3000, DEFAULT_KNEE = 40;
    static final float DEFAULT_AUX = 12.0f;

    int deltaLimitMv() {
        return sp.getInt(pfx() + KEY_DELTA_LIMIT, DEFAULT_DELTA);
    }

    static final int DELTA_MIN = 5, DELTA_MAX = 2000;

    void setDeltaLimitMv(int mv) {
        sp.edit().putInt(pfx() + KEY_DELTA_LIMIT, clamp(mv, DELTA_MIN, DELTA_MAX)).apply();
    }

    /** Absolute floor on the weakest cell; below this a pack is being pushed hard. */
    int minCellLimitMv() {
        return sp.getInt(pfx() + KEY_MIN_CELL_LIMIT, DEFAULT_MIN_CELL);
    }

    static final int MIN_CELL_MIN = 1000, MIN_CELL_MAX = 4200;

    void setMinCellLimitMv(int mv) {
        sp.edit().putInt(pfx() + KEY_MIN_CELL_LIMIT,
                clamp(mv, MIN_CELL_MIN, MIN_CELL_MAX)).apply();
    }

    /**
     * Fall of the weakest cell, within a minute at steady current, that reads as
     * a knee. An LFP group running out drops tens of millivolts in seconds once
     * it leaves the flat part of its curve; 40 mV is well clear of the ~10 mV of
     * ordinary wander and well short of the fixed floor.
     */
    int kneeDropMv() {
        return sp.getInt(pfx() + "knee_drop_mv", DEFAULT_KNEE);
    }

    static final int KNEE_MIN = 10, KNEE_MAX = 500;

    void setKneeDropMv(int mv) {
        sp.edit().putInt(pfx() + "knee_drop_mv", clamp(mv, KNEE_MIN, KNEE_MAX)).apply();
    }

    /**
     * 12 V rail floor while the car is awake. The DC-DC holds a healthy rail
     * near 13.7 V; a drive that dipped to 12.6 V at start-up stayed above this.
     */
    float auxLowV() {
        return sp.getFloat(pfx() + "aux_low_v", DEFAULT_AUX);
    }

    static final float AUX_MIN = 9f, AUX_MAX = 14f;

    void setAuxLowV(float v) {
        float c = Float.isNaN(v) ? 12.0f : Math.max(AUX_MIN, Math.min(AUX_MAX, v));
        sp.edit().putFloat(pfx() + "aux_low_v", c).apply();
    }

    /**
     * Multiplier for the midpoint-encoded current DID. Still uncalibrated, but no
     * longer arbitrary.
     *
     * 0.01 was fitted on a stationary car against the WRONG zero point (see
     * {@link #currentZero}), which made the raw offset look like 761 counts. With
     * the zero corrected the same reading is 7 counts, and only one scale puts a
     * parked-but-awake EV where physics says it should be:
     *
     *   0.01 -> 0.07 A ->  24 W  - too little to run a BMS, VCU and DC-DC
     *   0.1  -> 0.7  A -> 243 W  - right for an awake car holding up its 12 V bus
     *   1.0  -> 7    A -> 2.4 kW - far too much for a parked car
     *
     * Confirm it against a known load - a DC fast charge at a stated kW is the
     * easiest - and adjust here.
     */
    /** The Nexon EV Max values, so Settings can say whether a car still runs on them. */
    static final float DEFAULT_SCALE = 0.1f;
    static final int DEFAULT_ZERO = 32000;

    float currentScale() {
        return sp.getFloat(pfx() + KEY_CURRENT_SCALE, DEFAULT_SCALE);
    }

    static final float SCALE_MIN = 0.0001f, SCALE_MAX = 10f;

    /**
     * Clamped in magnitude: 0 would zero the current reading. NEGATIVE is
     * allowed on purpose - it flips the sign for a BMS that counts charging
     * upward, which the charger calibration detects. Everything downstream
     * (sag under discharge, load bands, the knee) assumes positive means
     * discharging, and the sign here is how another model's BMS meets that.
     */
    void setCurrentScale(float s) {
        float v = s;
        if (Float.isNaN(v) || v == 0f) v = 0.1f;
        if (Math.abs(v) > SCALE_MAX) v = Math.copySign(SCALE_MAX, v);
        if (Math.abs(v) < SCALE_MIN) v = Math.copySign(SCALE_MIN, v);
        sp.edit().putFloat(pfx() + KEY_CURRENT_SCALE, v).apply();
    }

    /**
     * The raw value of DID 3401 that means zero current.
     *
     * MEASURED, not assumed. The first on-vehicle log caught the car shutting
     * down: DID 3482 collapsed from 346.5 V to 1.60 V - the DC link bleeding out
     * as the contactors opened - and at that exact sample 3401 read 0x7D00 =
     * 32000 and stayed there. Open contactors force the current to zero, so 32000
     * is the zero point. 0x8000 (32768) was a guess from the value merely sitting
     * "just under" it, and it made every reading 768 counts too low - which is
     * why a parked car reported -7.61 A.
     *
     * Settable because another Tata EV may well use a different offset.
     */
    int currentZero() {
        return sp.getInt(pfx() + KEY_CURRENT_ZERO, DEFAULT_ZERO);
    }

    static final int ZERO_MIN = 0, ZERO_MAX = 65535;

    void setCurrentZero(int raw) {
        sp.edit().putInt(pfx() + KEY_CURRENT_ZERO, clamp(raw, ZERO_MIN, ZERO_MAX)).apply();
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** True when a value would be silently altered on its way into storage. */
    static boolean inRange(int v, int lo, int hi) {
        return v >= lo && v <= hi;
    }

    /** Empty means "auto-detect on connect". */
    String bmsRequestId() {
        return sp.getString(pfx() + KEY_BMS_REQUEST, "");
    }

    /**
     * @param userEntered true when a person typed it. A user-entered address is
     *                    never auto-cleared: silently deleting what someone
     *                    deliberately configured leaves them with no way to pin
     *                    an ECU the auto-detector cannot recognise.
     */
    void setBmsRequestId(String id, boolean userEntered) {
        sp.edit()
          .putString(pfx() + KEY_BMS_REQUEST,
                  id == null ? "" : id.trim().toUpperCase(Locale.ROOT))
          .putBoolean(pfx() + KEY_BMS_REQUEST + "_user", userEntered)
          .apply();
    }

    void setBmsRequestId(String id) {
        setBmsRequestId(id, false);
    }

    boolean bmsRequestIdIsUserEntered() {
        return sp.getBoolean(pfx() + KEY_BMS_REQUEST + "_user", false);
    }

    String adapterName() {
        return sp.getString(KEY_ADAPTER_NAME, "");
    }

    void setAdapterName(String n) {
        sp.edit().putString(KEY_ADAPTER_NAME, n == null ? "" : n).apply();
    }

    /**
     * Per-role DID override, for a Tata EV whose BMS uses a different map than
     * the one discovered on the Nexon EV Max. Empty means "use the default".
     */
    String didOverride(String roleKey) {
        return sp.getString(pfx() + "did_override_" + roleKey, "");
    }

    void setDidOverride(String roleKey, String did) {
        String v = did == null ? "" : did.trim().toUpperCase(Locale.ROOT);
        sp.edit().putString(pfx() + "did_override_" + roleKey, v).apply();
    }

    void clearAllDidOverrides() {
        SharedPreferences.Editor e = sp.edit();
        // pfx() for profile 1 is "", and "p2_did_override_x" does not start
        // with "did_override_", so each profile's clear stays its own.
        String p = pfx() + "did_override_";
        for (String k : sp.getAll().keySet()) {
            if (k.startsWith(p)) e.remove(k);
        }
        e.apply();
    }

    // ------------------------------------------------------- vehicle profiles
    //
    // A profile is the set of PER-VEHICLE settings: BMS address, DID overrides,
    // current calibration, alert thresholds, and what the pack map has learned.
    // Everything else (logging, poll rate, adapter, CSV fields) stays global.
    //
    // Profile 1 keeps the LEGACY un-prefixed settings keys, so an existing
    // install's settings simply ARE profile 1 - there is no migration step to
    // get wrong. Later profiles prefix their settings keys with "p<id>_".
    // Profile METADATA (name, VIN, fingerprint, learned pack facts) is always
    // prefixed, profile 1 included, so it can never collide with a global key.

    private String pfx() {
        return settingsPrefix(activeProfile());
    }

    private static String settingsPrefix(int id) {
        return id == 1 ? "" : "p" + id + "_";
    }

    private static String meta(int id) {
        return "p" + id + "_";
    }

    int activeProfile() {
        return sp.getInt("active_profile", 1);
    }

    void setActiveProfile(int id) {
        synchronized (sp) {     // atomic against createProfile / deleteProfile
            sp.edit().putInt("active_profile", id).apply();
        }
    }

    /**
     * Run {@code commit} only if {@code expectedProfile} is still the active
     * profile, atomically against profile switches - setActiveProfile shares
     * this monitor, so identifyVehicle cannot re-point the prefix-resolving
     * setters at another car's profile between the check and the writes.
     *
     * @return whether the commit ran
     */
    boolean commitIfActiveProfile(int expectedProfile, Runnable commit) {
        synchronized (sp) {
            if (activeProfile() != expectedProfile) return false;
            commit.run();
            return true;
        }
    }

    java.util.List<Integer> profileIds() {
        String csv = sp.getString("profile_ids", "1");
        java.util.List<Integer> out = new java.util.ArrayList<>();
        for (String s : csv.split(",")) {
            try {
                out.add(Integer.parseInt(s.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        if (out.isEmpty()) out.add(1);
        return out;
    }

    /**
     * Create an empty profile and return its id. Does not switch to it.
     *
     * Synchronized on the process-wide SharedPreferences instance (one object
     * per prefs file, shared by every Prefs): the poll thread creates profiles
     * in identifyVehicle while the main thread can be deleting one, and both
     * are read-modify-writes of the same "profile_ids" list.
     */
    int createProfile() {
        synchronized (sp) {
            int id = sp.getInt("profile_next", 2);
            java.util.List<Integer> ids = profileIds();
            ids.add(id);
            sp.edit().putString("profile_ids", joinIds(ids))
              .putInt("profile_next", id + 1).apply();
            return id;
        }
    }

    /**
     * Delete a profile's settings and metadata. Profile 1 and the active
     * profile are refused: profile 1's settings live on legacy un-prefixed
     * keys a prefix scan cannot tell apart from the global ones, and deleting
     * the profile in use would leave every getter pointing at nothing.
     */
    boolean deleteProfile(int id) {
        synchronized (sp) {              // see createProfile
            if (id == 1 || id == activeProfile()) return false;
            SharedPreferences.Editor e = sp.edit();
            String p = "p" + id + "_";
            for (String k : sp.getAll().keySet()) {
                if (k.startsWith(p)) e.remove(k);
            }
            java.util.List<Integer> ids = profileIds();
            ids.remove(Integer.valueOf(id));
            e.putString("profile_ids", joinIds(ids)).apply();
            return true;
        }
    }

    private static String joinIds(java.util.List<Integer> ids) {
        StringBuilder sb = new StringBuilder();
        for (int i : ids) {
            if (sb.length() > 0) sb.append(',');
            sb.append(i);
        }
        return sb.toString();
    }

    String profileName(int id) {
        return sp.getString(meta(id) + "name", "");
    }

    void setProfileName(int id, String name) {
        sp.edit().putString(meta(id) + "name", name == null ? "" : name.trim()).apply();
    }

    String profileVin(int id) {
        return sp.getString(meta(id) + "vin", "");
    }

    void setProfileVin(int id, String vin) {
        sp.edit().putString(meta(id) + "vin", vin == null ? "" : vin).apply();
    }

    String profileFingerprint(int id) {
        return sp.getString(meta(id) + "fp", "");
    }

    void setProfileFingerprint(int id, String fp) {
        sp.edit().putString(meta(id) + "fp", fp == null ? "" : fp).apply();
    }

    /** The profile whose VIN matches, or -1. */
    int profileByVin(String vin) {
        if (vin == null || vin.isEmpty()) return -1;
        for (int id : profileIds()) {
            if (vin.equals(profileVin(id))) return id;
        }
        return -1;
    }

    int profileByFingerprint(String fp) {
        if (fp == null || fp.isEmpty()) return -1;
        for (int id : profileIds()) {
            if (fp.equals(profileFingerprint(id))) return id;
        }
        return -1;
    }

    /** Series group count a previous session derived; 0 = not learned yet. */
    int learnedSeriesCount() {
        return sp.getInt(meta(activeProfile()) + "series", 0);
    }

    void setLearnedSeriesCount(int n) {
        sp.edit().putInt(meta(activeProfile()) + "series", n).apply();
    }

    boolean learnedZeroBased() {
        return sp.getBoolean(meta(activeProfile()) + "zero_based", false);
    }

    void setLearnedZeroBased(boolean z) {
        sp.edit().putBoolean(meta(activeProfile()) + "zero_based", z).apply();
    }

    /** Range for the on-device DID scan. */
    int scanFrom() {
        return sp.getInt("scan_from", 0x3400);
    }

    int scanTo() {
        return sp.getInt("scan_to", 0x35FF);
    }

    void setScanRange(int from, int to) {
        sp.edit().putInt("scan_from", from).putInt("scan_to", to).apply();
    }

    // The CSV field selector and the "log unresolved DIDs" switch are gone:
    // every field is logged whenever recording is on. Their only purpose was a
    // faster sample, and two-speed polling reads the extras on every fifth cycle
    // for almost nothing. Stored csv_field_* values are ignored.
}

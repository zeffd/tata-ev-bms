package com.tataev.bms;

/**
 * TEST DOUBLE - not shipped.
 *
 * The real Prefs needs an Android Context. The classes under test ask it for DID
 * overrides and for the alert thresholds, so those are plain fields here and the
 * test sets them directly.
 */
final class Prefs {

    int deltaLimit = 50;
    int minCellLimit = 3000;
    int kneeDrop = 40;
    float auxLow = 12.0f;
    boolean alerts = true;

    int kneeDropMv() {
        return kneeDrop;
    }

    float auxLowV() {
        return auxLow;
    }

    final java.util.Map<String, String> dids = new java.util.HashMap<>();

    String didOverride(String roleKey) {
        String d = dids.get(roleKey);
        return d == null ? "" : d;
    }

    final java.util.Map<String, String> scales = new java.util.HashMap<>();

    String scaleOverride(String roleKey) {
        String s = scales.get(roleKey);
        return s == null ? "" : s;
    }

    int deltaLimitMv() {
        return deltaLimit;
    }

    int minCellLimitMv() {
        return minCellLimit;
    }

    boolean alertsEnabled() {
        return alerts;
    }

    float currentScale() {
        return 0.1f;
    }

    int currentZero() {
        return 32000;
    }
}

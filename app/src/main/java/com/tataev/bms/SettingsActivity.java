package com.tataev.bms;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * Settings for an owner whose one job is finding a bad cell group: three
 * switches, the car's name, the other cars on the phone, and one line for a
 * car that is not reading. Everything an engineer might need is behind an
 * Expert section that appears after seven taps on the version line - the same
 * gesture Android uses for Developer options - and stays until hidden.
 *
 * Switches apply at once. Typed values are validated together and committed
 * together by Save, in the header. No "DID", "poll" or "rail" outside Expert.
 */
public final class SettingsActivity extends Activity {

    /**
     * Stable view ids, so the framework can save and restore what is typed.
     * Every view here is built in code, and a view with no id is skipped by
     * onSaveInstanceState entirely.
     */
    private static final int ID_DELTA = 0x7F00_0002;
    private static final int ID_MIN_CELL = 0x7F00_0003;
    private static final int ID_SCALE = 0x7F00_0004;
    private static final int ID_ADAPTER = 0x7F00_0006;
    private static final int ID_ZERO = 0x7F00_0007;
    private static final int ID_LOGGING = 0x7F00_0008;
    private static final int ID_ALERTS = 0x7F00_0009;
    private static final int ID_PROFILE_NAME = 0x7F00_000B;
    private static final int ID_KNEE = 0x7F00_000C;
    private static final int ID_AUX = 0x7F00_000D;
    private static final int ID_CHARGER = 0x7F00_000E;
    private static final int ID_AUTO = 0x7F00_000F;

    private static final String KEY_PROFILE = "shown_profile";
    /** Taps on the version line that open Expert, and how close together. */
    private static final int UNLOCK_TAPS = 7;
    private static final long UNLOCK_WINDOW_MS = 2500L;

    private Prefs prefs;
    private Switch alerts;
    private Switch autoConnect;
    private Switch logging;
    private EditText profileName;
    // Expert-only; null while Expert is hidden.
    private EditText deltaLimit;
    private EditText minCell;
    private EditText kneeDrop;
    private EditText auxLow;
    private EditText currentScale;
    private EditText currentZero;
    private EditText chargerKw;
    private EditText adapterName;
    private TextView calibrationStatus;
    /** The profile this screen was built for; save() refuses to write another. */
    private int shownProfile;
    private int unlockTaps;
    private long lastUnlockTapMs;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = new Prefs(this);
        // A config change can recreate this screen AFTER the service switched
        // profiles mid-connect; restoring the old profile's text over the new
        // one's values would sail past save()'s check. Start clean instead.
        if (saved != null && saved.getInt(KEY_PROFILE, prefs.activeProfile())
                != prefs.activeProfile()) {
            relaunch();
            return;
        }
        shownProfile = prefs.activeProfile();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Palette.BG);
        root.addView(Ui.backHeader(this, "Settings", "Save", this::save));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        body.setPadding(pad, 0, pad, pad);

        // ------------------------------------------------------------ alerts
        body.addView(header("Alerts"));
        alerts = toggle("Tone and vibration", prefs.alertsEnabled());
        alerts.setId(ID_ALERTS);
        alerts.setOnCheckedChangeListener((b, on) -> perProfile(() -> prefs.setAlertsEnabled(on)));
        body.addView(alerts);
        body.addView(note("A weak cell group is announced once when the pack map finds "
                + "it, and again if it gets worse. A fast fall of the weakest cell, a "
                + "spread wider than this pack normally shows, a cell under "
                + String.format(Locale.ROOT, "%.1f", prefs.minCellLimitMv() / 1000.0) + " V and a "
                + "low 12 V battery sound every 20 s while they last. The on-screen "
                + "warning always shows; this switch is the sound."));

        // -------------------------------------------------------- connection
        body.addView(header("Connection"));
        autoConnect = toggle("Connect automatically when the app opens", prefs.autoConnect());
        autoConnect.setId(ID_AUTO);
        autoConnect.setOnCheckedChangeListener((b, on) -> prefs.setAutoConnect(on));
        body.addView(autoConnect);
        body.addView(note("Starts reading as soon as the app opens, if the adapter is "
                + "paired and in range. Once per launch: Stop stays stopped."));

        // --------------------------------------------------------- recording
        body.addView(header("Recording"));
        logging = toggle("Record drives", prefs.loggingEnabled());
        logging.setId(ID_LOGGING);
        logging.setOnCheckedChangeListener((b, on) -> prefs.setLoggingEnabled(on));
        body.addView(logging);
        body.addView(note("The same switch as on the dashboard. Every drive recorded "
                + "feeds All drives, where the same group turning up weak drive after "
                + "drive is the strongest evidence there is. Share or delete from Logs."));

        // ----------------------------------------------------------- vehicle
        body.addView(header("Vehicle"));
        profileName = new EditText(this);
        profileName.setId(ID_PROFILE_NAME);
        profileName.setText(prefs.profileName(shownProfile));
        profileName.setHint("named on first connect");
        profileName.setTextColor(Palette.TEXT);
        profileName.setHintTextColor(Palette.MUTED);
        body.addView(labelled("This car", profileName));
        body.addView(note(profileFacts()));
        for (int id : prefs.profileIds()) {
            if (id != shownProfile) body.addView(profileRow(id));
        }
        body.addView(note("A second Tata EV gets its own entry the first time it is "
                + "connected, matched by VIN, with its own alert thresholds and "
                + "calibration."));
        TextView map = new TextView(this);
        map.setText("Not reading this car? Map its battery codes.");
        map.setTextSize(13);
        map.setTextColor(Palette.OK);
        map.setPadding(0, dp(10), 0, dp(10));
        map.setOnClickListener(v -> startActivity(new Intent(this, ScanActivity.class)));
        Ui.buttonRole(map);
        body.addView(map);

        // ------------------------------------------------- expert, or its door
        String differs = prefs.expertDifferences();
        if (!differs.isEmpty()) {
            TextView inUse = note("This car uses its own " + differs
                    + " (expert settings).");
            inUse.setTextColor(Palette.WARN);
            body.addView(inUse);
        }
        TextView version = note("Tata EV BMS " + versionName());
        version.setPadding(0, dp(24), 0, dp(12));
        version.setOnClickListener(v -> unlockTap());
        body.addView(version);

        if (prefs.expertUnlocked()) body.addView(expertSection());

        ScrollView sv = new ScrollView(this);
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        SystemBars.pad(root);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt(KEY_PROFILE, shownProfile);
    }

    // ------------------------------------------------------------ unlocking

    /**
     * Seven taps on the version line, within a couple of seconds of each other,
     * with a countdown from the third so nobody arrives by accident and anyone
     * who keeps going is told what they are doing.
     */
    private void unlockTap() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastUnlockTapMs > UNLOCK_WINDOW_MS) unlockTaps = 0;
        lastUnlockTapMs = now;
        unlockTaps++;
        if (prefs.expertUnlocked()) {
            if (unlockTaps >= 3) {
                Toast.makeText(this, "Expert settings are already shown below",
                        Toast.LENGTH_SHORT).show();
                unlockTaps = 0;
            }
            return;
        }
        int left = UNLOCK_TAPS - unlockTaps;
        if (left <= 0) {
            unlockTaps = 0;
            confirmExpert();
        } else if (unlockTaps >= 3) {
            Toast.makeText(this, left + " more tap" + (left == 1 ? "" : "s")
                    + " to open expert settings", Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmExpert() {
        new AlertDialog.Builder(this)
                .setTitle("Show expert settings?")
                .setMessage("Expert settings hold raw alert thresholds and the current "
                        + "calibration. They exist "
                        + "for mapping a Tata model this app has not seen. Normal use never "
                        + "needs them, and they stay shown until you hide them.")
                .setPositiveButton("Show", (d, w) -> {
                    prefs.setExpertUnlocked(true);
                    relaunch();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // --------------------------------------------------------------- expert

    private View expertSection() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(header("Expert"));
        box.addView(note("Raw values behind the alerts and the current reading, and the "
                + "escape hatches for a car whose codes differ from the Nexon's. Save "
                + "commits the typed values; the buttons only fill them in."));

        box.addView(header("Alert thresholds"));
        deltaLimit = number(String.valueOf(prefs.deltaLimitMv()));
        deltaLimit.setId(ID_DELTA);
        box.addView(labelled("Cell delta alert floor (mV). In force: the larger of this "
                + "and three times this pack's own rest spread", deltaLimit));
        minCell = number(String.valueOf(prefs.minCellLimitMv()));
        minCell.setId(ID_MIN_CELL);
        box.addView(labelled("Weakest cell floor (mV)", minCell));
        kneeDrop = number(String.valueOf(prefs.kneeDropMv()));
        kneeDrop.setId(ID_KNEE);
        box.addView(labelled("Weakest-cell fall alert (mV within a minute, at steady "
                + "current, load-corrected)", kneeDrop));
        auxLow = decimal(String.valueOf(prefs.auxLowV()));
        auxLow.setId(ID_AUX);
        box.addView(labelled("12 V battery alert below (V, while the pack passes current)",
                auxLow));

        box.addView(header("Current calibration"));
        box.addView(note("Current is decoded as (raw - zero) x scale. Both were measured "
                + "on one Nexon EV Max. The dashboard notices a wrong zero on its own "
                + "and offers the fix; the scale only affects absolute mΩ - the pack "
                + "map's percentages are scale-free."));
        calibrationStatus = note(calibrationStatus());
        box.addView(calibrationStatus);
        Button parked = new Button(this);
        parked.setText("Set zero point from a parked car");
        parked.setOnClickListener(v -> zeroFromParked());
        box.addView(parked);
        chargerKw = decimal("");
        chargerKw.setId(ID_CHARGER);
        chargerKw.setHint("e.g. 30");
        chargerKw.setHintTextColor(Palette.MUTED);
        box.addView(labelled("kW shown on a DC charger right now", chargerKw));
        Button calibrate = new Button(this);
        calibrate.setText("Set current scale from charger");
        calibrate.setOnClickListener(v -> calibrateFromCharger());
        box.addView(calibrate);
        currentZero = number(String.valueOf(prefs.currentZero()));
        currentZero.setId(ID_ZERO);
        box.addView(labelled("Zero: the raw count that means 0 A", currentZero));
        currentScale = decimal(String.valueOf(prefs.currentScale()));
        currentScale.setId(ID_SCALE);
        box.addView(labelled("Scale: amps per count (negative if this car counts charging "
                + "upward)", currentScale));
        Button resetCal = new Button(this);
        resetCal.setText("Back to the Nexon EV Max values");
        resetCal.setOnClickListener(v -> {
            currentZero.setText(String.valueOf(Prefs.DEFAULT_ZERO));
            currentScale.setText(String.valueOf(Prefs.DEFAULT_SCALE));
            Toast.makeText(this, "Defaults filled in. Press Save to keep them.",
                    Toast.LENGTH_SHORT).show();
        });
        box.addView(resetCal);

        box.addView(header("Adapter"));
        adapterName = new EditText(this);
        adapterName.setId(ID_ADAPTER);
        adapterName.setText(prefs.adapterName());
        adapterName.setHint("blank = pick a paired OBD adapter");
        adapterName.setTextColor(Palette.TEXT);
        adapterName.setHintTextColor(Palette.MUTED);
        box.addView(labelled("Bluetooth adapter name", adapterName));
        Button scan = new Button(this);
        scan.setText("Map a different Tata model");
        scan.setOnClickListener(v -> startActivity(new Intent(this, ScanActivity.class)));
        box.addView(scan);

        box.addView(header("Diagnostics"));
        double period = BmsService.actualPeriodS();
        box.addView(note("Tata EV BMS " + versionName()
                + (period > 0 ? String.format(Locale.ROOT, "  ·  %.1f s per sample", period) : "")
                + "  ·  cycle floor " + BmsService.MIN_CYCLE_MS + " ms"));

        Button hide = new Button(this);
        hide.setText("Hide expert settings");
        hide.setOnClickListener(v -> {
            prefs.setExpertUnlocked(false);
            Toast.makeText(this, "Hidden. Values you set stay in force.", Toast.LENGTH_SHORT).show();
            relaunch();
        });
        box.addView(hide);
        return box;
    }

    // ---------------------------------------------------------------- saving

    /**
     * A per-car switch applies at once - but only into the car this screen was
     * built for. The service can switch profiles mid-connect.
     */
    private void perProfile(Runnable write) {
        if (!prefs.commitIfActiveProfile(shownProfile, write)) {
            Toast.makeText(this, "The car changed while this screen was open - nothing "
                    + "was saved. Reopening.", Toast.LENGTH_LONG).show();
            relaunch();
        }
    }

    /**
     * Validate EVERYTHING typed, then commit - or commit nothing at all. With
     * Expert hidden that is just the car's name.
     */
    private void save() {
        if (prefs.activeProfile() != shownProfile) {
            Toast.makeText(this, "The car changed while this screen was open - nothing "
                    + "was saved. Reopening.", Toast.LENGTH_LONG).show();
            relaunch();
            return;
        }
        final boolean expert = deltaLimit != null;
        java.util.List<String> rejected = new java.util.ArrayList<>();
        Integer dl = null, mc = null, kd = null, cz = null;
        Float al = null, sc = null;

        if (expert) {
            dl = parsed(deltaLimit);
            if (dl == null) rejected.add("Delta floor must be a whole number - kept "
                    + prefs.deltaLimitMv() + " mV");
            else if (!Prefs.inRange(dl, Prefs.DELTA_MIN, Prefs.DELTA_MAX)) {
                rejected.add(outOfRange("Delta floor", dl, Prefs.DELTA_MIN, Prefs.DELTA_MAX, "mV"));
                dl = null;
            }
            mc = parsed(minCell);
            if (mc == null) rejected.add("Weakest-cell floor must be a whole number - kept "
                    + prefs.minCellLimitMv() + " mV");
            else if (!Prefs.inRange(mc, Prefs.MIN_CELL_MIN, Prefs.MIN_CELL_MAX)) {
                rejected.add(outOfRange("Weakest-cell floor", mc, Prefs.MIN_CELL_MIN,
                        Prefs.MIN_CELL_MAX, "mV"));
                mc = null;
            }
            kd = parsed(kneeDrop);
            if (kd == null) rejected.add("Cell-fall alert must be a whole number - kept "
                    + prefs.kneeDropMv() + " mV");
            else if (!Prefs.inRange(kd, Prefs.KNEE_MIN, Prefs.KNEE_MAX)) {
                rejected.add(outOfRange("Cell-fall alert", kd, Prefs.KNEE_MIN, Prefs.KNEE_MAX, "mV"));
                kd = null;
            }
            try {
                float v = Float.parseFloat(auxLow.getText().toString().trim().replace(',', '.'));
                if (Float.isNaN(v) || v < Prefs.AUX_MIN || v > Prefs.AUX_MAX) {
                    rejected.add("12 V alert must be between " + Prefs.AUX_MIN + " and "
                            + Prefs.AUX_MAX + " V - kept " + prefs.auxLowV());
                } else {
                    al = v;
                }
            } catch (NumberFormatException e) {
                rejected.add("12 V alert must be a number - kept " + prefs.auxLowV());
            }
            try {
                float v = Float.parseFloat(currentScale.getText().toString().trim().replace(',', '.'));
                float mag = Math.abs(v);
                if (Float.isNaN(v) || mag < Prefs.SCALE_MIN || mag > Prefs.SCALE_MAX) {
                    rejected.add("Scale must be between " + Prefs.SCALE_MIN + " and "
                            + Prefs.SCALE_MAX + " A per count (negative to flip the sign) - kept "
                            + prefs.currentScale());
                } else {
                    sc = v;
                }
            } catch (NumberFormatException e) {
                rejected.add("Scale must be a number - kept " + prefs.currentScale());
            }
            try {
                int v = Integer.parseInt(currentZero.getText().toString().trim());
                if (!Prefs.inRange(v, Prefs.ZERO_MIN, Prefs.ZERO_MAX)) {
                    rejected.add(outOfRange("Zero point", v, Prefs.ZERO_MIN, Prefs.ZERO_MAX, ""));
                } else {
                    cz = v;
                }
            } catch (NumberFormatException e) {
                rejected.add("Zero point must be a whole number 0-65535 - kept "
                        + prefs.currentZero());
            }
        }

        if (!rejected.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String msg : rejected) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(msg);
            }
            Toast.makeText(this, sb.toString(), Toast.LENGTH_LONG).show();
            return;
        }

        // Atomic against identifyVehicle switching profiles on the poll thread.
        final Integer fDl = dl, fMc = mc, fKd = kd, fCz = cz;
        final Float fAl = al, fSc = sc;
        boolean committed = prefs.commitIfActiveProfile(shownProfile, () -> {
            String pn = profileName.getText().toString().trim();
            if (!pn.isEmpty()) prefs.setProfileName(shownProfile, pn);
            if (!expert) return;
            prefs.setDeltaLimitMv(fDl);
            prefs.setMinCellLimitMv(fMc);
            prefs.setKneeDropMv(fKd);
            prefs.setAuxLowV(fAl);
            prefs.setCurrentScale(fSc);
            prefs.setCurrentZero(fCz);
            prefs.setAdapterName(adapterName.getText().toString());
        });

        if (!committed) {
            Toast.makeText(this, "The car changed while this screen was open - nothing "
                    + "was saved. Reopening.", Toast.LENGTH_LONG).show();
            relaunch();
            return;
        }
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
        finish();
    }

    // ---------------------------------------------------------- calibration

    private void calibrateFromCharger() {
        Reading r = BmsService.latest();
        if (!BmsService.isActive() || r == null) {
            Toast.makeText(this, "Connect first, with the car charging", Toast.LENGTH_LONG).show();
            return;
        }
        double kw;
        try {
            kw = Double.parseDouble(chargerKw.getText().toString().trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Enter the power the charger is showing, in kW",
                    Toast.LENGTH_LONG).show();
            return;
        }
        int zero = prefs.currentZero();
        try {
            zero = Integer.parseInt(currentZero.getText().toString().trim());
        } catch (NumberFormatException ignored) {
            // the stored value stands; Save will complain about the field
        }
        CurrentCalibration.Result c = CurrentCalibration.fromCharger(
                kw, r.get("pack_v"), r.raw.get("current_a"), zero);
        if (c.error != null) {
            Toast.makeText(this, c.error, Toast.LENGTH_LONG).show();
            return;
        }
        currentScale.setText(String.format(Locale.ROOT, "%.4f", c.scale));
        calibrationStatus.setText(calibrationStatus());
        Toast.makeText(this, String.format(Locale.ROOT,
                "%.1f kW at %.1f V is %.1f A over %d counts. Scale filled in; press Save.",
                kw, r.get("pack_v"), c.amps, Math.abs(c.counts))
                + (c.note == null ? "" : "\n" + c.note), Toast.LENGTH_LONG).show();
    }

    private void zeroFromParked() {
        Reading r = BmsService.latest();
        if (!BmsService.isActive() || r == null) {
            Toast.makeText(this, "Connect first, with the car parked, awake and unplugged",
                    Toast.LENGTH_LONG).show();
            return;
        }
        Integer z = CurrentCalibration.zeroFromParked(r.raw.get("current_a"));
        if (z == null) {
            Toast.makeText(this, "No current reading yet", Toast.LENGTH_LONG).show();
            return;
        }
        currentZero.setText(String.valueOf(z));
        calibrationStatus.setText(calibrationStatus());
        Toast.makeText(this, "Zero point filled in from the parked car. Press Save.",
                Toast.LENGTH_LONG).show();
    }

    private String calibrationStatus() {
        int zero = prefs.currentZero();
        float scale = prefs.currentScale();
        StringBuilder sb = new StringBuilder("Stored for this car: zero ");
        sb.append(zero == Prefs.DEFAULT_ZERO ? "Nexon EV Max default" : "set for this car")
          .append(", scale ")
          .append(Math.abs(scale - Prefs.DEFAULT_SCALE) < 1e-6f
                  ? "Nexon EV Max default (inferred, not measured)" : "set for this car")
          .append(scale < 0 ? ", counting charging upward" : "")
          .append('.');
        Reading r = BmsService.latest();
        Double amps = r == null ? null : r.get("current_a");
        String raw = r == null ? null : r.raw.get("current_a");
        if (amps != null && raw != null) {
            sb.append(String.format(Locale.ROOT, "\nRight now: raw %s reads as %.1f A.", raw, amps));
        } else {
            sb.append("\nConnect to see the live raw count and what it decodes to.");
        }
        return sb.toString();
    }

    // -------------------------------------------------------------- helpers

    private static String outOfRange(String what, int typed, int lo, int hi, String unit) {
        String u = unit.isEmpty() ? "" : " " + unit;
        return what + " must be between " + lo + " and " + hi + u
                + " - " + typed + u + " was not saved";
    }

    private Integer parsed(EditText e) {
        try {
            return Integer.valueOf(e.getText().toString().trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String versionName() {
        try {
            String v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return v == null ? "" : v;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return "";
        }
    }

    /**
     * Rebuild this screen from prefs WITHOUT the framework's view-state restore,
     * which would overwrite the switched-to profile's values with the old text.
     */
    private void relaunch() {
        finish();
        startActivity(new Intent(this, SettingsActivity.class));
        overridePendingTransition(0, 0);
    }

    /** What is known about THIS car, in plain words. */
    private String profileFacts() {
        StringBuilder sb = new StringBuilder();
        String vin = prefs.profileVin(shownProfile);
        sb.append(vin.isEmpty() ? "No VIN yet - it is read on the next connect." : "VIN " + vin);
        int series = prefs.learnedSeriesCount();
        if (series > 0) {
            sb.append("  ·  ").append(series).append(" cell groups");
            if (prefs.learnedZeroBased()) sb.append(" (numbered from 0)");
        }
        return sb.toString();
    }

    /** Another car on this phone: its name, with Switch and Delete in the open. */
    private View profileRow(final int id) {
        String n = prefs.profileName(id);
        final String shown = n.isEmpty() ? ("car " + id) : n;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView name = new TextView(this);
        name.setText(shown);
        name.setTextSize(14);
        name.setTextColor(Palette.TEXT);
        row.addView(name, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button sw = new Button(this);
        sw.setText("Switch");
        sw.setOnClickListener(v -> {
            if (BmsService.isActive()) {
                Toast.makeText(this, "Stop live reading first", Toast.LENGTH_SHORT).show();
                return;
            }
            prefs.setActiveProfile(id);
            relaunch();
        });
        row.addView(sw);
        // The first car cannot be deleted (see Prefs.deleteProfile), so do
        // not offer a button that only ever answers with a refusal.
        if (id == 1) return row;
        Button del = new Button(this);
        del.setText("Delete");
        del.setOnClickListener(v -> {
            if (BmsService.isActive()) {
                Toast.makeText(this, "Stop live reading first", Toast.LENGTH_SHORT).show();
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("Delete " + shown + "?")
                    .setMessage("Its calibration, battery map and alert settings are "
                            + "removed. Log files are not touched.")
                    .setPositiveButton("Delete", (d, w) -> {
                        if (BmsService.isActive()) {
                            Toast.makeText(this, "Stop live reading first",
                                    Toast.LENGTH_SHORT).show();
                        } else if (prefs.deleteProfile(id)) {
                            relaunch();
                        } else {
                            Toast.makeText(this, "The first car cannot be deleted",
                                    Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });
        row.addView(del);
        return row;
    }

    // ------------------------------------------------------------- widgets

    private TextView header(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Palette.OK);
        t.setTextSize(15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(14), 0, dp(4));
        return t;
    }

    private TextView note(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Palette.MUTED);
        t.setTextSize(11);
        int v = dp(6);
        t.setPadding(0, v, 0, v);
        return t;
    }

    private Switch toggle(String label, boolean on) {
        Switch s = new Switch(this);
        s.setText("  " + label);
        s.setTextColor(Palette.TEXT);
        s.setChecked(on);
        return s;
    }

    private EditText number(String value) {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setText(value);
        e.setTextColor(Palette.TEXT);
        return e;
    }

    private EditText decimal(String value) {
        EditText e = number(value);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        return e;
    }

    private View labelled(String label, View field) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(Palette.SOFT);
        t.setTextSize(12);
        box.addView(t);
        box.addView(field);
        return box;
    }

    private int dp(int v) {
        return Ui.dp(this, v);
    }
}

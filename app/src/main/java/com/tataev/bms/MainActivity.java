package com.tataev.bms;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The dashboard: one square block per parameter, three to a row.
 *
 * Every block is a live reading and carries the DID it came from, so nothing on
 * the grid is inferred. The one computed value - delta, cell max minus min - sits
 * in its own bar above the grid rather than posing as a measurement.
 *
 * Built with framework views only (no AndroidX, no Compose): the grid is
 * generated from BmsFields, so a remapped or missing parameter changes the screen
 * without a layout edit.
 */
public final class MainActivity extends Activity {

    private static final int REQ_PERMS = 1;

    // Palette. Colour is only ever used to mean something.
    private static final int BG = Palette.BG;
    private static final int TILE = Palette.TILE;
    private static final int TEXT = Palette.TEXT;
    private static final int MUTED = Palette.MUTED;
    private static final int FAINT = Palette.FAINT;
    private static final int OK = Palette.OK;
    private static final int WARN = Palette.WARN;
    private static final int BAD = Palette.BAD;

    private static final int COLUMNS = 3;
    /** Older than this and a reading is called out rather than shown as live. */
    private static final long STALE_MS = 5000L;
    /** A double-tap on Connect is otherwise connect-then-stop. */
    private static final long TAP_DEBOUNCE_MS = 800L;

    private Prefs prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView statusDot;
    private TextView statusView;
    private TextView bmsView;
    private TextView alertView;
    /** Calibration hint: amber, not red - nothing about the pack is wrong. */
    private TextView hintView;
    /**
     * A failure with its fix attached: an address field when no battery
     * controller was found, a Map button when it answered in unknown codes.
     * Shown only while the service is stopped on that failure.
     */
    private LinearLayout troubleBox;
    private BmsService.Trouble shownTrouble = BmsService.Trouble.NONE;
    private TextView deltaValue;
    private TextView deltaSub;
    private View deltaBar;
    private TextView logState;
    private TextView logDetail;
    private Switch recSwitch;
    /** True while refresh() writes widget state, so listeners do not fire back. */
    private boolean updatingUi;
    private TextView connectButton;
    private TextView shareButton;
    private TextView settingsButton;
    private ProgressBar spinner;
    private LinearLayout recRow;
    /** Last status announced by a toast, so one failure is not announced twice. */
    private String toastedStatus = "";
    private long lastToggleMs;
    /** Whether the pending permission request came from the user pressing something. */
    private boolean permissionAskWasUserGesture;
    /** Auto-connect is once per launch: a Stop must stay stopped when the user comes back. */
    private boolean autoConnectTried;

    private final List<String> tileKeys = new ArrayList<>();
    private final List<TextView> tileValues = new ArrayList<>();
    /** The fields behind the tiles, and the captions naming the DID each is read from. */
    private final List<BmsFields.Field> tileFields = new ArrayList<>();
    private final List<TextView> tileDids = new ArrayList<>();

    private final BroadcastReceiver updates = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String alert = intent.getStringExtra(BmsService.EXTRA_ALERT);
            if (alert != null) {
                Toast.makeText(MainActivity.this, alert, Toast.LENGTH_LONG).show();
            }
            refresh();
        }
    };

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refresh();
            ui.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = new Prefs(this);
        View root = buildUi();
        setContentView(root);
        // Android 15 draws the window behind the system bars, so without this the
        // top row sits underneath the status bar.
        SystemBars.pad(root);
        // Not a user gesture: see onRequestPermissionsResult.
        requestNeededPermissions(false);
    }

    /**
     * Hold the screen awake only while there is something to watch.
     *
     * This used to be set unconditionally in onCreate, so simply opening the app
     * to glance at the last reading pinned the display on until the user left the
     * screen - on a phone sitting in a car dock, indefinitely.
     */
    private void updateScreenOnFlag() {
        if (isLive()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter(BmsService.ACTION_UPDATE);
        // Signature permission on BOTH ends: RECEIVER_NOT_EXPORTED only exists
        // from API 34, and below it a plain receiver is public - any
        // co-installed app could inject spoofed alert broadcasts.
        if (Build.VERSION.SDK_INT >= 34) {
            registerReceiver(updates, f, BmsService.PERM_UPDATES, null,
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(updates, f, BmsService.PERM_UPDATES, null);
        }
        // Settings or Scan vehicle may have remapped a DID while we were away.
        refreshDidCaptions();
        ui.post(ticker);
        // Connect on open: a driver wants the app up and reading with no tap.
        // Only with permission in hand - the launch-time request pauses this
        // screen, and the resume after a grant lands here again - and never over
        // a running scan. Once per launch, so Stop stays stopped.
        if (!autoConnectTried && prefs.autoConnect() && hasBluetoothPermission()) {
            autoConnectTried = true;
            if (!isLive() && !ScanActivity.isScanning()) startMonitoring();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(updates);
        } catch (IllegalArgumentException ignored) {
        }
        ui.removeCallbacks(ticker);
    }

    // ------------------------------------------------------------------ UI

    private View buildUi() {
        int side = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        root.addView(buildHeader(side));

        // The grid plus the delta bar plus the footer overflow a small screen -
        // and the alert banner pushes even a mid-size phone over, which would
        // clip the footer exactly when a fault appears. So the middle scrolls
        // while the header and the controls stay pinned.
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(buildDeltaBar(side));

        alertView = new TextView(this);
        alertView.setTextSize(13);
        alertView.setTextColor(Palette.ALERT_TEXT);
        alertView.setLineSpacing(0, 1.2f);
        alertView.setPadding(dp(14), dp(12), dp(14), dp(12));
        alertView.setBackground(rounded(Palette.ALERT_BG, dp(8)));
        alertView.setVisibility(View.GONE);
        LinearLayout.LayoutParams alertLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        alertLp.setMargins(side, 0, side, dp(8));
        content.addView(alertView, alertLp);

        hintView = new TextView(this);
        hintView.setTextSize(12.5f);
        hintView.setTextColor(WARN);
        hintView.setLineSpacing(0, 1.2f);
        hintView.setPadding(dp(14), dp(10), dp(14), dp(10));
        hintView.setBackground(pressable(TILE, dp(8)));
        hintView.setVisibility(View.GONE);
        hintView.setOnClickListener(v -> offerZeroFix());
        Ui.buttonRole(hintView);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        hintLp.setMargins(side, 0, side, dp(8));
        content.addView(hintView, hintLp);

        troubleBox = new LinearLayout(this);
        troubleBox.setOrientation(LinearLayout.VERTICAL);
        troubleBox.setPadding(dp(14), dp(12), dp(14), dp(12));
        troubleBox.setBackground(rounded(TILE, dp(8)));
        troubleBox.setVisibility(View.GONE);
        // Its own params: LayoutParams are per-view state, and sharing one
        // object between two children lets a later change to one move the other.
        LinearLayout.LayoutParams troubleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        troubleLp.setMargins(side, 0, side, dp(8));
        content.addView(troubleBox, troubleLp);
        content.addView(buildGrid(side));

        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        // Let the last row clear the fade rather than sitting under it.
        scroller.setClipToPadding(false);
        scroller.setPadding(0, 0, 0, dp(8));
        scroller.addView(content);
        root.addView(scroller, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(buildFooter(side));
        return root;
    }

    private View buildHeader(int side) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(side, dp(18), side, dp(12));

        spinner = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        spinner.setIndeterminate(true);
        spinner.setVisibility(View.GONE);
        LinearLayout.LayoutParams spinLp =
                new LinearLayout.LayoutParams(dp(14), dp(14));
        spinLp.setMargins(0, 0, dp(8), 0);
        header.addView(spinner, spinLp);

        statusDot = new TextView(this);
        statusDot.setText("●");
        statusDot.setTextSize(9);
        statusDot.setTextColor(MUTED);
        header.addView(statusDot);

        statusView = new TextView(this);
        statusView.setTextSize(12);
        statusView.setTextColor(MUTED);
        statusView.setLetterSpacing(0.16f);
        statusView.setPadding(dp(8), 0, 0, 0);
        statusView.setText("NOT CONNECTED");
        header.addView(statusView);

        header.addView(new View(this), new LinearLayout.LayoutParams(0,
                1, 1f));

        bmsView = new TextView(this);
        bmsView.setTextSize(10);
        bmsView.setTextColor(FAINT);
        bmsView.setTypeface(Typeface.MONOSPACE);
        // A detected BMS line carries an address, a system name and a supplier.
        // Unbounded and unellipsized it pushed the status text - the thing that
        // says whether the pack is being read at all - off its own row.
        bmsView.setSingleLine(true);
        bmsView.setEllipsize(TextUtils.TruncateAt.END);
        bmsView.setMaxWidth(getResources().getDisplayMetrics().widthPixels / 2);
        header.addView(bmsView);
        return header;
    }

    /** Delta is computed, so it is presented apart from the measured grid. */
    private View buildDeltaBar(int side) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        // Tap-through to the live pack map, which was three taps away (Logs,
        // Live session, Open) from the one screen a driver actually has up.
        bar.setBackground(pressable(TILE, dp(8)));
        bar.setPadding(dp(16), dp(14), dp(16), dp(14));
        // No content description on the bar itself: it would override the DELTA
        // value, the leaders line and SOH for a screen reader, which reads the
        // children and offers to activate the bar on its own.
        bar.setOnClickListener(v -> startActivity(new Intent(this, PackMapActivity.class)));

        deltaBar = new View(this);
        deltaBar.setBackgroundColor(OK);
        LinearLayout.LayoutParams accentLp = new LinearLayout.LayoutParams(dp(3), dp(34));
        accentLp.setMargins(0, 0, dp(12), 0);
        bar.addView(deltaBar, accentLp);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);

        TextView label = new TextView(this);
        label.setText("DELTA");
        label.setTextSize(10);
        label.setTextColor(MUTED);
        label.setLetterSpacing(0.14f);
        text.addView(label);

        deltaValue = new TextView(this);
        deltaValue.setText("--  mV");
        deltaValue.setTextSize(28);
        deltaValue.setTextColor(MUTED);
        deltaValue.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        text.addView(deltaValue);

        // The running lock-on tally. The Alerter has always computed which group
        // holds the minimum and how much of the window it holds, but showed it
        // only once it crossed the alert threshold - so the one number this whole
        // app exists to find could not be watched as it formed.
        deltaSub = new TextView(this);
        deltaSub.setTextSize(10.5f);
        deltaSub.setTextColor(MUTED);
        deltaSub.setVisibility(View.GONE);
        text.addView(deltaSub);
        // Weighted, so the leaders line wraps instead of pushing the SOH block
        // off the right edge: "under load: group 77 (86%) · at rest: group 35
        // (63%)" is ~270 dp at this size and a 360 dp phone has ~330 to give.
        bar.addView(text, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // SOH lives here rather than in the grid.
        //
        // The right of this bar used to carry a "cell max minus min" caption,
        // which only restated the DELTA label beside it. State of health is the
        // other number that describes the pack as a whole rather than one
        // instant, so it belongs next to delta and not among the live
        // measurements - and it frees a square in the grid.
        //
        // It is still registered in the same four lists as a tile, so refresh()
        // fills it, clearValues() blanks it and refreshDidCaptions() keeps its
        // DID honest, with no special case anywhere.
        bar.addView(sohBlock());

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(side, 0, side, dp(8));
        LinearLayout wrap = new LinearLayout(this);
        wrap.addView(bar, lp);
        return wrap;
    }

    /** The field shown in the delta bar instead of in the grid. */
    private static final String BAR_FIELD_KEY = "soh_pct";

    /** SOH, right-aligned in the delta bar: label, value, and the DID it came from. */
    private View sohBlock() {
        BmsFields.Field f = null;
        for (BmsFields.Field x : BmsFields.primary()) {
            if (BAR_FIELD_KEY.equals(x.key)) {
                f = x;
                break;
            }
        }
        // Defensive: a remapped model could in principle drop the field. An empty
        // view keeps the bar's layout rather than crashing the dashboard.
        if (f == null) return new View(this);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.END);

        TextView label = new TextView(this);
        label.setText(f.label.toUpperCase(Locale.ROOT));
        label.setTextSize(10);
        label.setTextColor(MUTED);
        label.setLetterSpacing(0.14f);
        label.setGravity(Gravity.END);
        box.addView(label);

        TextView value = new TextView(this);
        value.setText("--");
        value.setTextSize(22);
        value.setTextColor(TEXT);
        value.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        value.setGravity(Gravity.END);
        box.addView(value);

        TextView did = new TextView(this);
        did.setText(BmsFields.effectiveDid(f, prefs));
        did.setTextSize(8.5f);
        did.setTextColor(FAINT);
        did.setTypeface(Typeface.MONOSPACE);
        did.setGravity(Gravity.END);
        box.addView(did);

        tileKeys.add(f.key);
        tileValues.add(value);
        tileFields.add(f);
        tileDids.add(did);
        return box;
    }

    /** Square blocks, COLUMNS to a row, one per measured parameter. */
    private View buildGrid(int side) {
        int gap = dp(8);
        int usable = getResources().getDisplayMetrics().widthPixels - side * 2;
        int size = (usable - gap * (COLUMNS - 1)) / COLUMNS;

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(side, 0, side, 0);

        // SOH is drawn in the delta bar, so it does not also take a square here.
        List<BmsFields.Field> fields = new ArrayList<>();
        for (BmsFields.Field f : BmsFields.primary()) {
            if (!BAR_FIELD_KEY.equals(f.key)) fields.add(f);
        }
        LinearLayout row = null;
        for (int i = 0; i < fields.size(); i++) {
            if (i % COLUMNS == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                rowLp.setMargins(0, i == 0 ? 0 : gap, 0, 0);
                grid.addView(row, rowLp);
            }
            LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(size, size);
            if (i % COLUMNS != 0) cellLp.setMargins(gap, 0, 0, 0);
            row.addView(tile(fields.get(i)), cellLp);
        }
        return grid;
    }

    /** One square block: label, live value, and the DID it was read from. */
    private View tile(BmsFields.Field f) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(rounded(TILE, dp(8)));
        box.setPadding(dp(11), dp(11), dp(11), dp(11));

        TextView label = new TextView(this);
        label.setText(f.label.toUpperCase(Locale.ROOT));
        label.setTextSize(9.5f);
        label.setTextColor(MUTED);
        label.setLetterSpacing(0.1f);
        box.addView(label);

        box.addView(new View(this), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView value = new TextView(this);
        value.setText("--");
        value.setTextSize(25);
        value.setTextColor(TEXT);
        value.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        box.addView(value);

        TextView did = new TextView(this);
        did.setText(BmsFields.effectiveDid(f, prefs));
        did.setTextSize(8.5f);
        did.setTextColor(FAINT);
        did.setTypeface(Typeface.MONOSPACE);
        box.addView(did);

        tileKeys.add(f.key);
        tileValues.add(value);
        tileFields.add(f);
        tileDids.add(did);
        return box;
    }

    /**
     * Re-read the DID captions.
     *
     * Scan vehicle can remap a field to a different DID, and this screen is
     * portrait-locked so it is never recreated - the captions were built once in
     * onCreate and then went on naming DIDs the values no longer came from, which
     * is exactly what the grid promises never to do.
     */
    private void refreshDidCaptions() {
        for (int i = 0; i < tileDids.size(); i++) {
            tileDids.get(i).setText(BmsFields.effectiveDid(tileFields.get(i), prefs));
        }
    }

    private View buildFooter(int side) {
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setPadding(side, 0, side, dp(20));

        LinearLayout rec = new LinearLayout(this);
        recRow = rec;
        rec.setOrientation(LinearLayout.HORIZONTAL);
        rec.setGravity(Gravity.CENTER_VERTICAL);
        rec.setBackground(pressable(TILE, dp(8)));
        rec.setPadding(dp(16), dp(12), dp(16), dp(12));
        rec.setOnClickListener(v -> {
            if (recSwitch.isEnabled()) recSwitch.toggle();
        });

        LinearLayout recText = new LinearLayout(this);
        recText.setOrientation(LinearLayout.VERTICAL);
        logState = new TextView(this);
        logState.setTextSize(14);
        logState.setTextColor(TEXT);
        logState.setText("Not recording");
        recText.addView(logState);
        logDetail = new TextView(this);
        logDetail.setTextSize(10);
        logDetail.setTextColor(MUTED);
        logDetail.setTypeface(Typeface.MONOSPACE);
        logDetail.setText("tap to start");
        recText.addView(logDetail);
        rec.addView(recText);

        rec.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));

        recSwitch = new Switch(this);
        recSwitch.setOnCheckedChangeListener((b, on) -> {
            if (updatingUi) return;      // refresh() setting state must not loop back
            prefs.setLoggingEnabled(on);
            Toast.makeText(MainActivity.this,
                    on ? "Recording" : "Recording stopped", Toast.LENGTH_SHORT).show();
            refresh();
        });
        rec.addView(recSwitch);
        footer.addView(rec, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams btnRow = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        btnRow.setMargins(0, dp(8), 0, 0);

        // Was "Share log", which could only ever reach the newest file, with
        // share-all hidden behind an undiscoverable long press. The list screen
        // shares any of them, and it is where the pack map opens from.
        shareButton = flatButton("Logs", Palette.SOFT,
                v -> startActivity(new Intent(this, LogsActivity.class)));
        shareButton.setContentDescription("Open the log list and pack map");
        buttons.addView(shareButton, buttonLp(0));
        settingsButton = flatButton("Settings", Palette.SOFT,
                v -> startActivity(new Intent(this, SettingsActivity.class)));
        buttons.addView(settingsButton, buttonLp(dp(8)));
        connectButton = flatButton("Connect", OK, v -> toggleService());
        buttons.addView(connectButton, buttonLp(dp(8)));
        footer.addView(buttons, btnRow);
        return footer;
    }

    private LinearLayout.LayoutParams buttonLp(int leftMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        lp.setMargins(leftMargin, 0, 0, 0);
        return lp;
    }

    private TextView flatButton(String text, int color, View.OnClickListener click) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(13);
        b.setTextColor(color);
        b.setGravity(Gravity.CENTER);
        // A flat TextView is invisible to both a finger and a screen reader:
        // nothing moved when it was pressed, and TalkBack announced it as static
        // text rather than something that can be activated.
        b.setBackground(pressable(TILE, dp(8)));
        Ui.buttonRole(b);
        b.setOnClickListener(click);
        return b;
    }

    private GradientDrawable rounded(int color, int radius) {
        return Ui.rounded(color, radius);
    }

    private Drawable pressable(int color, int radius) {
        return Ui.pressable(color, radius);
    }

    private int dp(int v) {
        return Ui.dp(this, v);
    }

    // -------------------------------------------------------------- updates

    /**
     * setText only when the text actually changed.
     *
     * refresh() runs at 2 Hz and writes about twenty TextViews. TextView.setText
     * does no equality check of its own - every call re-lays-out and re-draws the
     * view - so an idle dashboard was doing forty pointless layout passes a
     * second, which on a phone in a hot car is heat and battery for nothing. Most
     * of these strings change rarely or never.
     */
    private static void set(TextView v, CharSequence text) {
        if (v == null) return;
        CharSequence cur = v.getText();
        if (cur != null && cur.length() == text.length()
                && cur.toString().equals(text.toString())) {
            return;
        }
        v.setText(text);
    }

    private void refresh() {
        BmsService.State st = BmsService.state();
        Reading r = BmsService.latest();
        updateScreenOnFlag();

        String label = BmsService.statusText().toUpperCase(Locale.ROOT);
        int statusColor = st == BmsService.State.RUNNING ? OK
                : st == BmsService.State.ERROR ? BAD : MUTED;
        // Age the reading out loud. A poll that comes back completely empty is
        // tolerated for one interval before the link is torn down, and during
        // that window the grid went on painting the previous numbers as if they
        // were live - which for a pack monitor is the worst way to be wrong.
        if (r != null && st == BmsService.State.RUNNING) {
            // Monotonic, like every other timeout in the app: the reading's own
            // stamp is wall-clock for the CSV, and an NTP step against it showed
            // an hour of staleness on a perfectly live link.
            long ageMs = SystemClock.elapsedRealtime() - BmsService.latestElapsedMs();
            if (ageMs > STALE_MS) {
                label = label + "  ·  " + (ageMs / 1000) + "S AGO";
                statusColor = WARN;
            }
        }
        set(statusView, label);
        statusView.setTextColor(statusColor);
        statusDot.setTextColor(statusColor);
        set(bmsView, BmsService.bmsInfoText());

        // Three states, because "running" and "trying to connect" are different
        // things to a user: connecting shows progress and cancels, running stops.
        boolean live = isLive();
        boolean connecting = live && st != BmsService.State.RUNNING;
        if (!live) {
            set(connectButton, "Connect");
            connectButton.setTextColor(OK);
        } else if (connecting) {
            set(connectButton, "Cancel");
            connectButton.setTextColor(WARN);
        } else {
            set(connectButton, "Stop");
            connectButton.setTextColor(BAD);
        }
        spinner.setVisibility(connecting ? View.VISIBLE : View.GONE);
        statusDot.setVisibility(connecting ? View.GONE : View.VISIBLE);
        // Share and Settings would fight the connect attempt for a
        // single-connection adapter, so they wait until the link settles.
        setControlsEnabled(!connecting);
        // Recording is stricter: it only means anything while the BMS is actually
        // answering. Enabling it while disconnected just set a preference that
        // recorded nothing, which read as a broken switch.
        setRecordingEnabled(st == BmsService.State.RUNNING);

        // A failure the app can offer a fix for gets its fix right here, while
        // the service is stopped on it - not a toast pointing at a menu.
        BmsService.Trouble t = live ? BmsService.Trouble.NONE : BmsService.trouble();
        if (t != shownTrouble) {
            shownTrouble = t;
            showTrouble(t);
        }

        // Announce a failure once, when it has actually stopped trying.
        String status = BmsService.statusText();
        if (!live && st == BmsService.State.ERROR && !status.equals(toastedStatus)) {
            toastedStatus = status;
            Toast.makeText(this, status, Toast.LENGTH_LONG).show();
        } else if (live) {
            toastedStatus = "";
        }

        boolean logging = prefs.loggingEnabled();
        set(logState, logging ? "Recording" : "Not recording");
        // Show the cadence actually achieved, not the one that was asked for. The
        // adapter needs ~250 ms per batched round-trip, so a 1 s setting really
        // produces ~1.8 s samples once the unresolved DIDs are being logged too -
        // and nothing on screen used to say so.
        double period = BmsService.actualPeriodS();
        String rate = period > 0
                ? String.format(Locale.ROOT, "%.1f s/sample", period) : "";
        set(logDetail, logging
                ? (rate.isEmpty() ? "writing to CSV" : "writing to CSV  ·  " + rate)
                : st == BmsService.State.RUNNING
                        ? (rate.isEmpty() ? "tap to start" : "tap to start  ·  " + rate)
                        : "connect first");
        if (recSwitch.isChecked() != logging) {
            updatingUi = true;
            recSwitch.setChecked(logging);
            updatingUi = false;
        }

        if (r == null) {
            clearValues();
            return;
        }

        for (int i = 0; i < tileKeys.size(); i++) {
            String key = tileKeys.get(i);
            Double v = r.get(key);
            set(tileValues.get(i), v == null ? "--" : formatValue(key, v));
            // The weakest group's number, coloured by what the drive so far says
            // about that group - so a 77 on the tile reads as the suspect it is,
            // and a 35 as the low-charge floor it is.
            if ("cell_min_idx".equals(key)) {
                tileValues.get(i).setTextColor(v == null ? TEXT
                        : verdictColour(BmsService.packMap().stateOf((int) Math.round(v))));
            }
        }

        if (r.cellDeltaMv != null) {
            long d = Math.round(r.cellDeltaMv);
            // The limit in force, which follows this pack's own rest spread.
            int limit = BmsService.deltaLimitMv() > 0 ? BmsService.deltaLimitMv()
                    : prefs.deltaLimitMv();
            // Strictly greater, matching Alerter: the bar used to go red one
            // millivolt before the alert it is supposed to be previewing fired.
            int c = d > limit ? BAD : d >= limit * 0.6 ? WARN : OK;
            set(deltaValue, d + "  mV");
            deltaValue.setTextColor(c);
            deltaBar.setBackgroundColor(c);
        } else {
            set(deltaValue, "--  mV");
            deltaValue.setTextColor(MUTED);
            deltaBar.setBackgroundColor(MUTED);
        }

        String weakest = BmsService.weakestText();
        set(deltaSub, weakest);
        deltaSub.setVisibility(weakest.isEmpty() ? View.GONE : View.VISIBLE);

        String reason = BmsService.lastAlert();
        boolean alerting = reason != null && !reason.isEmpty();
        alertView.setVisibility(alerting ? View.VISIBLE : View.GONE);
        if (alerting) set(alertView, reason);

        String hint = BmsService.calibrationHint();
        hintView.setVisibility(hint.isEmpty() ? View.GONE : View.VISIBLE);
        if (!hint.isEmpty()) set(hintView, hint);
    }

    /** The pack map's colour for a verdict, or plain text when it has none. */
    private static int verdictColour(PackMap.State st) {
        switch (st) {
            case SUSPECT: return BAD;
            case WATCH: return WARN;
            case BALANCE: return Palette.BALANCE;
            default: return TEXT;
        }
    }

    /** Indices and millivolts are whole numbers; percentages and volts get a decimal. */
    private String formatValue(String key, double v) {
        boolean whole = key.endsWith("_idx") || key.endsWith("_mv") || key.startsWith("temp");
        return whole ? String.valueOf(Math.round(v))
                : String.format(Locale.ROOT, "%.1f", v);
    }

    private void clearValues() {
        for (TextView v : tileValues) {
            set(v, "--");
            v.setTextColor(TEXT);
        }
        set(deltaValue, "--  mV");
        deltaValue.setTextColor(MUTED);
        deltaBar.setBackgroundColor(MUTED);
        deltaSub.setVisibility(View.GONE);
        alertView.setVisibility(View.GONE);
        hintView.setVisibility(View.GONE);
    }



    private void toggleService() {
        // A double-tap used to read as connect-then-stop, which looks exactly
        // like the app ignoring the button.
        long now = SystemClock.elapsedRealtime();
        if (now - lastToggleMs < TAP_DEBOUNCE_MS) return;
        lastToggleMs = now;

        if (isLive()) {
            stopService(new Intent(this, BmsService.class));
        } else {
            if (!hasBluetoothPermission()) {
                requestNeededPermissions(true);
                return;
            }
            startMonitoring();
        }
        ui.postDelayed(this::refresh, 300);
    }

    /**
     * Start the foreground service. Permission is the caller's to check.
     *
     * With two or more OBD-looking adapters paired and none chosen, ask which -
     * once, here, where the choice matters - rather than silently taking the
     * first or offering a name field in Settings nobody would find.
     */
    private void startMonitoring() {
        if (prefs.adapterName().isEmpty()) {
            BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter ba = bm == null ? BluetoothAdapter.getDefaultAdapter() : bm.getAdapter();
            final List<String> names = ElmClient.obdCandidates(ba);
            if (names.size() >= 2) {
                new AlertDialog.Builder(this)
                        .setTitle("Which adapter is in the car?")
                        .setItems(names.toArray(new String[0]), (d, which) -> {
                            prefs.setAdapterName(names.get(which));
                            launchService();
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
                return;
            }
        }
        launchService();
    }

    private void launchService() {
        Intent i = new Intent(this, BmsService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
        ui.postDelayed(this::refresh, 300);
    }

    /**
     * The wrong-zero hint's fix, on the hint itself. A human confirms, because a
     * parked car running its air conditioning hard can look the same to the
     * detector - and the value affects every reading.
     */
    private void offerZeroFix() {
        Reading r = BmsService.latest();
        final Integer raw = r == null ? null : CurrentCalibration.zeroFromParked(r.raw.get("current_a"));
        if (raw == null) {
            Toast.makeText(this, "No current reading right now", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Fix the current zero point?")
                .setMessage("Only if the car is parked, switched on, unplugged and not "
                        + "running the air conditioning hard. The current reading right now "
                        + "becomes zero amps for this car. It can be changed later under "
                        + "Settings, Expert.")
                .setPositiveButton("Fix now", (d, w) -> {
                    prefs.setCurrentZero(raw);
                    BmsService.clearCalibrationHint();
                    Toast.makeText(this, "Zero point set for this car", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Not now", null)
                .show();
    }

    /** Build the failure card for one kind of trouble, or hide it. */
    private void showTrouble(BmsService.Trouble t) {
        troubleBox.removeAllViews();
        if (t == BmsService.Trouble.NONE) {
            troubleBox.setVisibility(View.GONE);
            return;
        }
        TextView text = new TextView(this);
        text.setTextSize(13);
        text.setTextColor(TEXT);
        text.setLineSpacing(0, 1.25f);
        troubleBox.addView(text);
        if (t == BmsService.Trouble.NO_BMS) {
            String extras = BmsService.discoveredEcus();
            text.setText("No battery controller answered at any address this app knows. "
                    + (extras.isEmpty() ? ""
                        : "ECUs did answer at: " + extras + " - one of them may be it. ")
                    + "If you know this car's address, enter it (three hex digits, like 785).");
            final EditText addr = new EditText(this);
            addr.setHint("e.g. 785");
            addr.setTextColor(TEXT);
            addr.setHintTextColor(MUTED);
            troubleBox.addView(addr);
            TextView go = flatButton("Try this address", OK, v -> {
                String id = addr.getText().toString().trim();
                if (!CommandGuard.isRequestId(id)) {
                    Toast.makeText(this, "Three hex digits up to 7F7, like 785",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                prefs.setBmsRequestId(id, true);
                startMonitoring();
            });
            troubleBox.addView(go, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));
            // What the car actually said, as a file - so an owner of an
            // unrecognised model can send facts instead of guesses.
            final String report = BmsService.detectReportPath();
            if (report != null) {
                TextView share = flatButton("Share detection report", OK, v -> {
                    android.net.Uri uri = FileSharing.uriFor(this,
                            new java.io.File(report));
                    if (uri == null) {
                        Toast.makeText(this, "Report file is gone",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("text/plain");
                    send.putExtra(Intent.EXTRA_STREAM, uri);
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(send, "Share detection report"));
                });
                LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
                sl.setMargins(0, dp(6), 0, 0);
                troubleBox.addView(share, sl);
            }
        } else {
            text.setText("This car's battery controller answers, but not with the codes this "
                    + "app knows. Mapping it reads every code the controller serves and "
                    + "suggests which is which. It takes a few minutes with the car awake.");
            TextView go = flatButton("Map this car", OK,
                    v -> startActivity(new Intent(this, ScanActivity.class)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
            lp.setMargins(0, dp(10), 0, 0);
            troubleBox.addView(go, lp);
        }
        troubleBox.setVisibility(View.VISIBLE);
    }

    /** Grey out and detach anything that must not be touched mid-connect. */
    private void setControlsEnabled(boolean enabled) {
        shareButton.setEnabled(enabled);
        settingsButton.setEnabled(enabled);
        int c = enabled ? Palette.SOFT : FAINT;
        shareButton.setTextColor(c);
        settingsButton.setTextColor(c);
    }

    /** Recording is only meaningful while the BMS is answering. */
    private void setRecordingEnabled(boolean enabled) {
        recSwitch.setEnabled(enabled);
        recRow.setClickable(enabled);
        logState.setTextColor(enabled ? TEXT : FAINT);
    }

    /**
     * Whether the service is running at all.
     *
     * Deliberately NOT a check on the status enum: State.ERROR is the normal
     * retry loop, and treating it as "not live" left the button offering to
     * connect with no way to stop the service or release its wake lock.
     */
    private boolean isLive() {
        return BmsService.isActive();
    }

    // ---------------------------------------------------------- permissions

    private boolean hasBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.BLUETOOTH)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** The one Bluetooth permission this app needs on the running platform. */
    private static String bluetoothPermission() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? Manifest.permission.BLUETOOTH_CONNECT
                : Manifest.permission.BLUETOOTH;
    }

    /**
     * @param fromUserGesture true when the user just asked for something that
     *        needs the permission. Only then may a refusal send them to Settings.
     */
    private void requestNeededPermissions(boolean fromUserGesture) {
        permissionAskWasUserGesture = fromUserGesture;
        List<String> want = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // BLUETOOTH_CONNECT only. BLUETOOTH_SCAN was requested and declared
            // for years without being needed: this app never discovers anything,
            // it reads getBondedDevices() and opens RFCOMM to a device the user
            // already paired. Asking for a scan permission on a screen about a
            // battery reader is an unexplained demand for something adjacent to
            // location.
            want.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            want.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        List<String> missing = new ArrayList<>();
        for (String p : want) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQ_PERMS);
        }
    }

    /**
     * Say something when Bluetooth permission is refused.
     *
     * After two refusals (or one "Don't allow" on older versions) the system
     * stops showing the dialog: requestPermissions() returns instantly and calls
     * straight back here with DENIED. With no callback at all, Connect became a
     * button that visibly did nothing, forever, with no hint that a permission
     * was the reason - so route the user to the one screen that can fix it.
     *
     * ONLY when the user asked for something, though. onCreate also requests, and
     * that request is the one that returns instantly once permission is
     * permanently denied - so this used to fire on every cold start and throw the
     * user straight out of the app into system Settings before they had touched
     * anything. Launching an app must never do that. A refusal on the launch
     * request is silent; the Connect button re-requests and explains itself.
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_PERMS || hasBluetoothPermission()) return;
        if (!permissionAskWasUserGesture) return;
        if (shouldShowRequestPermissionRationale(bluetoothPermission())) {
            Toast.makeText(this, "Bluetooth permission is needed to reach the "
                    + "OBD adapter", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "Bluetooth permission is blocked - enable it under "
                + "Permissions", Toast.LENGTH_LONG).show();
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null));
            startActivity(i);
        } catch (RuntimeException ignored) {
            // no settings activity to open; the toast has already said what to do
        }
    }
}

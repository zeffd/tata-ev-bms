package com.tataev.bms;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Owns the Bluetooth link and the polling loop, as a foreground service so a
 * drive keeps logging with the screen off.
 *
 * The activity is a thin observer: it reads {@link #latest()} and listens for
 * {@link #ACTION_UPDATE} broadcasts.
 */
public final class BmsService extends Service {

    private static final String TAG = "BmsService";
    private static final String CHANNEL = "bms";
    private static final int NOTIF_ID = 42;
    /** Service byte + 3x2 DID bytes = 7, the CAN single-frame limit. */
    private static final int BATCH_SIZE = 3;
    /** TesterPresent cadence; ECU S3 timers are typically ~5 s. */
    private static final long KEEPALIVE_MS = 2000L;
    /**
     * Two speeds. The six roles the pack map is built from change with every
     * sample and are read every cycle - two batched requests, about 0.4 s. SOC,
     * SOH, the temperatures, the 12 V rail and the logged extras move over
     * minutes yet cost three to six more requests, so they are read every this
     * many cycles and carried forward in between.
     */
    static final int SLOW_EVERY = 5;
    /**
     * Floor on the cycle time: the adapter's own pace. This used to be a
     * setting; it only ever set a minimum under a maximum the adapter fixes.
     */
    static final long MIN_CYCLE_MS = 250L;
    /**
     * Wake-lock lease, renewed on every successful poll.
     *
     * This used to be a single four-hour lock taken at startup. On a session
     * longer than that - an overnight charge log is a realistic use of this app -
     * the lock expired mid-drive and polling silently degraded to whatever the
     * doze scheduler allowed, while the screen still said "Connected". A short
     * lease that the poll loop renews expires within minutes of the loop actually
     * stopping and never expires while it is working.
     */
    private static final long WAKELOCK_LEASE_MS = 10 * 60 * 1000L;

    static final String ACTION_UPDATE = "com.tataev.bms.UPDATE";
    /**
     * Signature-level permission guarding ACTION_UPDATE on BOTH ends: below
     * API 34 a plain receiver is public, and a co-installed app could pop
     * spoofed alert toasts over the dashboard.
     */
    static final String PERM_UPDATES = "com.tataev.bms.permission.UPDATES";
    /**
     * The broadcast is a "look again" nudge, not a snapshot: the activity reads
     * the state and status straight off this class, which is also what its 500 ms
     * ticker does. A status extra used to ride along and was never read by
     * anyone - two sources of truth, one of them silently ignored.
     */
    static final String EXTRA_ALERT = "alert";

    /** Connection/poll state, published for the UI. */
    enum State { IDLE, CONNECTING, DETECTING, RUNNING, ERROR }

    private static volatile Reading latestReading;
    /**
     * When {@link #latestReading} arrived, on the monotonic clock. The reading's
     * own stamp is wall-clock (it goes into the CSV), and the dashboard's "N s
     * ago" used to subtract it from the wall clock - the one clock every other
     * timeout here was moved off, because an NTP step mid-drive would show an
     * hour of staleness on a live link.
     */
    private static volatile long latestElapsedMs;
    private static volatile State state = State.IDLE;
    private static volatile String statusText = "Not connected";
    private static volatile String bmsInfoText = "";
    private static volatile String lastAlert = "";
    /** Running tally of which group holds the minimum; "" until there is one. */
    private static volatile String weakestText = "";
    /**
     * A plain-words hint for the dashboard when the current calibration looks
     * wrong for this car, or "". Not an alert: nothing about the pack is wrong.
     */
    private static volatile String calibrationHint = "";
    /** Set from the dashboard's "Fix now"; the poll loop restarts the detector. */
    private static volatile boolean resetZeroCheck;
    /**
     * The delta limit in force - the configured floor or three times this pack's
     * own rest spread - so the dashboard colours the bar by the same number the
     * alert fires on. 0 until the first poll.
     */
    private static volatile int deltaLimitMv;

    /**
     * A failure the dashboard can offer a fix for, right where it is reported.
     * NO_BMS: nothing at any candidate address called itself a battery - the
     * user may know the address. UNMAPPED: the BMS answered but none of the
     * known codes decoded - this model needs mapping.
     */
    enum Trouble { NONE, NO_BMS, UNMAPPED }

    private static volatile Trouble trouble = Trouble.NONE;
    /**
     * What this session has learned about individual cell groups.
     *
     * Owned by the service rather than by the map screen, so the picture covers
     * the whole drive instead of only the minutes that screen happened to be
     * open. Static for the same reason the reading is: the activity is a thin
     * observer that may not exist.
     */
    private static volatile PackMap packMap = new PackMap();
    /** Which profile {@link #packMap} belongs to; 0 = no vehicle identified yet. */
    private static volatile int packMapProfile;
    /**
     * Achieved seconds per sample, or 0 before there are two samples.
     *
     * The poll interval is a floor the adapter often cannot meet: seven batched
     * round-trips at ~250 ms each is ~1.8 s, so a 1 s setting silently produced a
     * 1.8 s cadence. Publishing what is actually happening is more honest than
     * letting the setting imply a rate.
     */
    private static volatile double actualPeriodS;
    /**
     * Set when the service stops itself for a stated reason rather than because
     * the user asked it to.
     *
     * stopSelf() runs onDestroy immediately afterwards, and onDestroy used to
     * overwrite the reason with "Stopped" before the UI could read it - so
     * "Bluetooth is off - turn it on and try again" reached the screen as a bare
     * STOPPED, with the notification carrying the real reason torn down along
     * with the service. The activity only toasts a failure while the state is
     * ERROR, so both the state and the text have to survive the teardown.
     */
    private static volatile boolean stoppedWithReason;

    static Reading latest() {
        return latestReading;
    }

    /** elapsedRealtime() at which {@link #latest()} arrived. */
    static long latestElapsedMs() {
        return latestElapsedMs;
    }

    static State state() {
        return state;
    }

    static String statusText() {
        return statusText;
    }

    static String bmsInfoText() {
        return bmsInfoText;
    }

    static String lastAlert() {
        return lastAlert;
    }

    static String weakestText() {
        return weakestText;
    }

    static String calibrationHint() {
        return calibrationHint;
    }

    /** The zero point was just fixed: drop the hint and start the detector over. */
    static void clearCalibrationHint() {
        calibrationHint = "";
        resetZeroCheck = true;
    }

    static int deltaLimitMv() {
        return deltaLimitMv;
    }

    static Trouble trouble() {
        return trouble;
    }

    /** ECUs the broadcast probe heard that were not identified; "" when none. */
    private static volatile String discoveredEcus = "";
    /** Path of the last saved detection report, or null. */
    private static volatile String detectReportPath;

    static String discoveredEcus() {
        return discoveredEcus;
    }

    static String detectReportPath() {
        return detectReportPath;
    }

    static double actualPeriodS() {
        return actualPeriodS;
    }

    static PackMap packMap() {
        return packMap;
    }

    /**
     * True whenever the worker thread exists, regardless of status.
     *
     * State.ERROR does NOT mean stopped - the loop is still reconnecting every
     * 5 s holding a wake lock - so anything asking "is the adapter in use?" or
     * "should the button say Disconnect?" must consult this, not the enum.
     */
    static boolean isActive() {
        return activeInstances.get() > 0;
    }

    /**
     * Held while either the poll loop or a DID sweep claims the adapter.
     *
     * These clones carry one conversation at a time. Each side checked the other
     * before starting, but a check and a claim in separate steps is a race: both
     * checks can pass at once and two things then open the same socket. Claiming
     * under one monitor makes the check-and-start atomic.
     */
    static final Object ADAPTER = new Object();

    /** Atomic: onCreate and onDestroy are a read-modify-write on this. */
    private static final java.util.concurrent.atomic.AtomicInteger activeInstances =
            new java.util.concurrent.atomic.AtomicInteger();

    private Thread worker;
    private volatile boolean running;
    /**
     * Set the moment teardown begins, and never cleared.
     *
     * BluetoothSocket.connect() is uninterruptible and blocks for 5-20 s when the
     * dongle is off - exactly when someone presses Cancel. onDestroy gives up
     * waiting after 1.5 s, so the worker outlives the service and then reports
     * the failure of a connection the user already cancelled: the dashboard
     * flipped to red with a toast, and an ONGOING notification was re-posted for
     * a service that no longer existed, which before Android 14 the user cannot
     * even swipe away. Every publishing path checks this first.
     */
    private volatile boolean destroyed;
    /** Held so {@link #onDestroy} can unblock a connect() that cannot be interrupted. */
    private volatile ElmClient elm;
    private Prefs prefs;
    private CsvLogger csv;
    private final Alerter alerter =
            new Alerter(new ToneBeeper(this), SystemClock::elapsedRealtime);
    /** Notices a current zero point that is wrong for this car. */
    private final ZeroCheck zeroCheck = new ZeroCheck();
    /** The last verdict announced, kept in the notification for a few minutes. */
    private String lastVerdict = "";
    private long lastVerdictMs;
    private static final long VERDICT_SHOWN_MS = 5 * 60 * 1000L;
    private PowerManager.WakeLock wakeLock;
    /** Consecutive polls where nothing at all came back. */
    private int emptyPolls;
    /** Consecutive polls the ECU answered without producing a single known value. */
    private int unmappedPolls;
    /** Cleared once a saved BMS address proves itself; drives re-detection. */
    private int badAddressStreak;
    /** Multi-DID reads are assumed to work until this ECU proves otherwise. */
    private boolean batchingWorks = true;
    private int batchFailures;
    private long lastKeepAliveMs;
    /** Previous successful poll, for the achieved-cadence readout. */
    private long lastSampleMs;
    /** Cycles since connect; every SLOW_EVERY-th (the first included) is a slow cycle. */
    private int pollCount;
    /** The slow roles as last measured, carried into fast-cycle readings. */
    private final Map<String, Double> slowValues = new HashMap<>();
    private final Map<String, String> slowRaw = new HashMap<>();
    /**
     * Two-speed polling is for a model that serves the six pack-map roles. One
     * that does not - a Tata EV whose SOC DID matches the Nexon's but whose cell
     * DIDs do not - would otherwise see every fast cycle come back empty and be
     * read as a dead link: "No response from BMS" and a reconnect every two
     * seconds, forever - or, where pack volts and current DO decode, hit the
     * unmapped-model path from fast cycles and stop with a wrong message. Only a
     * full cycle may judge the model; {@link PollPlan#judgeFast} decides what a
     * fast cycle that carries nothing usable means, from whether the cell roles
     * have ever decoded on this link.
     */
    private boolean singleSpeed;
    private boolean cellsDecoded;
    /** Notification throttle: the text is glanceable, not a live readout. */
    private static final long NOTIFY_MIN_INTERVAL_MS = 5000L;
    private long lastNotifyMs;
    private String lastNotifyText = "";
    /** Consecutive failed connects; bounded so the service cannot spin forever. */
    private int consecutiveConnectFailures;
    /** Kept so the give-up message can say WHY, not just that it gave up. */
    private String lastFailureReason = "no response from the adapter";
    private static final int MAX_CONNECT_FAILURES = 12;   // ~1 minute of retries
    /** Detection sweeps in a row where no address answered at all. */
    private int silentSweeps;

    @Override
    public void onCreate() {
        super.onCreate();
        activeInstances.incrementAndGet();
        // A fresh start clears the last run's failure, so the old reason cannot
        // linger on screen while this attempt is still connecting.
        stoppedWithReason = false;
        weakestText = "";
        calibrationHint = "";
        trouble = Trouble.NONE;
        deltaLimitMv = 0;
        actualPeriodS = 0;
        packMap = new PackMap();
        packMapProfile = 0;   // a new session starts empty even for the same car
        alerter.forgetVerdicts();
        setState(State.CONNECTING, "Starting...");
        prefs = new Prefs(this);
        csv = new CsvLogger(this);
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForeground(NOTIF_ID, buildNotification("Starting..."));
        } catch (RuntimeException e) {
            // A connectedDevice foreground service requires BLUETOOTH_CONNECT.
            // START_STICKY means the system restarts us after a process death, so
            // if the user revoked that permission in between, this throws on every
            // restart - an uncaught throw here is a crash loop, not a one-off.
            Log.e(TAG, "cannot start in foreground", e);
            fatal("Bluetooth permission denied - grant it in app settings");
            return START_NOT_STICKY;
        }
        // The mirror of ScanActivity's own check. These clones carry one
        // conversation at a time, and a sweep can run for tens of minutes; without
        // this, Connect quietly fought a running scan for the same socket and both
        // ended up with garbage.
        synchronized (ADAPTER) {
            if (ScanActivity.isScanning()) {
                fatal("A vehicle scan is running - stop it first");
                return START_NOT_STICKY;
            }
            if (worker == null || !worker.isAlive()) {
                running = true;
                worker = new Thread(this::runLoop, "bms-poll");
                worker.start();
            }
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        running = false;
        // Close the socket FIRST. Thread.interrupt() does nothing to a blocking
        // BluetoothSocket.connect(); closing it from this thread is the only thing
        // that unblocks it, and without that the join below always times out and
        // leaves a worker running against a dead service.
        ElmClient live = elm;
        if (live != null) live.abort();
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        csv.stop();
        alerter.release();
        releaseWakeLock();
        // A service that never reached the foreground (startForeground threw)
        // leaves its ongoing notification behind - the system only clears the
        // foreground one. In every other case this is a no-op.
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(NOTIF_ID);
        // Keep a stated reason (Bluetooth off, nothing paired, gave up retrying);
        // only a user-requested stop becomes a plain "Stopped".
        if (!stoppedWithReason) {
            state = State.IDLE;
            statusText = "Stopped";
        }
        latestReading = null;     // don't leave stale cell voltages on screen
        lastAlert = "";
        bmsInfoText = "";
        weakestText = "";
        calibrationHint = "";
        actualPeriodS = 0;
        broadcast(null);          // bypasses the destroyed guard on purpose
        activeInstances.updateAndGet(v -> Math.max(0, v - 1));
        super.onDestroy();
    }

    /**
     * The single writer for the published state.
     *
     * Silently drops writes once teardown has begun, so a worker still unwinding
     * cannot repaint the screen for a session that is over.
     */
    private void setState(State s, String text) {
        if (destroyed) return;
        state = s;
        statusText = text;
    }

    // ---------------------------------------------------------------- worker

    private void runLoop() {
        acquireWakeLock();
        ElmClient client = new ElmClient();
        elm = client;
        try {
            while (running && !Thread.currentThread().isInterrupted()) {
                long started = SystemClock.elapsedRealtime();
                try {
                    if (!client.isConnected()) {
                        if (!establish(client)) {
                            if (!running) return;      // a fatal failure stopped us
                            if (++consecutiveConnectFailures >= MAX_CONNECT_FAILURES) {
                                // Give up rather than retry for the whole lease
                                // after the dongle is unplugged or Bluetooth is
                                // switched off.
                                setState(State.ERROR, "Could not connect - "
                                        + withoutRetrySuffix(lastFailureReason));
                                stoppedWithReason = true;   // survives onDestroy
                                publish(null);
                                updateNotificationText(statusText);
                                stopSelf();
                                return;
                            }
                            // The lease is otherwise renewed only by a successful
                            // poll, and twelve retries can outlive it.
                            refreshWakeLock();
                            sleep(5000);       // back off before retrying
                            continue;
                        }
                        consecutiveConnectFailures = 0;
                    }
                    pollOnce(client);
                    holdSession(client);
                } catch (RuntimeException e) {
                    // A malformed frame must never kill this thread: the service
                    // would stay alive as a zombie, frozen on the last reading.
                    Log.e(TAG, "poll error", e);
                    client.close();
                    fail("Recovered from error - reconnecting");
                }
                // The floor is the CADENCE, not a gap tacked onto however long
                // the round-trips took; a cycle that took longer waits nothing.
                long spent = SystemClock.elapsedRealtime() - started;
                idle(client, MIN_CYCLE_MS - spent);
            }
        } finally {
            client.close();
            elm = null;
            csv.stop();
            releaseWakeLock();
        }
    }

    /**
     * Wait out the poll interval without letting the ECU's S3 timer expire.
     *
     * TesterPresent used to be sent only between polls, so at the 10 s interval
     * Settings allows the bus went quiet for far longer than a typical 5 s S3
     * timer. The extended session is opened once, in establish(), and never
     * re-opened - so once it lapsed, every extended-session-only DID would read
     * blank for the rest of the drive with nothing on screen to say why.
     */
    private void idle(ElmClient client, long ms) {
        long remaining = ms;
        while (remaining > 0 && running && !Thread.currentThread().isInterrupted()) {
            long chunk = Math.min(remaining, KEEPALIVE_MS);
            sleep(chunk);
            remaining -= chunk;
            if (remaining > 0) holdSession(client);
        }
    }

    /**
     * Keep the extended session alive. The ECU drops back to the default session
     * after a few seconds of silence, which would silently blank every
     * extended-session-only DID for the rest of the drive.
     */
    private void holdSession(ElmClient client) {
        // pollOnce closes the client on link loss; a keep-alive into a closed
        // socket is one wasted round and a logged re-open failure per drop.
        if (!client.isConnected()) return;
        if (SystemClock.elapsedRealtime() - lastKeepAliveMs < KEEPALIVE_MS) return;
        if (!client.keepAlive()) {
            // The keep-alive did not land, so we can no longer claim the session
            // is held. Re-open it rather than assume: 1003 is a read-only session
            // request, it is cheap, and the alternative is extended-only DIDs
            // reading blank for the rest of the drive with nothing to explain it.
            // A refusal is not fatal - the default-session DIDs still work - so
            // the outcome only goes to the log.
            try {
                if (!client.enterExtendedSession()) {
                    Log.i(TAG, "extended session refused on re-open; "
                            + "default-session DIDs still read");
                }
            } catch (IOException e) {
                // A dead socket surfaces on the next poll, which handles it
                // properly by reconnecting. Nothing useful to do from here.
                Log.w(TAG, "session re-open failed: " + e.getMessage());
            }
        }
        lastKeepAliveMs = SystemClock.elapsedRealtime();
    }

    /** "... - retrying" reads badly nested inside "Could not connect - ...". */
    private static String withoutRetrySuffix(String s) {
        String[] tails = {" - retrying", " - reconnecting", " - will re-detect"};
        for (String tail : tails) {
            if (s.endsWith(tail)) return s.substring(0, s.length() - tail.length());
        }
        return s;
    }

    /** Connect, locate the BMS, and open the extended session. */
    private boolean establish(ElmClient client) {
        // A trouble from an earlier attempt must not sit under an unrelated
        // failure on this one; the card follows whatever THIS attempt hit.
        trouble = Trouble.NONE;
        try {
            BluetoothAdapter adapter = bluetoothAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                fatal("Bluetooth is off - turn it on and try again");
                return false;
            }
            BluetoothDevice dev;
            try {
                dev = ElmClient.findAdapter(adapter, prefs.adapterName());
            } catch (ElmClient.PermissionMissingException e) {
                fatal("Bluetooth permission denied - grant it in app settings");
                return false;
            }
            if (dev == null) {
                fatal("No paired OBD adapter - pair it in Android settings first");
                return false;
            }

            setState(State.CONNECTING, "Connecting to " + ElmClient.safeName(dev) + "...");
            publish(null);
            client.connect(dev);
            // connect() can block for 5-20 s; the user may well be gone by now.
            if (destroyed || !running) return false;

            String configured = prefs.bmsRequestId();
            String bmsAddress = configured;
            boolean pinned = configured != null && !configured.isEmpty();
            if (pinned) {
                client.initAndTargetBms(configured);
                // ATSH/ATCRA are adapter-local and always answer OK, so a saved
                // address must be confirmed against a real read or a stale one
                // loops forever without ever re-detecting.
                // Any UDS reply proves the address is right - even a negative
                // one. Requiring F197 to be SUPPORTED would reject a BMS that
                // serves data DIDs but not that identification DID.
                //
                // Ask for the DID this vehicle actually uses, not the Nexon
                // default. On an ECU that answers NRC 0x31 for an unsupported DID
                // the choice does not matter - a negative reply still proves the
                // address. On one that simply stays SILENT for a DID it does not
                // serve, asking for the hardcoded 3402 on a model remapped away
                // from it fails a perfectly good address; if it was auto-detected
                // it is then discarded and re-detection starts over, and if the
                // user typed it the connect retries forever. Exactly the remapped
                // vehicle Scan exists to support.
                boolean alive = client.respondsAtAll(BmsFields.DID_SYSTEM_NAME)
                        || client.respondsAtAll(
                                BmsFields.effectiveDid(BmsFields.ALL.get(0), prefs));
                if (alive) {
                    badAddressStreak = 0;
                    if (!destroyed) bmsInfoText = "BMS " + configured + " (from settings)";
                } else if (++badAddressStreak < 2) {
                    fail("BMS " + configured + " not answering - retrying");
                    client.close();
                    return false;
                } else {
                    // Two misses: look for the battery controller elsewhere on
                    // this link rather than retry one address for the whole
                    // budget. An auto-detected address is forgotten. One the
                    // user typed stays saved on this profile - it is the only
                    // way to pin an ECU detection cannot find - because the
                    // likeliest reason it fails is that a DIFFERENT car is
                    // plugged in today: detection finds that car's controller
                    // and identifyVehicle switches to its profile.
                    badAddressStreak = 0;
                    if (!prefs.bmsRequestIdIsUserEntered()) prefs.setBmsRequestId("");
                    pinned = false;
                }
            }
            if (!pinned) {
                setState(State.DETECTING, "Locating BMS...");
                publish(null);
                ElmClient.BmsInfo found = client.detectBms(
                        BmsFields.BMS_CANDIDATES,
                        msg -> {
                            setState(State.DETECTING, msg);
                            publish(null);
                        });
                if (found == null) {
                    // The candidate list failed - ask the WHOLE bus who is there
                    // before giving up. A model whose BMS lives at an address
                    // this app has never seen (the first Tiago EV field report)
                    // is found this way instead of dead-ending at a typed-address
                    // card the owner cannot fill in.
                    setState(State.DETECTING, "Asking every ECU on the bus...");
                    publish(null);
                    java.util.List<String> extra = client.discoverEcus();
                    extra.removeAll(BmsFields.BMS_CANDIDATES);
                    if (!extra.isEmpty()) {
                        found = client.sweepCandidates(extra, msg -> {
                            setState(State.DETECTING, msg);
                            publish(null);
                        });
                    }
                    discoveredEcus = (found != null || extra.isEmpty())
                            ? "" : String.join(", ", extra);
                }
                if (found == null) {
                    client.close();
                    boolean carSilent = client.lastDetectAnswered() == 0;
                    if (carSilent && ++silentSweeps < 2) {
                        // Nothing answered anywhere: an asleep or switched-off car
                        // looks exactly like this. One more sweep before giving
                        // up, with the reason the owner can act on.
                        fail("No answer from the car at any address - is it switched on? Retrying");
                        return false;
                    }
                    silentSweeps = 0;
                    // Deterministic on an awake car, so retrying only hides the
                    // fix: stop, and let the dashboard turn this into an address
                    // field - an owner of a model whose BMS neither names itself
                    // nor serves the Nexon's data DIDs may know where it lives.
                    trouble = Trouble.NO_BMS;
                    // Save what the car said, so the owner of an unrecognised
                    // model can send the developer facts instead of guesses.
                    detectReportPath = saveDetectReport(client.detectionLog());
                    fatal(carSilent
                            ? "No answer from the car at any address this app knows"
                            : "Could not find a battery controller on this vehicle");
                    return false;
                }
                silentSweeps = 0;
                // detectBms already ran initAdapter(); repeating it here would be a
                // second ATZ hardware reset that discards ATH1/ATSP6/ATCAF1.
                client.targetAndOpenSession(found.requestId);
                // NOT persisted here: identifyVehicle below may switch profiles,
                // and saving now would pin this car's address on the profile of
                // whatever car was active before. identifyVehicle stores it on
                // whichever profile ends up active - still only after it worked.
                bmsAddress = found.requestId;
                if (!destroyed) {
                    bmsInfoText = "BMS " + found.requestId + "/" + found.responseId
                            + "  " + found.systemName
                            + (found.supplier == null || found.supplier.isEmpty()
                            ? "" : " (" + found.supplier + ")");
                }
            }

            identifyVehicle(client, bmsAddress);

            // A new link may be a different vehicle or adapter, so give the
            // batching optimisation another chance rather than staying latched off.
            batchingWorks = true;
            batchFailures = 0;
            alerter.reset();          // a new link starts the alert windows over
            zeroCheck.reset();
            calibrationHint = "";
            weakestText = "";
            int profileNow = prefs.activeProfile();
            if (packMapProfile != profileNow) {
                // A different vehicle must not inherit a map - but a mid-drive
                // RECONNECT to the same car must not wipe hours of accumulated
                // per-group evidence either (one Bluetooth hiccup used to erase
                // every observation), and identifyVehicle has just settled which
                // case this is. Reset only when the vehicle changed.
                packMap = new PackMap();
                packMapProfile = profileNow;
                alerter.forgetVerdicts();     // a new map's verdicts are news again
                // Seed what this vehicle's profile learned on earlier drives, so
                // the grid and deviation baseline are right from the first
                // sample. Zero-based numbering is NOT seeded - see PackMap.seed.
                int learned = prefs.learnedSeriesCount();
                if (learned > 0) packMap.seed(learned);
            }
            lastSampleMs = 0;
            actualPeriodS = 0;
            // A new link starts on a slow cycle with nothing carried: the
            // dashboard fills at once, and nothing from another link survives.
            pollCount = 0;
            slowValues.clear();
            slowRaw.clear();
            singleSpeed = false;
            cellsDecoded = false;
            if (prefs.loggingEnabled()) {
                try {
                    csv.start();
                } catch (IOException e) {
                    // Storage full or unavailable. The pack is still readable, so
                    // losing the log must not be reported as a failed connection.
                    Log.w(TAG, "cannot open log file: " + e.getMessage());
                }
            }
            trouble = Trouble.NONE;       // whatever went wrong before, this link works
            discoveredEcus = "";
            detectReportPath = null;
            setState(State.RUNNING, "Connected");
            publish(null);
            return true;
        } catch (IOException e) {
            fail("Connect failed: " + e.getMessage());
            client.close();
            return false;
        } catch (RuntimeException e) {
            Log.e(TAG, "unexpected connect error", e);
            fail("Connect error: " + e.getMessage());
            client.close();
            return false;
        }
    }

    /**
     * Bind this connection to a vehicle profile, keyed by VIN.
     *
     * Read-only like everything else: at most two 0x22 identity reads, both to
     * the BMS already targeted - no other ECU is touched. When the BMS serves
     * no VIN, a fingerprint of address + supplier stands in; when neither is
     * readable the active profile simply stays, because churning profiles on a
     * bad link would scatter one car's calibration across several records.
     */
    private void identifyVehicle(ElmClient client, String bmsAddress) {
        String vin = ProfileMatch.extractVin(client.readIdString(BmsFields.DID_VIN));
        boolean byVin = !vin.isEmpty();
        int active = prefs.activeProfile();
        String id = byVin ? vin
                : ProfileMatch.fingerprint(bmsAddress,
                        client.readIdString(BmsFields.DID_SUPPLIER));
        boolean unresolved = false;
        if (!byVin && !prefs.profileVin(active).isEmpty()) {
            // The active profile is VIN-keyed but this connect read no VIN. The
            // same car with a flaky F190 read is far more likely than a
            // different VIN-less Tata, and both guesses are destructive: CREATE
            // would mint a junk profile on every hiccup, ADOPT would merge two
            // cars into one record. Treat it as unidentified - STAY, attaching
            // nothing.
            id = "";
            unresolved = true;
        }
        String activeId = byVin ? prefs.profileVin(active)
                : prefs.profileFingerprint(active);
        int match = byVin ? prefs.profileByVin(id) : prefs.profileByFingerprint(id);
        ProfileMatch.Action action = ProfileMatch.decide(id, activeId,
                match > 0 && match != active);
        switch (action) {
            case SWITCH:
                prefs.setActiveProfile(match);
                Log.i(TAG, "known vehicle - switched to profile " + match);
                break;
            case CREATE:
                int fresh = prefs.createProfile();
                prefs.setActiveProfile(fresh);
                Log.i(TAG, "new vehicle " + id + " - created profile " + fresh);
                break;
            case ADOPT:
            case STAY:
                break;
        }
        int now = prefs.activeProfile();
        if (byVin) prefs.setProfileVin(now, vin);
        else if (!id.isEmpty()) prefs.setProfileFingerprint(now, id);
        if (prefs.profileName(now).isEmpty()) {
            prefs.setProfileName(now, byVin
                    ? "Tata EV · " + vin.substring(vin.length() - 4)
                    : "Vehicle " + now);
        }
        // The address that just worked belongs on this profile - but never
        // overwrite one already stored (an address the user pinned on purpose
        // would silently unpin itself), and never write ANYTHING on the
        // unresolved path above: this may not be the profile's car at all.
        if (!unresolved && prefs.bmsRequestId().isEmpty()) {
            prefs.setBmsRequestId(bmsAddress);
        }
        // On the unresolved path the profile's name would assert an
        // identification that did not happen; label the session honestly.
        String pn = unresolved ? "profile unconfirmed" : prefs.profileName(now);
        if (!destroyed && !pn.isEmpty()) {
            bmsInfoText = bmsInfoText + "  ·  " + pn;
        }
    }

    /**
     * Remember what the map has derived, once enough samples have voted that
     * the numbers are the pack's rather than one noisy reading's.
     */
    private void persistLearned() {
        PackMap m = packMap;
        // Settled, not merely sampled: a provisional count taken under load used
        // to be stored, and then seeded the next drive's pinned baseline with it.
        if (!m.countSettled()) return;
        int series = m.seriesCount();
        if (series > 0 && series != prefs.learnedSeriesCount()) {
            prefs.setLearnedSeriesCount(series);
        }
        boolean zero = m.isZeroBased();
        if (zero != prefs.learnedZeroBased()) prefs.setLearnedZeroBased(zero);
    }

    /**
     * The detection transcript, saved as a shareable file: address by address,
     * who answered and what it called itself.
     */
    private String saveDetectReport(String log) {
        try {
            java.io.File dir = CsvLogger.logsDir(this);
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            String stamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss",
                    java.util.Locale.US).format(new java.util.Date());
            java.io.File f = new java.io.File(dir, "detect_" + stamp + ".txt");
            java.io.FileWriter w = new java.io.FileWriter(f);
            w.write("Tata EV BMS - detection report\n"
                    + "No battery controller was identified. Below is every address "
                    + "probed and what answered.\n\n");
            w.write(log == null ? "" : log);
            w.close();
            return f.getAbsolutePath();
        } catch (java.io.IOException e) {
            return null;
        }
    }

    private void pollOnce(ElmClient client) {
        Reading r = new Reading(System.currentTimeMillis());
        double scale = prefs.currentScale();
        int zero = prefs.currentZero();
        // On the reading, so the CSV's provenance columns say per row what each
        // row was decoded with; Settings can change these while the file is open.
        r.curScale = (float) scale;
        r.curZero = zero;
        // Read ONCE and use the same answer at both ends of the poll. Recording
        // can be toggled mid-poll, and reading it twice meant the top of the poll
        // could decide against the extras while the bottom decided to write the
        // row - so the first row of every log the user started mid-session had all
        // seven extra columns blank.
        final boolean logging = prefs.loggingEnabled();
        // Extras are only worth their round-trip time when they will be written
        // to CSV; they are never shown on the dashboard. When recording, every
        // one of them is read - on slow cycles, so they cost almost nothing.
        boolean wantExtras = logging;

        final boolean slowCycle = singleSpeed || pollCount % SLOW_EVERY == 0;
        pollCount++;
        List<BmsFields.Field> wanted = new ArrayList<>();
        for (BmsFields.Field f : BmsFields.ALL) {
            // A non-primary field exists only to be logged. Primary fields feed
            // the dashboard and are polled whether or not recording is on.
            if (!(f.primary || wantExtras)) continue;
            // The pack-map roles every cycle; everything else only on a slow one.
            if (slowCycle || PackMap.requiredKey(f.key)) wanted.add(f);
        }

        // Resolve every DID and its width ONCE per poll.
        //
        // These used to be looked up inline, which put SharedPreferences on the
        // hot path several hundred times a second: splitBatch asks for a width
        // per DID in every batched reply, and each of those walked all the fields
        // doing one getString each. Resolving up front also guarantees the DID we
        // ask for is the exact key we look the answer up under.
        final List<String> resolved = new ArrayList<>(wanted.size());
        final Map<String, Integer> widthByDid = new HashMap<>();
        for (BmsFields.Field f : wanted) {
            String did = BmsFields.effectiveDid(f, prefs).toUpperCase(Locale.ROOT);
            resolved.add(did);
            widthByDid.put(did, BmsFields.width(f.kind));
        }
        final UdsCodec.WidthLookup widths = did -> {
            Integer w = did == null ? null : widthByDid.get(did.toUpperCase(Locale.ROOT));
            return w == null ? -1 : w;
        };

        // Groups this poll where nothing came back at all. Two is the point where
        // the ECU is clearly mute rather than momentarily busy, and continuing
        // would burn a full read timeout per remaining group for nothing.
        int silentGroups = 0;
        // Batch 3 DIDs per request: ~3x fewer Bluetooth round-trips, which is what
        // lets the poll interval actually be met. Falls back per-DID if a batched
        // reply cannot be split with confidence.
        for (int i = 0; i < wanted.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, wanted.size());
            List<BmsFields.Field> group = wanted.subList(i, end);
            List<String> dids = resolved.subList(i, end);

            Map<String, byte[]> batch = null;
            boolean formRejected = false;
            boolean transportError = false;
            try {
                if (batchingWorks) {
                    ElmClient.BatchResult br = client.readDidBatch(dids, widths);
                    batch = br.values;
                    formRejected = br.formRejected;
                }
            } catch (ElmClient.SilentException e) {
                // The socket is fine, the ECU just did not answer. Tolerated here
                // and judged at the end of the poll: tearing the link down on one
                // quiet moment costs a reconnect, an ATZ and a re-detect, which is
                // far more disruptive than skipping a single sample.
                transportError = true;
                silentGroups++;
            } catch (IOException e) {
                // A real transport error - the socket is gone. Retrying the other
                // groups would only wait out their timeouts, so reconnect now.
                client.close();
                fail("Link lost - reconnecting");
                return;
            }
            if (transportError) {
                // Asking a mute ECU the same three DIDs one at a time only waits
                // out three more timeouts, so skip the fallback and let the
                // empty-poll check below decide whether the link is really gone.
                if (silentGroups >= 2) break;
                continue;
            }

            if (batchingWorks) {
                if (batch == null && !formRejected) {
                    // Anything transient - a clean NRC 0x31 meaning one of the
                    // three DIDs is absent, a busy ECU, a dropped consecutive
                    // frame. None of these say anything about multi-DID support.
                    batchFailures = 0;
                } else if (batch == null) {
                    // The batched FORM was refused: NRC 0x13 from the ECU, or a
                    // bare "?" from the adapter. Only THIS counts against
                    // batching; a link hiccup must not, or three bad frames would
                    // permanently triple the round-trips.
                    if (++batchFailures >= 3) {
                        batchingWorks = false;
                        Log.i(TAG, "multi-DID reads unsupported; using single reads");
                    }
                } else {
                    batchFailures = 0;
                }
            }

            if (batch != null) {
                for (int j = 0; j < group.size(); j++) {
                    BmsFields.Field f = group.get(j);
                    byte[] data = batch.get(dids.get(j));
                    if (data == null) continue;      // ECU omitted it: unsupported
                    r.raw.put(f.key, UdsCodec.toHex(data));
                    Double v = BmsFields.decode(f, data, scale, zero);
                    if (v != null) r.values.put(f.key, v);
                }
                continue;
            }

            // Fallback: ask one at a time.
            for (int j = 0; j < group.size(); j++) {
                BmsFields.Field f = group.get(j);
                try {
                    UdsCodec.Response resp = client.readDid(dids.get(j));
                    if (resp != null && resp.isData()) {
                        r.raw.put(f.key, UdsCodec.toHex(resp.data));
                        Double v = BmsFields.decode(f, resp.data, scale, zero);
                        if (v != null) r.values.put(f.key, v);
                    }
                } catch (IOException e) {
                    // readDid returns null on silence rather than throwing, so
                    // this is always a real transport error.
                    client.close();
                    fail("Link lost - reconnecting");
                    return;
                }
            }
        }

        if (r.values.containsKey("cell_min_mv")) {
            cellsDecoded = true;
            if (singleSpeed) {
                singleSpeed = false;
                Log.i(TAG, "cell roles answered; two-speed polling resumed");
            }
        }
        r.computeDerived();
        if (!slowCycle) {
            // A fast cycle never asked for SOC, so it is no judge of the model.
            switch (PollPlan.judgeFast(r.isUsable(), r.raw.isEmpty(), cellsDecoded)) {
                case DROP_TO_SINGLE:
                    singleSpeed = true;
                    Log.i(TAG, "cell roles never answered; polling every role each cycle");
                    return;
                case SKIP:
                    return;          // the cells answered before on this link: a transient
                default:
                    break;           // PROCEED, or EMPTY for the empty-poll rule below
            }
        }
        if (!r.isUsable()) {
            // The link is fine (the ECU answered the batch probes) but none of the
            // DIDs we know produced a value - most likely this model uses a
            // different map. Reconnecting would not help, so say what to do
            // instead of thrashing.
            if (r.raw.isEmpty()) {
                // Two in a row, not one: a single silent poll is a bus hiccup, and
                // this is the only debounce left now that a silent group no longer
                // tears the link down on the spot.
                emptyPolls++;
                if (emptyPolls >= 2) {
                    emptyPolls = 0;
                    client.close();
                    fail(silentGroups > 0 ? "Link lost - reconnecting"
                            : "No response from BMS - reconnecting");
                }
            } else {
                // Debounced like the empty case, and for a sharper reason: this
                // branch is the most disruptive outcome in the service. It stops
                // logging for the rest of the drive.
                //
                // Reaching it does NOT require an unknown DID map. It is enough
                // for the batches carrying soc_pct and cell_min_mv to come back
                // unsplittable and their single-read fallbacks to stay silent -
                // readDid() returns null on silence rather than throwing, so
                // silentGroups never counts them - while some later batch answers
                // and leaves r.raw non-empty. One unlucky poll on a busy bus
                // therefore used to end the session.
                //
                // A genuinely unmapped model fails this way on EVERY poll, so the
                // debounce costs it three polls and nothing else.
                unmappedPolls++;
                if (unmappedPolls >= 3) {
                    unmappedPolls = 0;
                    // STOP rather than keep polling. Retrying cannot teach us a
                    // DID map, and staying alive locked the user out of the very
                    // fix the message prescribes: the dashboard reads a
                    // live-but-not-RUNNING service as "connecting", which greys
                    // out the Settings button - the only route to Scan vehicle -
                    // while telling them to run it. Scan itself also refuses to
                    // start while this service is up.
                    trouble = Trouble.UNMAPPED;
                    fatal("The battery controller answers, but not with codes this app "
                            + "knows - this car needs mapping");
                }
            }
            return;
        }
        emptyPolls = 0;
        unmappedPolls = 0;

        // Carry the slow roles forward on a fast cycle - AFTER the usable check
        // above, so a carried SOC cannot make a poll whose fast batches all
        // failed look healthy. Marked as carried, so CsvLogger leaves them blank
        // rather than writing a minutes-old value as if it were measured now.
        if (slowCycle) {
            slowValues.clear();
            slowRaw.clear();
            for (Map.Entry<String, Double> e : r.values.entrySet()) {
                if (!PackMap.requiredKey(e.getKey())) slowValues.put(e.getKey(), e.getValue());
            }
            for (Map.Entry<String, String> e : r.raw.entrySet()) {
                if (!PackMap.requiredKey(e.getKey())) slowRaw.put(e.getKey(), e.getValue());
            }
        } else {
            for (Map.Entry<String, Double> e : slowValues.entrySet()) {
                if (!r.values.containsKey(e.getKey())) {
                    r.values.put(e.getKey(), e.getValue());
                    r.carried.add(e.getKey());
                }
            }
            for (Map.Entry<String, String> e : slowRaw.entrySet()) {
                if (!r.raw.containsKey(e.getKey())) {
                    r.raw.put(e.getKey(), e.getValue());
                    // Marked too: a slow DID that answers but does not decode
                    // has raw hex and no value, and its hex must not be written
                    // as fresh on four rows in five either.
                    r.carried.add(e.getKey());
                }
            }
        }
        refreshWakeLock();

        long nowMs = SystemClock.elapsedRealtime();
        if (lastSampleMs != 0) {
            double gap = (nowMs - lastSampleMs) / 1000.0;
            // Smoothed, or the readout jitters by a tenth of a second every poll.
            actualPeriodS = actualPeriodS == 0 ? gap : actualPeriodS * 0.8 + gap * 0.2;
        }
        lastSampleMs = nowMs;

        latestReading = r;
        latestElapsedMs = nowMs;
        setState(State.RUNNING, "Connected");

        // Logging can be toggled mid-drive; open or close the file to match.
        try {
            // !destroyed: a worker outliving onDestroy (a blocked read past the
            // join timeout) must not reopen a fresh file for one orphan row.
            if (logging && !destroyed) {
                csv.start();
                csv.write(r);
            } else if (csv.isOpen()) {
                csv.stop();
            }
        } catch (IOException e) {
            Log.w(TAG, "csv write failed: " + e.getMessage());
        }

        packMap.add(r);
        persistLearned();
        // Tens of amps from a stationary pack means the zero point is another
        // car's. Say so on the dashboard, with the fix one tap away on the hint
        // itself; once seen the hint stays until it is fixed or the link drops.
        if (resetZeroCheck) {
            zeroCheck.reset();
            resetZeroCheck = false;
        }
        if (calibrationHint.isEmpty() && zeroCheck.feed(nowMs, r.get("pack_v"),
                r.get("current_a"), r.get("soc_pct"))) {
            calibrationHint = String.format(Locale.ROOT,
                    "Reading %.0f A while the pack voltage and charge are steady, which a "
                            + "parked car cannot do: the current zero point looks wrong for "
                            + "this car. Tap to fix it now.",
                    Math.abs(r.get("current_a")));
        }
        // The knee test takes Ohm's law out with the pack's own per-group fit,
        // and the delta alert measures against this pack's own rest spread.
        alerter.setGroupMilliOhm(packMap.groupMilliOhm());
        alerter.setRestSpreadMv(packMap.restSpreadMv());
        deltaLimitMv = alerter.deltaLimitMv(prefs);
        String alert = alerter.evaluate(r, prefs);
        // Verdicts are announced once each, when the map first reaches them:
        // "group 77 is now a weak module" is news the first time and noise the
        // tenth. The 20 s re-arm belongs to conditions, not to this.
        String verdict = alerter.verdictAlert(packMap.notable(), prefs);
        if (verdict != null) {
            lastVerdict = verdict;
            lastVerdictMs = SystemClock.elapsedRealtime();
            alert = alert == null ? verdict : alert + "\n" + verdict;
        }
        // activeReason reflects the CURRENT state; lastAlert must follow it down
        // again, otherwise one transient spike leaves the banner red for good and
        // trains the driver to ignore it.
        lastAlert = alerter.activeReason() == null ? "" : alerter.activeReason();
        // Drive-long leaders by band, not the 40-sample raw tally: on a gently
        // driven pack that tally names the low-charge floor group, which the
        // pack map calls BALANCE, so the headline and the map disagreed.
        String leaders = packMap.leadersText();
        weakestText = leaders.isEmpty() ? alerter.weakestSummary() : leaders;
        publish(alert);
        updateNotification(r);
    }

    /**
     * A failure retrying cannot fix - Bluetooth off, nothing paired, permission
     * denied. Stops the service instead of burning the retry budget, so the UI
     * returns to "Connect" immediately with the reason on screen.
     */
    private void fatal(String message) {
        if (destroyed) return;
        latestReading = null;
        stoppedWithReason = true;      // must outlive onDestroy, which follows
        setState(State.ERROR, message);
        publish(null);
        updateNotificationText(message);
        running = false;
        stopSelf();
    }

    private void fail(String message) {
        if (destroyed) return;
        lastFailureReason = message;
        // Clear the reading on EVERY failure path. Leaving it lets the grid, the
        // spread readout and the alert banner keep painting minutes-old numbers
        // that still look live while the pack is not being monitored at all.
        latestReading = null;
        setState(State.ERROR, message);
        publish(null);
        updateNotificationText(message);
    }

    private void publish(String alert) {
        if (destroyed) return;
        broadcast(alert);
    }

    /** Unguarded, so {@link #onDestroy} can announce its own teardown. */
    private void broadcast(String alert) {
        Intent i = new Intent(ACTION_UPDATE);
        i.setPackage(getPackageName());
        if (alert != null) i.putExtra(EXTRA_ALERT, alert);
        sendBroadcast(i, PERM_UPDATES);
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private BluetoothAdapter bluetoothAdapter() {
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        return bm == null ? BluetoothAdapter.getDefaultAdapter() : bm.getAdapter();
    }

    // ------------------------------------------------------- wake / notify

    /**
     * All three wake-lock methods are synchronized on the same monitor.
     *
     * The worker's finally block and onDestroy both release, and the field was
     * neither volatile nor guarded: a null slipped in between the null check and
     * isHeld(), and the resulting NPE propagated out of the worker's finally,
     * where an uncaught exception on a background thread takes the whole process
     * down.
     */
    private synchronized void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tataev:bms");
            // Not reference counted, so renewing is just "extend the lease" rather
            // than stacking a new acquisition on every poll.
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(WAKELOCK_LEASE_MS);
        } catch (RuntimeException ignored) {
        }
    }

    private synchronized void refreshWakeLock() {
        if (wakeLock == null) return;
        try {
            wakeLock.acquire(WAKELOCK_LEASE_MS);
        } catch (RuntimeException ignored) {
        }
    }

    private synchronized void releaseWakeLock() {
        PowerManager.WakeLock wl = wakeLock;
        wakeLock = null;
        if (wl == null) return;
        try {
            if (wl.isHeld()) wl.release();
        } catch (RuntimeException ignored) {
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL, "BMS monitoring", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Keeps reading the battery while driving");
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, flags);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return b.setContentTitle("Tata EV BMS")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    /**
     * Refresh the ongoing notification from a reading.
     *
     * Throttled, unlike {@link #updateNotificationText}: this fires on every
     * successful poll, so at the default 1 s interval it was rebuilding a
     * PendingIntent and a Notification and making two binder calls 3600 times an
     * hour - on the service whose whole purpose is to be cheap in the background -
     * to move a reading by a tenth of a percent.
     */
    private void updateNotification(Reading r) {
        if (SystemClock.elapsedRealtime() - lastNotifyMs < NOTIFY_MIN_INTERVAL_MS) return;
        String delta = r.cellDeltaMv == null ? "--" : String.valueOf(Math.round(r.cellDeltaMv));
        String soc = r.fmt("soc_pct", 1);
        String text = "SOC " + soc + "%  delta " + delta + " mV"
                + (csv.isOpen() ? "  logging " + csv.rowCount() : "");
        // With the app backgrounded the notification is the only place an active
        // alert can be seen - the beep says something is wrong but never what.
        // The one number this app exists to find, where a glance can see it.
        String leaders = packMap.leadersText();
        if (!leaders.isEmpty()) text = text + "\n" + leaders;
        String reason = alerter.activeReason();
        if (reason != null && !reason.isEmpty()) text = text + "\n" + reason;
        // A verdict beeps once; the notification says what it was for a while.
        if (!lastVerdict.isEmpty()
                && SystemClock.elapsedRealtime() - lastVerdictMs < VERDICT_SHOWN_MS) {
            text = text + "\n" + lastVerdict;
        }
        updateNotificationText(text);
    }

    /** Posts immediately: status and failure messages must not be delayed. */
    private void updateNotificationText(String text) {
        if (destroyed) return;
        if (text.equals(lastNotifyText)) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.notify(NOTIF_ID, buildNotification(text));
        lastNotifyText = text;
        lastNotifyMs = SystemClock.elapsedRealtime();
    }
}

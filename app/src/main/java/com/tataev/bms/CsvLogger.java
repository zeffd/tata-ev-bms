package com.tataev.bms;

import android.content.Context;
import android.content.pm.PackageManager;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Writes readings to CSV in the app's own external files directory, so no
 * storage permission is needed and the files survive uninstall-free sharing
 * (Android/data/com.tataev.bms/files/logs).
 *
 * Columns mirror the desktop bmslog.py output: scaled values first, then the
 * derived diagnostics, then every raw hex payload so scalings can be revisited
 * later without re-driving.
 *
 * The file is SELF-DESCRIBING. Values are keyed by role, and a role can be
 * remapped to a different DID by Scan vehicle - so a log that recorded only the
 * role name could not be read back with confidence months later. Every raw column
 * therefore carries the DID it came from in its own name, and each row repeats
 * the app version, the BMS address, the current calibration and the VIN that
 * produced it. Repeating five fields per row is a few bytes against a log that
 * is useless if you cannot tell what produced it.
 */
final class CsvLogger {

    private final File dir;
    private final Prefs prefs;
    private final String appVersion;
    // Touched from the poll thread and from the main thread (service shutdown).
    private volatile FileWriter writer;
    private volatile long rows;
    /**
     * Frozen when the file is opened. The current calibration is NOT frozen
     * here any more: it rides on each Reading and is written per row, because
     * Settings can change it while the file is open.
     */
    private volatile String bmsId = "";
    private volatile String vin = "";
    /**
     * Every field, always. A per-field selector used to live in Settings; its
     * only purpose was a faster sample, and two-speed polling reads the extras
     * on every fifth cycle for almost nothing.
     */
    private static final List<BmsFields.Field> LOGGED = BmsFields.ALL;
    /**
     * Absolute path of the file currently being written by any logger in this
     * process, or null. Static so the Logs screen can refuse to delete the file
     * the service is appending to: deleting it unlinks the name while the open
     * FileWriter keeps flushing rows to the orphaned inode, and the drive ends
     * with nothing on disk and no warning.
     */
    private static volatile String openPath;

    static String openPath() {
        return openPath;
    }

    CsvLogger(Context ctx) {
        dir = logsDir(ctx);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        prefs = new Prefs(ctx);
        appVersion = versionOf(ctx);
    }

    private static String versionOf(Context ctx) {
        try {
            String v = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).versionName;
            return v == null ? "?" : v;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return "?";
        }
    }

    /**
     * The one definition of where logs live. Settings and the share action must
     * agree with this, including the fallback when external storage is absent.
     */
    static File logsDir(Context ctx) {
        File base = ctx.getExternalFilesDir(null);
        return new File(base == null ? ctx.getFilesDir() : base, "logs");
    }

    boolean isOpen() {
        return writer != null;
    }

    long rowCount() {
        return rows;
    }

    /** Start a new file. Safe to call when already open (no-op). */
    synchronized void start() throws IOException {
        if (writer != null) return;
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date());
        File f = new File(dir, "bms_" + stamp + ".csv");
        // Toggling logging twice inside one second would otherwise reopen the same
        // second-resolution filename and append a second header mid-file.
        int n = 1;
        while (f.exists()) {
            f = new File(dir, "bms_" + stamp + "_" + (n++) + ".csv");
        }
        bmsId = prefs.bmsRequestId();
        if (bmsId == null || bmsId.isEmpty()) bmsId = "auto";
        vin = prefs.profileVin(prefs.activeProfile());
        writer = new FileWriter(f, false);
        openPath = f.getAbsolutePath();
        rows = 0;
        try {
            writeHeader();
        } catch (IOException e) {
            // Leaving the writer in place would look "open" forever: start() would
            // return early on every later call and every row would be appended to
            // a file with no header line, making the whole drive log unreadable by
            // column name. Close it so the next poll genuinely retries.
            stop();
            throw e;
        }
    }

    private void writeHeader() throws IOException {
        writer.write(CsvFormat.header(LOGGED, prefs) + "\n");
        writer.flush();
    }

    /**
     * Reused across rows. Built once instead of per row: a drive is thousands of
     * rows and SimpleDateFormat is expensive to construct.
     *
     * SimpleDateFormat is NOT thread-safe, so this is only safe because every
     * caller comes through {@link #write}, which is synchronized on this logger.
     * Do not format with it from anywhere else.
     *
     * Full date, not just HH:mm:ss: an overnight charge log crosses midnight, and
     * the time column alone could not say which side of it a row was on.
     */
    private final SimpleDateFormat rowStamp =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    synchronized void write(Reading r) throws IOException {
        FileWriter w = writer;
        if (w == null) return;
        // The row itself is CsvFormat's, so the self-test can hold its promises
        // (carried roles blank, provenance block last) to the byte.
        w.write(CsvFormat.row(r, LOGGED, rowStamp.format(new Date(r.timestampMs)),
                appVersion, bmsId, vin) + "\n");
        w.flush();       // a drive can end with the phone being unplugged
        rows++;
    }

    synchronized void stop() {
        if (writer != null) {
            try {
                writer.flush();
                writer.close();
            } catch (IOException ignored) {
            }
            writer = null;
            openPath = null;
        }
    }
}

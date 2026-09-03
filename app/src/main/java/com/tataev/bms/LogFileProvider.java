package com.tataev.bms;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * A minimal FileProvider for handing log files to other apps.
 *
 * AndroidX's FileProvider would do this, but pulling in the support library for
 * one class would multiply the APK size, so this implements just the two things
 * a share target actually needs: openFile, and a query for display name and size.
 *
 * Only files inside the app's own logs directory are served, and only read-only.
 */
public final class LogFileProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        return true;
    }

    /** Resolve a content Uri to a file, refusing anything outside the logs dir. */
    private File resolve(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || name.contains("..") || name.contains("/")) {
            throw new FileNotFoundException("bad name: " + name);
        }
        android.content.Context ctx = getContext();
        if (ctx == null) throw new FileNotFoundException("provider has no context");
        // Must match CsvLogger exactly, including its internal-storage fallback,
        // or sharing breaks on a device with no external storage.
        File dir = CsvLogger.logsDir(ctx);
        File f = new File(dir, name);
        try {
            // Belt and braces: the resolved path must still be inside the logs dir.
            // The separator matters: a bare prefix also matches a sibling
            // directory called logsX. Not reachable through the names this
            // app generates, but containment checks should not rely on that.
            if (!f.getCanonicalPath().startsWith(
                    dir.getCanonicalPath() + File.separator)) {
                throw new FileNotFoundException("outside logs dir");
            }
        } catch (java.io.IOException e) {
            throw new FileNotFoundException("cannot resolve: " + name);
        }
        if (!f.exists()) throw new FileNotFoundException(name);
        return f;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode != null && !mode.equals("r")) {
            throw new FileNotFoundException("read-only provider");
        }
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        File f;
        try {
            f = resolve(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        // Honour the requested projection rather than always returning
        // {DISPLAY_NAME, SIZE}. Most receivers look columns up by name and so
        // survived the old behaviour, but a receiver that asks for two columns and
        // reads them positionally is entitled to get the two it asked for - and
        // one asking only for SIZE would have read the display name as a long.
        String[] cols = projection != null && projection.length > 0
                ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) {
                row[i] = f.getName();
            } else if (OpenableColumns.SIZE.equals(cols[i])) {
                row[i] = f.length();
            } else {
                row[i] = null;      // a column this provider does not carry
            }
        }
        MatrixCursor c = new MatrixCursor(cols, 1);
        c.addRow(row);
        return c;
    }

    @Override
    public String getType(Uri uri) {
        String n = uri.getLastPathSegment();
        if (n != null && n.endsWith(".csv")) return "text/csv";
        return "text/plain";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only");
    }
}

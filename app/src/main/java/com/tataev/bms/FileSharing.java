package com.tataev.bms;

import android.content.Context;
import android.net.Uri;

import java.io.File;

/**
 * Hands a log file to other apps.
 *
 * Files live in the app's external files dir, which is awkward to reach with a
 * file manager on Android 11+, so sharing is the practical way to get a CSV off
 * the phone. A FileProvider is used from Android 7 onwards because a raw
 * file:// Uri triggers FileUriExposedException. The provider is our own minimal
 * one ({@link LogFileProvider}) rather than AndroidX's, to keep the APK tiny.
 */
final class FileSharing {

    private FileSharing() { }

    static Uri uriFor(Context ctx, File file) {
        if (file == null || !file.exists()) return null;
        // No file:// fallback: minSdk is 24, which IS Build.VERSION_CODES.N, so
        // every device this APK installs on goes through the provider.
        return new Uri.Builder()
                .scheme("content")
                .authority(ctx.getPackageName() + ".files")
                .appendPath(file.getName())
                .build();
    }
}

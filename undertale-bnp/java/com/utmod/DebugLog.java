package com.utmod;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Streams this app's logcat into Download/UndertaleBnP/log_<time>.txt (falling back to the
 * app's external files dir), and appends the stack trace of any uncaught Java exception.
 */
public final class DebugLog {
    private static PrintWriter out;
    private static String where;

    public static synchronized void start(Context ctx) {
        if (out != null) return;
        try {
            if (ctx instanceof Activity && Build.VERSION.SDK_INT >= 23
                    && ctx.checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE") != PackageManager.PERMISSION_GRANTED) {
                ((Activity) ctx).requestPermissions(new String[] {"android.permission.WRITE_EXTERNAL_STORAGE"}, 7301);
            }
        } catch (Throwable ignored) {
        }
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String name = "log_" + stamp + ".txt";
        // Android 10+: MediaStore can add a file to Download/ without any permission.
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/UndertaleBnP");
                Uri uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                OutputStream os = ctx.getContentResolver().openOutputStream(uri);
                out = new PrintWriter(os, true);
                where = "Download/UndertaleBnP/" + name;
            } catch (Throwable e) {
                out = null;
            }
        }
        File[] dirs = {
            new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "UndertaleBnP"),
            ctx.getExternalFilesDir(null),
            ctx.getFilesDir(),
        };
        for (File dir : dirs) {
            if (out != null) break;
            try {
                if (dir == null) continue;
                dir.mkdirs();
                File f = new File(dir, name);
                out = new PrintWriter(new FileOutputStream(f), true);
                where = f.getAbsolutePath();
            } catch (Throwable ignored) {
                out = null;
            }
        }
        if (out == null) return;
        out.println("UNDERTALE BnP debug log " + stamp);
        out.println("device: " + Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + "), abi " + Build.SUPPORTED_ABIS[0]);
        out.println("log file: " + where);
        out.println();

        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                synchronized (DebugLog.class) {
                    out.println();
                    out.println("=== UNCAUGHT EXCEPTION on thread " + t.getName() + " ===");
                    e.printStackTrace(out);
                    out.flush();
                }
                try { Thread.sleep(500); } catch (InterruptedException ignored) { }
                if (previous != null) previous.uncaughtException(t, e);
            }
        });

        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Process p = Runtime.getRuntime().exec(new String[] {"logcat", "-v", "time"});
                    BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                    String line;
                    while ((line = r.readLine()) != null) {
                        synchronized (DebugLog.class) {
                            out.println(line);
                        }
                    }
                } catch (Throwable e) {
                    synchronized (DebugLog.class) {
                        out.println("logcat reader stopped: " + e);
                    }
                }
            }
        }, "DebugLog");
        reader.setDaemon(true);
        reader.start();
    }
}

package com.utmod;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
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
    private static File file;

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
        File[] dirs = {
            new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "UndertaleBnP"),
            ctx.getExternalFilesDir(null),
            ctx.getFilesDir(),
        };
        for (File dir : dirs) {
            try {
                if (dir == null) continue;
                dir.mkdirs();
                File f = new File(dir, "log_" + stamp + ".txt");
                out = new PrintWriter(new FileOutputStream(f), true);
                file = f;
                break;
            } catch (Throwable ignored) {
                out = null;
            }
        }
        if (out == null) return;
        out.println("UNDERTALE BnP debug log " + stamp);
        out.println("device: " + Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + "), abi " + Build.SUPPORTED_ABIS[0]);
        out.println("log file: " + file.getAbsolutePath());
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

package com.mango.alarm;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.util.Calendar;

/** Stores the alarm time and keeps a single daily AlarmManager alarm in sync with it. */
public final class AlarmScheduler {
    private static final String PREFS = "alarm";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_HOUR = "hour";
    private static final String KEY_MINUTE = "minute";

    private AlarmScheduler() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    public static int getHour(Context context) {
        return prefs(context).getInt(KEY_HOUR, 7);
    }

    public static int getMinute(Context context) {
        return prefs(context).getInt(KEY_MINUTE, 0);
    }

    public static void enable(Context context, int hour, int minute) {
        prefs(context).edit()
                .putBoolean(KEY_ENABLED, true)
                .putInt(KEY_HOUR, hour)
                .putInt(KEY_MINUTE, minute)
                .apply();
        scheduleNext(context);
    }

    public static void disable(Context context) {
        prefs(context).edit().putBoolean(KEY_ENABLED, false).apply();
        alarmManager(context).cancel(firePendingIntent(context));
    }

    /** Schedules the next occurrence of the saved time, if the alarm is on. */
    public static void scheduleNext(Context context) {
        if (!isEnabled(context)) return;
        AlarmManager am = alarmManager(context);
        long triggerAt = nextTrigger(getHour(context), getMinute(context));
        PendingIntent show = PendingIntent.getActivity(context, 1,
                new Intent(context, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        am.setAlarmClock(new AlarmManager.AlarmClockInfo(triggerAt, show), firePendingIntent(context));
    }

    public static long nextTrigger(int hour, int minute) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis()) {
            c.add(Calendar.DAY_OF_YEAR, 1);
        }
        return c.getTimeInMillis();
    }

    private static AlarmManager alarmManager(Context context) {
        return (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
    }

    private static PendingIntent firePendingIntent(Context context) {
        Intent intent = new Intent(context, AlarmReceiver.class);
        return PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}

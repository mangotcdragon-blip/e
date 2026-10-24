package com.mango.alarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Fired by AlarmManager at the alarm time. */
public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        AlarmService.start(context);
        // Daily alarm: queue up tomorrow's.
        AlarmScheduler.scheduleNext(context);
    }
}

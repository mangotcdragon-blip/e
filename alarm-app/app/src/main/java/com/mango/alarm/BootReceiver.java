package com.mango.alarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** AlarmManager forgets everything on reboot, update or clock change, so re-arm. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        AlarmScheduler.scheduleNext(context);
    }
}

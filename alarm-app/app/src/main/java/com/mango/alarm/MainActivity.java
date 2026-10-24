package com.mango.alarm;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.TimePicker;

import java.text.DateFormat;
import java.util.Date;

public class MainActivity extends Activity {
    private TimePicker picker;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(48, 96, 48, 48);

        status = new TextView(this);
        status.setTextSize(20);
        status.setTextColor(Color.WHITE);
        status.setGravity(Gravity.CENTER);
        root.addView(status);

        picker = new TimePicker(this);
        picker.setIs24HourView(android.text.format.DateFormat.is24HourFormat(this));
        picker.setHour(AlarmScheduler.getHour(this));
        picker.setMinute(AlarmScheduler.getMinute(this));
        root.addView(picker);

        root.addView(button("Set alarm", () -> {
            AlarmScheduler.enable(this, picker.getHour(), picker.getMinute());
            refresh();
        }));
        root.addView(button("Turn alarm off", () -> {
            AlarmScheduler.disable(this);
            refresh();
        }));
        root.addView(button("Test ring now", () -> AlarmService.start(this)));

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#121212"));
        scroll.addView(root);
        setContentView(scroll);

        requestPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private Button button(String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 16;
        b.setLayoutParams(lp);
        return b;
    }

    private void refresh() {
        if (AlarmScheduler.isEnabled(this)) {
            long next = AlarmScheduler.nextTrigger(AlarmScheduler.getHour(this), AlarmScheduler.getMinute(this));
            String when = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(next));
            status.setText("Alarm on, daily\nNext: " + when);
        } else {
            status.setText("Alarm off");
        }
    }

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        // Android 12 only; 13+ uses USE_EXACT_ALARM, which is granted automatically.
        if (Build.VERSION.SDK_INT == 31 || Build.VERSION.SDK_INT == 32) {
            AlarmManager am = getSystemService(AlarmManager.class);
            if (!am.canScheduleExactAlarms()) {
                startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:" + getPackageName())));
            }
        }

        // Needed for the challenge screen to pop up over the lock screen.
        if (Build.VERSION.SDK_INT >= 34) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (!nm.canUseFullScreenIntent()) {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                        Uri.parse("package:" + getPackageName())));
            }
        }
    }
}

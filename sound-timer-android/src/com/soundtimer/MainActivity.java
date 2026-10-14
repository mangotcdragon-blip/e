package com.soundtimer;

import android.app.Activity;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.math.BigDecimal;

public class MainActivity extends Activity {

    private SoundPool pool;
    private int soundId;
    private volatile boolean loaded;

    private EditText intervalInput;
    private EditText countInput;
    private Button startButton;
    private TextView status;

    private volatile Thread worker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        buildUi();
        loadSound();
    }

    private void loadSound() {
        pool = new SoundPool.Builder()
                .setMaxStreams(32)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .build();
        pool.setOnLoadCompleteListener((p, id, st) -> {
            loaded = st == 0;
            runOnUiThread(() -> {
                startButton.setEnabled(loaded);
                status.setText(loaded ? "Ready" : "Could not load sound");
            });
        });
        soundId = pool.load(this, R.raw.sound, 1);
    }

    private void buildUi() {
        int pad = dp(20);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Sound Timer");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        root.addView(label("Interval (seconds, e.g. 0.5 or 0.0001)"));
        intervalInput = new EditText(this);
        intervalInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        intervalInput.setText("1");
        intervalInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        root.addView(intervalInput);

        root.addView(label("How many times (0 = forever)"));
        countInput = new EditText(this);
        countInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        countInput.setText("10");
        countInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        root.addView(countInput);

        startButton = new Button(this);
        startButton.setText("Start");
        startButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        startButton.setEnabled(false);
        startButton.setOnClickListener(v -> {
            if (worker != null) stop();
            else start();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(24);
        root.addView(startButton, lp);

        status = new TextView(this);
        status.setText("Loading sound...");
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        status.setGravity(Gravity.CENTER_HORIZONTAL);
        status.setPadding(0, dp(16), 0, 0);
        root.addView(status);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setPadding(0, dp(20), 0, 0);
        return t;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void start() {
        long intervalNanos;
        long count;
        try {
            String s = intervalInput.getText().toString().trim().replace(',', '.');
            BigDecimal seconds = new BigDecimal(s);
            if (seconds.signum() < 0) throw new NumberFormatException();
            intervalNanos = seconds.movePointRight(9).longValue();
        } catch (Exception e) {
            status.setText("Interval must be a number like 0.5");
            return;
        }
        try {
            String c = countInput.getText().toString().trim();
            count = c.isEmpty() ? 0 : Long.parseLong(c);
            if (count < 0) throw new NumberFormatException();
        } catch (Exception e) {
            status.setText("Count must be a whole number");
            return;
        }

        final long interval = intervalNanos;
        final long total = count;
        startButton.setText("Stop");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        Thread t = new Thread(() -> run(interval, total), "sound-timer");
        t.setPriority(Thread.MAX_PRIORITY);
        worker = t;
        t.start();
    }

    private void stop() {
        Thread t = worker;
        worker = null;
        if (t != null) t.interrupt();
        finishedUi("Stopped");
    }

    // Each play is scheduled against the absolute start time, so small delays
    // never pile up into drift. Sleep most of the wait, then spin the last
    // couple of milliseconds to hit the target as closely as possible.
    private void run(long interval, long total) {
        Thread self = Thread.currentThread();
        long startTime = System.nanoTime();
        long played = 0;
        long lastUiUpdate = 0;
        try {
            while (worker == self && (total == 0 || played < total)) {
                long target = startTime + played * interval;
                long wait;
                while ((wait = target - System.nanoTime()) > 2_000_000L) {
                    Thread.sleep((wait - 1_500_000L) / 1_000_000L);
                }
                while (System.nanoTime() < target) {
                    if (worker != self) return;
                }
                pool.play(soundId, 1f, 1f, 1, 0, 1f);
                played++;

                long now = System.nanoTime();
                if (now - lastUiUpdate > 50_000_000L || played == total) {
                    lastUiUpdate = now;
                    final long p = played;
                    runOnUiThread(() -> {
                        if (worker == self) {
                            status.setText("Played " + p + (total == 0 ? "" : " / " + total));
                        }
                    });
                }
            }
        } catch (InterruptedException ignored) {
            return;
        }
        final long p = played;
        runOnUiThread(() -> {
            if (worker == self) {
                worker = null;
                finishedUi("Done - played " + p + " times");
            }
        });
    }

    private void finishedUi(String msg) {
        startButton.setText("Start");
        status.setText(msg);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    protected void onDestroy() {
        Thread t = worker;
        worker = null;
        if (t != null) t.interrupt();
        if (pool != null) pool.release();
        super.onDestroy();
    }
}

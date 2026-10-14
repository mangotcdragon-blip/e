package com.soundtimer;

import android.app.Activity;
import android.content.Context;
import android.media.AudioManager;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared plumbing for the Repeat and Countdown screens. */
abstract class TimerActivity extends Activity {

    protected float[] sound;
    protected int rate;
    protected TickPlayer player;

    protected Button startButton;
    protected TextView status;

    /** Adds this screen's inputs to {@code root}. */
    protected abstract void buildInputs(LinearLayout root);

    /** Reads the inputs and starts a player, or shows an error and returns null. */
    protected abstract TickPlayer createPlayer();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        LinearLayout root = Ui.column(this);
        buildInputs(root);

        startButton = Ui.bigButton(this, root, "Start");
        startButton.setEnabled(false);
        startButton.setOnClickListener(v -> {
            if (player != null) stop("Stopped");
            else start();
        });

        status = new TextView(this);
        status.setText("Loading sound...");
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        status.setGravity(Gravity.CENTER_HORIZONTAL);
        status.setPadding(0, Ui.dp(this, 16), 0, 0);
        root.addView(status);

        setContentView(Ui.scroll(this, root));

        rate = TickPlayer.outputRate((AudioManager) getSystemService(Context.AUDIO_SERVICE));
        new Thread(() -> {
            float[] s;
            try {
                s = SoundDecoder.load(getApplicationContext(), rate);
            } catch (Exception e) {
                s = null;
            }
            final float[] loaded = s;
            runOnUiThread(() -> {
                sound = loaded;
                startButton.setEnabled(loaded != null);
                status.setText(loaded != null ? "Ready" : "Could not load sound");
            });
        }).start();
    }

    private void start() {
        TickPlayer p = createPlayer();
        if (p == null) return;
        player = p;
        startButton.setText("Stop");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        onStarted();
        p.start();
    }

    protected void onStarted() {}

    protected void onStopped() {}

    /** Called on the UI thread when the run ends, by the user or by finishing. */
    protected void stop(String message) {
        if (player != null) {
            player.cancel();
            player = null;
        }
        startButton.setText("Start");
        status.setText(message);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        onStopped();
    }

    /** Wraps a listener so callbacks reach the UI thread only while that run is current. */
    protected TickPlayer.Listener uiListener(TickPlayer[] self, TickPlayer.Listener l) {
        return new TickPlayer.Listener() {
            @Override
            public void onTick(long played) {
                runOnUiThread(() -> {
                    if (player == self[0]) l.onTick(played);
                });
            }

            @Override
            public void onDone(long played) {
                runOnUiThread(() -> {
                    if (player == self[0]) l.onDone(played);
                });
            }
        };
    }

    @Override
    protected void onDestroy() {
        if (player != null) player.cancel();
        player = null;
        super.onDestroy();
    }
}

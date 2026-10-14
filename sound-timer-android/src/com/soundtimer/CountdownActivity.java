package com.soundtimer;

import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.math.BigDecimal;
import java.util.Locale;

/** Counts down a precise duration and plays the tick exactly when it hits zero. */
public class CountdownActivity extends TimerActivity {

    private EditText hoursInput;
    private EditText minutesInput;
    private EditText secondsInput;
    private TextView display;

    private long endNanos;
    private boolean ticking;

    private final Choreographer.FrameCallback frame = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!ticking) return;
            long left = endNanos - System.nanoTime();
            show(Math.max(0, left));
            if (left > 0) Choreographer.getInstance().postFrameCallback(this);
            else status.setText("Time!");
        }
    };

    @Override
    protected void buildInputs(LinearLayout root) {
        root.addView(Ui.title(this, "Countdown"));

        root.addView(Ui.label(this, "Hours"));
        hoursInput = Ui.wholeInput(this, "0");
        root.addView(hoursInput);

        root.addView(Ui.label(this, "Minutes"));
        minutesInput = Ui.wholeInput(this, "0");
        root.addView(minutesInput);

        root.addView(Ui.label(this, "Seconds (e.g. 5 or 2.3456)"));
        secondsInput = Ui.decimalInput(this, "5");
        root.addView(secondsInput);

        display = new TextView(this);
        display.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40);
        display.setTypeface(Typeface.MONOSPACE);
        display.setGravity(Gravity.CENTER_HORIZONTAL);
        display.setPadding(0, Ui.dp(this, 24), 0, 0);
        root.addView(display);
        show(0);
    }

    @Override
    protected TickPlayer createPlayer() {
        long nanos;
        try {
            BigDecimal total = Ui.parseDecimal(secondsInput)
                    .add(BigDecimal.valueOf(Ui.parseWhole(minutesInput) * 60))
                    .add(BigDecimal.valueOf(Ui.parseWhole(hoursInput) * 3600));
            nanos = total.movePointRight(9).longValueExact();
        } catch (Exception e) {
            status.setText("Enter numbers like 0, 1, 2.5");
            return null;
        }

        endNanos = System.nanoTime() + nanos;
        TickPlayer[] self = new TickPlayer[1];
        self[0] = new TickPlayer(sound, rate, endNanos, BigDecimal.ZERO, 1,
                uiListener(self, new TickPlayer.Listener() {
                    @Override
                    public void onTick(long played) {
                    }

                    @Override
                    public void onDone(long played) {
                        stop("Time!");
                    }
                }));
        status.setText("Counting down...");
        return self[0];
    }

    @Override
    protected void onStarted() {
        ticking = true;
        Choreographer.getInstance().postFrameCallback(frame);
    }

    @Override
    protected void onStopped() {
        ticking = false;
        Choreographer.getInstance().removeFrameCallback(frame);
    }

    private void show(long nanos) {
        long ms = nanos / 1_000_000L;
        display.setText(String.format(Locale.US, "%d:%02d:%02d.%03d",
                ms / 3_600_000L, (ms / 60_000L) % 60, (ms / 1000L) % 60, ms % 1000L));
    }
}

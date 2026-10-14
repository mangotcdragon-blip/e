package com.soundtimer;

import android.widget.EditText;
import android.widget.LinearLayout;

import java.math.BigDecimal;

/** Plays the tick N times (or forever), a fixed interval apart. */
public class RepeatActivity extends TimerActivity {

    private EditText intervalInput;
    private EditText countInput;

    @Override
    protected void buildInputs(LinearLayout root) {
        root.addView(Ui.title(this, "Repeat"));

        root.addView(Ui.label(this, "Interval (seconds, e.g. 0.5 or 0.0001)"));
        intervalInput = Ui.decimalInput(this, "1");
        root.addView(intervalInput);

        root.addView(Ui.label(this, "How many times (0 = forever)"));
        countInput = Ui.wholeInput(this, "10");
        root.addView(countInput);
    }

    @Override
    protected TickPlayer createPlayer() {
        BigDecimal interval;
        long count;
        try {
            interval = Ui.parseDecimal(intervalInput);
        } catch (Exception e) {
            status.setText("Interval must be a number like 0.5");
            return null;
        }
        try {
            count = Ui.parseWhole(countInput);
        } catch (Exception e) {
            status.setText("Count must be a whole number");
            return null;
        }
        if (count == 0 && interval.signum() == 0) {
            status.setText("An interval of 0 needs a count");
            return null;
        }

        final long total = count;
        TickPlayer[] self = new TickPlayer[1];
        self[0] = new TickPlayer(sound, rate, System.nanoTime(), interval, count,
                uiListener(self, new TickPlayer.Listener() {
                    @Override
                    public void onTick(long played) {
                        status.setText("Played " + played + (total == 0 ? "" : " / " + total));
                    }

                    @Override
                    public void onDone(long played) {
                        stop("Done - played " + played + " times");
                    }
                }));
        return self[0];
    }
}

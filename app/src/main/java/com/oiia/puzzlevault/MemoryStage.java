package com.oiia.puzzlevault;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Random;

/** Watch the pads flash, then repeat the sequence. */
class MemoryStage extends Stage {
    private static final int LENGTH = 6;
    private static final int[] COLORS = {0xFFFF5C7A, 0xFF3DDC97, 0xFF4DA3FF, 0xFFFFC857};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final View[] pads = new View[4];
    private int[] sequence;
    private int pos;
    private boolean accepting;
    private TextView status;
    private Button play;

    MemoryStage(Host host, Random rnd) {
        super(host, rnd);
        newSequence();
    }

    private void newSequence() {
        sequence = new int[LENGTH];
        for (int i = 0; i < LENGTH; i++) sequence[i] = rnd.nextInt(4);
    }

    @Override
    String title() {
        return "Echo Pads";
    }

    @Override
    String instructions() {
        return "Watch the " + LENGTH + " flashes, then tap the pads in the same order.";
    }

    @Override
    View build(Context c) {
        LinearLayout col = Ui.column(c);
        GridLayout grid = new GridLayout(c);
        grid.setColumnCount(2);
        int size = Ui.dp(c, 120), m = Ui.dp(c, 8);
        for (int i = 0; i < 4; i++) {
            View pad = new View(c);
            pad.setBackground(Ui.rounded(c, COLORS[i], 20));
            pad.setAlpha(0.3f);
            final int idx = i;
            pad.setOnClickListener(v -> tap(idx));
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = size;
            lp.height = size;
            lp.setMargins(m, m, m, m);
            grid.addView(pad, lp);
            pads[i] = pad;
        }
        col.addView(grid);
        status = Ui.text(c, "Press play when ready.", 16, Ui.MUTED, false);
        col.addView(status, Ui.wide(c, 12));
        play = Ui.button(c, "Play sequence", Ui.ACCENT);
        play.setOnClickListener(v -> playSequence());
        col.addView(play, Ui.wide(c, 12));
        return col;
    }

    private void playSequence() {
        accepting = false;
        play.setEnabled(false);
        status.setText("Watch closely...");
        long t = 500;
        for (int i = 0; i < sequence.length; i++) {
            final int idx = sequence[i];
            handler.postDelayed(() -> flash(idx, 450), t);
            t += 700;
        }
        handler.postDelayed(() -> {
            pos = 0;
            accepting = true;
            play.setEnabled(true);
            play.setText("Replay sequence");
            status.setText("Your turn: 0 / " + LENGTH);
        }, t);
    }

    private void flash(int idx, long ms) {
        pads[idx].setAlpha(1f);
        handler.postDelayed(() -> pads[idx].setAlpha(0.3f), ms);
    }

    private void tap(int idx) {
        if (!accepting) return;
        flash(idx, 180);
        if (idx == sequence[pos]) {
            pos++;
            status.setText("Your turn: " + pos + " / " + LENGTH);
            if (pos == LENGTH) {
                accepting = false;
                handler.postDelayed(this::solved, 300);
            }
        } else {
            accepting = false;
            newSequence();
            play.setText("Play new sequence");
            status.setText("Wrong pad. The sequence has changed.");
            wrong((View) pads[0].getParent(), "Wrong order!");
        }
    }

    @Override
    void dispose() {
        handler.removeCallbacksAndMessages(null);
    }
}

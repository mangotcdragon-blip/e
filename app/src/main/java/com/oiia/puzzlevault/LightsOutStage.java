package com.oiia.puzzlevault;

import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;

import java.util.Random;

/** Classic Lights Out: tapping a tile toggles it and its neighbours. Turn every light off. */
class LightsOutStage extends Stage {
    private static final int N = 4;
    private static final int ON = 0xFFFFC857;
    private static final int OFF = 0xFF2A3444;

    private final boolean[] start = new boolean[N * N];
    private final boolean[] lit = new boolean[N * N];
    private final View[] tiles = new View[N * N];
    private boolean done;

    LightsOutStage(Host host, Random rnd) {
        super(host, rnd);
        // Build the puzzle by pressing random tiles from the solved state,
        // which guarantees it can be solved.
        do {
            java.util.Arrays.fill(start, false);
            int presses = 3 + rnd.nextInt(3);
            for (int i = 0; i < presses; i++) toggle(start, rnd.nextInt(N * N));
        } while (allOff(start));
        System.arraycopy(start, 0, lit, 0, lit.length);
    }

    @Override
    String title() {
        return "Lights Out";
    }

    @Override
    String instructions() {
        return "Tapping a tile flips it and the tiles above, below, left and right.\nTurn all the lights off.";
    }

    @Override
    View build(Context c) {
        LinearLayout col = Ui.column(c);
        GridLayout grid = new GridLayout(c);
        grid.setColumnCount(N);
        int size = Ui.dp(c, 64), m = Ui.dp(c, 5);
        for (int i = 0; i < N * N; i++) {
            View t = new View(c);
            final int idx = i;
            t.setOnClickListener(v -> press(idx));
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = size;
            lp.height = size;
            lp.setMargins(m, m, m, m);
            grid.addView(t, lp);
            tiles[i] = t;
        }
        col.addView(grid);
        Button reset = Ui.button(c, "Reset board", Ui.CARD);
        reset.setOnClickListener(v -> {
            System.arraycopy(start, 0, lit, 0, lit.length);
            render();
        });
        col.addView(reset, Ui.wide(c, 20));
        render();
        return col;
    }

    private void press(int idx) {
        if (done) return;
        toggle(lit, idx);
        render();
        if (allOff(lit)) {
            done = true;
            tiles[0].postDelayed(this::solved, 300);
        }
    }

    private void render() {
        for (int i = 0; i < tiles.length; i++) {
            tiles[i].setBackground(Ui.rounded(tiles[i].getContext(), lit[i] ? ON : OFF, 10));
        }
    }

    private static void toggle(boolean[] b, int idx) {
        int r = idx / N, col = idx % N;
        flip(b, r, col);
        flip(b, r - 1, col);
        flip(b, r + 1, col);
        flip(b, r, col - 1);
        flip(b, r, col + 1);
    }

    private static void flip(boolean[] b, int r, int c) {
        if (r >= 0 && r < N && c >= 0 && c < N) b[r * N + c] = !b[r * N + c];
    }

    private static boolean allOff(boolean[] b) {
        for (boolean x : b) if (x) return false;
        return true;
    }
}

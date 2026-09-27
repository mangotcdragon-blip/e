package com.oiia.puzzlevault;

import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Arrays;
import java.util.Random;

/** Classic Lights Out: tapping a tile toggles it and its neighbours. Turn every light off. */
class LightsOutStage extends Stage {
    private static final int ON = 0xFFFFC857;
    private static final int OFF = 0xFF2A3444;

    private final int n;
    private final boolean[] start;
    private final boolean[] lit;
    private final View[] tiles;
    private int moves;
    private TextView counter;
    private boolean done;

    LightsOutStage(Host host, Random rnd, int level) {
        super(host, rnd, level);
        n = level < 3 ? 4 : 5;
        start = new boolean[n * n];
        lit = new boolean[n * n];
        tiles = new View[n * n];
        // Build the puzzle by pressing distinct random tiles from the solved state,
        // which guarantees it can be solved.
        int[] cells = new int[n * n];
        for (int i = 0; i < cells.length; i++) cells[i] = i;
        do {
            Arrays.fill(start, false);
            shuffle(cells);
            int presses = 4 + level * 2;
            for (int i = 0; i < presses; i++) toggle(start, cells[i]);
        } while (allOff(start));
        System.arraycopy(start, 0, lit, 0, lit.length);
    }

    @Override
    String title() {
        return "Lights Out";
    }

    @Override
    String instructions() {
        return "Tapping a tile flips it and the tiles above, below, left and right of it.\n"
                + "Turn every light off.";
    }

    @Override
    View build(Context c) {
        LinearLayout col = Ui.column(c);
        GridLayout grid = new GridLayout(c);
        grid.setColumnCount(n);
        int size = Ui.cell(c, n, 64, 10), m = Ui.dp(c, 5);
        for (int i = 0; i < n * n; i++) {
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
        counter = Ui.text(c, "", 14, Ui.MUTED, false);
        col.addView(counter, Ui.wide(c, 12));
        Button reset = Ui.button(c, "Reset board", Ui.CARD);
        reset.setOnClickListener(v -> {
            System.arraycopy(start, 0, lit, 0, lit.length);
            moves = 0;
            render();
        });
        col.addView(reset, Ui.wide(c, 12));
        render();
        return col;
    }

    private void press(int idx) {
        if (done) return;
        toggle(lit, idx);
        moves++;
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
        counter.setText("Moves: " + moves);
    }

    private void toggle(boolean[] b, int idx) {
        int r = idx / n, col = idx % n;
        flip(b, r, col);
        flip(b, r - 1, col);
        flip(b, r + 1, col);
        flip(b, r, col - 1);
        flip(b, r, col + 1);
    }

    private void flip(boolean[] b, int r, int c) {
        if (r >= 0 && r < n && c >= 0 && c < n) b[r * n + c] = !b[r * n + c];
    }

    private static boolean allOff(boolean[] b) {
        for (boolean x : b) if (x) return false;
        return true;
    }
}

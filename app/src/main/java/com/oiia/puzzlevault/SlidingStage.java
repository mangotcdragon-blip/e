package com.oiia.puzzlevault;

import android.content.Context;
import android.view.Gravity;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.View;

import java.util.Random;

/** Sliding tile puzzle (3x3, then 4x4 at the top levels). Put the numbers in order. */
class SlidingStage extends Stage {
    private final int n;
    private final int[] start;
    private final int[] board;
    private final TextView[] tiles;
    private int moves;
    private boolean done;
    private TextView counter;

    SlidingStage(Host host, Random rnd, int level) {
        super(host, rnd, level);
        n = level < 4 ? 3 : 4;
        start = new int[n * n];
        board = new int[n * n];
        tiles = new TextView[n * n];
        int scramble = n == 3 ? 30 + level * 15 : 60 + level * 12;
        do {
            for (int i = 0; i < n * n - 1; i++) start[i] = i + 1;
            start[n * n - 1] = 0;
            int blank = n * n - 1, prev = -1;
            for (int s = 0; s < scramble; s++) {
                int[] opts = new int[4];
                int k = 0;
                int r = blank / n, cc = blank % n;
                if (r > 0) opts[k++] = blank - n;
                if (r < n - 1) opts[k++] = blank + n;
                if (cc > 0) opts[k++] = blank - 1;
                if (cc < n - 1) opts[k++] = blank + 1;
                int next;
                do next = opts[rnd.nextInt(k)]; while (next == prev);
                start[blank] = start[next];
                start[next] = 0;
                prev = blank;
                blank = next;
            }
        } while (isSolved(start));
        System.arraycopy(start, 0, board, 0, board.length);
    }

    @Override
    String title() {
        return "Slide Lock";
    }

    @Override
    String instructions() {
        return "Slide the tiles into order, 1 to " + (n * n - 1) + ", with the gap in the bottom-right "
                + "corner.\nTap a tile in the same row or column as the gap to slide it.";
    }

    @Override
    View build(Context c) {
        LinearLayout col = Ui.column(c);
        GridLayout grid = new GridLayout(c);
        grid.setColumnCount(n);
        int size = Ui.cell(c, n, 84, 8), m = Ui.dp(c, 4);
        for (int i = 0; i < n * n; i++) {
            TextView t = Ui.text(c, "", 26, Ui.TEXT, true);
            t.setGravity(Gravity.CENTER);
            final int idx = i;
            t.setOnClickListener(v -> tap(idx));
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
        Button reset = Ui.button(c, "Reset tiles", Ui.CARD);
        reset.setOnClickListener(v -> {
            if (done) return;
            System.arraycopy(start, 0, board, 0, board.length);
            moves = 0;
            render();
        });
        col.addView(reset, Ui.wide(c, 12));
        render();
        return col;
    }

    private void tap(int i) {
        if (done) return;
        int blank = 0;
        for (int k = 0; k < board.length; k++) if (board[k] == 0) blank = k;
        int br = blank / n, bc = blank % n, r = i / n, c = i % n;
        if (r == br && c != bc) {
            int dir = c < bc ? -1 : 1;
            for (int cc = bc; cc != c; cc += dir) board[r * n + cc] = board[r * n + cc + dir];
        } else if (c == bc && r != br) {
            int dir = r < br ? -1 : 1;
            for (int rr = br; rr != r; rr += dir) board[rr * n + c] = board[(rr + dir) * n + c];
        } else {
            return;
        }
        board[i] = 0;
        moves++;
        render();
        if (isSolved(board)) {
            done = true;
            tiles[0].postDelayed(this::solved, 500);
        }
    }

    private boolean isSolved(int[] b) {
        for (int k = 0; k < b.length - 1; k++) if (b[k] != k + 1) return false;
        return true;
    }

    private void render() {
        for (int i = 0; i < tiles.length; i++) {
            TextView t = tiles[i];
            int v = board[i];
            t.setText(v == 0 ? "" : String.valueOf(v));
            t.setBackground(v == 0 ? null : Ui.rounded(t.getContext(),
                    v == i + 1 ? 0xFF2E7D5B : Ui.ACCENT, 12));
        }
        counter.setText("Moves: " + moves);
    }
}

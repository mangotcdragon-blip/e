package com.oiia.puzzlevault;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Random;

/**
 * Nim against the machine. The player always starts from a winnable position, but the
 * machine plays perfectly (a little sloppily on early levels), so winning needs the real
 * strategy.
 */
class NimStage extends Stage {
    private static final int[] ROW_COLORS = {0xFFFF5C7A, 0xFF3DDC97, 0xFF4DA3FF, 0xFFFFC857};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int[] heaps;
    private boolean playerTurn;
    private boolean over;
    private int losses;
    private LinearLayout board;
    private TextView status;

    NimStage(Host host, Random rnd, int level) {
        super(host, rnd, level);
        newGame();
    }

    private void newGame() {
        int count = level < 3 ? 3 : 4;
        int max = 3 + level;
        do {
            heaps = new int[count];
            for (int i = 0; i < count; i++) heaps[i] = 1 + rnd.nextInt(max);
        } while (xor() == 0);
        playerTurn = true;
        over = false;
    }

    private int xor() {
        int x = 0;
        for (int h : heaps) x ^= h;
        return x;
    }

    @Override
    String title() {
        return "Beat the Machine";
    }

    @Override
    String instructions() {
        return "Take turns removing stones. On your turn take any number of stones from ONE row "
                + "(tap a stone to take it and every stone to its right).\n"
                + "Whoever takes the LAST stone wins. You go first.";
    }

    @Override
    View build(Context c) {
        LinearLayout col = Ui.column(c);
        board = Ui.column(c);
        col.addView(board, Ui.wide(c, 0));
        status = Ui.text(c, "", 16, Ui.MUTED, false);
        col.addView(status, Ui.wide(c, 16));
        render();
        return col;
    }

    private void render() {
        Context c = board.getContext();
        board.removeAllViews();
        int size = Ui.cell(c, 8, 32, 8), m = Ui.dp(c, 4);
        for (int h = 0; h < heaps.length; h++) {
            LinearLayout row = Ui.row(c);
            row.setMinimumHeight(size + 2 * m);
            for (int i = 0; i < heaps[h]; i++) {
                View stone = Ui.circle(c, ROW_COLORS[h % ROW_COLORS.length], false);
                final int hh = h, ii = i;
                stone.setOnClickListener(v -> take(hh, ii));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
                lp.setMargins(m, m, m, m);
                row.addView(stone, lp);
            }
            if (heaps[h] == 0) row.addView(Ui.text(c, "(empty)", 14, Ui.MUTED, false));
            board.addView(row, Ui.wide(c, 6));
        }
        if (!over) status.setText(playerTurn ? "Your move." : "The machine is thinking...");
    }

    private boolean empty() {
        for (int h : heaps) if (h > 0) return false;
        return true;
    }

    private void take(int heap, int from) {
        if (!playerTurn || over) return;
        heaps[heap] = from;
        if (empty()) {
            over = true;
            render();
            status.setText("You took the last stone!");
            handler.postDelayed(this::solved, 700);
            return;
        }
        playerTurn = false;
        render();
        handler.postDelayed(this::machineMove, 900);
    }

    private void machineMove() {
        int x = xor();
        boolean sloppy = level < 2 && rnd.nextInt(10) < 4;
        boolean moved = false;
        if (x != 0 && !sloppy) {
            for (int h = 0; h < heaps.length; h++) {
                if ((heaps[h] ^ x) < heaps[h]) {
                    heaps[h] ^= x;
                    moved = true;
                    break;
                }
            }
        }
        if (!moved) {
            int h;
            do h = rnd.nextInt(heaps.length); while (heaps[h] == 0);
            heaps[h] -= 1 + rnd.nextInt(heaps[h]);
        }
        if (empty()) {
            over = true;
            losses++;
            render();
            status.setText("The machine took the last stone.");
            wrong(board, losses >= 4
                    ? "Hint: write each row's count in binary. After your move, every binary "
                            + "column should hold an even number of 1s."
                    : losses >= 2
                    ? "You lost. Hint: think of each row in binary..."
                    : "You lost. A new game begins.");
            handler.postDelayed(() -> {
                newGame();
                render();
            }, 1800);
            return;
        }
        playerTurn = true;
        render();
    }

    @Override
    void dispose() {
        handler.removeCallbacksAndMessages(null);
    }
}

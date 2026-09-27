package com.oiia.puzzlevault;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Random;

/**
 * Mini sudoku with a guaranteed unique solution: 4x4 (2x2 boxes) on early levels,
 * 6x6 (2x3 boxes) later. Clues are removed only while the solution stays unique.
 */
class SudokuStage extends Stage {
    private static final int[] TARGET_CLUES = {8, 6, 5, 16, 13, 11};
    private static final int GIVEN_BG = 0xFF2A3444;

    private final int n, boxR, boxC;
    private final int[] solution;
    private final int[] puzzle;
    private final int[] entry;
    private final TextView[] cells;
    private int selected = -1;
    private boolean done;
    private GridLayout grid;

    SudokuStage(Host host, Random rnd, int level) {
        super(host, rnd, level);
        n = level < 3 ? 4 : 6;
        boxR = 2;
        boxC = n / 2;
        solution = new int[n * n];
        fill(solution, 0);

        puzzle = solution.clone();
        int[] order = new int[n * n];
        for (int i = 0; i < order.length; i++) order[i] = i;
        shuffle(order);
        int clues = n * n;
        int target = TARGET_CLUES[Math.min(level, TARGET_CLUES.length - 1)];
        for (int cell : order) {
            if (clues <= target) break;
            int keep = puzzle[cell];
            puzzle[cell] = 0;
            if (countSolutions(puzzle.clone(), 2) == 1) clues--;
            else puzzle[cell] = keep;
        }
        entry = puzzle.clone();
        cells = new TextView[n * n];
    }

    private boolean ok(int[] g, int pos, int d) {
        int r = pos / n, c = pos % n;
        for (int i = 0; i < n; i++) {
            if (g[r * n + i] == d || g[i * n + c] == d) return false;
        }
        int r0 = r / boxR * boxR, c0 = c / boxC * boxC;
        for (int rr = r0; rr < r0 + boxR; rr++) {
            for (int cc = c0; cc < c0 + boxC; cc++) if (g[rr * n + cc] == d) return false;
        }
        return true;
    }

    private boolean fill(int[] g, int pos) {
        if (pos == g.length) return true;
        int[] digits = new int[n];
        for (int i = 0; i < n; i++) digits[i] = i + 1;
        shuffle(digits);
        for (int d : digits) {
            if (ok(g, pos, d)) {
                g[pos] = d;
                if (fill(g, pos + 1)) return true;
                g[pos] = 0;
            }
        }
        return false;
    }

    private int countSolutions(int[] g, int limit) {
        int pos = -1;
        for (int i = 0; i < g.length; i++) {
            if (g[i] == 0) {
                pos = i;
                break;
            }
        }
        if (pos < 0) return 1;
        int total = 0;
        for (int d = 1; d <= n && total < limit; d++) {
            if (ok(g, pos, d)) {
                g[pos] = d;
                total += countSolutions(g, limit - total);
                g[pos] = 0;
            }
        }
        return total;
    }

    @Override
    String title() {
        return "Number Grid";
    }

    @Override
    String instructions() {
        return "Fill the grid so every row, every column and every outlined box contains each "
                + "number from 1 to " + n + " exactly once.\nTap a square, then tap a number.";
    }

    @Override
    View build(Context c) {
        LinearLayout col = Ui.column(c);
        grid = new GridLayout(c);
        grid.setColumnCount(n);
        int size = Ui.cell(c, n, 60, 10), m = Ui.dp(c, 2), big = Ui.dp(c, 8);
        for (int i = 0; i < n * n; i++) {
            int r = i / n, cc = i % n;
            TextView t = Ui.text(c, "", n == 4 ? 26 : 22, Ui.TEXT, true);
            t.setGravity(Gravity.CENTER);
            final int idx = i;
            t.setOnClickListener(v -> {
                if (puzzle[idx] != 0 || done) return;
                selected = idx;
                render();
            });
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = size;
            lp.height = size;
            lp.setMargins(cc > 0 && cc % boxC == 0 ? big : m, r > 0 && r % boxR == 0 ? big : m, m, m);
            grid.addView(t, lp);
            cells[i] = t;
        }
        col.addView(grid);

        LinearLayout pad = Ui.row(c);
        int keyW = Ui.cell(c, n + 1, 56, 8);
        for (int d = 0; d <= n; d++) {
            final int digit = d == n ? 0 : d + 1;
            Button b = Ui.smallButton(c, digit == 0 ? "⌫" : String.valueOf(digit),
                    digit == 0 ? Ui.BAD : Ui.CARD);
            b.setTextSize(20);
            b.setOnClickListener(v -> input(digit));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(keyW, keyW);
            lp.setMargins(Ui.dp(c, 3), 0, Ui.dp(c, 3), 0);
            pad.addView(b, lp);
        }
        col.addView(pad, Ui.wide(c, 20));
        render();
        return col;
    }

    private void input(int digit) {
        if (done || selected < 0) return;
        entry[selected] = digit;
        render();
        for (int v : entry) if (v == 0) return;
        for (int i = 0; i < entry.length; i++) {
            if (entry[i] != solution[i]) {
                wrong(grid, "Not quite. Check your rows, columns and boxes.");
                return;
            }
        }
        done = true;
        selected = -1;
        render();
        grid.postDelayed(this::solved, 600);
    }

    private void render() {
        for (int i = 0; i < cells.length; i++) {
            TextView t = cells[i];
            boolean given = puzzle[i] != 0;
            t.setText(entry[i] == 0 ? "" : String.valueOf(entry[i]));
            t.setTypeface(Typeface.DEFAULT, given ? Typeface.BOLD : Typeface.NORMAL);
            t.setTextColor(given ? Ui.MUTED : done ? Ui.GOOD : Ui.TEXT);
            int bg = i == selected ? Ui.ACCENT : given ? GIVEN_BG : Ui.CARD;
            t.setBackground(Ui.rounded(t.getContext(), bg, 8));
        }
    }
}

package com.oiia.puzzlevault;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Random;

/** Measure an exact amount of water using two unmarked jugs. */
class WaterJugStage extends Stage {
    private static final int WATER = 0xFF4DA3FF;

    private final int[] cap = new int[2];
    private final int target;
    private final int best;
    private final int[] amt = new int[2];
    private int steps;
    private boolean done;

    private final FrameLayout[] jugs = new FrameLayout[2];
    private final View[] fills = new View[2];
    private final TextView[] labels = new TextView[2];
    private TextView counter;
    private LinearLayout root;

    WaterJugStage(Host host, Random rnd, int level) {
        super(host, rnd, level);
        int a = 3, b = 5, t = 4, d = 6;
        for (int tries = 0; tries < 5000; tries++) {
            int ca = 3 + rnd.nextInt(3 + level);
            int cb = ca + 1 + rnd.nextInt(4 + level);
            int ct = 1 + rnd.nextInt(cb - 1);
            if (ct == ca) continue;
            int dd = solve(ca, cb, ct);
            if (dd >= 4 + level && dd <= 10 + 2 * level) {
                a = ca;
                b = cb;
                t = ct;
                d = dd;
                break;
            }
        }
        cap[0] = a;
        cap[1] = b;
        target = t;
        best = d;
    }

    /** Minimum number of actions to get {@code t} litres into either jug, or -1. */
    private static int solve(int a, int b, int t) {
        int[][] dist = new int[a + 1][b + 1];
        for (int[] row : dist) Arrays.fill(row, -1);
        ArrayDeque<int[]> q = new ArrayDeque<>();
        dist[0][0] = 0;
        q.add(new int[]{0, 0});
        while (!q.isEmpty()) {
            int[] s = q.poll();
            int x = s[0], y = s[1], d = dist[x][y];
            if (x == t || y == t) return d;
            int p1 = Math.min(x, b - y), p2 = Math.min(y, a - x);
            int[][] next = {{a, y}, {x, b}, {0, y}, {x, 0}, {x - p1, y + p1}, {x + p2, y - p2}};
            for (int[] nx : next) {
                if (dist[nx[0]][nx[1]] < 0) {
                    dist[nx[0]][nx[1]] = d + 1;
                    q.add(nx);
                }
            }
        }
        return -1;
    }

    @Override
    String title() {
        return "The Two Jugs";
    }

    @Override
    String instructions() {
        return "You have a " + cap[0] + " L jug and a " + cap[1] + " L jug, with no markings, "
                + "and an endless tap.\nGet EXACTLY " + target + " L of water into one of the jugs.";
    }

    @Override
    View build(Context c) {
        root = Ui.column(c);
        LinearLayout pair = Ui.row(c);
        pair.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        for (int i = 0; i < 2; i++) pair.addView(jugColumn(c, i), new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(pair);

        counter = Ui.text(c, "", 14, Ui.MUTED, false);
        root.addView(counter, Ui.wide(c, 14));
        Button reset = Ui.button(c, "Empty both and start over", Ui.CARD);
        reset.setOnClickListener(v -> {
            amt[0] = amt[1] = 0;
            steps = 0;
            render();
        });
        root.addView(reset, Ui.wide(c, 10));
        render();
        return root;
    }

    private View jugColumn(Context c, int i) {
        LinearLayout col = Ui.column(c);
        col.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        int h = Ui.dp(c, 60 + 140f * cap[i] / cap[1]);
        FrameLayout jug = new FrameLayout(c);
        jug.setBackground(Ui.rounded(c, Ui.CARD, 10));
        View fill = new View(c);
        fill.setBackground(Ui.rounded(c, WATER, 10));
        jug.addView(fill, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, 0, Gravity.BOTTOM));
        TextView label = Ui.text(c, "", 18, Ui.TEXT, true);
        jug.addView(label, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        col.addView(jug, new LinearLayout.LayoutParams(Ui.dp(c, 90), h));
        jugs[i] = jug;
        fills[i] = fill;
        labels[i] = label;

        col.addView(Ui.text(c, cap[i] + " L jug", 14, Ui.MUTED, true), Ui.wide(c, 6));
        Button fillB = Ui.smallButton(c, "Fill", Ui.ACCENT);
        fillB.setOnClickListener(v -> act(() -> amt[i] = cap[i]));
        Button emptyB = Ui.smallButton(c, "Empty", Ui.CARD);
        emptyB.setOnClickListener(v -> act(() -> amt[i] = 0));
        final int o = 1 - i;
        Button pourB = Ui.smallButton(c, i == 0 ? "Pour  →" : "←  Pour", 0xFF2E5E8C);
        pourB.setOnClickListener(v -> act(() -> {
            int p = Math.min(amt[i], cap[o] - amt[o]);
            amt[i] -= p;
            amt[o] += p;
        }));
        for (Button b : new Button[]{fillB, emptyB, pourB}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    Ui.dp(c, 120), LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(c, 8);
            col.addView(b, lp);
        }
        return col;
    }

    private void act(Runnable r) {
        if (done) return;
        r.run();
        steps++;
        render();
        if (amt[0] == target || amt[1] == target) {
            done = true;
            counter.setText("Exactly " + target + " L! Solved in " + steps + " steps (best possible: " + best + ").");
            root.postDelayed(this::solved, 900);
        }
    }

    private void render() {
        for (int i = 0; i < 2; i++) {
            final int idx = i;
            jugs[i].post(() -> {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) fills[idx].getLayoutParams();
                lp.height = jugs[idx].getHeight() * amt[idx] / cap[idx];
                fills[idx].setLayoutParams(lp);
            });
            labels[i].setText(amt[i] + " L");
        }
        counter.setText("Steps: " + steps);
    }
}

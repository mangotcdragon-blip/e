package com.oiia.puzzlevault;

import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Random;

/** Mastermind: deduce a hidden colour code from the feedback on each guess. */
class CodeBreakerStage extends Stage {
    private static final int SLOTS = 4;
    private static final int[] COLORS = {
            0xFFFF5C7A, 0xFF3DDC97, 0xFF4DA3FF, 0xFFFFC857, 0xFFB57CFF, 0xFFFF9F43
    };

    private final int colors;
    private final boolean repeats;
    private final int maxGuesses;
    private int[] secret;
    private final int[] current = new int[SLOTS];
    private int guesses;
    private boolean done;

    private LinearLayout history;
    private LinearLayout currentRow;
    private TextView left;

    CodeBreakerStage(Host host, Random rnd, int level) {
        super(host, rnd, level);
        colors = level < 3 ? 5 : 6;
        repeats = level >= 2;
        maxGuesses = level < 3 ? 10 : 8;
        newSecret();
    }

    private void newSecret() {
        secret = new int[SLOTS];
        boolean[] used = new boolean[colors];
        for (int i = 0; i < SLOTS; i++) {
            int col;
            do col = rnd.nextInt(colors); while (!repeats && used[col]);
            used[col] = true;
            secret[i] = col;
        }
        java.util.Arrays.fill(current, -1);
        guesses = 0;
    }

    @Override
    String title() {
        return "Colour Lock";
    }

    @Override
    String instructions() {
        return "Crack the hidden " + SLOTS + "-colour code in " + maxGuesses + " guesses.\n"
                + "● = right colour in the right spot.  ○ = right colour, wrong spot.\n"
                + (repeats ? "Colours CAN repeat." : "No colour is used twice.");
    }

    @Override
    View build(Context c) {
        LinearLayout col = Ui.column(c);
        history = Ui.column(c);
        col.addView(history, Ui.wide(c, 0));

        currentRow = Ui.row(c);
        col.addView(currentRow, Ui.wide(c, 16));

        LinearLayout palette = Ui.row(c);
        int ps = Ui.dp(c, 42), pm = Ui.dp(c, 5);
        for (int i = 0; i < colors; i++) {
            View dot = Ui.circle(c, COLORS[i], false);
            final int idx = i;
            dot.setOnClickListener(v -> pick(idx));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ps, ps);
            lp.setMargins(pm, pm, pm, pm);
            palette.addView(dot, lp);
        }
        col.addView(palette, Ui.wide(c, 14));

        Button go = Ui.button(c, "Try this code", Ui.ACCENT);
        go.setOnClickListener(v -> submit());
        col.addView(go, Ui.wide(c, 12));
        left = Ui.text(c, "", 14, Ui.MUTED, false);
        col.addView(left, Ui.wide(c, 8));
        renderCurrent();
        return col;
    }

    private void pick(int color) {
        if (done) return;
        for (int i = 0; i < SLOTS; i++) {
            if (current[i] < 0) {
                current[i] = color;
                break;
            }
        }
        renderCurrent();
    }

    private void renderCurrent() {
        Context c = currentRow.getContext();
        currentRow.removeAllViews();
        int s = Ui.dp(c, 48), m = Ui.dp(c, 6);
        for (int i = 0; i < SLOTS; i++) {
            View slot = current[i] < 0 ? Ui.circle(c, 0, true) : Ui.circle(c, COLORS[current[i]], false);
            final int idx = i;
            slot.setOnClickListener(v -> {
                current[idx] = -1;
                renderCurrent();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
            lp.setMargins(m, 0, m, 0);
            currentRow.addView(slot, lp);
        }
        left.setText((maxGuesses - guesses) + " guesses left. Tap a slot to clear it.");
    }

    private void submit() {
        if (done) return;
        for (int v : current) {
            if (v < 0) {
                wrong(currentRow, "Fill all " + SLOTS + " slots first.");
                return;
            }
        }
        int exact = 0;
        int[] cs = new int[colors], cg = new int[colors];
        for (int i = 0; i < SLOTS; i++) {
            if (current[i] == secret[i]) exact++;
            cs[secret[i]]++;
            cg[current[i]]++;
        }
        int common = 0;
        for (int i = 0; i < colors; i++) common += Math.min(cs[i], cg[i]);
        int near = common - exact;
        guesses++;
        addHistory(current.clone(), exact, near);

        if (exact == SLOTS) {
            done = true;
            left.setText("Cracked in " + guesses + " guesses!");
            currentRow.postDelayed(this::solved, 800);
            return;
        }
        java.util.Arrays.fill(current, -1);
        if (guesses >= maxGuesses) {
            newSecret();
            history.removeAllViews();
            wrong(currentRow, "Out of guesses. The lock picked a new code.");
        }
        renderCurrent();
    }

    private void addHistory(int[] guess, int exact, int near) {
        Context c = history.getContext();
        LinearLayout row = Ui.row(c);
        int s = Ui.dp(c, 26), m = Ui.dp(c, 4);
        row.addView(Ui.text(c, guesses + ".", 14, Ui.MUTED, false),
                new LinearLayout.LayoutParams(Ui.dp(c, 28), LinearLayout.LayoutParams.WRAP_CONTENT));
        for (int g : guess) {
            View dot = Ui.circle(c, COLORS[g], false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
            lp.setMargins(m, m, m, m);
            row.addView(dot, lp);
        }
        StringBuilder fb = new StringBuilder("   ");
        for (int i = 0; i < exact; i++) fb.append('●');
        for (int i = 0; i < near; i++) fb.append('○');
        if (exact + near == 0) fb.append("—");
        TextView t = Ui.text(c, fb.toString(), 18, Ui.TEXT, true);
        row.addView(t, new LinearLayout.LayoutParams(Ui.dp(c, 96), LinearLayout.LayoutParams.WRAP_CONTENT));
        history.addView(row);
    }
}

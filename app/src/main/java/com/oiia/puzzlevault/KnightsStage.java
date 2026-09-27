package com.oiia.puzzlevault;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Knights and knaves: knights always tell the truth, knaves always lie. Statements are
 * generated at random and kept only when exactly one assignment is consistent, so the
 * puzzle always has a single logical answer. A wrong answer brings a new group.
 */
class KnightsStage extends Stage {
    private static final String[] NAMES = {"Ada", "Bram", "Cleo", "Dax", "Esme", "Finn", "Gus", "Hana"};

    private interface Claim {
        boolean holds(boolean[] knight);
    }

    private final int n;
    private String[] names;
    private String[] said;
    private Claim[] claims;
    private boolean[] solution;
    private int[] guess; // 0 = undecided, 1 = knight, 2 = knave
    private LinearLayout list;

    KnightsStage(Host host, Random rnd, int level) {
        super(host, rnd, level);
        n = level >= 3 ? 4 : 3;
        generate();
    }

    private void generate() {
        List<String> pool = new ArrayList<>();
        Collections.addAll(pool, NAMES);
        Collections.shuffle(pool, rnd);
        names = new String[n];
        for (int i = 0; i < n; i++) names[i] = pool.get(i);
        Collections.sort(java.util.Arrays.asList(names));

        while (true) {
            said = new String[n];
            claims = new Claim[n];
            for (int s = 0; s < n; s++) makeClaim(s);
            int found = 0;
            boolean[] sol = null;
            for (int mask = 0; mask < (1 << n); mask++) {
                boolean[] k = new boolean[n];
                for (int i = 0; i < n; i++) k[i] = (mask & (1 << i)) != 0;
                boolean ok = true;
                for (int s = 0; s < n && ok; s++) ok = k[s] == claims[s].holds(k);
                if (ok) {
                    found++;
                    sol = k;
                }
            }
            // Skip puzzles where everyone is the same kind: they're too easy to guess.
            if (found == 1 && !allSame(sol)) {
                solution = sol;
                break;
            }
        }
        guess = new int[n];
    }

    private static boolean allSame(boolean[] b) {
        for (boolean x : b) if (x != b[0]) return false;
        return true;
    }

    private static String kind(boolean knight) {
        return knight ? "a knight" : "a knave";
    }

    private void makeClaim(final int s) {
        int x0;
        do x0 = rnd.nextInt(n); while (x0 == s);
        int y0;
        do y0 = rnd.nextInt(n); while (y0 == s || y0 == x0);
        final int x = x0, y = y0;
        final boolean p = rnd.nextBoolean(), q = rnd.nextBoolean();
        int types = level < 2 ? 3 : 6;
        switch (rnd.nextInt(types)) {
            case 0:
                said[s] = names[x] + " is " + kind(p) + ".";
                claims[s] = k -> k[x] == p;
                break;
            case 1:
                said[s] = p ? names[x] + " and I are the same kind."
                        : names[x] + " and I are different kinds.";
                claims[s] = k -> (k[x] == k[s]) == p;
                break;
            case 2: {
                final int cnt = rnd.nextInt(n + 1);
                said[s] = cnt == 0 ? "None of us are knights."
                        : "Exactly " + cnt + " of us " + (cnt == 1 ? "is a knight." : "are knights.");
                claims[s] = k -> {
                    int t = 0;
                    for (boolean b : k) if (b) t++;
                    return t == cnt;
                };
                break;
            }
            case 3:
                said[s] = names[x] + " and " + names[y] + " are both " + (p ? "knights." : "knaves.");
                claims[s] = k -> k[x] == p && k[y] == p;
                break;
            case 4:
                said[s] = names[x] + " is " + kind(p) + ", or " + names[y] + " is " + kind(q)
                        + " (or both).";
                claims[s] = k -> k[x] == p || k[y] == q;
                break;
            default:
                said[s] = "If " + names[x] + " is " + kind(p) + ", then " + names[y] + " is "
                        + kind(q) + ".";
                claims[s] = k -> k[x] != p || k[y] == q;
                break;
        }
    }

    @Override
    String title() {
        return "Island of Liars";
    }

    @Override
    String instructions() {
        return "Knights always tell the truth. Knaves always lie.\n"
                + "Work out who is who. One wrong guess and a new group arrives.";
    }

    @Override
    View build(Context c) {
        LinearLayout col = Ui.column(c);
        list = Ui.column(c);
        col.addView(list, Ui.wide(c, 0));
        Button go = Ui.button(c, "Submit verdict", Ui.ACCENT);
        go.setOnClickListener(v -> submit(v));
        col.addView(go, Ui.wide(c, 20));
        renderList(c);
        return col;
    }

    private void renderList(Context c) {
        list.removeAllViews();
        for (int i = 0; i < n; i++) {
            LinearLayout card = Ui.column(c);
            card.setGravity(Gravity.START);
            card.setBackground(Ui.rounded(c, Ui.CARD, 14));
            int p = Ui.dp(c, 14);
            card.setPadding(p, p, p, p);

            TextView who = Ui.text(c, names[i] + " says:", 15, Ui.MUTED, true);
            who.setGravity(Gravity.START);
            card.addView(who);
            TextView quote = Ui.text(c, "“" + said[i] + "”", 18, Ui.TEXT, false);
            quote.setGravity(Gravity.START);
            quote.setTypeface(Typeface.defaultFromStyle(Typeface.ITALIC));
            card.addView(quote, Ui.wide(c, 4));

            final int idx = i;
            Button toggle = Ui.smallButton(c, "", Ui.BG);
            styleToggle(c, toggle, guess[i]);
            toggle.setOnClickListener(v -> {
                guess[idx] = guess[idx] == 1 ? 2 : 1;
                styleToggle(c, toggle, guess[idx]);
            });
            card.addView(toggle, Ui.wide(c, 10));
            list.addView(card, Ui.wide(c, i == 0 ? 0 : 12));
        }
    }

    private void styleToggle(Context c, Button b, int g) {
        b.setText(g == 0 ? "Tap to decide: knight or knave?" : g == 1 ? "Knight (truth-teller)" : "Knave (liar)");
        b.setBackground(Ui.rounded(c, g == 0 ? Ui.BG : g == 1 ? 0xFF2E7D5B : 0xFF9C2F45, 10));
    }

    private void submit(View v) {
        for (int g : guess) {
            if (g == 0) {
                wrong(list, "Decide on everyone first.");
                return;
            }
        }
        for (int i = 0; i < n; i++) {
            if ((guess[i] == 1) != solution[i]) {
                generate();
                renderList(v.getContext());
                wrong(list, "Wrong! A new group of islanders arrives.");
                return;
            }
        }
        solved();
    }
}

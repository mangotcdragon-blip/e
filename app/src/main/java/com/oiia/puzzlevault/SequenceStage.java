package com.oiia.puzzlevault;

import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;

import java.util.Random;

/** Find the next number in a pattern. */
class SequenceStage extends Stage {
    private final int[] terms = new int[6];

    SequenceStage(Host host, Random rnd) {
        super(host, rnd);
        switch (rnd.nextInt(5)) {
            case 0: { // arithmetic
                int s = rnd.nextInt(20), d = 3 + rnd.nextInt(9);
                for (int i = 0; i < 6; i++) terms[i] = s + d * i;
                break;
            }
            case 1: { // geometric
                int s = 1 + rnd.nextInt(4), r = 2 + rnd.nextInt(2);
                terms[0] = s;
                for (int i = 1; i < 6; i++) terms[i] = terms[i - 1] * r;
                break;
            }
            case 2: { // squares
                int k = 1 + rnd.nextInt(7);
                for (int i = 0; i < 6; i++) terms[i] = (k + i) * (k + i);
                break;
            }
            case 3: { // fibonacci-like
                terms[0] = 1 + rnd.nextInt(5);
                terms[1] = 1 + rnd.nextInt(5);
                for (int i = 2; i < 6; i++) terms[i] = terms[i - 1] + terms[i - 2];
                break;
            }
            default: { // growing gaps: +d, +2d, +3d...
                int s = 1 + rnd.nextInt(10), d = 1 + rnd.nextInt(3);
                terms[0] = s;
                for (int i = 1; i < 6; i++) terms[i] = terms[i - 1] + d * i;
                break;
            }
        }
    }

    @Override
    String title() {
        return "Pattern Hunter";
    }

    @Override
    String instructions() {
        return "What number comes next?";
    }

    @Override
    View build(Context c) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) sb.append(terms[i]).append(",  ");
        sb.append("?");

        LinearLayout col = Ui.column(c);
        col.addView(Ui.text(c, sb.toString(), 28, Ui.TEXT, true));
        EditText in = Ui.input(c, true);
        in.setHint("next number");
        col.addView(in, Ui.wide(c, 24));
        Button go = Ui.button(c, "Submit", Ui.ACCENT);
        go.setOnClickListener(v -> {
            Integer n = parse(in.getText());
            if (n != null && n == terms[5]) solved();
            else wrong(in, "That breaks the pattern.");
        });
        col.addView(go, Ui.wide(c, 16));
        return col;
    }
}

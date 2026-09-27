package com.oiia.puzzlevault;

import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;

import java.util.Random;

/** Solve an arithmetic expression (with operator precedence). */
class MathStage extends Stage {
    private final String expr;
    private final int answer;

    MathStage(Host host, Random rnd) {
        super(host, rnd);
        int a = 3 + rnd.nextInt(17);
        int b = 2 + rnd.nextInt(9);
        int c = 2 + rnd.nextInt(12);
        switch (rnd.nextInt(4)) {
            case 0:
                expr = a + " + " + b + " × " + c;
                answer = a + b * c;
                break;
            case 1:
                expr = "(" + a + " + " + b + ") × " + c;
                answer = (a + b) * c;
                break;
            case 2:
                expr = a + "² − " + b + " × " + c;
                answer = a * a - b * c;
                break;
            default:
                expr = a * b + " ÷ " + b + " + " + c + " × " + b;
                answer = a + c * b;
                break;
        }
    }

    @Override
    String title() {
        return "Number Cruncher";
    }

    @Override
    String instructions() {
        return "Mind the order of operations.";
    }

    @Override
    View build(Context c) {
        LinearLayout col = Ui.column(c);
        col.addView(Ui.text(c, expr + " = ?", 32, Ui.TEXT, true));
        EditText in = Ui.input(c, true);
        in.setHint("answer");
        col.addView(in, Ui.wide(c, 24));
        Button go = Ui.button(c, "Submit", Ui.ACCENT);
        go.setOnClickListener(v -> {
            Integer n = parse(in.getText());
            if (n != null && n == answer) solved();
            else wrong(in, "Not quite. Try again.");
        });
        col.addView(go, Ui.wide(c, 16));
        return col;
    }
}

package com.oiia.puzzlevault;

import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;

import java.util.Locale;
import java.util.Random;

/** Decode a Caesar-shifted word. */
class CipherStage extends Stage {
    private final String word;
    private final String encoded;
    private final int shift;

    CipherStage(Host host, Random rnd) {
        super(host, rnd);
        word = Words.pick(rnd);
        shift = 1 + rnd.nextInt(4);
        StringBuilder sb = new StringBuilder();
        for (char ch : word.toCharArray()) sb.append((char) ('A' + (ch - 'A' + shift) % 26));
        encoded = sb.toString();
    }

    @Override
    String title() {
        return "Secret Message";
    }

    @Override
    String instructions() {
        return "Every letter was moved " + shift + " step" + (shift == 1 ? "" : "s")
                + " forward in the alphabet (A→" + (char) ('A' + shift) + ").\nDecode the word.";
    }

    @Override
    View build(Context c) {
        LinearLayout col = Ui.column(c);
        col.addView(Ui.text(c, encoded, 34, Ui.GOOD, true));
        col.addView(Ui.text(c, "A B C D E F G H I J K L M\nN O P Q R S T U V W X Y Z", 15,
                Ui.MUTED, false), Ui.wide(c, 12));
        EditText in = Ui.input(c, false);
        in.setHint("decoded word");
        col.addView(in, Ui.wide(c, 20));
        Button go = Ui.button(c, "Submit", Ui.ACCENT);
        go.setOnClickListener(v -> {
            if (in.getText().toString().trim().toUpperCase(Locale.US).equals(word)) solved();
            else wrong(in, "The message is still scrambled.");
        });
        col.addView(go, Ui.wide(c, 16));
        return col;
    }
}

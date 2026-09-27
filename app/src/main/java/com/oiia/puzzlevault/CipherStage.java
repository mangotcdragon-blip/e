package com.oiia.puzzlevault;

import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;

import java.util.Locale;
import java.util.Random;

/**
 * Crack a shift cipher whose key is never given: the player has to work it out from a
 * known word ("crib"). From level 3 the text is also written backwards.
 */
class CipherStage extends Stage {
    private final String answer;
    private final String encoded;
    private final String crib;
    private final String cribEncoded;

    CipherStage(Host host, Random rnd, int level) {
        super(host, rnd, level);
        int shift = 1 + rnd.nextInt(25);
        boolean reversed = level >= 3;
        String w1 = Words.pick(rnd);
        String a = w1;
        if (level >= 2) {
            String w2;
            do w2 = Words.pick(rnd); while (w2.equals(w1));
            a = w1 + " " + w2;
        }
        String c;
        do c = Words.pickShort(rnd); while (a.contains(c));
        answer = a;
        crib = c;
        encoded = encode(a, shift, reversed);
        cribEncoded = encode(c, shift, reversed);
    }

    private static String encode(String s, int shift, boolean reversed) {
        StringBuilder sb = new StringBuilder();
        for (char ch : s.toCharArray()) {
            sb.append(ch == ' ' ? ' ' : (char) ('A' + (ch - 'A' + shift) % 26));
        }
        return reversed ? sb.reverse().toString() : sb.toString();
    }

    @Override
    String title() {
        return "Intercepted Message";
    }

    @Override
    String instructions() {
        return "Every letter was moved the same secret number of steps along the alphabet "
                + "(wrapping from Z back to A)" + (level >= 3 ? ", and the sender added one more trick" : "")
                + ".\nYou know how one word looks in this code:\n"
                + crib + "  →  " + cribEncoded;
    }

    @Override
    View build(Context c) {
        LinearLayout col = Ui.column(c);
        col.addView(Ui.text(c, encoded, 30, Ui.GOOD, true));
        col.addView(Ui.text(c, "A B C D E F G H I J K L M\nN O P Q R S T U V W X Y Z", 15,
                Ui.MUTED, false), Ui.wide(c, 12));
        EditText in = Ui.input(c, false);
        in.setHint("decoded message");
        col.addView(in, Ui.wide(c, 20));
        Button go = Ui.button(c, "Submit", Ui.ACCENT);
        go.setOnClickListener(v -> {
            if (normalise(in.getText().toString()).equals(normalise(answer))) solved();
            else wrong(in, "The message still doesn't make sense.");
        });
        col.addView(go, Ui.wide(c, 16));
        return col;
    }

    private static String normalise(String s) {
        return s.replaceAll("[^A-Za-z]", "").toUpperCase(Locale.US);
    }
}

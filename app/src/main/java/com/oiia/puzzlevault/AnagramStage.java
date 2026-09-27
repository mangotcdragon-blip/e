package com.oiia.puzzlevault;

import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;
import java.util.Random;

/** Unscramble a word. Hints reveal letters one at a time. */
class AnagramStage extends Stage {
    private final String word;
    private final String scrambled;
    private int hintsUsed = 0;

    AnagramStage(Host host, Random rnd) {
        super(host, rnd);
        word = Words.pick(rnd);
        char[] ch = word.toCharArray();
        String s;
        do {
            for (int i = ch.length - 1; i > 0; i--) {
                int j = rnd.nextInt(i + 1);
                char t = ch[i];
                ch[i] = ch[j];
                ch[j] = t;
            }
            s = new String(ch);
        } while (s.equals(word));
        scrambled = s;
    }

    @Override
    String title() {
        return "Word Scramble";
    }

    @Override
    String instructions() {
        return "Unscramble the letters to form a word.";
    }

    @Override
    View build(Context c) {
        LinearLayout col = Ui.column(c);
        col.addView(Ui.text(c, spaced(scrambled), 32, Ui.TEXT, true));
        TextView hint = Ui.text(c, "", 16, Ui.MUTED, false);
        col.addView(hint, Ui.wide(c, 8));
        EditText in = Ui.input(c, false);
        in.setHint("your word");
        col.addView(in, Ui.wide(c, 20));
        Button go = Ui.button(c, "Submit", Ui.ACCENT);
        go.setOnClickListener(v -> {
            if (in.getText().toString().trim().toUpperCase(Locale.US).equals(word)) solved();
            else wrong(in, "That's not it.");
        });
        col.addView(go, Ui.wide(c, 16));
        Button hintBtn = Ui.button(c, "Hint", Ui.CARD);
        hintBtn.setOnClickListener(v -> {
            if (hintsUsed < word.length() - 2) hintsUsed++;
            StringBuilder sb = new StringBuilder("Starts with: ");
            sb.append(word, 0, hintsUsed);
            for (int i = hintsUsed; i < word.length(); i++) sb.append('_');
            hint.setText(sb.toString());
        });
        col.addView(hintBtn, Ui.wide(c, 10));
        return col;
    }

    private static String spaced(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(s.charAt(i));
        }
        return sb.toString();
    }
}

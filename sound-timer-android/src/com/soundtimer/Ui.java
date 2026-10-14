package com.soundtimer;

import android.content.Context;
import android.graphics.Typeface;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.math.BigDecimal;

/** Small helpers so each screen can be built in code without layout XML. */
final class Ui {

    private Ui() {}

    static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    static LinearLayout column(Context c) {
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(c, 20);
        root.setPadding(pad, pad, pad, pad);
        return root;
    }

    static View scroll(Context c, View content) {
        ScrollView s = new ScrollView(c);
        s.addView(content);
        return s;
    }

    static TextView title(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    static TextView label(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setPadding(0, dp(c, 20), 0, 0);
        return t;
    }

    static EditText decimalInput(Context c, String initial) {
        EditText e = new EditText(c);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setText(initial);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        return e;
    }

    static EditText wholeInput(Context c, String initial) {
        EditText e = new EditText(c);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setText(initial);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        return e;
    }

    static Button bigButton(Context c, LinearLayout parent, String text) {
        Button b = new Button(c);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 24);
        parent.addView(b, lp);
        return b;
    }

    /** Parses a non-negative decimal like "0.5" or "0,0001"; empty means zero. */
    static BigDecimal parseDecimal(EditText e) {
        String s = e.getText().toString().trim().replace(',', '.');
        if (s.isEmpty()) return BigDecimal.ZERO;
        BigDecimal d = new BigDecimal(s);
        if (d.signum() < 0) throw new NumberFormatException();
        return d;
    }

    static long parseWhole(EditText e) {
        String s = e.getText().toString().trim();
        if (s.isEmpty()) return 0;
        long v = Long.parseLong(s);
        if (v < 0) throw new NumberFormatException();
        return v;
    }
}

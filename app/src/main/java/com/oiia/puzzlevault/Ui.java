package com.oiia.puzzlevault;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small helpers for building the (code-only) UI with a consistent look. */
final class Ui {
    static final int BG = 0xFF0F1419;
    static final int CARD = 0xFF1B2330;
    static final int ACCENT = 0xFF7C5CFF;
    static final int TEXT = 0xFFECEFF4;
    static final int MUTED = 0xFF9AA5B1;
    static final int GOOD = 0xFF3DDC97;
    static final int BAD = 0xFFFF5C7A;

    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        return t;
    }

    static GradientDrawable rounded(Context c, int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    static Button button(Context c, String label, int color) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(rounded(c, color, 12));
        b.setStateListAnimator(null);
        int p = dp(c, 14);
        b.setPadding(p, p, p, p);
        return b;
    }

    static EditText input(Context c, boolean numeric) {
        EditText e = new EditText(c);
        e.setTextColor(TEXT);
        e.setHintTextColor(MUTED);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        e.setGravity(Gravity.CENTER);
        e.setSingleLine(true);
        e.setBackground(rounded(c, CARD, 12));
        int p = dp(c, 14);
        e.setPadding(p, p, p, p);
        if (numeric) {
            e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        } else {
            e.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        }
        return e;
    }

    static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER_HORIZONTAL);
        return l;
    }

    /** Full-width child with a top margin. */
    static LinearLayout.LayoutParams wide(Context c, int topMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, topMarginDp);
        return lp;
    }

    /** Size in px for one cell of a row of {@code n} cells that must fit the screen width. */
    static int cell(Context c, int n, int maxDp, int gapDp) {
        float density = c.getResources().getDisplayMetrics().density;
        int screenDp = (int) (c.getResources().getDisplayMetrics().widthPixels / density);
        int size = Math.min(maxDp, (screenDp - 48) / n - gapDp);
        return dp(c, size);
    }

    static View circle(Context c, int color, boolean outlined) {
        View v = new View(c);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        if (outlined) {
            d.setColor(CARD);
            d.setStroke(dp(c, 2), MUTED);
        } else {
            d.setColor(color);
        }
        v.setBackground(d);
        return v;
    }

    static Button smallButton(Context c, String label, int color) {
        Button b = button(c, label, color);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        int p = dp(c, 8);
        b.setPadding(p, p, p, p);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        return b;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER);
        return l;
    }

    static void shake(View v) {
        v.animate().cancel();
        v.setTranslationX(0);
        final float d = dp(v.getContext(), 12);
        v.animate().translationX(d).setDuration(50).withEndAction(() ->
                v.animate().translationX(-d).setDuration(70).withEndAction(() ->
                        v.animate().translationX(d / 2).setDuration(60).withEndAction(() ->
                                v.animate().translationX(0).setDuration(50).start()
                        ).start()
                ).start()
        ).start();
    }
}

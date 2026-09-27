package com.oiia.puzzlevault;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.Random;

/** Keypad where the issued code is punched in. A correct code triggers a fake load, then the video. */
public class VaultActivity extends Activity {
    private static final int CODE_LENGTH = 6;
    private static final String[] LOADING_LINES = {
            "Verifying code...",
            "Code accepted.",
            "Disengaging vault locks...",
            "Decrypting payload...",
            "Calibrating spin axis...",
            "Warming up the cat...",
            "Almost there...",
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    private final StringBuilder entry = new StringBuilder();
    private TextView display;
    private TextView message;
    private LinearLayout root;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        root.setGravity(Gravity.CENTER);
        int pad = Ui.dp(this, 24);
        root.setPadding(pad, pad, pad, pad);
        setContentView(root);
        showKeypad();
    }

    private void showKeypad() {
        root.removeAllViews();
        root.addView(Ui.text(this, "THE VAULT", 32, Ui.ACCENT, true));
        root.addView(Ui.text(this, "Enter your " + CODE_LENGTH + "-digit code", 15, Ui.MUTED, false),
                Ui.wide(this, 6));

        display = Ui.text(this, "", 40, Ui.TEXT, true);
        display.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        display.setBackground(Ui.rounded(this, Ui.CARD, 14));
        int p = Ui.dp(this, 16);
        display.setPadding(p, p, p, p);
        root.addView(display, Ui.wide(this, 24));

        message = Ui.text(this, " ", 15, Ui.MUTED, false);
        root.addView(message, Ui.wide(this, 10));

        GridLayout pad = new GridLayout(this);
        pad.setColumnCount(3);
        String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "CLR", "0", "OK"};
        int size = Ui.dp(this, 80), m = Ui.dp(this, 6);
        for (String k : keys) {
            int color = k.equals("OK") ? Ui.GOOD : k.equals("CLR") ? Ui.BAD : Ui.CARD;
            Button b = Ui.button(this, k, color);
            b.setTextSize(22);
            b.setOnClickListener(v -> onKey(k));
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = size;
            lp.height = size;
            lp.setMargins(m, m, m, m);
            pad.addView(b, lp);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 16);
        root.addView(pad, lp);
        render();
    }

    private void render() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < CODE_LENGTH; i++) {
            if (i > 0) sb.append(' ');
            sb.append(i < entry.length() ? entry.charAt(i) : '•');
        }
        display.setText(sb.toString());
    }

    private void onKey(String k) {
        if (k.equals("CLR")) {
            entry.setLength(0);
            message.setText(" ");
        } else if (k.equals("OK")) {
            submit();
        } else if (entry.length() < CODE_LENGTH) {
            entry.append(k);
        }
        render();
    }

    private void submit() {
        String issued = CodeStore.get(this);
        if (issued == null) {
            deny("No code has been issued. Solve the puzzles first.");
        } else if (entry.length() < CODE_LENGTH) {
            deny("The code has " + CODE_LENGTH + " digits.");
        } else if (!entry.toString().equals(issued)) {
            deny("ACCESS DENIED");
        } else {
            showLoading();
        }
    }

    private void deny(String msg) {
        message.setTextColor(Ui.BAD);
        message.setText(msg);
        entry.setLength(0);
        Ui.shake(display);
    }

    private void showLoading() {
        root.removeAllViews();
        root.addView(Ui.text(this, "ACCESS GRANTED", 30, Ui.GOOD, true));

        ProgressBar spinner = new ProgressBar(this);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(Ui.ACCENT));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 64));
        slp.topMargin = Ui.dp(this, 32);
        root.addView(spinner, slp);

        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgressTintList(ColorStateList.valueOf(Ui.ACCENT));
        root.addView(bar, Ui.wide(this, 32));

        TextView percent = Ui.text(this, "0%", 18, Ui.TEXT, true);
        root.addView(percent, Ui.wide(this, 8));
        TextView line = Ui.text(this, LOADING_LINES[0], 15, Ui.MUTED, false);
        root.addView(line, Ui.wide(this, 8));

        // Progress creeps forward in uneven steps (with the odd stall) over roughly 8 seconds.
        final int[] progress = {0};
        Runnable tick = new Runnable() {
            @Override
            public void run() {
                int step = rnd.nextInt(10) == 0 ? 0 : 4 + rnd.nextInt(14);
                progress[0] = Math.min(1000, progress[0] + step);
                bar.setProgress(progress[0]);
                percent.setText(progress[0] / 10 + "%");
                int li = Math.min(LOADING_LINES.length - 1,
                        progress[0] * LOADING_LINES.length / 1000);
                line.setText(LOADING_LINES[li]);
                if (progress[0] < 1000) {
                    handler.postDelayed(this, 60 + rnd.nextInt(60));
                } else {
                    line.setText("Unlocked.");
                    handler.postDelayed(() -> {
                        startActivity(new Intent(VaultActivity.this, VideoActivity.class));
                        finish();
                    }, 600);
                }
            }
        };
        handler.postDelayed(tick, 400);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}

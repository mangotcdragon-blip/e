package com.oiia.puzzlevault;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Runs the player through every puzzle stage (in a shuffled order). Each solved stage
 * unlocks one digit of a freshly generated vault code; once all are solved the full code
 * is revealed and stored for the vault.
 */
public class PuzzleActivity extends Activity implements Stage.Host {
    private final Random rnd = new Random();
    private final List<Stage> stages = new ArrayList<>();
    private String code;
    private int current = 0;
    private Stage active;

    private TextView progressLabel;
    private TextView codeSlots;
    private ProgressBar progressBar;
    private FrameLayout body;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        stages.add(new MathStage(this, rnd));
        stages.add(new SequenceStage(this, rnd));
        stages.add(new AnagramStage(this, rnd));
        stages.add(new MemoryStage(this, rnd));
        stages.add(new LightsOutStage(this, rnd));
        stages.add(new CipherStage(this, rnd));
        Collections.shuffle(stages, rnd);

        SecureRandom sr = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        sb.append(1 + sr.nextInt(9));
        for (int i = 1; i < stages.size(); i++) sb.append(sr.nextInt(10));
        code = sb.toString();

        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        int pad = Ui.dp(this, 20);
        root.setPadding(pad, pad, pad, pad);

        progressLabel = Ui.text(this, "", 14, Ui.MUTED, false);
        root.addView(progressLabel);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(stages.size());
        progressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        root.addView(progressBar, Ui.wide(this, 6));

        codeSlots = Ui.text(this, "", 30, Ui.GOOD, true);
        codeSlots.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        root.addView(codeSlots, Ui.wide(this, 14));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        body = new FrameLayout(this);
        scroll.addView(body);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = Ui.dp(this, 16);
        root.addView(scroll, lp);

        setContentView(root);
        showStage();
    }

    private void updateHeader() {
        progressLabel.setText(current < stages.size()
                ? "Puzzle " + (current + 1) + " of " + stages.size()
                : "All puzzles solved");
        progressBar.setProgress(current);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < code.length(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(i < current ? code.charAt(i) : '_');
        }
        codeSlots.setText(sb.toString());
    }

    private void setBody(View v) {
        hideKeyboard();
        body.removeAllViews();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        body.addView(v, lp);
        v.setAlpha(0f);
        v.animate().alpha(1f).setDuration(250).start();
    }

    private void showStage() {
        updateHeader();
        active = stages.get(current);
        LinearLayout col = Ui.column(this);
        col.addView(Ui.text(this, active.title(), 26, Ui.ACCENT, true));
        col.addView(Ui.text(this, active.instructions(), 15, Ui.MUTED, false), Ui.wide(this, 6));
        LinearLayout.LayoutParams lp = Ui.wide(this, 24);
        col.addView(active.build(this), lp);
        setBody(col);
    }

    @Override
    public void onStageSolved() {
        active.dispose();
        active = null;
        current++;
        updateHeader();
        if (current >= stages.size()) {
            showFinished();
            return;
        }
        LinearLayout col = Ui.column(this);
        col.addView(Ui.text(this, "Solved!", 30, Ui.GOOD, true));
        col.addView(Ui.text(this, "Digit " + current + " unlocked:", 16, Ui.MUTED, false),
                Ui.wide(this, 16));
        TextView digit = Ui.text(this, String.valueOf(code.charAt(current - 1)), 72, Ui.TEXT, true);
        col.addView(digit, Ui.wide(this, 4));
        digit.setScaleX(0.3f);
        digit.setScaleY(0.3f);
        digit.animate().scaleX(1f).scaleY(1f).setDuration(350).start();
        Button next = Ui.button(this, "Next puzzle", Ui.ACCENT);
        next.setOnClickListener(v -> showStage());
        col.addView(next, Ui.wide(this, 28));
        setBody(col);
    }

    private void showFinished() {
        CodeStore.save(this, code);
        LinearLayout col = Ui.column(this);
        col.addView(Ui.text(this, "Vault code issued", 28, Ui.GOOD, true));
        col.addView(Ui.text(this, "Memorise it (or write it down). You'll need to punch it into the vault.",
                15, Ui.MUTED, false), Ui.wide(this, 8));
        TextView big = Ui.text(this, code, 54, Ui.TEXT, true);
        big.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        big.setLetterSpacing(0.15f);
        big.setBackground(Ui.rounded(this, Ui.CARD, 16));
        int p = Ui.dp(this, 18);
        big.setPadding(p, p, p, p);
        col.addView(big, Ui.wide(this, 24));
        Button go = Ui.button(this, "Go to the vault", Ui.ACCENT);
        go.setOnClickListener(v -> {
            startActivity(new Intent(this, VaultActivity.class));
            finish();
        });
        col.addView(go, Ui.wide(this, 28));
        setBody(col);
    }

    private void hideKeyboard() {
        View f = getCurrentFocus();
        if (f != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(f.getWindowToken(), 0);
        }
    }

    @Override
    protected void onDestroy() {
        if (active != null) active.dispose();
        super.onDestroy();
    }
}

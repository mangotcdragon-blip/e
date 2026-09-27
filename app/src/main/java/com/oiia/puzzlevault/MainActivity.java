package com.oiia.puzzlevault;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private TextView status;
    private Button resume;
    private Button start;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        root.setGravity(Gravity.CENTER);
        int pad = Ui.dp(this, 28);
        root.setPadding(pad, pad, pad, pad);

        root.addView(Ui.text(this, "PUZZLE VAULT", 36, Ui.ACCENT, true));
        root.addView(Ui.text(this,
                "Every digit of the vault code is locked behind " + Plan.STEPS + " puzzles: logic, "
                        + "strategy and deduction, getting harder as you go.\n"
                        + "Earn all " + Plan.DIGITS + " digits, then punch the code into the vault.",
                16, Ui.MUTED, false), Ui.wide(this, 16));

        resume = Ui.button(this, "Continue", Ui.ACCENT);
        resume.setOnClickListener(v -> startActivity(new Intent(this, PuzzleActivity.class)));
        root.addView(resume, Ui.wide(this, 36));

        start = Ui.button(this, "Start puzzles", Ui.ACCENT);
        start.setOnClickListener(v -> {
            if (CodeStore.hasRun(this)) {
                new AlertDialog.Builder(this)
                        .setTitle("Start over?")
                        .setMessage("Your current progress will be lost.")
                        .setPositiveButton("Start over", (d, w) -> startNew())
                        .setNegativeButton("Cancel", null)
                        .show();
            } else {
                startNew();
            }
        });
        root.addView(start, Ui.wide(this, 14));

        Button vault = Ui.button(this, "Enter vault code", Ui.CARD);
        vault.setOnClickListener(v -> startActivity(new Intent(this, VaultActivity.class)));
        root.addView(vault, Ui.wide(this, 14));

        status = Ui.text(this, "", 14, Ui.MUTED, false);
        root.addView(status, Ui.wide(this, 24));

        setContentView(root);
    }

    private void startNew() {
        startActivity(new Intent(this, PuzzleActivity.class)
                .putExtra(PuzzleActivity.EXTRA_NEW_RUN, true));
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean inRun = CodeStore.hasRun(this);
        resume.setVisibility(inRun ? View.VISIBLE : View.GONE);
        start.setText(inRun ? "Start a new run" : "Start puzzles");
        start.setBackground(Ui.rounded(this, inRun ? Ui.CARD : Ui.ACCENT, 12));
        if (inRun) {
            int idx = CodeStore.runIndex(this);
            resume.setText("Continue (digit " + (idx / Plan.STEPS + 1) + ", step "
                    + (idx % Plan.STEPS + 1) + ")");
            status.setText("Run in progress: " + idx + " of " + Plan.TOTAL + " puzzles solved.");
        } else {
            status.setText(CodeStore.get(this) == null
                    ? "No code issued yet."
                    : "A vault code has been issued. Do you remember it?");
        }
    }
}

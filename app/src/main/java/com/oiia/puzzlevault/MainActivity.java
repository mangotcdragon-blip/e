package com.oiia.puzzlevault;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        root.setGravity(Gravity.CENTER);
        int pad = Ui.dp(this, 28);
        root.setPadding(pad, pad, pad, pad);

        TextView title = Ui.text(this, "PUZZLE VAULT", 36, Ui.ACCENT, true);
        root.addView(title);

        TextView sub = Ui.text(this,
                "Solve every puzzle to earn a one-time vault code.\n"
                        + "Punch it into the vault to unlock what's inside.",
                16, Ui.MUTED, false);
        root.addView(sub, Ui.wide(this, 16));

        Button start = Ui.button(this, "Start puzzles", Ui.ACCENT);
        start.setOnClickListener(v -> startActivity(new Intent(this, PuzzleActivity.class)));
        root.addView(start, Ui.wide(this, 40));

        Button vault = Ui.button(this, "Enter vault code", Ui.CARD);
        vault.setOnClickListener(v -> startActivity(new Intent(this, VaultActivity.class)));
        root.addView(vault, Ui.wide(this, 14));

        status = Ui.text(this, "", 14, Ui.MUTED, false);
        root.addView(status, Ui.wide(this, 24));

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        status.setText(CodeStore.get(this) == null
                ? "No code issued yet."
                : "A vault code has been issued. Do you remember it?");
    }
}

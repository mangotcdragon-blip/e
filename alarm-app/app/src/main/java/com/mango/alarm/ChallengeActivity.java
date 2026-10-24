package com.mango.alarm;

import android.app.Activity;
import android.app.KeyguardManager;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Random;

/** Shown over the lock screen while ringing. Solve a small sum to turn the alarm off. */
public class ChallengeActivity extends Activity {
    private final Random random = new Random();
    private int answer;
    private TextView problem;
    private TextView feedback;
    private EditText input;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if (!AlarmService.isRinging()) {
            finish();
            return;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(64, 64, 64, 64);
        root.setBackgroundColor(Color.parseColor("#121212"));

        TextView title = new TextView(this);
        title.setText("Solve to turn off the alarm");
        title.setTextColor(Color.LTGRAY);
        title.setTextSize(20);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        problem = new TextView(this);
        problem.setTextColor(Color.WHITE);
        problem.setTextSize(56);
        problem.setGravity(Gravity.CENTER);
        problem.setPadding(0, 48, 0, 48);
        root.addView(problem);

        input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.GRAY);
        input.setHint("Answer");
        input.setTextSize(32);
        input.setGravity(Gravity.CENTER);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setOnEditorActionListener((v, actionId, event) -> {
            check();
            return true;
        });
        root.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        Button submit = new Button(this);
        submit.setText("Turn off");
        submit.setTextSize(20);
        submit.setOnClickListener(v -> check());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 32;
        root.addView(submit, lp);

        feedback = new TextView(this);
        feedback.setTextColor(Color.parseColor("#FF6E6E"));
        feedback.setTextSize(18);
        feedback.setGravity(Gravity.CENTER);
        feedback.setPadding(0, 32, 0, 0);
        root.addView(feedback);

        setContentView(root);
        newProblem();

        input.requestFocus();
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
    }

    private void newProblem() {
        int a = 10 + random.nextInt(40);
        int b = 10 + random.nextInt(40);
        answer = a + b;
        problem.setText(a + " + " + b);
        input.setText("");
    }

    private void check() {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        int value;
        try {
            value = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            value = Integer.MIN_VALUE;
        }
        if (value == answer) {
            AlarmService.stop(this);
            InputMethodManager imm = getSystemService(InputMethodManager.class);
            imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
            KeyguardManager km = getSystemService(KeyguardManager.class);
            if (km.isKeyguardLocked()) km.requestDismissKeyguard(this, null);
            finish();
        } else {
            feedback.setText("Wrong, try this one");
            newProblem();
        }
    }

    @Override
    public void onBackPressed() {
        // No escaping without solving.
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Swallow volume keys so they can't be used to quiet the alarm from here.
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}

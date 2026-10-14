package com.soundtimer;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;

/** Home screen: pick Repeat or Countdown. */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = Ui.column(this);
        root.addView(Ui.title(this, "Sound Timer"));

        Button repeat = Ui.bigButton(this, root, "Repeat");
        repeat.setOnClickListener(v -> startActivity(new Intent(this, RepeatActivity.class)));
        root.addView(Ui.label(this, "Play the sound a set number of times, a set interval apart."));

        Button countdown = Ui.bigButton(this, root, "Countdown");
        countdown.setOnClickListener(v -> startActivity(new Intent(this, CountdownActivity.class)));
        root.addView(Ui.label(this, "Play the sound once, exactly when a timer runs out."));

        setContentView(Ui.scroll(this, root));
    }
}

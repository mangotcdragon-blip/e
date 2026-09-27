package com.oiia.puzzlevault;

import android.content.Context;
import android.view.View;
import android.widget.Toast;

import java.util.Random;

/** One puzzle in the run. Each solved stage unlocks one digit of the vault code. */
abstract class Stage {
    interface Host {
        void onStageSolved();
    }

    protected final Host host;
    protected final Random rnd;

    Stage(Host host, Random rnd) {
        this.host = host;
        this.rnd = rnd;
    }

    abstract String title();

    abstract String instructions();

    abstract View build(Context c);

    /** Called when the stage is removed from screen; stop any pending callbacks. */
    void dispose() {}

    protected void solved() {
        host.onStageSolved();
    }

    protected void wrong(View v, String msg) {
        Ui.shake(v);
        Toast.makeText(v.getContext(), msg, Toast.LENGTH_SHORT).show();
    }

    /** Parses an int typed by the player, or returns null if it isn't a number. */
    protected static Integer parse(CharSequence s) {
        try {
            return Integer.parseInt(s.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

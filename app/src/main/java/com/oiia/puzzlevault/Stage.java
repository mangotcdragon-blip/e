package com.oiia.puzzlevault;

import android.content.Context;
import android.view.View;
import android.widget.Toast;

import java.util.Random;

/**
 * One puzzle in the run. Every digit of the vault code takes several stages to unlock.
 * {@code level} (0 = first digit ... 5 = last digit) scales the difficulty.
 */
abstract class Stage {
    interface Host {
        void onStageSolved();
    }

    protected final Host host;
    protected final Random rnd;
    protected final int level;

    Stage(Host host, Random rnd, int level) {
        this.host = host;
        this.rnd = rnd;
        this.level = level;
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
        toast(v.getContext(), msg);
    }

    protected static void toast(Context c, String msg) {
        Toast.makeText(c, msg, Toast.LENGTH_SHORT).show();
    }

    protected void shuffle(int[] a) {
        for (int i = a.length - 1; i > 0; i--) {
            int j = rnd.nextInt(i + 1);
            int t = a[i];
            a[i] = a[j];
            a[j] = t;
        }
    }
}

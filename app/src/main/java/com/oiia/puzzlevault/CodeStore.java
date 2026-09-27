package com.oiia.puzzlevault;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Persists the vault code that was issued after the last completed run, plus the
 * in-progress run (its seed, pending code and how many puzzles are solved) so a
 * long run survives the app being closed.
 */
final class CodeStore {
    private static final String PREFS = "vault";
    private static final String KEY_CODE = "code";
    private static final String KEY_RUN_SEED = "run_seed";
    private static final String KEY_RUN_CODE = "run_code";
    private static final String KEY_RUN_INDEX = "run_index";

    private CodeStore() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Issues the final code and ends the run. */
    static void save(Context c, String code) {
        prefs(c).edit()
                .putString(KEY_CODE, code)
                .remove(KEY_RUN_SEED).remove(KEY_RUN_CODE).remove(KEY_RUN_INDEX)
                .apply();
    }

    /** Returns the issued code, or null if no run has been completed yet. */
    static String get(Context c) {
        return prefs(c).getString(KEY_CODE, null);
    }

    static boolean hasRun(Context c) {
        return prefs(c).contains(KEY_RUN_SEED) && prefs(c).contains(KEY_RUN_CODE);
    }

    static void startRun(Context c, long seed, String code) {
        prefs(c).edit()
                .putLong(KEY_RUN_SEED, seed)
                .putString(KEY_RUN_CODE, code)
                .putInt(KEY_RUN_INDEX, 0)
                .apply();
    }

    static long runSeed(Context c) {
        return prefs(c).getLong(KEY_RUN_SEED, 0);
    }

    static String runCode(Context c) {
        return prefs(c).getString(KEY_RUN_CODE, null);
    }

    static int runIndex(Context c) {
        return prefs(c).getInt(KEY_RUN_INDEX, 0);
    }

    static void setRunIndex(Context c, int index) {
        prefs(c).edit().putInt(KEY_RUN_INDEX, index).apply();
    }
}

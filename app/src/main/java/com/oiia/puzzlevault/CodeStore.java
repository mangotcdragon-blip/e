package com.oiia.puzzlevault;

import android.content.Context;
import android.content.SharedPreferences;

/** Persists the vault code that was issued after the last completed puzzle run. */
final class CodeStore {
    private static final String PREFS = "vault";
    private static final String KEY_CODE = "code";

    private CodeStore() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static void save(Context c, String code) {
        prefs(c).edit().putString(KEY_CODE, code).apply();
    }

    /** Returns the issued code, or null if no puzzle run has been completed yet. */
    static String get(Context c) {
        return prefs(c).getString(KEY_CODE, null);
    }
}

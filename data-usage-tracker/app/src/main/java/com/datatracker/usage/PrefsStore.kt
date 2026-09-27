package com.datatracker.usage

import android.content.Context

class PrefsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var allowanceBytes: Long
        get() = prefs.getLong(KEY_ALLOWANCE, DEFAULT_ALLOWANCE_BYTES)
        set(value) = prefs.edit().putLong(KEY_ALLOWANCE, value).apply()

    var resetDay: Int
        get() = prefs.getInt(KEY_RESET_DAY, 1)
        set(value) = prefs.edit().putInt(KEY_RESET_DAY, value.coerceIn(1, 31)).apply()

    var resetHour: Int
        get() = prefs.getInt(KEY_RESET_HOUR, 0)
        set(value) = prefs.edit().putInt(KEY_RESET_HOUR, value.coerceIn(0, 23)).apply()

    var resetMinute: Int
        get() = prefs.getInt(KEY_RESET_MINUTE, 0)
        set(value) = prefs.edit().putInt(KEY_RESET_MINUTE, value.coerceIn(0, 59)).apply()

    var rolloverEnabled: Boolean
        get() = prefs.getBoolean(KEY_ROLLOVER, true)
        set(value) = prefs.edit().putBoolean(KEY_ROLLOVER, value).apply()

    var isConfigured: Boolean
        get() = prefs.getBoolean(KEY_CONFIGURED, false)
        set(value) = prefs.edit().putBoolean(KEY_CONFIGURED, value).apply()

    /** Set once, the first time the user ever saves settings. Currently informational only. */
    var firstConfiguredAtMillis: Long
        get() = prefs.getLong(KEY_FIRST_CONFIGURED_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_FIRST_CONFIGURED_AT, value).apply()

    /** The running rollover balance carried into the current cycle. See [RolloverLedger]. */
    var carriedRolloverBytes: Long
        get() = prefs.getLong(KEY_CARRIED_ROLLOVER, 0L)
        set(value) = prefs.edit().putLong(KEY_CARRIED_ROLLOVER, value).apply()

    /** The start (epoch millis) of the most recent cycle the ledger has folded into
     * [carriedRolloverBytes]. 0 means the ledger hasn't been bootstrapped yet. */
    var lastProcessedCycleStartMillis: Long
        get() = prefs.getLong(KEY_LAST_PROCESSED_CYCLE, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_PROCESSED_CYCLE, value).apply()

    /** Overwrites the ledger directly (e.g. the user syncing it to a balance their carrier
     * reports) and anchors it to the given cycle start so future cycles compound from here. */
    fun overrideCarriedRollover(newBalanceBytes: Long, asOfCycleStartMillis: Long) {
        prefs.edit()
            .putLong(KEY_CARRIED_ROLLOVER, newBalanceBytes.coerceAtLeast(0))
            .putLong(KEY_LAST_PROCESSED_CYCLE, asOfCycleStartMillis)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "data_tracker_prefs"
        private const val KEY_ALLOWANCE = "allowance_bytes"
        private const val KEY_RESET_DAY = "reset_day"
        private const val KEY_RESET_HOUR = "reset_hour"
        private const val KEY_RESET_MINUTE = "reset_minute"
        private const val KEY_ROLLOVER = "rollover_enabled"
        private const val KEY_CONFIGURED = "is_configured"
        private const val KEY_FIRST_CONFIGURED_AT = "first_configured_at"
        private const val KEY_CARRIED_ROLLOVER = "carried_rollover_bytes"
        private const val KEY_LAST_PROCESSED_CYCLE = "last_processed_cycle_start"
        const val DEFAULT_ALLOWANCE_BYTES = 10L * 1024 * 1024 * 1024 // 10 GB
    }
}

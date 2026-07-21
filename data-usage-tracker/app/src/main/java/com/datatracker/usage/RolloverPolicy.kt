package com.datatracker.usage

/**
 * Pure decision logic, kept separate from [DataUsageRepository] so it can be unit-tested
 * without touching any Android API.
 */
object RolloverPolicy {

    /**
     * Rollover should only apply once the user has actually completed a full billing cycle
     * under a configured plan -- crediting a brand-new setup with "all of your allowance
     * unused last cycle" (because there's no real previous cycle to compare against) would
     * silently double the total the very first time someone sets up the app.
     */
    fun shouldApplyRollover(
        rolloverEnabled: Boolean,
        firstConfiguredAtMillis: Long,
        previousCycleStartMillis: Long
    ): Boolean {
        if (!rolloverEnabled) return false
        if (firstConfiguredAtMillis <= 0L) return false
        return firstConfiguredAtMillis <= previousCycleStartMillis
    }
}

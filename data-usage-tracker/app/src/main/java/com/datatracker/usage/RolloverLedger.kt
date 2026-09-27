package com.datatracker.usage

/**
 * Pure compounding logic for the rollover balance, kept free of Android APIs so it can be
 * unit-tested directly. A single "look back one cycle" computation (the previous design) can't
 * represent a balance that's been quietly building up over several under-used cycles in a row --
 * this walks forward through every cycle boundary crossed since it was last run, folding each
 * one's leftover into the next, so the carried balance behaves like a real running total instead
 * of resetting itself every time it's read.
 */
object RolloverLedger {

    data class Result(val carriedRolloverBytes: Long, val lastProcessedCycleStartMillis: Long)

    /**
     * Advances the ledger from [lastProcessedCycleStartMillis] up to [currentCycleStartMillis].
     *
     * @param nextCycleBoundary given a cycle's start, returns the following cycle's start.
     * @param queryUsedBytes returns bytes used in [start, end), or null if that reading
     *   genuinely couldn't be obtained -- in which case that segment's balance carries forward
     *   unchanged rather than guessing, the same principle as the single-cycle version before it.
     */
    fun advance(
        lastProcessedCycleStartMillis: Long,
        currentCycleStartMillis: Long,
        allowanceBytes: Long,
        rolloverEnabled: Boolean,
        carriedRolloverBytes: Long,
        nextCycleBoundary: (afterMillis: Long) -> Long,
        queryUsedBytes: (startMillis: Long, endMillis: Long) -> Long?
    ): Result {
        if (!rolloverEnabled) {
            return Result(0L, currentCycleStartMillis)
        }
        // No ledger yet (fresh install): nothing to compound forward from. The caller is
        // expected to bootstrap an initial balance itself (see DataUsageRepository), since
        // older cycles generally aren't reliably queryable on a device NetworkStatsManager has
        // limited retention on.
        if (lastProcessedCycleStartMillis <= 0L) {
            return Result(carriedRolloverBytes, currentCycleStartMillis)
        }
        if (lastProcessedCycleStartMillis >= currentCycleStartMillis) {
            return Result(carriedRolloverBytes, lastProcessedCycleStartMillis)
        }

        var cycleStart = lastProcessedCycleStartMillis
        var rollover = carriedRolloverBytes
        while (cycleStart < currentCycleStartMillis) {
            val cycleEnd = nextCycleBoundary(cycleStart)
            val used = queryUsedBytes(cycleStart, cycleEnd)
            if (used != null) {
                rollover = (allowanceBytes + rollover - used).coerceAtLeast(0)
            }
            cycleStart = cycleEnd
        }
        return Result(rollover, currentCycleStartMillis)
    }
}

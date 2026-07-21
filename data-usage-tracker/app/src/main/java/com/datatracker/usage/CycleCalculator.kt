package com.datatracker.usage

import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Pure date math for billing-cycle boundaries, deliberately kept free of any Android API so it
 * can be unit-tested directly on the JVM. Cycle usage totals are always computed on demand from
 * [now] and the configured reset day/time -- nothing about a cycle's boundaries is persisted, so
 * there's no separate state that can drift out of sync with the clock.
 */
object CycleCalculator {

    data class Cycle(val startEpochMillis: Long, val endEpochMillis: Long)

    /**
     * Returns the currently active billing cycle (start = most recent reset, end = [now]) and
     * the cycle immediately before it. Handles months shorter than [resetDay] by clamping to the
     * last day of that month (e.g. day 31 in February uses the 28th/29th).
     */
    fun currentAndPreviousCycle(
        now: ZonedDateTime,
        resetDay: Int,
        resetHour: Int,
        resetMinute: Int
    ): Pair<Cycle, Cycle> {
        val zone = now.zone
        val thisMonthReset = resetInstantFor(YearMonth.from(now), resetDay, resetHour, resetMinute, zone)
        val currentStart = if (!now.isBefore(thisMonthReset)) {
            thisMonthReset
        } else {
            resetInstantFor(YearMonth.from(now).minusMonths(1), resetDay, resetHour, resetMinute, zone)
        }
        val previousStart = resetInstantFor(
            YearMonth.from(currentStart).minusMonths(1), resetDay, resetHour, resetMinute, zone
        )

        val current = Cycle(currentStart.toInstant().toEpochMilli(), now.toInstant().toEpochMilli())
        val previous = Cycle(previousStart.toInstant().toEpochMilli(), currentStart.toInstant().toEpochMilli())
        return current to previous
    }

    private fun resetInstantFor(
        yearMonth: YearMonth,
        day: Int,
        hour: Int,
        minute: Int,
        zone: ZoneId
    ): ZonedDateTime {
        val clampedDay = day.coerceIn(1, yearMonth.lengthOfMonth())
        return ZonedDateTime.of(
            yearMonth.year, yearMonth.monthValue, clampedDay, hour, minute, 0, 0, zone
        )
    }
}

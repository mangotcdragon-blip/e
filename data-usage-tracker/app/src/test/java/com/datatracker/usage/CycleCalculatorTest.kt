package com.datatracker.usage

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class CycleCalculatorTest {

    private val zone = ZoneId.of("UTC")

    @Test
    fun `after this month's reset, current cycle starts this month`() {
        val now = ZonedDateTime.of(2026, 7, 20, 10, 0, 0, 0, zone)
        val (current, previous) = CycleCalculator.currentAndPreviousCycle(now, resetDay = 15, resetHour = 0, resetMinute = 0)

        assertEquals(ZonedDateTime.of(2026, 7, 15, 0, 0, 0, 0, zone).toInstant().toEpochMilli(), current.startEpochMillis)
        assertEquals(now.toInstant().toEpochMilli(), current.endEpochMillis)
        assertEquals(ZonedDateTime.of(2026, 6, 15, 0, 0, 0, 0, zone).toInstant().toEpochMilli(), previous.startEpochMillis)
        assertEquals(current.startEpochMillis, previous.endEpochMillis)
    }

    @Test
    fun `before this month's reset, current cycle started last month`() {
        val now = ZonedDateTime.of(2026, 7, 10, 10, 0, 0, 0, zone)
        val (current, previous) = CycleCalculator.currentAndPreviousCycle(now, resetDay = 15, resetHour = 0, resetMinute = 0)

        assertEquals(ZonedDateTime.of(2026, 6, 15, 0, 0, 0, 0, zone).toInstant().toEpochMilli(), current.startEpochMillis)
        assertEquals(ZonedDateTime.of(2026, 5, 15, 0, 0, 0, 0, zone).toInstant().toEpochMilli(), previous.startEpochMillis)
    }

    @Test
    fun `reset day beyond a short month clamps to the last day of that month`() {
        // Reset day 31 requested; February only has 28 days in 2026 (not a leap year).
        // "now" must be after the clamped Feb 28 reset for it to be the active cycle start.
        val now = ZonedDateTime.of(2026, 3, 5, 10, 0, 0, 0, zone)
        val (current, _) = CycleCalculator.currentAndPreviousCycle(now, resetDay = 31, resetHour = 0, resetMinute = 0)

        assertEquals(ZonedDateTime.of(2026, 2, 28, 0, 0, 0, 0, zone).toInstant().toEpochMilli(), current.startEpochMillis)
    }

    @Test
    fun `reset day 31 clamps correctly across a leap February`() {
        val now = ZonedDateTime.of(2028, 2, 29, 10, 0, 0, 0, zone) // 2028 is a leap year
        val (current, _) = CycleCalculator.currentAndPreviousCycle(now, resetDay = 31, resetHour = 0, resetMinute = 0)

        assertEquals(ZonedDateTime.of(2028, 2, 29, 0, 0, 0, 0, zone).toInstant().toEpochMilli(), current.startEpochMillis)
    }

    @Test
    fun `exactly at reset instant counts as the new cycle`() {
        val now = ZonedDateTime.of(2026, 7, 15, 9, 30, 0, 0, zone)
        val (current, _) = CycleCalculator.currentAndPreviousCycle(now, resetDay = 15, resetHour = 9, resetMinute = 30)

        assertEquals(now.toInstant().toEpochMilli(), current.startEpochMillis)
    }

    @Test
    fun `one minute before reset instant still counts as the previous cycle`() {
        val now = ZonedDateTime.of(2026, 7, 15, 9, 29, 0, 0, zone)
        val (current, _) = CycleCalculator.currentAndPreviousCycle(now, resetDay = 15, resetHour = 9, resetMinute = 30)

        assertEquals(ZonedDateTime.of(2026, 6, 15, 9, 30, 0, 0, zone).toInstant().toEpochMilli(), current.startEpochMillis)
    }

    @Test
    fun `year boundary rolls over correctly`() {
        val now = ZonedDateTime.of(2027, 1, 5, 10, 0, 0, 0, zone)
        val (current, previous) = CycleCalculator.currentAndPreviousCycle(now, resetDay = 15, resetHour = 0, resetMinute = 0)

        assertEquals(ZonedDateTime.of(2026, 12, 15, 0, 0, 0, 0, zone).toInstant().toEpochMilli(), current.startEpochMillis)
        assertEquals(ZonedDateTime.of(2026, 11, 15, 0, 0, 0, 0, zone).toInstant().toEpochMilli(), previous.startEpochMillis)
    }
}

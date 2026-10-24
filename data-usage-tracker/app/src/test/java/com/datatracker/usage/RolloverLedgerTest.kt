package com.datatracker.usage

import org.junit.Assert.assertEquals
import org.junit.Test

class RolloverLedgerTest {

    private val ONE_DAY = 24 * 60 * 60 * 1000L
    private val GB = 1024L * 1024 * 1024

    /** A simple fixed-interval boundary stepper for tests, standing in for CycleCalculator. */
    private fun stepper(intervalMillis: Long): (Long) -> Long = { it + intervalMillis }
    private fun backStepper(intervalMillis: Long): (Long) -> Long = { it - intervalMillis }

    @Test
    fun `rollover disabled always resets to zero`() {
        val result = RolloverLedger.advance(
            lastProcessedCycleStartMillis = 1000L,
            currentCycleStartMillis = 5000L,
            allowanceBytes = 1 * GB,
            rolloverEnabled = false,
            carriedRolloverBytes = 5 * GB, // stale balance from when it used to be enabled
            nextCycleBoundary = stepper(ONE_DAY),
            queryUsedBytes = { _, _ -> 0L }
        )
        assertEquals(0L, result.carriedRolloverBytes)
        assertEquals(5000L, result.lastProcessedCycleStartMillis)
    }

    @Test
    fun `no ledger yet passes the bootstrapped balance through unchanged`() {
        val result = RolloverLedger.advance(
            lastProcessedCycleStartMillis = 0L,
            currentCycleStartMillis = 5000L,
            allowanceBytes = 1 * GB,
            rolloverEnabled = true,
            carriedRolloverBytes = 950_000_000L, // bootstrapped by the caller
            nextCycleBoundary = stepper(ONE_DAY),
            queryUsedBytes = { _, _ -> error("should not be queried on first bootstrap") }
        )
        assertEquals(950_000_000L, result.carriedRolloverBytes)
        assertEquals(5000L, result.lastProcessedCycleStartMillis)
    }

    @Test
    fun `still within the last processed cycle makes no changes`() {
        val result = RolloverLedger.advance(
            lastProcessedCycleStartMillis = 5000L,
            currentCycleStartMillis = 5000L,
            allowanceBytes = 1 * GB,
            rolloverEnabled = true,
            carriedRolloverBytes = 500L,
            nextCycleBoundary = stepper(ONE_DAY),
            queryUsedBytes = { _, _ -> error("should not be queried") }
        )
        assertEquals(500L, result.carriedRolloverBytes)
        assertEquals(5000L, result.lastProcessedCycleStartMillis)
    }

    @Test
    fun `single elapsed cycle folds its leftover into the balance`() {
        // Allowance 1 GB, carried in 0.2 GB, used 0.5 GB this cycle -> leftover 1 + 0.2 - 0.5 = 0.7 GB
        val allowance = GB
        val carriedIn = (0.2 * GB).toLong()
        val used = (0.5 * GB).toLong()

        val result = RolloverLedger.advance(
            lastProcessedCycleStartMillis = ONE_DAY,
            currentCycleStartMillis = 2 * ONE_DAY,
            allowanceBytes = allowance,
            rolloverEnabled = true,
            carriedRolloverBytes = carriedIn,
            nextCycleBoundary = stepper(ONE_DAY),
            queryUsedBytes = { _, _ -> used }
        )

        assertEquals(allowance + carriedIn - used, result.carriedRolloverBytes)
        assertEquals(2 * ONE_DAY, result.lastProcessedCycleStartMillis)
    }

    @Test
    fun `multiple skipped cycles compound in sequence rather than resetting`() {
        // Three consecutive under-used cycles: allowance 1 GB each, using 0.3 GB every time.
        // Cycle 1: 1.0 + 0 - 0.3 = 0.7
        // Cycle 2: 1.0 + 0.7 - 0.3 = 1.4
        // Cycle 3: 1.0 + 1.4 - 0.3 = 2.1
        val allowance = GB
        val used = (0.3 * GB).toLong()

        val result = RolloverLedger.advance(
            lastProcessedCycleStartMillis = ONE_DAY,
            currentCycleStartMillis = 4 * ONE_DAY,
            allowanceBytes = allowance,
            rolloverEnabled = true,
            carriedRolloverBytes = 0L,
            nextCycleBoundary = stepper(ONE_DAY),
            queryUsedBytes = { _, _ -> used }
        )

        val expected = allowance - used + allowance - used + allowance - used
        assertEquals(expected, result.carriedRolloverBytes)
        assertEquals(4 * ONE_DAY, result.lastProcessedCycleStartMillis)
    }

    @Test
    fun `overuse in one cycle can zero out an accumulated balance but not go negative`() {
        // Carried in 2 GB, allowance 1 GB, but used 5 GB this cycle.
        val result = RolloverLedger.advance(
            lastProcessedCycleStartMillis = ONE_DAY,
            currentCycleStartMillis = 2 * ONE_DAY,
            allowanceBytes = GB,
            rolloverEnabled = true,
            carriedRolloverBytes = 2 * GB,
            nextCycleBoundary = stepper(ONE_DAY),
            queryUsedBytes = { _, _ -> 5 * GB }
        )
        assertEquals(0L, result.carriedRolloverBytes)
    }

    @Test
    fun `a segment with unavailable usage data carries the balance forward unchanged`() {
        // Two elapsed cycles: first one's usage is unreadable (null), second is readable.
        var call = 0
        val result = RolloverLedger.advance(
            lastProcessedCycleStartMillis = ONE_DAY,
            currentCycleStartMillis = 3 * ONE_DAY,
            allowanceBytes = GB,
            rolloverEnabled = true,
            carriedRolloverBytes = (0.5 * GB).toLong(),
            nextCycleBoundary = stepper(ONE_DAY),
            queryUsedBytes = { _, _ ->
                call++
                if (call == 1) null else (0.4 * GB).toLong()
            }
        )
        // First segment: balance unchanged (0.5 GB). Second segment: 1 + 0.5 - 0.4 = 1.1 GB.
        val expected = GB + (0.5 * GB).toLong() - (0.4 * GB).toLong()
        assertEquals(expected, result.carriedRolloverBytes)
        assertEquals(3 * ONE_DAY, result.lastProcessedCycleStartMillis)
    }

    @Test
    fun `bootstrapFromHistory finds where usage history begins and compounds forward from it`() {
        // History begins at cycle boundary 100 days in (standing in for a real epoch-millis
        // date, always far from zero -- 0 itself is reserved as the "not bootstrapped" sentinel,
        // see advance()); five cycles each used 0.3 GB of a 1 GB allowance, netting +0.7 GB every
        // time.
        val historyBegins = 100 * ONE_DAY
        val current = historyBegins + 5 * ONE_DAY
        val usedPerCycle = (0.3 * GB).toLong()

        val result = RolloverLedger.bootstrapFromHistory(
            currentCycleStartMillis = current,
            allowanceBytes = GB,
            rolloverEnabled = true,
            maxLookbackCycles = 10,
            previousCycleBoundary = backStepper(ONE_DAY),
            nextCycleBoundary = stepper(ONE_DAY),
            queryUsageBeforeMillis = { before -> if (before <= historyBegins) 0L else 999L },
            queryUsedBytes = { _, _ -> usedPerCycle }
        )

        assertEquals(5, result?.cyclesReconstructed)
        assertEquals(historyBegins, result?.earliestCycleStartMillis)
        assertEquals(current, result?.lastProcessedCycleStartMillis)
        val expected = (GB - usedPerCycle) * 5
        assertEquals(expected, result?.carriedRolloverBytes)
    }

    @Test
    fun `bootstrapFromHistory gives up when history never bottoms out within the lookback cap`() {
        val result = RolloverLedger.bootstrapFromHistory(
            currentCycleStartMillis = 20 * ONE_DAY,
            allowanceBytes = GB,
            rolloverEnabled = true,
            maxLookbackCycles = 5,
            previousCycleBoundary = backStepper(ONE_DAY),
            nextCycleBoundary = stepper(ONE_DAY),
            queryUsageBeforeMillis = { _ -> 999L }, // never finds a zero point
            queryUsedBytes = { _, _ -> error("should not be queried without a found boundary") }
        )
        assertEquals(null, result)
    }

    @Test
    fun `bootstrapFromHistory bails out when a history query is unavailable`() {
        val result = RolloverLedger.bootstrapFromHistory(
            currentCycleStartMillis = 20 * ONE_DAY,
            allowanceBytes = GB,
            rolloverEnabled = true,
            maxLookbackCycles = 10,
            previousCycleBoundary = backStepper(ONE_DAY),
            nextCycleBoundary = stepper(ONE_DAY),
            queryUsageBeforeMillis = { _ -> null },
            queryUsedBytes = { _, _ -> error("should not be queried") }
        )
        assertEquals(null, result)
    }

    @Test
    fun `bootstrapFromHistory does nothing when rollover is disabled`() {
        val result = RolloverLedger.bootstrapFromHistory(
            currentCycleStartMillis = 20 * ONE_DAY,
            allowanceBytes = GB,
            rolloverEnabled = false,
            maxLookbackCycles = 10,
            previousCycleBoundary = backStepper(ONE_DAY),
            nextCycleBoundary = stepper(ONE_DAY),
            queryUsageBeforeMillis = { _ -> error("should not be queried") },
            queryUsedBytes = { _, _ -> error("should not be queried") }
        )
        assertEquals(null, result)
    }
}

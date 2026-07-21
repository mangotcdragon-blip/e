package com.datatracker.usage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RolloverPolicyTest {

    @Test
    fun `disabled rollover never applies`() {
        assertFalse(RolloverPolicy.shouldApplyRollover(false, firstConfiguredAtMillis = 100, previousCycleStartMillis = 0))
    }

    @Test
    fun `never configured never applies`() {
        assertFalse(RolloverPolicy.shouldApplyRollover(true, firstConfiguredAtMillis = 0, previousCycleStartMillis = 0))
    }

    @Test
    fun `configured mid previous cycle does not apply -- no full previous cycle under this plan`() {
        // User configured the app *during* what CycleCalculator considers the "previous" cycle,
        // so there's no genuine prior data to roll over.
        assertFalse(
            RolloverPolicy.shouldApplyRollover(
                true, firstConfiguredAtMillis = 5_000, previousCycleStartMillis = 1_000
            )
        )
    }

    @Test
    fun `configured before the previous cycle started applies`() {
        assertTrue(
            RolloverPolicy.shouldApplyRollover(
                true, firstConfiguredAtMillis = 500, previousCycleStartMillis = 1_000
            )
        )
    }

    @Test
    fun `configured exactly at the previous cycle boundary applies`() {
        assertTrue(
            RolloverPolicy.shouldApplyRollover(
                true, firstConfiguredAtMillis = 1_000, previousCycleStartMillis = 1_000
            )
        )
    }
}

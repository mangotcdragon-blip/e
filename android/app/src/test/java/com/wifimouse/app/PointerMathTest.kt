package com.wifimouse.app

import com.wifimouse.app.ui.PointerMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PointerMathTest {

    @Test
    fun `acceleration off means plain sensitivity`() {
        val gain = PointerMath.gain(dx = 40f, dy = 0f, dtMillis = 8f, sensitivity = 1.5f, accelerate = false)
        assertEquals(1.5f, gain, 0.0001f)
    }

    @Test
    fun `slow movement is not amplified`() {
        val gain = PointerMath.gain(dx = 0.5f, dy = 0f, dtMillis = 16f, sensitivity = 1f, accelerate = true)
        assertEquals(1f, gain, 0.0001f)
    }

    @Test
    fun `fast movement is amplified but bounded`() {
        val fast = PointerMath.gain(dx = 200f, dy = 0f, dtMillis = 8f, sensitivity = 1f, accelerate = true)
        assertTrue("expected a boost, got $fast", fast > 2f)
        assertTrue("gain must stay bounded, got $fast", fast <= 2.21f)
    }

    @Test
    fun `gain rises with speed`() {
        val slower = PointerMath.gain(10f, 0f, 16f, 1f, true)
        val faster = PointerMath.gain(40f, 0f, 16f, 1f, true)
        assertTrue(faster > slower)
    }

    @Test
    fun `a stalled frame cannot be mistaken for a flick`() {
        // dt is clamped, so a 500 ms gap behaves like a slow drag, not a throw.
        val gain = PointerMath.gain(20f, 0f, 500f, 1f, true)
        assertTrue("a long gap must not accelerate, got $gain", gain < 1.05f)
    }
}

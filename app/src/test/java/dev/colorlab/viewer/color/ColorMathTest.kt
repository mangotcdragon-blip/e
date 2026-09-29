package dev.colorlab.viewer.color

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorMathTest {

    private fun apply(m: FloatArray, r: Float, g: Float, b: Float): FloatArray {
        val out = FloatArray(3)
        for (row in 0 until 3) {
            out[row] = m[row * 5] * r + m[row * 5 + 1] * g + m[row * 5 + 2] * b + m[row * 5 + 4]
        }
        return out
    }

    @Test
    fun defaultAdjustmentsAreIdentity() {
        assertArrayEquals(ColorMath.identity(), ColorAdjustments().toColorMatrix(), 1e-6f)
    }

    @Test
    fun multiplyWithIdentityIsNoOp() {
        val m = ColorMath.contrast(1.5f)
        assertArrayEquals(m, ColorMath.multiply(ColorMath.identity(), m), 1e-6f)
        assertArrayEquals(m, ColorMath.multiply(m, ColorMath.identity()), 1e-6f)
    }

    @Test
    fun multiplyComposesTranslations() {
        val a = ColorMath.translate(10f, 0f, 0f)
        val b = ColorMath.scale(2f, 2f, 2f)
        // a after b: scale first, then add 10.
        val ab = ColorMath.multiply(a, b)
        val (r, _, _) = apply(ab, 100f, 0f, 0f)
        assertEquals(210f, r, 1e-4f)
    }

    @Test
    fun invertFlipsChannels() {
        val (r, g, b) = apply(ColorMath.invert(), 0f, 255f, 100f)
        assertEquals(255f, r, 1e-4f)
        assertEquals(0f, g, 1e-4f)
        assertEquals(155f, b, 1e-4f)
    }

    @Test
    fun zeroSaturationProducesGrey() {
        val (r, g, b) = apply(ColorMath.saturation(0f), 200f, 50f, 20f)
        assertEquals(r, g, 1e-3f)
        assertEquals(g, b, 1e-3f)
    }

    @Test
    fun contrastPreservesMidGrey() {
        val (r, g, b) = apply(ColorMath.contrast(1.8f), 127.5f, 127.5f, 127.5f)
        assertEquals(127.5f, r, 1e-3f)
        assertEquals(127.5f, g, 1e-3f)
        assertEquals(127.5f, b, 1e-3f)
    }

    @Test
    fun hueRotationPreservesGrey() {
        val (r, g, b) = apply(ColorMath.hueRotate(120f), 90f, 90f, 90f)
        assertEquals(90f, r, 1e-2f)
        assertEquals(90f, g, 1e-2f)
        assertEquals(90f, b, 1e-2f)
    }

    @Test
    fun hueRotationBy360IsIdentity() {
        assertArrayEquals(ColorMath.identity(), ColorMath.hueRotate(360f), 1e-4f)
    }

    @Test
    fun warmTemperatureBoostsRedAndCutsBlue() {
        val m = ColorAdjustments(temperature = 100f).toColorMatrix()
        val (r, _, b) = apply(m, 100f, 100f, 100f)
        assertTrue(r > 100f)
        assertTrue(b < 100f)
    }

    @Test
    fun exposureOneStopDoubles() {
        val (r, _, _) = apply(ColorMath.exposure(1f), 40f, 40f, 40f)
        assertEquals(80f, r, 1e-4f)
    }

    @Test
    fun presetsRoundTripThroughEquality() {
        // Every preset must be distinguishable from the default so the chip highlights correctly.
        val nonDefault = Presets.all.drop(1)
        assertTrue(nonDefault.all { !it.adjustments.isDefault })
    }
}

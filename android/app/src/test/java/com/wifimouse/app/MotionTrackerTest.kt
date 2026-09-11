package com.wifimouse.app

import com.wifimouse.app.vision.MotionTracker
import java.util.Random
import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionTrackerTest {

    private val width = 80
    private val height = 60

    /**
     * A blurred noise field, standing in for a textured surface. Real camera
     * frames are never pixel-level white noise, so the blur matters: it is what
     * makes neighbouring offsets score differently.
     */
    private val surface: Array<IntArray> = buildSurface()

    private fun buildSurface(): Array<IntArray> {
        val random = Random(42)
        var field = Array(height * 2) { IntArray(width * 2) { random.nextInt(256) } }
        repeat(2) {
            val blurred = Array(height * 2) { IntArray(width * 2) }
            for (y in field.indices) {
                for (x in field[0].indices) {
                    var sum = 0
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            val sy = (y + dy).coerceIn(0, field.size - 1)
                            val sx = (x + dx).coerceIn(0, field[0].size - 1)
                            sum += field[sy][sx]
                        }
                    }
                    blurred[y][x] = sum / 9
                }
            }
            field = blurred
        }
        return field
    }

    /** Crops the surface at an offset: a positive shift moves content right and down. */
    private fun frame(shiftX: Int, shiftY: Int): ByteArray {
        val out = ByteArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val sy = (y - shiftY + height / 2).coerceIn(0, height * 2 - 1)
                val sx = (x - shiftX + width / 2).coerceIn(0, width * 2 - 1)
                out[y * width + x] = surface[sy][sx].toByte()
            }
        }
        return out
    }

    private fun tracker() = MotionTracker(width, height)

    @Test
    fun `recovers a known shift`() {
        val cases = listOf(0 to 0, 1 to 0, 3 to -2, -5 to 4, 8 to 8, 12 to -11, 14 to 0)
        val reference = frame(0, 0)
        for ((shiftX, shiftY) in cases) {
            val result = tracker().track(reference, frame(shiftX, shiftY))
            val error = hypot((result.dx - shiftX).toDouble(), (result.dy - shiftY).toDouble())
            assertTrue(
                "shift ($shiftX, $shiftY) came back as (${result.dx}, ${result.dy})",
                error < 0.6,
            )
            assertTrue("shift ($shiftX, $shiftY) was not usable", result.usable)
        }
    }

    @Test
    fun `tracks up to the stated range`() {
        val result = tracker().track(frame(0, 0), frame(MotionTracker.MAX_SHIFT - 2, 0))
        assertTrue(abs(result.dx - (MotionTracker.MAX_SHIFT - 2)) < 0.6)
    }

    @Test
    fun `a blank surface moves nothing`() {
        // An unlit desk or a bare wall: there is nothing to match, and guessing
        // would send the pointer wandering on its own.
        val flat = ByteArray(width * height) { 40 }
        val result = tracker().track(flat, flat)
        assertTrue("blank frames must not be usable", !result.usable)
        assertTrue(result.dx == 0f && result.dy == 0f)
    }

    @Test
    fun `faint texture is rejected before it becomes drift`() {
        val random = Random(7)
        val faint = ByteArray(width * height) { (128 + random.nextInt(3) - 1).toByte() }
        val result = tracker().track(faint, faint)
        assertTrue("a nearly flat frame must not be usable", !result.usable)
    }

    @Test
    fun `movement beyond the search range is reported as unreliable`() {
        // Better to drop a frame than to fling the pointer somewhere random.
        val result = tracker().track(frame(0, 0), frame(30, 0))
        assertTrue("a 30px jump should not be trusted: $result", !result.usable)
    }

    @Test
    fun `identical frames report no movement`() {
        val still = frame(0, 0)
        val result = tracker().track(still, still)
        assertTrue(abs(result.dx) < 0.05f && abs(result.dy) < 0.05f)
        assertTrue(result.usable)
    }

    @Test
    fun `quality rises with a clear match`() {
        val clear = tracker().track(frame(0, 0), frame(2, 1))
        val flat = ByteArray(width * height) { 90 }
        val none = tracker().track(flat, flat)
        assertTrue(clear.quality > none.quality)
        assertTrue(clear.quality in 0f..1f && none.quality in 0f..1f)
    }

    @Test
    fun `a frame too small to search is refused`() {
        try {
            MotionTracker(32, 24)
            assertTrue("expected a complaint about the frame size", false)
        } catch (expected: IllegalArgumentException) {
            // The search would read outside the buffer; better to fail loudly.
        }
    }
}

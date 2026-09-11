package com.wifimouse.app.ui

import kotlin.math.hypot
import kotlin.math.min

/**
 * Pointer gain. Kept free of Android types so it can be unit-tested.
 *
 * A fixed multiplier forces a bad trade-off: high enough to cross a 4K desktop
 * in one swipe, and single-pixel aiming becomes impossible. Scaling the gain
 * with finger speed gives both — slow movement stays 1:1-ish, fast movement
 * covers ground.
 */
object PointerMath {

    /** Speed (px/ms) at which the full extra gain is reached. */
    private const val FULL_GAIN_SPEED = 4.0f

    /** Largest multiplier acceleration may add on top of the sensitivity. */
    private const val MAX_EXTRA_GAIN = 2.2f

    /** Below this speed (px/ms) no extra gain is applied at all. */
    private const val DEAD_ZONE_SPEED = 0.15f

    /**
     * Returns the factor to multiply a raw finger delta by.
     *
     * @param dx       horizontal finger movement, in pixels
     * @param dy       vertical finger movement, in pixels
     * @param dtMillis time since the previous sample; clamped, because a
     *                 stalled frame would otherwise look like a flick
     */
    fun gain(
        dx: Float,
        dy: Float,
        dtMillis: Float,
        sensitivity: Float,
        accelerate: Boolean,
    ): Float {
        if (!accelerate) return sensitivity

        val dt = dtMillis.coerceIn(4f, 50f)
        val speed = hypot(dx, dy) / dt
        if (speed <= DEAD_ZONE_SPEED) return sensitivity

        val ramp = min(1f, (speed - DEAD_ZONE_SPEED) / (FULL_GAIN_SPEED - DEAD_ZONE_SPEED))
        // Squared ramp: gentle at walking pace, decisive at a flick.
        return sensitivity * (1f + (MAX_EXTRA_GAIN - 1f) * ramp * ramp)
    }
}

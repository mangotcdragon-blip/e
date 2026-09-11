package com.wifimouse.app.vision

import kotlin.math.abs

/**
 * Works out how far the picture moved between two camera frames — the same job
 * the sensor in an optical mouse does.
 *
 * The method is block matching: take a patch from the middle of the new frame
 * and slide it over the old one until it lines up. A coarse pass on a
 * half-size copy finds the rough offset cheaply, then a fine pass refines it,
 * and a parabola through the neighbouring scores gives sub-pixel precision.
 * Two passes keep the cost near 110k byte comparisons per frame, which is
 * nothing at 30 fps, while still tracking movement of up to [MAX_SHIFT] pixels
 * per frame.
 *
 * Deliberately free of Android types so the whole thing can be unit-tested
 * against synthetic frames. Not thread-safe: it reuses scratch buffers, so call
 * [track] from one thread only.
 */
class MotionTracker(private val width: Int, private val height: Int) {

    /**
     * @param dx         how far the image moved, in frame pixels
     * @param confidence 0..1, how clear the match was; a flat surface scores 0
     * @param texture    how much detail the patch had to work with
     */
    data class Result(
        val dx: Float,
        val dy: Float,
        val confidence: Float,
        val texture: Float,
    ) {
        /** Whether this reading is worth acting on. */
        val usable: Boolean
            get() = confidence >= MotionTracker.MIN_CONFIDENCE && texture >= MotionTracker.MIN_TEXTURE

        /** 0..1, for the on-screen tracking-quality meter. */
        val quality: Float
            get() = (confidence / MotionTracker.MIN_CONFIDENCE * 0.5f)
                .coerceAtMost(1f) * (texture / MotionTracker.MIN_TEXTURE).coerceAtMost(1f)

        companion object {
            val NONE = Result(0f, 0f, 0f, 0f)
        }
    }

    private val halfWidth = width / 2
    private val halfHeight = height / 2
    private val previousHalf = ByteArray(halfWidth * halfHeight)
    private val currentHalf = ByteArray(halfWidth * halfHeight)

    /** Fine-pass scores, kept so the sub-pixel fit can read the neighbours. */
    private val fineScores = FloatArray(FINE_WINDOW * FINE_WINDOW)

    init {
        require(width > 4 * MAX_SHIFT && height > 4 * MAX_SHIFT) {
            "frame $width x $height is too small to track ±$MAX_SHIFT pixels"
        }
    }

    /**
     * Returns the displacement from [previous] to [current], such that
     * `current(x, y)` matches `previous(x - dx, y - dy)`.
     *
     * Both arrays are 8-bit luma, [width] * [height] bytes.
     */
    fun track(previous: ByteArray, current: ByteArray): Result {
        require(previous.size >= width * height && current.size >= width * height) {
            "frame buffers are smaller than $width x $height"
        }

        val texture = textureOf(current)
        if (texture < MIN_TEXTURE) {
            // A blank wall or an unlit desk: any "match" here would be noise.
            return Result(0f, 0f, 0f, texture)
        }

        halve(previous, previousHalf)
        halve(current, currentHalf)

        val coarse = searchCoarse()
        return searchFine(previous, current, coarse.first * 2, coarse.second * 2, texture)
    }

    // -- passes ------------------------------------------------------------ #

    /** Rough offset, in half-resolution pixels. */
    private fun searchCoarse(): Pair<Int, Int> {
        val x0 = MAX_SHIFT / 2
        val y0 = MAX_SHIFT / 2
        val x1 = halfWidth - MAX_SHIFT / 2
        val y1 = halfHeight - MAX_SHIFT / 2

        var bestX = 0
        var bestY = 0
        var best = Float.MAX_VALUE

        for (oy in -COARSE_SEARCH..COARSE_SEARCH) {
            for (ox in -COARSE_SEARCH..COARSE_SEARCH) {
                val score = sad(
                    previousHalf, currentHalf, halfWidth,
                    x0, y0, x1, y1, ox, oy, step = 1, ceiling = best,
                )
                if (score < best) {
                    best = score
                    bestX = ox
                    bestY = oy
                }
            }
        }
        return bestX to bestY
    }

    /** Refines around the coarse guess and fits a parabola for sub-pixel. */
    private fun searchFine(
        previous: ByteArray,
        current: ByteArray,
        guessX: Int,
        guessY: Int,
        texture: Float,
    ): Result {
        val x0 = MAX_SHIFT
        val y0 = MAX_SHIFT
        val x1 = width - MAX_SHIFT
        val y1 = height - MAX_SHIFT

        var bestIndex = 0
        var best = Float.MAX_VALUE
        var total = 0f

        for (row in 0 until FINE_WINDOW) {
            val oy = guessY + row - FINE_SEARCH
            for (col in 0 until FINE_WINDOW) {
                val ox = guessX + col - FINE_SEARCH
                val score = sad(previous, current, width, x0, y0, x1, y1, ox, oy, step = 1)
                fineScores[row * FINE_WINDOW + col] = score
                total += score
                if (score < best) {
                    best = score
                    bestIndex = row * FINE_WINDOW + col
                }
            }
        }

        val mean = total / fineScores.size
        // A real match sits in a clear trough; on a featureless surface every
        // offset scores about the same and this collapses towards zero.
        val confidence = if (mean <= 0f) 0f else ((mean - best) / mean).coerceIn(0f, 1f)

        val bestRow = bestIndex / FINE_WINDOW
        val bestCol = bestIndex % FINE_WINDOW
        val subX = subPixel(
            left = scoreAt(bestRow, bestCol - 1),
            middle = best,
            right = scoreAt(bestRow, bestCol + 1),
        )
        val subY = subPixel(
            left = scoreAt(bestRow - 1, bestCol),
            middle = best,
            right = scoreAt(bestRow + 1, bestCol),
        )

        val dx = (guessX + bestCol - FINE_SEARCH) + subX
        val dy = (guessY + bestRow - FINE_SEARCH) + subY
        return Result(dx, dy, confidence, texture)
    }

    private fun scoreAt(row: Int, col: Int): Float =
        if (row in 0 until FINE_WINDOW && col in 0 until FINE_WINDOW) {
            fineScores[row * FINE_WINDOW + col]
        } else {
            Float.NaN // off the edge of the window: no sub-pixel fit
        }

    /**
     * Vertex of the parabola through three scores, in pixels from the middle.
     * Clamped to half a pixel because a larger correction would mean the
     * integer minimum was in the wrong place.
     */
    private fun subPixel(left: Float, middle: Float, right: Float): Float {
        if (left.isNaN() || right.isNaN()) return 0f
        val denominator = left - 2f * middle + right
        if (abs(denominator) < 1e-4f) return 0f
        return (0.5f * (left - right) / denominator).coerceIn(-0.5f, 0.5f)
    }

    // -- pixel helpers ----------------------------------------------------- #

    /**
     * Sum of absolute differences between the patch of [current] and the same
     * patch of [previous] shifted by ([offsetX], [offsetY]).
     *
     * Bails out once the running total passes [ceiling], since a candidate that
     * is already worse than the best cannot win.
     */
    private fun sad(
        previous: ByteArray,
        current: ByteArray,
        stride: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        offsetX: Int,
        offsetY: Int,
        step: Int,
        ceiling: Float = Float.MAX_VALUE,
    ): Float {
        var total = 0
        var samples = 0
        var y = y0
        while (y < y1) {
            val currentRow = y * stride
            val previousRow = (y - offsetY) * stride
            var x = x0
            while (x < x1) {
                val a = current[currentRow + x].toInt() and 0xFF
                val b = previous[previousRow + x - offsetX].toInt() and 0xFF
                total += abs(a - b)
                samples++
                x += step
            }
            if (samples > 0 && total.toFloat() / samples > ceiling) return Float.MAX_VALUE
            y += step
        }
        return if (samples == 0) Float.MAX_VALUE else total.toFloat() / samples
    }

    /** Mean absolute deviation of the patch: how much detail there is to match. */
    private fun textureOf(frame: ByteArray): Float {
        var sum = 0
        var count = 0
        var y = MAX_SHIFT
        while (y < height - MAX_SHIFT) {
            val row = y * width
            var x = MAX_SHIFT
            while (x < width - MAX_SHIFT) {
                sum += frame[row + x].toInt() and 0xFF
                count++
                x += 2
            }
            y += 2
        }
        if (count == 0) return 0f
        val mean = sum.toFloat() / count

        var deviation = 0f
        y = MAX_SHIFT
        while (y < height - MAX_SHIFT) {
            val row = y * width
            var x = MAX_SHIFT
            while (x < width - MAX_SHIFT) {
                deviation += abs((frame[row + x].toInt() and 0xFF) - mean)
                x += 2
            }
            y += 2
        }
        return deviation / count
    }

    /** Box-averages a frame down to half size, which is what the coarse pass reads. */
    private fun halve(source: ByteArray, destination: ByteArray) {
        for (y in 0 until halfHeight) {
            val sourceRow = 2 * y * width
            val nextRow = sourceRow + width
            val destinationRow = y * halfWidth
            for (x in 0 until halfWidth) {
                val left = 2 * x
                val sum = (source[sourceRow + left].toInt() and 0xFF) +
                    (source[sourceRow + left + 1].toInt() and 0xFF) +
                    (source[nextRow + left].toInt() and 0xFF) +
                    (source[nextRow + left + 1].toInt() and 0xFF)
                destination[destinationRow + x] = (sum shr 2).toByte()
            }
        }
    }

    companion object {
        /** Half-resolution search radius, so ±14 pixels at full resolution. */
        const val COARSE_SEARCH = 7

        /** Full-resolution refinement radius around the coarse guess. */
        const val FINE_SEARCH = 2

        private const val FINE_WINDOW = FINE_SEARCH * 2 + 1

        /** Largest movement, in frame pixels, that can be tracked per frame. */
        const val MAX_SHIFT = COARSE_SEARCH * 2 + FINE_SEARCH

        /** Below this, the match is too ambiguous to move the pointer. */
        const val MIN_CONFIDENCE = 0.08f

        /** Below this, the camera is looking at something too plain to track. */
        const val MIN_TEXTURE = 4.0f
    }
}

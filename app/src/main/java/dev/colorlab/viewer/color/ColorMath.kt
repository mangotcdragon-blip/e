package dev.colorlab.viewer.color

import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Helpers for 4x5 colour matrices stored row-major in a 20-element FloatArray,
 * the same layout used by android.graphics.ColorMatrix and
 * androidx.compose.ui.graphics.ColorMatrix. The fifth column is a translation
 * in the 0..255 range.
 */
object ColorMath {
    // Rec. 709 luma coefficients, what Android uses for saturation.
    private const val LUM_R = 0.2126f
    private const val LUM_G = 0.7152f
    private const val LUM_B = 0.0722f

    fun identity(): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    /** Returns a * b, treating both as 5x5 matrices whose last row is [0 0 0 0 1]. */
    fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(20)
        for (row in 0 until 4) {
            for (col in 0 until 5) {
                var sum = 0f
                for (k in 0 until 4) sum += a[row * 5 + k] * b[k * 5 + col]
                if (col == 4) sum += a[row * 5 + 4]
                out[row * 5 + col] = sum
            }
        }
        return out
    }

    /** Linear blend of two matrices. Valid because the transforms are affine. */
    fun mix(a: FloatArray, b: FloatArray, t: Float): FloatArray =
        FloatArray(20) { i -> a[i] + (b[i] - a[i]) * t }

    fun scale(r: Float, g: Float, b: Float): FloatArray = floatArrayOf(
        r, 0f, 0f, 0f, 0f,
        0f, g, 0f, 0f, 0f,
        0f, 0f, b, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    fun translate(r: Float, g: Float, b: Float): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f, r,
        0f, 1f, 0f, 0f, g,
        0f, 0f, 1f, 0f, b,
        0f, 0f, 0f, 1f, 0f,
    )

    /** Exposure in stops: each stop doubles the light. */
    fun exposure(stops: Float): FloatArray {
        val s = 2f.pow(stops)
        return scale(s, s, s)
    }

    /** Brightness as a fraction of full scale, -1..1. */
    fun brightness(amount: Float): FloatArray {
        val offset = amount * 128f
        return translate(offset, offset, offset)
    }

    /** Contrast around mid grey. 1 = unchanged. */
    fun contrast(c: Float): FloatArray {
        val offset = (1f - c) * 127.5f
        return floatArrayOf(
            c, 0f, 0f, 0f, offset,
            0f, c, 0f, 0f, offset,
            0f, 0f, c, 0f, offset,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    /** Lifts blacks and compresses range for a matte, washed look. 0..1. */
    fun fade(amount: Float): FloatArray {
        val s = 1f - 0.33f * amount
        val offset = 60f * amount
        return floatArrayOf(
            s, 0f, 0f, 0f, offset,
            0f, s, 0f, 0f, offset,
            0f, 0f, s, 0f, offset,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    /** Saturation: 0 = greyscale, 1 = unchanged, >1 = boosted. */
    fun saturation(s: Float): FloatArray {
        val inv = 1f - s
        val r = LUM_R * inv
        val g = LUM_G * inv
        val b = LUM_B * inv
        return floatArrayOf(
            r + s, g, b, 0f, 0f,
            r, g + s, b, 0f, 0f,
            r, g, b + s, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    /** Rotates hue by [degrees] while preserving luma. */
    fun hueRotate(degrees: Float): FloatArray {
        val rad = Math.toRadians(degrees.toDouble())
        val cosA = cos(rad).toFloat()
        val sinA = sin(rad).toFloat()
        return floatArrayOf(
            LUM_R + cosA * (1f - LUM_R) + sinA * (-LUM_R),
            LUM_G + cosA * (-LUM_G) + sinA * (-LUM_G),
            LUM_B + cosA * (-LUM_B) + sinA * (1f - LUM_B),
            0f, 0f,

            LUM_R + cosA * (-LUM_R) + sinA * 0.143f,
            LUM_G + cosA * (1f - LUM_G) + sinA * 0.140f,
            LUM_B + cosA * (-LUM_B) + sinA * (-0.283f),
            0f, 0f,

            LUM_R + cosA * (-LUM_R) + sinA * (-(1f - LUM_R)),
            LUM_G + cosA * (-LUM_G) + sinA * LUM_G,
            LUM_B + cosA * (1f - LUM_B) + sinA * LUM_B,
            0f, 0f,

            0f, 0f, 0f, 1f, 0f,
        )
    }

    private val SEPIA = floatArrayOf(
        0.393f, 0.769f, 0.189f, 0f, 0f,
        0.349f, 0.686f, 0.168f, 0f, 0f,
        0.272f, 0.534f, 0.131f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    /** Classic sepia tone, blended in by [amount] 0..1. */
    fun sepia(amount: Float): FloatArray = mix(identity(), SEPIA, amount)

    fun invert(): FloatArray = floatArrayOf(
        -1f, 0f, 0f, 0f, 255f,
        0f, -1f, 0f, 0f, 255f,
        0f, 0f, -1f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f,
    )
}

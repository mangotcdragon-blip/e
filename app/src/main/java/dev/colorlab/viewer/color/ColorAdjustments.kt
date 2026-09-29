package dev.colorlab.viewer.color

/**
 * Every user-tweakable setting. All values are in "UI units" (percent, degrees,
 * stops) so sliders and presets read naturally; [toColorMatrix] converts them
 * into a single 4x5 colour matrix that can be handed to both Compose and the
 * Android framework.
 */
data class ColorAdjustments(
    // Light
    val exposure: Float = 0f,       // stops, -3..3
    val brightness: Float = 0f,     // -100..100
    val contrast: Float = 100f,     // percent, 0..200
    val fade: Float = 0f,           // 0..100, lifts blacks
    // Colour
    val saturation: Float = 100f,   // percent, 0..200
    val hue: Float = 0f,            // degrees, -180..180
    val temperature: Float = 0f,    // -100 (cool) .. 100 (warm)
    val tint: Float = 0f,           // -100 (green) .. 100 (magenta)
    val red: Float = 100f,          // channel gain percent, 0..200
    val green: Float = 100f,
    val blue: Float = 100f,
    // Effects
    val sepia: Float = 0f,          // 0..100
    val monochrome: Boolean = false,
    val invert: Boolean = false,
    val vignette: Float = 0f,       // 0..100
    val blur: Float = 0f,           // dp, 0..25 (Android 12+ only)
) {
    val isDefault: Boolean get() = this == Default

    /** True when the settings change pixel colours (as opposed to only vignette/blur overlays). */
    val hasColorChanges: Boolean
        get() = copy(vignette = 0f, blur = 0f) != Default

    /**
     * Builds the combined colour matrix. Operations are applied in the order
     * listed below; each step is post-multiplied onto the running matrix.
     */
    fun toColorMatrix(): FloatArray {
        var m = ColorMath.identity()
        fun then(step: FloatArray) { m = ColorMath.multiply(step, m) }

        if (exposure != 0f) then(ColorMath.exposure(exposure))
        if (brightness != 0f) then(ColorMath.brightness(brightness / 100f))
        if (contrast != 100f) then(ColorMath.contrast(contrast / 100f))
        if (fade > 0f) then(ColorMath.fade(fade / 100f))

        val t = temperature / 100f
        val ti = tint / 100f
        val rGain = (1f + 0.25f * t) * (red / 100f)
        val gGain = (1f - 0.25f * ti) * (green / 100f)
        val bGain = (1f - 0.25f * t) * (blue / 100f)
        if (rGain != 1f || gGain != 1f || bGain != 1f) then(ColorMath.scale(rGain, gGain, bGain))

        if (saturation != 100f) then(ColorMath.saturation(saturation / 100f))
        if (hue != 0f) then(ColorMath.hueRotate(hue))
        if (sepia > 0f) then(ColorMath.sepia(sepia / 100f))
        if (monochrome) then(ColorMath.saturation(0f))
        if (invert) then(ColorMath.invert())
        return m
    }

    companion object {
        val Default = ColorAdjustments()
    }
}

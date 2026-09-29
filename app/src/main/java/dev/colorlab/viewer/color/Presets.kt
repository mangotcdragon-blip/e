package dev.colorlab.viewer.color

data class Preset(val name: String, val adjustments: ColorAdjustments)

object Presets {
    val all: List<Preset> = listOf(
        Preset("Original", ColorAdjustments()),
        Preset("Vivid", ColorAdjustments(saturation = 145f, contrast = 115f)),
        Preset("Warm", ColorAdjustments(temperature = 45f, saturation = 110f, brightness = 3f)),
        Preset("Cool", ColorAdjustments(temperature = -45f, tint = -8f)),
        Preset("Faded", ColorAdjustments(fade = 45f, contrast = 90f, saturation = 85f)),
        Preset("Retro", ColorAdjustments(temperature = 25f, fade = 30f, saturation = 120f, hue = -8f, vignette = 30f)),
        Preset("Noir", ColorAdjustments(monochrome = true, contrast = 135f, vignette = 45f)),
        Preset("Sepia", ColorAdjustments(sepia = 100f, contrast = 105f, vignette = 25f)),
        Preset("Dramatic", ColorAdjustments(contrast = 150f, saturation = 80f, brightness = -8f, vignette = 55f)),
        Preset("Neon", ColorAdjustments(hue = 30f, saturation = 170f, contrast = 120f)),
        Preset("Dreamy", ColorAdjustments(blur = 4f, fade = 20f, brightness = 6f, saturation = 110f)),
        Preset("Negative", ColorAdjustments(invert = true)),
    )
}

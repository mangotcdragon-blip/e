package com.dailytools.calculator.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.dailytools.calculator.data.ThemeMode

private val GalleryDarkScheme = darkColorScheme(
    primary = GalleryPrimary,
    onPrimary = GalleryOnPrimary,
    background = GalleryBackgroundDark,
    surface = GallerySurfaceDark,
    surfaceVariant = Color(0xFF2A2A2A),
)

private val GalleryLightScheme = lightColorScheme(
    primary = GalleryPrimary,
    onPrimary = GalleryOnPrimary,
    background = GalleryBackgroundLight,
    surface = GallerySurfaceLight,
)

private val CalculatorScheme = lightColorScheme(
    primary = CalcOperatorKey,
    onPrimary = CalcOperatorKeyText,
    background = CalcBackground,
    surface = CalcBackground,
    onBackground = CalcDisplayText,
    onSurface = CalcDisplayText,
)

@Composable
fun CalculatorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CalculatorScheme,
        typography = AppTypography,
        content = content,
    )
}

@Composable
fun GalleryTheme(themeMode: ThemeMode, content: @Composable () -> Unit) {
    val useDark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (useDark) GalleryDarkScheme else GalleryLightScheme,
        typography = AppTypography,
        content = content,
    )
}

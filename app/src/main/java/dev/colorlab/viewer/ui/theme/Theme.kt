package dev.colorlab.viewer.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val FallbackScheme = darkColorScheme(
    primary = Color(0xFFB39DFF),
    onPrimary = Color(0xFF2A0F6B),
    primaryContainer = Color(0xFF4A2FA6),
    onPrimaryContainer = Color(0xFFE8DDFF),
    secondary = Color(0xFF80CBC4),
    background = Color(0xFF121212),
    surface = Color(0xFF161616),
    surfaceVariant = Color(0xFF2A2A2E),
)

/** Always dark: a neutral, dim chrome keeps the eye on the media. */
@Composable
fun ColorLabTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(context)
    } else {
        FallbackScheme
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

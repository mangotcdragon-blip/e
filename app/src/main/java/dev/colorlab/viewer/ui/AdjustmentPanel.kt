package dev.colorlab.viewer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.colorlab.viewer.color.ColorAdjustments
import dev.colorlab.viewer.color.Presets
import java.util.Locale

private val Tabs = listOf("Light", "Color", "Effects", "Presets")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdjustmentPanel(
    adjustments: ColorAdjustments,
    onChange: (ColorAdjustments) -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Column(modifier = modifier) {
        PrimaryTabRow(selectedTabIndex = tab) {
            Tabs.forEachIndexed { index, title ->
                Tab(
                    selected = tab == index,
                    onClick = { tab = index },
                    text = { Text(title) },
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            when (tab) {
                0 -> LightTab(adjustments, onChange)
                1 -> ColorTab(adjustments, onChange)
                2 -> EffectsTab(adjustments, onChange)
                else -> PresetsTab(adjustments, onChange)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun LightTab(a: ColorAdjustments, onChange: (ColorAdjustments) -> Unit) {
    AdjustmentSlider("Exposure", a.exposure, -3f..3f, 0f, ::formatStops) { onChange(a.copy(exposure = it)) }
    AdjustmentSlider("Brightness", a.brightness, -100f..100f, 0f, ::formatSigned) { onChange(a.copy(brightness = it)) }
    AdjustmentSlider("Contrast", a.contrast, 0f..200f, 100f, ::formatPercent) { onChange(a.copy(contrast = it)) }
    AdjustmentSlider("Fade", a.fade, 0f..100f, 0f, ::formatPercent) { onChange(a.copy(fade = it)) }
}

@Composable
private fun ColorTab(a: ColorAdjustments, onChange: (ColorAdjustments) -> Unit) {
    AdjustmentSlider("Saturation", a.saturation, 0f..200f, 100f, ::formatPercent) { onChange(a.copy(saturation = it)) }
    AdjustmentSlider("Hue", a.hue, -180f..180f, 0f, ::formatDegrees) { onChange(a.copy(hue = it)) }
    AdjustmentSlider("Temperature", a.temperature, -100f..100f, 0f, ::formatSigned) { onChange(a.copy(temperature = it)) }
    AdjustmentSlider("Tint", a.tint, -100f..100f, 0f, ::formatSigned) { onChange(a.copy(tint = it)) }
    SectionLabel("Channels")
    AdjustmentSlider("Red", a.red, 0f..200f, 100f, ::formatPercent) { onChange(a.copy(red = it)) }
    AdjustmentSlider("Green", a.green, 0f..200f, 100f, ::formatPercent) { onChange(a.copy(green = it)) }
    AdjustmentSlider("Blue", a.blue, 0f..200f, 100f, ::formatPercent) { onChange(a.copy(blue = it)) }
}

@Composable
private fun EffectsTab(a: ColorAdjustments, onChange: (ColorAdjustments) -> Unit) {
    AdjustmentSlider("Sepia", a.sepia, 0f..100f, 0f, ::formatPercent) { onChange(a.copy(sepia = it)) }
    AdjustmentSlider("Vignette", a.vignette, 0f..100f, 0f, ::formatPercent) { onChange(a.copy(vignette = it)) }
    AdjustmentSlider("Blur", a.blur, 0f..25f, 0f, ::formatDp) { onChange(a.copy(blur = it)) }
    Text(
        text = "Blur needs Android 12 or newer and is preview-only when saving.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
    ToggleRow("Monochrome", a.monochrome) { onChange(a.copy(monochrome = it)) }
    ToggleRow("Invert colours", a.invert) { onChange(a.copy(invert = it)) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetsTab(a: ColorAdjustments, onChange: (ColorAdjustments) -> Unit) {
    Text(
        text = "Tap a preset to start from it, then fine-tune in the other tabs.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Presets.all.forEach { preset ->
            FilterChip(
                selected = a == preset.adjustments,
                onClick = { onChange(preset.adjustments) },
                label = { Text(preset.name) },
            )
        }
    }
}

@Composable
private fun AdjustmentSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    default: Float,
    format: (Float) -> String,
    onChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                text = format(value),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = if (value == default) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
            )
            IconButton(
                onClick = { onChange(default) },
                enabled = value != default,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = "Reset $label", modifier = Modifier.size(16.dp))
            }
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

private fun formatPercent(v: Float) = "${v.toInt()}%"
private fun formatSigned(v: Float) = if (v > 0f) "+${v.toInt()}" else "${v.toInt()}"
private fun formatDegrees(v: Float) = "${v.toInt()}°"
private fun formatStops(v: Float) = String.format(Locale.US, "%+.2f EV", v)
private fun formatDp(v: Float) = String.format(Locale.US, "%.1f", v)

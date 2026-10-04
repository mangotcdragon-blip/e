package dev.colorlab.viewer.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Forward5
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Replay5
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.colorlab.viewer.AudioTrack
import dev.colorlab.viewer.VideoState
import java.util.Locale

private val Speeds = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

@Composable
fun VideoControls(
    state: VideoState,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onLoopChange: (Boolean) -> Unit,
    onMuteChange: (Boolean) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onAudioTrackSelect: (AudioTrack) -> Unit,
    modifier: Modifier = Modifier,
    onMenuOpenChange: (Boolean) -> Unit = {},
) {
    // While the user drags the slider, show the drag position instead of the live one.
    var scrubPosition by remember { mutableStateOf<Float?>(null) }
    val duration = state.durationMs.coerceAtLeast(1L)
    val shownPosition = scrubPosition?.toLong() ?: state.positionMs

    Column(modifier = modifier.padding(horizontal = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledIconButton(onClick = onPlayPause) {
                Icon(
                    imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (state.isPlaying) "Pause" else "Play",
                )
            }
            TimeLabel(shownPosition, Modifier.padding(start = 8.dp))
            Slider(
                value = shownPosition.toFloat().coerceIn(0f, duration.toFloat()),
                onValueChange = { scrubPosition = it },
                onValueChangeFinished = {
                    scrubPosition?.let { onSeek(it.toLong()) }
                    scrubPosition = null
                },
                valueRange = 0f..duration.toFloat(),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            )
            TimeLabel(state.durationMs)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            IconButton(onClick = { onSeekBy(-5_000L) }) {
                Icon(Icons.Filled.Replay5, contentDescription = "Back 5 seconds")
            }
            IconButton(onClick = { onSeekBy(5_000L) }) {
                Icon(Icons.Filled.Forward5, contentDescription = "Forward 5 seconds")
            }
            IconToggleButton(checked = state.loop, onCheckedChange = onLoopChange) {
                Icon(
                    imageVector = if (state.loop) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                    contentDescription = if (state.loop) "Looping" else "Play once",
                )
            }
            IconToggleButton(checked = state.muted, onCheckedChange = onMuteChange) {
                Icon(
                    imageVector = if (state.muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = if (state.muted) "Unmute" else "Mute",
                )
            }
            AudioTrackMenu(
                tracks = state.audioTracks,
                onSelect = onAudioTrackSelect,
                onOpenChange = onMenuOpenChange,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
            ) {
                Speeds.forEach { speed ->
                    FilterChip(
                        selected = state.speed == speed,
                        onClick = { onSpeedChange(speed) },
                        label = { Text(formatSpeed(speed)) },
                    )
                }
            }
        }
    }
}

/** Lists the video's audio streams; the current one is ticked. */
@Composable
private fun AudioTrackMenu(
    tracks: List<AudioTrack>,
    onSelect: (AudioTrack) -> Unit,
    onOpenChange: (Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    fun setExpanded(value: Boolean) {
        expanded = value
        onOpenChange(value)
    }

    Box {
        IconButton(onClick = { setExpanded(true) }, enabled = tracks.isNotEmpty()) {
            Icon(
                imageVector = Icons.Filled.Audiotrack,
                contentDescription = "Audio track",
                tint = if (tracks.size > 1) MaterialTheme.colorScheme.primary else Color.Unspecified,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { setExpanded(false) }) {
            tracks.forEach { track ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(track.label)
                            if (track.detail.isNotEmpty()) {
                                Text(
                                    text = track.detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    trailingIcon = {
                        if (track.selected) Icon(Icons.Filled.Check, contentDescription = "Selected")
                    },
                    onClick = {
                        setExpanded(false)
                        if (!track.selected) onSelect(track)
                    },
                )
            }
        }
    }
}

@Composable
private fun TimeLabel(ms: Long, modifier: Modifier = Modifier) {
    Text(
        text = formatTime(ms),
        style = MaterialTheme.typography.labelMedium,
        fontFamily = FontFamily.Monospace,
        modifier = modifier.width(44.dp),
    )
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

private fun formatSpeed(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "${speed.toInt()}x" else "${speed}x"

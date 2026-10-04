package dev.colorlab.viewer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.colorlab.viewer.player.Codecs

/** Shows which video codecs this phone can decode, and with what. */
@Composable
fun DecoderInfoDialog(onDismiss: () -> Unit) {
    val support = remember { Codecs.videoDecoders() }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Video decoders on this device") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "The app plays whatever the phone can decode. Hardware decoders are fast; software ones work but may struggle above 1080p.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                support.forEach { codec ->
                    Text(
                        text = codec.codec,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (codec.supported) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                    if (!codec.supported) {
                        Text(
                            text = "No decoder",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    codec.decoders.forEach { decoder ->
                        val kind = if (decoder.hardware) "hardware" else "software"
                        val size = if (decoder.maxWidth > 0) ", up to ${decoder.maxWidth}x${decoder.maxHeight}" else ""
                        Text(
                            text = "${decoder.name} ($kind$size)",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        },
    )
}

package com.dailytools.calculator.ui.detail

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

private const val VLC_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

/**
 * A fallback for videos whose codec the device's hardware decoder can't handle (see
 * VideoPlayerView's UNSUPPORTED_FORMAT_ERROR_CODES). LibVLC bundles its own complete software
 * decoder - the same engine dedicated players use - so it plays these regardless of what the
 * phone's hardware supports, rendered inline here instead of handing the user off to another app.
 */
@Composable
fun VlcVideoPlayerView(url: String, isActive: Boolean = true, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var isBuffering by remember(url) { mutableStateOf(true) }

    val libVlc = remember { LibVLC(context, arrayListOf("--no-drop-late-frames", "--no-skip-frames")) }
    val videoLayout = remember { VLCVideoLayout(context) }
    val mediaPlayer = remember(url) { MediaPlayer(libVlc) }

    DisposableEffect(mediaPlayer) {
        mediaPlayer.attachViews(videoLayout, null, false, false)

        val referer = when {
            url.contains("e621") -> "https://e621.net/"
            url.contains("rule34") -> "https://rule34.xxx/"
            else -> null
        }
        val media = Media(libVlc, Uri.parse(url)).apply {
            addOption(":http-user-agent=$VLC_USER_AGENT")
            if (referer != null) addOption(":http-referrer=$referer")
        }
        mediaPlayer.media = media
        media.release()

        val listener = MediaPlayer.EventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Buffering -> isBuffering = event.buffering < 100f
                MediaPlayer.Event.Playing -> isBuffering = false
            }
        }
        mediaPlayer.setEventListener(listener)
        mediaPlayer.play()

        onDispose {
            mediaPlayer.setEventListener(null)
            mediaPlayer.stop()
            mediaPlayer.detachViews()
            mediaPlayer.release()
        }
    }

    LaunchedEffect(isActive, mediaPlayer) {
        if (isActive) mediaPlayer.play() else mediaPlayer.pause()
    }

    DisposableEffect(Unit) {
        onDispose { libVlc.release() }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { videoLayout })
        if (isBuffering) {
            CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

package com.dailytools.calculator.ui.detail

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.PlayerView
import com.dailytools.calculator.data.network.VideoDataSource

/**
 * Error codes ExoPlayer uses for "the file/codec itself is the problem," as opposed to a network
 * hiccup. Retrying does nothing for these - the device's decoder genuinely can't handle the
 * format (e.g. AV1, which some phones lack hardware support for) - so they get a different
 * message and action than a transient load failure.
 */
private val UNSUPPORTED_FORMAT_ERROR_CODES = setOf(
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
)

/**
 * ExoPlayer's default error-handling policy retries a stalled/misbehaving connection many times
 * with growing backoff before finally giving up - on a genuinely bad connection that can add up
 * to several minutes of silent "buffering" with nothing on screen to explain it. This still gives
 * up eventually so a truly dead connection surfaces (and can be retried) rather than hanging
 * forever, but allows enough attempts that an ordinary dropped connection or brief server hiccup -
 * common on smaller sites like these - can recover on its own instead of immediately erroring out.
 */
private class FastFailLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getMinimumLoadableRetryCount(dataType: Int): Int = 4
}

@Composable
fun VideoPlayerView(
    url: String,
    networkUrl: String = url,
    isActive: Boolean = true,
    onLaunchingExternalActivity: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var isBuffering by remember(url) { mutableStateOf(true) }
    var hasError by remember(url) { mutableStateOf(false) }
    var isUnsupportedFormat by remember(url) { mutableStateOf(false) }
    var retryToken by remember(url) { mutableIntStateOf(0) }

    val exoPlayer = remember(url, retryToken) {
        // Start playback as soon as a small amount is buffered instead of ExoPlayer's much
        // larger default pre-buffer, so a swipe into a post starts playing almost immediately.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(5_000, 30_000, 500, 1_000)
            .build()
        val mediaSourceFactory = DefaultMediaSourceFactory(VideoDataSource.factoryFor(context, url))
            .setLoadErrorHandlingPolicy(FastFailLoadErrorHandlingPolicy())
        ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(url))
                repeatMode = Player.REPEAT_MODE_ONE
                // Always prepare so an off-screen, pre-loaded neighbor page (see
                // beyondViewportPageCount in MediaViewerScreen) starts buffering immediately -
                // but only the active page is actually allowed to play or make sound, kept in
                // sync below for as long as this same instance lives across page transitions.
                volume = if (isActive) 1f else 0f
                playWhenReady = isActive
                prepare()
            }
    }

    LaunchedEffect(isActive, exoPlayer) {
        exoPlayer.playWhenReady = isActive
        exoPlayer.volume = if (isActive) 1f else 0f
    }

    DisposableEffect(exoPlayer) {
        hasError = false
        isUnsupportedFormat = false
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
            }

            override fun onPlayerError(error: PlaybackException) {
                isBuffering = false
                hasError = true
                isUnsupportedFormat = error.errorCode in UNSUPPORTED_FORMAT_ERROR_CODES
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                PlayerView(context).apply {
                    useController = false
                }
            },
            // factory only runs once per on-screen view - without this, swapping to a new
            // exoPlayer instance (a cache hit resolving after network playback already started,
            // or a manual retry) would leave the visible surface bound to the old, now-released
            // player while the new one plays invisibly off-screen.
            update = { view -> view.player = exoPlayer },
        )
        if (isBuffering && !hasError) {
            CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        if (hasError && isUnsupportedFormat) {
            Text(
                text = "This device can't play this video's format - tap to open in another app",
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .clickable {
                        onLaunchingExternalActivity()
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(Uri.parse(networkUrl), "video/*")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        try {
                            context.startActivity(Intent.createChooser(intent, null))
                        } catch (e: ActivityNotFoundException) {
                            Toast.makeText(context, "No app found to play this video", Toast.LENGTH_SHORT).show()
                        }
                    },
            )
        } else if (hasError) {
            Text(
                text = "Couldn't load video - tap to retry",
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .clickable { retryToken++ },
            )
        }
    }
}

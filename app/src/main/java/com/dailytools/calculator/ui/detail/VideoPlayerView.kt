package com.dailytools.calculator.ui.detail

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
 * ExoPlayer's default error-handling policy retries a stalled/misbehaving connection many times
 * with growing backoff before finally giving up - on a genuinely bad connection that can add up
 * to several minutes of silent "buffering" with nothing on screen to explain it. This gives up
 * after a couple of quick attempts instead, so a real failure surfaces (and can be retried) in
 * seconds, not minutes.
 */
private class FastFailLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getMinimumLoadableRetryCount(dataType: Int): Int = 1
}

@Composable
fun VideoPlayerView(url: String, isActive: Boolean = true, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var isBuffering by remember(url) { mutableStateOf(true) }
    var hasError by remember(url) { mutableStateOf(false) }
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
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
            }

            override fun onPlayerError(error: PlaybackException) {
                isBuffering = false
                hasError = true
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
        if (hasError) {
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

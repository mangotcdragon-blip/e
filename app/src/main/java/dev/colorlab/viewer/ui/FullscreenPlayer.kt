package dev.colorlab.viewer.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.colorlab.viewer.EditorViewModel
import dev.colorlab.viewer.LoadedMedia
import dev.colorlab.viewer.color.ColorAdjustments
import kotlinx.coroutines.delay

private const val CONTROLS_HIDE_DELAY_MS = 3_500L

/**
 * Cinema mode: the media fills the whole screen, the system bars are hidden,
 * the phone rotates freely with the sensor, and the screen stays on. Tap to
 * show or hide the controls; back or the exit button leaves fullscreen.
 */
@Composable
fun FullscreenPlayer(
    viewModel: EditorViewModel,
    media: LoadedMedia,
    onExit: () -> Unit,
) {
    var controlsVisible by remember { mutableStateOf(true) }
    var showOriginal by remember { mutableStateOf(false) }
    val isPlaying = viewModel.videoState.isPlaying
    val effective = if (showOriginal) ColorAdjustments.Default else viewModel.adjustments

    BackHandler(onBack = onExit)
    ImmersiveModeEffect()

    // Auto-hide the controls while a video is playing.
    LaunchedEffect(controlsVisible, isPlaying, viewModel.videoState.speed) {
        if (controlsVisible && isPlaying) {
            delay(CONTROLS_HIDE_DELAY_MS)
            controlsVisible = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        MediaPreview(
            media = media,
            adjustments = effective,
            player = viewModel.player,
            videoState = viewModel.videoState,
            isLoading = viewModel.isLoading,
            onTap = { controlsVisible = !controlsVisible },
            modifier = Modifier.fillMaxSize(),
        )

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.displayCutout),
            ) {
                // Top scrim with the exit button.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)),
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    IconButton(onClick = onExit) {
                        Icon(Icons.Filled.FullscreenExit, contentDescription = "Exit fullscreen", tint = Color.White)
                    }
                    Text(
                        text = if (media is LoadedMedia.Video) "Now playing" else "Viewing",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                        modifier = Modifier.weight(1f),
                    )
                    CompareButton(active = showOriginal, onActiveChange = { showOriginal = it })
                }

                // Bottom scrim with the playback controls.
                if (media is LoadedMedia.Video) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))),
                            )
                            .padding(top = 24.dp, bottom = 8.dp),
                    ) {
                        Spacer(Modifier.padding(4.dp))
                        VideoControls(
                            state = viewModel.videoState,
                            onPlayPause = viewModel::togglePlayPause,
                            onSeek = viewModel::seekTo,
                            onSeekBy = viewModel::seekBy,
                            onLoopChange = viewModel::setLoop,
                            onMuteChange = viewModel::setMuted,
                            onSpeedChange = viewModel::setSpeed,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Hides the system bars, unlocks rotation to follow the sensor (even when the
 * system auto-rotate toggle is off) and keeps the screen awake. Everything is
 * restored when the composable leaves the screen, unless the activity is only
 * being recreated for a rotation, in which case the new instance re-applies it.
 */
@Composable
private fun ImmersiveModeEffect() {
    val view = LocalView.current
    val activity = LocalContext.current.findActivity()
    DisposableEffect(view, activity) {
        val window = activity?.window ?: return@DisposableEffect onDispose {}
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (!activity.isChangingConfigurations) {
                controller.show(WindowInsetsCompat.Type.systemBars())
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

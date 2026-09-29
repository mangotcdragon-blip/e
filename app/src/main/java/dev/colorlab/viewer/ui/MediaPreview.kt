package dev.colorlab.viewer.ui

import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import dev.colorlab.viewer.LoadedMedia
import dev.colorlab.viewer.VideoState
import dev.colorlab.viewer.color.ColorAdjustments
import kotlin.math.max

/**
 * Shows the loaded image or video with the current adjustments applied live.
 * Pinch to zoom, drag to pan, double tap to reset the view.
 */
@Composable
fun MediaPreview(
    media: LoadedMedia,
    adjustments: ColorAdjustments,
    player: ExoPlayer,
    videoState: VideoState,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
) {
    val matrix = remember(adjustments) { adjustments.toColorMatrix() }
    var zoom by remember(media.uri) { mutableFloatStateOf(1f) }
    var pan by remember(media.uri) { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .background(Color.Black)
            .clipToBounds()
            .pointerInput(media.uri) {
                detectTransformGestures { _, dragAmount, zoomChange, _ ->
                    zoom = (zoom * zoomChange).coerceIn(1f, 8f)
                    pan = if (zoom == 1f) Offset.Zero else pan + dragAmount
                }
            }
            .pointerInput(media.uri) {
                detectTapGestures(onDoubleTap = {
                    zoom = 1f
                    pan = Offset.Zero
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .vignette(adjustments.vignette / 100f)
                .graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = pan.x
                    translationY = pan.y
                }
                .then(if (adjustments.blur > 0f) Modifier.blur(adjustments.blur.dp) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            when (media) {
                is LoadedMedia.Image -> Image(
                    bitmap = media.bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    colorFilter = ColorFilter.colorMatrix(ColorMatrix(matrix)),
                    modifier = Modifier.fillMaxSize(),
                )

                is LoadedMedia.Video -> VideoSurface(
                    player = player,
                    colorMatrix = matrix,
                    aspectRatio = videoState.aspectRatio,
                )
            }
        }

        if (isLoading || (media is LoadedMedia.Video && videoState.isBuffering)) {
            CircularProgressIndicator(modifier = Modifier.size(40.dp))
        }

        if (zoom > 1.01f) {
            Surface(
                color = Color.Black.copy(alpha = 0.55f),
                contentColor = Color.White,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
            ) {
                Text(
                    text = String.format(java.util.Locale.US, "%.1fx", zoom),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/**
 * Renders ExoPlayer output into a TextureView. A TextureView is always drawn as
 * a hardware layer, and the framework applies the layer paint's colour filter to
 * that layer, so we can recolour video frames in real time with no GL code.
 */
@Composable
private fun VideoSurface(
    player: ExoPlayer,
    colorMatrix: FloatArray,
    aspectRatio: Float,
) {
    AndroidView(
        factory = { context ->
            TextureView(context).also { player.setVideoTextureView(it) }
        },
        update = { view ->
            view.setLayerPaint(Paint().apply { colorFilter = ColorMatrixColorFilter(colorMatrix) })
        },
        onRelease = { view -> player.clearVideoTextureView(view) },
        modifier = Modifier.aspectRatio(aspectRatio),
    )
}

/** Darkens the edges with a radial falloff. [strength] is 0..1. */
private fun Modifier.vignette(strength: Float): Modifier =
    if (strength <= 0f) this else drawWithContent {
        drawContent()
        val radius = max(size.width, size.height) * 0.75f
        drawRect(
            brush = Brush.radialGradient(
                0f to Color.Transparent,
                0.45f to Color.Transparent,
                1f to Color.Black.copy(alpha = strength),
                center = center,
                radius = radius,
            ),
        )
    }

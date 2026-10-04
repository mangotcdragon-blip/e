package dev.colorlab.viewer

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import dev.colorlab.viewer.color.ColorAdjustments
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

sealed interface LoadedMedia {
    val uri: Uri

    data class Image(override val uri: Uri, val bitmap: ImageBitmap) : LoadedMedia
    data class Video(override val uri: Uri) : LoadedMedia
}

/** One selectable audio stream inside the current video. */
data class AudioTrack(
    val label: String,
    val detail: String,
    val selected: Boolean,
    internal val group: Tracks.Group,
    internal val indexInGroup: Int,
)

data class VideoState(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val pixelRatio: Float = 1f,
    val loop: Boolean = true,
    val muted: Boolean = false,
    val speed: Float = 1f,
    val audioTracks: List<AudioTrack> = emptyList(),
) {
    val aspectRatio: Float
        get() = if (width > 0 && height > 0) width * pixelRatio / height else 16f / 9f
}

class EditorViewModel(app: Application) : AndroidViewModel(app) {

    var media by mutableStateOf<LoadedMedia?>(null)
        private set
    var isLoading by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var adjustments by mutableStateOf(ColorAdjustments())
    var videoState by mutableStateOf(VideoState())
        private set
    /** Cinema mode; kept here so it survives the rotation it invites. */
    var isFullscreen by mutableStateOf(false)

    val player: ExoPlayer = ExoPlayer.Builder(app).build()

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            videoState = videoState.copy(isPlaying = isPlaying)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            videoState = videoState.copy(
                isBuffering = playbackState == Player.STATE_BUFFERING,
                durationMs = player.duration.coerceAtLeast(0L),
            )
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                videoState = videoState.copy(
                    width = videoSize.width,
                    height = videoSize.height,
                    pixelRatio = videoSize.pixelWidthHeightRatio,
                )
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            videoState = videoState.copy(audioTracks = describeAudioTracks(tracks))
        }

        override fun onPlayerError(error: PlaybackException) {
            message = "Couldn't play this video (${error.errorCodeName})"
        }
    }

    init {
        player.repeatMode = Player.REPEAT_MODE_ONE
        player.addListener(listener)
        viewModelScope.launch {
            while (isActive) {
                if (media is LoadedMedia.Video) {
                    videoState = videoState.copy(
                        positionMs = player.currentPosition.coerceAtLeast(0L),
                        durationMs = player.duration.coerceAtLeast(0L),
                    )
                }
                delay(if (videoState.isPlaying) 100L else 400L)
            }
        }
    }

    // ---- Opening media -------------------------------------------------

    fun open(uri: Uri) {
        viewModelScope.launch {
            isLoading = true
            try {
                val mime = resolveMimeType(uri)
                when {
                    mime.startsWith("video/") -> openVideo(uri)
                    mime.startsWith("image/") -> openImage(uri)
                    else -> {
                        // Unknown type: try as an image first, fall back to video.
                        val bitmap = runCatching { decodeImage(uri) }.getOrNull()
                        if (bitmap != null) showImage(uri, bitmap) else openVideo(uri)
                    }
                }
            } catch (e: Exception) {
                message = "Couldn't open file: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                isLoading = false
            }
        }
    }

    private fun openVideo(uri: Uri) {
        media = LoadedMedia.Video(uri)
        videoState = VideoState(loop = videoState.loop, muted = videoState.muted, speed = videoState.speed)
        // Forget any audio track chosen for the previous file.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .build()
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.playWhenReady = true
    }

    private suspend fun openImage(uri: Uri) {
        val bitmap = decodeImage(uri)
        showImage(uri, bitmap)
    }

    private fun showImage(uri: Uri, bitmap: Bitmap) {
        player.stop()
        player.clearMediaItems()
        media = LoadedMedia.Image(uri, bitmap.asImageBitmap())
    }

    private fun resolveMimeType(uri: Uri): String {
        val app = getApplication<Application>()
        app.contentResolver.getType(uri)?.let { return it }
        val ext = MimeTypeMap.getFileExtensionFromUrl(uri.toString()).lowercase(Locale.ROOT)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: ""
    }

    private suspend fun decodeImage(uri: Uri): Bitmap = withContext(Dispatchers.IO) {
        val resolver = getApplication<Application>().contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(resolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                // Software bitmaps can be drawn onto a Canvas when exporting.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
                val largest = max(info.size.width, info.size.height)
                if (largest > MAX_IMAGE_DIMENSION) {
                    val scale = MAX_IMAGE_DIMENSION.toFloat() / largest
                    decoder.setTargetSize(
                        (info.size.width * scale).toInt().coerceAtLeast(1),
                        (info.size.height * scale).toInt().coerceAtLeast(1),
                    )
                }
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                ?: error("Cannot read file")
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_IMAGE_DIMENSION) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: error("Unsupported image")
            applyExifRotation(decoded, uri)
        }
    }

    private fun applyExifRotation(bitmap: Bitmap, uri: Uri): Bitmap {
        val resolver = getApplication<Application>().contentResolver
        val orientation = runCatching {
            resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    // ---- Adjustments -----------------------------------------------------

    fun update(transform: ColorAdjustments.() -> ColorAdjustments) {
        adjustments = adjustments.transform()
    }

    fun resetAdjustments() {
        adjustments = ColorAdjustments()
    }

    fun clearMessage() {
        message = null
    }

    // ---- Playback --------------------------------------------------------

    fun togglePlayPause() {
        if (player.isPlaying) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0L)
            player.play()
        }
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs.coerceIn(0L, videoState.durationMs))
        videoState = videoState.copy(positionMs = player.currentPosition)
    }

    fun seekBy(deltaMs: Long) = seekTo(player.currentPosition + deltaMs)

    fun setLoop(loop: Boolean) {
        player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        videoState = videoState.copy(loop = loop)
    }

    fun setMuted(muted: Boolean) {
        player.volume = if (muted) 0f else 1f
        videoState = videoState.copy(muted = muted)
    }

    fun setSpeed(speed: Float) {
        player.setPlaybackSpeed(speed)
        videoState = videoState.copy(speed = speed)
    }

    fun selectAudioTrack(track: AudioTrack) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .setOverrideForType(TrackSelectionOverride(track.group.mediaTrackGroup, track.indexInGroup))
            .build()
    }

    private fun describeAudioTracks(tracks: Tracks): List<AudioTrack> {
        val result = mutableListOf<AudioTrack>()
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val format = group.getTrackFormat(i)
                result += AudioTrack(
                    label = audioLabel(format, result.size + 1),
                    detail = audioDetail(format),
                    selected = group.isTrackSelected(i),
                    group = group,
                    indexInGroup = i,
                )
            }
        }
        return result
    }

    private fun audioLabel(format: Format, ordinal: Int): String {
        format.label?.takeIf { it.isNotBlank() }?.let { return it }
        val language = format.language
        if (!language.isNullOrBlank() && language != C.LANGUAGE_UNDETERMINED) {
            val display = Locale.forLanguageTag(language).getDisplayLanguage(Locale.getDefault())
            if (display.isNotBlank() && display != language) return display
            return language.uppercase(Locale.ROOT)
        }
        return "Audio $ordinal"
    }

    private fun audioDetail(format: Format): String {
        val codec = when (format.sampleMimeType) {
            MimeTypes.AUDIO_AAC -> "AAC"
            MimeTypes.AUDIO_AC3 -> "AC-3"
            MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "E-AC-3"
            MimeTypes.AUDIO_AC4 -> "AC-4"
            MimeTypes.AUDIO_DTS -> "DTS"
            MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_EXPRESS -> "DTS-HD"
            MimeTypes.AUDIO_TRUEHD -> "TrueHD"
            MimeTypes.AUDIO_MPEG -> "MP3"
            MimeTypes.AUDIO_OPUS -> "Opus"
            MimeTypes.AUDIO_VORBIS -> "Vorbis"
            MimeTypes.AUDIO_FLAC -> "FLAC"
            MimeTypes.AUDIO_ALAC -> "ALAC"
            MimeTypes.AUDIO_RAW -> "PCM"
            else -> format.sampleMimeType?.substringAfter('/')?.uppercase(Locale.ROOT)
        }
        val channels = when (format.channelCount) {
            Format.NO_VALUE, 0 -> null
            1 -> "Mono"
            2 -> "Stereo"
            6 -> "5.1"
            8 -> "7.1"
            else -> "${format.channelCount} ch"
        }
        return listOfNotNull(codec, channels).joinToString(" \u00B7 ")
    }

    // ---- Export ------------------------------------------------------------

    /** Renders the current adjustments into a new JPEG in Pictures/ColorLab. Blur is preview-only. */
    fun saveImage() {
        val image = media as? LoadedMedia.Image ?: return
        val settings = adjustments
        viewModelScope.launch {
            isLoading = true
            val result = withContext(Dispatchers.IO) { runCatching { writeImage(image.bitmap.asAndroidBitmap(), settings) } }
            isLoading = false
            message = result.fold(
                onSuccess = { "Saved to Pictures/ColorLab" },
                onFailure = { "Couldn't save image: ${it.message ?: it.javaClass.simpleName}" },
            )
        }
    }

    private fun writeImage(source: Bitmap, settings: ColorAdjustments): Uri {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(settings.toColorMatrix())
        }
        canvas.drawBitmap(source, 0f, 0f, paint)
        if (settings.vignette > 0f) {
            val strength = settings.vignette / 100f
            val cx = output.width / 2f
            val cy = output.height / 2f
            val radius = max(output.width, output.height) * 0.75f
            val edge = Color.argb((255 * strength).toInt().coerceIn(0, 255), 0, 0, 0)
            val vignettePaint = Paint().apply {
                shader = RadialGradient(
                    cx, cy, radius,
                    intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, edge),
                    floatArrayOf(0f, 0.45f, 1f),
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRect(0f, 0f, output.width.toFloat(), output.height.toFloat(), vignettePaint)
        }

        val resolver = getApplication<Application>().contentResolver
        val name = "ColorLab_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ColorLab")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore refused the file")
        try {
            resolver.openOutputStream(uri)?.use { stream ->
                if (!output.compress(Bitmap.CompressFormat.JPEG, 95, stream)) error("JPEG encoding failed")
            } ?: error("Cannot open output")
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        } finally {
            output.recycle()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        }
        return uri
    }

    override fun onCleared() {
        player.removeListener(listener)
        player.release()
    }

    private companion object {
        const val MAX_IMAGE_DIMENSION = 4096
    }
}

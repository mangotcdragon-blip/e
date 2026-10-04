package dev.colorlab.viewer.player

import android.media.MediaCodecList
import android.os.Build
import androidx.media3.common.MimeTypes

/** A decoder on this device for one video codec. */
data class DecoderInfo(
    val name: String,
    val hardware: Boolean,
    val maxWidth: Int,
    val maxHeight: Int,
)

data class CodecSupport(
    val codec: String,
    val decoders: List<DecoderInfo>,
) {
    val supported: Boolean get() = decoders.isNotEmpty()
}

object Codecs {
    private val VideoCodecs = listOf(
        MimeTypes.VIDEO_H264,
        MimeTypes.VIDEO_H265,
        MimeTypes.VIDEO_VP9,
        MimeTypes.VIDEO_AV1,
        MimeTypes.VIDEO_DOLBY_VISION,
        MimeTypes.VIDEO_VP8,
        MimeTypes.VIDEO_MP4V,
    )

    fun friendlyName(mimeType: String?): String = when (mimeType) {
        MimeTypes.VIDEO_H265 -> "H.265 (HEVC)"
        MimeTypes.VIDEO_H264 -> "H.264 (AVC)"
        MimeTypes.VIDEO_AV1 -> "AV1"
        MimeTypes.VIDEO_VP9 -> "VP9"
        MimeTypes.VIDEO_VP8 -> "VP8"
        MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
        MimeTypes.VIDEO_MP4V -> "MPEG-4 Part 2"
        MimeTypes.VIDEO_H263 -> "H.263"
        MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
        null -> "this video"
        else -> mimeType.substringAfter('/')
    }

    /** Asks the platform which decoders exist for the common video codecs. */
    fun videoDecoders(): List<CodecSupport> {
        val infos = runCatching { MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.toList() }
            .getOrDefault(emptyList())
            .filter { !it.isEncoder }
        return VideoCodecs.map { mime ->
            val decoders = infos.mapNotNull { info ->
                if (info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) return@mapNotNull null
                val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull()
                val video = caps?.videoCapabilities
                val hardware = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    info.isHardwareAccelerated
                } else {
                    !(info.name.startsWith("OMX.google.") || info.name.startsWith("c2.android."))
                }
                DecoderInfo(
                    name = info.name,
                    hardware = hardware,
                    maxWidth = video?.supportedWidths?.upper ?: 0,
                    maxHeight = video?.supportedHeights?.upper ?: 0,
                )
            }.sortedByDescending { it.hardware }
            CodecSupport(codec = friendlyName(mime), decoders = decoders)
        }
    }
}

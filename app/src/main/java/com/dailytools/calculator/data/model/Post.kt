package com.dailytools.calculator.data.model

enum class MediaKind { IMAGE, VIDEO, GIF, UNKNOWN }

data class Post(
    val id: String,
    val source: Source,
    val previewUrl: String,
    val fileUrl: String,
    val width: Int,
    val height: Int,
    val tags: List<String>,
    val ratingLabel: String,
    val score: Int,
    val mediaKind: MediaKind,
) {
    val aspectRatio: Float
        get() = if (width > 0 && height > 0) width.toFloat() / height.toFloat() else 1f

    companion object {
        fun mediaKindFor(url: String): MediaKind {
            val clean = url.substringBefore('?').lowercase()
            return when {
                clean.endsWith(".mp4") || clean.endsWith(".webm") || clean.endsWith(".mov") -> MediaKind.VIDEO
                clean.endsWith(".gif") -> MediaKind.GIF
                clean.endsWith(".jpg") || clean.endsWith(".jpeg") ||
                    clean.endsWith(".png") || clean.endsWith(".webp") -> MediaKind.IMAGE
                else -> MediaKind.UNKNOWN
            }
        }
    }
}

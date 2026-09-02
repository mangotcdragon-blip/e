package com.dailytools.calculator.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import com.dailytools.calculator.data.model.MediaKind
import com.dailytools.calculator.data.model.Post

private val downloadClient = OkHttpClient()

/** Saves a post's full media file into the device's public Pictures/Movies collection. */
suspend fun downloadPost(context: Context, post: Post): Result<Unit> = withContext(Dispatchers.IO) {
    runCatching {
        val request = Request.Builder().url(post.fileUrl).header("User-Agent", "CalcGallery/1.0").build()
        downloadClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Download failed: ${response.code}")
            val body = response.body ?: error("Empty response body")
            val extension = post.fileUrl.substringBefore('?').substringAfterLast('.', "jpg")
            val fileName = "${post.source.label.lowercase()}_${post.id}.$extension"
            val isVideo = post.mediaKind == MediaKind.VIDEO

            val collection = if (isVideo) {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeTypeFor(extension))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        if (isVideo) "Movies/CalcGallery" else "Pictures/CalcGallery",
                    )
                }
            }
            val uri = context.contentResolver.insert(collection, values)
                ?: error("Could not create media entry")
            context.contentResolver.openOutputStream(uri)?.use { out ->
                body.byteStream().copyTo(out)
            } ?: error("Could not open output stream")
            Unit
        }
    }
}

private fun mimeTypeFor(extension: String): String = when (extension.lowercase()) {
    "png" -> "image/png"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "mp4" -> "video/mp4"
    "webm" -> "video/webm"
    else -> "image/jpeg"
}

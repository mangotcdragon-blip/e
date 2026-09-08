package com.dailytools.calculator.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.dailytools.calculator.data.model.MediaKind
import com.dailytools.calculator.data.model.Post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

private val downloadClient = OkHttpClient()

/**
 * Splitting a large-enough file into this many byte ranges and fetching them over separate
 * connections at once - the same trick dedicated download managers use - gets around a single
 * connection's own throughput cap (and any per-connection throttling the source CDN applies),
 * instead of the transfer being stuck however fast just one TCP stream happens to go.
 */
private const val MAX_SEGMENTS = 4
private const val MIN_SEGMENT_BYTES = 1L * 1024 * 1024

/** Saves a post's full media file into the device's public Pictures/Movies collection. */
suspend fun downloadPost(context: Context, post: Post): Result<Unit> = withContext(Dispatchers.IO) {
    runCatching {
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
                // Keeps the gallery/other apps from picking up a half-written file while a
                // multi-segment download is still filling it in out of byte order.
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = context.contentResolver.insert(collection, values)
            ?: error("Could not create media entry")

        val segments = segmentPlanFor(probeContentLength(post.fileUrl))
        if (segments == null) {
            downloadSequential(context, uri, post.fileUrl)
        } else {
            downloadSegmented(context, uri, post.fileUrl, segments)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
        }
        Unit
    }
}

/** The file's total size, only if the server both reports it and explicitly supports byte ranges. */
private fun probeContentLength(url: String): Long? = runCatching {
    val request = Request.Builder().url(url).head().header("User-Agent", "CalcGallery/1.0").build()
    downloadClient.newCall(request).execute().use { response ->
        val acceptsRanges = response.header("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true
        val length = response.header("Content-Length")?.toLongOrNull()
        if (response.isSuccessful && acceptsRanges && length != null && length > 0) length else null
    }
}.getOrNull()

/** Byte ranges to fetch in parallel, or null if the file's too small/unknown to bother splitting. */
private fun segmentPlanFor(contentLength: Long?): List<LongRange>? {
    if (contentLength == null || contentLength < MIN_SEGMENT_BYTES * 2) return null
    val segmentCount = minOf(MAX_SEGMENTS.toLong(), contentLength / MIN_SEGMENT_BYTES).toInt().coerceAtLeast(1)
    if (segmentCount <= 1) return null
    val baseSize = contentLength / segmentCount
    return (0 until segmentCount).map { i ->
        val start = i * baseSize
        val end = if (i == segmentCount - 1) contentLength - 1 else start + baseSize - 1
        start..end
    }
}

private fun downloadSequential(context: Context, uri: Uri, url: String) {
    val request = Request.Builder().url(url).header("User-Agent", "CalcGallery/1.0").build()
    downloadClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) error("Download failed: ${response.code}")
        val body = response.body ?: error("Empty response body")
        context.contentResolver.openOutputStream(uri)?.use { out ->
            body.byteStream().copyTo(out)
        } ?: error("Could not open output stream")
    }
}

private suspend fun downloadSegmented(context: Context, uri: Uri, url: String, segments: List<LongRange>) {
    context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
        // Deliberately not closing this channel separately - it shares the same underlying fd as
        // pfd, and closing both would double-close it. pfd.use{} above releases it exactly once.
        val channel = FileOutputStream(pfd.fileDescriptor).channel
        coroutineScope {
            segments.map { range -> async { downloadRangeInto(channel, url, range) } }.awaitAll()
        }
        channel.force(true)
    } ?: error("Could not open output file")
}

/**
 * Fetches one byte range and writes it straight to its final position in the shared file.
 * FileChannel's positional write is safe to call from several segments at once - concurrent
 * writers never corrupt each other or the file, they just briefly queue for the (fast, in-memory)
 * write step while the real bottleneck, the network reads, keeps running in parallel underneath.
 */
private fun downloadRangeInto(channel: FileChannel, url: String, range: LongRange) {
    val request = Request.Builder()
        .url(url)
        .header("User-Agent", "CalcGallery/1.0")
        .header("Range", "bytes=${range.first}-${range.last}")
        .build()
    downloadClient.newCall(request).execute().use { response ->
        if (response.code != 206) error("Server didn't honor range request (${response.code})")
        val body = response.body ?: error("Empty response body")
        body.byteStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            var position = range.first
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                channel.write(ByteBuffer.wrap(buffer, 0, read), position)
                position += read
            }
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

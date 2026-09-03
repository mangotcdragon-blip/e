package com.dailytools.calculator.util

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.dailytools.calculator.data.model.Post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

private val cacheHttpClient = OkHttpClient()

/**
 * A simple "check the drive first, download and copy a copy there otherwise" cache for the
 * user's chosen external (typically OTG USB) folder. Deliberately doesn't try to make
 * Coil/ExoPlayer's own disk caches point at a SAF tree - their caches are built around plain
 * File access for performance and don't support SAF's content:// URIs as a cache directory.
 * This sits as its own layer above the network fetch instead.
 */
object ExternalCache {

    private fun fileNameFor(post: Post, sourceUrl: String): String {
        val ext = sourceUrl.substringBefore('?').substringAfterLast('.', "bin")
        return "${post.id}.$ext"
    }

    private fun rootOrNull(context: Context, treeUriString: String?): DocumentFile? {
        val treeUri = treeUriString?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return null
        return runCatching { DocumentFile.fromTreeUri(context, treeUri) }.getOrNull()?.takeIf { it.canRead() }
    }

    /** A content:// Uri to already-cached media for this post, or null if it isn't cached (yet). */
    fun cachedUri(context: Context, treeUriString: String?, post: Post, sourceUrl: String): Uri? {
        val root = rootOrNull(context, treeUriString) ?: return null
        val fileName = fileNameFor(post, sourceUrl)
        val existing = runCatching { root.findFile(fileName) }.getOrNull()
        return existing?.takeIf { it.isFile && it.length() > 0 }?.uri
    }

    /**
     * Downloads [sourceUrl] into the external folder if it isn't already there. Safe to call
     * every time a post is viewed - it's a no-op once cached, and fails silently (falling back
     * to plain network loading) if the drive is unplugged, out of space, or permission was lost.
     */
    suspend fun cacheInBackground(context: Context, treeUriString: String?, post: Post, sourceUrl: String) {
        withContext(Dispatchers.IO) {
            runCatching {
                val root = rootOrNull(context, treeUriString) ?: return@runCatching
                if (!root.canWrite()) return@runCatching
                val fileName = fileNameFor(post, sourceUrl)
                if (root.findFile(fileName)?.let { it.isFile && it.length() > 0 } == true) return@runCatching

                val request = Request.Builder().url(sourceUrl).header("User-Agent", "CalcGallery/1.0").build()
                cacheHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val body = response.body ?: return@use
                    val mimeType = response.header("Content-Type")?.substringBefore(';') ?: "application/octet-stream"
                    val doc = root.createFile(mimeType, fileName) ?: return@use
                    context.contentResolver.openOutputStream(doc.uri)?.use { out ->
                        body.byteStream().copyTo(out)
                    }
                }
            }
        }
    }
}

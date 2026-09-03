package com.dailytools.calculator.data.network

import android.content.Context
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * ExoPlayer's built-in HTTP stack, used with zero configuration, sends a generic client User-Agent
 * and no Referer - exactly what these sites' CDNs tend to throttle or deprioritize (the JSON API
 * has the same requirement, see [NetworkModule]). It also gets built fresh per player with no
 * shared connection pool, so every single video pays for a brand new TCP+TLS handshake instead of
 * reusing a warm connection to the same CDN host the way a browser tab would.
 *
 * This fixes both: one shared OkHttpClient (and therefore one shared connection pool) backs every
 * video load for the life of the app, with a browser-like User-Agent and a Referer matched to
 * whichever site the video actually came from.
 */
object VideoDataSource {

    private val sharedClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36"

    /** A DataSource.Factory for this specific video's host, sharing the connection pool above. */
    fun factoryFor(context: Context, url: String): DataSource.Factory {
        val referer = when {
            url.contains("e621") -> "https://e621.net/"
            url.contains("rule34") -> "https://rule34.xxx/"
            else -> null
        }
        val httpFactory = OkHttpDataSource.Factory(sharedClient)
        httpFactory.setUserAgent(USER_AGENT)
        if (referer != null) {
            httpFactory.setDefaultRequestProperties(mapOf("Referer" to referer))
        }
        // Falls back to file/content/asset access for non-http(s) URIs (e.g. the external
        // drive cache's decrypted temp files), while routing http(s) through httpFactory above.
        return DefaultDataSource.Factory(context, httpFactory)
    }
}

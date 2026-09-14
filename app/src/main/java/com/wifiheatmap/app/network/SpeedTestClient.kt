package com.wifiheatmap.app.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Measures real download throughput against Cloudflare's public speed-test
 * backend (the same endpoint speed.cloudflare.com itself uses). It requires
 * no API key and, unlike most speed-test sites, allows direct requests from
 * plain HTTP clients (the site's own CORS restriction only matters to
 * browsers, not to a native app), so we can stream real bytes and time them.
 */
object SpeedTestClient {

    private const val DOWNLOAD_URL = "https://speed.cloudflare.com/__down?bytes=100000000"
    private const val MAX_TEST_DURATION_MS = 12_000L
    private const val MIN_TEST_DURATION_MS = 2_000L
    private const val MIN_BYTES_FOR_SHORT_TEST = 500_000L
    private const val READ_BUFFER_SIZE = 64 * 1024

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    sealed class SpeedTestResult {
        data class Success(val mbps: Double, val bytesTransferred: Long, val durationMs: Long) : SpeedTestResult()
        data class Failure(val message: String) : SpeedTestResult()
    }

    /**
     * Streams the download in [READ_BUFFER_SIZE] chunks, reporting progress via
     * [onProgress], and stops once [MAX_TEST_DURATION_MS] has elapsed so the
     * test finishes quickly even on very fast connections (the payload is
     * larger than any realistic home connection can finish in that window).
     */
    suspend fun measureDownloadSpeed(
        onProgress: (bytesSoFar: Long, elapsedMs: Long) -> Unit = { _, _ -> }
    ): SpeedTestResult = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(DOWNLOAD_URL).build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext SpeedTestResult.Failure("Server returned HTTP ${response.code}")
                }
                val body = response.body ?: return@withContext SpeedTestResult.Failure("Empty response body")
                val source = body.source()
                val buffer = ByteArray(READ_BUFFER_SIZE)
                var totalBytes = 0L
                val startTime = System.nanoTime()

                while (true) {
                    val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
                    if (elapsedMs >= MAX_TEST_DURATION_MS) break
                    val read = source.read(buffer)
                    if (read == -1) break
                    totalBytes += read
                    onProgress(totalBytes, elapsedMs)
                }

                val elapsedMs = max(1L, (System.nanoTime() - startTime) / 1_000_000)
                if (elapsedMs < MIN_TEST_DURATION_MS && totalBytes < MIN_BYTES_FOR_SHORT_TEST) {
                    return@withContext SpeedTestResult.Failure("Not enough data transferred to measure speed")
                }
                val seconds = elapsedMs / 1000.0
                val mbps = (totalBytes * 8) / seconds / 1_000_000.0
                SpeedTestResult.Success(mbps, totalBytes, elapsedMs)
            }
        } catch (e: IOException) {
            SpeedTestResult.Failure(e.message ?: "Network error")
        }
    }
}

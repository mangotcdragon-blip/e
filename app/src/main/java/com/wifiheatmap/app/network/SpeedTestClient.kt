package com.wifiheatmap.app.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Measures real download throughput by streaming from a public speed-test
 * file host and timing the transfer. No API key is required, and unlike most
 * speed-test sites their CORS restriction only matters to browsers, not to a
 * native app, so a plain HTTP client can pull real bytes and time them.
 *
 * Several independent hosts are tried in order: some edge networks put bot
 * protection in front of endpoints that serve unlimited free bandwidth (seen
 * in practice as an HTTP 403 from Cloudflare's speed-test backend on some
 * connections even with a browser-like request), so plain static files from
 * other, unrelated hosts are used as fallbacks rather than letting one
 * provider's block (or an outage) take out the whole feature.
 */
object SpeedTestClient {

    private data class Endpoint(val url: String, val label: String)

    private val ENDPOINTS = listOf(
        Endpoint("https://speed.cloudflare.com/__down?bytes=100000000", "Cloudflare"),
        Endpoint("https://fsn1-speed.hetzner.com/100MB.bin", "Hetzner"),
        Endpoint("https://proof.ovh.net/files/100Mb.dat", "OVH")
    )

    private const val MAX_TEST_DURATION_MS = 12_000L
    private const val MIN_TEST_DURATION_MS = 2_000L
    private const val MIN_BYTES_FOR_SHORT_TEST = 500_000L
    private const val READ_BUFFER_SIZE = 64 * 1024
    private const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

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
     * test finishes quickly even on very fast connections (each payload is
     * larger than any realistic home connection can finish in that window).
     * Tries each endpoint in [ENDPOINTS] in turn, returning the first success.
     */
    suspend fun measureDownloadSpeed(
        onProgress: (bytesSoFar: Long, elapsedMs: Long) -> Unit = { _, _ -> }
    ): SpeedTestResult = withContext(Dispatchers.IO) {
        val failures = mutableListOf<String>()
        for (endpoint in ENDPOINTS) {
            when (val result = attempt(endpoint, onProgress)) {
                is SpeedTestResult.Success -> return@withContext result
                is SpeedTestResult.Failure -> failures += result.message
            }
        }
        SpeedTestResult.Failure(
            failures.ifEmpty { listOf("No speed test servers available") }.joinToString("; ")
        )
    }

    private fun attempt(
        endpoint: Endpoint,
        onProgress: (bytesSoFar: Long, elapsedMs: Long) -> Unit
    ): SpeedTestResult {
        val request = Request.Builder()
            .url(endpoint.url)
            .header("User-Agent", BROWSER_USER_AGENT)
            .header("Accept", "*/*")
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return failure(endpoint, "Server returned HTTP ${response.code}")
                }
                val body = response.body ?: return failure(endpoint, "Empty response body")
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
                    return failure(endpoint, "Not enough data transferred to measure speed")
                }
                val seconds = elapsedMs / 1000.0
                val mbps = (totalBytes * 8) / seconds / 1_000_000.0
                SpeedTestResult.Success(mbps, totalBytes, elapsedMs)
            }
        } catch (e: IOException) {
            failure(endpoint, e.message ?: "Network error")
        }
    }

    private fun failure(endpoint: Endpoint, message: String) =
        SpeedTestResult.Failure("${endpoint.label}: $message")
}

package com.dailytools.calculator.data.network

import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.pow

/**
 * Retries a 429 (Too Many Requests) with backoff instead of just failing outright. Honors the
 * server's Retry-After header when it sends one, otherwise backs off 2s/4s/8s. Applied to every
 * OkHttp client in the app - JSON API, video streaming, OTG caching, and downloads all hit the
 * same handful of hosts, so any of them can trip a host's rate limit.
 */
class RateLimitRetryInterceptor(private val maxRetries: Int = 3) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var response = chain.proceed(request)
        var attempt = 0
        while (response.code == 429 && attempt < maxRetries) {
            val retryAfterSeconds = response.header("Retry-After")?.toLongOrNull()
                ?: 2.0.pow(attempt + 1).toLong()
            response.close()
            Thread.sleep(retryAfterSeconds.coerceIn(1, 30) * 1000)
            attempt++
            response = chain.proceed(request)
        }
        return response
    }
}

/**
 * e621's API docs ask that clients never send more than one request per second. Our own request
 * volume grew a lot recently (preloading the next post, parallel download segments, background
 * OTG caching all run concurrently) without anything here pacing requests to the JSON search API
 * itself, which is the part e621 is strict about. This just makes sure consecutive calls through
 * a given client are spaced out, blocking briefly rather than firing them all at once.
 */
class MinIntervalInterceptor(private val minIntervalMs: Long = 1100) : Interceptor {
    private val lastRequestAtMs = AtomicLong(0)

    override fun intercept(chain: Interceptor.Chain): Response {
        synchronized(this) {
            val elapsed = System.currentTimeMillis() - lastRequestAtMs.get()
            val waitMs = minIntervalMs - elapsed
            if (waitMs > 0) Thread.sleep(waitMs)
            lastRequestAtMs.set(System.currentTimeMillis())
        }
        return chain.proceed(chain.request())
    }
}

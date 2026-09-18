package com.dailytools.calculator.data.network

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/** e621 requires a descriptive, non-generic User-Agent on every request or it may reject calls. */
private class UserAgentInterceptor(private val userAgent: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("User-Agent", userAgent)
            .build()
        return chain.proceed(request)
    }
}

object NetworkModule {

    private fun client(userAgent: String): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(UserAgentInterceptor(userAgent))
        // e621/Rule34 ask that the search API specifically not be hit more than once a second -
        // this paces requests instead of firing them all at once, and this client's the one that
        // needs it since it's the JSON API rather than a media/CDN host.
        .addInterceptor(MinIntervalInterceptor())
        .addInterceptor(RateLimitRetryInterceptor())
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    val e621Api: E621Api by lazy {
        Retrofit.Builder()
            .baseUrl("https://e621.net/")
            .client(client("CalcGallery/1.0 (Android booru client)"))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(E621Api::class.java)
    }

    val rule34Api: Rule34Api by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.rule34.xxx/")
            .client(client("CalcGallery/1.0 (Android booru client)"))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(Rule34Api::class.java)
    }
}

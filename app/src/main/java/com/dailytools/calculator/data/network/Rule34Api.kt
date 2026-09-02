package com.dailytools.calculator.data.network

import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Url

/**
 * Rule34.xxx Danbooru-style API (https://rule34.xxx/index.php?page=help&topic=dapi).
 * Returns a bare JSON array of posts.
 */
interface Rule34Api {
    @GET("index.php")
    suspend fun posts(
        @Query("page") page: String,
        @Query("s") s: String,
        @Query("q") q: String,
        @Query("json") json: Int,
        @Query("tags") tags: String,
        @Query("limit") limit: Int,
        @Query("pid") pid: Int,
    ): List<Rule34Post>?

    // Rule34's autocomplete lives on the main domain rather than api.rule34.xxx,
    // so this call takes a full absolute URL that overrides the client's base URL.
    @GET
    suspend fun autocompleteTags(@Url url: String): List<Rule34TagSuggestion>?
}

data class Rule34TagSuggestion(
    val label: String?,
    val value: String?,
)

data class Rule34Post(
    val id: Long,
    val score: Int?,
    val width: Int?,
    val height: Int?,
    val rating: String?,
    val tags: String?,
    val file_url: String?,
    val sample_url: String?,
    val preview_url: String?,
)

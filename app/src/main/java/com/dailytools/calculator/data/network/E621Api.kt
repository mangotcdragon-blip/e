package com.dailytools.calculator.data.network

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * e621.net public JSON API (https://e621.net/help/api). No auth required for read-only search.
 */
interface E621Api {
    @GET("posts.json")
    suspend fun posts(
        @Query("tags") tags: String,
        @Query("limit") limit: Int,
        @Query("page") page: Int,
    ): E621PostsResponse

    @GET("tags/autocomplete.json")
    suspend fun autocompleteTags(
        @Query("search[name_matches]") prefix: String,
        @Query("limit") limit: Int,
    ): List<E621TagSuggestion>
}

data class E621TagSuggestion(
    val id: Long?,
    val name: String?,
    @SerializedName("post_count") val postCount: Int?,
)

data class E621PostsResponse(
    val posts: List<E621Post>?
)

data class E621Post(
    val id: Long,
    val score: E621Score?,
    val rating: String?,
    val tags: E621Tags?,
    val file: E621File?,
    val preview: E621Preview?,
    val sample: E621Sample?,
)

data class E621Score(
    val total: Int?
)

data class E621Tags(
    val general: List<String>?,
    val species: List<String>?,
    val character: List<String>?,
    val copyright: List<String>?,
    val artist: List<String>?,
    val meta: List<String>?,
) {
    fun flatten(): List<String> =
        (general.orEmpty() + character.orEmpty() + species.orEmpty() + copyright.orEmpty() + artist.orEmpty())
}

data class E621File(
    val url: String?,
    val width: Int?,
    val height: Int?,
    @SerializedName("ext") val extension: String?,
)

data class E621Preview(
    val url: String?
)

data class E621Sample(
    val url: String?
)

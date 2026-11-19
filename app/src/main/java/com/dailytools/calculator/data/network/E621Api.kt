package com.dailytools.calculator.data.network

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * e621.net public JSON API (https://e621.net/help/api). No auth required for read-only search.
 */
interface E621Api {
    /**
     * Raw JSON rather than a typed model: e621 changed this endpoint from `{"posts": [...]}` to a
     * bare array with a different post layout, and a rigid Gson model turns any such change into an
     * unreadable parse crash. BooruRepository parses both layouts by hand.
     */
    @GET("posts.json")
    suspend fun posts(
        @Query("tags") tags: String,
        @Query("limit") limit: Int,
        @Query("page") page: Int,
    ): JsonElement

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

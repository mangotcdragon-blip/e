package com.dailytools.calculator.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dailytools.calculator.data.model.Post
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.favoritesDataStore by preferencesDataStore(name = "favorites")
private val favoritesGson = Gson()
private val likedPostsListType = object : TypeToken<List<Post>>() {}.type

class FavoritesStore(private val context: Context) {

    private object Keys {
        val LIKED_POSTS_JSON = stringPreferencesKey("liked_posts_json")
    }

    private fun parse(json: String?): List<Post> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching { favoritesGson.fromJson<List<Post>>(json, likedPostsListType) }
            .getOrNull()
            .orEmpty()
    }

    /** Newest-liked-first. */
    val likedPosts: Flow<List<Post>> = context.favoritesDataStore.data.map { prefs ->
        parse(prefs[Keys.LIKED_POSTS_JSON])
    }

    val likedIds: Flow<Set<String>> = likedPosts.map { posts -> posts.map { it.id }.toSet() }

    suspend fun toggleLiked(post: Post) {
        context.favoritesDataStore.edit { prefs ->
            val current = parse(prefs[Keys.LIKED_POSTS_JSON]).toMutableList()
            val existingIndex = current.indexOfFirst { it.id == post.id }
            if (existingIndex >= 0) {
                current.removeAt(existingIndex)
            } else {
                current.add(0, post)
            }
            prefs[Keys.LIKED_POSTS_JSON] = favoritesGson.toJson(current)
        }
    }
}

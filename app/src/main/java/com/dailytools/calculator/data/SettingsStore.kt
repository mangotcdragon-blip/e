package com.dailytools.calculator.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dailytools.calculator.data.model.MediaTypeFilter
import com.dailytools.calculator.data.model.Rating
import com.dailytools.calculator.data.model.Source
import com.dailytools.calculator.data.model.SortOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Everything needed to drop the user back exactly where they left off. */
data class SessionState(
    val source: Source = Source.E621,
    val query: String = "",
    val rating: Rating = Rating.ALL,
    val mediaType: MediaTypeFilter = MediaTypeFilter.ALL,
    val sort: SortOrder = SortOrder.NEWEST,
    val pagesLoaded: Int = 0,
    val gridScrollIndex: Int = 0,
    val lastPostId: String? = null,
    val wasInDetail: Boolean = false,
)

private val Context.dataStore by preferencesDataStore(name = "gallery_settings")

class SettingsStore(private val context: Context) {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val SOURCE = stringPreferencesKey("source")
        val QUERY = stringPreferencesKey("query")
        val RATING = stringPreferencesKey("rating")
        val MEDIA_TYPE = stringPreferencesKey("media_type")
        val SORT = stringPreferencesKey("sort")
        val PAGES_LOADED = intPreferencesKey("pages_loaded")
        val GRID_SCROLL_INDEX = intPreferencesKey("grid_scroll_index")
        val LAST_POST_ID = stringPreferencesKey("last_post_id")
        val WAS_IN_DETAIL = booleanPreferencesKey("was_in_detail")
        val EXTERNAL_CACHE_ENABLED = booleanPreferencesKey("external_cache_enabled")
        val EXTERNAL_CACHE_TREE_URI = stringPreferencesKey("external_cache_tree_uri")
        val FOR_YOU_ENABLED = booleanPreferencesKey("for_you_enabled")
    }

    val forYouEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.FOR_YOU_ENABLED] ?: false
    }

    suspend fun setForYouEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.FOR_YOU_ENABLED] = enabled }
    }

    val externalCacheEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.EXTERNAL_CACHE_ENABLED] ?: false
    }

    val externalCacheTreeUri: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[Keys.EXTERNAL_CACHE_TREE_URI]
    }

    suspend fun setExternalCacheEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.EXTERNAL_CACHE_ENABLED] = enabled }
    }

    suspend fun setExternalCacheTreeUri(treeUri: String?) {
        context.dataStore.edit { prefs ->
            if (treeUri == null) prefs.remove(Keys.EXTERNAL_CACHE_TREE_URI) else prefs[Keys.EXTERNAL_CACHE_TREE_URI] = treeUri
        }
    }

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        prefs[Keys.THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.DARK
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    val sessionState: Flow<SessionState> = context.dataStore.data.map { prefs ->
        SessionState(
            source = prefs[Keys.SOURCE]?.let { runCatching { Source.valueOf(it) }.getOrNull() } ?: Source.E621,
            query = prefs[Keys.QUERY] ?: "",
            rating = prefs[Keys.RATING]?.let { runCatching { Rating.valueOf(it) }.getOrNull() } ?: Rating.ALL,
            mediaType = prefs[Keys.MEDIA_TYPE]?.let { runCatching { MediaTypeFilter.valueOf(it) }.getOrNull() }
                ?: MediaTypeFilter.ALL,
            sort = prefs[Keys.SORT]?.let { runCatching { SortOrder.valueOf(it) }.getOrNull() } ?: SortOrder.NEWEST,
            pagesLoaded = prefs[Keys.PAGES_LOADED] ?: 0,
            gridScrollIndex = prefs[Keys.GRID_SCROLL_INDEX] ?: 0,
            lastPostId = prefs[Keys.LAST_POST_ID],
            wasInDetail = prefs[Keys.WAS_IN_DETAIL] ?: false,
        )
    }

    /** Called whenever the source/query/filters change: the old position no longer means anything. */
    suspend fun saveFiltersAndResetPosition(
        source: Source,
        query: String,
        rating: Rating,
        mediaType: MediaTypeFilter,
        sort: SortOrder,
    ) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SOURCE] = source.name
            prefs[Keys.QUERY] = query
            prefs[Keys.RATING] = rating.name
            prefs[Keys.MEDIA_TYPE] = mediaType.name
            prefs[Keys.SORT] = sort.name
            prefs[Keys.PAGES_LOADED] = 1
            prefs[Keys.GRID_SCROLL_INDEX] = 0
            prefs.remove(Keys.LAST_POST_ID)
            prefs[Keys.WAS_IN_DETAIL] = false
        }
    }

    suspend fun savePagesLoaded(pages: Int) {
        context.dataStore.edit { it[Keys.PAGES_LOADED] = pages }
    }

    suspend fun saveGridScrollIndex(index: Int) {
        context.dataStore.edit { it[Keys.GRID_SCROLL_INDEX] = index }
    }

    suspend fun saveLastPostId(postId: String) {
        context.dataStore.edit { it[Keys.LAST_POST_ID] = postId }
    }

    suspend fun saveInDetailFlag(inDetail: Boolean) {
        context.dataStore.edit { it[Keys.WAS_IN_DETAIL] = inDetail }
    }
}

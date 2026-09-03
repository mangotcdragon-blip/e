package com.dailytools.calculator.ui.detail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import com.dailytools.calculator.data.FavoritesStore
import com.dailytools.calculator.data.SettingsStore
import kotlinx.coroutines.launch

@Composable
fun LikedDetailScreen(
    favoritesStore: FavoritesStore,
    settingsStore: SettingsStore,
    initialIndex: Int,
    onBack: () -> Unit,
    onLaunchingExternalActivity: () -> Unit,
) {
    val likedPosts by favoritesStore.likedPosts.collectAsState(initial = emptyList())
    val cacheEnabled by settingsStore.externalCacheEnabled.collectAsState(initial = false)
    val cacheTreeUri by settingsStore.externalCacheTreeUri.collectAsState(initial = null)
    val scope = rememberCoroutineScope()

    MediaViewerScreen(
        posts = likedPosts,
        initialIndex = initialIndex,
        externalCacheEnabled = cacheEnabled,
        externalCacheTreeUri = cacheTreeUri,
        isLiked = { true },
        onToggleLike = { post -> scope.launch { favoritesStore.toggleLiked(post) } },
        onBack = onBack,
        onLaunchingExternalActivity = onLaunchingExternalActivity,
    )
}

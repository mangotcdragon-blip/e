package com.dailytools.calculator.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dailytools.calculator.data.FavoritesStore
import com.dailytools.calculator.ui.browser.PostThumbnail
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LikedScreen(
    favoritesStore: FavoritesStore,
    onOpenPost: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val likedPosts by favoritesStore.likedPosts.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Liked") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (likedPosts.isEmpty()) {
                Text(
                    text = "Nothing liked yet. Tap the heart on a post to save it here.",
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
            } else {
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(2),
                    contentPadding = PaddingValues(8.dp),
                    verticalItemSpacing = 8.dp,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(likedPosts.size) { index ->
                        val post = likedPosts[index]
                        PostThumbnail(
                            post = post,
                            onClick = { onOpenPost(index) },
                            isLiked = true,
                            onToggleLike = { scope.launch { favoritesStore.toggleLiked(post) } },
                        )
                    }
                }
            }
        }
    }
}

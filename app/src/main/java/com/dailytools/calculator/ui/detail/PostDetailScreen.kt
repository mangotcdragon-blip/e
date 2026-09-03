package com.dailytools.calculator.ui.detail

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dailytools.calculator.data.model.MediaKind
import com.dailytools.calculator.data.model.Post
import com.dailytools.calculator.ui.browser.BrowserUiState
import com.dailytools.calculator.ui.browser.BrowserViewModel
import com.dailytools.calculator.util.ExternalCache
import com.dailytools.calculator.util.downloadPost
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostDetailScreen(
    viewModel: BrowserViewModel,
    initialIndex: Int,
    onBack: () -> Unit,
    onLaunchingExternalActivity: () -> Unit,
) {
    val state = viewModel.uiState
    val posts = state.posts
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, (posts.size - 1).coerceAtLeast(0)),
    ) { posts.size }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isDownloading by remember { mutableStateOf(false) }

    // Doomscroll: remember exactly which post we're on, and keep the feed topped up.
    LaunchedEffect(pagerState.currentPage, posts.size) {
        posts.getOrNull(pagerState.currentPage)?.let { viewModel.setViewingPost(it.id) }
        if (posts.isNotEmpty() && pagerState.currentPage >= posts.size - 5) {
            viewModel.loadMore()
        }
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                actions = {
                    val current = posts.getOrNull(pagerState.currentPage)
                    IconButton(onClick = {
                        current?.let {
                            onLaunchingExternalActivity()
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, it.fileUrl)
                            }
                            context.startActivity(Intent.createChooser(intent, null))
                        }
                    }) {
                        Icon(Icons.Filled.Share, contentDescription = "Share", tint = Color.White)
                    }
                    IconButton(
                        enabled = !isDownloading,
                        onClick = {
                            current?.let { post ->
                                isDownloading = true
                                scope.launch {
                                    val result = downloadPost(context, post)
                                    isDownloading = false
                                    val message = if (result.isSuccess) "Saved" else "Download failed"
                                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                    ) {
                        Icon(Icons.Filled.Download, contentDescription = "Download", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black),
            )
        },
    ) { padding ->
        if (posts.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }
            return@Scaffold
        }

        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            VerticalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
            ) { page ->
                val post = posts[page]
                val resolvedUrl by rememberResolvedMediaUrl(context, state, post)
                when (post.mediaKind) {
                    MediaKind.VIDEO -> VideoPlayerView(url = resolvedUrl, modifier = Modifier.fillMaxSize())
                    else -> ZoomableImage(
                        model = resolvedUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            val current = posts.getOrNull(pagerState.currentPage)
            if (current != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF181818))
                        .padding(vertical = 8.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                    ) {
                        Text("${current.source.label} · score ${current.score}", color = Color.White)
                        Text(current.ratingLabel.ifBlank { "unrated" }, color = Color.Gray)
                    }
                    LazyRow(
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                    ) {
                        items(current.tags) { tag ->
                            AssistChip(onClick = {}, label = { Text(tag) })
                        }
                    }
                }
            }
        }

        if (isDownloading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/**
 * Resolves what to actually load for a post: an already-cached copy on the user's external
 * drive if one exists, otherwise the normal network URL - while kicking off a background copy
 * to that drive so it's cached for next time. Falls back to plain network loading whenever
 * external caching is off, no folder is chosen, or anything about the drive goes wrong.
 */
@Composable
private fun rememberResolvedMediaUrl(
    context: android.content.Context,
    state: BrowserUiState,
    post: Post,
) = produceState(initialValue = post.viewUrl, post.id, state.externalCacheEnabled, state.externalCacheTreeUri) {
    if (!state.externalCacheEnabled) {
        value = post.viewUrl
        return@produceState
    }
    val cachedFile = ExternalCache.cachedDecryptedFile(context, state.externalCacheTreeUri, post)
    if (cachedFile != null) {
        value = android.net.Uri.fromFile(cachedFile).toString()
    }
    ExternalCache.cacheInBackground(context, state.externalCacheTreeUri, post, post.viewUrl)
}

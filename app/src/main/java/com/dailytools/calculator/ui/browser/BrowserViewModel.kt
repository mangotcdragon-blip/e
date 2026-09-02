package com.dailytools.calculator.ui.browser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dailytools.calculator.data.SessionState
import com.dailytools.calculator.data.SettingsStore
import com.dailytools.calculator.data.model.MediaTypeFilter
import com.dailytools.calculator.data.model.Post
import com.dailytools.calculator.data.model.Rating
import com.dailytools.calculator.data.model.Source
import com.dailytools.calculator.data.model.SortOrder
import com.dailytools.calculator.data.repo.BooruRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Cap on how many pages we'll re-fetch on startup to rebuild a remembered position. */
private const val MAX_RESTORE_PAGES = 10

data class BrowserUiState(
    val source: Source = Source.E621,
    val queryInput: String = "",
    val appliedQuery: String = "",
    val rating: Rating = Rating.ALL,
    val mediaType: MediaTypeFilter = MediaTypeFilter.ALL,
    val sort: SortOrder = SortOrder.NEWEST,
    val posts: List<Post> = emptyList(),
    val page: Int = 0,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val endReached: Boolean = false,
    val error: String? = null,
    val suggestions: List<String> = emptyList(),
    val wasInDetail: Boolean = false,
    val lastViewedPostId: String? = null,
    val oneTimeRestoreIndex: Int? = null,
    val initialGridScrollIndex: Int = 0,
)

class BrowserViewModel(
    private val repository: BooruRepository,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    var uiState by mutableStateOf(BrowserUiState())
        private set

    private var suggestionJob: Job? = null

    init {
        viewModelScope.launch {
            val session = runCatching { settingsStore.sessionState.first() }.getOrNull()
            if (session != null && session.pagesLoaded > 0) {
                restoreSession(session)
            } else {
                refresh()
            }
        }
    }

    fun onQueryInputChange(text: String) {
        uiState = uiState.copy(queryInput = text)
        suggestionJob?.cancel()
        val token = text.substringAfterLast(' ').trim()
        if (token.length < 2) {
            uiState = uiState.copy(suggestions = emptyList())
            return
        }
        suggestionJob = viewModelScope.launch {
            delay(300)
            val results = repository.suggestTags(uiState.source, token)
            uiState = uiState.copy(suggestions = results)
        }
    }

    fun applySuggestion(tag: String) {
        val trimmed = uiState.queryInput.trimEnd()
        val lastSpace = trimmed.lastIndexOf(' ')
        val prefix = if (lastSpace >= 0) trimmed.substring(0, lastSpace + 1) else ""
        uiState = uiState.copy(queryInput = "$prefix$tag ", suggestions = emptyList())
        submitSearch()
    }

    fun submitSearch() {
        uiState = uiState.copy(appliedQuery = uiState.queryInput.trim(), suggestions = emptyList())
        persistFiltersAndRefresh()
    }

    fun setSource(source: Source) {
        if (source == uiState.source) return
        uiState = uiState.copy(source = source)
        persistFiltersAndRefresh()
    }

    fun setRating(rating: Rating) {
        uiState = uiState.copy(rating = rating)
        persistFiltersAndRefresh()
    }

    fun setMediaType(mediaType: MediaTypeFilter) {
        uiState = uiState.copy(mediaType = mediaType)
        persistFiltersAndRefresh()
    }

    fun setSort(sort: SortOrder) {
        uiState = uiState.copy(sort = sort)
        persistFiltersAndRefresh()
    }

    /** Marks whether the user is currently in the full-screen viewer, for resuming later. */
    fun setInDetailMode(inDetail: Boolean) {
        uiState = uiState.copy(wasInDetail = inDetail)
        viewModelScope.launch { settingsStore.saveInDetailFlag(inDetail) }
    }

    /** Called as the doomscroll pager settles on a post, so we know exactly where to resume. */
    fun setViewingPost(postId: String) {
        uiState = uiState.copy(lastViewedPostId = postId)
        viewModelScope.launch { settingsStore.saveLastPostId(postId) }
    }

    fun consumeOneTimeRestore() {
        if (uiState.oneTimeRestoreIndex != null) {
            uiState = uiState.copy(oneTimeRestoreIndex = null)
        }
    }

    fun updateGridScrollIndex(index: Int) {
        viewModelScope.launch { settingsStore.saveGridScrollIndex(index) }
    }

    fun refresh() {
        uiState = uiState.copy(
            posts = emptyList(),
            page = 0,
            endReached = false,
            error = null,
        )
        loadPage(append = false)
    }

    fun loadMore() {
        if (uiState.isLoading || uiState.isLoadingMore || uiState.endReached) return
        uiState = uiState.copy(page = uiState.page + 1)
        loadPage(append = true)
    }

    private fun persistFiltersAndRefresh() {
        val s = uiState
        viewModelScope.launch {
            settingsStore.saveFiltersAndResetPosition(s.source, s.appliedQuery, s.rating, s.mediaType, s.sort)
        }
        refresh()
    }

    private fun loadPage(append: Boolean) {
        val requestedPage = uiState.page
        uiState = if (append) uiState.copy(isLoadingMore = true) else uiState.copy(isLoading = true)
        viewModelScope.launch {
            val snapshot = uiState
            runCatching {
                repository.fetchPage(
                    source = snapshot.source,
                    query = snapshot.appliedQuery,
                    rating = snapshot.rating,
                    mediaType = snapshot.mediaType,
                    sort = snapshot.sort,
                    pageIndex = requestedPage,
                )
            }.onSuccess { newPosts ->
                uiState = uiState.copy(
                    posts = if (append) uiState.posts + newPosts else newPosts,
                    isLoading = false,
                    isLoadingMore = false,
                    endReached = newPosts.isEmpty(),
                    error = null,
                )
                if (newPosts.isNotEmpty()) {
                    viewModelScope.launch { settingsStore.savePagesLoaded(requestedPage + 1) }
                }
            }.onFailure { e ->
                uiState = uiState.copy(
                    isLoading = false,
                    isLoadingMore = false,
                    error = e.message ?: "Something went wrong",
                )
            }
        }
    }

    /** Re-fetches as many pages as were loaded last time, then jumps back to the exact post if one was open. */
    private fun restoreSession(session: SessionState) {
        uiState = uiState.copy(
            source = session.source,
            queryInput = session.query,
            appliedQuery = session.query,
            rating = session.rating,
            mediaType = session.mediaType,
            sort = session.sort,
            wasInDetail = session.wasInDetail,
            lastViewedPostId = session.lastPostId,
            initialGridScrollIndex = session.gridScrollIndex,
            isLoading = true,
        )
        viewModelScope.launch {
            val targetPages = session.pagesLoaded.coerceIn(1, MAX_RESTORE_PAGES)
            val collected = mutableListOf<Post>()
            var reachedEnd = false
            for (p in 0 until targetPages) {
                val batch = runCatching {
                    repository.fetchPage(
                        source = uiState.source,
                        query = uiState.appliedQuery,
                        rating = uiState.rating,
                        mediaType = uiState.mediaType,
                        sort = uiState.sort,
                        pageIndex = p,
                    )
                }.getOrElse { emptyList() }
                if (batch.isEmpty()) {
                    reachedEnd = true
                    break
                }
                collected += batch
            }
            val restoreIndex = if (session.wasInDetail && session.lastPostId != null) {
                collected.indexOfFirst { it.id == session.lastPostId }.takeIf { it >= 0 }
            } else {
                null
            }
            uiState = uiState.copy(
                posts = collected,
                page = (targetPages - 1).coerceAtLeast(0),
                isLoading = false,
                endReached = reachedEnd,
                oneTimeRestoreIndex = restoreIndex,
            )
        }
    }

    companion object {
        fun factory(repository: BooruRepository, settingsStore: SettingsStore) =
            object : androidx.lifecycle.ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    return BrowserViewModel(repository, settingsStore) as T
                }
            }
    }
}

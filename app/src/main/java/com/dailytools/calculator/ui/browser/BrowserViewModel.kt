package com.dailytools.calculator.ui.browser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dailytools.calculator.data.SettingsStore
import com.dailytools.calculator.data.model.MediaTypeFilter
import com.dailytools.calculator.data.model.Post
import com.dailytools.calculator.data.model.Rating
import com.dailytools.calculator.data.model.Source
import com.dailytools.calculator.data.model.SortOrder
import com.dailytools.calculator.data.repo.BooruRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
)

class BrowserViewModel(
    private val repository: BooruRepository,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    var uiState by mutableStateOf(BrowserUiState())
        private set

    init {
        viewModelScope.launch {
            val source = runCatching { settingsStore.lastSource.first() }.getOrDefault(Source.E621)
            uiState = uiState.copy(source = source)
            refresh()
        }
    }

    fun onQueryInputChange(text: String) {
        uiState = uiState.copy(queryInput = text)
    }

    fun submitSearch() {
        uiState = uiState.copy(appliedQuery = uiState.queryInput.trim())
        refresh()
    }

    fun setSource(source: Source) {
        if (source == uiState.source) return
        uiState = uiState.copy(source = source)
        viewModelScope.launch { settingsStore.setLastSource(source) }
        refresh()
    }

    fun setRating(rating: Rating) {
        uiState = uiState.copy(rating = rating)
        refresh()
    }

    fun setMediaType(mediaType: MediaTypeFilter) {
        uiState = uiState.copy(mediaType = mediaType)
        refresh()
    }

    fun setSort(sort: SortOrder) {
        uiState = uiState.copy(sort = sort)
        refresh()
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
            }.onFailure { e ->
                uiState = uiState.copy(
                    isLoading = false,
                    isLoadingMore = false,
                    error = e.message ?: "Something went wrong",
                )
            }
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

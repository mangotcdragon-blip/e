package com.dailytools.calculator.ui.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dailytools.calculator.data.model.MediaTypeFilter
import com.dailytools.calculator.data.model.Rating
import com.dailytools.calculator.data.model.Source
import com.dailytools.calculator.data.model.SortOrder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSheet(
    state: BrowserUiState,
    onDismiss: () -> Unit,
    onSourceChange: (Source) -> Unit,
    onRatingChange: (Rating) -> Unit,
    onMediaTypeChange: (MediaTypeFilter) -> Unit,
    onSortChange: (SortOrder) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            Text("Source", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
            ChipRow(
                options = Source.entries,
                selected = state.source,
                label = { it.label },
                onSelect = onSourceChange,
            )

            Text(
                "Rating",
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 20.dp),
            )
            ChipRow(
                options = Rating.entries,
                selected = state.rating,
                label = { it.label },
                onSelect = onRatingChange,
            )

            Text(
                "Media type",
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 20.dp),
            )
            ChipRow(
                options = MediaTypeFilter.entries,
                selected = state.mediaType,
                label = { it.label },
                onSelect = onMediaTypeChange,
            )

            Text(
                "Sort" + if (state.source == Source.RULE34) " (e621 only)" else "",
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 20.dp),
            )
            ChipRow(
                options = SortOrder.entries,
                selected = state.sort,
                label = { it.label },
                enabled = state.source == Source.E621,
                onSelect = onSortChange,
            )
        }
    }
}

@Composable
private fun <T> ChipRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    enabled: Boolean = true,
    onSelect: (T) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 8.dp),
    ) {
        items(options.size) { index ->
            val option = options[index]
            FilterChip(
                selected = option == selected,
                enabled = enabled,
                onClick = { onSelect(option) },
                label = { Text(label(option)) },
            )
        }
    }
}

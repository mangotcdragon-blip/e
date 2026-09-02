package com.dailytools.calculator.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dailytools.calculator.data.SettingsStore
import com.dailytools.calculator.data.ThemeMode
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(settingsStore: SettingsStore, onBack: () -> Unit) {
    val themeMode by settingsStore.themeMode.collectAsState(initial = ThemeMode.DARK)
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Text(
                "Theme",
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(16.dp),
            )
            ThemeMode.entries.forEach { mode ->
                ListItem(
                    headlineContent = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    leadingContent = {
                        RadioButton(
                            selected = mode == themeMode,
                            onClick = { scope.launch { settingsStore.setThemeMode(mode) } },
                        )
                    },
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
    }
}

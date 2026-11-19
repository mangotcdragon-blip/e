package com.dailytools.calculator.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dailytools.calculator.data.SettingsStore
import com.dailytools.calculator.data.ThemeMode
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settingsStore: SettingsStore,
    onBack: () -> Unit,
    onLaunchingExternalActivity: () -> Unit,
) {
    val themeMode by settingsStore.themeMode.collectAsState(initial = ThemeMode.DARK)
    val cacheEnabled by settingsStore.externalCacheEnabled.collectAsState(initial = false)
    val cacheTreeUri by settingsStore.externalCacheTreeUri.collectAsState(initial = null)
    val blacklistedTags by settingsStore.blacklistedTags.collectAsState(initial = emptySet())
    var blacklistInput by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            scope.launch {
                settingsStore.setExternalCacheTreeUri(uri.toString())
                settingsStore.setExternalCacheEnabled(true)
            }
        }
    }

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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                "Theme",
                style = MaterialTheme.typography.titleLarge,
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

            Text(
                "External drive cache",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 24.dp, start = 16.dp, end = 16.dp),
            )
            Text(
                "Save viewed photos and videos to a folder on a connected USB/OTG drive so they " +
                    "don't need to be re-downloaded. Needs a folder to be picked below.",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            ListItem(
                headlineContent = { Text("Cache to external drive") },
                supportingContent = {
                    Text(cacheTreeUri?.let { folderLabelFor(it) } ?: "No folder selected")
                },
                trailingContent = {
                    Switch(
                        checked = cacheEnabled && cacheTreeUri != null,
                        onCheckedChange = { checked ->
                            scope.launch { settingsStore.setExternalCacheEnabled(checked) }
                        },
                        enabled = cacheTreeUri != null,
                    )
                },
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
            ) {
                Button(onClick = {
                    onLaunchingExternalActivity()
                    folderPicker.launch(null)
                }) {
                    Text(if (cacheTreeUri == null) "Choose folder" else "Change folder")
                }
                if (cacheTreeUri != null) {
                    Button(onClick = {
                        scope.launch {
                            settingsStore.setExternalCacheEnabled(false)
                            settingsStore.setExternalCacheTreeUri(null)
                        }
                    }) {
                        Text("Remove")
                    }
                }
            }

            Text(
                "Blacklisted tags",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 24.dp, start = 16.dp, end = 16.dp),
            )
            Text(
                "Posts tagged with any of these are excluded from every search, including For You.",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = blacklistInput,
                    onValueChange = { blacklistInput = it },
                    label = { Text("Add tag") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        val tag = blacklistInput.trim()
                        if (tag.isNotEmpty()) {
                            scope.launch { settingsStore.setBlacklistedTags(blacklistedTags + tag) }
                            blacklistInput = ""
                        }
                    },
                ) {
                    Text("Add")
                }
            }
            if (blacklistedTags.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    blacklistedTags.forEach { tag ->
                        AssistChip(
                            onClick = { scope.launch { settingsStore.setBlacklistedTags(blacklistedTags - tag) } },
                            label = { Text(tag) },
                            trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Remove") },
                        )
                    }
                }
            }
        }
    }
}

private fun folderLabelFor(treeUriString: String): String {
    val segment = runCatching { Uri.parse(treeUriString).lastPathSegment }.getOrNull() ?: return treeUriString
    return runCatching { Uri.decode(segment) }.getOrDefault(segment)
}

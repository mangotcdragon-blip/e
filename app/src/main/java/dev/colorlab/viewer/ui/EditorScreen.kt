package dev.colorlab.viewer.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.colorlab.viewer.EditorViewModel
import dev.colorlab.viewer.LoadedMedia
import dev.colorlab.viewer.color.ColorAdjustments

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(viewModel: EditorViewModel) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var openMenuExpanded by remember { mutableStateOf(false) }
    var showOriginal by remember { mutableStateOf(false) }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(viewModel::open)
    }
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::open)
    }
    val requestWritePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.saveImage()
    }

    fun pickFromGallery() = pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
    fun browseFiles() = openDocument.launch(arrayOf("image/*", "video/*"))
    fun save() {
        val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) requestWritePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) else viewModel.saveImage()
    }

    LaunchedEffect(viewModel.message) {
        val text = viewModel.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        viewModel.clearMessage()
    }

    // Don't keep decoding video while the app is in the background.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.player.pause() }

    val media = viewModel.media

    if (media != null && viewModel.isFullscreen) {
        FullscreenPlayer(
            viewModel = viewModel,
            media = media,
            onExit = { viewModel.isFullscreen = false },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ColorLab") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                actions = {
                    if (media != null) {
                        IconButton(onClick = { viewModel.isFullscreen = true }) {
                            Icon(Icons.Filled.Fullscreen, contentDescription = "Fullscreen")
                        }
                        IconButton(onClick = viewModel::resetAdjustments, enabled = !viewModel.adjustments.isDefault) {
                            Icon(Icons.Filled.RestartAlt, contentDescription = "Reset all adjustments")
                        }
                        if (media is LoadedMedia.Image) {
                            IconButton(onClick = ::save, enabled = !viewModel.isLoading) {
                                Icon(Icons.Filled.Save, contentDescription = "Save image")
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = { openMenuExpanded = true }) {
                            Icon(Icons.Filled.FolderOpen, contentDescription = "Open media")
                        }
                        DropdownMenu(expanded = openMenuExpanded, onDismissRequest = { openMenuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("Photos and videos") },
                                leadingIcon = { Icon(Icons.Filled.PhotoLibrary, contentDescription = null) },
                                onClick = { openMenuExpanded = false; pickFromGallery() },
                            )
                            DropdownMenuItem(
                                text = { Text("Browse files") },
                                leadingIcon = { Icon(Icons.Filled.FolderOpen, contentDescription = null) },
                                onClick = { openMenuExpanded = false; browseFiles() },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (media == null) {
            EmptyState(
                isLoading = viewModel.isLoading,
                onPickFromGallery = ::pickFromGallery,
                onBrowseFiles = ::browseFiles,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        } else {
            EditorContent(
                viewModel = viewModel,
                media = media,
                showOriginal = showOriginal,
                onShowOriginalChange = { showOriginal = it },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        }
    }
}

@Composable
private fun EditorContent(
    viewModel: EditorViewModel,
    media: LoadedMedia,
    showOriginal: Boolean,
    onShowOriginalChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val effective = if (showOriginal) ColorAdjustments.Default else viewModel.adjustments

    BoxWithConstraints(modifier = modifier) {
        val landscape = maxWidth > maxHeight
        val portraitPreviewHeight = maxHeight * 0.42f

        val preview: @Composable (Modifier) -> Unit = { previewModifier ->
            Box(modifier = previewModifier) {
                MediaPreview(
                    media = media,
                    adjustments = effective,
                    player = viewModel.player,
                    videoState = viewModel.videoState,
                    isLoading = viewModel.isLoading,
                    modifier = Modifier.fillMaxSize(),
                )
                CompareButton(
                    active = showOriginal,
                    onActiveChange = onShowOriginalChange,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp),
                )
                Surface(
                    color = Color.Black.copy(alpha = 0.6f),
                    contentColor = Color.White,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                ) {
                    IconButton(onClick = { viewModel.isFullscreen = true }) {
                        Icon(Icons.Filled.Fullscreen, contentDescription = "Fullscreen")
                    }
                }
            }
        }

        val controls: @Composable (Modifier) -> Unit = { controlsModifier ->
            Column(modifier = controlsModifier) {
                if (media is LoadedMedia.Video) {
                    VideoControls(
                        state = viewModel.videoState,
                        onPlayPause = viewModel::togglePlayPause,
                        onSeek = viewModel::seekTo,
                        onSeekBy = viewModel::seekBy,
                        onLoopChange = viewModel::setLoop,
                        onMuteChange = viewModel::setMuted,
                        onSpeedChange = viewModel::setSpeed,
                        onAudioTrackSelect = viewModel::selectAudioTrack,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                AdjustmentPanel(
                    adjustments = viewModel.adjustments,
                    onChange = { viewModel.adjustments = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }
        }

        if (landscape) {
            Row(modifier = Modifier.fillMaxSize()) {
                preview(Modifier.weight(1.3f).fillMaxHeight())
                controls(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                preview(Modifier.fillMaxWidth().height(portraitPreviewHeight))
                controls(Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}

/** Press and hold to see the unedited media. */
@Composable
internal fun CompareButton(
    active: Boolean,
    onActiveChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = if (active) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.6f),
        contentColor = if (active) MaterialTheme.colorScheme.onPrimary else Color.White,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.pointerInput(Unit) {
            detectTapGestures(onPress = {
                onActiveChange(true)
                try {
                    tryAwaitRelease()
                } finally {
                    onActiveChange(false)
                }
            })
        },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Icon(Icons.Filled.Compare, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(if (active) "Original" else "Hold to compare", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun EmptyState(
    isLoading: Boolean,
    onPickFromGallery: () -> Unit,
    onBrowseFiles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.Palette,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text("ColorLab", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Open a photo or video and dial in exposure, contrast, saturation, hue, temperature and more. Every change shows live, even during playback.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = onPickFromGallery, enabled = !isLoading) {
            Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text("Photos and videos")
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onBrowseFiles, enabled = !isLoading) {
            Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text("Browse files")
        }
        if (isLoading) {
            Spacer(Modifier.height(24.dp))
            androidx.compose.material3.CircularProgressIndicator()
        }
    }
}

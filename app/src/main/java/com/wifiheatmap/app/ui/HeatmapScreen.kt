package com.wifiheatmap.app.ui

import android.graphics.BitmapFactory
import android.graphics.Paint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Image as ImageIcon
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wifiheatmap.app.data.CellResult
import kotlin.math.roundToInt

private val SpeedRed = Color(0xFFD32F2F)
private val SpeedGreen = Color(0xFF2E7D32)

@Composable
fun App(viewModel: HeatmapViewModel) {
    val heatmapState by viewModel.heatmapState.collectAsState()

    if (heatmapState.imagePath == null) {
        UploadScreen(onImagePicked = viewModel::onImagePicked)
    } else {
        HeatmapScreen(viewModel = viewModel)
    }
}

@Composable
private fun UploadScreen(onImagePicked: (android.net.Uri) -> Unit) {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri -> uri?.let(onImagePicked) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.CloudDownload,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "WiFi Heatmap",
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Upload a floor plan, lay a grid over it, then walk around " +
                    "testing your real download speed in each square.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = { launcher.launch("image/*") }) {
                Icon(Icons.Filled.ImageIcon, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Upload Floor Plan")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HeatmapScreen(viewModel: HeatmapViewModel) {
    val heatmapState by viewModel.heatmapState.collectAsState()
    val selectedCell by viewModel.selectedCell.collectAsState()
    val isTesting by viewModel.isTesting.collectAsState()
    val testProgress by viewModel.testProgress.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val changeMapLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri -> uri?.let(viewModel::onImagePicked) }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WiFi Heatmap") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White
                ),
                actions = {
                    IconButton(onClick = { changeMapLauncher.launch("image/*") }) {
                        Icon(Icons.Filled.SwapHoriz, contentDescription = "Change floor plan", tint = Color.White)
                    }
                    IconButton(onClick = viewModel::clearResults) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear results", tint = Color.White)
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(it) } }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            GridSizeControl(
                gridSize = heatmapState.gridSize,
                hasResults = heatmapState.results.isNotEmpty(),
                onGridSizeChange = viewModel::setGridSize
            )

            Spacer(Modifier.height(12.dp))

            Legend(results = heatmapState.results.values)

            Spacer(Modifier.height(12.dp))

            FloorPlanGrid(
                imagePath = heatmapState.imagePath!!,
                gridSize = heatmapState.gridSize,
                results = heatmapState.results,
                selectedCell = selectedCell,
                onCellTap = viewModel::selectCell
            )

            Spacer(Modifier.height(16.dp))

            SpeedTestPanel(
                selectedCell = selectedCell,
                isTesting = isTesting,
                progressBytes = testProgress?.bytesSoFar ?: 0L,
                progressMs = testProgress?.elapsedMs ?: 0L,
                onRunTest = viewModel::runSpeedTestOnSelectedCell
            )
        }
    }
}

@Composable
private fun GridSizeControl(gridSize: Int, hasResults: Boolean, onGridSizeChange: (Int) -> Unit) {
    var sliderValue by remember(gridSize) { mutableStateOf(gridSize.toFloat()) }
    Column {
        Text(
            "Grid fineness: ${gridSize}×${gridSize} squares",
            style = MaterialTheme.typography.titleMedium
        )
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = { onGridSizeChange(sliderValue.roundToInt()) },
            valueRange = 2f..20f,
            steps = 17
        )
        if (hasResults) {
            Text(
                "Changing the grid size clears existing test results.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun Legend(results: Collection<CellResult>) {
    val min = results.minOfOrNull { it.mbps }
    val max = results.maxOfOrNull { it.mbps }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(Brush.horizontalGradient(listOf(SpeedRed, SpeedGreen)))
        ) {}
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                if (min != null) "Slowest: ${formatMbps(min)}" else "Slowest: –",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                if (max != null) "Fastest: ${formatMbps(max)}" else "Fastest: –",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun FloorPlanGrid(
    imagePath: String,
    gridSize: Int,
    results: Map<Pair<Int, Int>, CellResult>,
    selectedCell: Pair<Int, Int>?,
    onCellTap: (Int, Int) -> Unit
) {
    val bitmap = remember(imagePath) { BitmapFactory.decodeFile(imagePath) }
    if (bitmap == null) {
        Text("Couldn't load this image.", color = MaterialTheme.colorScheme.error)
        return
    }
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
    val aspectRatio = bitmap.width.toFloat() / bitmap.height.toFloat()

    val speeds = results.values.map { it.mbps }
    val minMbps = speeds.minOrNull() ?: 0.0
    val maxMbps = speeds.maxOrNull() ?: 0.0
    val density = LocalDensity.current
    val labelSizePx = with(density) { 12.sp.toPx() }
    val gridLineWidthPx = with(density) { 1.dp.toPx() }
    val selectionStrokePx = with(density) { 3.dp.toPx() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio)
            .border(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Image(
            bitmap = imageBitmap,
            contentDescription = "Floor plan",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds
        )
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(gridSize) {
                    detectTapGestures { offset ->
                        val cellW = size.width.toFloat() / gridSize
                        val cellH = size.height.toFloat() / gridSize
                        val col = (offset.x / cellW).toInt().coerceIn(0, gridSize - 1)
                        val row = (offset.y / cellH).toInt().coerceIn(0, gridSize - 1)
                        onCellTap(row, col)
                    }
                }
        ) {
            val cellW = this.size.width / gridSize
            val cellH = this.size.height / gridSize

            results.values.forEach { result ->
                val topLeft = Offset(result.col * cellW, result.row * cellH)
                drawRect(
                    color = colorForSpeed(result.mbps, minMbps, maxMbps).copy(alpha = 0.6f),
                    topLeft = topLeft,
                    size = Size(cellW, cellH)
                )
                drawContext.canvas.nativeCanvas.drawText(
                    formatMbpsShort(result.mbps),
                    topLeft.x + cellW / 2f,
                    topLeft.y + cellH / 2f + labelSizePx / 3f,
                    Paint().apply {
                        color = android.graphics.Color.WHITE
                        textSize = labelSizePx
                        textAlign = Paint.Align.CENTER
                        isAntiAlias = true
                        setShadowLayer(4f, 0f, 0f, android.graphics.Color.BLACK)
                    }
                )
            }

            for (i in 0..gridSize) {
                drawLine(
                    color = Color.White.copy(alpha = 0.85f),
                    start = Offset(i * cellW, 0f),
                    end = Offset(i * cellW, this.size.height),
                    strokeWidth = gridLineWidthPx
                )
                drawLine(
                    color = Color.White.copy(alpha = 0.85f),
                    start = Offset(0f, i * cellH),
                    end = Offset(this.size.width, i * cellH),
                    strokeWidth = gridLineWidthPx
                )
            }

            selectedCell?.let { (row, col) ->
                drawRect(
                    color = Color.Yellow,
                    topLeft = Offset(col * cellW, row * cellH),
                    size = Size(cellW, cellH),
                    style = Stroke(width = selectionStrokePx)
                )
            }
        }
    }
}

@Composable
private fun SpeedTestPanel(
    selectedCell: Pair<Int, Int>?,
    isTesting: Boolean,
    progressBytes: Long,
    progressMs: Long,
    onRunTest: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(
            when {
                selectedCell == null -> "Tap the square you're standing in"
                else -> "Selected square: row ${selectedCell.first + 1}, column ${selectedCell.second + 1}"
            },
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onRunTest,
            enabled = !isTesting && selectedCell != null,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Icon(Icons.Filled.CloudDownload, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (isTesting) "Testing…" else "Run Speed Test Here")
        }
        if (isTesting) {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(0.8f))
            Spacer(Modifier.height(8.dp))
            val liveMbps = if (progressMs > 0) (progressBytes * 8) / (progressMs / 1000.0) / 1_000_000.0 else 0.0
            Text(
                "${formatMbps(liveMbps)}  ·  ${formatBytes(progressBytes)} downloaded",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

private fun colorForSpeed(mbps: Double, min: Double, max: Double): Color {
    val t = if (max > min) ((mbps - min) / (max - min)).toFloat().coerceIn(0f, 1f) else 1f
    return lerp(SpeedRed, SpeedGreen, t)
}

private fun formatMbps(mbps: Double): String = "%.1f Mbps".format(mbps)

private fun formatMbpsShort(mbps: Double): String = "%.0f".format(mbps)

private fun formatBytes(bytes: Long): String {
    val mb = bytes / 1_000_000.0
    return if (mb >= 1.0) "%.1f MB".format(mb) else "${bytes / 1000} KB"
}

package com.wifiheatmap.app.data

/** One completed speed test pinned to a grid cell. */
data class CellResult(
    val row: Int,
    val col: Int,
    val mbps: Double,
    val timestampMillis: Long
)

/** Everything needed to redraw the heatmap and survive process death. */
data class HeatmapState(
    val imagePath: String? = null,
    val gridSize: Int = 6,
    val results: Map<Pair<Int, Int>, CellResult> = emptyMap()
)

package com.wifiheatmap.app.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists the floor plan image (copied into app-private storage, since the
 * source content:// Uri from the picker is not guaranteed to survive a
 * restart) and the grid/test-result state as a small JSON file.
 */
class HeatmapRepository(private val context: Context) {

    private val stateFile: File
        get() = File(context.filesDir, "heatmap_state.json")

    private val imagesDir: File
        get() = File(context.filesDir, "floorplans").apply { mkdirs() }

    fun load(): HeatmapState {
        if (!stateFile.exists()) return HeatmapState()
        return try {
            val json = JSONObject(stateFile.readText())
            val gridSize = json.optInt("gridSize", 6)
            val imagePath = json.optString("imagePath", "").ifBlank { null }
            val resultsJson = json.optJSONArray("results") ?: JSONArray()
            val results = mutableMapOf<Pair<Int, Int>, CellResult>()
            for (i in 0 until resultsJson.length()) {
                val entry = resultsJson.getJSONObject(i)
                val row = entry.getInt("row")
                val col = entry.getInt("col")
                results[row to col] = CellResult(
                    row = row,
                    col = col,
                    mbps = entry.getDouble("mbps"),
                    timestampMillis = entry.optLong("timestamp", 0L)
                )
            }
            val validImagePath = imagePath?.takeIf { File(it).exists() }
            HeatmapState(imagePath = validImagePath, gridSize = gridSize, results = results)
        } catch (e: Exception) {
            HeatmapState()
        }
    }

    fun save(state: HeatmapState) {
        val json = JSONObject()
        json.put("gridSize", state.gridSize)
        json.put("imagePath", state.imagePath ?: "")
        val resultsJson = JSONArray()
        state.results.values.forEach { result ->
            resultsJson.put(
                JSONObject()
                    .put("row", result.row)
                    .put("col", result.col)
                    .put("mbps", result.mbps)
                    .put("timestamp", result.timestampMillis)
            )
        }
        json.put("results", resultsJson)
        stateFile.writeText(json.toString())
    }

    /** Copies the picked image into app-private storage and returns its path, or null on failure. */
    fun copyImageToInternalStorage(sourceUri: Uri): String? {
        return try {
            val destFile = File(imagesDir, "floorplan_${System.currentTimeMillis()}.img")
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            destFile.absolutePath
        } catch (e: Exception) {
            null
        }
    }
}

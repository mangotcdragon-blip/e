package com.wifiheatmap.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wifiheatmap.app.data.CellResult
import com.wifiheatmap.app.data.HeatmapRepository
import com.wifiheatmap.app.data.HeatmapState
import com.wifiheatmap.app.network.SpeedTestClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class TestProgress(val bytesSoFar: Long, val elapsedMs: Long)

class HeatmapViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = HeatmapRepository(application)

    private val _heatmapState = MutableStateFlow(repository.load())
    val heatmapState: StateFlow<HeatmapState> = _heatmapState.asStateFlow()

    private val _selectedCell = MutableStateFlow<Pair<Int, Int>?>(null)
    val selectedCell: StateFlow<Pair<Int, Int>?> = _selectedCell.asStateFlow()

    private val _isTesting = MutableStateFlow(false)
    val isTesting: StateFlow<Boolean> = _isTesting.asStateFlow()

    private val _testProgress = MutableStateFlow<TestProgress?>(null)
    val testProgress: StateFlow<TestProgress?> = _testProgress.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun onImagePicked(uri: Uri) {
        val path = repository.copyImageToInternalStorage(uri)
        if (path == null) {
            _errorMessage.value = "Could not load that image"
            return
        }
        val newState = HeatmapState(imagePath = path, gridSize = _heatmapState.value.gridSize)
        _heatmapState.value = newState
        _selectedCell.value = null
        repository.save(newState)
    }

    fun setGridSize(size: Int) {
        val clamped = size.coerceIn(2, 20)
        if (clamped == _heatmapState.value.gridSize) return
        // Cell coordinates are only meaningful for the grid they were measured on.
        val newState = _heatmapState.value.copy(gridSize = clamped, results = emptyMap())
        _heatmapState.value = newState
        _selectedCell.value = null
        repository.save(newState)
    }

    fun selectCell(row: Int, col: Int) {
        _selectedCell.value = row to col
    }

    fun clearResults() {
        val newState = _heatmapState.value.copy(results = emptyMap())
        _heatmapState.value = newState
        repository.save(newState)
    }

    fun clearImage() {
        val newState = HeatmapState()
        _heatmapState.value = newState
        _selectedCell.value = null
        repository.save(newState)
    }

    fun runSpeedTestOnSelectedCell() {
        val cell = _selectedCell.value
        if (cell == null) {
            _errorMessage.value = "Tap a square on the map first"
            return
        }
        if (_isTesting.value) return

        _isTesting.value = true
        _testProgress.value = TestProgress(0, 0)
        _errorMessage.value = null

        viewModelScope.launch {
            val result = SpeedTestClient.measureDownloadSpeed { bytesSoFar, elapsedMs ->
                _testProgress.value = TestProgress(bytesSoFar, elapsedMs)
            }
            when (result) {
                is SpeedTestClient.SpeedTestResult.Success -> {
                    val (row, col) = cell
                    val updated = _heatmapState.value.results.toMutableMap()
                    updated[row to col] = CellResult(row, col, result.mbps, System.currentTimeMillis())
                    val newState = _heatmapState.value.copy(results = updated)
                    _heatmapState.value = newState
                    repository.save(newState)
                }
                is SpeedTestClient.SpeedTestResult.Failure -> {
                    _errorMessage.value = result.message
                }
            }
            _isTesting.value = false
            _testProgress.value = null
        }
    }

    fun consumeError() {
        _errorMessage.value = null
    }
}

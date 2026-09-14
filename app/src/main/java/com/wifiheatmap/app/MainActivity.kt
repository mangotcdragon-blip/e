package com.wifiheatmap.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wifiheatmap.app.ui.App
import com.wifiheatmap.app.ui.HeatmapViewModel
import com.wifiheatmap.app.ui.theme.WifiHeatmapTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WifiHeatmapApp()
        }
    }
}

@Composable
private fun WifiHeatmapApp(viewModel: HeatmapViewModel = viewModel()) {
    WifiHeatmapTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            App(viewModel = viewModel)
        }
    }
}

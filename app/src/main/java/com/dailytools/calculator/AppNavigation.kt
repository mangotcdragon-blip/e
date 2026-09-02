package com.dailytools.calculator

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dailytools.calculator.calculator.CalculatorScreen
import com.dailytools.calculator.calculator.CalculatorViewModel
import com.dailytools.calculator.data.SettingsStore
import com.dailytools.calculator.data.ThemeMode
import com.dailytools.calculator.data.model.Post
import com.dailytools.calculator.data.repo.BooruRepository
import com.dailytools.calculator.ui.SettingsScreen
import com.dailytools.calculator.ui.browser.BrowserScreen
import com.dailytools.calculator.ui.browser.BrowserViewModel
import com.dailytools.calculator.ui.detail.PostDetailScreen
import com.dailytools.calculator.ui.theme.CalculatorTheme
import com.dailytools.calculator.ui.theme.GalleryTheme

private sealed class Screen {
    data object Calculator : Screen()
    data object Browser : Screen()
    data object Settings : Screen()
    data class Detail(val posts: List<Post>, val index: Int) : Screen()
}

@Composable
fun AppRoot(
    unlocked: Boolean,
    onUnlock: () -> Unit,
    settingsStore: SettingsStore,
) {
    var screen by remember { mutableStateOf<Screen>(Screen.Calculator) }
    val themeMode by settingsStore.themeMode.collectAsState(initial = ThemeMode.DARK)

    LaunchedEffect(unlocked) {
        screen = if (unlocked) Screen.Browser else Screen.Calculator
    }

    when (val current = screen) {
        Screen.Calculator -> {
            CalculatorTheme {
                val calculatorViewModel: CalculatorViewModel = viewModel()
                CalculatorScreen(viewModel = calculatorViewModel, onUnlock = onUnlock)
            }
        }

        Screen.Browser -> {
            GalleryTheme(themeMode) {
                val repository = remember { BooruRepository() }
                val browserViewModel: BrowserViewModel = viewModel(
                    factory = BrowserViewModel.factory(repository, settingsStore),
                )
                BrowserScreen(
                    viewModel = browserViewModel,
                    onOpenPost = { posts, index -> screen = Screen.Detail(posts, index) },
                    onOpenSettings = { screen = Screen.Settings },
                )
            }
        }

        Screen.Settings -> {
            GalleryTheme(themeMode) {
                BackHandler { screen = Screen.Browser }
                SettingsScreen(settingsStore = settingsStore, onBack = { screen = Screen.Browser })
            }
        }

        is Screen.Detail -> {
            GalleryTheme(themeMode) {
                BackHandler { screen = Screen.Browser }
                PostDetailScreen(
                    posts = current.posts,
                    initialIndex = current.index,
                    onBack = { screen = Screen.Browser },
                )
            }
        }
    }
}

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
    data class Detail(val index: Int) : Screen()
}

@Composable
fun AppRoot(
    unlocked: Boolean,
    onUnlock: () -> Unit,
    settingsStore: SettingsStore,
) {
    var screen by remember { mutableStateOf<Screen>(Screen.Calculator) }
    val themeMode by settingsStore.themeMode.collectAsState(initial = ThemeMode.DARK)

    // Hoisted above the calculator/browser split so the browsing session (and its
    // in-flight restore-from-disk fetch) survives every re-lock, not just navigation.
    val repository = remember { BooruRepository() }
    val browserViewModel: BrowserViewModel = viewModel(
        factory = BrowserViewModel.factory(repository, settingsStore),
    )

    LaunchedEffect(unlocked) {
        if (unlocked) {
            val s = browserViewModel.uiState
            val idx = if (s.wasInDetail && s.lastViewedPostId != null) {
                s.posts.indexOfFirst { it.id == s.lastViewedPostId }
            } else {
                -1
            }
            screen = if (idx >= 0) Screen.Detail(idx) else Screen.Browser
        } else {
            screen = Screen.Calculator
        }
    }

    // Covers a cold start: the disk-backed restore can still be loading posts when the
    // unlock happens, so jump into the viewer as soon as it's ready instead of staying
    // on the grid.
    LaunchedEffect(browserViewModel.uiState.oneTimeRestoreIndex) {
        val idx = browserViewModel.uiState.oneTimeRestoreIndex ?: return@LaunchedEffect
        if (unlocked && screen == Screen.Browser) {
            screen = Screen.Detail(idx)
        }
        browserViewModel.consumeOneTimeRestore()
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
                BrowserScreen(
                    viewModel = browserViewModel,
                    onOpenPost = { index ->
                        browserViewModel.setInDetailMode(true)
                        screen = Screen.Detail(index)
                    },
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
                val exitDetail = {
                    browserViewModel.setInDetailMode(false)
                    screen = Screen.Browser
                }
                BackHandler(onBack = exitDetail)
                PostDetailScreen(
                    viewModel = browserViewModel,
                    initialIndex = current.index,
                    onBack = exitDetail,
                )
            }
        }
    }
}

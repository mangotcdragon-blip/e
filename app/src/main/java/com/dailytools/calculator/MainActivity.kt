package com.dailytools.calculator

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dailytools.calculator.data.SettingsStore

class MainActivity : ComponentActivity() {

    private var unlocked by mutableStateOf(false)
    private lateinit var settingsStore: SettingsStore

    // Set right before launching a picker/share sheet of our own (folder picker, share intent)
    // so the resulting onStop doesn't re-lock the app before it gets its answer back.
    private var suppressNextRelock = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsStore = SettingsStore(applicationContext)

        setContent {
            LaunchedEffect(unlocked) {
                if (unlocked) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
            AppRoot(
                unlocked = unlocked,
                onUnlock = { unlocked = true },
                onLaunchingExternalActivity = { suppressNextRelock = true },
                settingsStore = settingsStore,
            )
        }
    }

    override fun onStop() {
        super.onStop()
        // Re-lock whenever the app leaves the foreground so reopening it always
        // shows the calculator, never the gallery - unless we're the ones who just
        // launched a picker/share sheet and are expecting to come straight back.
        if (suppressNextRelock) {
            suppressNextRelock = false
        } else {
            unlocked = false
        }
    }
}

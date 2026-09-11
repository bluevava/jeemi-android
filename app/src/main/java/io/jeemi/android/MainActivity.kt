// Author: Bluevava
// Open-source repository: https://github.com/bluevava/jeemi-android

package io.jeemi.android

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.jeemi.android.ui.JeemiApp
import io.jeemi.android.ui.JeemiViewModel
import io.jeemi.android.ui.theme.JeemiTheme
import io.jeemi.android.ui.theme.isJeemiDark

class MainActivity : AppCompatActivity() {
    // Locale/layoutDirection are delivered to Compose through LocalConfiguration.
    // Keep the active editor and input session when switching the application language.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val model: JeemiViewModel = viewModel()
            val state by model.state.collectAsStateWithLifecycle()
            val dark = isJeemiDark(state.library.preferences.theme)
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            JeemiTheme(state.library.preferences.theme) { JeemiApp(state, model) }
        }
    }
}

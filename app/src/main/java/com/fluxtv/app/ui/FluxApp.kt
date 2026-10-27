package com.fluxtv.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fluxtv.app.ui.admin.AdminScreen
import com.fluxtv.app.ui.easymode.EasyModeScreen
import com.fluxtv.app.ui.startup.StartingScreen
import com.fluxtv.app.ui.theme.FluxTheme
import com.fluxtv.app.ui.theme.ScaledText

/**
 * Routes between the three top-level modes (§25): STARTING, EASY (watching), ADMIN. What the Viewer
 * sees takes the text size of Preferences; Settings, which is for the administrator, keeps its own.
 */
@Composable
fun FluxApp(vm: AppViewModel, onExit: () -> Unit) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    FluxTheme {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val textScale = state.settings.textSizePercent / 100f
            when (state.mode) {
                AppMode.STARTING -> ScaledText(textScale) { StartingScreen(state.startupMessage) }
                AppMode.EASY -> ScaledText(textScale) { EasyModeScreen(state, vm, onExit) }
                AppMode.ADMIN -> AdminScreen(state, vm, onExit)
            }
        }
    }
}

package com.fluxtv.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fluxtv.app.ui.admin.AdminScreen
import com.fluxtv.app.ui.easymode.EasyModeScreen
import com.fluxtv.app.ui.easymode.TouchEasyModeScreen
import com.fluxtv.app.ui.startup.StartingScreen
import com.fluxtv.app.ui.theme.FluxTheme
import com.fluxtv.app.ui.theme.LocalTouchUi
import com.fluxtv.app.ui.theme.ScaledText

/**
 * Routes between the three top-level modes (§25): STARTING, EASY (watching), ADMIN. What the Viewer
 * sees takes the text size of Preferences; Settings, which is for the administrator, keeps its own.
 *
 * [touchUi]: a phone or tablet, where Easy Mode is worked by touch ([TouchEasyModeScreen]) instead of
 * a remote; Settings adapts its layout to the narrower screen. [onFullscreen] turns a phone sideways
 * (true) or back upright (false).
 */
@Composable
fun FluxApp(vm: AppViewModel, touchUi: Boolean, onExit: () -> Unit, onFullscreen: (Boolean) -> Unit) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalTouchUi provides touchUi) {
        FluxTheme {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                val textScale = state.settings.textSizePercent / 100f
                when (state.mode) {
                    AppMode.STARTING -> ScaledText(textScale) {
                        StartingScreen(state.startupMessage, networkLost = state.playback.networkLost)
                    }
                    AppMode.EASY -> ScaledText(textScale) {
                        if (touchUi) TouchEasyModeScreen(state, vm, onFullscreen) else EasyModeScreen(state, vm, onExit)
                    }
                    AppMode.ADMIN -> AdminScreen(state, vm, onExit)
                }
            }
        }
    }
}

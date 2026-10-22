package com.fluxtv.app

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.fluxtv.app.playback.FrameRateController
import com.fluxtv.app.ui.AppMode
import com.fluxtv.app.ui.AppViewModel
import com.fluxtv.app.ui.EasyOverlay
import com.fluxtv.app.ui.FluxApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Single activity. It hosts Compose and owns the central remote-control handler so that each
 * physical button maps to one consistent action regardless of what is focused (§7, REQ-REM-001).
 * Volume and Home are never intercepted; a long-press of OK (or the MENU button) is the deliberate,
 * non-obvious gesture into the administrator area (REQ-CFG-001).
 */
class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels {
        AppViewModel.factory(application as FluxTvApplication)
    }

    private lateinit var frameRateController: FrameRateController
    private var okDownTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        frameRateController = FrameRateController(this)
        if (intent?.getBooleanExtra(BootTrace.EXTRA_FROM_BOOT, false) == true) BootTrace.markLaunchedByBoot()

        setContent { FluxApp(viewModel) }

        // Match the display mode to the source frame rate when enabled (§14).
        lifecycleScope.launch {
            viewModel.controller.videoFrameRate.collect { fps ->
                if (viewModel.uiState.value.settings.frameRateMatching) {
                    frameRateController.apply(fps)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(BootTrace.EXTRA_FROM_BOOT, false)) BootTrace.markLaunchedByBoot()
    }

    override fun onResume() {
        super.onResume()
        viewModel.onActivityResume()
        if (BootTrace.awaitingOnScreen) {
            BootTrace.awaitingOnScreen = false
            val store = (application as FluxTvApplication).container.diagnosticsStore
            val event = BootTrace.event("FluxTV on screen")
            lifecycleScope.launch(Dispatchers.IO) { store.append(event) }
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.onActivityPause()
    }

    override fun onDestroy() {
        frameRateController.reset()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return handleRemoteKey(event) || super.dispatchKeyEvent(event)
    }

    private fun handleRemoteKey(event: KeyEvent): Boolean {
        val state = viewModel.uiState.value
        // In STARTING / ADMIN, let Compose handle focus navigation and Back.
        if (state.mode != AppMode.EASY) return false

        val code = event.keyCode
        val firstDown = event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
        val up = event.action == KeyEvent.ACTION_UP

        // Never intercept volume or Home (REQ-REM-004, standard remote behaviour).
        when (code) {
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_HOME -> return false
        }

        when (code) {
            KeyEvent.KEYCODE_CHANNEL_UP -> {
                if (firstDown) viewModel.channelUp()
                return true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (firstDown) viewModel.channelDown()
                return true
            }

            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                if (firstDown) viewModel.onDigit(code - KeyEvent.KEYCODE_0)
                return true
            }

            KeyEvent.KEYCODE_DPAD_UP -> {
                if (state.overlay == EasyOverlay.NONE) {
                    if (firstDown) viewModel.channelUp()
                    return true
                }
                return false
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (state.overlay == EasyOverlay.NONE) {
                    if (firstDown) viewModel.channelDown()
                    return true
                }
                return false
            }

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A -> {
                when (state.overlay) {
                    EasyOverlay.NONE -> {
                        if (firstDown) okDownTime = SystemClock.elapsedRealtime()
                        if (up) {
                            val start = okDownTime
                            okDownTime = 0L
                            val held = if (start > 0L) SystemClock.elapsedRealtime() - start else 0L
                            when {
                                held >= 2000L -> viewModel.requestAdmin()
                                state.playback.showRecoveryPrompt -> viewModel.retryCurrent()
                                else -> viewModel.openChannelList()
                            }
                        }
                        return true
                    }
                    EasyOverlay.NUMBER_ENTRY -> {
                        if (firstDown) viewModel.confirmNumberEntry()
                        return true
                    }
                    EasyOverlay.CHANNEL_LIST -> return false // Compose list handles selection
                }
            }

            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_SETTINGS,
            KeyEvent.KEYCODE_TV_CONTENTS_MENU -> {
                if (firstDown) viewModel.requestAdmin()
                return true
            }
        }
        return false
    }
}

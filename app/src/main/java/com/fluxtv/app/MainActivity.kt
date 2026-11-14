package com.fluxtv.app

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.OrientationEventListener
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.fluxtv.app.data.model.DiagnosticsEvent
import com.fluxtv.app.playback.FrameRateController
import com.fluxtv.app.ui.AppMode
import com.fluxtv.app.ui.AppViewModel
import com.fluxtv.app.ui.EasyOverlay
import com.fluxtv.app.ui.FluxApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Single activity. It hosts Compose and owns the central remote-control handler so that each
 * physical button maps to one consistent action regardless of what is focused (§7, REQ-REM-001).
 * Volume and Home are never intercepted; a long-press of OK (or the MENU button) is the deliberate,
 * non-obvious gesture into the administrator area (REQ-CFG-001).
 *
 * On a phone or tablet, Easy Mode is worked by touch instead: the screen turns with the phone (and
 * goes sideways with the full-screen button), the status bar shows while it is upright, and the
 * remote's handler keeps only the keys that mean nothing else, so a keyboard types into the search.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels {
        AppViewModel.factory(application as FluxTvApplication)
    }

    private lateinit var frameRateController: FrameRateController
    private var okDownTime = 0L

    /** A phone or tablet, worked by touch, rather than a TV and its remote. */
    private val touchUi: Boolean by lazy { !(application as FluxTvApplication).container.isTelevision }

    /** After the full-screen button, gives the turning back to the phone once it is held that way. */
    private var orientationRelease: OrientationEventListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (touchUi) {
            // Upright or sideways, as the phone is held (and as its rotation lock allows).
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            // The theme's full-screen flag would keep the status bar away upright, and stops the
            // window from making room for the keyboard on older Android versions.
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            @Suppress("DEPRECATION")
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            @Suppress("DEPRECATION")
            window.statusBarColor = Color.TRANSPARENT
            @Suppress("DEPRECATION")
            window.navigationBarColor = Color.TRANSPARENT
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        applySystemBars()
        frameRateController = FrameRateController(this)
        if (intent?.getBooleanExtra(BootTrace.EXTRA_FROM_BOOT, false) == true) BootTrace.markLaunchedByBoot()
        if (savedInstanceState == null) noteStartedByWakeHelper(intent)

        setContent {
            FluxApp(viewModel, touchUi, onExit = ::finishAndRemoveTask, onFullscreen = ::setFullscreen)
        }

        // A phone's screen modes are its own business (and a faster one only costs battery), so the
        // frame rate is matched on a TV only.
        if (touchUi) return

        // Match the display mode to the source frame rate when enabled (§14), once the frame rate has
        // held for a moment: many TVs go black for a second or more on each mode switch, and zapping
        // between 25/50 and 30/60 fps channels used to switch the mode at every step. Switching the
        // option off puts the TV back in its own mode straight away (it used to stay in the last
        // matched one until FluxTV closed); switching it on matches the channel already playing.
        lifecycleScope.launch {
            combine(
                viewModel.controller.videoFrameRate,
                viewModel.uiState.map { it.settings.frameRateMatching }.distinctUntilChanged()
            ) { fps, matching -> fps to matching }
                .collectLatest { (fps, matching) ->
                    if (!matching) {
                        frameRateController.reset()
                        return@collectLatest
                    }
                    delay(FRAME_RATE_SETTLE_MS)
                    frameRateController.apply(fps)
                }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applySystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // A dialog or the keyboard can bring the bars back; they go again once FluxTV has the focus.
        if (hasFocus) applySystemBars()
    }

    /**
     * The system bars: hidden on a TV, and on a phone held sideways, where the picture takes the whole
     * screen; shown on a phone held upright, as in any phone app. A swipe from the edge shows them for
     * a moment while they are hidden.
     */
    private fun applySystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
            val upright = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
            if (touchUi && upright) show(WindowInsetsCompat.Type.systemBars())
            else hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    /**
     * The phone's full-screen button: sideways for the picture alone ([on]), or back upright for the
     * channel list. The screen turns at once, whichever way the phone is held. With auto-rotate on,
     * the phone takes over again once it is held the new way, so turning it keeps working as usual;
     * with the rotation locked, the screen stays as the button left it.
     */
    private fun setFullscreen(on: Boolean) {
        if (!touchUi) return
        orientationRelease?.disable()
        orientationRelease = null
        val autoRotate = Settings.System.getInt(contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
        if (!on && !autoRotate) {
            // Back to the way the rotation lock holds the screen: upright, for most.
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            return
        }
        requestedOrientation =
            if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
        if (!autoRotate) return
        val listener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees == ORIENTATION_UNKNOWN) return
                val heldSideways = degrees in 60..120 || degrees in 240..300
                val heldUpright = degrees <= 30 || degrees >= 330 || degrees in 150..210
                if (if (on) heldSideways else heldUpright) {
                    disable()
                    if (orientationRelease === this) orientationRelease = null
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
                }
            }
        }
        if (listener.canDetectOrientation()) {
            listener.enable()
            orientationRelease = listener
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(BootTrace.EXTRA_FROM_BOOT, false)) BootTrace.markLaunchedByBoot()
        noteStartedByWakeHelper(intent)
    }

    // The Shizuku helper starts FluxTV when the TV wakes and FluxTV has no process; the Diagnostics
    // history is where an administrator checks that it did.
    private fun noteStartedByWakeHelper(intent: Intent?) {
        if (intent?.getBooleanExtra(WakeDaemon.EXTRA_STARTED_BY_HELPER, false) != true) return
        val store = (application as FluxTvApplication).container.diagnosticsStore
        val event = DiagnosticsEvent(
            System.currentTimeMillis(), null, "wake",
            "The TV woke while FluxTV was not running: the Shizuku helper started it"
        )
        lifecycleScope.launch(Dispatchers.IO) { store.append(event) }
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

    // The TV has gone into standby, or another app is on screen: the video surface goes away with
    // the window, so playback stops here and the channel is re-tuned when FluxTV comes back.
    override fun onStop() {
        super.onStop()
        viewModel.onActivityStop()
        // The sensor is not kept running off screen; the screen stays as the button left it.
        orientationRelease?.disable()
        orientationRelease = null
    }

    override fun onDestroy() {
        orientationRelease?.disable()
        if (!touchUi) frameRateController.reset()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return handleRemoteKey(event) || super.dispatchKeyEvent(event)
    }

    private var holdStartedAt = 0L
    private var lastHoldStepAt = 0L

    // Tap = one channel. Holding past 2s keeps stepping one channel every 0.5s. Some remotes report
    // downTime == eventTime on every repeat, so the hold is timed here.
    private fun zap(event: KeyEvent, upward: Boolean) {
        if (event.action != KeyEvent.ACTION_DOWN) return
        val now = SystemClock.elapsedRealtime()
        if (event.repeatCount == 0) {
            holdStartedAt = now
            lastHoldStepAt = now
            if (upward) viewModel.channelUp() else viewModel.channelDown()
            return
        }
        if (now - holdStartedAt < 2000L || now - lastHoldStepAt < 500L) return
        lastHoldStepAt = now
        if (upward) viewModel.channelUp() else viewModel.channelDown()
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

        // On a phone, the screen is the remote. Digits, arrows and Enter from a keyboard (or one
        // that sends keys) belong to the channel search; only keys that mean nothing else are kept.
        if (touchUi && code !in TOUCH_KEYS) return false

        when (code) {
            KeyEvent.KEYCODE_CHANNEL_UP -> {
                zap(event, true)
                return true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                zap(event, false)
                return true
            }
            // Remotes that have it: back to the channel watched before.
            KeyEvent.KEYCODE_LAST_CHANNEL -> {
                if (firstDown) viewModel.previousChannel()
                return true
            }

            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                if (firstDown) viewModel.onDigit(code - KeyEvent.KEYCODE_0)
                return true
            }

            KeyEvent.KEYCODE_DPAD_UP -> {
                if (state.overlay == EasyOverlay.NONE) {
                    zap(event, true)
                    return true
                }
                return false
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (state.overlay == EasyOverlay.NONE) {
                    zap(event, false)
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

            // Remotes that have one: the channel banner, with what is on now and next.
            KeyEvent.KEYCODE_INFO,
            KeyEvent.KEYCODE_GUIDE -> {
                if (firstDown) viewModel.toggleChannelInfo()
                return true
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

    private companion object {
        /** How long a new frame rate has to hold before the display mode follows it. */
        const val FRAME_RATE_SETTLE_MS = 3_000L

        /** The keys still handled here on a phone, from a remote paired with it: none of them types text. */
        val TOUCH_KEYS = setOf(
            KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_LAST_CHANNEL,
            KeyEvent.KEYCODE_INFO,
            KeyEvent.KEYCODE_GUIDE
        )
    }
}

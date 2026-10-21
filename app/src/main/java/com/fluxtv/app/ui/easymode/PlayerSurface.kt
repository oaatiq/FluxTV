package com.fluxtv.app.ui.easymode

import android.graphics.Color as AndroidColor
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.fluxtv.app.playback.FluxPlayerController

/**
 * Full-screen video surface (REQ-SEN-001). Uses a bare Media3 [PlayerView] with its own controls
 * disabled — all control comes from the remote via the central key handler.
 *
 * [playerReady] is passed in so this recomposes (and re-binds the player) the moment the controller
 * has created its ExoPlayer.
 */
@Composable
fun PlayerSurface(
    controller: FluxPlayerController,
    playerReady: Boolean,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(AndroidColor.BLACK)
                setBackgroundColor(AndroidColor.BLACK)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
        },
        update = { view ->
            // Reading playerReady here ties this update to the controller's readiness signal.
            view.player = if (playerReady) controller.player else controller.player
        }
    )
}

package com.fluxtv.app.ui.easymode

import android.graphics.Color as AndroidColor
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
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
 *
 * A TV going into standby destroys the video surface and builds a new one on wake, and a decoder
 * that was playing cannot be relied on to move onto the replacement: many TV decoders keep the
 * sound and lose the picture. [onSurfaceReady] and [onSurfaceLost] report those two moments so the
 * channel can be re-tuned against the new surface.
 */
@Composable
fun PlayerSurface(
    controller: FluxPlayerController,
    playerReady: Boolean,
    onSurfaceReady: () -> Unit,
    onSurfaceLost: () -> Unit,
    modifier: Modifier = Modifier
) {
    // The surface callback lives as long as the view, so it reads the current lambdas through these.
    val ready by rememberUpdatedState(onSurfaceReady)
    val lost by rememberUpdatedState(onSurfaceLost)

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val view = PlayerView(ctx).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(AndroidColor.BLACK)
                setBackgroundColor(AndroidColor.BLACK)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            val videoHolder = (view.videoSurfaceView as? SurfaceView)?.holder
            // PlayerView draws into a SurfaceView unless told otherwise, so there is one to watch.
            // Should that ever change, report a surface that is ready and never lost, rather than
            // leaving the app waiting for a signal that cannot come.
            if (videoHolder == null) view.post { ready() }
            videoHolder?.addCallback(
                object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = reportReady(holder)

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
                        reportReady(holder)

                    override fun surfaceDestroyed(holder: SurfaceHolder) = lost()

                    /**
                     * Media3 listens on this same holder to pass the player its surface and
                     * registered after this callback, so the report is posted to run once every
                     * callback for the event has had its turn and the player holds the surface —
                     * and only if the surface is still there by then.
                     */
                    private fun reportReady(holder: SurfaceHolder) {
                        view.post { if (holder.surface?.isValid == true) ready() }
                    }
                }
            )
            view
        },
        // Settings replaces this view and Compose lets it go, but the PlayerView stayed registered
        // with the process-wide player and kept receiving its events until FluxTV was closed: one
        // more for every visit to Settings. Detaching it here lets it go for real.
        onRelease = { view -> view.player = null },
        update = { view ->
            // The controller has no player until it first tunes, hence the readiness signal.
            view.player = if (playerReady) controller.player else null
        }
    )
}

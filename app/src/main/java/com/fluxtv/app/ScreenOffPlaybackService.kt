package com.fluxtv.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.fluxtv.app.data.model.DiagnosticsEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps a phone playing the channel's sound while its screen is off ("Keep playing with the screen
 * off", Settings → Preferences, phones and tablets only).
 *
 * Android stops or freezes an app that has nothing on screen, so the listening runs as a media
 * foreground service, for exactly as long as [com.fluxtv.app.playback.FluxPlayerController.playingWithScreenOff]
 * is set: it stops itself when FluxTV is back on screen, when the sound is stopped, and when Android
 * pauses it for good. Its notification is a media one, with the channel's name and Stop, which
 * Android also shows on the lock screen; such notifications are shown without the notification
 * permission. The headset's pause button stops it too.
 *
 * Unlocking the phone normally brings FluxTV back on screen, which ends the listening with the
 * picture back. Unlocked into another app instead (from a notification, say), FluxTV stops the sound
 * as it always does when another app takes the screen.
 *
 * Never on a TV: a TV box "turns its screen off" while its picture may still be on the television,
 * and FluxTV stops there as it always has. The view model only starts this on a phone, and the
 * service refuses to run anywhere else.
 */
class ScreenOffPlaybackService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: MediaSession? = null
    private var unlockReceiver: BroadcastReceiver? = null
    private var unlockCheck: Job? = null
    private var channelName: String? = null
    private var foreground = false

    private val container get() = (application as FluxTvApplication).container

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (container.isTelevision) return
        session = MediaSession(this, "FluxTV").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPause() = stopListening("Stopped from the lock screen")
                override fun onStop() = stopListening("Stopped from the lock screen")
            })
            setSessionActivity(openFluxTv())
            isActive = true
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_USER_PRESENT) onUnlocked()
            }
        }
        // The system's unlock broadcast; exported, as StandbyWatcher registers it.
        ContextCompat.registerReceiver(
            this, receiver, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_EXPORTED
        )
        unlockReceiver = receiver

        val controller = container.playerController
        scope.launch {
            controller.playingWithScreenOff.collect { on -> if (!on) finish() }
        }
        scope.launch {
            controller.status.map { it.channel?.name }.distinctUntilChanged().collect { name ->
                channelName = name
                showChannel()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Into the foreground first, every time: Android requires it of a service it was asked to
        // start in the foreground, even one that is about to stop.
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), FOREGROUND_TYPE)
            foreground = true
        } catch (e: Exception) {
            record("Android did not let ${AppName} play on with the screen off: ${e.javaClass.simpleName}")
            container.playerController.endScreenOffPlayback("Could not play on with the screen off")
            finish()
            return START_NOT_STICKY
        }
        if (container.isTelevision) {
            finish()
        } else if (intent?.action == ACTION_STOP) {
            stopListening("Stopped from the notification")
        } else if (!container.playerController.playingWithScreenOff.value) {
            finish()
        }
        // Not restarted by Android: the listening lived in this process, and went with it.
        return START_NOT_STICKY
    }

    private fun stopListening(reason: String) {
        container.playerController.endScreenOffPlayback(reason)
        finish()
    }

    /**
     * The phone was unlocked. FluxTV is normally back on screen a moment later, which ends the
     * listening by itself; still not there after [UNLOCK_GRACE_MS], another app has the screen.
     */
    private fun onUnlocked() {
        unlockCheck?.cancel()
        unlockCheck = scope.launch {
            delay(UNLOCK_GRACE_MS)
            container.playerController.endScreenOffPlayback("The phone was unlocked into another app")
        }
    }

    /** Ends the service; once the listening is over, a waiting update may go in. */
    private fun finish() {
        if (foreground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foreground = false
        }
        stopSelf()
    }

    private fun showChannel() {
        val name = channelName ?: getString(R.string.app_name)
        session?.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, name)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, getString(R.string.app_name))
                .build()
        )
        session?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP)
                .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )
        if (foreground) getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification())
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager?.getNotificationChannel(CHANNEL_ID) == null) {
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Playing with the screen off", NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) }
            )
        }
        val stop = PendingIntent.getService(
            this, 0, Intent(this, ScreenOffPlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID)
            else @Suppress("DEPRECATION") Notification.Builder(this)
        val style = Notification.MediaStyle().setShowActionsInCompactView(0)
        session?.let { style.setMediaSession(it.sessionToken) }
        return builder
            .setSmallIcon(R.drawable.ic_stat_fluxtv)
            .setContentTitle(channelName ?: getString(R.string.app_name))
            .setContentText("Playing with the screen off")
            .setContentIntent(openFluxTv())
            .setDeleteIntent(stop)
            .addAction(
                Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stop), "Stop", stop).build()
            )
            .setStyle(style)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    private fun openFluxTv(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun record(message: String) {
        container.diagnosticsStore.append(DiagnosticsEvent(System.currentTimeMillis(), null, "standby", message))
    }

    override fun onDestroy() {
        unlockCheck?.cancel()
        scope.cancel()
        unlockReceiver?.let { runCatching { unregisterReceiver(it) } }
        unlockReceiver = null
        session?.run {
            isActive = false
            release()
        }
        session = null
        // The listening is over and FluxTV may be off screen: the moment a waiting update was held for.
        if (!container.isTelevision) container.updateManager.onLeftScreen()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "screen_off"
        private const val NOTIFICATION_ID = 2
        private const val ACTION_STOP = "com.fluxtv.app.action.STOP_SCREEN_OFF_PLAYBACK"

        /** How long FluxTV has to come back on screen after the phone is unlocked. */
        private const val UNLOCK_GRACE_MS = 3_000L

        private val FOREGROUND_TYPE =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            else 0

        /**
         * Starts the service as the screen goes off; false if Android refuses (a foreground service
         * started from the background, which the moment just after leaving the screen normally is not).
         */
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, ScreenOffPlaybackService::class.java))
            true
        } catch (e: Exception) {
            false
        }
    }
}

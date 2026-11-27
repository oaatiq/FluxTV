package com.fluxtv.app

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.fluxtv.app.data.model.DiagnosticsEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps FluxTV able to hear the TV wake up on Android 14 and newer, for as long as "Start FluxTV
 * when the TV turns on" is on.
 *
 * [StandbyWatcher] hears the wake through a receiver registered in code, the only kind Android sends
 * SCREEN_ON to. Since Android 14, Android freezes an app that is in the background with nothing to
 * do and holds such broadcasts back until the app is used again, which on a TV that went to sleep
 * with another app (or the Home screen) in front means never: the wake was announced, FluxTV heard
 * nothing, and the TV stayed on whatever it showed before (seen on a Raspberry Pi 5 on Android 16,
 * with FluxTV's SCREEN_OFF and SCREEN_ON both listed as deferred). A foreground service is what
 * keeps an app out of that state, so FluxTV runs this one, which does nothing but exist. Android TV
 * does not show the notification a foreground service has to carry.
 *
 * Android only lets an app start a foreground service while it is on screen (with a few exceptions,
 * a boot among them), so it is started each time FluxTV comes on screen and whenever the setting is
 * turned on, and stopped when the setting is turned off. Each start that Android refuses is noted in
 * the Diagnostics history; the next time FluxTV is opened it tries again.
 *
 * Older Android versions deliver the broadcast to a background app, and on a phone FluxTV never opens
 * itself, so there this does nothing.
 */
class WakeListenerService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), FOREGROUND_TYPE)
        } catch (e: Exception) {
            // Android refused it (a restart it did itself from the background, say): without the
            // foreground the service is no use, and the next time FluxTV is on screen it starts again.
            record(this, "Could not keep listening for the TV to wake: ${e.javaClass.simpleName}")
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager?.getNotificationChannel(CHANNEL_ID) == null) {
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Start when the TV turns on", NotificationManager.IMPORTANCE_MIN)
                    .apply { setShowBadge(false) }
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Ready to start when the TV turns on")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "wake"
        private const val NOTIFICATION_ID = 1

        private val FOREGROUND_TYPE =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            else 0

        /** Where Android holds back the wake broadcast from an app in the background. */
        private val needed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

        @Volatile
        private var wanted = false

        /** True while the service is running (started and not yet refused or stopped). */
        @Volatile
        private var running = false

        /**
         * Follows the "Start FluxTV when the TV turns on" setting from now on, for the life of the
         * process. Called once, on a TV, from [FluxTvApplication].
         */
        fun install(app: Application, container: AppContainer, scope: CoroutineScope) {
            if (!needed) return
            scope.launch {
                container.settingsRepository.settings.map { it.startOnBoot }.distinctUntilChanged().collect { on ->
                    wanted = on
                    if (on) start(app) else stop(app)
                }
            }
            // FluxTV on screen is the moment Android allows the start, so a start refused earlier
            // (the process began in the background) is made good the next time FluxTV is opened.
            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    if (wanted && !running) start(app)
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            })
        }

        private fun start(context: Context) {
            if (running) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, WakeListenerService::class.java))
                running = true
                record(context, "Listening for the TV to wake")
            } catch (e: Exception) {
                // ForegroundServiceStartNotAllowedException, from the background: tried again when
                // FluxTV is next on screen.
                record(context, "Android did not let ${AppName} listen for the TV to wake yet; it will when ${AppName} is opened")
            }
        }

        private fun stop(context: Context) {
            if (!running) return
            running = false
            context.stopService(Intent(context, WakeListenerService::class.java))
            record(context, "Stopped listening for the TV to wake (start when the TV turns on is off)")
        }

        private fun record(context: Context, message: String) {
            val app = context.applicationContext as? FluxTvApplication ?: return
            app.container.diagnosticsStore.append(DiagnosticsEvent(System.currentTimeMillis(), null, "wake", message))
        }
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }
}

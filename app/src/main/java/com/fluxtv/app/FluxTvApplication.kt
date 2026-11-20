package com.fluxtv.app

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.SystemClock
import com.fluxtv.app.update.UpdateWorker
import com.fluxtv.app.work.PlaylistRefreshWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class FluxTvApplication : Application() {

    lateinit var container: AppContainer
        private set

    private lateinit var standbyWatcher: StandbyWatcher

    lateinit var shizukuWake: ShizukuWake
        private set

    /** Start-up work that must not hold up the main thread, for as long as the process lives. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        BootTrace.applicationStartedMs = SystemClock.elapsedRealtime()
        // First, so that a crash anywhere after this leaves a note in the Diagnostics history.
        CrashNotes.install(this) {
            if (::container.isInitialized) container.playerController.status.value.channel?.name else null
        }
        container = AppContainer(this)
        appScope.launch { CrashNotes.collect(this@FluxTvApplication, container.diagnosticsStore) }
        // Starting by itself is for a TV, where turning it on should mean TV. On a phone, opening
        // FluxTV each time the screen is unlocked or the phone restarts would take the phone over.
        val television = container.isTelevision
        appScope.launch { setBootReceiverEnabled(television) }
        // Coming back from standby is announced to receivers registered in code only, so FluxTV
        // listens for it from here, for as long as the process lives.
        standbyWatcher = StandbyWatcher(this, container).also { if (television) it.register() }
        // Android 14+ holds that broadcast back from an app it has frozen in the background; a small
        // foreground service, while "Start FluxTV when the TV turns on" is on, keeps FluxTV hearing it.
        if (television) WakeListenerService.install(this, container, appScope)
        // With Shizuku on the TV, a helper that outlives a force-stop of FluxTV starts it on a wake.
        shizukuWake = ShizukuWake(this, container).also { if (television) it.register() }
        // The same helper installs FluxTV's updates without a question, on a TV that has it.
        if (television) container.updateManager.helper = shizukuWake
        // Unattended updates: a look every 6 hours, downloads and installations at quiet moments.
        appScope.launch { UpdateWorker.schedule(this@FluxTvApplication) }
        // Periodic, unattended playlist refresh (REQ-PL-004), at the interval chosen in Preferences.
        // Scheduling it with the 12 h default here, on every start, overwrote that choice.
        appScope.launch {
            val hours = runCatching { container.settingsRepository.settings.first().refreshIntervalHours }
                .getOrDefault(12)
            PlaylistRefreshWorker.schedule(this@FluxTvApplication, hours)
        }
        BootTrace.applicationReadyMs = SystemClock.elapsedRealtime()
    }

    /**
     * Off on a phone, so that a restart of the phone does not even start FluxTV's process; on (as the
     * manifest has it) on a TV. Only written when it differs, which is once.
     */
    private fun setBootReceiverEnabled(enabled: Boolean) {
        try {
            val component = ComponentName(this, BootReceiver::class.java)
            val wanted = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            if (packageManager.getComponentEnabledSetting(component) != wanted) {
                packageManager.setComponentEnabledSetting(component, wanted, PackageManager.DONT_KILL_APP)
            }
        } catch (_: Exception) {
            // BootReceiver checks for itself as well.
        }
    }
}

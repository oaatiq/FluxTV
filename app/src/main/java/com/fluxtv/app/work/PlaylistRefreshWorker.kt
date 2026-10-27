package com.fluxtv.app.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.fluxtv.app.FluxTvApplication
import com.fluxtv.app.data.repository.RefreshOutcome
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Refreshes the playlist in the background with no Viewer intervention (REQ-PL-004).
 * A failed refresh never discards the existing playlist (REQ-PL-005); the work simply retries.
 */
class PlaylistRefreshWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? FluxTvApplication ?: return Result.success()
        val settings = app.container.settingsRepository.settings.first()
        if (!settings.isConfigured) return Result.success()

        // The cache gives the refresh the channel numbers to keep. A process started for this job has
        // no playlist yet; one that is showing TV already holds it, and decoding it again there would
        // only add a second copy to memory while a channel plays.
        if (app.container.channelRepository.channels.value.isEmpty()) {
            app.container.channelRepository.loadFromCache()
        }
        val outcome = app.container.channelRepository.refresh(
            settings.playlistUrl!!,
            settings.playlistType,
            settings.playlistName
        )
        return when {
            outcome is RefreshOutcome.Success -> Result.success()
            // Too large for this TV: trying again within minutes would only run it out of memory
            // again, so it waits for the next scheduled refresh.
            outcome is RefreshOutcome.Failed && outcome.tooLarge -> Result.success()
            else -> Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "flux_playlist_refresh"

        fun schedule(context: Context, hours: Int = 12) {
            val request = PeriodicWorkRequestBuilder<PlaylistRefreshWorker>(
                hours.toLong().coerceAtLeast(1), TimeUnit.HOURS
            ).setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}

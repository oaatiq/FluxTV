package com.fluxtv.app.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.fluxtv.app.FluxTvApplication
import java.util.concurrent.TimeUnit

/**
 * Looks for a newer FluxTV every 6 hours, with nobody doing anything, and downloads and installs it
 * as [UpdateManager] sees fit. It runs whether FluxTV is on screen or not (Android starts FluxTV's
 * process for it), which is also what lets a TV in standby get its update. Failures are retried by
 * [UpdateManager]'s own schedule, so the job itself always succeeds.
 */
class UpdateWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? FluxTvApplication ?: return Result.success()
        app.container.updateManager.runAutomatic()
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "flux_update_check"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresStorageNotLow(true)
                        .build()
                )
                // The look after the start (UpdateManager.onAppStarted) covers the first minutes.
                .setInitialDelay(30, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}

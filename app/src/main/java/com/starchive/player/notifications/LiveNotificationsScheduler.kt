package com.starchive.player.notifications

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.starchive.player.data.SettingsDataStore
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Schedules and cancels the periodic background check for favourite DJs going live.
 *
 * WorkManager enforces a minimum periodic interval of 15 minutes (this is an OS-level
 * floor, not a choice), so a notification can be up to ~15 minutes behind the DJ.
 */
object LiveNotificationsScheduler {

    private const val WORK_NAME = "live_alert"

    /** Schedule a unique 15-minute periodic worker that only runs when connected. */
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<LiveNotificationsWorker>(
            15,
            TimeUnit.MINUTES,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** Cancel the periodic worker, if any. */
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /** Idempotently schedule on app launch when the toggle is enabled. */
    suspend fun scheduleIfEnabled(context: Context) {
        val enabled = SettingsDataStore(context).liveNotifications.first()
        if (enabled) schedule(context)
    }
}

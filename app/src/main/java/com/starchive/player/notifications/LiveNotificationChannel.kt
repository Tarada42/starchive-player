package com.starchive.player.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.content.ContextCompat
import com.starchive.player.R

/**
 * Creates the notification channel used by [LiveNotificationsWorker].
 * Safe to call repeatedly; channels with the same id are left unchanged.
 */
object LiveNotificationChannel {

    const val CHANNEL_ID = "live_alerts"

    fun create(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = ContextCompat.getSystemService(context, NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.live_notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.live_notification_channel_description)
        }
        manager.createNotificationChannel(channel)
    }
}

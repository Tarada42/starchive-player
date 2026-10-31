package com.starchive.player.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmapOrNull
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.starchive.player.MainActivity
import com.starchive.player.R
import com.starchive.player.StArchiveApp
import com.starchive.player.data.Artist
import com.starchive.player.data.SettingsDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Background poll that checks whether any favourited DJ has gone live and posts a
 * notification for each *newly* live favourite. The same DJ staying live does not
 * trigger repeat notifications thanks to a persisted last-seen ledger in
 * [SettingsDataStore].
 *
 * WorkManager enforces a minimum periodic interval of 15 minutes, so a notification
 * may be delayed by up to ~15 minutes after the DJ actually goes live.
 */
class LiveNotificationsWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val settings = SettingsDataStore(context)

    override suspend fun doWork(): Result {
        val context = applicationContext

        // Feature toggle guard.
        val enabled = runCatching { settings.liveNotifications.first() }.getOrDefault(true)
        if (!enabled) return Result.success()

        val app = context.applicationContext as? StArchiveApp ?: return Result.success()
        val repo = app.repository

        // Empty favourites = nothing to notify about.
        val favoriteIds = runCatching { repo.favoriteArtists.first() }.getOrDefault(emptySet())
        if (favoriteIds.isEmpty()) return Result.success()

        // Fetch the current live roster (force refresh). Never crash on network failure.
        runCatching { repo.loadLive(forceRefresh = true) }.onFailure {
            return Result.success()
        }

        val liveArtists = repo.liveArtists.value
        val favoriteSet = favoriteIds.map { it.lowercase() }.toSet()
        val liveFavorites = liveArtists.filter { artist ->
            artist.id.lowercase() in favoriteSet
        }

        val currentlyLiveIds = liveFavorites.map { it.id.lowercase() }.toSet()
        val lastNotified = runCatching {
            settings.lastNotifiedLiveArtists.first().map { it.lowercase() }.toSet()
        }.getOrDefault(emptySet())

        // Only notify for DJs that were not in the previous run's live set.
        // Compute this from the UNPRUNED ledger so we don't lose notifications.
        val newlyLive = liveFavorites.filter { it.id.lowercase() !in lastNotified }

        // Always prune the ledger to DJs that are still live, independent of
        // permission state. This clears DJs that stopped being live so they will
        // notify again the next time they go live, while keeping DJs that stayed
        // live in the ledger (anti-spam). Newly-live DJs are NOT added here.
        val pruned = lastNotified intersect currentlyLiveIds
        runCatching { settings.setLastNotifiedLiveArtists(pruned) }

        // On Android 13+ the user may have denied the permission. Keep the schedule
        // but don't post anything visible, and don't add newly-live DJs to the
        // ledger (they must notify after the user grants permission).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return Result.success()
        }

        val notificationManager = ContextCompat.getSystemService(
            context,
            NotificationManager::class.java,
        ) ?: return Result.success()

        newlyLive.forEach { artist ->
            postNotification(context, notificationManager, artist)
        }

        // Now that we've actually posted, persist the full current live set so
        // these DJs won't notify again while they stay live.
        runCatching { settings.setLastNotifiedLiveArtists(currentlyLiveIds) }

        return Result.success()
    }

    private suspend fun postNotification(
        context: Context,
        notificationManager: NotificationManager,
        artist: Artist,
    ) {
        val title = artist.displayName.ifBlank { prettify(artist.id) }
        val text = context.getString(R.string.live_notification_text)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_LIVE_ARTIST_ID, artist.id)
            putExtra(MainActivity.EXTRA_LIVE_ARTIST_NAME, artist.displayName)
            putExtra(MainActivity.EXTRA_LIVE_ARTIST_IMAGE, artist.bestImageUrl)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            artist.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        val largeIcon = loadLargeIcon(context, artist.bestImageUrl)
        if (largeIcon != null) {
            builder.setLargeIcon(largeIcon)
        }

        notificationManager.notify(artist.id.hashCode(), builder.build())
    }

    private suspend fun loadLargeIcon(context: Context, imageUrl: String?): Bitmap? {
        if (imageUrl.isNullOrBlank()) return null
        return runCatching {
            val request = ImageRequest.Builder(context)
                .data(imageUrl)
                .allowHardware(false)
                .build()
            val result = context.imageLoader.execute(request)
            (result as? SuccessResult)?.drawable?.toBitmapOrNull()
        }.getOrNull()
    }

    private fun prettify(raw: String): String =
        raw.replace('_', ' ').trim().replace(Regex("\\s+"), " ")

    companion object {
        const val CHANNEL_ID = "live_alerts"
    }
}

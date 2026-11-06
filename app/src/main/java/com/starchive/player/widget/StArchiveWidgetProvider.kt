package com.starchive.player.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.starchive.player.MainActivity
import com.starchive.player.R
import com.starchive.player.data.PlaybackStateDataStore
import com.starchive.player.playback.PlaybackService
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Home-screen mini-player widget. Renders the currently-playing (or last-played)
 * track from the playback DataStore, drives play/pause + next through the same
 * MediaSession as the in-app player (via a headless MediaController), and is asked
 * to re-render by [PlaybackManager.ACTION_WIDGET_UPDATE] broadcasts.
 *
 * Widgets cannot use Compose or Coil, so artwork is loaded with a tiny cached
 * HttpURLConnection download; remote view clicks go through PendingIntents.
 */
class StArchiveWidgetProvider : AppWidgetProvider() {

    private data class NowPlaying(
        val title: String?,
        val artist: String?,
        val artworkUrl: String?,
        val playing: Boolean,
    )

    private fun readNowPlaying(context: Context): NowPlaying {
        val store = PlaybackStateDataStore(context.applicationContext)
        return kotlinx.coroutines.runBlocking {
            combine(store.nowTitle, store.nowArtist, store.nowArtworkUrl, store.nowPlaying) { t, a, u, p ->
                NowPlaying(t, a, u, p)
            }.first()
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id -> render(context, id) }
        super.onUpdate(context, appWidgetManager, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent?) {
        super.onReceive(context, intent)
        when (intent?.action) {
            ACTION_TOGGLE -> runControllerAction(context) { c ->
                if (c.isPlaying) c.pause() else c.play()
            }
            ACTION_NEXT -> runControllerAction(context) { c -> c.seekToNextMediaItem() }
            ACTION_UPDATE -> reRenderAll(context)
        }
    }

    /** Issue a playback command through a short-lived MediaController to our service. */
    private fun runControllerAction(context: Context, action: (Player) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        val controller = MediaController.Builder(
            context,
            SessionToken(context, ComponentName(context, PlaybackService::class.java)),
        ).buildAsync()
        controller.addListener({
            val c = runCatching { controller.get() }.getOrNull()
            main.post {
                if (c != null) {
                    runCatching { action(c) }
                    runCatching { c.release() }
                }
                // Let the service settle, then refresh the widget from persisted state.
                main.postDelayed({ reRenderAll(context) }, 250L)
            }
        }, { runnable -> main.post(runnable) })
        // Safety net: if the controller never connects, still refresh after a delay.
        main.postDelayed({ reRenderAll(context) }, 1500L)
    }

    private fun reRenderAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, StArchiveWidgetProvider::class.java))
        if (ids.isNotEmpty()) {
            ids.forEach { id -> render(context, id) }
        }
    }

    /** Build + post a [RemoteViews] for one widget instance (off the main thread). */
    private fun render(context: Context, appWidgetId: Int) {
        CoroutineScope(Dispatchers.IO).launch {
            val np = readNowPlaying(context)
            val views = RemoteViews(context.packageName, R.layout.widget_mini_player)
            views.setTextViewText(R.id.widget_title, np.title?.takeIf { it.isNotBlank() } ?: "STArchive")
            views.setTextViewText(R.id.widget_artist, np.artist?.takeIf { it.isNotBlank() } ?: "Tap to open")
            views.setImageViewResource(
                R.id.btn_play,
                if (np.playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
            )
            views.setContentDescription(R.id.btn_play, if (np.playing) "Pause" else "Play")

            val bitmap = loadArtwork(np.artworkUrl)
            if (bitmap != null) {
                views.setImageViewBitmap(R.id.widget_artwork, bitmap)
            } else {
                views.setImageViewResource(R.id.widget_artwork, R.mipmap.ic_launcher)
            }

            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            // Tap the widget body -> open the app.
            val openApp = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            views.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(context, appWidgetId * 3 + 1, openApp, flags),
            )
            // Play/pause + next -> our own actions (forwarded to the MediaSession).
            views.setOnClickPendingIntent(
                R.id.btn_play,
                PendingIntent.getBroadcast(
                    context, appWidgetId * 3 + 2,
                    Intent(context, StArchiveWidgetProvider::class.java).setAction(ACTION_TOGGLE),
                    flags,
                ),
            )
            views.setOnClickPendingIntent(
                R.id.btn_next,
                PendingIntent.getBroadcast(
                    context, appWidgetId * 3 + 3,
                    Intent(context, StArchiveWidgetProvider::class.java).setAction(ACTION_NEXT),
                    flags,
                ),
            )

            try {
                AppWidgetManager.getInstance(context).updateAppWidget(appWidgetId, views)
            } catch (_: Exception) {
                // Failed to post the views (e.g. widget removed in the meantime) — ignore.
            }
        }
    }

    /** Single-slot artwork cache (the widget only ever shows one track at a time). */
    @Volatile private var cachedUrl: String? = null
    @Volatile private var cachedBitmap: Bitmap? = null

    private fun loadArtwork(url: String?): Bitmap? {
        if (url == null || url.isBlank()) return null
        if (url == cachedUrl) return cachedBitmap
        val bitmap = try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.instanceFollowRedirects = true
            val stream = conn.inputStream
            val decoded = BitmapFactory.decodeStream(stream)
            stream.close()
            conn.disconnect()
            decoded
        } catch (_: Exception) {
            null
        }
        cachedUrl = url
        cachedBitmap = bitmap
        return bitmap
    }

    companion object {
        const val ACTION_UPDATE = "com.starchive.player.widget.UPDATE"
        const val ACTION_TOGGLE = "com.starchive.player.widget.TOGGLE"
        const val ACTION_NEXT = "com.starchive.player.widget.NEXT"
    }
}
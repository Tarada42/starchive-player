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
import android.view.View
import android.widget.RemoteViews
import androidx.media3.common.MediaItem
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
import kotlinx.coroutines.runBlocking

/**
 * Home-screen mini-player widget for STArchive.
 *
 * It keeps ONE persistent [MediaController] connected to [PlaybackService] (the same
 * MediaSession the in-app player uses), registers a [Player.Listener] that re-renders
 * the widget whenever the track or play state changes, and drives Play/Pause + Next
 * through that controller.
 *
 * THREADING RULE (this caused a crash): `MediaController` getters (isPlaying,
 * currentMediaItem, mediaMetadata) may ONLY be called on the application (main) thread
 * — calling them on a background thread throws `IllegalStateException`. So the play
 * state is read synchronously on the caller's thread (onUpdate/onReceive/listeners all
 * run on main), and only artwork download + the final RemoteViews post run off the
 * main thread, wrapped so a widget error never crashes the app process.
 *
 * Artwork is loaded with a tiny cached HttpURLConnection download (widgets can't use
 * Coil); tapping the widget body opens the app. Remote view clicks go via PendingIntents.
 */
class StArchiveWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        ensureController(context)
        appWidgetIds.forEach { id -> render(context, id) }
        super.onUpdate(context, appWidgetManager, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent?) {
        super.onReceive(context, intent)
        ensureController(context)
        when (intent?.action) {
            ACTION_TOGGLE -> runOnController { c -> if (c.isPlaying) c.pause() else c.play() }
            // Skip from the widget must auto-play the next track.
            ACTION_NEXT -> runOnController { c ->
                c.seekToNextMediaItem()
                c.play()
            }
            ACTION_UPDATE -> reRenderAll(context)
        }
    }

    companion object {
        const val ACTION_UPDATE = "com.starchive.player.widget.UPDATE"
        const val ACTION_TOGGLE = "com.starchive.player.widget.TOGGLE"
        const val ACTION_NEXT = "com.starchive.player.widget.NEXT"
    }
}

// ---------------------------------------------------------------------------
// Persistent controller + helpers (top-level so the listener can reach them).
// ---------------------------------------------------------------------------

private data class NowState(
    val title: String?,
    val artist: String?,
    val artworkUrl: String?,
    val playing: Boolean,
    val isLive: Boolean = false,
)

private val EMPTY_STATE = NowState(null, null, null, false)

@Volatile private var appContextRef: Context? = null
private val controllerLock = Any()
private var controllerRef: MediaController? = null

/** Re-render the widget whenever the live playback state changes. */
private val widgetControllerListener = object : Player.Listener {
    override fun onIsPlayingChanged(isPlaying: Boolean) {
        appContextRef?.let(::reRenderAll)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        appContextRef?.let(::reRenderAll)
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        appContextRef?.let(::reRenderAll)
    }
}

/** Bind a single long-lived [MediaController] to our PlaybackService (once). */
private fun ensureController(context: Context) {
    synchronized(controllerLock) {
        if (controllerRef != null) return
        val appContext = context.applicationContext
        appContextRef = appContext
        val controller = MediaController.Builder(
            appContext,
            SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java)),
        ).buildAsync()
        controller.addListener({
            runCatching {
                val c = controller.get()
                synchronized(controllerLock) { controllerRef = c }
                c.addListener(widgetControllerListener)
            }
            reRenderAll(appContext)
        }, { r -> Handler(Looper.getMainLooper()).post(r) })
    }
}

/** Perform a transport action on the shared controller (main thread), if connected. */
private fun runOnController(action: (Player) -> Unit) {
    val c = synchronized(controllerLock) { controllerRef } ?: return
    Handler(Looper.getMainLooper()).post { runCatching { action(c) } }
}

/**
 * Read the play state for the widget. MUST run on the application (main) thread —
 * MediaController getters throw if called from another thread. Falls back to the
 * persisted "now playing" record only while the controller is still connecting.
 */
private fun currentState(): NowState {
    val cont = synchronized(controllerLock) { controllerRef }
    if (cont != null) {
        return runCatching {
            val item = cont.currentMediaItem
            val meta = item?.mediaMetadata
            NowState(
                title = meta?.title?.toString()?.takeIf { it.isNotBlank() },
                artist = meta?.artist?.toString()?.takeIf { it.isNotBlank() },
                artworkUrl = meta?.artworkUri?.toString()?.takeIf { it.isNotBlank() },
                playing = cont.isPlaying,
                isLive = item?.mediaId?.startsWith("live-") == true,
            )
        }.getOrNull() ?: EMPTY_STATE
    }
    // Controller not connected yet — brief, minimal persisted fallback.
    val ctx = appContextRef
    if (ctx == null) return EMPTY_STATE
    val store = PlaybackStateDataStore(ctx)
    return runCatching {
        runBlocking {
            combine(store.nowTitle, store.nowArtist, store.nowArtworkUrl, store.nowPlaying) { t, a, u, p ->
                NowState(t, a, u, p)
            }.first()
        }
    }.getOrNull() ?: EMPTY_STATE
}

private fun reRenderAll(context: Context) {
    val manager = AppWidgetManager.getInstance(context)
    val ids = manager.getAppWidgetIds(ComponentName(context, StArchiveWidgetProvider::class.java))
    if (ids.isNotEmpty()) ids.forEach { id -> render(context, id) }
}

/**
 * Build + post a [RemoteViews] for one widget instance.
 *
 * The play state is read HERE on the caller's (main) thread — required for
 * MediaController. Only artwork download and the final `updateAppWidget` post happen on
 * a background thread, and that whole block is guarded so a widget error never crashes
 * the app process.
 */
private fun render(context: Context, appWidgetId: Int) {
    val state = currentState()
    val views = RemoteViews(context.packageName, R.layout.widget_mini_player)
    views.setTextViewText(R.id.widget_title, state.title ?: "STArchive")
    views.setTextViewText(R.id.widget_artist, state.artist ?: "Tap to open")
    views.setImageViewResource(
        R.id.btn_play,
        if (state.playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
    )
    views.setContentDescription(R.id.btn_play, if (state.playing) "Pause" else "Play")

    val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    // Tap the widget body -> open the app.
    val openApp = Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    views.setOnClickPendingIntent(
        R.id.widget_root,
        PendingIntent.getActivity(context, appWidgetId * 3 + 1, openApp, flags),
    )
    views.setOnClickPendingIntent(
        R.id.btn_play,
        PendingIntent.getBroadcast(
            context, appWidgetId * 3 + 2,
            Intent(context, StArchiveWidgetProvider::class.java)
                .setAction(StArchiveWidgetProvider.ACTION_TOGGLE),
            flags,
        ),
    )
    // Skip/Next is only meaningful for archive playlists — a live stream has no next
    // track, so hide the button on live (mirrors the in-app player).
    if (state.isLive) {
        views.setViewVisibility(R.id.btn_next, View.GONE)
    } else {
        views.setViewVisibility(R.id.btn_next, View.VISIBLE)
        views.setOnClickPendingIntent(
            R.id.btn_next,
            PendingIntent.getBroadcast(
                context, appWidgetId * 3 + 3,
                Intent(context, StArchiveWidgetProvider::class.java)
                    .setAction(StArchiveWidgetProvider.ACTION_NEXT),
                flags,
            ),
        )
    }

    // Artwork download + post run off the main thread; guard everything so a failure
    // cannot crash the app.
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val bitmap = loadArtwork(state.artworkUrl)
            if (bitmap != null) {
                views.setImageViewBitmap(R.id.widget_artwork, bitmap)
            } else {
                views.setImageViewResource(R.id.widget_artwork, R.mipmap.ic_launcher)
            }
            AppWidgetManager.getInstance(context).updateAppWidget(appWidgetId, views)
        } catch (_: Exception) {
            // Widget render failed — never propagate.
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
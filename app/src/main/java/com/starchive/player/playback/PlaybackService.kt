package com.starchive.player.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.cast.CastPlayer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.starchive.player.MainActivity
import com.starchive.player.StArchiveApp
import com.starchive.player.data.Repository
import com.starchive.player.data.SettingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/**
 * Background playback service (Media3 MediaLibraryService).
 * Provides:
 *  - a MediaLibrarySession so playback continues with the screen off,
 *  - lock-screen / notification transport controls,
 *  - the shared session that Android Auto connects to and browses,
 *  - and phone-only Google Cast forwarding.
 *
 * The single ExoPlayer instance uses 30-second seek increments for archived sets.
 */
@UnstableApi
class PlaybackService : MediaLibraryService() {

    private var player: Player? = null
    /** The local (ExoPlayer) player; kept so we can swap back after casting. */
    private var localPlayer: Player? = null
    private var castPlayer: CastPlayer? = null
    private var castContext: CastContext? = null
    private var isCasting = false
    private var openAppIntent: PendingIntent? = null
    private var mediaLibrarySession: MediaLibrarySession? = null
    private var librarySessionCallback: MediaLibrarySessionCallback? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val repository: Repository by lazy { (application as StArchiveApp).repository }
    private val settings by lazy { SettingsDataStore(this) }
    private val sleepTimer by lazy { SleepTimer(serviceScope) { stopPlayback() } }

    override fun onCreate() {
        super.onCreate()

        // Buffer generously so big (150 MB) sets stream smoothly.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 60_000,
                /* bufferForPlaybackMs = */ 2_500,
                /* bufferForPlaybackAfterRebufferMs = */ 5_000,
            )
            .build()

        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this))
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(30_000)
            .setSeekForwardIncrementMs(30_000)
            .build()
        player = exoPlayer
        localPlayer = exoPlayer

        // Tapping the notification body (anywhere except the action buttons) opens and
        // focuses the app, directly on the Player screen for the now-playing track.
        // The transport buttons themselves stay handled by Media3.
        openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        librarySessionCallback =
            MediaLibrarySessionCallback(
                this,
                repository,
                serviceScope,
                sleepTimer,
                settings,
                isCastingProvider = { isCasting },
            )

        // Player listener: clears the resume point on stop, persists it on pause, and
        // logs seek capabilities (see the diagnostic below).
        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_IDLE && exoPlayer.mediaItemCount > 0) {
                    serviceScope.launch { runCatching { repository.clearLastPlayback() } }
                }
            }

            // Save the resume point when playback is paused (e.g. arriving at a
            // destination and turning the car off) rather than waiting for the ticker.
            // Archive and live items need different car buttons, so republish the layout
            // when the current item changes.
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                publishCarLayout()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    // Playback resumed: a later stop should be able to clear again.
                    librarySessionCallback?.clearStopInProgress()
                } else {
                    persistPlaybackPosition(force = true)
                }
            }

        })

        // Android Auto drives the session directly, so it never passes through
        // PlaybackManager (which is what saves the phone's resume point). Persist here
        // as well, otherwise a set started in the car is forgotten when the app closes.
        serviceScope.launch {
            while (isActive) {
                delay(POSITION_SAVE_INTERVAL_MS)
                persistPlaybackPosition()
            }
        }

        mediaLibrarySession = MediaLibrarySession.Builder(this, exoPlayer, librarySessionCallback!!)
            .setSessionActivity(openAppIntent!!)
            .setMediaButtonPreferences(librarySessionCallback!!.mediaButtonPreferences(live = false))
            .build()


        // Google Cast (PHONE ONLY). CastContext init can fail on devices without Play
        // Services — fail soft and keep local playback fully working.
        castContext = runCatching { CastContext.getSharedInstance(this) }.getOrNull()
        castContext?.sessionManager?.addSessionManagerListener(typedCastSessionListener, CastSession::class.java)
    }

    // --- Cast session handover (phone only) ---

    private val castSessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) = switchToCast()
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = switchToCast()
        override fun onSessionEnded(session: CastSession, error: Int) = switchToLocal()
        override fun onSessionSuspended(session: CastSession, reason: Int) = switchToLocal()
        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionStartFailed(session: CastSession, error: Int) {}
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionResumeFailed(session: CastSession, error: Int) {}
    }

    // Explicitly typed listener reference so Kotlin can resolve the generic class
    // parameter for the add/remove listener calls below.
    private val typedCastSessionListener: SessionManagerListener<CastSession> = castSessionListener

    /** Move the current queue/position from the local player onto a new CastPlayer. */
    private fun switchToCast() {
        val ctx = castContext ?: return
        val old = player ?: return
        if (isCasting) return
        val cp = CastPlayer(ctx)
        castPlayer = cp
        transferPlaybackState(from = old, to = cp)
        old.pause()
        mediaLibrarySession?.setPlayer(cp)
        player = cp
        isCasting = true
    }

    /** Move playback back to the local player when the cast session ends. */
    private fun switchToLocal() {
        val lp = localPlayer ?: return
        val old = player ?: return
        if (!isCasting) return
        old.pause()
        castPlayer?.release()
        castPlayer = null
        // Keep the single persistent MediaLibrarySession alive and just point it back at the
        // local player. PlaybackManager observes the resulting LOCAL device-info change
        // and re-issues the authoritative queue/URI; the service no longer tries to
        // rebuild the local player's items from the CastPlayer snapshot.
        mediaLibrarySession?.setPlayer(lp)
        player = lp
        isCasting = false
    }

    /** Copy media items + current index/position/play state between players.
     *  Live streams have no meaningful fixed start position, so we resume them at 0. */
    private fun transferPlaybackState(from: Player, to: Player) {
        val items = (0 until from.mediaItemCount).map { from.getMediaItemAt(it) }
        if (items.isNotEmpty()) {
            val isLive = from.currentMediaItem?.mediaId?.startsWith("live-") == true
            val startPositionMs = if (isLive) 0L else from.currentPosition.coerceAtLeast(0L)
            to.setMediaItems(
                items,
                from.currentMediaItemIndex.coerceAtLeast(0),
                startPositionMs,
            )
        }
        to.playWhenReady = from.playWhenReady
        to.prepare()
    }

    /**
     * Service-side stop: pause, reset to the beginning, and clear the persisted resume
     * state. This mirrors [PlaybackManager.stop] and is invoked by the sleep timer on
     * expiry (and only there). It works for the local player and for CastPlayer because
     * both support pause/seekTo.
     */
    private fun stopPlayback() {
        val p = player ?: return
        p.pause()
        p.seekTo(0L)
        serviceScope.launch { repository.clearLastPlayback() }
    }


    /** Keep the car's media button preferences in step with what is actually playing. */
    private fun publishCarLayout() {
        val session = mediaLibrarySession ?: return
        val callback = librarySessionCallback ?: return
        val live = player?.currentMediaItem?.mediaId?.startsWith(LIVE_ID_PREFIX) == true
        val buttons = callback.mediaButtonPreferences(live)
        runCatching {
            // Update the session's preferences for future controllers.
            session.setMediaButtonPreferences(buttons)
            // Force an immediate rebuild of the legacy PlaybackStateCompat for the
            // currently-connected media-notification controller (Android Auto reads this).
            // The global overload alone only stores the preferences and does not rebuild
            // PlaybackStateCompat (MediaSessionImpl.setMediaButtonPreferences:596-605).
            session.getMediaNotificationControllerInfo()?.let { notificationController ->
                session.setMediaButtonPreferences(notificationController, buttons)
            }
        }
    }

    /**
     * Persist the current archive track + position. Live streams are skipped: their
     * position is meaningless and resuming a stream hours later would play nothing.
     */
    private fun persistPlaybackPosition(force: Boolean = false) {
        val p = player ?: return
        if (!force && !p.isPlaying) return
        // A stop clears the resume point (see the STATE_IDLE listener); never write one
        // back on the way out, or Stop would silently leave something to resume.
        if (librarySessionCallback?.stopInProgress == true) return
        if (p.playbackState == Player.STATE_IDLE) return
        val mediaId = p.currentMediaItem?.mediaId ?: return
        if (mediaId.startsWith(LIVE_ID_PREFIX)) return
        val position = p.currentPosition
        if (position <= 0L) return
        serviceScope.launch {
            runCatching {
                val track = withContext(Dispatchers.IO) { repository.trackById(mediaId) }
                    ?: return@runCatching
                repository.saveLastPlayback(track, position)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        mediaLibrarySession

    /**
     * Swiping the app out of Recents is an explicit "close it" gesture, but a foreground
     * media session outlives the task by design: playback and the ongoing notification
     * kept running, so the card felt impossible to dismiss. Stop playback and the service.
     *
     * pauseAllPlayersAndStopSelf() is the documented way to end the foreground state; a
     * bare player.pause() + stopSelf() no longer works in media3 1.4.x. super is not
     * called on purpose — it is documented as safe to skip when overriding this behaviour.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        sleepTimer.cancel()
        // Never leave audio playing on a cast device after the app is closed.
        runCatching { castContext?.sessionManager?.endCurrentSession(true) }
        pauseAllPlayersAndStopSelf()
    }

    override fun onDestroy() {
        // The service is being stopped (e.g. user dismissed the notification's close
        // action): pause and clear the persisted resume state so the app closes out
        // to the home screen cleanly. Uses its own scope since serviceScope is about
        // to be cancelled.
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            runCatching { repository.clearLastPlayback() }
        }
        sleepTimer.cancel()
        serviceScope.cancel()
        castContext?.sessionManager?.removeSessionManagerListener(typedCastSessionListener, CastSession::class.java)
        librarySessionCallback?.release()
        librarySessionCallback = null
        mediaLibrarySession?.run {
            player.release()
            release()
        }
        mediaLibrarySession = null
        player = null
        castPlayer?.release()
        castPlayer = null
        super.onDestroy()
    }

    companion object {
        const val COMMAND_SET_SLEEP_TIMER = "com.starchive.player.playback.SET_SLEEP_TIMER"
        const val COMMAND_CANCEL_SLEEP_TIMER = "com.starchive.player.playback.CANCEL_SLEEP_TIMER"
        const val COMMAND_GET_SLEEP_TIMER_REMAINING = "com.starchive.player.playback.GET_SLEEP_TIMER_REMAINING"
        const val COMMAND_AA_STOP = "com.starchive.player.playback.AA_STOP"

        /** Car -/+30 s buttons. Custom commands, so they are never drawn on live streams. */
        const val COMMAND_AA_SEEK_BACK = "com.starchive.player.playback.AA_SEEK_BACK"
        const val COMMAND_AA_SEEK_FORWARD = "com.starchive.player.playback.AA_SEEK_FORWARD"

        const val EXTRA_MINUTES = "com.starchive.player.playback.EXTRA_MINUTES"
        const val EXTRA_REMAINING_MS = "com.starchive.player.playback.EXTRA_REMAINING_MS"

        /** How often playback position is persisted for the resume point. */
        private const val POSITION_SAVE_INTERVAL_MS = 5_000L

        /** Must match the prefix the browse tree uses for live items. */
        private const val LIVE_ID_PREFIX = "live-"
    }
}

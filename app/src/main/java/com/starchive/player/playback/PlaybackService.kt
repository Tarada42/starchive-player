package com.starchive.player.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.cast.CastPlayer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.MediaSessionService
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.starchive.player.MainActivity
import com.starchive.player.StArchiveApp
import com.starchive.player.aa.AaPlaybackClient
import com.starchive.player.aa.AaPlayerWrapper
import com.starchive.player.data.Repository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Background playback service (Media3 MediaSessionService).
 * Provides:
 *  - a MediaSession so playback continues with the screen off,
 *  - lock-screen / notification transport controls,
 *  - the shared session that Android Auto connects to,
 *  - and phone-only Google Cast forwarding.
 *
 * The single ExoPlayer instance is optionally wrapped by [AaPlayerWrapper] to provide
 * the 10-second seek increments and Stop behavior required by the Android Auto module.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var player: Player? = null
    /** The local (ExoPlayer) player; kept so we can swap back after casting. */
    private var localPlayer: Player? = null
    private var castPlayer: CastPlayer? = null
    private var castContext: CastContext? = null
    private var isCasting = false
    private var openAppIntent: PendingIntent? = null
    private var mediaSession: MediaSession? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val repository: Repository by lazy { (application as StArchiveApp).repository }

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
            .build()
        val wrappedPlayer = AaPlayerWrapper(exoPlayer) {
            serviceScope.launch { repository.clearLastPlayback() }
        }
        player = wrappedPlayer
        localPlayer = wrappedPlayer

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

        mediaSession = MediaSession.Builder(this, wrappedPlayer)
            .setSessionActivity(openAppIntent!!)
            .setCallback(mediaSessionCallback)
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

    // --- Android Auto session callback ---

    /**
     * Exposes the platform MediaSession token to the Android Auto module so it can register
     * the token with CarAppLibrary's MediaPlaybackManager. This is the standard CAL 1.8+
     * integration path for Media3-based sessions.
     */
    private val mediaSessionCallback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(AaPlaybackClient.COMMAND_GET_PLATFORM_TOKEN, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == AaPlaybackClient.COMMAND_GET_PLATFORM_TOKEN) {
                val extras = Bundle().apply {
                    putParcelable(AaPlaybackClient.EXTRA_PLATFORM_TOKEN, session.platformToken)
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, extras))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
        }
    }

    /** Move the current queue/position from the local player onto a new CastPlayer. */
    private fun switchToCast() {
        val ctx = castContext ?: return
        val old = player ?: return
        if (isCasting) return
        val cp = CastPlayer(ctx)
        castPlayer = cp
        transferPlaybackState(from = old, to = cp)
        old.pause()
        rebuildSession(cp)
        player = cp
        isCasting = true
    }

    /** Move playback back to the local player when the cast session ends. */
    private fun switchToLocal() {
        val lp = localPlayer ?: return
        val old = player ?: return
        if (!isCasting) return
        transferPlaybackState(from = old, to = lp)
        old.pause()
        castPlayer?.release()
        castPlayer = null
        rebuildSession(lp)
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

    /** MediaSession can't swap its player in place — release and rebuild around [p]. */
    private fun rebuildSession(p: Player) {
        mediaSession?.release()
        mediaSession = MediaSession.Builder(this, p)
            .setSessionActivity(openAppIntent!!)
            .setCallback(mediaSessionCallback)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        // The service is being stopped (e.g. user dismissed the notification's close
        // action): pause and clear the persisted resume state so the app closes out
        // to the home screen cleanly. Uses its own scope since serviceScope is about
        // to be cancelled.
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            runCatching { repository.clearLastPlayback() }
        }
        serviceScope.cancel()
        castContext?.sessionManager?.removeSessionManagerListener(typedCastSessionListener, CastSession::class.java)
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        player = null
        castPlayer?.release()
        castPlayer = null
        super.onDestroy()
    }
}
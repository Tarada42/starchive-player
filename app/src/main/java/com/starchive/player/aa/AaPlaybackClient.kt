package com.starchive.player.aa

import android.content.ComponentName
import androidx.car.app.CarContext
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.starchive.player.data.Artist
import com.starchive.player.data.Track
import com.starchive.player.playback.PlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Connects the Android Auto module to the single phone [PlaybackService] MediaSession.
 *
 * This deliberately does NOT register a media-playback token with the host. The previous
 * token/custom-command path repeatedly produced "Could not load your selection" on this
 * app/host; the proven player surface is the plain [ListTemplate] in [PlayerScreen].
 */
class AaPlaybackClient(
    private val carContext: CarContext,
    private val scope: CoroutineScope,
) {
    private var controller: MediaController? = null
    private var pending: PendingPlayback? = null
    private var connectionFuture: ListenableFuture<MediaController>? = null

    /** Connects asynchronously; any play call before connection is queued and played once ready. */
    fun connect() {
        if (connectionFuture != null) return
        val token = SessionToken(carContext, ComponentName(carContext, PlaybackService::class.java))
        val future = MediaController.Builder(carContext, token).buildAsync()
        connectionFuture = future
        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            when (val p = pending) {
                is PendingPlayback.Track -> play(p.track)
                is PendingPlayback.Live -> playLive(p.artist, p.url, p.mimeType, p.qualityLabel)
                null -> { /* nothing queued */ }
            }
            pending = null
        }, { runnable -> scope.launch { runnable.run() } })
    }

    /** Play [track] through the shared MediaSession. */
    fun play(track: Track) {
        val c = controller
        if (c == null) {
            pending = PendingPlayback.Track(track)
            return
        }
        c.setMediaItems(listOf(track.toAaMediaItem()))
        c.prepare()
        c.play()
    }

    /**
     * Play a live restream for [artist] through the shared MediaSession.
     * [url] and [mimeType] select the High (MP3) or Low (Ogg) mount.
     */
    fun playLive(
        artist: Artist,
        url: String,
        mimeType: String,
        qualityLabel: String,
    ) {
        val c = controller
        if (c == null) {
            pending = PendingPlayback.Live(artist, url, mimeType, qualityLabel)
            return
        }
        c.setMediaItems(listOf(buildLiveMediaItem(artist, url, mimeType, qualityLabel)))
        c.prepare()
        c.play()
    }

    /** Toggle play/pause on the shared session. */
    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    /** Seek exactly [millis] from the current position (10_000 = 10 s). Clamped to the media. */
    fun seekBy(millis: Long) {
        val c = controller ?: return
        val target = (c.currentPosition + millis).coerceAtLeast(0L)
        val bounded = c.duration.takeIf { it > 0 }?.let { target.coerceAtMost(it) } ?: target
        c.seekTo(bounded)
    }

    /** Stop playback. The shared player wrapper implements the live-vs-normal distinction. */
    fun stop() {
        controller?.stop()
    }

    /**
     * Command/action names retained for backward compatibility with [PlaybackService], which
     * still handles the token request if a host ever sends it. The AA module no longer initiates
     * that exchange.
     */
    companion object {
        const val COMMAND_GET_PLATFORM_TOKEN = "com.starchive.player.aa.GET_PLATFORM_TOKEN"
        const val EXTRA_PLATFORM_TOKEN = "com.starchive.player.aa.EXTRA_PLATFORM_TOKEN"
    }
}

/** Queued playback request used before the MediaController has finished connecting. */
private sealed class PendingPlayback {
    data class Track(val track: com.starchive.player.data.Track) : PendingPlayback()
    data class Live(
        val artist: Artist,
        val url: String,
        val mimeType: String,
        val qualityLabel: String,
    ) : PendingPlayback()
}

private fun Track.toAaMediaItem(): MediaItem {
    val metadata = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artistName)
        .setArtworkUri(thumbnailUrl?.let { android.net.Uri.parse(it) })
        .setIsBrowsable(false)
        .setIsPlayable(true)
        .build()
    return MediaItem.Builder()
        .setUri(url)
        .setMediaId(id)
        .setMimeType(MimeTypes.AUDIO_MPEG)
        .setMediaMetadata(metadata)
        .build()
}

private fun buildLiveMediaItem(
    artist: Artist,
    url: String,
    mimeType: String,
    qualityLabel: String,
): MediaItem {
    val metadata = MediaMetadata.Builder()
        .setTitle("${artist.displayName} — LIVE ($qualityLabel)")
        .setArtist(artist.displayName)
        .setArtworkUri(artist.bestImageUrl?.let { android.net.Uri.parse(it) })
        .setIsBrowsable(false)
        .setIsPlayable(true)
        .build()
    return MediaItem.Builder()
        .setUri(url)
        .setMediaId("live-${artist.id}")
        .setMimeType(mimeType)
        .setMediaMetadata(metadata)
        .build()
}

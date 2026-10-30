package com.starchive.player.aa

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.starchive.player.data.Track
import com.starchive.player.playback.PlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Connects the Android Auto module to the single phone [PlaybackService] MediaSession. */
class AaPlaybackClient(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private var controller: MediaController? = null
    private var pending: Track? = null
    private var connectionFuture: ListenableFuture<MediaController>? = null

    /** Connects asynchronously; any [play] call before connection is queued and played once ready. */
    fun connect() {
        if (connectionFuture != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        connectionFuture = future
        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            pending?.let { track ->
                pending = null
                play(track)
            }
        }, { runnable -> scope.launch { runnable.run() } })
    }

    /** Play [track] through the shared MediaSession. */
    fun play(track: Track) {
        val c = controller
        if (c == null) {
            pending = track
            return
        }
        c.setMediaItems(listOf(track.toAaMediaItem()))
        c.prepare()
        c.play()
    }
}

private fun Track.toAaMediaItem(): MediaItem {
    val metadata = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artistName)
        .setArtworkUri(thumbnailUrl?.let { Uri.parse(it) })
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

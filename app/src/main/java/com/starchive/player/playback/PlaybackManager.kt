package com.starchive.player.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.starchive.player.data.Track
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Thin wrapper around a MediaController connected to [PlaybackService].
 * Holds a queue of [Track]s so the UI and (later) Android Auto refer to the same items.
 */
class PlaybackManager(private val context: Context, private val scope: CoroutineScope) {

    private var controller: MediaController? = null
    var controllerFuture: ListenableFuture<MediaController>? = null

    private val _current = MutableStateFlow<Track?>(null)
    val current: StateFlow<Track?> = _current

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs

    private var queue: List<Track> = emptyList()
    private var pendingPlay: Pair<List<Track>, Int>? = null

    fun connect() {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        controllerFuture = future
        future.addListener({
            controller = future.get()
            startPositionTicker()
            pendingPlay?.let { (tracks, index) ->
                pendingPlay = null
                play(tracks, index)
            }
        }, { runnable -> scope.launch { withContext(Dispatchers.Main) { runnable.run() } } })
    }

    fun play(tracks: List<Track>, startIndex: Int) {
        val c = controller
        if (c == null) {
            pendingPlay = tracks to startIndex
            return
        }
        queue = tracks
        val items = tracks.map { it.toMediaItem() }
        c.setMediaItems(items, startIndex, 0L)
        c.prepare()
        c.play()
        _current.value = tracks.getOrNull(startIndex)
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun next() = controller?.seekToNextMediaItem()
    fun previous() = controller?.seekToPreviousMediaItem()

    fun skipForward(millis: Long = 10_000L) = controller?.seekTo(controller?.currentPosition?.plus(millis) ?: 0L)
    fun skipBackward(millis: Long = 10_000L) = controller?.seekTo((controller?.currentPosition?.minus(millis) ?: 0L).coerceAtLeast(0L))

    fun seekTo(ms: Long) = controller?.seekTo(ms)

    fun stop() {
        val c = controller ?: return
        c.pause()
        c.seekTo(0L)
        _isPlaying.value = false
        _positionMs.value = 0L
    }

    fun release() {
        controller?.release()
        controller = null
    }

    private fun startPositionTicker() {
        scope.launch {
            while (true) {
                val c = controller
                if (c != null) {
                    _isPlaying.value = c.isPlaying
                    _positionMs.value = c.currentPosition.coerceAtLeast(0L)
                    _durationMs.value = c.duration.coerceAtLeast(0L)
                    // Which queue item is active?
                    val idx = c.currentMediaItemIndex
                    if (idx in queue.indices) _current.value = queue[idx]
                }
                delay(500)
            }
        }
    }
}

/** Map a scraped [Track] to a Media3 item, carrying artwork + metadata for the notification. */
fun Track.toMediaItem(): MediaItem {
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
        .setMediaMetadata(metadata)
        .build()
}

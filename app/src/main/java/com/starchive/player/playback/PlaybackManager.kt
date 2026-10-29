package com.starchive.player.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.starchive.player.StArchiveApp
import com.starchive.player.data.DownloadManager
import com.starchive.player.data.Repository
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
 * Holds a queue of [Track]s so the UI and Android Auto refer to the same items.
 */
class PlaybackManager(private val context: Context, private val scope: CoroutineScope) {

    private val repository: Repository by lazy { (context.applicationContext as StArchiveApp).repository }
    private val downloadManager: DownloadManager by lazy { (context.applicationContext as StArchiveApp).downloadManager }

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
    private var lastPersistedTrackId: String? = null

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

    /** Play [tracks] starting at [startIndex]. When [startPositionMs] > 0, resume at
     *  that position within the starting track (for last-played / favourite-DJ resume). */
    fun play(tracks: List<Track>, startIndex: Int, startPositionMs: Long = 0L) {
        val c = controller
        if (c == null) {
            pendingPlay = tracks to startIndex
            return
        }
        queue = tracks
        val items = tracks.map { it.toMediaItemWithLocal(downloadManager) }
        c.setMediaItems(items, startIndex, startPositionMs.coerceAtLeast(0L))
        c.prepare()
        c.play()
        _current.value = tracks.getOrNull(startIndex)
    }

    /** Play just one track (e.g. a favourite or a search hit). */
    fun playTrack(track: Track, startPositionMs: Long = 0L) = play(listOf(track), 0, startPositionMs)

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun next() = controller?.seekToNextMediaItem()
    fun previous() = controller?.seekToPreviousMediaItem()

    fun skipForward(millis: Long = 30_000L) = controller?.seekTo(controller?.currentPosition?.plus(millis) ?: 0L)
    fun skipBackward(millis: Long = 30_000L) = controller?.seekTo((controller?.currentPosition?.minus(millis) ?: 0L).coerceAtLeast(0L))

    fun seekTo(ms: Long) = controller?.seekTo(ms)

    fun stop() {
        val c = controller ?: return
        c.pause()
        c.seekTo(0L)
        _isPlaying.value = false
        _positionMs.value = 0L
        scope.launch { repository.clearLastPlayback() }
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
                    persistIfChanged()
                }
                delay(500)
            }
        }
    }

    /** Persist the current track + position (throttled to once per ~2s per track) so a
     *  fresh app launch can resume exactly where the user left off. Live restreams are
     *  skipped — they're indefinite streams with no meaningful resume position. */
    private fun persistIfChanged() {
        val track = _current.value ?: return
        if (track.id.startsWith("live-")) return
        if (_positionMs.value <= 0L) return
        if (track.id == lastPersistedTrackId && _positionMs.value % 2000L > 500L) return
        lastPersistedTrackId = track.id
        scope.launch { repository.saveLastPlayback(track, _positionMs.value) }
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
    val builder = MediaItem.Builder()
        .setUri(url)
        .setMediaId(id)
        .setMimeType(MimeTypes.AUDIO_MPEG)
        .setMediaMetadata(metadata)
    // Live restreams are Icecast Ogg audio, not MP3. The URL has no file extension,
    // so we must declare the MIME explicitly or the player tries to decode it as MP3
    // (and the Chromecast receiver refuses the stream).
    if (id.startsWith("live-")) {
        builder.setMimeType(MimeTypes.AUDIO_OGG)
        builder.setLiveConfiguration(MediaItem.LiveConfiguration.Builder().build())
    }
    return builder.build()
}

/**
 * Same as [toMediaItem], but plays the local downloaded file instead of streaming
 * when a copy exists (offline playback, saves mobile data).
 */
fun Track.toMediaItemWithLocal(downloadManager: DownloadManager): MediaItem {
    val local = downloadManager.localUri(id)
    if (local != null) {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artistName)
            .setArtworkUri(thumbnailUrl?.let { android.net.Uri.parse(it) })
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .build()
        return MediaItem.Builder()
            .setUri(local)
            .setMediaId(id)
            .setMimeType(MimeTypes.AUDIO_MPEG)
            .setMediaMetadata(metadata)
            .build()
    }
    return toMediaItem()
}

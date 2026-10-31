package com.starchive.player.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.starchive.player.StArchiveApp
import com.google.common.util.concurrent.ListenableFuture
import com.starchive.player.data.DownloadManager
import com.starchive.player.data.Repository
import com.starchive.player.data.Track
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

    private val _sleepTimerMinutes = MutableStateFlow<Int?>(null)
    val sleepTimerMinutes: StateFlow<Int?> = _sleepTimerMinutes

    private val _sleepTimerRemainingMs = MutableStateFlow<Long?>(null)
    val sleepTimerRemainingMs: StateFlow<Long?> = _sleepTimerRemainingMs

    private var queue: List<Track> = emptyList()
    private var pendingPlay: Pair<List<Track>, Int>? = null
    private var lastPersistedTrackId: String? = null
    private var wasRemotePlayback = false

    /**
     * Reacts to the MediaSession swapping the underlying player. When the service moves
     * playback back to the local ExoPlayer after a cast session ends, the controller's
     * device info flips from remote to local. That is the signal to re-issue the last
     * user-selected track from the authoritative queue, using its real local/stream URI.
     */
    private val deviceInfoListener = object : Player.Listener {
        override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) {
            val isRemote = deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE
            val isLocal = deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_LOCAL
            if (isLocal && wasRemotePlayback) {
                restoreLocalPlayback()
            }
            wasRemotePlayback = isRemote
        }
    }

    private fun restoreLocalPlayback() {
        val tracks = queue
        val currentTrack = _current.value ?: return
        val index = tracks.indexOfFirst { it.id == currentTrack.id }.coerceAtLeast(0)
        val positionMs = if (currentTrack.id.startsWith("live-")) {
            0L
        } else {
            _positionMs.value.coerceAtLeast(0L)
        }
        play(tracks, index, positionMs, cancelSleepTimer = false)
    }

    fun connect() {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        controllerFuture = future
        future.addListener({
            controller = future.get()
            val c = controller ?: return@addListener
            c.addListener(deviceInfoListener)
            wasRemotePlayback = c.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE
            startPositionTicker()
            startSleepTimerTicker()
            pendingPlay?.let { (tracks, index) ->
                pendingPlay = null
                play(tracks, index)
            }
        }, { runnable -> scope.launch { withContext(Dispatchers.Main) { runnable.run() } } })
    }

    /** Play [tracks] starting at [startIndex]. When [startPositionMs] > 0, resume at
     *  that position within the starting track (for last-played / favourite-DJ resume).
     *  A new user playback session cancels any active sleep timer. */
    fun play(
        tracks: List<Track>,
        startIndex: Int,
        startPositionMs: Long = 0L,
        cancelSleepTimer: Boolean = true,
    ) {
        val c = controller
        if (c == null) {
            pendingPlay = tracks to startIndex
            return
        }
        if (cancelSleepTimer) cancelSleepTimer()
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

    /** Arms the sleep timer for [minutes] (15/30/45/60). 0 or negative cancels. */
    fun setSleepTimer(minutes: Int) {
        if (minutes > 0) {
            sendSleepTimerCommand(PlaybackService.COMMAND_SET_SLEEP_TIMER) { extras ->
                extras.putInt(PlaybackService.EXTRA_MINUTES, minutes)
            }
            _sleepTimerMinutes.value = minutes
            _sleepTimerRemainingMs.value = minutes * 60_000L
        } else {
            cancelSleepTimer()
        }
    }

    /** Cancels an armed sleep timer and clears the UI state. */
    fun cancelSleepTimer() {
        sendSleepTimerCommand(PlaybackService.COMMAND_CANCEL_SLEEP_TIMER)
        _sleepTimerMinutes.value = null
        _sleepTimerRemainingMs.value = null
    }

    private fun sendSleepTimerCommand(
        action: String,
        configureExtras: (Bundle) -> Unit = {},
    ) {
        val c = controller ?: return
        val extras = Bundle().apply(configureExtras)
        val command = SessionCommand(action, extras)
        val future: ListenableFuture<SessionResult> = c.sendCustomCommand(command, Bundle.EMPTY)
        future.addListener({
            runCatching { future.get() }
        }, { runnable -> scope.launch { withContext(Dispatchers.Main) { runnable.run() } } })
    }

    /** Polls the service for the remaining sleep time while a timer is active. */
    private fun startSleepTimerTicker() {
        scope.launch {
            while (true) {
                delay(5_000L)
                if (_sleepTimerMinutes.value != null) {
                    querySleepTimerRemaining()
                }
            }
        }
    }

    private fun querySleepTimerRemaining() {
        val c = controller ?: return
        val command = SessionCommand(PlaybackService.COMMAND_GET_SLEEP_TIMER_REMAINING, Bundle.EMPTY)
        val future: ListenableFuture<SessionResult> = c.sendCustomCommand(command, Bundle.EMPTY)
        future.addListener({
            val result = runCatching { future.get() }.getOrNull() ?: return@addListener
            if (result.resultCode == SessionResult.RESULT_SUCCESS) {
                val extras = result.extras
                if (extras.containsKey(PlaybackService.EXTRA_REMAINING_MS)) {
                    _sleepTimerRemainingMs.value = extras.getLong(PlaybackService.EXTRA_REMAINING_MS)
                } else {
                    // Timer has expired or was cancelled.
                    _sleepTimerMinutes.value = null
                    _sleepTimerRemainingMs.value = null
                }
            }
        }, { runnable -> scope.launch { withContext(Dispatchers.Main) { runnable.run() } } })
    }

    fun release() {
        controller?.removeListener(deviceInfoListener)
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
    return MediaItem.Builder()
        .setUri(url)
        .setMediaId(id)
        .setMimeType(mimeType ?: MimeTypes.AUDIO_MPEG)
        .setMediaMetadata(metadata)
        .build()
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

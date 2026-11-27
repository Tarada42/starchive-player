package com.starchive.player.playback

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import com.starchive.player.R
import com.starchive.player.data.Artist
import com.starchive.player.data.Repository
import com.starchive.player.data.SettingsDataStore
import com.starchive.player.data.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Android Auto / MediaLibraryService callback.
 *
 * Exposes a three-level browse tree (Live now, Artists, Favorites) and resolves playback
 * requests into full MediaItems. Archive selections are expanded to the artist's full track
 * list so prev/next work inside that artist.
 */
@UnstableApi
class MediaLibrarySessionCallback(
    private val context: Context,
    private val repository: Repository,
    private val serviceScope: CoroutineScope,
    private val sleepTimer: SleepTimer,
    private val settings: SettingsDataStore,
    /** True while the phone is casting; the car must never drive a cast device. */
    private val isCastingProvider: () -> Boolean = { false },
) : MediaLibraryService.MediaLibrarySession.Callback {

    /** Auto-resume is attempted once per service lifetime, not per reconnecting controller. */
    private val autoResumeAttempted = java.util.concurrent.atomic.AtomicBoolean(false)

    /** True while a Stop is being handled, so the pause-save cannot resurrect a resume point. */
    @Volatile
    var stopInProgress: Boolean = false
        private set

    fun clearStopInProgress() {
        stopInProgress = false
    }

    private val executor: ListeningExecutorService =
        MoreExecutors.listeningDecorator(
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "StArchiveMediaLibraryCallback").apply { isDaemon = true }
            }
        )

    // --- Browse tree ---

    override fun onGetLibraryRoot(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> = future {
        LibraryResult.ofItem(rootItem(), params)
    }

    override fun onGetChildren(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
        val children = loadChildren(parentId)
        LibraryResult.ofItemList(ImmutableList.copyOf(children), params)
    }

    override fun onGetItem(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> = future {
        resolveMediaItem(mediaId)?.let {
            LibraryResult.ofItem(it, /* params= */ null)
        } ?: LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
    }

    // --- Playback resolution ---

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
    ): ListenableFuture<List<MediaItem>> = future { resolveIncoming(mediaItems) }

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = future {
        // The legacy player path (playFromMediaId) delivers items with a mediaId only and
        // no URI; they must be resolved before ExoPlayer builds a MediaSource.
        val resolved = resolveIncoming(mediaItems)
        val item = resolved.getOrNull(startIndex) ?: resolved.firstOrNull()
        if (item == null) {
            return@future MediaSession.MediaItemsWithStartPosition(emptyList(), 0, startPositionMs)
        }

        val mediaId = item.mediaId
        if (mediaId.startsWith(LIVE_PREFIX)) {
            // Live: exactly one item in the queue. Returning the whole resolved list left
            // every other station queued behind it, which is what gave the car a
            // next-track button on a live stream.
            return@future MediaSession.MediaItemsWithStartPosition(listOf(item), 0, startPositionMs)
        }

        // Only expand archive selections for Android Auto / Automotive OS controllers.
        // The phone UI builds its own queues via PlaybackManager and must not be overridden.
        if (!mediaSession.isAutoCompanionController(controller) && !mediaSession.isAutomotiveController(controller)) {
            return@future MediaSession.MediaItemsWithStartPosition(
                resolved,
                startIndex.coerceIn(0, (resolved.size - 1).coerceAtLeast(0)),
                startPositionMs,
            )
        }

        // Archive: expand to the artist's full track list, starting at the selected track.
        val track = repository.trackById(mediaId)
        if (track == null) {
            return@future MediaSession.MediaItemsWithStartPosition(
                resolved,
                startIndex.coerceIn(0, (resolved.size - 1).coerceAtLeast(0)),
                startPositionMs,
            )
        }
        val artist = Artist(track.artistId, track.artistName, thumbnailUrl = null, twitchAvatarUrl = null)
        val tracks = repository.tracks(artist)
        if (tracks.isEmpty()) {
            return@future MediaSession.MediaItemsWithStartPosition(
                resolved,
                startIndex.coerceIn(0, (resolved.size - 1).coerceAtLeast(0)),
                startPositionMs,
            )
        }
        val items = tracks.map { it.toArchiveMediaItem() }
        val index = tracks.indexOfFirst { it.id == mediaId }.coerceIn(0, items.size - 1)
        MediaSession.MediaItemsWithStartPosition(items, index, startPositionMs)
    }

    // --- Command filtering ---

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
        // A car controller is connecting: remember that the car has been used (so the
        // phone only asks the first-run question after a real drive) and resume the
        // last set if the user opted in.
        if (session.isAutoCompanionController(controller) || session.isAutomotiveController(controller)) {
            serviceScope.launch {
                runCatching { settings.markCarSeen() }
                runCatching { autoResumeForCar(session) }
            }
        }

        val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
            .add(SessionCommand(PlaybackService.COMMAND_SET_SLEEP_TIMER, Bundle.EMPTY))
            .add(SessionCommand(PlaybackService.COMMAND_CANCEL_SLEEP_TIMER, Bundle.EMPTY))
            .add(SessionCommand(PlaybackService.COMMAND_GET_SLEEP_TIMER_REMAINING, Bundle.EMPTY))
            .add(SessionCommand(PlaybackService.COMMAND_AA_STOP, Bundle.EMPTY))
            .add(SessionCommand(PlaybackService.COMMAND_AA_SEEK_BACK, Bundle.EMPTY))
            .add(SessionCommand(PlaybackService.COMMAND_AA_SEEK_FORWARD, Bundle.EMPTY))
            .build()
        // DEFAULT_PLAYER_COMMANDS is addAllCommands(), so seek/skip commands are available
        // unless explicitly removed. The car draws rewind/fast-forward/skip buttons whenever
        // they are advertised, including on live streams. Archive sets use custom session
        // commands for -30 s / +30 s instead.
        val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
            .removeAll(
                Player.COMMAND_SEEK_BACK,
                Player.COMMAND_SEEK_FORWARD,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            )
            .add(Player.COMMAND_STOP)
            .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            .build()
        val live = session.player.isLiveNow()
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
            .setAvailableSessionCommands(sessionCommands)
            .setAvailablePlayerCommands(playerCommands)
            .setMediaButtonPreferences(mediaButtonPreferences(live))
            .build()
    }

    /**
     * Re-publish the media button preferences once the media-notification controller has
     * actually connected so the buttons match the currently-playing item (archive vs live).
     * The initial connection result already uses the current item, but this catches any
     * race and forces an immediate rebuild of the legacy PlaybackStateCompat.
     */
    override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
        // Only the media-notification controller's connection refreshes the legacy
        // PlaybackStateCompat that Android Auto reads (media3 1.11.1
        // MediaSessionImpl.setMediaButtonPreferences(ControllerInfo):578-590).
        if (!session.isMediaNotificationController(controller)) return
        runCatching {
            logLiveCommandDiagnostics(session.player, "onPostConnect")
            val live = session.player.isLiveNow()
            val buttons = mediaButtonPreferences(live)
            android.util.Log.i(
                "STArchiveAA",
                "onPostConnect pkg=${controller.packageName} buttons=${buttons.map { it.displayName }}",
            )
            // For the media-notification controller, the per-controller overload updates
            // the platform session AND rebuilds PlaybackStateCompat immediately. The global
            // overload only stores the preferences for future controllers and does not
            // trigger an immediate legacy state rebuild.
            session.setMediaButtonPreferences(controller, buttons)
        }
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        return when (customCommand.customAction) {
            PlaybackService.COMMAND_SET_SLEEP_TIMER -> {
                val minutes = customCommand.customExtras.getInt(PlaybackService.EXTRA_MINUTES, 0)
                if (minutes > 0) {
                    sleepTimer.start(minutes * 60_000L)
                } else {
                    sleepTimer.cancel()
                }
                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, Bundle.EMPTY))
            }
            PlaybackService.COMMAND_AA_SEEK_BACK,
            PlaybackService.COMMAND_AA_SEEK_FORWARD,
            -> {
                val delta = if (customCommand.customAction == PlaybackService.COMMAND_AA_SEEK_BACK) -30_000L else 30_000L
                val player = session.player
                val live = player.isLiveNow()
                if (live) {
                    Futures.immediateFuture(
                        SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED, Bundle.EMPTY)
                    )
                } else {
                    val duration = player.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
                    player.seekTo((player.currentPosition + delta).coerceIn(0L, duration))
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, Bundle.EMPTY))
                }
            }
            PlaybackService.COMMAND_AA_STOP -> {
                stopInProgress = true
                val wasLive = session.player.isLiveNow()
                session.player.pause()
                session.player.stop()
                if (wasLive) {
                    // Leaving a stopped live item in the session keeps the car on the player
                    // screen. Dropping it closes the player and returns to the browse list.
                    session.player.clearMediaItems()
                }
                serviceScope.launch { repository.clearLastPlayback() }
                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, Bundle.EMPTY))
            }
            PlaybackService.COMMAND_CANCEL_SLEEP_TIMER -> {
                sleepTimer.cancel()
                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, Bundle.EMPTY))
            }
            PlaybackService.COMMAND_GET_SLEEP_TIMER_REMAINING -> {
                val extras = Bundle()
                sleepTimer.remainingMs()?.let { remainingMs ->
                    extras.putLong(PlaybackService.EXTRA_REMAINING_MS, remainingMs)
                }
                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, extras))
            }
            else -> Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
        }
    }

    /**
     * Resume the last archive set in the car. Deliberately conservative: only when the
     * user chose "on", only when nothing is queued, and never for a live stream — the
     * car joining a live set unasked would be a surprise, not a convenience.
     */
    private suspend fun autoResumeForCar(session: MediaSession) {
        if (!autoResumeAttempted.compareAndSet(false, true)) return
        if (isCastingProvider()) return
        if (settings.aaResumeChoice.first() != "on") return
        val player = session.player
        if (player.mediaItemCount > 0) return

        val resume = withContext(Dispatchers.IO) {
            val last = repository.lastPlaybackOnce() ?: return@withContext null
            val (trackId, positionMs) = last
            if (trackId.startsWith(LIVE_PREFIX)) return@withContext null
            val track = repository.trackById(trackId) ?: return@withContext null
            val artist = Artist(track.artistId, track.artistName, thumbnailUrl = null, twitchAvatarUrl = null)
            val tracks = repository.tracks(artist)
            if (tracks.isEmpty()) return@withContext null
            Triple(tracks, trackId, positionMs)
        } ?: return

        val (tracks, trackId, positionMs) = resume
        val items = tracks.map { it.toArchiveMediaItem() }
        val index = tracks.indexOfFirst { it.id == trackId }.coerceAtLeast(0)
        player.setMediaItems(items, index, positionMs)
        player.prepare()
        player.play()
    }

    /**
     * Car player buttons, expressed as media button preferences with CommandButton slots so
     * Android Auto (and the media notification) can place up to four buttons.
     *
     * Archive sets get the -/+30 s pair plus Stop; live streams get Stop (seeking a live
     * stream is meaningless, and the host's transport has no stop of its own).
     */
    fun mediaButtonPreferences(live: Boolean): List<CommandButton> {
        val stopButton = CommandButton.Builder(CommandButton.ICON_STOP)
            .setDisplayName(context.getString(R.string.aa_stop))
            .setSessionCommand(SessionCommand(PlaybackService.COMMAND_AA_STOP, Bundle.EMPTY))
        if (live) {
            return listOf(
                stopButton
                    .setSlots(CommandButton.SLOT_BACK)
                    .build(),
            )
        }
        // Custom session commands, not player commands: advertising COMMAND_SEEK_BACK/
        // FORWARD made the car draw its own seek buttons on LIVE streams too, where they
        // are meaningless. Custom buttons appear only where this layout puts them.
        return listOf(
            CommandButton.Builder(CommandButton.ICON_SKIP_BACK_30)
                .setDisplayName(context.getString(R.string.aa_seek_back_30))
                .setSessionCommand(SessionCommand(PlaybackService.COMMAND_AA_SEEK_BACK, Bundle.EMPTY))
                .build(),
            CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_30)
                .setDisplayName(context.getString(R.string.aa_seek_forward_30))
                .setSessionCommand(SessionCommand(PlaybackService.COMMAND_AA_SEEK_FORWARD, Bundle.EMPTY))
                .build(),
            stopButton
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .build(),
        )
    }

    /** Release the background executor when the service is destroyed. */
    fun release() {
        executor.shutdown()
    }

    // --- Helpers ---

    private fun <T> future(block: suspend () -> T): ListenableFuture<T> =
        executor.submit(Callable { runBlocking(Dispatchers.IO) { block() } })

    /** Replace mediaId-only items with fully resolved items that carry a URI. */
    private suspend fun resolveIncoming(mediaItems: List<MediaItem>): List<MediaItem> =
        mediaItems.map { item ->
            if (item.localConfiguration != null) {
                item
            } else {
                resolveMediaItem(item.mediaId) ?: item
            }
        }

    private suspend fun loadChildren(parentId: String): List<MediaItem> = when (parentId) {
        ID_ROOT -> listOf(liveNowFolder(), artistsFolder(), favoritesFolder())
        ID_LIVE_NOW -> {
            repository.loadLive()
            repository.liveArtists.value.map { it.toLiveMediaItem(repository) }
        }
        ID_ARTISTS -> {
            repository.loadArtists()
            // Newest activity first, not A-Z. Prefer the newest set's date when the
            // repository already knows it, otherwise the artist folder's source date.
            // loadLatestDates() walks every artist, so it is warmed in the background
            // instead of blocking the browse request.
            serviceScope.launch { runCatching { repository.loadLatestDates() } }
            val newestByArtist = repository.latestDateByArtist.value
            repository.artists.value
                .sortedWith(
                    compareByDescending<Artist> { newestByArtist[it.id] ?: it.sourceDateMillis }
                        .thenBy { it.displayName.lowercase() }
                )
                .map { it.toArtistFolderMediaItem() }
        }
        ID_FAVORITES -> {
            // Car favourites are DJs, so this folder lists favourite artists as browsable
            // folders. Track favourites stay a phone-only concept and are not listed here.
            val ids = repository.favoriteArtists.first()
            if (ids.isEmpty()) {
                emptyList()
            } else {
                repository.loadArtists()
                val byId = repository.artists.value.associateBy { it.id.lowercase() }
                ids.mapNotNull { id ->
                    byId[id.lowercase()]?.toArtistFolderMediaItem()
                }
            }
        }
        else -> {
            if (parentId.startsWith(ARTIST_FOLDER_PREFIX)) {
                val artistId = parentId.removePrefix(ARTIST_FOLDER_PREFIX)
                repository.loadArtists()
                val artist = repository.artists.value.find { it.id == artistId }
                    ?: Artist(artistId, artistId.replace('_', ' '), null, null)
                val sets = repository.tracks(artist).map { it.toArchiveMediaItem() }
                // Mirror the phone: when this DJ is streaming right now, their live stream sits
                // at the top of the folder, above the archived sets.
                val liveEntry = liveEntryFor(artistId)
                if (liveEntry != null) {
                    listOf(liveEntry.toLiveMediaItem(repository)) + sets
                } else {
                    sets
                }
            } else {
                emptyList()
            }
        }
    }

    /**
     * The cached live roster entry for [artistId], if that DJ is streaming right now.
     *
     * Browsing must not block on the live server, so the cached roster is used. Only when
     * the cache is still empty (first browse after connecting) does this kick off a fetch
     * and wait briefly for it; a slow or dead live server just means no live row this time.
     */
    private suspend fun liveEntryFor(artistId: String): Artist? {
        var roster = repository.liveArtists.value
        if (roster.isEmpty()) {
            serviceScope.launch { runCatching { repository.loadLive() } }
            roster = withTimeoutOrNull(LIVE_ROSTER_WAIT_MS) {
                repository.liveArtists.first { it.isNotEmpty() }
            } ?: repository.liveArtists.value
        }
        return roster.firstOrNull { it.id.equals(artistId, ignoreCase = true) }
    }

    private suspend fun resolveMediaItem(mediaId: String): MediaItem? = when {
        mediaId == ID_ROOT -> rootItem()
        mediaId == ID_LIVE_NOW -> liveNowFolder()
        mediaId == ID_ARTISTS -> artistsFolder()
        mediaId == ID_FAVORITES -> favoritesFolder()
        mediaId.startsWith(ARTIST_FOLDER_PREFIX) -> {
            val artistId = mediaId.removePrefix(ARTIST_FOLDER_PREFIX)
            repository.loadArtists()
            repository.artists.value.find { it.id == artistId }?.toArtistFolderMediaItem()
        }
        mediaId.startsWith(LIVE_PREFIX) -> {
            val artistId = mediaId.removePrefix(LIVE_PREFIX)
            repository.loadLive()
            repository.liveArtists.value.find { it.id == artistId }
                ?.toLiveMediaItem(repository)
        }
        else -> repository.trackById(mediaId)?.toArchiveMediaItem()
    }

    private fun rootItem(): MediaItem = folderMediaItem(ID_ROOT, "STArchive", artworkUri = null)

    private fun liveNowFolder(): MediaItem = folderMediaItem(ID_LIVE_NOW, "Live now", artworkUri = null)

    private fun artistsFolder(): MediaItem = folderMediaItem(ID_ARTISTS, "Artists", artworkUri = null)

    private fun favoritesFolder(): MediaItem = folderMediaItem(ID_FAVORITES, "Favorites", artworkUri = null)

    private fun folderMediaItem(
        id: String,
        title: String,
        artworkUri: Uri?,
    ): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtworkUri(artworkUri)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
            .build()
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(metadata)
            .build()
    }

    private fun Artist.toArtistFolderMediaItem(): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(displayName)
            .setArtworkUri(bestImageUrl?.let { Uri.parse(it) })
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
            .build()
        return MediaItem.Builder()
            .setMediaId("$ARTIST_FOLDER_PREFIX$id")
            .setMediaMetadata(metadata)
            .build()
    }

    private fun Artist.toLiveMediaItem(repository: Repository): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle("$displayName · Live")
            .setArtist(displayName)
            .setArtworkUri(bestImageUrl?.let { Uri.parse(it) })
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .build()
        return MediaItem.Builder()
            .setUri(repository.liveStreamUrlHigh(id))
            .setMediaId("$LIVE_PREFIX$id")
            .setMimeType(MimeTypes.AUDIO_MPEG)
            .setMediaMetadata(metadata)
            // Mark the item as live so ExoPlayer's timeline reports isCurrentMediaItemLive()
            // and MediaSessionLegacyStub clears ACTION_SEEK_TO from the PlaybackStateCompat.
            .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().build())
            .build()
    }

    private fun Track.toArchiveMediaItem(): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artistName)
            .setArtworkUri(thumbnailUrl?.let { Uri.parse(it) })
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .build()
        return MediaItem.Builder()
            .setUri(url)
            .setMediaId(id)
            .setMimeType(mimeType ?: MimeTypes.AUDIO_MPEG)
            .setMediaMetadata(metadata)
            .build()
    }

    companion object {
        private const val ID_ROOT = "root"
        private const val ID_LIVE_NOW = "live-now"
        private const val ID_ARTISTS = "artists"
        private const val ID_FAVORITES = "favorites"
        private const val ARTIST_FOLDER_PREFIX = "artist-"
        private const val LIVE_PREFIX = "live-"

        /** Bounded wait for a first-time live fetch while browsing an artist folder. */
        private const val LIVE_ROSTER_WAIT_MS = 2500L

    }
}

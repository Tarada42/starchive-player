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
import com.starchive.player.data.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
) : MediaLibraryService.MediaLibrarySession.Callback {

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
            // Live: single-item queue.
            return@future MediaSession.MediaItemsWithStartPosition(
                resolved,
                startIndex.coerceIn(0, (resolved.size - 1).coerceAtLeast(0)),
                startPositionMs,
            )
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
        val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
            .add(SessionCommand(PlaybackService.COMMAND_SET_SLEEP_TIMER, Bundle.EMPTY))
            .add(SessionCommand(PlaybackService.COMMAND_CANCEL_SLEEP_TIMER, Bundle.EMPTY))
            .add(SessionCommand(PlaybackService.COMMAND_GET_SLEEP_TIMER_REMAINING, Bundle.EMPTY))
            .add(SessionCommand(PlaybackService.COMMAND_AA_STOP, Bundle.EMPTY))
            .add(SessionCommand(PlaybackService.COMMAND_AA_FAVORITE, Bundle.EMPTY))
            .build()
        val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
            .add(Player.COMMAND_STOP)
            .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_BACK)
            .add(Player.COMMAND_SEEK_FORWARD)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .add(Player.COMMAND_SEEK_TO_NEXT)
            .build()
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailableSessionCommands(sessionCommands)
            .setAvailablePlayerCommands(playerCommands)
            .build()
    }

    override fun onPlayerCommandRequest(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        @Player.Command playerCommand: Int,
    ): Int {
        if (playerCommand == Player.COMMAND_STOP) {
            // Mirror the service-side stop behavior: clear the persisted resume state.
            serviceScope.launch { repository.clearLastPlayback() }
        }
        // Do not reject commands here. Returning an error mid-batch makes the car host
        // abort the whole selection ("Could not load your selection"). Live streams hide
        // seek/skip on their own because the item is non-seekable and the queue has one item.
        return SessionResult.RESULT_SUCCESS
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
            PlaybackService.COMMAND_AA_FAVORITE -> future {
                // Artist-only favouriting in the car: a heart means "I like this DJ",
                // whether the playing item is a live stream or one of their archive sets.
                val mediaId = session.player.currentMediaItem?.mediaId
                val artistId = when {
                    mediaId == null -> null
                    mediaId.startsWith(LIVE_PREFIX) -> mediaId.removePrefix(LIVE_PREFIX)
                    else -> repository.trackById(mediaId)?.artistId
                }
                var nowFavorite = false
                if (artistId != null) {
                    val current = repository.favoriteArtists.first()
                    nowFavorite = artistId !in current
                    val artist = repository.artists.value.find { it.id.equals(artistId, ignoreCase = true) }
                        ?: repository.liveArtists.value.find { it.id.equals(artistId, ignoreCase = true) }
                        ?: Artist(artistId, artistId.replace('_', ' '), thumbnailUrl = null, twitchAvatarUrl = null)
                    repository.toggleFavoriteArtist(artist)
                }
                // Reflect the new state in the button; a legacy (car) controller may ignore
                // this update, in which case only the action takes effect.
                runCatching { session.setCustomLayout(controller, customLayout(nowFavorite)) }
                SessionResult(SessionResult.RESULT_SUCCESS, Bundle.EMPTY)
            }
            PlaybackService.COMMAND_AA_STOP -> {
                // Halt playback but keep the current item in the session. Clearing it makes
                // the car host try to reload a now-playing item that no longer exists, which
                // surfaces as "Could not load your selection".
                session.player.pause()
                session.player.stop()
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

    /** Car player buttons: favorite heart (state-aware) and stop. */
    fun customLayout(favoriteFilled: Boolean): List<CommandButton> {
        val favorite = CommandButton.Builder(
            if (favoriteFilled) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED
        )
            .setDisplayName(context.getString(R.string.aa_favorite))
            .setSessionCommand(SessionCommand(PlaybackService.COMMAND_AA_FAVORITE, Bundle.EMPTY))
            .build()
        // Player-command button so the host renders it with the transport controls
        // (play/pause styling) instead of as a secondary custom-action chip.
        val stop = CommandButton.Builder(CommandButton.ICON_STOP)
            .setDisplayName(context.getString(R.string.aa_stop))
            .setPlayerCommand(Player.COMMAND_STOP)
            .build()
        return listOf(favorite, stop)
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
                repository.tracks(artist).map { it.toArchiveMediaItem() }
            } else {
                emptyList()
            }
        }
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

    }
}

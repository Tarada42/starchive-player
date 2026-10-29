package com.starchive.player.playback

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.starchive.player.MainActivity
import com.starchive.player.StArchiveApp
import com.starchive.player.data.Artist
import com.starchive.player.ui.ArtistSort
import com.starchive.player.data.Repository
import com.starchive.player.data.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch

/**
 * Background playback service (Media3 MediaLibraryService). Provides:
 *  - a MediaSession so playback continues with the screen off,
 *  - lock-screen / notification transport controls,
 *  - and a browsable media tree (Artists / Favorites / Search History) that makes
 *    the app discoverable and controllable from Android Auto and the Assistant.
 */
@UnstableApi
class PlaybackService : MediaLibraryService() {

    private var player: Player? = null
    private var mediaLibrarySession: MediaLibrarySession? = null
    private var _aaSort = ArtistSort.DATE
    private var _aaSortAscending = false

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
        // Android Auto's rewind/fast-forward buttons call seekBack()/seekForward().
        // media3 1.4.1 has no setSeekForwardIncrementMs, so we wrap the player in a
        // ForwardingPlayer that overrides those two to use a fixed 10-second step
        // and explicitly advertises SEEK_BACK / SEEK_FORWARD so Android Auto shows
        // the back/forward buttons.
        val seekPlayer = TenSecondSeekPlayer(exoPlayer)
        player = seekPlayer

        // Tapping the notification body (anywhere except the action buttons) opens and
        // focuses the app, directly on the Player screen for the now-playing track.
        // The transport buttons themselves stay handled by Media3.
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        mediaLibrarySession = MediaLibrarySession.Builder(this, seekPlayer, LibrarySessionCallback())
            .setSessionActivity(openAppIntent)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        mediaLibrarySession

    override fun onDestroy() {
        // The service is being stopped (e.g. user dismissed the notification's close
        // action): pause and clear the persisted resume state so the app closes out
        // to the home screen cleanly. Uses its own scope since serviceScope is about
        // to be cancelled.
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            runCatching { repository.clearLastPlayback() }
        }
        serviceScope.cancel()
        mediaLibrarySession?.run {
            player.release()
            release()
        }
        mediaLibrarySession = null
        player = null
        super.onDestroy()
    }

    // --- Android Auto browse tree ---

    /**
     * Implements the browsable media tree Android Auto (and the Assistant) use:
     *
     *   ROOT
     *   ├── Artists              (browsable) -> sort nodes
     *   │   ├── Sort by Name     (action node)
     *   │   ├── Sort by Date     (action node)
     *   ├── Live                 (browsable) -> live DJs (playable)
     *   ├── Favorites            (browsable)
     *   │   ├── Favorite Artists (browsable) -> tracks (playable)
     *   │   └── Favorite Tracks  (playable)
     *   └── Search History       (browsable)
     *       └── <past query>    (browsable) -> matching tracks (playable)
     *
     * Voice actions ("play dj_td", "play favorites") arrive through [onAddMediaItems],
     * which resolves a loosely specified request item into real, playable [MediaItem]s.
     */
    private inner class LibrarySessionCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(folderItem(ROOT_ID, "STArchive"), params))

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = serviceScope.future {
            val item = resolveBrowsableOrPlayable(mediaId)
            if (item != null) LibraryResult.ofItem(item, null)
            else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = serviceScope.future {
            LibraryResult.ofItemList(ImmutableList.copyOf(childrenOf(parentId)), params)
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = serviceScope.future {
            val results = repository.search(query)
            session.notifySearchResultChanged(browser, query, results.size, params)
            LibraryResult.ofVoid(params)
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = serviceScope.future {
            val results = repository.search(query).map { it.toMediaItem() }
            LibraryResult.ofItemList(ImmutableList.copyOf(results), params)
        }

        /**
         * Resolves voice / Assistant "play" requests, e.g. "play dj_td on STArchive" or
         * "play favorites on STArchive". The incoming [mediaItems] usually carry only a
         * media id or a free-text search query in [MediaItem.requestMetadata].
         */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> = serviceScope.future {
            mediaItems.flatMap { resolveForPlayback(it) }
        }
    }

    private suspend fun resolveForPlayback(requestItem: MediaItem): List<MediaItem> {
        val mediaId = requestItem.mediaId
        val query = requestItem.requestMetadata.searchQuery

        return when {
            // Live restreams carry their own playable URL/MIME; keep them as-is rather
            // than trying to resolve against the archive (they have no track file id).
            mediaId.startsWith(LIVE_PREFIX) -> listOf(requestItem)
            mediaId.startsWith(ARTIST_PREFIX) -> {
                val artistId = mediaId.removePrefix(ARTIST_PREFIX)
                tracksForArtistId(artistId).map { it.toMediaItem() }
            }
            mediaId == FAVORITE_TRACKS_ID || query?.contains("favorite", ignoreCase = true) == true -> {
                favoriteTracks().map { it.toMediaItem() }
            }
            !query.isNullOrBlank() -> repository.search(query).map { it.toMediaItem() }
            mediaId.isNotBlank() -> {
                repository.trackById(mediaId)?.let { listOf(it.toMediaItem()) } ?: emptyList()
            }
            else -> emptyList()
        }
    }

    private suspend fun resolveBrowsableOrPlayable(mediaId: String): MediaItem? {
        if (mediaId == ROOT_ID) return folderItem(ROOT_ID, "STArchive")
        if (mediaId == ARTISTS_ID) return folderItem(ARTISTS_ID, "Artists")
        if (mediaId == ARTISTS_NAME_ID) return folderItem(ARTISTS_NAME_ID, "Sort by Name")
        if (mediaId == ARTISTS_DATE_ID) return folderItem(ARTISTS_DATE_ID, "Sort by Date")
        if (mediaId == ARTISTS_TOGGLE_ID) return folderItem(ARTISTS_TOGGLE_ID, "Reverse Order")
        if (mediaId == LIVE_ID) return folderItem(LIVE_ID, "Live")
        if (mediaId == FAVORITES_ID) return folderItem(FAVORITES_ID, "Favorites")
        if (mediaId == FAVORITE_ARTISTS_ID) return folderItem(FAVORITE_ARTISTS_ID, "Favorite Artists")
        if (mediaId == FAVORITE_TRACKS_ID) return folderItem(FAVORITE_TRACKS_ID, "Favorite Tracks")
        if (mediaId == SEARCH_HISTORY_ID) return folderItem(SEARCH_HISTORY_ID, "Search History")
        if (mediaId.startsWith(ARTIST_PREFIX)) {
            val artistId = mediaId.removePrefix(ARTIST_PREFIX)
            val artist = repository.artists.value.find { it.id == artistId }
            return artist?.toFolderItem(mediaId) ?: folderItem(mediaId, prettify(artistId))
        }
        if (mediaId.startsWith(SEARCH_PREFIX)) {
            return folderItem(mediaId, mediaId.removePrefix(SEARCH_PREFIX))
        }
        return repository.trackById(mediaId)?.toMediaItem()
    }

    private suspend fun childrenOf(parentId: String): List<MediaItem> = when (parentId) {
        ROOT_ID -> listOf(
            folderItem(ARTISTS_ID, "Artists"),
            folderItem(LIVE_ID, "Live"),
            folderItem(FAVORITES_ID, "Favorites"),
            folderItem(SEARCH_HISTORY_ID, "Search History"),
        )
        ARTISTS_ID -> {
            repository.loadArtists()
            val sort = _aaSort
            val asc = _aaSortAscending
            val sorted = sortedArtists(repository.artists.value, repository.latestDateByArtist.value, sort, asc)
            sorted.map { it.toFolderItem("$ARTIST_PREFIX${it.id}") }
        }
        ARTISTS_NAME_ID -> {
            _aaSort = ArtistSort.NAME
            _aaSortAscending = true
            repository.loadArtists()
            val sorted = sortedArtists(repository.artists.value, repository.latestDateByArtist.value, ArtistSort.NAME, true)
            sorted.map { it.toFolderItem("$ARTIST_PREFIX${it.id}") }
        }
        ARTISTS_DATE_ID -> {
            _aaSort = ArtistSort.DATE
            _aaSortAscending = false
            repository.loadArtists()
            val sorted = sortedArtists(repository.artists.value, repository.latestDateByArtist.value, ArtistSort.DATE, false)
            sorted.map { it.toFolderItem("$ARTIST_PREFIX${it.id}") }
        }
        ARTISTS_TOGGLE_ID -> {
            _aaSortAscending = !_aaSortAscending
            repository.loadArtists()
            val sorted = sortedArtists(repository.artists.value, repository.latestDateByArtist.value, _aaSort, _aaSortAscending)
            sorted.map { it.toFolderItem("$ARTIST_PREFIX${it.id}") }
        }
        LIVE_ID -> {
            repository.loadLive(forceRefresh = true)
            repository.liveArtists.value.map { it.toLiveMediaItem() }
        }
        FAVORITES_ID -> listOf(
            folderItem(FAVORITE_ARTISTS_ID, "Favorite Artists"),
            folderItem(FAVORITE_TRACKS_ID, "Favorite Tracks"),
        )
        FAVORITE_ARTISTS_ID -> {
            val favIds = favoriteArtistIds()
            repository.loadArtists()
            repository.artists.value.filter { it.id in favIds }
                .map { it.toFolderItem("$ARTIST_PREFIX${it.id}") }
        }
        FAVORITE_TRACKS_ID -> favoriteTracks().map { it.toMediaItem() }
        SEARCH_HISTORY_ID -> recentSearches().map { folderItem("$SEARCH_PREFIX$it", it) }
        else -> when {
            parentId.startsWith(ARTIST_PREFIX) ->
                tracksForArtistId(parentId.removePrefix(ARTIST_PREFIX)).map { it.toMediaItem() }
            parentId.startsWith(SEARCH_PREFIX) ->
                repository.search(parentId.removePrefix(SEARCH_PREFIX)).map { it.toMediaItem() }
            else -> emptyList()
        }
    }

    private suspend fun tracksForArtistId(artistId: String): List<Track> {
        val artist = repository.artists.value.find { it.id == artistId }
            ?: Artist(artistId, prettify(artistId), null, null)
        return runCatching { repository.tracks(artist) }.getOrDefault(emptyList())
    }

    private suspend fun favoriteArtistIds(): Set<String> =
        runCatching { repository.favoriteArtists.first() }.getOrDefault(emptySet())

    private suspend fun favoriteTracks(): List<Track> {
        val ids = runCatching { repository.favoriteTracks.first() }.getOrDefault(emptySet())
        return repository.favoriteTrackObjects(ids)
    }

    private suspend fun recentSearches(): List<String> =
        runCatching { repository.recentSearches.first() }.getOrDefault(emptyList())

    /**
     * Sort helper matching MainViewModel: by name (case-insensitive) or by newest date
     * (sourceDateMillis, falling back to latest track-derived date).
     */
    private fun sortedArtists(
        artists: List<Artist>,
        dates: Map<String, Long>,
        sort: ArtistSort,
        asc: Boolean,
    ): List<Artist> = when (sort) {
        ArtistSort.NAME -> if (asc) artists.sortedBy { it.displayName.lowercase() }
            else artists.sortedByDescending { it.displayName.lowercase() }
        ArtistSort.DATE -> {
            val keyed = artists.sortedByDescending { a -> a.sourceDateMillis.takeIf { it > 0L } ?: dates[a.id] ?: 0L }
            if (asc) keyed.reversed() else keyed
        }
    }

    /** Map an artist to a playable live-stream item (used by the AA "Live" node). */
    private fun Artist.toLiveMediaItem(): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(displayName)
            .setArtist("LIVE")
            .setArtworkUri(bestImageUrl?.let { android.net.Uri.parse(it) })
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .build()
        return MediaItem.Builder()
            .setUri(repository.liveStreamUrl(id))
            .setMediaId("$LIVE_PREFIX$id")
            .setMimeType(androidx.media3.common.MimeTypes.AUDIO_MPEG)
            .setMediaMetadata(metadata)
            .build()
    }

    private fun prettify(raw: String): String = raw.replace('_', ' ').trim()

    private fun folderItem(id: String, title: String): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .build()
        return MediaItem.Builder().setMediaId(id).setMediaMetadata(metadata).build()
    }

    private fun Artist.toFolderItem(mediaId: String): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(displayName)
            .setArtworkUri(bestImageUrl?.let { android.net.Uri.parse(it) })
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .build()
        return MediaItem.Builder().setMediaId(mediaId).setMediaMetadata(metadata).build()
    }

    companion object {
        const val ROOT_ID = "root"
        const val ARTISTS_ID = "artists"
        const val ARTISTS_NAME_ID = "artists:name"
        const val ARTISTS_DATE_ID = "artists:date"
        const val ARTISTS_TOGGLE_ID = "artists:toggle"
        const val LIVE_ID = "live"
        const val FAVORITES_ID = "favorites"
        const val FAVORITE_ARTISTS_ID = "favorite_artists"
        const val FAVORITE_TRACKS_ID = "favorite_tracks"
        const val SEARCH_HISTORY_ID = "search_history"
        const val ARTIST_PREFIX = "artist:"
        const val SEARCH_PREFIX = "search:"
        const val LIVE_PREFIX = "live-"
    }
}

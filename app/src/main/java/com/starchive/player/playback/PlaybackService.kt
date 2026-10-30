package com.starchive.player.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.cast.CastPlayer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.starchive.player.MainActivity
import com.starchive.player.R
import com.starchive.player.StArchiveApp
import com.starchive.player.data.Artist
import com.starchive.player.data.Repository
import com.starchive.player.data.Track
import com.starchive.player.ui.ArtistSort
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
 *  - and a flat, phone-like browsable media tree for Android Auto:
 *
 *   ROOT
 *   ├── Artists              (browsable)
 *   │   ├── [action] Sort: Date — newest first (tap for Name)
 *   │   ├── [action] Reverse order (now descending ↓)
 *   │   └── <artist>         (browsable) -> tracks (playable, single level)
 *   ├── Live                 (browsable)
 *   │   ├── <DJ> — High (MP3)  (playable)
 *   │   └── <DJ> — Low (Ogg)   (playable)
 *   ├── Favorite Artists     (browsable) -> artists (browsable) -> tracks
 *   ├── Favorite Tracks      (browsable) -> tracks (playable)
 *   └── Search History       (browsable)
 *       └── <past query>    (browsable) -> matching tracks (playable)
 *
 * Sort actions are flat "action items" (the AA equivalent of the phone's sort
 * chips): tapping one triggers a custom set-media request which the service
 * intercepts, toggles the sort, persists it, and tells AA to re-browse.
 * No cast functionality is exposed to Android Auto — casting is phone-only.
 */
@UnstableApi
class PlaybackService : MediaLibraryService() {

    private var player: Player? = null
    /** The local (ExoPlayer-wrapped) player; kept so we can swap back after casting. */
    private var localPlayer: Player? = null
    private var castPlayer: CastPlayer? = null
    private var castContext: CastContext? = null
    private var isCasting = false
    private var openAppIntent: PendingIntent? = null
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
        // ForwardingPlayer that overrides those two to use a fixed 30-second step
        // and explicitly advertises SEEK_BACK / SEEK_FORWARD so Android Auto shows
        // the back/forward buttons.
        val seekPlayer = ThirtySecondSeekPlayer(exoPlayer)
        player = seekPlayer
        localPlayer = seekPlayer

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

        mediaLibrarySession = MediaLibrarySession.Builder(this, seekPlayer, LibrarySessionCallback())
            .setSessionActivity(openAppIntent!!)
            .build()
        // AA player: expose a Stop button in the custom layout (tears down live
        // streams; stops + clears normal tracks).
        mediaLibrarySession?.setCustomLayout(listOf(STOP_BUTTON))

        // Google Cast (PHONE ONLY). CastContext init can fail on devices without Play
        // Services — fail soft and keep local playback fully working.
        castContext = runCatching { CastContext.getSharedInstance(this) }.getOrNull()
        castContext?.sessionManager?.addSessionManagerListener(typedCastSessionListener, CastSession::class.java)

        // Restore the persisted AA sort so it survives AA reconnects.
        serviceScope.launch {
            runCatching {
                val (sort, asc) = repository.aaSort.first()
                _aaSort = runCatching { ArtistSort.valueOf(sort) }.getOrDefault(ArtistSort.DATE)
                _aaSortAscending = asc
            }
        }
        // Warm the newest-date cache in the background so DATE sorting is meaningful
        // even when AA connects without the phone UI running.
        serviceScope.launch { runCatching { repository.loadLatestDates() } }
    }

    // --- Cast session handover (phone only; Android Auto never touches this) ---

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

    /** Copy media items + current index/position/play state between players. */
    private fun transferPlaybackState(from: Player, to: Player) {
        val items = (0 until from.mediaItemCount).map { from.getMediaItemAt(it) }
        if (items.isNotEmpty()) {
            to.setMediaItems(
                items,
                from.currentMediaItemIndex.coerceAtLeast(0),
                from.currentPosition.coerceAtLeast(0L),
            )
        }
        to.playWhenReady = from.playWhenReady
        to.prepare()
    }

    /** MediaSession can't swap its player in place — release and rebuild around [p]. */
    private fun rebuildSession(p: Player) {
        mediaLibrarySession?.release()
        mediaLibrarySession = MediaLibrarySession.Builder(this, p, LibrarySessionCallback())
            .setSessionActivity(openAppIntent!!)
            .build()
        mediaLibrarySession?.setCustomLayout(listOf(STOP_BUTTON))
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
        castContext?.sessionManager?.removeSessionManagerListener(typedCastSessionListener, CastSession::class.java)
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

    // --- Android Auto browse tree ---

    private inner class LibrarySessionCallback : MediaLibrarySession.Callback {

        /** Advertise the custom Stop command so AA can send it. */
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                        .buildUpon()
                        .add(STOP_COMMAND)
                        .build()
                )
                .build()

        /**
         * Custom Stop (AA custom layout button): for live streams this tears down the
         * Icecast connection (stop() releases the source); for normal tracks it stops
         * and clears the active media/session state.
         */
        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == CUSTOM_ACTION_STOP) {
                player?.let { p ->
                    p.stop()
                    p.clearMediaItems()
                }
                serviceScope.launch { runCatching { repository.clearLastPlayback() } }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
        }

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

        /**
         * Single choke point for every "play this selection" request (browse taps,
         * playFromMediaId, search results). Guarantees any new selection always starts
         * and plays the new choice:
         *
         *  - Flat sort "action items" are intercepted here: the sort is toggled and
         *    persisted, AA is told to re-browse, and the currently playing item is
         *    left completely untouched (the returned MediaItemsToSet mirrors the
         *    current playlist + position, so nothing restarts).
         *  - Everything else is resolved into fresh, fully-populated MediaItems
         *    (URI + MIME) via [resolveForPlayback] — never the bare mediaId-only
         *    request item, which previously caused the legacy AA stub to fall back
         *    to resuming the previously played track.
         */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsToSet> = serviceScope.future {
            if (mediaItems.size == 1 && mediaItems[0].mediaId.startsWith(ACTION_PREFIX)) {
                handleSortAction(mediaItems[0].mediaId)
                val p = player
                if (p != null && p.mediaItemCount > 0) {
                    val items = (0 until p.mediaItemCount).map { p.getMediaItemAt(it) }
                    MediaSession.MediaItemsToSet(items, p.currentMediaItemIndex, p.currentPosition)
                } else {
                    MediaSession.MediaItemsToSet(emptyList(), C.INDEX_UNSET, C.TIME_UNSET)
                }
            } else {
                val resolved = mediaItems.flatMap { resolveForPlayback(it) }
                val safeIndex = if (resolved.isEmpty()) C.INDEX_UNSET
                    else startIndex.coerceIn(0, resolved.size - 1)
                MediaSession.MediaItemsToSet(resolved, safeIndex, startPositionMs)
            }
        }
    }

    /**
     * Always rebuilds playable items from the request's mediaId (URI + MIME included).
     * Never returns the bare request item: legacy controllers (Android Auto) send
     * mediaId-only items for playFromMediaId, and passing them through yields
     * unplayable items — the session then falls back to the previously played track.
     */
    private suspend fun resolveForPlayback(requestItem: MediaItem): List<MediaItem> {
        val mediaId = requestItem.mediaId
        val query = requestItem.requestMetadata.searchQuery

        return when {
            // Sort actions are intercepted in onSetMediaItems; they never play.
            mediaId.startsWith(ACTION_PREFIX) -> emptyList()
            mediaId.startsWith(LIVE_HIGH_PREFIX) ->
                listOf(liveQualityItem(mediaId.removePrefix(LIVE_HIGH_PREFIX), high = true))
            mediaId.startsWith(LIVE_LOW_PREFIX) ->
                listOf(liveQualityItem(mediaId.removePrefix(LIVE_LOW_PREFIX), high = false))
            // Phone-built live items ("live-<artist>"): default to the HQ MP3 mount.
            mediaId.startsWith(LIVE_PREFIX) ->
                listOf(liveQualityItem(mediaId.removePrefix(LIVE_PREFIX), high = true))
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
        if (mediaId == LIVE_ID) return folderItem(LIVE_ID, "Live")
        if (mediaId == FAVORITE_ARTISTS_ID) return folderItem(FAVORITE_ARTISTS_ID, "Favorite Artists")
        if (mediaId == FAVORITE_TRACKS_ID) return folderItem(FAVORITE_TRACKS_ID, "Favorite Tracks")
        if (mediaId == SEARCH_HISTORY_ID) return folderItem(SEARCH_HISTORY_ID, "Search History")
        if (mediaId == ACTION_SORT_TOGGLE_ID) return actionItem(ACTION_SORT_TOGGLE_ID, sortToggleLabel())
        if (mediaId == ACTION_SORT_REVERSE_ID) return actionItem(ACTION_SORT_REVERSE_ID, reverseLabel())
        if (mediaId.startsWith(LIVE_HIGH_PREFIX) || mediaId.startsWith(LIVE_LOW_PREFIX)) {
            val high = mediaId.startsWith(LIVE_HIGH_PREFIX)
            val artistId = mediaId.removePrefix(if (high) LIVE_HIGH_PREFIX else LIVE_LOW_PREFIX)
            return liveQualityItem(artistId, high)
        }
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
            folderItem(FAVORITE_ARTISTS_ID, "Favorite Artists"),
            folderItem(FAVORITE_TRACKS_ID, "Favorite Tracks"),
            folderItem(SEARCH_HISTORY_ID, "Search History"),
        )
        ARTISTS_ID -> {
            repository.loadArtists()
            val sorted = sortedArtists(repository.artists.value, repository.latestDateByArtist.value, _aaSort, _aaSortAscending)
            // Flat sort actions (the AA equivalent of the phone's sort chips), then the
            // flat artist list. No nested sort folders.
            listOf(
                actionItem(ACTION_SORT_TOGGLE_ID, sortToggleLabel()),
                actionItem(ACTION_SORT_REVERSE_ID, reverseLabel()),
            ) + sorted.map { it.toFolderItem("$ARTIST_PREFIX${it.id}") }
        }
        LIVE_ID -> {
            repository.loadLive(forceRefresh = true)
            // Flat list: every live DJ appears twice — High (MP3) and Low (Ogg) — with
            // no per-artist quality sub-folder.
            repository.liveArtists.value.flatMap { artist ->
                listOf(
                    liveQualityItem(artist.id, high = true),
                    liveQualityItem(artist.id, high = false),
                )
            }
        }
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

    /** Toggle/persist the AA artist sort and tell AA to re-browse the Artists node. */
    private suspend fun handleSortAction(mediaId: String) {
        when (mediaId) {
            ACTION_SORT_TOGGLE_ID -> {
                _aaSort = if (_aaSort == ArtistSort.NAME) ArtistSort.DATE else ArtistSort.NAME
                _aaSortAscending = _aaSort == ArtistSort.NAME
            }
            ACTION_SORT_REVERSE_ID -> _aaSortAscending = !_aaSortAscending
            else -> return
        }
        runCatching { repository.setAASort(_aaSort.name, _aaSortAscending) }
        repository.loadArtists()
        val sorted = sortedArtists(repository.artists.value, repository.latestDateByArtist.value, _aaSort, _aaSortAscending)
        mediaLibrarySession?.notifyChildrenChanged(ARTISTS_ID, sorted.size + 2, null)
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

    /** Build a playable live-stream item for a specific quality (flat in the AA list). */
    private fun liveQualityItem(artistId: String, high: Boolean): MediaItem {
        val artist = repository.artists.value.find { it.id == artistId }
            ?: repository.liveArtists.value.find { it.id == artistId }
            ?: Artist(artistId, prettify(artistId), null, null)
        val url = if (high) repository.liveStreamUrlHigh(artistId) else repository.liveStreamUrlLow(artistId)
        val mime = if (high) androidx.media3.common.MimeTypes.AUDIO_MPEG else androidx.media3.common.MimeTypes.AUDIO_OGG
        val metadata = MediaMetadata.Builder()
            .setTitle("${artist.displayName} — ${if (high) "High (MP3)" else "Low (Ogg)"}")
            .setArtist("LIVE")
            .setArtworkUri(artist.bestImageUrl?.let { android.net.Uri.parse(it) })
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .build()
        val prefix = if (high) LIVE_HIGH_PREFIX else LIVE_LOW_PREFIX
        return MediaItem.Builder()
            .setUri(url)
            .setMediaId("$prefix$artistId")
            .setMimeType(mime)
            .setMediaMetadata(metadata)
            .build()
    }

    /** Label for the sort-toggle action item (mirrors the phone's active sort chip). */
    private fun sortToggleLabel(): String =
        if (_aaSort == ArtistSort.DATE) "Sort: Date — newest first (tap for Name)"
        else "Sort: Name A–Z (tap for Date)"

    /** Label for the direction-reverse action item (mirrors the phone's arrow). */
    private fun reverseLabel(): String =
        "Reverse order (now ${if (_aaSortAscending) "ascending ↑" else "descending ↓"})"

    private fun prettify(raw: String): String = raw.replace('_', ' ').trim()

    private fun folderItem(id: String, title: String): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .build()
        return MediaItem.Builder().setMediaId(id).setMediaMetadata(metadata).build()
    }

    /** A flat, playable action row; tapping it is intercepted in onSetMediaItems. */
    private fun actionItem(id: String, title: String): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setIsBrowsable(false)
            .setIsPlayable(true)
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
        const val LIVE_ID = "live"
        const val FAVORITE_ARTISTS_ID = "favorite_artists"
        const val FAVORITE_TRACKS_ID = "favorite_tracks"
        const val SEARCH_HISTORY_ID = "search_history"
        const val ARTIST_PREFIX = "artist:"
        const val SEARCH_PREFIX = "search:"
        const val ACTION_PREFIX = "action:"
        const val ACTION_SORT_TOGGLE_ID = "action:sort_toggle"
        const val ACTION_SORT_REVERSE_ID = "action:sort_reverse"
        const val LIVE_PREFIX = "live-"
        const val LIVE_HIGH_PREFIX = "live-high:"
        const val LIVE_LOW_PREFIX = "live-low:"

        private const val CUSTOM_ACTION_STOP = "com.starchive.player.STOP"
        private val STOP_COMMAND = SessionCommand(CUSTOM_ACTION_STOP, Bundle.EMPTY)
        private val STOP_BUTTON: CommandButton = CommandButton.Builder()
            .setDisplayName("Stop")
            .setIconResId(R.drawable.ic_stop_24)
            .setSessionCommand(STOP_COMMAND)
            .build()
    }
}

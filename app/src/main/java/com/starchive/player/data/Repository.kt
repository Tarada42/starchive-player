package com.starchive.player.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.regex.Pattern

/** Single entry point for archive data; caches artists + tracks in memory for the session. */
class Repository(
    context: Context,
    private val scraper: Scraper = Scraper(),
) {
    private val appContext = context.applicationContext

    private val favoritesDataStore = FavoritesDataStore(appContext)
    private val searchHistoryDataStore = SearchHistoryDataStore(appContext)
    private val avatarCacheFile = File(appContext.cacheDir, "avatar_cache.json")
    private val avatarCache: MutableMap<String, String> = loadAvatarCache().toMutableMap()
    private val avatarCacheTimesFile = File(appContext.cacheDir, "avatar_cache_times.json")
    private val avatarCacheTimes: MutableMap<String, Long> = loadAvatarCacheTimes().toMutableMap()
    private val datesCacheFile = File(appContext.cacheDir, "dates_cache.json")
    private val datesCache: MutableMap<String, Long> = loadDatesCache().toMutableMap()

    private val _artists = MutableStateFlow<List<Artist>>(emptyList())
    val artists: StateFlow<List<Artist>> = _artists.asStateFlow()

    /** Twitch usernames currently live on live.streambox.space (empty until first fetch). */
    private val _liveUsernames = MutableStateFlow<List<String>>(emptyList())
    val liveUsernames: StateFlow<List<String>> = _liveUsernames.asStateFlow()

    /** Resolved live DJs (with avatars where available). Emits when the roster or avatars change. */
    private val _liveArtists = MutableStateFlow<List<Artist>>(emptyList())
    val liveArtists: StateFlow<List<Artist>> = _liveArtists.asStateFlow()

    @Volatile
    private var artistsLoaded = false

    @Volatile
    private var tracksByArtist: MutableMap<String, List<Track>> = mutableMapOf()

    /** Flat lookup of every track that has been fetched so far this session, by [Track.id]. */
    private val _allTracksById = MutableStateFlow<Map<String, Track>>(emptyMap())
    val allTracksById: StateFlow<Map<String, Track>> = _allTracksById.asStateFlow()

    /** Most-recent stream date per artist id, used for newest-first sorting. */
    private val _latestDateByArtist = MutableStateFlow<Map<String, Long>>(emptyMap())
    val latestDateByArtist: StateFlow<Map<String, Long>> = _latestDateByArtist.asStateFlow()

    @Volatile
    private var datesLoaded = false

    suspend fun loadArtists(forceRefresh: Boolean = false) {
        if (!forceRefresh && artistsLoaded && _artists.value.isNotEmpty()) return
        val fresh = scraper.fetchArtists().withCachedAvatars()
        _artists.value = fresh
        artistsLoaded = true
        // Seed newest-first ordering from the last persisted dates so Browse is sorted
        // immediately on launch, before the background refresh completes.
        if (_latestDateByArtist.value.isEmpty() && datesCache.isNotEmpty()) {
            _latestDateByArtist.value = datesCache.filterKeys { it in fresh.map { a -> a.id } }
        }
    }

    /**
     * Fetch the most recent stream date for every artist, so the Browse grid can be
     * sorted newest-first. Populated lazily once; cheap enough to run in the background.
     */
    suspend fun loadLatestDates(forceRefresh: Boolean = false) {
        if (!forceRefresh && datesLoaded) return
        val dates = mutableMapOf<String, Long>()
        _artists.value.forEach { artist ->
            runCatching { tracks(artist) }.getOrNull()?.maxOfOrNull { it.dateMillis }
                ?.let { dates[artist.id] = it }
        }
        _latestDateByArtist.value = dates
        datesCache.clear()
        datesCache.putAll(dates)
        saveDatesCache()
        datesLoaded = true
    }

    /**
     * Fetch which DJs are live right now. Resolves each live username to a DJ entry
     * (including those without an archive folder) and refreshes their Twitch avatars.
     * The roster is emitted immediately with any cached avatars, then avatars for the
     * live DJs are fetched in parallel and the roster re-emitted as they arrive.
     */
    suspend fun loadLive(forceRefresh: Boolean = false) {
        if (!forceRefresh && _liveUsernames.value.isNotEmpty()) return
        val usernames = scraper.fetchLiveUsernames().ifEmpty { _liveUsernames.value }
        _liveUsernames.value = usernames
        refreshLiveArtists()

        // Fetch avatars for live DJs that don't have a fresh (>24h) cached one, in
        // parallel. TTL keeps decapi load low across repeated tab openings.
        val now = System.currentTimeMillis()
        coroutineScope {
            usernames
                .filter { uname -> now - (avatarCacheTimes[uname.lowercase()] ?: 0L) >= AVATAR_TTL_MILLIS }
                .map { uname ->
                    launch {
                        val lower = uname.lowercase()
                        val avatar = scraper.fetchTwitchAvatar(uname)
                        if (avatar != null) {
                            avatarCache[lower] = avatar
                            avatarCacheTimes[lower] = System.currentTimeMillis()
                        }
                    }
                }
                .joinAll()
        }
        refreshLiveArtists()
        saveAvatarCache()
        saveAvatarCacheTimes()
    }

    private fun refreshLiveArtists() {
        val artistsById = _artists.value.associateBy { it.id.lowercase() }
        _liveArtists.value = _liveUsernames.value.map { uname ->
            val lower = uname.lowercase()
            artistsById[lower] ?: Artist(
                id = lower,
                displayName = prettify(uname),
                thumbnailUrl = null,
                twitchAvatarUrl = avatarCache[lower],
            )
        }
    }

    /** Playable HQ restream URL for a live DJ's Twitch channel. */
    fun liveStreamUrl(username: String): String = scraper.liveStreamUrl(username)

    /**
     * Refresh Twitch avatars once per day (24h TTL). Artists with a fresh cached avatar
     * are left untouched; the rest are fetched in parallel (bounded) and the roster is
     * re-emitted with avatars applied. The TTL caps decapi load: full refresh only runs
     * every 24h per artist, so repeated launches are near-instant.
     */
    suspend fun refreshAvatars(forceRefresh: Boolean = false) {
        val current = _artists.value
        if (current.isEmpty()) return
        val now = System.currentTimeMillis()
        val staleness = AVATAR_TTL_MILLIS
        val stale = current.filter { artist ->
            forceRefresh || artist.twitchAvatarUrl == null || now - (avatarCacheTimes[artist.id] ?: 0L) >= staleness
        }
        if (stale.isEmpty()) return

        val resolved = coroutineScope {
            stale.map { artist -> async { artist to runCatching { scraper.fetchTwitchAvatar(artist.id) }.getOrNull() } }
                .awaitAll()
                .mapNotNull { (artist, avatar) ->
                    if (avatar != null) {
                        avatarCache[artist.id] = avatar
                        avatarCacheTimes[artist.id] = System.currentTimeMillis()
                        artist to avatar
                    } else {
                        null
                    }
                }
        }
        if (resolved.isNotEmpty()) {
            val byId = resolved.toMap()
            _artists.value = _artists.value.map { byId[it]?.let { av -> it.copy(twitchAvatarUrl = av) } ?: it }
            refreshLiveArtists()
            saveAvatarCache()
            saveAvatarCacheTimes()
        }
    }

    suspend fun tracks(artist: Artist): List<Track> {
        tracksByArtist[artist.id]?.let { return it }
        val fresh = scraper.fetchTracks(artist)
        tracksByArtist[artist.id] = fresh
        _allTracksById.value = _allTracksById.value + fresh.associateBy { it.id }
        return fresh
    }

    /**
     * Resolve a single track by id, loading its owning artist's track list if needed.
     * The artist id is embedded as the prefix of the track filename
     * (e.g. "dj_td_2026-09-02-04-00.mp4" -> artist "dj_td"), so we can fetch just that
     * one artist's tracks instead of scanning the whole archive.
     *
     * Used by the Android Auto media-browser service to resolve favorite tracks /
     * voice-search results without needing the mobile UI to have visited the artist first.
     */
    suspend fun trackById(trackId: String): Track? {
        _allTracksById.value[trackId]?.let { return it }
        val artistId = deriveArtistId(trackId) ?: return null
        val artist = _artists.value.find { it.id == artistId } ?: Artist(artistId, prettify(artistId), null, null)
        return runCatching { tracks(artist) }.getOrNull()?.find { it.id == trackId }
    }

    /** Resolve favorite track ids into full [Track] objects, fetching per-artist as needed. */
    suspend fun favoriteTrackObjects(ids: Set<String>): List<Track> =
        ids.mapNotNull { runCatching { trackById(it) }.getOrNull() }
            .sortedByDescending { it.dateMillis }

    /** Search across all tracks. If artists are not cached, fetch them first. */
    suspend fun search(query: String): List<Track> {
        val q = query.trim().lowercase()
        if (q.isBlank()) return emptyList()
        val artists = artists.value.takeIf { it.isNotEmpty() } ?: scraper.fetchArtists()
        val result = mutableListOf<Track>()
        artists.forEach { artist ->
            val artistMatches = artist.displayName.lowercase().contains(q)
            val tracks = tracks(artist)
            result += if (artistMatches) {
                tracks
            } else {
                tracks.filter { it.title.lowercase().contains(q) }
            }
        }
        return result.sortedByDescending { it.dateMillis }
    }

    val favoriteTracks: Flow<Set<String>> = favoritesDataStore.favoriteTracks
    val favoriteArtists: Flow<Set<String>> = favoritesDataStore.favoriteArtists
    val recentSearches: Flow<List<String>> = searchHistoryDataStore.recentQueries

    suspend fun toggleFavoriteTrack(track: Track) = favoritesDataStore.toggleTrack(track)
    suspend fun toggleFavoriteArtist(artist: Artist) = favoritesDataStore.toggleArtist(artist)
    suspend fun recordSearch(query: String) = searchHistoryDataStore.record(query)
    suspend fun clearSearchHistory() = searchHistoryDataStore.clear()

    private fun List<Artist>.withCachedAvatars(): List<Artist> = map { artist ->
        avatarCache[artist.id]?.let { artist.copy(twitchAvatarUrl = it) } ?: artist
    }

    private fun loadAvatarCache(): Map<String, String> {
        if (!avatarCacheFile.exists()) return emptyMap()
        return try {
            val json = JSONObject(avatarCacheFile.readText())
            json.keys().asSequence().associateWith { json.getString(it) }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun loadAvatarCacheTimes(): Map<String, Long> {
        if (!avatarCacheTimesFile.exists()) return emptyMap()
        return try {
            val json = JSONObject(avatarCacheTimesFile.readText())
            json.keys().asSequence()
                .associateWith { json.optLong(it) }
                .filterValues { it > 0L }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun saveAvatarCacheTimes() {
        try {
            val json = JSONObject()
            avatarCacheTimes.forEach { (k, v) -> json.put(k, v) }
            avatarCacheTimesFile.writeText(json.toString())
        } catch (_: Exception) {
            // ignore cache write failures
        }
    }

    private fun loadDatesCache(): Map<String, Long> {
        if (!datesCacheFile.exists()) return emptyMap()
        return try {
            val json = JSONObject(datesCacheFile.readText())
            json.keys().asSequence()
                .associateWith { json.optLong(it) }
                .filterValues { it > 0L }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun saveDatesCache() {
        try {
            val json = JSONObject()
            datesCache.forEach { (k, v) -> json.put(k, v) }
            datesCacheFile.writeText(json.toString())
        } catch (_: Exception) {
            // ignore cache write failures
        }
    }

    private fun saveAvatarCache() {
        try {
            val json = JSONObject()
            avatarCache.forEach { (k, v) -> json.put(k, v) }
            avatarCacheFile.writeText(json.toString())
        } catch (_: Exception) {
            // ignore cache write failures
        }
    }

    private fun deriveArtistId(trackId: String): String? {
        val m = timestampRe.matcher(trackId)
        if (!m.find()) return null
        return trackId.substring(0, m.start()).removeSuffix("_").ifBlank { null }
    }

    private fun prettify(raw: String): String =
        raw.replace('_', ' ').trim().replace(Regex("\\s+"), " ")

    companion object {
        /** Twitch avatars refresh at most once per 24h per artist. */
        const val AVATAR_TTL_MILLIS: Long = 24L * 60L * 60L * 1000L
        private val timestampRe: Pattern = Pattern.compile("(\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2})")
    }
}

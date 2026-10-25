package com.starchive.player.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val _artists = MutableStateFlow<List<Artist>>(emptyList())
    val artists: StateFlow<List<Artist>> = _artists.asStateFlow()

    /** Twitch usernames currently live on live.streambox.space (empty until first fetch). */
    private val _liveUsernames = MutableStateFlow<List<String>>(emptyList())
    val liveUsernames: StateFlow<List<String>> = _liveUsernames.asStateFlow()

    @Volatile
    private var artistsLoaded = false

    @Volatile
    private var tracksByArtist: MutableMap<String, List<Track>> = mutableMapOf()

    /** Flat lookup of every track that has been fetched so far this session, by [Track.id]. */
    private val _allTracksById = MutableStateFlow<Map<String, Track>>(emptyMap())
    val allTracksById: StateFlow<Map<String, Track>> = _allTracksById.asStateFlow()

    suspend fun loadArtists(forceRefresh: Boolean = false) {
        if (!forceRefresh && artistsLoaded && _artists.value.isNotEmpty()) return
        val fresh = scraper.fetchArtists().withCachedAvatars()
        _artists.value = fresh
        artistsLoaded = true
    }

    /**
     * Fetch which DJs are live right now. Keeps the raw username list; the UI maps
     * these onto artists (matching by id, which equals the Twitch username).
     */
    suspend fun loadLive(forceRefresh: Boolean = false) {
        if (!forceRefresh && _liveUsernames.value.isNotEmpty()) return
        _liveUsernames.value = scraper.fetchLiveUsernames()
    }

    /** Live DJs that also exist in the artist list, with their current avatar. */
    fun liveArtists(): List<Artist> {
        val byName = _artists.value.associateBy { it.id.lowercase() }
        return _liveUsernames.value
            .mapNotNull { byName[it.lowercase()] }
    }

    /**
     * Refresh Twitch avatars in the background. The decapi.me endpoint is rate-limited
     * to ~100 req/min; we throttle requests to stay well below that and update artists
     * as avatars become available.
     */
    suspend fun refreshAvatars() {
        val current = _artists.value
        if (current.isEmpty()) return
        val updated = current.toMutableList()
        var changed = false
        current.forEachIndexed { index, artist ->
            if (artist.twitchAvatarUrl != null) return@forEachIndexed
            val avatar = scraper.fetchTwitchAvatar(artist.id)
            if (avatar != null) {
                avatarCache[artist.id] = avatar
                updated[index] = artist.copy(twitchAvatarUrl = avatar)
                changed = true
            }
            // Throttle to ~45 req/min to stay safe across reloads.
            delay(1_350L)
        }
        if (changed) {
            _artists.value = updated
            saveAvatarCache()
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
        private val timestampRe: Pattern = Pattern.compile("(\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2})")
    }
}

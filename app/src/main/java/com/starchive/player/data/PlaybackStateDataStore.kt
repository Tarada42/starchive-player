package com.starchive.player.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.playbackStore: DataStore<Preferences> by preferencesDataStore(name = "starchive_playback")

/**
 * Persists playback resume state so the app can start where the user left off:
 *  - the last track + its position (global resume),
 *  - the last position *per artist* (so opening a favourite DJ resumes that DJ's
 *    last track/position),
 *  - Single Artist mode (the app's first page shows only one artist until exited).
 *
 * Per-artist positions are stored as a JSON object: {"<artistId>": "trackId|positionMs"}.
 */
class PlaybackStateDataStore(private val context: Context) {

    private val lastTrackKey = stringPreferencesKey("last_track_id")
    private val lastArtistKey = stringPreferencesKey("last_artist_id")
    private val lastPositionKey = longPreferencesKey("last_position_ms")
    private val singleArtistKey = stringPreferencesKey("single_artist_id")
    private val artistPositionsKey = stringPreferencesKey("artist_positions")

    val lastTrackId: Flow<String?> = context.playbackStore.data.map { it[lastTrackKey] }
    val lastArtistId: Flow<String?> = context.playbackStore.data.map { it[lastArtistKey] }
    val lastPositionMs: Flow<Long> = context.playbackStore.data.map { it[lastPositionKey] ?: 0L }
    val singleArtistId: Flow<String?> = context.playbackStore.data.map { it[singleArtistKey] }
    val artistPositions: Flow<Map<String, Pair<String, Long>>> =
        context.playbackStore.data.map { decodePositions(it[artistPositionsKey]) }

    /** Save the currently-playing track + position, and remember it for that artist too. */
    suspend fun saveLast(trackId: String, artistId: String, positionMs: Long) {
        context.playbackStore.edit { prefs ->
            prefs[lastTrackKey] = trackId
            prefs[lastArtistKey] = artistId
            prefs[lastPositionKey] = positionMs.coerceAtLeast(0L)
            val positions = decodePositions(prefs[artistPositionsKey])
            positions[artistId] = trackId to positionMs.coerceAtLeast(0L)
            prefs[artistPositionsKey] = encodePositions(positions)
        }
    }

    /** Clear global resume (e.g. user hit Stop). Keeps per-artist history for later. */
    suspend fun clearLast() {
        context.playbackStore.edit { prefs ->
            prefs.remove(lastTrackKey)
            prefs.remove(lastArtistKey)
            prefs.remove(lastPositionKey)
        }
    }

    suspend fun setSingleArtist(artistId: String?) {
        context.playbackStore.edit { prefs ->
            if (artistId == null) prefs.remove(singleArtistKey) else prefs[singleArtistKey] = artistId
        }
    }

    private fun decodePositions(raw: String?): MutableMap<String, Pair<String, Long>> {
        if (raw.isNullOrBlank()) return mutableMapOf()
        return try {
            val obj = org.json.JSONObject(raw)
            obj.keys().asSequence().associateTo(mutableMapOf()) { k ->
                val v = obj.getString(k)
                val sep = v.lastIndexOf('|')
                val trackId = v.substring(0, sep.coerceAtLeast(0))
                val pos = v.substring(sep + 1).toLongOrNull() ?: 0L
                k to (trackId to pos)
            }
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    private fun encodePositions(positions: Map<String, Pair<String, Long>>): String {
        val obj = org.json.JSONObject()
        positions.forEach { (artist, pair) -> obj.put(artist, "${pair.first}|${pair.second}") }
        return obj.toString()
    }
}

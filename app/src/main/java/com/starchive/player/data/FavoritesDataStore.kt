package com.starchive.player.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "starchive_prefs")

/** Simple favorites store for both track ids and artist ids. */
class FavoritesDataStore(private val context: Context) {

    private val trackKey = stringSetPreferencesKey("favorite_tracks")
    private val artistKey = stringSetPreferencesKey("favorite_artists")

    val favoriteTracks: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[trackKey] ?: emptySet()
    }

    val favoriteArtists: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[artistKey] ?: emptySet()
    }

    suspend fun toggleTrack(track: Track) {
        context.dataStore.edit { prefs ->
            val current = prefs[trackKey] ?: emptySet()
            prefs[trackKey] = if (track.id in current) current - track.id else current + track.id
        }
    }

    suspend fun toggleArtist(artist: Artist) {
        context.dataStore.edit { prefs ->
            val current = prefs[artistKey] ?: emptySet()
            prefs[artistKey] = if (artist.id in current) current - artist.id else current + artist.id
        }
    }

    suspend fun addTrack(track: Track) = context.dataStore.edit { prefs ->
        val current = prefs[trackKey] ?: emptySet()
        prefs[trackKey] = current + track.id
    }

    suspend fun removeTrack(track: Track) = context.dataStore.edit { prefs ->
        val current = prefs[trackKey] ?: emptySet()
        prefs[trackKey] = current - track.id
    }
}

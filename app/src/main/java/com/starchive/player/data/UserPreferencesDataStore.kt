package com.starchive.player.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.userPreferencesStore: DataStore<Preferences> by preferencesDataStore(name = "starchive_user_preferences")

enum class ArtistListLayout(val key: String) {
    GRID("grid"),
    LIST("list"),
}

/**
 * Persists small UI preferences that should survive rotation and relaunch.
 *
 * Currently holds the Browse artist-list layout (grid thumbnails vs. compact list).
 */
class UserPreferencesDataStore(private val context: Context) {

    private val artistLayoutKey = stringPreferencesKey("artist_list_layout")

    val artistListLayout: Flow<ArtistListLayout> = context.userPreferencesStore.data.map { prefs ->
        when (prefs[artistLayoutKey]) {
            ArtistListLayout.LIST.key -> ArtistListLayout.LIST
            else -> ArtistListLayout.GRID
        }
    }

    suspend fun setArtistListLayout(layout: ArtistListLayout) {
        context.userPreferencesStore.edit { prefs ->
            prefs[artistLayoutKey] = layout.key
        }
    }

    // --- Android Auto artist sort ("NAME" / "DATE" + direction) ---

    private val aaSortKey = stringPreferencesKey("aa_sort")
    private val aaSortAscendingKey = booleanPreferencesKey("aa_sort_ascending")

    /** AA artist sort preference; defaults to newest-first (DATE, descending). */
    val aaSort: Flow<Pair<String, Boolean>> = context.userPreferencesStore.data.map { prefs ->
        (prefs[aaSortKey] ?: "DATE") to (prefs[aaSortAscendingKey] ?: false)
    }

    suspend fun setAASort(sort: String, ascending: Boolean) {
        context.userPreferencesStore.edit { prefs ->
            prefs[aaSortKey] = sort
            prefs[aaSortAscendingKey] = ascending
        }
    }
}

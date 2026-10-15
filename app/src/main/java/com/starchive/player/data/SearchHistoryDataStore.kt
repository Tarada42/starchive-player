package com.starchive.player.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.searchHistoryStore: DataStore<Preferences> by preferencesDataStore(name = "starchive_search_history")

/**
 * Persists recent search queries (most-recent first, capped at [MAX_ENTRIES]).
 *
 * Used both by the on-device Search screen (quick re-run chips) and by the
 * Android Auto / MediaLibraryService browse tree, which exposes a "Search History"
 * root so a driver can re-browse or voice-replay a past search.
 */
class SearchHistoryDataStore(private val context: Context) {

    // Order matters for history, but DataStore only gives us a Set, so we encode
    // the rank as a zero-padded prefix: "00:query", "01:query", ...
    private val historyKey = stringSetPreferencesKey("search_history")

    val recentQueries: Flow<List<String>> = context.searchHistoryStore.data.map { prefs ->
        val raw = prefs[historyKey] ?: emptySet()
        raw.sortedBy { it.substringBefore(':') }
            .map { it.substringAfter(':') }
    }

    suspend fun record(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return
        context.searchHistoryStore.edit { prefs ->
            val current = (prefs[historyKey] ?: emptySet())
                .map { it.substringAfter(':') }
                .filterNot { it.equals(trimmed, ignoreCase = true) }
            val updated = (listOf(trimmed) + current).take(MAX_ENTRIES)
            prefs[historyKey] = updated.mapIndexed { index, q -> "%02d:%s".format(index, q) }.toSet()
        }
    }

    suspend fun clear() {
        context.searchHistoryStore.edit { prefs -> prefs[historyKey] = emptySet() }
    }

    companion object {
        private const val MAX_ENTRIES = 10
    }
}

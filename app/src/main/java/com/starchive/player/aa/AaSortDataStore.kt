package com.starchive.player.aa

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.aaSortStore: DataStore<Preferences> by preferencesDataStore(name = "starchive_aa_sort")

/**
 * Persists the Android Auto artist-list sort mode and direction so they survive AA reconnects
 * and app restarts. Kept inside the .aa package to avoid coupling phone UI preferences to AA.
 */
class AaSortDataStore(context: Context) {

    private val appContext = context.applicationContext
    private val sortModeKey = stringPreferencesKey("aa_sort_mode")
    private val ascendingKey = booleanPreferencesKey("aa_sort_ascending")

    val sortMode: Flow<SortMode> = appContext.aaSortStore.data.map { prefs ->
        when (prefs[sortModeKey]) {
            SortMode.NAME.name -> SortMode.NAME
            else -> SortMode.DATE
        }
    }

    val ascending: Flow<Boolean> = appContext.aaSortStore.data.map { prefs ->
        prefs[ascendingKey] ?: false
    }

    suspend fun save(mode: SortMode, ascending: Boolean) {
        appContext.aaSortStore.edit { prefs ->
            prefs[sortModeKey] = mode.name
            prefs[ascendingKey] = ascending
        }
    }
}

package com.starchive.player.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "starchive_settings")

class SettingsDataStore(private val context: Context) {

    private val darkModeKey = booleanPreferencesKey("dark_mode")

    val isDark: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[darkModeKey] ?: true
    }

    suspend fun toggleDarkMode() {
        context.dataStore.edit { prefs ->
            val current = prefs[darkModeKey] ?: true
            prefs[darkModeKey] = !current
        }
    }

    suspend fun setDarkMode(value: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[darkModeKey] = value
        }
    }
}

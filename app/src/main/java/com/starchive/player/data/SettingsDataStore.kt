package com.starchive.player.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "starchive_settings")

class SettingsDataStore(private val context: Context) {

    private val darkModeKey = booleanPreferencesKey("dark_mode")
    private val liveNotificationsKey = booleanPreferencesKey("live_notifications")
    private val lastNotifiedLiveArtistsKey = stringSetPreferencesKey("last_notified_live_artists")
    // Empty string = never asked, "on" / "off" = the user's answer.
    private val aaResumeChoiceKey = stringPreferencesKey("aa_resume_choice")
    private val aaCarSeenKey = booleanPreferencesKey("aa_car_seen")

    val isDark: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[darkModeKey] ?: true
    }

    /** Whether the user wants "favorite DJ is live" notifications. Defaults to on. */
    val liveNotifications: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[liveNotificationsKey] ?: true
    }

    /** Last usernames we posted a live notification for; used to avoid spam while a DJ stays live. */
    val lastNotifiedLiveArtists: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[lastNotifiedLiveArtistsKey] ?: emptySet()
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

    suspend fun setLiveNotifications(value: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[liveNotificationsKey] = value
        }
    }

    /** "" until the user answers the first-run question, then "on" or "off". */
    val aaResumeChoice: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[aaResumeChoiceKey] ?: ""
    }

    /** True once the app has actually seen an Android Auto connection. */
    val aaCarSeen: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[aaCarSeenKey] ?: false
    }

    suspend fun setAaResumeChoice(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[aaResumeChoiceKey] = if (enabled) "on" else "off"
        }
    }

    suspend fun markCarSeen() {
        context.dataStore.edit { prefs ->
            prefs[aaCarSeenKey] = true
        }
    }

    suspend fun setLastNotifiedLiveArtists(usernames: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[lastNotifiedLiveArtistsKey] = usernames
        }
    }
}

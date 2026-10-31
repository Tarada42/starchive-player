package com.starchive.player.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.starchive.player.data.SettingsDataStore
import com.starchive.player.notifications.LiveNotificationsScheduler
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel backing the Settings screen. Handles the "Live notifications" toggle:
 * persists the choice, schedules/cancels WorkManager, and respects runtime
 * permission state from the UI.
 */
class SettingsViewModel(context: Context) : ViewModel() {

    private val appContext = context.applicationContext
    private val settings = SettingsDataStore(appContext)

    val liveNotifications: StateFlow<Boolean> = settings.liveNotifications
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    /** Persist the toggle and (un)schedule the background worker accordingly. */
    fun setLiveNotifications(enabled: Boolean) {
        viewModelScope.launch {
            settings.setLiveNotifications(enabled)
            if (enabled) {
                LiveNotificationsScheduler.schedule(appContext)
            } else {
                LiveNotificationsScheduler.cancel(appContext)
            }
        }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == SettingsViewModel::class.java)
            return SettingsViewModel(context) as T
        }
    }
}

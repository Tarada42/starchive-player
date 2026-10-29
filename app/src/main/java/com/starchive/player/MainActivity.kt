package com.starchive.player

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.starchive.player.data.SettingsDataStore
import com.starchive.player.ui.AppNav
import com.starchive.player.ui.theme.StArchiveTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    /** Intent extra: opened by tapping the media notification body — route to Player. */
    companion object {
        const val EXTRA_OPEN_PLAYER = "com.starchive.player.OPEN_PLAYER"
    }

    /**
     * Increments every time an "open player" request arrives, whether via [onCreate]
     * (cold start) or [onNewIntent] (app already alive). The UI observes this to
     * navigate to the now-playing track regardless of how the activity was reached.
     */
    private val _openPlayerRequests = MutableStateFlow(0)
    val openPlayerRequests = _openPlayerRequests.asStateFlow()

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* granted or not — nothing to do */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val settings = SettingsDataStore(this)

        setContent {
            val isDark by settings.isDark.collectAsStateWithLifecycle(initialValue = true)

            StArchiveTheme(darkTheme = isDark) {
                AppNav(
                    isDark = isDark,
                    onToggleTheme = {
                        lifecycleScope.launch { settings.toggleDarkMode() }
                    },
                )
            }
        }

        // Ask for the notification permission on Android 13+ so the playback controls
        // show in the notification shade (not just on the lock screen) when the app
        // plays in the background.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        handleOpenPlayerIntent(intent)
    }

    /**
     * A live activity is reused (FLAG_ACTIVITY_SINGLE_TOP), so a notification tap
     * arrives here instead of [onCreate]. Forward the fresh intent and signal the UI.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpenPlayerIntent(intent)
    }

    private fun handleOpenPlayerIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) == true) {
            _openPlayerRequests.value += 1
            // Consume the flag so a later recreation doesn't double-handle it.
            intent.removeExtra(EXTRA_OPEN_PLAYER)
        }
    }
}

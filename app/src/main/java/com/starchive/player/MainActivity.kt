package com.starchive.player

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.starchive.player.data.SettingsDataStore
import com.starchive.player.ui.AppNav
import com.starchive.player.ui.theme.StArchiveTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

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
    }
}

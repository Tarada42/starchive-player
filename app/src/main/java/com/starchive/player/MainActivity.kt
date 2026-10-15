package com.starchive.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.starchive.player.data.SettingsDataStore
import com.starchive.player.ui.AppNav
import com.starchive.player.ui.theme.StArchiveTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
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
    }
}

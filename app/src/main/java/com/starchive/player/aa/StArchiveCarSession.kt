package com.starchive.player.aa

import android.content.Intent
import androidx.car.app.Session
import androidx.car.app.Screen
import com.starchive.player.StArchiveApp
import com.starchive.player.data.Repository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** CAL session for STArchive. Holds the shared scope and AA helpers used by all screens. */
class StArchiveCarSession : Session() {

    private val sessionScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val repository: Repository by lazy {
        (carContext.applicationContext as StArchiveApp).repository
    }

    private val imageCache: AaImageCache by lazy {
        AaImageCache(carContext, sessionScope)
    }

    private val playbackClient: AaPlaybackClient by lazy {
        AaPlaybackClient(carContext, sessionScope)
    }

    override fun onCreateScreen(intent: Intent): Screen {
        playbackClient.connect()
        return HomeScreen(carContext, repository, imageCache, playbackClient)
    }
}

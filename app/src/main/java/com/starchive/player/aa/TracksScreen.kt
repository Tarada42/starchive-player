package com.starchive.player.aa

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import com.starchive.player.data.Artist
import com.starchive.player.data.Repository
import com.starchive.player.data.Track
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Single-level track list for an artist. Tapping a track starts playback via the shared MediaSession. */
class TracksScreen(
    carContext: CarContext,
    private val repository: Repository,
    private val playbackClient: AaPlaybackClient,
    private val artist: Artist,
) : Screen(carContext) {

    private var tracks: List<Track> = emptyList()
    private var loaded = false

    private val screenScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                screenScope.cancel()
            }
        })
        screenScope.launch {
            tracks = repository.tracks(artist)
            loaded = true
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        if (!loaded) {
            return ListTemplate.Builder()
                .setTitle(artist.displayName)
                .setLoading(true)
                .build()
        }

        val listBuilder = ItemList.Builder()
        tracks.forEach { track ->
            listBuilder.addItem(
                Row.Builder()
                    .setTitle(track.title)
                    .setOnClickListener { playbackClient.play(track) }
                    .build()
            )
        }

        return ListTemplate.Builder()
            .setTitle(artist.displayName)
            .setHeaderAction(Action.BACK)
            .setSingleList(listBuilder.build())
            .build()
    }
}

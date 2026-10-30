package com.starchive.player.aa

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.starchive.player.R
import com.starchive.player.data.Repository
import com.starchive.player.data.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Flat list of favorite tracks (most-recent first). Tapping a track plays it through the
 * shared MediaSession and opens the player screen.
 */
class FavoriteTracksScreen(
    carContext: CarContext,
    private val repository: Repository,
    private val playbackClient: AaPlaybackClient,
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
            tracks = withContext(Dispatchers.IO) {
                val ids = repository.favoriteTracks.first()
                if (ids.isEmpty()) emptyList() else repository.favoriteTrackObjects(ids)
            }
            loaded = true
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        if (!loaded) {
            return ListTemplate.Builder()
                .setHeader(
                    Header.Builder()
                        .setTitle(carContext.getString(R.string.aa_favorite_tracks))
                        .setStartHeaderAction(Action.BACK)
                        .build()
                )
                .setLoading(true)
                .build()
        }

        if (tracks.isEmpty()) {
            val list = ItemList.Builder()
                .addItem(
                    Row.Builder()
                        .setTitle(carContext.getString(R.string.aa_favorite_tracks_empty))
                        .build()
                )
                .build()
            return ListTemplate.Builder()
                .setHeader(
                    Header.Builder()
                        .setTitle(carContext.getString(R.string.aa_favorite_tracks))
                        .setStartHeaderAction(Action.BACK)
                        .build()
                )
                .setSingleList(list)
                .build()
        }

        val listBuilder = ItemList.Builder()
        tracks.forEach { track ->
            listBuilder.addItem(
                Row.Builder()
                    .setTitle(track.title)
                    .setOnClickListener {
                        playbackClient.play(track)
                        screenManager.push(PlayerScreen(carContext, playbackClient))
                    }
                    .build()
            )
        }

        return ListTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setTitle(carContext.getString(R.string.aa_favorite_tracks))
                    .setStartHeaderAction(Action.BACK)
                    .build()
            )
            .setSingleList(listBuilder.build())
            .build()
    }
}

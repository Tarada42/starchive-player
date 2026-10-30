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
import com.starchive.player.data.Artist
import com.starchive.player.data.Repository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Flat list of currently-live DJs. Tapping a DJ opens the quality picker. */
class LiveScreen(
    carContext: CarContext,
    private val repository: Repository,
    private val imageCache: AaImageCache,
    private val playbackClient: AaPlaybackClient,
) : Screen(carContext) {

    private var liveArtists: List<Artist> = emptyList()
    private var loaded = false

    private val screenScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                screenScope.cancel()
            }
        })
        screenScope.launch {
            // Network I/O (live.streambox.space + optional avatar fetch) must run off Main.
            withContext(Dispatchers.IO) { repository.loadLive() }
            liveArtists = repository.liveArtists.value
            imageCache.preload(
                liveArtists.mapNotNull { artist ->
                    artist.bestImageUrl?.let { url -> AaImageRequest(artist.id, url) }
                }
            ) { invalidate() }
            loaded = true
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        if (!loaded) {
            return ListTemplate.Builder()
                .setHeader(
                    Header.Builder()
                        .setTitle(carContext.getString(R.string.aa_live_now))
                        .build()
                )
                .setLoading(true)
                .build()
        }

        if (liveArtists.isEmpty()) {
            val list = ItemList.Builder()
                .addItem(
                    Row.Builder()
                        .setTitle(carContext.getString(R.string.aa_live_empty))
                        .build()
                )
                .build()
            return ListTemplate.Builder()
                .setHeader(
                    Header.Builder()
                        .setTitle(carContext.getString(R.string.aa_live_now))
                        .setStartHeaderAction(Action.BACK)
                        .build()
                )
                .setSingleList(list)
                .build()
        }

        val listBuilder = ItemList.Builder()
        liveArtists.forEach { artist ->
            val icon = imageCache.iconFor(artist.id) ?: imageCache.placeholderIcon()
            listBuilder.addItem(
                Row.Builder()
                    .setTitle(artist.displayName)
                    .setImage(icon, Row.IMAGE_TYPE_LARGE)
                    .setOnClickListener {
                        screenManager.push(
                            LiveQualityScreen(carContext, repository, playbackClient, artist)
                        )
                    }
                    .build()
            )
        }

        return ListTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setTitle(carContext.getString(R.string.aa_live_now))
                    .setStartHeaderAction(Action.BACK)
                    .build()
            )
            .setSingleList(listBuilder.build())
            .build()
    }
}

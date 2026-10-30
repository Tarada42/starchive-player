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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Flat list of favorite artists. Tapping an artist opens their single-level track list.
 * Artists are sorted alphabetically for a stable, scannable list.
 */
class FavoriteArtistsScreen(
    carContext: CarContext,
    private val repository: Repository,
    private val imageCache: AaImageCache,
    private val playbackClient: AaPlaybackClient,
) : Screen(carContext) {

    private var artists: List<Artist> = emptyList()
    private var loaded = false

    private val screenScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                screenScope.cancel()
            }
        })
        screenScope.launch {
            val favoriteIds = withContext(Dispatchers.IO) {
                if (repository.artists.value.isEmpty()) repository.loadArtists()
                repository.favoriteArtists.first()
            }
            val all = withContext(Dispatchers.IO) {
                // Ensure the artist roster is loaded before resolving ids.
                if (repository.artists.value.isEmpty()) repository.loadArtists()
                repository.artists.value
            }
            artists = all
                .filter { it.id in favoriteIds }
                .sortedBy { it.displayName.lowercase() }
            imageCache.preload(
                artists.mapNotNull { artist ->
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
                        .setTitle(carContext.getString(R.string.aa_favorite_artists))
                        .setStartHeaderAction(Action.BACK)
                        .build()
                )
                .setLoading(true)
                .build()
        }

        if (artists.isEmpty()) {
            val list = ItemList.Builder()
                .addItem(
                    Row.Builder()
                        .setTitle(carContext.getString(R.string.aa_favorite_artists_empty))
                        .build()
                )
                .build()
            return ListTemplate.Builder()
                .setHeader(
                    Header.Builder()
                        .setTitle(carContext.getString(R.string.aa_favorite_artists))
                        .setStartHeaderAction(Action.BACK)
                        .build()
                )
                .setSingleList(list)
                .build()
        }

        val listBuilder = ItemList.Builder()
        artists.forEach { artist ->
            val icon = imageCache.iconFor(artist.id) ?: imageCache.placeholderIcon()
            listBuilder.addItem(
                Row.Builder()
                    .setTitle(artist.displayName)
                    .setImage(icon, Row.IMAGE_TYPE_LARGE)
                    .setOnClickListener {
                        screenManager.push(
                            TracksScreen(carContext, repository, playbackClient, artist)
                        )
                    }
                    .build()
            )
        }

        return ListTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setTitle(carContext.getString(R.string.aa_favorite_artists))
                    .setStartHeaderAction(Action.BACK)
                    .build()
            )
            .setSingleList(listBuilder.build())
            .build()
    }
}

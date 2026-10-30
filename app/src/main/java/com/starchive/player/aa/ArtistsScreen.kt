package com.starchive.player.aa

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import com.starchive.player.R
import com.starchive.player.data.Artist
import com.starchive.player.data.Repository
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Flat, newest-first artist list with sort controls. Tapping an artist opens its tracks. */
class ArtistsScreen(
    carContext: CarContext,
    private val repository: Repository,
    private val imageCache: AaImageCache,
    private val playbackClient: AaPlaybackClient,
) : Screen(carContext) {

    private var artists: List<Artist> = emptyList()
    private var sortMode = SortMode.DATE
    private var ascending = false
    private var loaded = false

    private val screenScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                screenScope.cancel()
            }
        })
        screenScope.launch {
            repository.loadArtists()
            repository.loadLatestDates()
            artists = applySort(repository.artists.value)
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
                        .setTitle(carContext.getString(R.string.aa_artists))
                        .build()
                )
                .setLoading(true)
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

        val sortAction = Action.Builder()
            .setTitle(carContext.getString(sortMode.labelRes))
            .setOnClickListener {
                sortMode = sortMode.next()
                ascending = when (sortMode) {
                    SortMode.DATE -> false // newest first
                    SortMode.NAME -> true  // A-Z
                }
                artists = applySort(repository.artists.value)
                invalidate()
            }
            .build()

        val directionAction = Action.Builder()
            .setTitle(if (ascending) "▲" else "▼")
            .setOnClickListener {
                ascending = !ascending
                artists = applySort(repository.artists.value)
                invalidate()
            }
            .build()

        val header = Header.Builder()
            .setTitle(carContext.getString(R.string.aa_artists))
            .setStartHeaderAction(Action.BACK)
            .addEndHeaderAction(sortAction)
            .addEndHeaderAction(directionAction)
            .build()

        return ListTemplate.Builder()
            .setHeader(header)
            .setSingleList(listBuilder.build())
            .build()
    }

    private fun applySort(source: List<Artist>): List<Artist> {
        val base = when (sortMode) {
            SortMode.DATE -> source.sortedBy {
                // Prefer the always-populated folder date; fall back to the (lazily loaded)
                // per-artist latest track date map when it differs.
                it.sourceDateMillis.takeIf { d -> d != 0L }
                    ?: repository.latestDateByArtist.value[it.id]
                    ?: 0L
            }
            SortMode.NAME -> source.sortedBy { it.displayName.lowercase() }
        }
        return if (ascending) base else base.reversed()
    }
}

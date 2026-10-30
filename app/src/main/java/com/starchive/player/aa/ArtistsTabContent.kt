package com.starchive.player.aa

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.core.graphics.drawable.IconCompat
import androidx.car.app.model.Template
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
 * Artists tab content for the root TabTemplate.
 *
 * Returns a bare [ListTemplate] with no header (headers inside [androidx.car.app.model.TabContents]
 * are ignored by the host). Sort controls are rendered as the first two rows so the user can still
 * toggle Date/Name and direction. Tapping an artist pushes the single-level track list.
 */
class ArtistsTabContent(
    private val carContext: CarContext,
    private val repository: Repository,
    private val imageCache: AaImageCache,
    private val playbackClient: AaPlaybackClient,
    private val push: (Screen) -> Unit,
    private val onInvalidate: () -> Unit,
) {
    private var artists: List<Artist> = emptyList()
    private var sortMode = SortMode.DATE
    private var ascending = false
    private var loaded = false

    private val sortDataStore = AaSortDataStore(carContext)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        scope.launch {
            // Restore the last AA sort choice before loading the list so the user sees the
            // same ordering after an AA reconnect or app restart.
            sortMode = sortDataStore.sortMode.first()
            ascending = sortDataStore.ascending.first()

            // Repository I/O (scrape, per-artist track scans) must run off the Main dispatcher.
            // Doing it on Main froze the AA UI here, which delayed tap-to-play for ~a minute.
            withContext(Dispatchers.IO) {
                repository.loadArtists()
                repository.loadLatestDates()
            }
            artists = applySort(repository.artists.value)
            imageCache.preload(
                artists.mapNotNull { artist ->
                    artist.bestImageUrl?.let { url -> AaImageRequest(artist.id, url) }
                }
            ) { onInvalidate() }
            loaded = true
            onInvalidate()
        }
    }

    fun template(): Template {
        if (!loaded) {
            return ListTemplate.Builder()
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
                        push(TracksScreen(carContext, repository, playbackClient, artist))
                    }
                    .build()
            )
        }

        // Sort controls as template-scoped actions so the host renders them as BUTTONS.
        // IMPORTANT: inside a media-app (MEDIA_TEMPLATES) TabTemplate, template actions may NOT
        // carry a custom title ("Action list exceeded max number of 0 actions with custom
        // titles" crash). So these are ICON-ONLY: a required CarIcon + CarColor, no setTitle.
        val toggleSortModeAction = Action.Builder()
            .setIcon(icon(R.drawable.aa_ic_sort))
            .setBackgroundColor(CarColor.BLUE)
            .setOnClickListener {
                sortMode = sortMode.next()
                ascending = when (sortMode) {
                    SortMode.DATE -> false // newest first
                    SortMode.NAME -> true  // A-Z
                }
                scope.launch { sortDataStore.save(sortMode, ascending) }
                artists = applySort(repository.artists.value)
                onInvalidate()
            }
            .build()

        val toggleOrderAction = Action.Builder()
            .setIcon(icon(R.drawable.aa_ic_order))
            .setBackgroundColor(CarColor.BLUE)
            .setOnClickListener {
                ascending = !ascending
                scope.launch { sortDataStore.save(sortMode, ascending) }
                artists = applySort(repository.artists.value)
                onInvalidate()
            }
            .build()

        return ListTemplate.Builder()
            .setSingleList(listBuilder.build())
            .addAction(toggleSortModeAction)
            .addAction(toggleOrderAction)
            .build()
    }

    fun onDestroy() {
        scope.cancel()
    }

    /** Build a [CarIcon] from a vector drawable resource, as actions require one. */
    private fun icon(resId: Int): CarIcon =
        CarIcon.Builder(IconCompat.createWithResource(carContext, resId)).build()

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

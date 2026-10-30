package com.starchive.player.aa

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.annotations.RequiresCarApi
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridSection
import androidx.car.app.model.Row
import androidx.car.app.model.RowSection
import androidx.car.app.model.SectionedItemTemplate
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import com.starchive.player.R
import com.starchive.player.data.Artist
import com.starchive.player.data.ArtistListLayout
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
 * Returns a bare [SectionedItemTemplate] with no header (headers inside
 * [androidx.car.app.model.TabContents] are ignored by the host). The artists are rendered as a
 * phone-like grid of circular avatar images with their full names underneath. Sort controls are
 * rendered as icon-only template-scoped actions to comply with the media-app TabTemplate action
 * rules. Tapping an artist pushes the single-level track list.
 */
@RequiresCarApi(8)
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
    private var layout = ArtistListLayout.GRID
    private var loaded = false

    private val sortDataStore = AaSortDataStore(carContext)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        scope.launch {
            // Restore the last AA sort choice before loading the list so the user sees the
            // same ordering after an AA reconnect or app restart.
            sortMode = sortDataStore.sortMode.first()
            ascending = sortDataStore.ascending.first()
            layout = repository.artistListLayout.first()

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
            return SectionedItemTemplate.Builder()
                .setLoading(true)
                .build()
        }

        val builder = SectionedItemTemplate.Builder()

        if (artists.isEmpty()) {
            builder.addSection(createEmptySection())
        } else if (layout == ArtistListLayout.GRID) {
            builder.addSection(createGridSection())
        } else {
            builder.addSection(createListSection())
        }

        // Template-scoped actions as BUTTONS. Constraints inside a media-app (MEDIA_TEMPLATES)
        // TabTemplate: actions MUST be icon-only (no custom title — "max number of 0 actions
        // with custom titles" crash) AND at most 2 actions (ACTIONS_CONSTRAINTS_FAB max 2).
        // So our action budget is exactly 2: (1) a combined sort-mode + order cycle, (2) the
        // grid<->list layout toggle. Both are icon-only with a required CarIcon + CarColor.
        builder.addAction(
            Action.Builder()
                .setIcon(icon(R.drawable.aa_ic_sort))
                .setBackgroundColor(CarColor.BLUE)
                .setOnClickListener {
                    // Cycle: newest-first -> oldest-first -> A-Z -> Z-A -> ...
                    when {
                        sortMode == SortMode.DATE && !ascending -> ascending = true
                        sortMode == SortMode.DATE && ascending -> {
                            sortMode = SortMode.NAME; ascending = true
                        }
                        sortMode == SortMode.NAME && ascending -> ascending = false
                        else -> { // NAME descending -> back to DATE newest-first
                            sortMode = SortMode.DATE; ascending = false
                        }
                    }
                    scope.launch { sortDataStore.save(sortMode, ascending) }
                    artists = applySort(repository.artists.value)
                    onInvalidate()
                }
                .build()
        )

        builder.addAction(
            Action.Builder()
                .setIcon(icon(R.drawable.aa_ic_view_list))
                .setBackgroundColor(CarColor.BLUE)
                .setOnClickListener {
                    layout = if (layout == ArtistListLayout.GRID) ArtistListLayout.LIST else ArtistListLayout.GRID
                    scope.launch { repository.setArtistListLayout(layout) }
                    onInvalidate()
                }
                .build()
        )

        return builder.build()
    }

    fun onDestroy() {
        scope.cancel()
    }

    /** Build a list (row) section of artists, each with a small thumbnail + full name. */
    private fun createListSection(): RowSection {
        val rowBuilder = RowSection.Builder()
        artists.forEach { artist ->
            val icon = imageCache.iconFor(artist.id) ?: imageCache.placeholderIcon()
            rowBuilder.addItem(
                Row.Builder()
                    .setTitle(artist.displayName)
                    .setImage(icon, Row.IMAGE_TYPE_SMALL)
                    .setOnClickListener {
                        push(TracksScreen(carContext, repository, playbackClient, artist))
                    }
                    .build()
            )
        }
        return rowBuilder.build()
    }

    /** Build a single grid section containing all artist items. */
    private fun createGridSection(): GridSection {
        val gridBuilder = GridSection.Builder()
            .setItemSize(GridSection.ITEM_SIZE_MEDIUM)
            .setItemImageShape(GridSection.ITEM_IMAGE_SHAPE_CIRCLE)

        artists.forEach { artist ->
            val icon = imageCache.iconFor(artist.id) ?: imageCache.placeholderIcon()
            gridBuilder.addItem(
                GridItem.Builder()
                    .setTitle(artist.displayName)
                    .setImage(icon)
                    .setOnClickListener {
                        push(TracksScreen(carContext, repository, playbackClient, artist))
                    }
                    .build()
            )
        }

        return gridBuilder.build()
    }

    /** Fallback section shown when the artist list is empty. */
    private fun createEmptySection(): RowSection {
        return RowSection.Builder()
            .addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.aa_artists_empty))
                    .build()
            )
            .build()
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

package com.starchive.player.aa

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import com.starchive.player.R
import com.starchive.player.data.Artist
import com.starchive.player.data.Repository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Live tab content for the root TabTemplate.
 *
 * Returns a bare [ListTemplate] with no header (headers inside [androidx.car.app.model.TabContents]
 * are ignored by the host). Tapping a live DJ pushes the quality picker.
 */
class LiveTabContent(
    private val carContext: CarContext,
    private val repository: Repository,
    private val imageCache: AaImageCache,
    private val playbackClient: AaPlaybackClient,
    private val push: (Screen) -> Unit,
    private val onInvalidate: () -> Unit,
) {
    private var liveArtists: List<Artist> = emptyList()
    private var loaded = false

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        scope.launch {
            // Network I/O (live.streambox.space + optional avatar fetch) must run off Main.
            withContext(Dispatchers.IO) { repository.loadLive() }
            liveArtists = repository.liveArtists.value
            imageCache.preload(
                liveArtists.mapNotNull { artist ->
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

        if (liveArtists.isEmpty()) {
            val list = ItemList.Builder()
                .addItem(
                    Row.Builder()
                        .setTitle(carContext.getString(R.string.aa_live_empty))
                        .build()
                )
                .build()
            return ListTemplate.Builder()
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
                        push(LiveQualityScreen(carContext, repository, playbackClient, artist))
                    }
                    .build()
            )
        }

        return ListTemplate.Builder()
            .setSingleList(listBuilder.build())
            .build()
    }

    fun onDestroy() {
        scope.cancel()
    }
}

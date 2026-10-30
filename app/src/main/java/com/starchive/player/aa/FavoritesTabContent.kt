package com.starchive.player.aa

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import com.starchive.player.R
import com.starchive.player.data.Repository

/**
 * Favorites tab content for the root TabTemplate.
 *
 * Returns a bare [ListTemplate] with no header (headers inside [androidx.car.app.model.TabContents]
 * are ignored by the host). It surfaces the two favorite lists as sibling entries; tapping one
 * pushes the existing flat favorite screen.
 */
class FavoritesTabContent(
    private val carContext: CarContext,
    private val repository: Repository,
    private val imageCache: AaImageCache,
    private val playbackClient: AaPlaybackClient,
    private val push: (Screen) -> Unit,
) {
    fun template(): Template {
        val list = ItemList.Builder()
            .addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.aa_favorite_artists))
                    .setOnClickListener {
                        push(
                            FavoriteArtistsScreen(
                                carContext,
                                repository,
                                imageCache,
                                playbackClient
                            )
                        )
                    }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.aa_favorite_tracks))
                    .setOnClickListener {
                        push(FavoriteTracksScreen(carContext, repository, playbackClient))
                    }
                    .build()
            )
            .build()

        return ListTemplate.Builder()
            .setSingleList(list)
            .build()
    }
}

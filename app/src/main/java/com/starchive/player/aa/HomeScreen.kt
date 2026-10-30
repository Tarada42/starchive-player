package com.starchive.player.aa

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import com.starchive.player.R
import com.starchive.player.data.Repository

/** Root screen of the Android Auto experience. */
class HomeScreen(
    carContext: CarContext,
    private val repository: Repository,
    private val imageCache: AaImageCache,
    private val playbackClient: AaPlaybackClient,
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
            .addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.aa_artists))
                    .setOnClickListener {
                        screenManager.push(
                            ArtistsScreen(carContext, repository, imageCache, playbackClient)
                        )
                    }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.aa_favorite_artists))
                    .setOnClickListener {
                        screenManager.push(
                            FavoriteArtistsScreen(carContext, repository, imageCache, playbackClient)
                        )
                    }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.aa_favorite_tracks))
                    .setOnClickListener {
                        screenManager.push(
                            FavoriteTracksScreen(carContext, repository, playbackClient)
                        )
                    }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.aa_live_now))
                    .setOnClickListener {
                        screenManager.push(
                            LiveScreen(carContext, repository, imageCache, playbackClient)
                        )
                    }
                    .build()
            )
            .build()

        val header = Header.Builder()
            .setTitle(carContext.getString(R.string.aa_home_title))
            .build()

        return ListTemplate.Builder()
            .setHeader(header)
            .setSingleList(list)
            .build()
    }
}

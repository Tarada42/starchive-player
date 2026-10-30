package com.starchive.player.aa

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.media3.common.MimeTypes
import com.starchive.player.R
import com.starchive.player.data.Artist
import com.starchive.player.data.Repository

/**
 * Quality picker for a live DJ. Two flat options (High MP3 / Low Ogg); selecting one
 * plays the restream through the shared MediaSession and opens the player screen.
 */
class LiveQualityScreen(
    carContext: CarContext,
    private val repository: Repository,
    private val playbackClient: AaPlaybackClient,
    private val artist: Artist,
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
            .addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.aa_live_high))
                    .setOnClickListener {
                        playbackClient.playLive(
                            artist,
                            repository.liveStreamUrlHigh(artist.id),
                            MimeTypes.AUDIO_MPEG,
                            carContext.getString(R.string.aa_live_quality_high_label),
                        )
                        screenManager.push(PlayerScreen(carContext, playbackClient, isLive = true))
                    }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.aa_live_low))
                    .setOnClickListener {
                        playbackClient.playLive(
                            artist,
                            repository.liveStreamUrlLow(artist.id),
                            MimeTypes.AUDIO_OGG,
                            carContext.getString(R.string.aa_live_quality_low_label),
                        )
                        screenManager.push(PlayerScreen(carContext, playbackClient, isLive = true))
                    }
                    .build()
            )
            .build()

        val header = Header.Builder()
            .setTitle(
                carContext.getString(R.string.aa_live_quality_title, artist.displayName)
            )
            .setStartHeaderAction(Action.BACK)
            .build()

        return ListTemplate.Builder()
            .setHeader(header)
            .setSingleList(list)
            .build()
    }
}

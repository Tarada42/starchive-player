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

/**
 * Android Auto player controls.
 *
 * Rendered as a plain [ListTemplate] with explicit transport actions wired to the shared
 * PlaybackService MediaSession through [AaPlaybackClient]. This deliberately avoids the CAL
 * MediaPlaybackTemplate, whose host-side Now-Playing body requires a fully drivable
 * MediaBrowserService media controller (binding that repeatedly produced the host's
 * "Could not load your selection" timeout). Here every control is a normal template action,
 * so it works on any host with no token/binder coupling.
 */
class PlayerScreen(
    carContext: CarContext,
    private val playbackClient: AaPlaybackClient,
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
            .addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.aa_seek_back))
                    .setOnClickListener { playbackClient.seekBy(-10_000L) }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.aa_seek_forward))
                    .setOnClickListener { playbackClient.seekBy(10_000L) }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.aa_play_pause))
                    .setOnClickListener { playbackClient.togglePlayPause() }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.aa_stop))
                    .setOnClickListener { playbackClient.stop() }
                    .build()
            )
            .build()

        val header = Header.Builder()
            .setTitle(carContext.getString(R.string.aa_now_playing))
            .setStartHeaderAction(Action.BACK)
            .build()

        return ListTemplate.Builder()
            .setHeader(header)
            .setSingleList(list)
            .build()
    }
}

package com.starchive.player.aa

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.Template
import androidx.car.app.media.model.MediaPlaybackTemplate
import com.starchive.player.R

/**
 * Android Auto Now Playing screen.
 *
 * The playback controls (play/pause, 10 s seek, stop) are rendered by the AA host from the
 * registered shared MediaSession. A Stop header action is provided as a guaranteed,
 * always-visible entry point because the host's primary control surface varies by OEM.
 */
class PlayerScreen(
    carContext: CarContext,
    private val playbackClient: AaPlaybackClient,
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val stopAction = Action.Builder()
            .setTitle(carContext.getString(R.string.aa_stop))
            .setOnClickListener { playbackClient.stop() }
            .build()

        val header = Header.Builder()
            .setTitle(carContext.getString(R.string.aa_now_playing))
            .setStartHeaderAction(Action.BACK)
            .addEndHeaderAction(stopAction)
            .build()

        return MediaPlaybackTemplate.Builder()
            .setHeader(header)
            .build()
    }
}

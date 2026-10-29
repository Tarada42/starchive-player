package com.starchive.player.playback

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/**
 * Cast Framework options. Uses the DEFAULT media receiver so any Chromecast /
 * Cast-enabled device can play the audio stream URLs we hand it (no custom receiver
 * app needed).
 */
class CastOptionsProvider : OptionsProvider {

    override fun getCastOptions(context: Context): CastOptions {
        // IMPORTANT: Replace DEFAULT_MEDIA_RECEIVER_APPLICATION_ID with your own
        // Cast receiver app ID once you have registered the receiver at
        // https://cast.google.com/publish and deployed cast-receiver/index.html.
        // The default receiver cannot play Icecast live streams with ICY metadata.
        return CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

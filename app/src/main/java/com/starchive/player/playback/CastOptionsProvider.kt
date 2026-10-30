package com.starchive.player.playback

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/**
 * Cast Framework options. Uses the DEFAULT media receiver so any Chromecast /
 * Cast-enabled device can play the audio stream URLs we hand it (no custom receiver
 * app needed). The default receiver supports both Icecast MP3 and Ogg live mounts
 * as well as archive audio, so this configuration stays PHONE-ONLY and shared
 * with the regular phone UI.
 */
class CastOptionsProvider : OptionsProvider {

    override fun getCastOptions(context: Context): CastOptions {
        return CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

package com.starchive.player.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player

/**
 * Hides "skip to next / previous" while a live stream is the current item.
 *
 * A live mount is one endless stream, so skipping has no meaning. Removing the
 * commands from the per-controller result in [MediaLibrarySessionCallback.onConnect]
 * is not enough: Android Auto reads PlaybackStateCompat, which media3 builds from
 * the *player's* available commands, so the host kept drawing a next-track button.
 * Masking them here means the command set itself never contains them.
 *
 * Only live items are affected; archive playback is returned untouched.
 */
class LiveSkipMaskingPlayer(player: Player) : ForwardingPlayer(player) {

    override fun getAvailableCommands(): Player.Commands {
        val commands = super.getAvailableCommands()
        val isLive = currentMediaItem?.mediaId?.startsWith(LIVE_MEDIA_ID_PREFIX) == true
        if (!isLive) return commands
        return commands.buildUpon()
            .removeAll(
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            )
            .build()
    }

    private companion object {
        /** Mirrors MediaLibrarySessionCallback.LIVE_PREFIX ("live-"). */
        const val LIVE_MEDIA_ID_PREFIX = "live-"
    }
}

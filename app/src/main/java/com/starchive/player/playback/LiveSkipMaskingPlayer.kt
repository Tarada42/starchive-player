package com.starchive.player.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player

/**
 * Hides car "next / previous" and the spurious next icon that [COMMAND_SET_SPEED_AND_PITCH]
 * creates for a live stream.
 *
 * A live mount is one endless stream, so skipping has no meaning. We detect live playback
 * using media3's own semantics ([Player.isCurrentMediaItemLive]) instead of matching a
 * hard-coded mediaId prefix. This ensures the mask applies whenever ExoPlayer reports the
 * current window as live, regardless of which browse/playback path built the [MediaItem].
 *
 * Media3 builds the legacy PlaybackStateCompat actions from the player command set in
 * MediaSessionLegacyStub.createPlaybackStateCompat() (1.11.1
 * MediaSessionLegacyStub.java:1914-1921), intersecting the per-controller player
 * commands with [player.getAvailableCommands()].
 *
 * - COMMAND_SEEK_TO_NEXT maps to ACTION_SKIP_TO_NEXT (bit 5).
 * - COMMAND_SET_SPEED_AND_PITCH maps to PlaybackStateCompat.ACTION_SET_PLAYBACK_SPEED
 *   (PlaybackStateCompat.java:252), whose bit position collides with the framework's
 *   ACTION_SEEK_TO_NEXT. Masking it for live streams removes the spurious next-track icon.
 *
 * Archive playback keeps speed, skip, seek and every other command untouched.
 */
class LiveSkipMaskingPlayer(player: Player) : ForwardingPlayer(player) {

    override fun getAvailableCommands(): Player.Commands {
        val commands = super.getAvailableCommands()
        if (!isLiveCurrentItem()) return commands
        return commands.buildUpon().removeAll(*HIDDEN_COMMANDS).build()
    }

    override fun isCommandAvailable(command: Int): Boolean {
        if (isLiveCurrentItem() && HIDDEN_COMMANDS.contains(command)) return false
        return super.isCommandAvailable(command)
    }

    private fun isLiveCurrentItem(): Boolean = isLiveNow()

    private companion object {
        val HIDDEN_COMMANDS = intArrayOf(
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SET_SPEED_AND_PITCH,
        )
    }
}

/**
 * Safe live check that mirrors MediaSessionLegacyStub's guard before using live state.
 * Returns false when the timeline is empty or [COMMAND_GET_CURRENT_MEDIA_ITEM] is unavailable.
 */
internal fun Player.isLiveNow(): Boolean =
    isCommandAvailable(Player.COMMAND_GET_CURRENT_MEDIA_ITEM) && isCurrentMediaItemLive()

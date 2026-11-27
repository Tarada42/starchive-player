package com.starchive.player.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player

/**
 * Hides car "next / previous" and the spurious next icon that [COMMAND_SET_SPEED_AND_PITCH]
 * creates for a live stream.
 *
 * A live mount is one endless stream, so skipping has no meaning. Two earlier attempts
 * masked only COMMAND_SEEK_TO_NEXT / COMMAND_SEEK_TO_NEXT_MEDIA_ITEM (and the previous
 * variants) at both [getAvailableCommands] and [isCommandAvailable]. They removed
 * PlaybackStateCompat.ACTION_SKIP_TO_NEXT (bit 5), but the car still showed a next
 * button because:
 *
 *  - Media3 builds the legacy PlaybackStateCompat actions from the player command set in
 *    MediaSessionLegacyStub.createPlaybackStateCompat() (1.11.1
 *    MediaSessionLegacyStub.java:1914-1921), intersecting the per-controller player
 *    commands with [player.getAvailableCommands()].
 *  - COMMAND_SEEK_TO_NEXT is mapped to ACTION_SKIP_TO_NEXT (bit 5) at
 *    MediaSessionLegacyStub.java:2028-2030, so masking it genuinely cleared bit 5.
 *  - COMMAND_SET_SPEED_AND_PITCH is mapped to
 *    PlaybackStateCompat.ACTION_SET_PLAYBACK_SPEED at
 *    MediaSessionLegacyStub.java:2043-2044.
 *  - media3's vendored PlaybackStateCompat defines ACTION_SET_PLAYBACK_SPEED as `1 << 22`
 *    (PlaybackStateCompat.java:252), which collides with the framework's
 *    PlaybackState.ACTION_SEEK_TO_NEXT bit. The framework MediaSession therefore reports
 *    bit 22 as ACTION_SEEK_TO_NEXT, and Android Auto draws its next-track icon from it.
 *
 * COMMAND_SET_SPEED_AND_PITCH is meaningless for a live stream and is added to the mask.
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

    private fun isLiveCurrentItem(): Boolean =
        currentMediaItem?.mediaId?.startsWith(LIVE_MEDIA_ID_PREFIX) == true

    private companion object {
        /** Mirrors MediaLibrarySessionCallback.LIVE_PREFIX ("live-"). */
        const val LIVE_MEDIA_ID_PREFIX = "live-"

        val HIDDEN_COMMANDS = intArrayOf(
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SET_SPEED_AND_PITCH,
        )
    }
}

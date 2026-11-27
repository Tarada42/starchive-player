package com.starchive.player.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player

/**
 * Hides "skip to next / previous" for a live stream, at both doors media3 knocks on.
 *
 * A live mount is one endless stream, so skipping has no meaning. Removing the commands
 * from the per-controller result in [MediaLibrarySessionCallback.onConnect] is not enough:
 * Android Auto reads PlaybackStateCompat, which media3 builds from the player, and it asks
 * `isCommandAvailable()` rather than reading the command set. [ForwardingPlayer] forwards
 * that call straight to the delegate, so overriding only [getAvailableCommands] left
 * SEEK_TO_NEXT advertised — verified in `dumpsys media_session`, where `actions=` still
 * carried bit 22 (ACTION_SEEK_TO_NEXT) while bit 5 (ACTION_SKIP_TO_NEXT) was gone.
 *
 * Both are overridden here so the command is genuinely unavailable while live is current.
 * Archive playback and every other command are untouched.
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
        )
    }
}

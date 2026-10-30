package com.starchive.player.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player

/**
 * Wraps the ExoPlayer so Android Auto's rewind/fast-forward buttons use a fixed
 * 30-second step. media3 1.4.1 has no setSeekForwardIncrementMs (added in 1.9), so we
 * override seekBack()/seekForward() — the methods Android Auto and the media session
 * call — to jump by [SEEK_STEP_MS].
 *
 * We also explicitly advertise SEEK_BACK and SEEK_FORWARD in the available commands
 * so the Android Auto head unit renders the back/forward buttons.
 */
class ThirtySecondSeekPlayer(player: Player) : ForwardingPlayer(player) {

    override fun seekBack() {
        seekTo((currentPosition - SEEK_STEP_MS).coerceAtLeast(0L))
    }

    override fun seekForward() {
        seekTo(currentPosition + SEEK_STEP_MS)
    }

    override fun isCommandAvailable(command: Int): Boolean {
        if (command == Player.COMMAND_SEEK_BACK || command == Player.COMMAND_SEEK_FORWARD) {
            return wrappedPlayer.isCommandAvailable(Player.COMMAND_SEEK_BACK)
                    || wrappedPlayer.isCommandAvailable(Player.COMMAND_SEEK_FORWARD)
                    || wrappedPlayer.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
        }
        return super.isCommandAvailable(command)
    }

    override fun getAvailableCommands(): Player.Commands {
        return wrappedPlayer.availableCommands
            .buildUpon()
            .addAll(
                Player.COMMAND_SEEK_BACK,
                Player.COMMAND_SEEK_FORWARD,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            )
            .build()
    }

    companion object {
        const val SEEK_STEP_MS: Long = 30_000L
    }
}

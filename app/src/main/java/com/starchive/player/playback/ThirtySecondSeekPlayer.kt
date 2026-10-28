package com.starchive.player.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player

/**
 * Wraps the ExoPlayer so Android Auto's rewind/fast-forward buttons use a fixed
 * 30-second step. media3 1.4.1 has no setSeekForwardIncrementMs (added in 1.9), so we
 * override seekBack()/seekForward() — the methods Android Auto and the media session
 * call — to jump by [SEEK_STEP_MS] instead of the ~10s default.
 *
 * The in-app player's skip buttons route through the same controller/player path, so
 * both surfaces share the identical 30-second behavior.
 */
class ThirtySecondSeekPlayer(player: Player) : ForwardingPlayer(player) {

    override fun seekBack() {
        seekTo((currentPosition - SEEK_STEP_MS).coerceAtLeast(0L))
    }

    override fun seekForward() {
        seekTo(currentPosition + SEEK_STEP_MS)
    }

    companion object {
        const val SEEK_STEP_MS: Long = 30_000L
    }
}
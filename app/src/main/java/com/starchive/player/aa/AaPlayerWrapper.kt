package com.starchive.player.aa

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * Wraps the shared PlaybackService player to guarantee exactly 10-second seek steps
 * for the Android Auto player screen, and to implement the AA Stop behavior
 * (live streams fully torn down, normal tracks stopped and reset).
 *
 * media3 1.4.1 does not expose setSeekBackIncrementMs/setSeekForwardIncrementMs
 * (added in 1.9), so seekBack()/seekForward() are overridden directly.
 */
@UnstableApi
class AaPlayerWrapper(
    player: Player,
    private val onClearSessionState: () -> Unit,
) : ForwardingPlayer(player) {

    override fun seekBack() {
        seekTo((currentPosition - SEEK_STEP_MS).coerceAtLeast(0L))
    }

    override fun seekForward() {
        val maxPosition = duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        seekTo((currentPosition + SEEK_STEP_MS).coerceAtMost(maxPosition))
    }

    override fun stop() {
        val mediaId = currentMediaItem?.mediaId
        when {
            mediaId?.startsWith("live-") == true -> {
                // Live streams: full teardown of the connection and queue.
                super.stop()
                clearMediaItems()
            }
            mediaId != null -> {
                // Normal tracks: stop and return to the beginning.
                super.stop()
                seekTo(0L)
            }
            else -> super.stop()
        }
        onClearSessionState()
    }

    companion object {
        private const val SEEK_STEP_MS = 10_000L
    }
}

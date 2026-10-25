package com.starchive.player.data

/** A DJ / artist. [id] is the folder name on the archive, e.g. "dj_td". */
data class Artist(
    val id: String,
    val displayName: String,
    /** Streambox thumbnail for the most recent track, if known. */
    val thumbnailUrl: String?,
    /** Twitch profile image URL, if it could be resolved. */
    val twitchAvatarUrl: String?,
    /** Last-modified date of the artist folder in the source listing (epoch millis). */
    val sourceDateMillis: Long = 0L,
) {
    /** Best image to show for this artist. */
    val bestImageUrl: String?
        get() = twitchAvatarUrl ?: thumbnailUrl
}

/** One playable set. [url] is a direct https link to the .mp4 (audio-only content). */
data class Track(
    val id: String,          // file name, e.g. dj_td_2026-09-02-04-00.mp4
    val artistId: String,
    val artistName: String,
    val title: String,       // human friendly, e.g. "2026-09-02 04:00"
    val url: String,
    val thumbnailUrl: String?,
    val dateMillis: Long,    // parsed from the file name, for sorting
)

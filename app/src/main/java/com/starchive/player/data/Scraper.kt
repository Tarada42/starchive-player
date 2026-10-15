package com.starchive.player.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Scrapes the STArchive Apache directory listings at https://starchive.streambox.space/
 *
 * Layout observed on the live server:
 *   /                       -> one folder per artist, e.g. href="dj_td/"
 *   /{artistId}/            -> .mp4 files, e.g. dj_td_2026-09-02-04-00.mp4
 *   /thumbnails/            -> {artistId}_picture_{timestamp}.jpg
 *
 * The timestamp embedded in the .mp4 file name (…_2026-09-02-04-00.mp4) matches the
 * thumbnail's timestamp exactly, so we can derive the artwork URL directly from the
 * track file name — no need to crawl the (very large) thumbnail directory.
 *
 * Artist IDs on streambox correspond to Twitch usernames, so we resolve avatars
 * through decapi.me/twitch/avatar/{username} (public, rate-limit 100/min).
 */
class Scraper(
    private val baseUrl: String = DEFAULT_BASE,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val inputFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm", Locale.US)
    private val outputFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)

    private val timestampRe = Pattern.compile("(\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2})")

    suspend fun fetchArtists(): List<Artist> = withContext(Dispatchers.IO) {
        val doc = getDoc("$baseUrl/")
        val artists = mutableListOf<Artist>()
        for (a in doc.select("table#list tbody a[href]")) {
            val href = a.attr("href")
            // artist folders are relative links ending in "/" (skip parent, thumbnails, sort links)
            if (!href.endsWith("/")) continue
            if (href == "../" || href.startsWith("?") || href == "/") continue
            val id = href.removeSuffix("/")
            if (id == "thumbnails" || id.isBlank()) continue
            artists.add(
                Artist(
                    id = id,
                    displayName = prettifyName(id),
                    // Artist-list thumbnails cannot be derived without a track timestamp,
                    // but we try to fetch a Twitch avatar instead later.
                    thumbnailUrl = null,
                    twitchAvatarUrl = null,
                )
            )
        }
        artists.sortedBy { it.displayName.lowercase(Locale.ROOT) }
    }

    suspend fun fetchTracks(artist: Artist): List<Track> = withContext(Dispatchers.IO) {
        val doc = getDoc("$baseUrl/${artist.id}/")
        val tracks = mutableListOf<Track>()
        for (a in doc.select("table#list tbody a[href]")) {
            val fileName = a.attr("href").substringAfterLast('/')
            if (!fileName.endsWith(".mp4", ignoreCase = true)) continue
            val ts = extractTimestamp(fileName)
            val (title, millis) = if (ts != null) {
                val dt = LocalDateTime.parse(ts, inputFormatter)
                dt.format(outputFormatter) to dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            } else {
                prettifyName(fileName.removeSuffix(".mp4")) to 0L
            }
            tracks.add(
                Track(
                    id = fileName,
                    artistId = artist.id,
                    artistName = artist.displayName,
                    title = title,
                    url = "$baseUrl/${artist.id}/$fileName",
                    thumbnailUrl = ts?.let { link("${artist.id}_picture_$it.jpg") },
                    dateMillis = millis,
                )
            )
        }
        tracks.sortedByDescending { it.dateMillis }
    }

    /**
     * Resolve a Twitch avatar for a username. Returns the direct image URL or null.
     * Uses decapi.me (public). Rate-limit is 100 req/min, so callers should throttle.
     */
    suspend fun fetchTwitchAvatar(username: String): String? = withContext(Dispatchers.IO) {
        val url = "https://decapi.me/twitch/avatar/${username.lowercase(Locale.ROOT)}"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string()?.trim() ?: return@withContext null
                // decapi returns either an image URL or an error string.
                if (body.startsWith("http")) body else null
            }
        } catch (e: Exception) {
            null
        }
    }

    // --- helpers ---

    private fun getDoc(url: String): Document {
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} for $url")
            return Jsoup.parse(resp.body?.string() ?: "")
        }
    }

    private fun extractTimestamp(fileName: String): String? {
        val m = timestampRe.matcher(fileName)
        return if (m.find()) m.group(1) else null
    }

    /** "dj_td" -> "dj td"; "andy gravity" kept as-is. Underscores -> spaces, trimmed. */
    private fun prettifyName(raw: String): String =
        raw.replace('_', ' ').trim().replace(Regex("\\s+"), " ")

    private fun link(fileName: String): String = "$baseUrl/thumbnails/$fileName"

    companion object {
        const val DEFAULT_BASE = "https://starchive.streambox.space"
        private const val USER_AGENT = "STArchivePlayer/1.0 (Android)"
    }
}

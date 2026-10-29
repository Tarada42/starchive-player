package com.starchive.player.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Fetches STArchive data from the hosted web-app API at https://webapp-copy-silk.vercel.app/
 *
 * Endpoints (owned by us, cached + refreshed daily server-side):
 *   GET /api/artists                  -> [{ id, name, displayName, date }] newest-first
 *   GET /api/artist-thumbnails        -> { "<artistId>": "<picture url>" } (incl. Twitch fallback)
 *   GET /api/tracks?artist=<id>       -> [{ id, artist, filename, title, url, thumbnailUrl,
 *                                           pictureUrl, size, date, index }] newest-first
 *
 * The API already excludes DJs that were removed (no picture for 14+ days), applies
 * Twitch-avatar fallback for DJs without an archive thumbnail, and resolves the newest
 * picture per DJ — so the app no longer scrapes streambox directly.
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
        val thumbs = parseThumbnailMap(getJson("$baseUrl/api/artist-thumbnails"))
        val artists = mutableListOf<Artist>()
        val arr = getJsonArray("$baseUrl/api/artists")
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            artists.add(
                Artist(
                    id = id,
                    displayName = o.optString("displayName").ifBlank { prettifyName(id) },
                    thumbnailUrl = thumbs[id],
                    twitchAvatarUrl = null,
                    sourceDateMillis = parseSourceDate(o.optString("date")) ?: 0L,
                )
            )
        }
        artists.sortedBy { it.displayName.lowercase(Locale.ROOT) }
    }

    suspend fun fetchTracks(artist: Artist): List<Track> = withContext(Dispatchers.IO) {
        val tracks = mutableListOf<Track>()
        val arr = getJsonArray("$baseUrl/api/tracks?artist=${artist.id}")
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val fileName = o.optString("filename")
            if (fileName.isBlank()) continue
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
                    url = o.optString("url").ifBlank { "$baseStaticUrl/${artist.id}/$fileName" },
                    thumbnailUrl = o.optString("pictureUrl").ifBlank { o.optString("thumbnailUrl").ifBlank { null } },
                    dateMillis = millis,
                )
            )
        }
        tracks.sortedByDescending { it.dateMillis }
    }

    /**
     * Resolve a Twitch avatar for a username. Returns the direct image URL or null.
     * Uses decapi.me (public). Rate-limit is 100 req/min; callers throttle. Retries a
     * couple of times on transient HTTP failures (incl. rate-limit 429) before giving up.
     */
    suspend fun fetchTwitchAvatar(username: String): String? = withContext(Dispatchers.IO) {
        val url = "https://decapi.me/twitch/avatar/${username.lowercase(Locale.ROOT)}"
        repeat(3) { attempt ->
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()
            try {
                client.newCall(req).execute().use { resp ->
                    if (resp.code == 429) {
                        // Rate limited — back off briefly then retry.
                        Thread.sleep(2_000L)
                        return@repeat
                    }
                    if (!resp.isSuccessful) return@withContext null
                    val body = resp.body?.string()?.trim() ?: return@withContext null
                    if (body.startsWith("http")) return@withContext body
                    return@withContext null
                }
            } catch (e: Exception) {
                if (attempt == 2) return@withContext null
                Thread.sleep(1_000L)
            }
        }
        null
    }

    /**
     * Fetch the usernames of DJs that are live right now, from the Icecast restream
     * page at live.streambox.space. The page lists one mount point per live DJ, e.g.
     * "Mountpoint /glog78" with "restream from https://www.twitch.tv/glog78". We pull
     * every unique Twitch username referenced on the page.
     */
    suspend fun fetchLiveUsernames(): List<String> = withContext(Dispatchers.IO) {
        val url = "https://live.streambox.space/"
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList<String>()
                val html = resp.body?.string() ?: return@withContext emptyList<String>()
                val found = LinkedHashSet<String>()
                val twitchRe = Pattern.compile("twitch\\.tv/([A-Za-z0-9_]+)", Pattern.CASE_INSENSITIVE)
                val m = twitchRe.matcher(html)
                while (m.find()) found.add(m.group(1) ?: continue)
                found.toList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Build the playable restream URL for a live DJ's Twitch channel. */
    fun liveStreamUrl(username: String): String =
        "https://live.streambox.space/${username.lowercase(Locale.ROOT)}_hq"

    // --- helpers ---

    private fun getJson(u: String): JSONObject {
        val req = Request.Builder().url(u).header("User-Agent", USER_AGENT).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} for $u")
            return JSONObject(resp.body?.string() ?: "{}")
        }
    }

    private fun getJsonArray(u: String): JSONArray {
        val req = Request.Builder().url(u).header("User-Agent", USER_AGENT).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} for $u")
            val body = resp.body?.string() ?: "[]"
            return JSONArray(body)
        }
    }

    /** { "<artistId>": "<picture url>" } from the API's thumbnail map. */
    private fun parseThumbnailMap(o: JSONObject): Map<String, String> {
        val map = mutableMapOf<String, String>()
        o.keys().forEach { k ->
            val v = o.optString(k)
            if (v.isNotBlank()) map[k] = v
        }
        return map
    }

    private fun extractTimestamp(fileName: String): String? {
        val m = timestampRe.matcher(fileName)
        return if (m.find()) m.group(1) else null
    }

    /** Parse an API date like "25-Jun-2026 07:00" to epoch millis. */
    private fun parseSourceDate(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        return try {
            val fmt = java.time.format.DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm", Locale.US)
            java.time.LocalDateTime.parse(text, fmt)
                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (e: Exception) {
            null
        }
    }

    /** "dj_td" -> "dj td"; "andy gravity" kept as-is. Underscores -> spaces, trimmed. */
    private fun prettifyName(raw: String): String =
        raw.replace('_', ' ').trim().replace(Regex("\\s+"), " ")

    companion object {
        const val DEFAULT_BASE = "https://webapp-copy-silk.vercel.app"
        private const val baseStaticUrl = "https://starchive.streambox.space"
        private const val USER_AGENT = "STArchivePlayer/1.0 (Android)"
    }
}
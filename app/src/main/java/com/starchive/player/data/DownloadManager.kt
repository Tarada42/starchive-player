package com.starchive.player.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

enum class DownloadState { NONE, DOWNLOADING, DONE, FAILED }

/**
 * Downloads tracks to the app's internal storage (no storage permission needed) so
 * they can be played offline, typically downloaded on Wi-Fi to save mobile data.
 *
 * State persists across restarts (JSON index in files dir). Each track id maps to a
 * file named after the id. Downloaded tracks are re-scannable at startup.
 */
class DownloadManager(private val context: Context, private val scope: CoroutineScope) {

    private val downloadsDir = File(context.filesDir, "downloads").apply { mkdirs() }
    private val indexFile = File(context.filesDir, "downloads_index.json")

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Downloaded track id -> the full track metadata (persisted). */
    private val _downloaded = MutableStateFlow<Map<String, Track>>(loadIndex())
    val downloaded: StateFlow<Map<String, Track>> = _downloaded.asStateFlow()

    /** Track id -> download progress 0f..1f while downloading. */
    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val progress: StateFlow<Map<String, Float>> = _progress.asStateFlow()

    private val jobs = mutableMapOf<String, Job>()

    /** Local file URI for a downloaded track, or null if not downloaded. */
    fun localUri(trackId: String): String? {
        val f = fileFor(trackId)
        return if (f.exists() && f.length() > 0) f.toURI().toString() else null
    }

    fun isDownloaded(trackId: String): Boolean = localUri(trackId) != null

    /** Download a single track. No-op if already downloaded or already queued. */
    fun download(track: Track) {
        if (isDownloaded(track.id)) return
        if (jobs.containsKey(track.id)) return
        jobs[track.id] = scope.launch { downloadInternal(track) }
    }

    /** Download many tracks (multi-select). */
    fun downloadAll(tracks: List<Track>) = tracks.forEach { download(it) }

    /** Delete a downloaded track (file + index entry). */
    fun delete(trackId: String) {
        jobs.remove(trackId)?.cancel()
        fileFor(trackId).delete()
        val updated = _downloaded.value.toMutableMap()
        updated.remove(trackId)
        _downloaded.value = updated
        val prog = _progress.value.toMutableMap()
        prog.remove(trackId)
        _progress.value = prog
        saveIndex(updated)
    }

    private suspend fun downloadInternal(track: Track) {
        val target = fileFor(track.id)
        _progress.value = _progress.value + (track.id to 0f)
        try {
            withContext(Dispatchers.IO) {
                val req = Request.Builder().url(track.url).build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
                    val total = resp.body?.contentLength() ?: -1L
                    var downloadedBytes = 0L
                    target.outputStream().use { out ->
                        resp.body?.byteStream()?.use { input ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buf)
                                if (read < 0) break
                                out.write(buf, 0, read)
                                downloadedBytes += read
                                if (total > 0) {
                                    val p = downloadedBytes.toFloat() / total
                                    _progress.value = _progress.value + (track.id to p)
                                }
                            }
                        }
                    }
                }
            }
            if (target.length() <= 0) throw java.io.IOException("Empty file")
            val updated = _downloaded.value + (track.id to track)
            _downloaded.value = updated
            saveIndex(updated)
        } catch (e: Exception) {
            target.delete()
            // Keep as failed but still listed so the UI can offer retry.
            val updated = _downloaded.value + (track.id to track)
            _downloaded.value = updated
        } finally {
            _progress.value = _progress.value - track.id
            jobs.remove(track.id)
        }
    }

    private fun fileFor(trackId: String): File {
        // Track ids are file names with dots (e.g. x.mp4); keep them unique under dir.
        val safe = trackId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(downloadsDir, safe)
    }

    /** Rebuild downloaded map from the index (metadata persisted; files verified). */
    private fun loadIndex(): Map<String, Track> {
        if (!indexFile.exists()) return emptyMap()
        return try {
            val arr = JSONArray(indexFile.readText())
            val map = mutableMapOf<String, Track>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val track = trackFromJson(o)
                if (track != null && fileFor(track.id).exists()) {
                    map[track.id] = track
                }
            }
            map
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun saveIndex(map: Map<String, Track>) {
        try {
            val arr = JSONArray()
            map.values.forEach { arr.put(trackToJson(it)) }
            indexFile.writeText(arr.toString())
        } catch (_: Exception) {
            // ignore index write failures
        }
    }

    private fun trackToJson(t: Track): JSONObject = JSONObject().apply {
        put("id", t.id)
        put("artistId", t.artistId)
        put("artistName", t.artistName)
        put("title", t.title)
        put("url", t.url)
        put("thumbnailUrl", t.thumbnailUrl ?: "")
        put("dateMillis", t.dateMillis)
    }

    private fun trackFromJson(o: JSONObject): Track? = try {
        Track(
            id = o.getString("id"),
            artistId = o.getString("artistId"),
            artistName = o.getString("artistName"),
            title = o.getString("title"),
            url = o.getString("url"),
            thumbnailUrl = o.optString("thumbnailUrl", "").ifBlank { null },
            dateMillis = o.optLong("dateMillis", 0L),
        )
    } catch (e: Exception) {
        null
    }
}

package com.starchive.player.aa

import android.content.Intent
import android.net.Uri
import androidx.car.app.CarContext
import androidx.car.app.model.CarIcon
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.IconCompat
import com.starchive.player.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/** Downloads and caches artist avatar images locally so they can be served to the AA host as content URIs. */
class AaImageCache(
    private val context: CarContext,
    private val scope: CoroutineScope,
) {
    private val client = OkHttpClient.Builder().build()
    private val cacheDir = File(context.cacheDir, "aa_images").apply { mkdirs() }
    private val authority = "${context.packageName}.aa.fileprovider"
    private val hostPackages = listOf("com.google.android.projection.gearhead")

    /** Maps caller-supplied id to the cached content URI, once downloaded. */
    private val cachedUris = mutableMapOf<String, Uri>()
    private val inFlight = mutableSetOf<String>()

    /** Start downloading [requests] in the background; invoke [onReady] when at least one new image is cached. */
    fun preload(requests: List<AaImageRequest>, onReady: () -> Unit) {
        scope.launch(Dispatchers.IO) {
            var changed = false
            requests.forEach { req ->
                if (cachedUris.containsKey(req.id) || !inFlight.add(req.id)) return@forEach
                val file = download(req.url)
                if (file != null) {
                    val uri = runCatching { FileProvider.getUriForFile(context, authority, file) }.getOrNull()
                    if (uri != null) {
                        grantReadPermission(uri)
                        cachedUris[req.id] = uri
                        changed = true
                    }
                }
                inFlight.remove(req.id)
            }
            if (changed) {
                withContext(Dispatchers.Main) { onReady() }
            }
        }
    }

    /** Returns a [CarIcon] for [id] if it has been cached, otherwise null (caller shows a placeholder). */
    fun iconFor(id: String): CarIcon? {
        val uri = cachedUris[id] ?: return null
        return CarIcon.Builder(IconCompat.createWithContentUri(uri)).build()
    }

    /** A safe placeholder icon used while an avatar is loading or after it failed. */
    fun placeholderIcon(): CarIcon {
        return CarIcon.Builder(
            IconCompat.createWithResource(context, R.drawable.aa_default_avatar)
        ).build()
    }

    private fun download(url: String): File? {
        val file = File(cacheDir, "${sha256(url)}.jpg")
        if (file.exists()) return file
        val request = Request.Builder().url(url).build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.byteStream()?.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                file
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun grantReadPermission(uri: Uri) {
        hostPackages.forEach { pkg ->
            runCatching {
                context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

/** Something that wants an avatar cached: a stable [id] plus the remote [url]. */
data class AaImageRequest(val id: String, val url: String)

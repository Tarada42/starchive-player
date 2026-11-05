package com.starchive.player.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.starchive.player.data.Artist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

private data class DjProfile(
    val dj: String,
    val bio: String?,
    val socials: Map<String, String>,
    val error: String?,
)

private sealed interface AboutUiState {
    data object Loading : AboutUiState
    data class Success(val profile: DjProfile) : AboutUiState
    data class Error(val message: String) : AboutUiState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutDjScreen(
    artist: Artist,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var state by remember(artist.id) { mutableStateOf<AboutUiState>(AboutUiState.Loading) }

    LaunchedEffect(artist.id) {
        state = AboutUiState.Loading
        state = withContext(Dispatchers.IO) {
            runCatching { fetchDjProfile(artist.id) }
                .fold(
                    onSuccess = { profile ->
                        if (!profile.error.isNullOrBlank()) {
                            AboutUiState.Error(profile.error)
                        } else {
                            AboutUiState.Success(profile)
                        }
                    },
                    onFailure = {
                        AboutUiState.Error(it.message ?: "Could not load DJ profile")
                    },
                )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        when (val s = state) {
            AboutUiState.Loading -> {
                Box(
                    modifier = modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            is AboutUiState.Error -> {
                Box(
                    modifier = modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = s.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }

            is AboutUiState.Success -> {
                AboutContent(
                    artist = artist,
                    profile = s.profile,
                    onOpenLink = { url ->
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    },
                    modifier = modifier.padding(padding),
                )
            }
        }
    }
}

@Composable
private fun AboutContent(
    artist: Artist,
    profile: DjProfile,
    onOpenLink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item { DjHeader(artist = artist) }
        item { BioCard(bio = profile.bio) }
        item {
            SocialLinks(
                socials = profile.socials,
                onOpenLink = onOpenLink,
            )
        }
    }
}

@Composable
private fun DjHeader(artist: Artist) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(160.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.secondary,
                        )
                    )
                ),
            contentAlignment = Alignment.Center,
        ) {
            artist.bestImageUrl?.let { url ->
                AsyncImage(
                    model = url,
                    contentDescription = artist.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } ?: Text(
                text = artist.displayName.take(2).uppercase(),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
        Text(
            text = artist.displayName,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun BioCard(bio: String?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Text(
            text = bio ?: "No bio available",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun SocialLinks(
    socials: Map<String, String>,
    onOpenLink: (String) -> Unit,
) {
    val entries = remember(socials) {
        socials
            .filterValues { it.isNotBlank() }
            .toList()
            .sortedWith(
                compareBy(
                    { (key, _) -> if (key.equals("twitch", ignoreCase = true)) 0 else 1 },
                    { (key, _) -> key.lowercase() },
                )
            )
    }
    if (entries.isEmpty()) {
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Links",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        entries.forEach { (key, url) ->
            SocialLinkItem(
                label = socialLabel(key),
                url = url,
                onClick = { onOpenLink(url) },
            )
        }
    }
}

@Composable
private fun SocialLinkItem(
    label: String,
    url: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        ListItem(
            headlineContent = {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            supportingContent = {
                Text(
                    text = url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingContent = {
                Icon(
                    imageVector = Icons.Filled.OpenInBrowser,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            colors = ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        )
    }
}

private fun socialLabel(key: String): String = when (key.lowercase()) {
    "twitch" -> "Twitch"
    "x", "twitter" -> "X"
    "instagram" -> "Instagram"
    "youtube" -> "YouTube"
    "discord" -> "Discord"
    "tiktok" -> "TikTok"
    "website" -> "Website"
    else -> key.replaceFirstChar { it.uppercase() }
}

private suspend fun fetchDjProfile(username: String): DjProfile = withContext(Dispatchers.IO) {
    val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    val url = "https://webapp.streambox.space/api/dj-profile?dj=$username"
    val request = Request.Builder()
        .url(url)
        .header("User-Agent", "STArchivePlayer/1.0 (Android)")
        .build()
    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
        val body = response.body?.string() ?: "{}"
        val json = JSONObject(body)
        val socialsObj = json.optJSONObject("socials") ?: JSONObject()
        val socials = mutableMapOf<String, String>()
        socialsObj.keys().forEach { socials[it] = socialsObj.optString(it) }
        DjProfile(
            dj = json.optString("dj", username),
            bio = json.optString("bio").takeIf { it.isNotBlank() },
            socials = socials,
            error = json.optString("error").takeIf { it.isNotBlank() },
        )
    }
}

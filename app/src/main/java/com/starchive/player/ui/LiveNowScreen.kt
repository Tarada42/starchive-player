package com.starchive.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.starchive.player.data.Artist

/** Dad jokes shown when no DJs are live (presented as an offer, not forced). */
private val DAD_JOKES = listOf(
    "Why don't eggs tell jokes? They'd crack each other up.",
    "How does a penguin build its house? Igloos it together.",
    "Why did the scarecrow win an award? Because he was outstanding in his field.",
    "What do you call a fish with no eyes? A fsh.",
    "Why don't skeletons fight each other? They don't have the guts.",
    "I told my wife she should embrace her mistakes. She gave me a hug.",
    "Why are elevator jokes classic? They really lift people up.",
    "What do you call fake spaghetti? An impasta.",
)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LiveNowScreen(
    liveArtists: List<Artist>,
    isRefreshing: Boolean,
    favoriteArtistIds: Set<String> = emptySet(),
    onPlay: (Artist) -> Unit,
    onOpenTwitch: (Artist) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Live now",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            if (liveArtists.isEmpty()) "No one live right now" else "${liveArtists.size} live right now",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    OutlinedButton(onClick = onRefresh) {
                        Text(if (isRefreshing) "…" else "Refresh")
                    }
                    OverflowMenuButton()
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                navigationIcon = { DrawerMenuButton() },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (liveArtists.isEmpty()) {
                LiveEmptyState(onRefresh = onRefresh)
            } else {
                Text(
                    "Team members streaming on Twitch right now:",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                // Pin followed DJs to the top under a group header; everyone else below.
                val favoriteLive = liveArtists.filter { it.id in favoriteArtistIds }
                val otherLive = liveArtists.filterNot { it.id in favoriteArtistIds }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (favoriteLive.isNotEmpty()) {
                        item { SectionHeader("Your favorites") }
                        items(favoriteLive, key = { it.id }) { artist ->
                            LiveDjListItem(
                                artist = artist,
                                isFavorite = true,
                                onPlay = { onPlay(artist) },
                                onOpenTwitch = { onOpenTwitch(artist) },
                            )
                        }
                        if (otherLive.isNotEmpty()) {
                            item { SectionHeader("Other live") }
                            items(otherLive, key = { it.id }) { artist ->
                                LiveDjListItem(
                                    artist = artist,
                                    isFavorite = false,
                                    onPlay = { onPlay(artist) },
                                    onOpenTwitch = { onOpenTwitch(artist) },
                                )
                            }
                        }
                    } else {
                        items(liveArtists, key = { it.id }) { artist ->
                            LiveDjListItem(
                                artist = artist,
                                isFavorite = false,
                                onPlay = { onPlay(artist) },
                                onOpenTwitch = { onOpenTwitch(artist) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun LiveDjListItem(
    artist: Artist,
    isFavorite: Boolean = false,
    onPlay: () -> Unit,
    onOpenTwitch: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onPlay)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
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
                artist.displayName.take(2).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                artist.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "on Twitch",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (isFavorite) {
            Icon(
                imageVector = Icons.Filled.Favorite,
                contentDescription = "Favorite",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
        }
        IconButton(onClick = onPlay, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = "Play",
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(24.dp),
            )
        }
        IconButton(onClick = onOpenTwitch, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = "Open Twitch",
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun LiveEmptyState(onRefresh: () -> Unit) {
    var wantJoke by remember { mutableStateOf(false) }
    var jokeIndex by remember { mutableStateOf(0) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text("🎧", style = MaterialTheme.typography.headlineLarge)
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "Sorry, no team members are live right now",
            style = MaterialTheme.typography.titleMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Do you want to hear a joke instead?",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        if (!wantJoke) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { wantJoke = true }) { Text("Yes, joke!") }
                OutlinedButton(onClick = onRefresh) { Text("Refresh") }
            }
        } else {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(
                    DAD_JOKES[jokeIndex % DAD_JOKES.size],
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(20.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { jokeIndex++ }) { Text("Another") }
                OutlinedButton(onClick = onRefresh) { Text("Check live") }
            }
        }
    }
}

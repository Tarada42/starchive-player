package com.starchive.player.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.starchive.player.data.Artist
import com.starchive.player.data.Track

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistDetailScreen(
    artist: Artist,
    tracks: List<Track>,
    favorites: Set<String>,
    currentTrackId: String?,
    isPlaying: Boolean,
    onBack: () -> Unit,
    onTrackClick: (List<Track>, Int) -> Unit,
    onToggleFavorite: (Track) -> Unit,
    singleArtistMode: Boolean = false,
    onExitSingleArtist: (() -> Unit)? = null,
    downloadManager: com.starchive.player.data.DownloadManager,
    onDownloadMany: (List<Track>) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // Multi-select download state: long-press a track to enter selection mode, tap to
    // select several, then one "Download" button downloads them all.
    var selectionMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    val downloadedIds by downloadManager.downloaded.collectAsStateWithLifecycle(initialValue = emptyMap())
    val downloadProgress by downloadManager.progress.collectAsStateWithLifecycle(initialValue = emptyMap())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(artist.displayName) },
                navigationIcon = {
                    if (singleArtistMode) {
                        // Exit Single Artist mode: back to the all-artists front page.
                        IconButton(onClick = { onExitSingleArtist?.invoke() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Forlad Single Artist",
                            )
                        }
                    } else {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Tilbage",
                            )
                        }
                    }
                },
                actions = {
                    if (singleArtistMode) {
                        Text(
                            text = "Single Artist",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 16.dp),
                        )
                    }
                    if (selectionMode) {
                        Text(
                            text = "${selected.size} valgt",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Text(
                            text = "Download",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                .clickable {
                                    val chosen = tracks.filter { it.id in selected }
                                    if (chosen.isNotEmpty()) onDownloadMany(chosen)
                                    selectionMode = false
                                    selected = emptySet()
                                },
                        )
                        IconButton(onClick = {
                            selectionMode = false
                            selected = emptySet()
                        }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Annuller valg",
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ArtistHeader(artist = artist)
            }
            itemsIndexed(tracks, key = { _, t -> t.id }) { index, track ->
                TrackRow(
                    track = track,
                    isFavorite = favorites.contains(track.id),
                    isCurrent = track.id == currentTrackId,
                    isPlaying = isPlaying,
                    isSelected = track.id in selected,
                    selectionMode = selectionMode,
                    isDownloaded = track.id in downloadedIds.keys,
                    downloadProgress = downloadProgress[track.id],
                    onClick = {
                        if (selectionMode) {
                            selected = if (track.id in selected) selected - track.id else selected + track.id
                        } else {
                            onTrackClick(tracks, index)
                        }
                    },
                    onLongClick = {
                        selectionMode = true
                        selected = selected + track.id
                    },
                    onDownload = { downloadManager.download(track) },
                    onToggleFavorite = { onToggleFavorite(track) },
                )
            }
        }
    }
}

@Composable
private fun ArtistHeader(artist: Artist) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f),
                    ),
                )
            )
            .padding(20.dp),
        contentAlignment = Alignment.BottomStart,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(RoundedCornerShape(20.dp))
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
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
            Text(
                text = artist.displayName,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackRow(
    track: Track,
    isFavorite: Boolean,
    isCurrent: Boolean,
    isPlaying: Boolean,
    isSelected: Boolean = false,
    selectionMode: Boolean = false,
    isDownloaded: Boolean = false,
    downloadProgress: Float? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDownload: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                isCurrent -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.secondary,
                            ),
                        )
                    ),
                contentAlignment = Alignment.Center,
            ) {
                track.thumbnailUrl?.let { url ->
                    AsyncImage(
                        model = url,
                        contentDescription = track.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (isCurrent && isPlaying) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "Afspiller",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            Spacer(Modifier.size(12.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
            ) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isCurrent || isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = track.artistName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
                if (isCurrent) {
                    Text(
                        text = if (isPlaying) "Afspiller nu" else "Pause",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (downloadProgress != null) {
                    Text(
                        text = "Downloader ${(downloadProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            // Download / downloaded indicator.
            if (isDownloaded) {
                Icon(
                    imageVector = Icons.Filled.DownloadDone,
                    contentDescription = "Downloadet",
                    tint = MaterialTheme.colorScheme.secondary,
                )
            } else if (downloadProgress == null) {
                IconButton(onClick = onDownload) {
                    Icon(
                        imageVector = Icons.Filled.Download,
                        contentDescription = "Download track",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = if (isFavorite) "Fjern favorit" else "Tilføj favorit",
                    tint = if (isFavorite) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

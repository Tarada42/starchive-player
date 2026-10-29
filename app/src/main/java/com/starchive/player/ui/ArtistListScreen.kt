package com.starchive.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.outlined.ViewModule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.starchive.player.data.Artist
import com.starchive.player.data.ArtistListLayout

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistListScreen(
    artists: List<Artist>,
    favoriteArtistIds: Set<String>,
    sort: ArtistSort,
    sortAscending: Boolean,
    singleArtistId: String?,
    isDark: Boolean,
    liveArtists: List<Artist> = emptyList(),
    layoutMode: ArtistListLayout = ArtistListLayout.GRID,
    onSortChange: (ArtistSort) -> Unit,
    onArtistClick: (Artist) -> Unit,
    onLiveClick: (Artist) -> Unit = {},
    onToggleFavorite: (Artist) -> Unit,
    onSetSingleArtist: (Artist?) -> Unit,
    onToggleTheme: () -> Unit,
    onToggleLayoutMode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // When the sort key or direction changes, reset the scroll to the top so the
    // user sees the first entry (they're otherwise left at the bottom of the list).
    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()
    LaunchedEffect(sort, sortAscending, artists.size) {
        if (artists.size > 0) {
            gridState.scrollToItem(0)
            listState.scrollToItem(0)
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Browse Artists",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "${artists.size} artists",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
                actions = {
                    IconButton(onClick = onToggleLayoutMode) {
                        Icon(
                            imageVector = if (layoutMode == ArtistListLayout.GRID) Icons.Filled.FormatListBulleted else Icons.Outlined.ViewModule,
                            contentDescription = if (layoutMode == ArtistListLayout.GRID) "Skift til liste" else "Skift til gitter",
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                    IconButton(onClick = onToggleTheme) {
                        Icon(
                            imageVector = if (isDark) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                            contentDescription = if (isDark) "Skift til lyst tema" else "Skift til mørkt tema",
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = sort == ArtistSort.NAME,
                    onClick = { onSortChange(ArtistSort.NAME) },
                    label = { Text("Name") },
                    leadingIcon = if (sort == ArtistSort.NAME) {
                        {
                            Icon(
                                imageVector = if (sortAscending) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                                contentDescription = if (sortAscending) "Name stigende" else "Name faldende",
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    } else null,
                )
                FilterChip(
                    selected = sort == ArtistSort.DATE,
                    onClick = { onSortChange(ArtistSort.DATE) },
                    label = { Text("Date") },
                    leadingIcon = if (sort == ArtistSort.DATE) {
                        {
                            Icon(
                                imageVector = if (sortAscending) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                                contentDescription = if (sortAscending) "Date stigende" else "Date faldende (nyeste først)",
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    } else null,
                )
            }
            if (liveArtists.isNotEmpty()) {
                LiveNowSection(artists = liveArtists, onLiveClick = onLiveClick)
            }
            // Banner when the app is pinned to a single artist: explains the front page
            // shows only this DJ and how to get back to all artists.
            singleArtistId?.let { pinned ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = "Single Artist: starting on this DJ. Tap “Show all” to exit.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "Show all",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .clickable { onSetSingleArtist(null) },
                    )
                }
            }
            // Grid mode preserves the existing card layout; list mode shows a compact
            // row with a thumbnail on the left and the full artist name.
            if (layoutMode == ArtistListLayout.GRID) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(artists, key = { it.id }) { artist ->
                        ArtistCard(
                            artist = artist,
                            isFavorite = favoriteArtistIds.contains(artist.id),
                            isPinned = artist.id == singleArtistId,
                            onClick = { onArtistClick(artist) },
                            onToggleFavorite = { onToggleFavorite(artist) },
                            onSetSingle = { onSetSingleArtist(if (artist.id == singleArtistId) null else artist) },
                        )
                    }
                }
            } else {
                LazyListContent(artists, favoriteArtistIds, singleArtistId, onArtistClick, onToggleFavorite, onSetSingleArtist)
            }
        }
    }
}

@Composable
private fun ArtistCard(
    artist: Artist,
    isFavorite: Boolean,
    isPinned: Boolean = false,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onSetSingle: () -> Unit = {},
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            // Web app's card surface: #11111c
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column {
            // Full-bleed square image at the top (matches web: artist-avatar is a
            // square block, no inner padding, overflow hidden).
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
            ) {
                artist.bestImageUrl?.let { url ->
                    AsyncImage(
                        model = url,
                        contentDescription = artist.displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } ?: Box(
                    modifier = Modifier
                        .fillMaxSize()
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
                    Text(
                        text = artist.displayName.take(2).uppercase(),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = artist.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // Pin = "Start app showing only this Artist" (Single Artist mode).
                IconButton(onClick = onSetSingle) {
                    Icon(
                        imageVector = Icons.Filled.PushPin,
                        contentDescription = if (isPinned) "Forlad single-artist" else "Start app på kun denne DJ",
                        tint = if (isPinned) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = if (isFavorite) "Fjern favorit" else "Tilføj favorit",
                        tint = if (isFavorite) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun LazyListContent(
    artists: List<Artist>,
    favoriteArtistIds: Set<String>,
    singleArtistId: String?,
    onArtistClick: (Artist) -> Unit,
    onToggleFavorite: (Artist) -> Unit,
    onSetSingleArtist: (Artist?) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(artists, key = { it.id }) { artist ->
            ArtistListItem(
                artist = artist,
                isFavorite = favoriteArtistIds.contains(artist.id),
                isPinned = artist.id == singleArtistId,
                onClick = { onArtistClick(artist) },
                onToggleFavorite = { onToggleFavorite(artist) },
                onSetSingle = { onSetSingleArtist(if (artist.id == singleArtistId) null else artist) },
            )
        }
    }
}

@Composable
private fun ArtistListItem(
    artist: Artist,
    isFavorite: Boolean,
    isPinned: Boolean = false,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onSetSingle: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
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
                text = artist.displayName.take(2).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            text = artist.displayName,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onSetSingle, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = Icons.Filled.PushPin,
                contentDescription = if (isPinned) "Forlad single-artist" else "Start app på kun denne DJ",
                tint = if (isPinned) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(onClick = onToggleFavorite, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription = if (isFavorite) "Fjern favorit" else "Tilføj favorit",
                tint = if (isFavorite) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Horizontal "Live now" row: DJs currently broadcasting on live.streambox.space. */
@Composable
private fun LiveNowSection(
    artists: List<Artist>,
    onLiveClick: (Artist) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFEF4444)),
            )
            Text(
                "Live now",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(artists, key = { it.id }) { artist ->
                LiveArtistItem(artist = artist, onClick = { onLiveClick(artist) })
            }
        }
    }
}

@Composable
private fun LiveArtistItem(
    artist: Artist,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(84.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            artist.bestImageUrl?.let { url ->
                AsyncImage(
                    model = url,
                    contentDescription = artist.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // Small LIVE badge in the corner.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = 0.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFFEF4444))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                Text(
                    "LIVE",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
        }
        Spacer(Modifier.size(6.dp))
        Text(
            text = artist.displayName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
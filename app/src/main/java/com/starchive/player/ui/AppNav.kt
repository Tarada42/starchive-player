package com.starchive.player.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.starchive.player.data.Artist
import com.starchive.player.data.Track
import com.starchive.player.playback.PlaybackManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable

@Serializable
data object HomeRoute

@Serializable
data object SearchRoute

@Serializable
data object FavoritesRoute

@Serializable
data class ArtistDetailRoute(val artistId: String, val artistName: String)

@Serializable
data object PlayerRoute

enum class TopLevel(val route: Any, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Browse(HomeRoute, "Browse", Icons.Filled.Home),
    Search(SearchRoute, "Search", Icons.Filled.Search),
    Favorites(FavoritesRoute, "Favorites", Icons.Filled.Favorite),
}

/**
 * Top-level navigation host.
 *
 * Deliberately has NO persistent mini-player: tapping a track opens the full-screen
 * [PlayerScreen]; once you navigate away from it, playback keeps running in the
 * background via [PlaybackService][com.starchive.player.playback.PlaybackService]
 * (lock-screen / notification controls still work), but no mini bar is shown in the UI.
 */
@Composable
fun AppNav(
    isDark: Boolean,
    onToggleTheme: () -> Unit,
    viewModel: MainViewModel = viewModel(factory = MainViewModel.Factory(LocalContext.current)),
    navController: NavHostController = rememberNavController(),
) {
    val context = LocalContext.current
    val manager = remember {
        PlaybackManager(context, CoroutineScope(Dispatchers.Main)).apply { connect() }
    }

    val current by manager.current.collectAsStateWithLifecycle()
    val isPlaying by manager.isPlaying.collectAsStateWithLifecycle()
    val position by manager.positionMs.collectAsStateWithLifecycle()
    val duration by manager.durationMs.collectAsStateWithLifecycle()

    val artistsState by viewModel.artists.collectAsStateWithLifecycle()
    val tracksState by viewModel.tracks.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val artistSort by viewModel.artistSort.collectAsStateWithLifecycle()
    val favoriteTrackIds by viewModel.favoriteTracks.collectAsStateWithLifecycle()
    val favoriteArtistIds by viewModel.favoriteArtists.collectAsStateWithLifecycle()
    val favoriteTrackObjects by viewModel.favoriteTrackObjects.collectAsStateWithLifecycle()
    val recentSearches by viewModel.recentSearches.collectAsStateWithLifecycle()
    val liveArtists by viewModel.liveArtists.collectAsStateWithLifecycle()
    var selectedArtist by remember { mutableStateOf<Artist?>(null) }

    LaunchedEffect(Unit) {
        viewModel.loadArtists()
    }

    val currentBackStack by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStack?.destination?.route
    val showBottomBar = TopLevel.entries.any { currentRoute?.contains(it.route::class.qualifiedName ?: "") == true }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            if (showBottomBar) {
                BottomNavBar(
                    currentRoute = currentRoute,
                    onItemSelected = { top ->
                        navController.navigate(top.route) {
                            popUpTo(HomeRoute) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = HomeRoute,
            modifier = Modifier.padding(padding),
        ) {
            composable<HomeRoute> {
                when (val s = artistsState) {
                    is UiState.Loading -> Loading(Modifier.fillMaxSize())
                    is UiState.Error -> Error(s.message, Modifier.fillMaxSize())
                    is UiState.Success -> ArtistListScreen(
                        artists = s.data,
                        favoriteArtistIds = favoriteArtistIds,
                        sort = artistSort,
                        isDark = isDark,
                        liveArtists = liveArtists,
                        onSortChange = viewModel::setArtistSort,
                        onArtistClick = { artist ->
                            selectedArtist = artist
                            viewModel.loadTracks(artist)
                            navController.navigate(ArtistDetailRoute(artist.id, artist.displayName))
                        },
                        onLiveClick = { artist ->
                            // Play the DJ's current live restream (HQ MP3).
                            val liveTrack = Track(
                                id = "live-${artist.id}",
                                artistId = artist.id,
                                artistName = artist.displayName,
                                title = "${artist.displayName} — LIVE",
                                url = viewModel.liveStreamUrl(artist.id),
                                thumbnailUrl = artist.bestImageUrl,
                                dateMillis = System.currentTimeMillis(),
                            )
                            manager.play(listOf(liveTrack), 0)
                            navController.navigate(PlayerRoute)
                        },
                        onToggleFavorite = { artist ->
                            viewModel.toggleFavoriteArtist(artist)
                        },
                        onToggleTheme = onToggleTheme,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            composable<ArtistDetailRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<ArtistDetailRoute>()
                val artist = remember(route.artistId) {
                    selectedArtist ?: Artist(route.artistId, route.artistName, null, null)
                }
                LaunchedEffect(artist) { selectedArtist = artist }

                when (val s = tracksState) {
                    is UiState.Loading -> Loading(Modifier.fillMaxSize())
                    is UiState.Error -> Error(s.message, Modifier.fillMaxSize())
                    is UiState.Success -> ArtistDetailScreen(
                        artist = artist,
                        tracks = s.data,
                        favorites = favoriteTrackIds,
                        currentTrackId = current?.id,
                        isPlaying = isPlaying,
                        onBack = { navController.popBackStack() },
                        onTrackClick = { tracks, index ->
                            manager.play(tracks, index)
                            navController.navigate(PlayerRoute)
                        },
                        onToggleFavorite = { track -> viewModel.toggleFavoriteTrack(track) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            composable<SearchRoute> {
                SearchScreen(
                    query = searchQuery,
                    results = searchResults,
                    favorites = favoriteTrackIds,
                    recentSearches = recentSearches,
                    onQueryChange = viewModel::onSearchQueryChange,
                    onRecentSearchClick = { q -> viewModel.onSearchQueryChange(q) },
                    onClearHistory = { viewModel.clearSearchHistory() },
                    onTrackClick = { track ->
                        viewModel.recordSearch(searchQuery)
                        manager.play(listOf(track), 0)
                        navController.navigate(PlayerRoute)
                    },
                    onToggleFavorite = { track -> viewModel.toggleFavoriteTrack(track) },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            composable<FavoritesRoute> {
                LaunchedEffect(favoriteTrackIds) { viewModel.loadFavoriteTrackObjects() }
                FavoritesScreen(
                    artistsState = artistsState,
                    favoriteArtistIds = favoriteArtistIds,
                    favoriteTracksState = favoriteTrackObjects,
                    onArtistClick = { artist ->
                        selectedArtist = artist
                        viewModel.loadTracks(artist)
                        navController.navigate(ArtistDetailRoute(artist.id, artist.displayName))
                    },
                    onTrackClick = { track ->
                        manager.play(listOf(track), 0)
                        navController.navigate(PlayerRoute)
                    },
                    onToggleFavoriteTrack = { viewModel.toggleFavoriteTrack(it) },
                    onToggleFavoriteArtist = { viewModel.toggleFavoriteArtist(it) },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            composable<PlayerRoute> {
                val isFav = current?.let { favoriteTrackIds.contains(it.id) } ?: false
                PlayerScreen(
                    track = current,
                    isPlaying = isPlaying,
                    isFavorite = isFav,
                    positionMs = position,
                    durationMs = duration,
                    onBack = { navController.popBackStack() },
                    onTogglePlay = { manager.togglePlayPause() },
                    onSeek = { ms -> manager.seekTo(ms) },
                    onNext = { manager.next() },
                    onPrev = { manager.previous() },
                    onSkipForward = { manager.skipForward(10_000L) },
                    onSkipBackward = { manager.skipBackward(10_000L) },
                    onToggleFavorite = { current?.let { viewModel.toggleFavoriteTrack(it) } },
                    onShare = { current?.let { shareTrack(context, it) } },
                    onStop = { manager.stop(); navController.popBackStack() },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun BottomNavBar(
    currentRoute: String?,
    onItemSelected: (TopLevel) -> Unit,
) {
    NavigationBar {
        TopLevel.entries.forEach { item ->
            val selected = currentRoute?.contains(item.route::class.qualifiedName ?: "") == true
            NavigationBarItem(
                icon = { Icon(item.icon, contentDescription = item.label) },
                label = { Text(item.label) },
                selected = selected,
                onClick = { onItemSelected(item) },
            )
        }
    }
}

private fun shareTrack(context: Context, track: com.starchive.player.data.Track) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, track.title)
        putExtra(Intent.EXTRA_TEXT, "${track.artistName} — ${track.title}\n${track.url}")
    }
    context.startActivity(Intent.createChooser(intent, "Share set"))
}

package com.starchive.player.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MimeTypes
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.starchive.player.MainActivity
import com.starchive.player.R
import com.starchive.player.data.Artist
import com.starchive.player.data.Track
import com.starchive.player.playback.PlaybackManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data object HomeRoute

@Serializable
data object LiveRoute

@Serializable
data object SearchRoute

@Serializable
data object FavoritesRoute

@Serializable
data object LocalRoute

@Serializable
data class ArtistDetailRoute(val artistId: String, val artistName: String)

@Serializable
data object PlayerRoute

@Serializable
data object SettingsRoute

@Serializable
data object AboutRoute

enum class TopLevel(val route: Any, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Browse(HomeRoute, "Browse", Icons.Filled.Home),
    Live(LiveRoute, "Live now", Icons.Filled.PlayArrow),
    Search(SearchRoute, "Search", Icons.Filled.Search),
    Local(LocalRoute, "Offline", Icons.Filled.Download),
    Favorites(FavoritesRoute, "Favorites", Icons.Filled.Favorite),
}

/**
 * Provides the top-level [DrawerState] to the primary screens so each can place a
 * hamburger icon in its own [TopAppBar] without threading the state through every
 * screen's parameter list.
 */
val LocalDrawerState = staticCompositionLocalOf<DrawerState> {
    error("LocalDrawerState not provided")
}

/**
 * Provides the top-level [NavHostController] so screen-level action bars can navigate
 * (e.g., the overflow menu) without passing the controller through every screen.
 */
val LocalNavController = staticCompositionLocalOf<NavHostController> {
    error("LocalNavController not provided")
}

/**
 * ☰ Hamburger icon used by each primary screen to open the shared side drawer.
 */
@Composable
fun DrawerMenuButton(
    contentDescription: String = "Menu",
) {
    val drawerState = LocalDrawerState.current
    val scope = rememberCoroutineScope()
    IconButton(onClick = { scope.launch { drawerState.open() } }) {
        Icon(
            imageVector = Icons.Filled.Menu,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onBackground,
        )
    }
}

/**
 * ⋮ Overflow menu used by each primary screen to expose top-level destinations that
 * also live in the navigation drawer (Settings, About).
 */
@Composable
fun OverflowMenuButton(
    contentDescription: String = "More options",
) {
    val navController = LocalNavController.current
    var expanded by remember { mutableStateOf(false) }

    IconButton(onClick = { expanded = true }) {
        Icon(
            imageVector = Icons.Filled.MoreVert,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onBackground,
        )
    }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false },
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        DropdownMenuItem(
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = null,
                )
            },
            text = { Text("Settings") },
            onClick = {
                expanded = false
                navController.navigate(SettingsRoute)
            },
        )
        DropdownMenuItem(
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = null,
                )
            },
            text = { Text("About") },
            onClick = {
                expanded = false
                navController.navigate(AboutRoute)
            },
        )
    }
}

/**
 * Top-level navigation host.
 *
 * Deliberately has NO persistent mini-player: tapping a track opens the full-screen
 * [PlayerScreen]; once you navigate away from it, playback keeps running in the
 * background via [PlaybackService][com.starchive.player.playback.PlaybackService]
 * (lock-screen / notification controls still work), but no mini bar is shown in the UI.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNav(
    isDark: Boolean,
    onToggleTheme: () -> Unit,
    viewModel: MainViewModel = viewModel(factory = MainViewModel.Factory(LocalContext.current)),
    navController: NavHostController = rememberNavController(),
) {
    val context = LocalContext.current
    val uiScope = rememberCoroutineScope()
    val downloadManager = remember {
        (context.applicationContext as com.starchive.player.StArchiveApp).downloadManager
    }
    val manager = remember {
        PlaybackManager(context, CoroutineScope(Dispatchers.Main)).apply { connect() }
    }

    val current by manager.current.collectAsStateWithLifecycle()
    val isPlaying by manager.isPlaying.collectAsStateWithLifecycle()
    val position by manager.positionMs.collectAsStateWithLifecycle()
    val duration by manager.durationMs.collectAsStateWithLifecycle()
    val sleepTimerMinutes by manager.sleepTimerMinutes.collectAsStateWithLifecycle()
    val sleepTimerRemainingMs by manager.sleepTimerRemainingMs.collectAsStateWithLifecycle()

    val artistsState by viewModel.artists.collectAsStateWithLifecycle()
    val tracksState by viewModel.tracks.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val artistSort by viewModel.artistSort.collectAsStateWithLifecycle()
    val sortAscending by viewModel.sortAscending.collectAsStateWithLifecycle()
    val singleArtistId by viewModel.singleArtistId.collectAsStateWithLifecycle()
    val favoriteTrackIds by viewModel.favoriteTracks.collectAsStateWithLifecycle()
    val favoriteArtistIds by viewModel.favoriteArtists.collectAsStateWithLifecycle()
    val favoriteTrackObjects by viewModel.favoriteTrackObjects.collectAsStateWithLifecycle()
    val recentSearches by viewModel.recentSearches.collectAsStateWithLifecycle()
    val liveArtists by viewModel.liveArtists.collectAsStateWithLifecycle()
    val artistListLayout = viewModel.artistListLayout.collectAsStateWithLifecycle()
    val recentResumePoints by viewModel.recentResumePoints.collectAsStateWithLifecycle()
    var selectedArtist by remember { mutableStateOf<Artist?>(null) }
    var liveQualityArtist by remember { mutableStateOf<Artist?>(null) }

    if (liveQualityArtist != null) {
        val artist = liveQualityArtist!!
        AlertDialog(
            onDismissRequest = { liveQualityArtist = null },
            title = { Text("Choose quality") },
            text = { Text("Stream ${artist.displayName} live audio:") },
            confirmButton = {
                TextButton(onClick = {
                    val liveTrack = Track(
                        id = "live-${artist.id}",
                        artistId = artist.id,
                        artistName = artist.displayName,
                        title = "${artist.displayName} — LIVE (High)",
                        url = viewModel.liveStreamUrlHigh(artist.id),
                        thumbnailUrl = artist.bestImageUrl,
                        dateMillis = System.currentTimeMillis(),
                        mimeType = MimeTypes.AUDIO_MPEG,
                    )
                    manager.playTrack(liveTrack)
                    liveQualityArtist = null
                    navController.navigate(PlayerRoute)
                }) { Text("High (MP3)") }
            },
            dismissButton = {
                TextButton(onClick = {
                    val liveTrack = Track(
                        id = "live-${artist.id}",
                        artistId = artist.id,
                        artistName = artist.displayName,
                        title = "${artist.displayName} — LIVE (Low)",
                        url = viewModel.liveStreamUrlLow(artist.id),
                        thumbnailUrl = artist.bestImageUrl,
                        dateMillis = System.currentTimeMillis(),
                        mimeType = MimeTypes.AUDIO_OGG,
                    )
                    manager.playTrack(liveTrack)
                    liveQualityArtist = null
                    navController.navigate(PlayerRoute)
                }) { Text("Low (Ogg)") }
            },
        )
    }

    LaunchedEffect(Unit) {
        viewModel.loadArtists()
    }

    // Notification tap → now-playing track. React to EVERY open-player request
    // (cold start via onCreate, or app already alive via onNewIntent — both increment
    // MainActivity.openPlayerRequests). Navigate only when a track is actually active;
    // never restart or seek playback.
    val mainActivity = context as? MainActivity
    val openPlayerRequests by mainActivity?.openPlayerRequests
        ?.collectAsStateWithLifecycle(initialValue = 0) ?: remember { mutableStateOf(0) }
    LaunchedEffect(openPlayerRequests) {
        if (openPlayerRequests > 0 && current != null) {
            navController.navigate(PlayerRoute)
        }
    }

    // "Favorite DJ is live" notification tap → start playing that DJ's live stream.
    // This mirrors tapping a live card in the Live tab, defaulting to the high-quality
    // MP3 stream and reusing the existing PlaybackManager playTrack path.
    val openLiveArtist by mainActivity?.openLiveArtist
        ?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }
    LaunchedEffect(openLiveArtist) {
        val info = openLiveArtist ?: return@LaunchedEffect
        val artist = Artist(info.id, info.displayName, null, info.imageUrl)
        val liveTrack = Track(
            id = "live-${artist.id}",
            artistId = artist.id,
            artistName = artist.displayName,
            title = context.getString(R.string.live_notification_playing_title, artist.displayName),
            url = viewModel.liveStreamUrlHigh(artist.id),
            thumbnailUrl = artist.bestImageUrl,
            dateMillis = System.currentTimeMillis(),
            mimeType = MimeTypes.AUDIO_MPEG,
        )
        manager.playTrack(liveTrack)
        mainActivity?.consumeOpenLiveArtist()
        navController.navigate(PlayerRoute)
    }

    // On launch: if Single Artist mode is active, the front page starts on that
    // artist — the HomeRoute already renders only that artist's tracks. We must NOT
    // auto-play/resume anything here (entering this mode or launching in it must only
    // OPEN the artist, never start, pause, or replace what's playing). Resume is
    // available through the explicit "Continue" button on the artist screen.
    // The non-single path still resumes the last-played track on a normal cold start.
    val resumeStarted = remember { mutableStateOf(false) }
    val launchArtists = artistsState
    LaunchedEffect(launchArtists, singleArtistId) {
        if (resumeStarted.value) return@LaunchedEffect
        val success = launchArtists as? UiState.Success ?: return@LaunchedEffect
        resumeStarted.value = true
        val activeSingle = singleArtistId
        if (activeSingle != null) {
            val artist = success.data.find { it.id == activeSingle }
            if (artist != null) {
                selectedArtist = artist
                viewModel.loadTracks(artist)
                // No auto-play: the artist's tracks are shown by HomeRoute; resume is
                // only triggered by the explicit "Continue" button.
            }
        } else {
            val pt = viewModel.lastResumePoint()
            if (pt != null) {
                manager.play(pt.tracks, pt.index, pt.positionMs)
                navController.navigate(PlayerRoute)
            }
        }
    }

    val currentBackStack by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStack?.destination?.route
    val isPrimaryRoute = TopLevel.entries.any { currentRoute?.contains(it.route::class.qualifiedName ?: "") == true }
    val showBottomBar = isPrimaryRoute
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    CompositionLocalProvider(
        LocalDrawerState provides drawerState,
        LocalNavController provides navController,
    ) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = isPrimaryRoute,
            drawerContent = {
                ModalDrawerSheet(
                    drawerContainerColor = MaterialTheme.colorScheme.surface,
                ) {
                    DrawerHeader()
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    )
                    NavigationDrawerItem(
                        icon = {
                            Icon(
                                Icons.Filled.Settings,
                                contentDescription = "Settings",
                            )
                        },
                        label = { Text("Settings") },
                        selected = false,
                        onClick = {
                            uiScope.launch { drawerState.close() }
                            navController.navigate(SettingsRoute)
                        },
                        colors = NavigationDrawerItemDefaults.colors(
                            unselectedContainerColor = MaterialTheme.colorScheme.surface,
                        ),
                    )
                    NavigationDrawerItem(
                        icon = {
                            Icon(
                                Icons.Filled.Info,
                                contentDescription = "About",
                            )
                        },
                        label = { Text("About") },
                        selected = false,
                        onClick = {
                            uiScope.launch { drawerState.close() }
                            navController.navigate(AboutRoute)
                        },
                        colors = NavigationDrawerItemDefaults.colors(
                            unselectedContainerColor = MaterialTheme.colorScheme.surface,
                        ),
                    )
                }
            },
        ) {
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
                            is UiState.Success -> {
                                // Single Artist mode: the front page IS the pinned artist's full
                                // track list — no artist grid, no picker. Exit via the top-bar back.
                                val pinned = singleArtistId?.let { id -> s.data.find { it.id == id } }
                                if (pinned != null) {
                                    LaunchedEffect(pinned.id) { viewModel.loadTracks(pinned) }
                                    when (val t = tracksState) {
                                        is UiState.Loading -> Loading(Modifier.fillMaxSize())
                                        is UiState.Error -> Error(t.message, Modifier.fillMaxSize())
                                        is UiState.Success -> {
                                            // Offer explicit resume (option B) but never auto-play.
                                            var singleRpState by remember(pinned.id) { mutableStateOf<MainViewModel.ResumePoint?>(null) }
                                            LaunchedEffect(pinned.id) {
                                                singleRpState = viewModel.resumePointForArtist(pinned)
                                            }
                                            val singleRp = singleRpState
                                            ArtistDetailScreen(
                                                artist = pinned,
                                                tracks = t.data,
                                                favorites = favoriteTrackIds,
                                                currentTrackId = current?.id,
                                                isPlaying = isPlaying,
                                                onBack = { viewModel.setSingleArtist(null) },
                                                onTrackClick = { tracks, index ->
                                                    manager.play(tracks, index)
                                                    navController.navigate(PlayerRoute)
                                                },
                                                onToggleFavorite = { track -> viewModel.toggleFavoriteTrack(track) },
                                                singleArtistMode = true,
                                                onExitSingleArtist = { viewModel.setSingleArtist(null) },
                                                downloadManager = downloadManager,
                                                onDownloadMany = { tracks -> downloadManager.downloadAll(tracks) },
                                                resumeTitle = singleRp?.tracks?.getOrNull(singleRp.index)?.title,
                                                onResume = {
                                                    if (singleRp != null) {
                                                        manager.play(singleRp.tracks, singleRp.index, singleRp.positionMs)
                                                        navController.navigate(PlayerRoute)
                                                    }
                                                },
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        }
                                    }
                                } else ArtistListScreen(
                                    artists = s.data,
                                    favoriteArtistIds = favoriteArtistIds,
                                    sort = artistSort,
                                    sortAscending = sortAscending,
                                    singleArtistId = singleArtistId,
                                    isDark = isDark,
                                    liveArtists = liveArtists,
                                    layoutMode = artistListLayout.value,
                                    recentResumePoints = recentResumePoints,
                                    onSortChange = viewModel::setArtistSort,
                                    onArtistClick = { artist ->
                                        selectedArtist = artist
                                        viewModel.loadTracks(artist)
                                        navController.navigate(ArtistDetailRoute(artist.id, artist.displayName))
                                    },
                                    onLiveClick = { artist ->
                                        liveQualityArtist = artist
                                    },
                                    onContinueListeningClick = { rp ->
                                        manager.play(rp.tracks, rp.index, rp.positionMs)
                                        navController.navigate(PlayerRoute)
                                    },
                                    onToggleFavorite = { artist ->
                                        viewModel.toggleFavoriteArtist(artist)
                                    },
                                    onSetSingleArtist = { artist ->
                                        viewModel.setSingleArtist(artist?.id)
                                    },
                                    onToggleTheme = onToggleTheme,
                                    onToggleLayoutMode = viewModel::toggleArtistListLayout,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }

                    composable<ArtistDetailRoute> { backStackEntry ->
                        val route = backStackEntry.toRoute<ArtistDetailRoute>()
                        val artist = remember(route.artistId) {
                            selectedArtist ?: Artist(route.artistId, route.artistName, null, null)
                        }
                        LaunchedEffect(artist) { selectedArtist = artist }

                        // Discover (but don't auto-play) this artist's saved resume point.
                        var resumePoint by remember(route.artistId) {
                            mutableStateOf<MainViewModel.ResumePoint?>(null)
                        }
                        LaunchedEffect(route.artistId) {
                            resumePoint = viewModel.resumePointForArtist(artist)
                        }

                        when (val s = tracksState) {
                            is UiState.Loading -> Loading(Modifier.fillMaxSize())
                            is UiState.Error -> Error(s.message, Modifier.fillMaxSize())
                            is UiState.Success -> {
                                val rp = resumePoint
                                ArtistDetailScreen(
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
                                    downloadManager = downloadManager,
                                    onDownloadMany = { tracks -> downloadManager.downloadAll(tracks) },
                                    resumeTitle = rp?.tracks?.getOrNull(rp.index)?.title,
                                    onResume = {
                                        if (rp != null) {
                                            manager.play(rp.tracks, rp.index, rp.positionMs)
                                            navController.navigate(PlayerRoute)
                                        }
                                    },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }

                    composable<LiveRoute> {
                        // Always check the live server when the tab is opened.
                        LaunchedEffect(Unit) { viewModel.loadLive(forceRefresh = true) }
                        LiveNowScreen(
                            liveArtists = liveArtists,
                            isRefreshing = false,
                            onPlay = { artist ->
                                liveQualityArtist = artist
                            },
                            onOpenTwitch = { artist ->
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://twitch.tv/${artist.id}"))
                                )
                            },
                            onRefresh = { viewModel.loadLive(forceRefresh = true) },
                            modifier = Modifier.fillMaxSize(),
                        )
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
                                manager.playTrack(track)
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
                                // Open the artist's detail page WITHOUT auto-resuming playback —
                                // resume is offered as an explicit button there and only plays on tap.
                                navController.navigate(ArtistDetailRoute(artist.id, artist.displayName))
                            },
                            onTrackClick = { track ->
                                manager.playTrack(track)
                                navController.navigate(PlayerRoute)
                            },
                            onToggleFavoriteTrack = { viewModel.toggleFavoriteTrack(it) },
                            onToggleFavoriteArtist = { viewModel.toggleFavoriteArtist(it) },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    composable<LocalRoute> {
                        val downloaded by downloadManager.downloaded.collectAsStateWithLifecycle()
                        val progress by downloadManager.progress.collectAsStateWithLifecycle()
                        LocallyAvailableScreen(
                            downloaded = downloaded,
                            progress = progress,
                            downloadManager = downloadManager,
                            onTrackClick = { track ->
                                val localTracks = listOf(track)
                                manager.play(localTracks, 0)
                                navController.navigate(PlayerRoute)
                            },
                            onDelete = { trackId -> downloadManager.delete(trackId) },
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
                            sleepTimerMinutes = sleepTimerMinutes,
                            sleepTimerRemainingMs = sleepTimerRemainingMs,
                            onBack = { navController.popBackStack() },
                            onTogglePlay = { manager.togglePlayPause() },
                            onSeek = { ms -> manager.seekTo(ms) },
                            onNext = { manager.next() },
                            onPrev = { manager.previous() },
                            onSkipForward = { manager.skipForward(30_000L) },
                            onSkipBackward = { manager.skipBackward(30_000L) },
                            onToggleFavorite = { current?.let { viewModel.toggleFavoriteTrack(it) } },
                            onShare = { current?.let { shareTrack(context, it) } },
                            onStop = { manager.stop(); navController.popBackStack() },
                            onSetSleepTimer = { minutes ->
                                if (minutes != null) manager.setSleepTimer(minutes) else manager.cancelSleepTimer()
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    composable<SettingsRoute> {
                        SettingsScreen(
                            onBack = { navController.popBackStack() },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    composable<AboutRoute> {
                        AboutScreen(
                            onBack = { navController.popBackStack() },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerHeader() {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
    ) {
        Text(
            text = "STArchive",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "Player",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
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

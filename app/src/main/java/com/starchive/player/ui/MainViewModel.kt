package com.starchive.player.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.starchive.player.data.Artist
import com.starchive.player.data.Repository
import com.starchive.player.data.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

enum class ArtistSort { NAME, DATE }

class MainViewModel(private val repo: Repository) : ViewModel() {

    private val _tracks = MutableStateFlow<UiState<List<Track>>>(UiState.Loading)
    val tracks: StateFlow<UiState<List<Track>>> = _tracks.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<UiState<List<Track>>>(UiState.Success(emptyList()))
    val searchResults: StateFlow<UiState<List<Track>>> = _searchResults.asStateFlow()

    private val _artistSort = MutableStateFlow(ArtistSort.DATE)
    val artistSort: StateFlow<ArtistSort> = _artistSort.asStateFlow()

    private val _favoriteTrackObjects = MutableStateFlow<UiState<List<Track>>>(UiState.Success(emptyList()))
    val favoriteTrackObjects: StateFlow<UiState<List<Track>>> = _favoriteTrackObjects.asStateFlow()

    /** Last network failure while loading artists, so the UI can show a real error
        instead of sitting on Loading forever when the server is unreachable. */
    private val _artistsLoadError = MutableStateFlow<String?>(null)

    /** Artists exposed with their avatars; loading state derived from emptiness. */
    val artists: StateFlow<UiState<List<Artist>>> = combine(
        combine(repo.artists, _artistSort) { list, sort -> list to sort },
        repo.latestDateByArtist,
    ) { (list, sort), dates ->
        val sorted = when (sort) {
            ArtistSort.NAME -> list.sortedBy { it.displayName.lowercase() }
            ArtistSort.DATE -> list.sortedByDescending { dates[it.id] ?: 0L }
        }
        when {
            list.isEmpty() && _artistsLoadError.value != null ->
                UiState.Error(_artistsLoadError.value ?: "Could not load artists")
            list.isEmpty() -> UiState.Loading
            else -> UiState.Success(sorted)
        }
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState.Loading)

    val favoriteTracks: StateFlow<Set<String>> = repo.favoriteTracks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val favoriteArtists: StateFlow<Set<String>> = repo.favoriteArtists
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /** DJs that are live right now (from live.streambox.space), with avatars resolved. */
    val liveArtists: StateFlow<List<Artist>> = repo.liveArtists
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Playable HQ restream URL for a live DJ's Twitch channel. */
    fun liveStreamUrl(username: String): String = repo.liveStreamUrl(username)

    val recentSearches: StateFlow<List<String>> = repo.recentSearches
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun loadArtists(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _artistsLoadError.value = null
            runCatching { repo.loadArtists(forceRefresh) }
                .onFailure { e -> _artistsLoadError.value = e.message ?: "Could not load artists" }
            launch { repo.refreshAvatars() }
            launch { repo.loadLive() }
            launch { repo.loadLatestDates() }
        }
    }

    fun loadLive(forceRefresh: Boolean = false) {
        viewModelScope.launch { repo.loadLive(forceRefresh) }
    }

    fun setArtistSort(sort: ArtistSort) {
        _artistSort.value = sort
    }

    fun loadTracks(artist: Artist) {
        _tracks.value = UiState.Loading
        viewModelScope.launch {
            runCatching { repo.tracks(artist) }
                .onSuccess { _tracks.value = UiState.Success(it) }
                .onFailure { _tracks.value = UiState.Error(it.message ?: "Could not load tracks") }
        }
    }

    /** Resolves favorite track ids into full track objects (artwork, url, etc). */
    fun loadFavoriteTrackObjects() {
        viewModelScope.launch {
            _favoriteTrackObjects.value = UiState.Loading
            val ids = favoriteTracks.value
            runCatching { repo.favoriteTrackObjects(ids) }
                .onSuccess { _favoriteTrackObjects.value = UiState.Success(it) }
                .onFailure { _favoriteTrackObjects.value = UiState.Error(it.message ?: "Could not load favorites") }
        }
    }

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
        if (query.isBlank()) {
            _searchResults.value = UiState.Success(emptyList())
            return
        }
        viewModelScope.launch {
            _searchResults.value = UiState.Loading
            runCatching { repo.search(query) }
                .onSuccess { _searchResults.value = UiState.Success(it) }
                .onFailure { _searchResults.value = UiState.Error(it.message ?: "Search failed") }
        }
    }

    /** Records a committed search (e.g. submit / track tapped) into persisted history. */
    fun recordSearch(query: String) {
        if (query.isBlank()) return
        viewModelScope.launch { repo.recordSearch(query) }
    }

    fun clearSearchHistory() {
        viewModelScope.launch { repo.clearSearchHistory() }
    }

    fun toggleFavoriteTrack(track: Track) {
        viewModelScope.launch { repo.toggleFavoriteTrack(track) }
    }

    fun toggleFavoriteArtist(artist: Artist) {
        viewModelScope.launch { repo.toggleFavoriteArtist(artist) }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == MainViewModel::class.java)
            return MainViewModel(Repository(context)) as T
        }
    }
}

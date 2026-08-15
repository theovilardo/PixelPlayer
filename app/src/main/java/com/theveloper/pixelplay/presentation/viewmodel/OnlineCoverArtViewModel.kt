package com.theveloper.pixelplay.presentation.viewmodel

import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.coverart.AlbumArtStorage
import com.theveloper.pixelplay.data.coverart.CoverArtCandidate
import com.theveloper.pixelplay.data.coverart.CoverArtProviderStatus
import com.theveloper.pixelplay.data.coverart.CoverArtSize
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.data.repository.CoverArtSearchRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OnlineCoverArtUiState(
    val album: String = "",
    val artist: String = "",
    val isSearching: Boolean = false,
    val hasSearched: Boolean = false,
    val candidates: List<CoverArtCandidate> = emptyList(),
    /** Per catalog progress, so the user sees which ones are still running. */
    val providerStatuses: List<CoverArtProviderStatus> = emptyList(),
    @StringRes val errorRes: Int? = null,
    val downloadingCandidateId: String? = null,
    /** Candidates a size is still being read for, so the rest can say so. */
    val measuringCandidateIds: Set<String> = emptySet(),
    /** Set once a picked cover is cached; the UI consumes it and clears it. */
    val downloadedUri: Uri? = null,
    /** Album and artist the state was started for, so a new song starts clean. */
    val startedFor: Pair<String, String>? = null,
    val webSearchConfigured: Boolean = false,
    val isSearchingWeb: Boolean = false,
    /** Web results, kept apart so a re-run of the catalogs cannot drop them. */
    val webCandidates: List<CoverArtCandidate> = emptyList(),
    /** Set once the web has been searched for this query, match or not. */
    val webSearched: Boolean = false
) {
    /** Catalog matches first, then anything the web turned up. */
    val allCandidates: List<CoverArtCandidate> get() = candidates + webCandidates
}

/**
 * Drives the online cover art picker: searching catalogs and caching the image
 * the user picks so the existing cropper can open it.
 */
@HiltViewModel
class OnlineCoverArtViewModel @Inject constructor(
    private val coverArtSearchRepository: CoverArtSearchRepository,
    userPreferencesRepository: UserPreferencesRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnlineCoverArtUiState())
    val uiState: StateFlow<OnlineCoverArtUiState> = _uiState.asStateFlow()

    /**
     * Where a cover picked here will end up, so the screen doing the applying
     * says so rather than leaving it to a setting the user is not looking at.
     *
     * Deliberately not part of [OnlineCoverArtUiState], which is replaced
     * wholesale each time the picker opens for a different album: a setting
     * folded into it was reset there and never corrected, since a preference
     * only re-emits when it changes.
     *
     * Null until the store has answered -- a default would be a statement about
     * whether the user's files are about to be written to.
     */
    val albumArtStorage: StateFlow<AlbumArtStorage?> = userPreferencesRepository.albumArtStorageFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var searchJob: Job? = null
    private var webSearchJob: Job? = null
    private var downloadJob: Job? = null

    /** Probes run once per candidate, across every snapshot of a search. */
    private val probedCandidateIds = mutableSetOf<String>()
    private val probePermits = Semaphore(PROBE_CONCURRENCY)

    /**
     * Prefills the query from the song being edited and searches straight away.
     *
     * The view model outlives the sheet, so state is reset whenever the picker
     * opens for a different song and kept when it reopens for the same one.
     */
    fun start(album: String, artist: String) {
        val key = album to artist
        val isNewQuery = _uiState.value.startedFor != key

        if (isNewQuery) {
            searchJob?.cancel()
            webSearchJob?.cancel()
            downloadJob?.cancel()
            probedCandidateIds.clear()
            _uiState.value = OnlineCoverArtUiState(album = album, artist = artist, startedFor = key)
        } else {
            // The view model outlives the sheet, so a download that finished
            // after it was dismissed would still be sitting in state and would
            // reopen the cropper on the previous image. Drop it.
            downloadJob?.cancel()
            _uiState.update { it.copy(downloadedUri = null, downloadingCandidateId = null) }
        }

        // Re-read on every open: the key is entered in Settings and this view
        // model outlives the sheet, so an answer taken once would keep the
        // action hidden for a user who went and configured one.
        viewModelScope.launch {
            val configured = coverArtSearchRepository.isWebImageSearchAvailable()
            _uiState.update { it.copy(webSearchConfigured = configured) }
        }

        if (isNewQuery && (album.isNotBlank() || artist.isNotBlank())) {
            search()
        }
    }

    fun onAlbumChange(album: String) {
        _uiState.update { it.copy(album = album) }
    }

    fun onArtistChange(artist: String) {
        _uiState.update { it.copy(artist = artist) }
    }

    fun search() {
        val album = _uiState.value.album.trim()
        val artist = _uiState.value.artist.trim()
        if (album.isEmpty() && artist.isEmpty()) return

        searchJob?.cancel()
        webSearchJob?.cancel()
        probedCandidateIds.clear()
        searchJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isSearching = true,
                    errorRes = null,
                    candidates = emptyList(),
                    providerStatuses = emptyList(),
                    // Probes in flight go down with the job being replaced, so
                    // a candidate mid-probe would otherwise read as measuring
                    // for as long as the picker stays open.
                    measuringCandidateIds = emptySet(),
                    // A new query invalidates the previous web results, and the
                    // user has to ask for the new ones: each request is metered.
                    webCandidates = emptyList(),
                    webSearched = false,
                    isSearchingWeb = false
                )
            }

            coverArtSearchRepository.searchStreaming(album = album, artist = artist)
                .collect { snapshot ->
                    _uiState.update { current ->
                        current.copy(
                            // A catalog answering later must not wipe the sizes
                            // already measured for results that are on screen.
                            candidates = snapshot.candidates.withKnownSizes(current.candidates),
                            providerStatuses = snapshot.statuses,
                            isSearching = !snapshot.isComplete,
                            hasSearched = snapshot.isComplete,
                            errorRes = when {
                                snapshot.failure != null -> R.string.cover_art_search_error
                                else -> null
                            }
                        )
                    }
                    probeSizes(snapshot.candidates)
                }
        }
    }

    /**
     * Searches the web for this album, on the user's explicit request.
     *
     * Image engines meter by request against a monthly allowance, so this is
     * never run for them: it is the last resort for an album no catalog carries,
     * and its results land below the catalog matches they failed to provide.
     */
    fun searchWeb() {
        val state = _uiState.value
        if (state.isSearchingWeb) return
        val album = state.album.trim()
        val artist = state.artist.trim()
        if (album.isEmpty() && artist.isEmpty()) return

        webSearchJob?.cancel()
        webSearchJob = viewModelScope.launch {
            _uiState.update { it.copy(isSearchingWeb = true, errorRes = null) }

            val result = coverArtSearchRepository.searchWebImages(album = album, artist = artist)
            _uiState.update { current ->
                result.fold(
                    onSuccess = { found ->
                        current.copy(
                            isSearchingWeb = false,
                            webSearched = true,
                            webCandidates = found.withKnownSizes(current.webCandidates)
                        )
                    },
                    onFailure = {
                        // webSearched is what hides the action, so a failed
                        // search leaves it alone: otherwise a dropped connection
                        // spends the user's one offer of it.
                        current.copy(
                            isSearchingWeb = false,
                            errorRes = R.string.cover_art_search_error
                        )
                    }
                )
            }
            probeSizes(result.getOrDefault(emptyList()))
        }
    }

    private fun List<CoverArtCandidate>.withSize(
        candidateId: String,
        size: CoverArtSize
    ): List<CoverArtCandidate> =
        map { existing -> if (existing.id == candidateId) existing.copy(size = size) else existing }

    private fun List<CoverArtCandidate>.withKnownSizes(
        previous: List<CoverArtCandidate>
    ): List<CoverArtCandidate> {
        if (previous.isEmpty()) return this
        val measured = previous.mapNotNull { candidate ->
            candidate.size?.takeIf { it.measured }?.let { candidate.id to it }
        }.toMap()
        if (measured.isEmpty()) return this
        return map { candidate -> measured[candidate.id]?.let { candidate.copy(size = it) } ?: candidate }
    }

    /**
     * Measures the real resolution and weight of the first results.
     *
     * Catalogs report neither, and each probe reads only a small prefix of the
     * image, folded into the grid as it arrives.
     *
     * Runs as a child of the search that produced the candidates, so a replaced
     * search takes its probes with it -- and per snapshot, since one job would
     * leave earlier batches measuring results nobody is looking at.
     */
    private fun CoroutineScope.probeSizes(candidates: List<CoverArtCandidate>) {
        val unmeasured = candidates
            .take(PROBE_LIMIT)
            .filter { candidate -> probedCandidateIds.add(candidate.id) }
        if (unmeasured.isEmpty()) return

        // Only the first PROBE_LIMIT are measured and some catalogs never state
        // a size, so the rest can say "unknown" rather than promise a number.
        val pending = unmeasured.mapTo(mutableSetOf()) { it.id }
        _uiState.update { it.copy(measuringCandidateIds = it.measuringCandidateIds + pending) }

        launch {
            unmeasured.map { candidate ->
                async {
                    val size = probePermits.withPermit {
                        coverArtSearchRepository.probeSize(candidate)
                    }

                    _uiState.update { current ->
                        current.copy(
                            candidates = size?.let { current.candidates.withSize(candidate.id, it) }
                                ?: current.candidates,
                            webCandidates = size?.let { current.webCandidates.withSize(candidate.id, it) }
                                ?: current.webCandidates,
                            measuringCandidateIds = current.measuringCandidateIds - candidate.id
                        )
                    }
                }
            }.awaitAll()
        }
    }

    fun onCandidateSelected(candidate: CoverArtCandidate) {
        if (_uiState.value.downloadingCandidateId != null) return

        downloadJob = viewModelScope.launch {
            _uiState.update { it.copy(downloadingCandidateId = candidate.id, errorRes = null) }
            val result = coverArtSearchRepository.downloadCandidate(candidate)
            _uiState.update { current ->
                result.fold(
                    onSuccess = { uri ->
                        current.copy(downloadingCandidateId = null, downloadedUri = uri)
                    },
                    onFailure = {
                        current.copy(
                            downloadingCandidateId = null,
                            errorRes = R.string.cover_art_search_download_error
                        )
                    }
                )
            }
        }
    }

    fun onDownloadedUriHandled() {
        _uiState.update { it.copy(downloadedUri = null) }
    }

    private companion object {
        /** Results measured per search; the rest keep their nominal size. */
        const val PROBE_LIMIT = 12
        const val PROBE_CONCURRENCY = 4
    }
}

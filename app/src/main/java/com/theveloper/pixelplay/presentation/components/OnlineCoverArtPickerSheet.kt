package com.theveloper.pixelplay.presentation.components

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.theveloper.pixelplay.ui.theme.GoogleSansRounded
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.coverart.AlbumArtStorage
import com.theveloper.pixelplay.data.coverart.CoverArtCandidate
import com.theveloper.pixelplay.data.coverart.CoverArtProviderStatus
import com.theveloper.pixelplay.data.coverart.CoverArtSource
import java.util.Locale
import com.theveloper.pixelplay.presentation.viewmodel.OnlineCoverArtUiState
import com.theveloper.pixelplay.presentation.viewmodel.OnlineCoverArtViewModel

/**
 * Lets the user search online catalogs for a cover and pick one.
 *
 * The picked image is downloaded to the cache and handed back as a local URI,
 * so it goes through the same cropper as an image picked from the gallery.
 *
 * @param noteText Optional line stating what the pick will affect, e.g. how many
 * tracks of the album the cover will be written to. Where the cover is kept is
 * stated alongside it either way, since that decides whether the user's own
 * files are about to be written to.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineCoverArtPickerSheet(
    visible: Boolean,
    initialAlbum: String,
    initialArtist: String,
    onDismiss: () -> Unit,
    onCoverDownloaded: (Uri) -> Unit,
    noteText: String? = null,
    viewModel: OnlineCoverArtViewModel = hiltViewModel()
) {
    // A dialog window rather than a sheet: covers are judged by looking at them,
    // so this wants the whole screen. The transition state, rather than `visible`
    // alone, keeps it composed long enough to animate away.
    val transitionState = remember { MutableTransitionState(false) }
    transitionState.targetState = visible

    if (!transitionState.currentState && !transitionState.targetState) return

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val albumArtStorage by viewModel.albumArtStorage.collectAsStateWithLifecycle()

    LaunchedEffect(initialAlbum, initialArtist) {
        viewModel.start(album = initialAlbum, artist = initialArtist)
    }

    LaunchedEffect(state.downloadedUri) {
        state.downloadedUri?.let { uri ->
            onCoverDownloaded(uri)
            viewModel.onDownloadedUriHandled()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        AnimatedVisibility(
            visibleState = transitionState,
            enter = slideInVertically(initialOffsetY = { it / 6 }) + fadeIn(animationSpec = tween(220)),
            exit = slideOutVertically(targetOffsetY = { it / 6 }) + fadeOut(animationSpec = tween(200))
        ) {
            CoverArtPickerContent(
                state = state,
                noteText = noteText,
                albumArtStorage = albumArtStorage,
                onAlbumChange = viewModel::onAlbumChange,
                onArtistChange = viewModel::onArtistChange,
                onSearch = viewModel::search,
                onSearchWeb = viewModel::searchWeb,
                onCandidateSelected = viewModel::onCandidateSelected,
                onDismiss = onDismiss
            )
        }
    }
}

/**
 * Everything the picker shows, given a state rather than a view model.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CoverArtPickerContent(
    state: OnlineCoverArtUiState,
    noteText: String?,
    albumArtStorage: AlbumArtStorage?,
    onAlbumChange: (String) -> Unit,
    onArtistChange: (String) -> Unit,
    onSearch: () -> Unit,
    onSearchWeb: () -> Unit,
    onCandidateSelected: (CoverArtCandidate) -> Unit,
    onDismiss: () -> Unit
) {
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    var isEditingQuery by remember { mutableStateOf(false) }

    // Said where the picking happens: the setting was made once, elsewhere, and
    // decides whether the user's own files are about to be written to. Nothing
    // is claimed until it has actually been read.
    val destinationText = when (albumArtStorage) {
        AlbumArtStorage.AUDIO_FILES -> stringResource(R.string.cover_art_search_destination_files)
        AlbumArtStorage.APP_ONLY -> stringResource(R.string.cover_art_search_destination_app)
        null -> null
    }
    val noteLine = listOfNotNull(noteText, destinationText).joinToString(" ")

    // The web engine joins the row only once it has been asked for, so an
    // untouched search does not advertise a source it did not query.
    val statuses = state.providerStatuses + listOfNotNull(
        if (state.isSearchingWeb || state.webSearched) {
            CoverArtProviderStatus(
                source = CoverArtSource.WEB_IMAGE_SEARCH,
                isSearching = state.isSearchingWeb,
                resultCount = state.webCandidates.size
            )
        } else {
            null
        }
    )

    // The catalogs register a moment after the sheet opens, and a lazy row holds
    // its anchored item still by scrolling itself to the end. So the row stays
    // empty until they are in it, and pinning keys off the catalogs alone -- a
    // web search changing the row must not jump it back under the user's finger.
    val chipsState = rememberLazyListState()
    LaunchedEffect(state.album, state.artist, state.providerStatuses.size) {
        chipsState.scrollToItem(0)
    }

    // Results are grouped under their source so a chip has somewhere to jump to,
    // and so a run of covers from one catalog reads as one catalog's opinion.
    val groups = remember(state.candidates, state.webCandidates) {
        (state.candidates + state.webCandidates)
            .groupBy { it.source }
            .toList()
    }
    // Where each group's header lands in the grid, counting the full-width
    // header as one item, so a chip can scroll straight to it.
    val groupStartIndex = remember(groups) {
        var index = 0
        buildMap {
            groups.forEach { (source, candidates) ->
                put(source, index)
                index += 1 + candidates.size
            }
        }
    }

    // The covers share their grid with the rows above them, so an index into
    // the groups only points at the right cover once those rows are counted.
    val leadingItemCount = 1 +
        // The note always carries the destination, so it is always drawn.
        1 +
        (if (isEditingQuery) 2 else 0) +
        1 +
        (if (state.errorRes != null && groups.isNotEmpty()) 1 else 0)

    // Searching again is the keyboard's action key: the sheet already searched
    // on open, so a button of its own would sit there unused for every album
    // the catalogs get right.
    val searchAgain = {
        keyboardController?.hide()
        isEditingQuery = false
        onSearch()
    }

    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val isKeyboardVisible by remember { derivedStateOf { imeInsets.getBottom(density) > 0 } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        modifier = Modifier.padding(start = 10.dp),
                        text = stringResource(R.string.cover_art_search_title),
                        fontFamily = GoogleSansRounded,
                        style = MaterialTheme.typography.displaySmall
                    )
                },
                navigationIcon = {
                    // Closing is the only way out: picking a cover is the commit,
                    // so there is no Save to pair a Cancel with down at the
                    // bottom the way the tag editor has.
                    FilledTonalIconButton(
                        modifier = Modifier.padding(start = 10.dp),
                        onClick = onDismiss,
                        shape = CircleShape
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.common_cancel)
                        )
                    }
                },
                actions = {
                    FilledTonalIconButton(
                        modifier = Modifier.padding(end = 10.dp),
                        onClick = { isEditingQuery = !isEditingQuery },
                        shape = CircleShape
                    ) {
                        Icon(
                            imageVector = if (isEditingQuery) Icons.Rounded.Search else Icons.Rounded.Edit,
                            contentDescription = stringResource(
                                if (isEditingQuery) {
                                    R.string.cover_art_search_edit_query_done
                                } else {
                                    R.string.cover_art_search_edit_query
                                }
                            )
                        )
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.statusBars
    ) { innerPadding ->
        val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

        // Everything above the covers rides in the grid as a full-width row, so
        // the screen contains exactly one thing that scrolls.
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = 104.dp),
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            // The gutter is the grid's, so every row lines up without each one
            // repeating it. The chip row opts back out below.
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = if (isKeyboardVisible) 8.dp else navBarBottom + 24.dp,
                start = 16.dp,
                end = 16.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "query") {
                // Opened from an album, so the terms are already known and read
                // as a line: two open text fields would cost about two rows of
                // covers. The fields sit behind the app bar's action, for tags
                // the catalogs do not agree with.
                val queryLine = listOf(state.album, state.artist)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                if (queryLine.isNotEmpty()) {
                    Text(
                        text = queryLine,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            item(span = { GridItemSpan(maxLineSpan) }, key = "note") {
                Text(
                    text = noteLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (isEditingQuery) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "field-album") {
                    OutlinedTextField(
                        value = state.album,
                        onValueChange = onAlbumChange,
                        label = { Text(stringResource(R.string.cover_art_search_field_album)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { searchAgain() }),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }, key = "field-artist") {
                    OutlinedTextField(
                        value = state.artist,
                        onValueChange = onArtistChange,
                        label = { Text(stringResource(R.string.cover_art_search_field_artist)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { searchAgain() }),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            item(span = { GridItemSpan(maxLineSpan) }, key = "chips") {
                SourceChips(
                    modifier = Modifier.bleedHorizontally(20.dp),
                    listState = chipsState,
                    statuses = statuses,
                    // Never automatic: each request is metered against the
                    // user's own allowance, so it takes a deliberate tap.
                    showWebSearchAction = state.providerStatuses.isNotEmpty() &&
                        state.webSearchConfigured &&
                        !state.webSearched && !state.isSearchingWeb,
                    webSearchEnabled = !state.isSearching &&
                        (state.album.isNotBlank() || state.artist.isNotBlank()),
                    onSearchWeb = onSearchWeb,
                    onJumpToSource = { source ->
                        groupStartIndex[source]?.let { index ->
                            scope.launch {
                                gridState.animateScrollToItem(leadingItemCount + index)
                            }
                        }
                    }
                )
            }

            // The results own this message, so it only speaks when there are
            // none -- a failed download happens with results still on screen.
            state.errorRes?.takeIf { groups.isNotEmpty() }?.let { errorRes ->
                item(span = { GridItemSpan(maxLineSpan) }, key = "error") {
                    Text(
                        text = stringResource(errorRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            coverArtResults(
                state = state,
                groups = groups,
                onCandidateSelected = onCandidateSelected
            )
        }
    }
}

/**
 * Lets one item ignore the horizontal padding its lazy container applies to
 * every item.
 *
 * Content padding is the right place for a gutter every row should share, but a
 * row that scrolls sideways wants the opposite: its contents inset, its track
 * running to the edges, so chips leaving the screen leave from the edge rather
 * than stopping at a margin. This measures [padding] wider than it was offered
 * and draws itself back over the gutter on both sides.
 */
private fun Modifier.bleedHorizontally(padding: Dp) = layout { measurable, constraints ->
    // Only meaningful against a bounded width; there is no gutter to escape
    // from when the parent is not offering one.
    if (constraints.maxWidth == Constraints.Infinity) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

    val inset = padding.roundToPx()
    val bleed = inset * 2
    val placeable = measurable.measure(
        constraints.copy(
            minWidth = constraints.minWidth + bleed,
            maxWidth = constraints.maxWidth + bleed
        )
    )
    layout(placeable.width - bleed, placeable.height) {
        placeable.place(-inset, 0)
    }
}

/**
 * One chip per source: spinner while it is being queried, result count once it
 * has answered, a warning when it failed. Catalogs answer seconds apart, so
 * without this the grid looks finished as soon as the fastest one lands.
 *
 * Tapping a chip scrolls to that source's results, which is the quickest way
 * past a catalog that answered with a dozen near misses. The row scrolls rather
 * than wrapping: "Cover Art Archive" and "Web search" do not fit beside the
 * others on a phone, and a second line of chrome costs a row of covers, which
 * is what the sheet is for.
 */
@Composable
private fun SourceChips(
    modifier: Modifier = Modifier,
    listState: LazyListState,
    statuses: List<CoverArtProviderStatus>,
    showWebSearchAction: Boolean,
    webSearchEnabled: Boolean,
    onSearchWeb: () -> Unit,
    onJumpToSource: (CoverArtSource) -> Unit
) {
    if (statuses.isEmpty() && !showWebSearchAction) return

    LazyRow(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        // The row runs edge to edge and insets its contents instead, so the
        // first chip lines up with everything above it while the rest leave
        // from the screen edge rather than from a padding line.
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        statuses.forEach { status ->
            item(key = status.source.name) {
                val hasResults = !status.isSearching && !status.failed && status.resultCount > 0
                // A chip reads as one thing, not as a label beside a count: read
                // out piecemeal, a failed catalog names itself and then a number.
                val chipDescription = when {
                    status.isSearching -> stringResource(
                        R.string.cover_art_search_cd_catalog_searching,
                        status.source.label
                    )
                    status.failed -> stringResource(
                        R.string.cover_art_search_cd_catalog_failed,
                        status.source.label
                    )
                    else -> stringResource(
                        R.string.cover_art_search_cd_catalog_results,
                        status.source.label,
                        status.resultCount
                    )
                }
                AssistChip(
                    onClick = { onJumpToSource(status.source) },
                    enabled = hasResults,
                    label = {
                        Text(
                            text = if (hasResults) {
                                "${status.source.label} · ${status.resultCount}"
                            } else {
                                status.source.label
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    leadingIcon = when {
                        status.isSearching -> {
                            {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(AssistChipDefaults.IconSize),
                                    strokeWidth = 2.dp
                                )
                            }
                        }
                        // A shape as well as a colour, so the one state that
                        // means "nothing here" does not rest on colour alone.
                        status.failed -> {
                            {
                                Icon(
                                    imageVector = Icons.Rounded.ErrorOutline,
                                    contentDescription = null,
                                    modifier = Modifier.size(AssistChipDefaults.IconSize)
                                )
                            }
                        }
                        else -> null
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        disabledLeadingIconContentColor = if (status.failed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    ),
                    modifier = Modifier.semantics { contentDescription = chipDescription }
                )
            }
        }

        if (showWebSearchAction) {
            item(key = "web-search-action") {
                AssistChip(
                    onClick = onSearchWeb,
                    enabled = webSearchEnabled,
                    label = {
                        Text(
                            text = stringResource(R.string.cover_art_search_web_action),
                            maxLines = 1
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Public,
                            contentDescription = null,
                            modifier = Modifier.size(AssistChipDefaults.IconSize)
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        leadingIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ),
                    border = null
                )
            }
        }
    }
}

/**
 * The covers themselves, and whatever stands in for them: a spinner while the
 * catalogs are still answering, or a line saying there was nothing.
 *
 * Emitted into the caller's grid rather than owning one, so the sheet has a
 * single scrolling surface and the header above the covers scrolls away with
 * them.
 */
private fun LazyGridScope.coverArtResults(
    state: OnlineCoverArtUiState,
    groups: List<Pair<CoverArtSource, List<CoverArtCandidate>>>,
    onCandidateSelected: (CoverArtCandidate) -> Unit
) {
    // Results are drawn as soon as the first catalog answers, even while the
    // slower ones are still running, so the grid comes before the spinner.
    if (groups.isNotEmpty()) {
        groups.forEach { (source, candidates) ->
            item(
                span = { GridItemSpan(maxLineSpan) },
                key = "header-${source.name}"
            ) {
                Text(
                    text = source.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            items(items = candidates, key = { it.id }) { candidate ->
                CoverArtResultItem(
                    candidate = candidate,
                    isDownloading = state.downloadingCandidateId == candidate.id,
                    isMeasuring = candidate.id in state.measuringCandidateIds,
                    onClick = { onCandidateSelected(candidate) }
                )
            }
        }
        return
    }

    item(span = { GridItemSpan(maxLineSpan) }, key = "results-placeholder") {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 200.dp),
            contentAlignment = Alignment.Center
        ) {
            when {
                state.isSearching || state.isSearchingWeb -> CircularProgressIndicator()
                state.errorRes != null -> ResultsMessage(text = stringResource(state.errorRes))
                state.hasSearched -> ResultsMessage(text = stringResource(R.string.cover_art_search_empty))
                else -> ResultsMessage(text = stringResource(R.string.cover_art_search_idle))
            }
        }
    }
}

@Composable
private fun ResultsMessage(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 16.dp)
    )
}

@Composable
private fun CoverArtResultItem(
    candidate: CoverArtCandidate,
    isDownloading: Boolean,
    isMeasuring: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier.clickable(
            enabled = !isDownloading,
            role = Role.Button,
            onClick = onClick
        ),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center
        ) {
            SmartImage(
                model = candidate.thumbnailUrl,
                contentDescription = stringResource(
                    R.string.cover_art_search_cd_result,
                    candidate.albumTitle,
                    candidate.artistName
                ),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                placeholderResId = R.drawable.rounded_music_note_24,
                errorResId = R.drawable.rounded_broken_image_24
            )

            if (isDownloading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                }
            }
        }

        Text(
            text = candidate.albumTitle,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (candidate.artistName.isNotBlank()) {
            Text(
                text = candidate.artistName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = candidateDetails(candidate, isMeasuring),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Builds the "1000 × 1000 · 148 KB · Deezer" line under a result.
 *
 * The resolution reads as approximate until the image itself has been measured,
 * because up to that point it is only what the catalog promises to serve.
 */
@Composable
private fun candidateDetails(candidate: CoverArtCandidate, isMeasuring: Boolean): String {
    val size = candidate.size
    val resolution = when {
        size == null && isMeasuring -> stringResource(R.string.cover_art_search_measuring)
        size == null -> stringResource(R.string.cover_art_search_size_unknown)
        size.measured -> stringResource(
            R.string.cover_art_search_size_measured,
            size.width,
            size.height
        )

        else -> stringResource(
            R.string.cover_art_search_size_nominal,
            size.width,
            size.height
        )
    }

    return listOfNotNull(
        resolution,
        size?.byteCount?.let { formatByteCount(it) },
        candidate.source.label
    ).joinToString(" · ")
}

private fun formatByteCount(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1048576.0)
    bytes >= 1024L -> "${bytes / 1024L} KB"
    else -> "$bytes B"
}

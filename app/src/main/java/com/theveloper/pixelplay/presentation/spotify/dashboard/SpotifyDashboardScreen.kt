package com.theveloper.pixelplay.presentation.spotify.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theveloper.pixelplay.data.database.SpotifyPlaylistEntity
import com.theveloper.pixelplay.ui.theme.GoogleSansRounded

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifyDashboardScreen(
    viewModel: SpotifyDashboardViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
    val syncMessage by viewModel.syncMessage.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Spotify",
                        fontFamily = GoogleSansRounded,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    FilledTonalIconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            viewModel.logout()
                            onBack()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Logout,
                            contentDescription = "Logout"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            )
        }
    ) { padding ->
        SpotifyDashboardContent(
            playlists = playlists,
            displayName = viewModel.displayName,
            isSyncing = isSyncing,
            syncMessage = syncMessage,
            onSyncLibrary = viewModel::syncLibrary,
            onSyncPlaylists = viewModel::syncPlaylists,
            onSyncPlaylist = viewModel::syncPlaylist,
            onDeletePlaylist = viewModel::deletePlaylist,
            modifier = Modifier.padding(padding)
        )
    }
}

@Composable
private fun SpotifyDashboardContent(
    playlists: List<SpotifyPlaylistEntity>,
    displayName: String?,
    isSyncing: Boolean,
    syncMessage: String?,
    onSyncLibrary: () -> Unit,
    onSyncPlaylists: () -> Unit,
    onSyncPlaylist: (String) -> Unit,
    onDeletePlaylist: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SpotifyAccountCard(
                displayName = displayName ?: "Spotify",
                playlistCount = playlists.size
            )
        }

        item {
            AnimatedVisibility(
                visible = syncMessage != null || isSyncing,
                enter = slideInVertically() + fadeIn(),
                exit = fadeOut()
            ) {
                SpotifySyncBanner(
                    isSyncing = isSyncing,
                    message = syncMessage ?: "Working..."
                )
            }
        }

        item {
            SpotifyActionsCard(
                isSyncing = isSyncing,
                onSyncLibrary = onSyncLibrary,
                onSyncPlaylists = onSyncPlaylists
            )
        }

        item {
            Text(
                text = "Playlists",
                style = MaterialTheme.typography.titleMedium,
                fontFamily = GoogleSansRounded,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        if (playlists.isEmpty()) {
            item {
                SpotifyEmptyState()
            }
        } else {
            items(
                items = playlists,
                key = { it.id }
            ) { playlist ->
                SpotifyPlaylistRow(
                    playlist = playlist,
                    isSyncing = isSyncing,
                    onSync = { onSyncPlaylist(playlist.id) },
                    onDelete = { onDeletePlaylist(playlist.id) }
                )
            }
        }
    }
}

@Composable
private fun SpotifyAccountCard(
    displayName: String,
    playlistCount: Int
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1DB954)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = Color.White
                )
            }

            Spacer(Modifier.width(16.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = GoogleSansRounded,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "$playlistCount playlists synced",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = GoogleSansRounded,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Playback uses the Spotify app",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = GoogleSansRounded,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SpotifySyncBanner(
    isSyncing: Boolean,
    message: String
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSyncing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(12.dp))
            }

            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = GoogleSansRounded
            )
        }
    }
}

@Composable
private fun SpotifyActionsCard(
    isSyncing: Boolean,
    onSyncLibrary: () -> Unit,
    onSyncPlaylists: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onSyncLibrary,
                enabled = !isSyncing,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.LibraryMusic, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Sync liked songs")
            }

            FilledTonalButton(
                onClick = onSyncPlaylists,
                enabled = !isSyncing,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.PlaylistPlay, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Sync playlists")
            }
        }
    }
}

@Composable
private fun SpotifyPlaylistRow(
    playlist: SpotifyPlaylistEntity,
    isSyncing: Boolean,
    onSync: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(Color(0xFF1DB954).copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlaylistPlay,
                    contentDescription = null,
                    tint = Color(0xFF1DB954)
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = GoogleSansRounded,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${playlist.songCount} songs",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = GoogleSansRounded,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(
                onClick = onSync,
                enabled = !isSyncing
            ) {
                Icon(Icons.Rounded.Sync, contentDescription = "Sync playlist")
            }

            IconButton(
                onClick = onDelete,
                enabled = !isSyncing
            ) {
                Icon(Icons.Rounded.Delete, contentDescription = "Delete cached playlist")
            }
        }
    }
}

@Composable
private fun SpotifyEmptyState() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Rounded.PlaylistPlay,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "No Spotify playlists synced yet",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = GoogleSansRounded,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
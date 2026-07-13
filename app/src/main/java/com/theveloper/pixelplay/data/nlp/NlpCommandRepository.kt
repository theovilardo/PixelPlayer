package com.theveloper.pixelplay.data.nlp

import android.content.Context
import android.util.Log
import com.theveloper.pixelplay.data.database.MusicDao
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.preferences.PlaylistPreferencesRepository
import com.theveloper.pixelplay.data.repository.MusicRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NlpCommandRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val musicRepository: MusicRepository,
    private val musicDao: MusicDao,
    private val playlistPreferencesRepository: PlaylistPreferencesRepository
) {

    companion object {
        private const val TAG = "NlpCommandRepository"
    }

    suspend fun execute(intent: NlpCommandIntent): NlpCommandResult = withContext(Dispatchers.IO) {
        when (intent) {
            is NlpCommandIntent.CreatePlaylist -> createPlaylistByTarget(intent)
            is NlpCommandIntent.DeleteArtist   -> resolveDeleteArtist(intent)
            is NlpCommandIntent.CategorizeGenre -> categorizeByGenre(intent)
            is NlpCommandIntent.Unknown         -> NlpCommandResult.Error(
                "Sorry, I didn't understand that command. Try:\n" +
                "• \"create playlist of [artist]\"\n" +
                "• \"delete artist [name]\"\n" +
                "• \"categorize songs by genre [genre]\""
            )
        }
    }

    private suspend fun createPlaylistByTarget(intent: NlpCommandIntent.CreatePlaylist): NlpCommandResult {
        val allSongs = musicRepository.getAllSongsOnce()
        if (allSongs.isEmpty()) {
            return NlpCommandResult.Error("Your library is empty. Add some songs first.")
        }

        val allArtistNames = allSongs.map { it.artist }.distinct()
        val allGenres = allSongs.mapNotNull { it.genre }.distinct()

        val matchingSongs = mutableListOf<Song>()
        val resolvedTargets = mutableListOf<String>()

        for (query in intent.targetQueries) {
            val matchedArtist = NlpFuzzyMatcher.findBestMatch(query, allArtistNames)
            if (matchedArtist != null) {
                val songs = allSongs.filter { it.artist.equals(matchedArtist, ignoreCase = true) }
                matchingSongs.addAll(songs)
                resolvedTargets.add(matchedArtist)
            } else {
                val matchedGenre = NlpFuzzyMatcher.findBestMatch(query, allGenres)
                if (matchedGenre != null) {
                    val songs = allSongs.filter { it.genre.equals(matchedGenre, ignoreCase = true) }
                    matchingSongs.addAll(songs)
                    resolvedTargets.add(matchedGenre)
                }
            }
        }

        if (matchingSongs.isEmpty()) {
            return NlpCommandResult.Error(
                "No artists or genres found matching: ${intent.targetQueries.joinToString(", ")}. " +
                "Check the spelling."
            )
        }

        val uniqueSongs = matchingSongs.distinctBy { it.id }
        val songIds = uniqueSongs.map { it.id }
        
        playlistPreferencesRepository.createPlaylist(
            name = intent.playlistName,
            songIds = songIds
        )

        return NlpCommandResult.Success(
            "✓ Playlist \"${intent.playlistName}\" created with ${uniqueSongs.size} songs " +
            "(matched: ${resolvedTargets.joinToString(", ")})."
        )
    }

    private suspend fun resolveDeleteArtist(intent: NlpCommandIntent.DeleteArtist): NlpCommandResult {
        val allSongs = musicRepository.getAllSongsOnce()
        val allArtistNames = allSongs.map { it.artist }.distinct()

        val matchingSongs = mutableListOf<Song>()
        val resolvedArtists = mutableListOf<String>()

        for (query in intent.targetQueries) {
            val matchedArtist = NlpFuzzyMatcher.findBestMatch(query, allArtistNames)
            if (matchedArtist != null) {
                val songs = allSongs.filter { it.artist.equals(matchedArtist, ignoreCase = true) }
                matchingSongs.addAll(songs)
                resolvedArtists.add(matchedArtist)
            }
        }

        if (matchingSongs.isEmpty()) {
            return NlpCommandResult.Error(
                "No artists found matching: ${intent.targetQueries.joinToString(", ")}. " +
                "Check the spelling."
            )
        }

        val uniqueSongs = matchingSongs.distinctBy { it.id }

        // Only delete local songs (filter out cloud providers)
        val localSongPaths = uniqueSongs
            .filter { it.path.isNotBlank() && !it.contentUriString.startsWith("telegram://")
                    && !it.contentUriString.startsWith("netease://")
                    && !it.contentUriString.startsWith("gdrive://")
                    && !it.contentUriString.startsWith("qqmusic://")
                    && !it.contentUriString.startsWith("navidrome://")
                    && !it.contentUriString.startsWith("jellyfin://") }
            .map { it.path }

        val artistListStr = resolvedArtists.joinToString(" & ")
        return NlpCommandResult.PendingConfirmation(
            message = "⚠️ This will permanently delete ${uniqueSongs.size} song(s) by " +
                      "\"$artistListStr\" from your device. This action cannot be undone.",
            songFilePaths = localSongPaths,
            confirmedIntent = intent.copy(targetQueries = resolvedArtists)
        )
    }

    suspend fun executeDeleteArtist(
        songFilePaths: List<String>,
        artistName: String
    ): NlpCommandResult = withContext(Dispatchers.IO) {
        var deletedFiles = 0
        var failedFiles = 0

        for (path in songFilePaths) {
            if (path.isBlank()) continue
            try {
                val file = File(path)
                if (file.exists()) {
                    if (file.delete()) {
                        deletedFiles++
                        Log.d(TAG, "Deleted file: $path")
                    } else {
                        failedFiles++
                        Log.w(TAG, "Could not delete file: $path")
                    }
                }
            } catch (e: Exception) {
                failedFiles++
                Log.e(TAG, "Error deleting file: $path", e)
            }
        }

        val resolvedArtists = artistName.split(" & ")

        try {
            val allSongs = musicRepository.getAllSongsOnce()
            val artistSongIds = allSongs
                .filter { song -> resolvedArtists.any { it.equals(song.artist, ignoreCase = true) } }
                .map { it.id.toLongOrNull() }
                .filterNotNull()

            if (artistSongIds.isNotEmpty()) {
                musicDao.deleteSongsAndRelatedData(artistSongIds)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing DB rows for artists: $artistName", e)
        }

        val message = buildString {
            append("✓ Deleted $deletedFiles file(s) for \"$artistName\".")
            if (failedFiles > 0) {
                append("\n⚠️ $failedFiles file(s) could not be removed (check app permissions).")
            }
        }

        NlpCommandResult.Success(message)
    }

    private suspend fun categorizeByGenre(intent: NlpCommandIntent.CategorizeGenre): NlpCommandResult {
        val allSongs = musicRepository.getAllSongsOnce()
        val allGenres = allSongs.mapNotNull { it.genre }.distinct()
        if (allGenres.isEmpty()) {
            return NlpCommandResult.Error(
                "No genre tags found in your library. Add genre metadata to your music files first."
            )
        }

        val matchingSongs = mutableListOf<Song>()
        val resolvedGenres = mutableListOf<String>()

        for (query in intent.genreNames) {
            val matchedGenres = NlpFuzzyMatcher.findAllMatches(query, allGenres)
            if (matchedGenres.isNotEmpty()) {
                val primaryGenre = matchedGenres.first()
                val songs = allSongs.filter { song ->
                    matchedGenres.any { genre -> song.genre.equals(genre, ignoreCase = true) }
                }
                matchingSongs.addAll(songs)
                resolvedGenres.add(primaryGenre)
            }
        }

        if (matchingSongs.isEmpty()) {
            return NlpCommandResult.Error(
                "No genres found matching: ${intent.genreNames.joinToString(", ")}. " +
                "Check the spelling."
            )
        }

        val uniqueSongs = matchingSongs.distinctBy { it.id }
        val playlistName = resolvedGenres.joinToString(" & ") + " Mix"
        
        playlistPreferencesRepository.createPlaylist(
            name = playlistName,
            songIds = uniqueSongs.map { it.id }
        )

        return NlpCommandResult.Success(
            "✓ Playlist \"$playlistName\" created with ${uniqueSongs.size} songs " +
            "(matched genres: ${resolvedGenres.joinToString(", ")})."
        )
    }
}

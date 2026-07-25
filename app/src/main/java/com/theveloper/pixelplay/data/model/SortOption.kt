package com.theveloper.pixelplay.data.model

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.theveloper.pixelplay.R

@Immutable
enum class SortDirection {
    Ascending,
    Descending
}

// Sealed class for Sort Options
@Immutable
sealed class SortOption(
    val storageKey: String,
    val displayName: String,
    @StringRes val displayNameRes: Int,
    val methodLabel: String = displayName,
    @StringRes val methodLabelRes: Int = displayNameRes,
    val methodKey: String = storageKey,
    val direction: SortDirection? = null
) {
    // Song Sort Options
    object SongDefaultOrder : SortOption(
        storageKey = "song_default_order",
        displayName = "Default Order",
        displayNameRes = R.string.sort_display_default_order
    )
    object SongTitleAZ : SortOption(
        storageKey = "song_title_az",
        displayName = "Title (A-Z)",
        displayNameRes = R.string.sort_display_title_az,
        methodLabel = "Title",
        methodLabelRes = R.string.sort_method_title,
        methodKey = "song_title",
        direction = SortDirection.Ascending
    )
    object SongTitleZA : SortOption(
        storageKey = "song_title_za",
        displayName = "Title (Z-A)",
        displayNameRes = R.string.sort_display_title_za,
        methodLabel = "Title",
        methodLabelRes = R.string.sort_method_title,
        methodKey = "song_title",
        direction = SortDirection.Descending
    )
    object SongArtist : SortOption(
        storageKey = "song_artist",
        displayName = "Artist",
        displayNameRes = R.string.sort_display_artist,
        methodLabel = "Artist",
        methodLabelRes = R.string.sort_method_artist,
        methodKey = "song_artist",
        direction = SortDirection.Ascending
    )
    object SongArtistDesc : SortOption(
        storageKey = "song_artist_desc",
        displayName = "Artist (Z-A)",
        displayNameRes = R.string.sort_display_artist_za,
        methodLabel = "Artist",
        methodLabelRes = R.string.sort_method_artist,
        methodKey = "song_artist",
        direction = SortDirection.Descending
    )
    object SongAlbum : SortOption(
        storageKey = "song_album",
        displayName = "Album",
        displayNameRes = R.string.sort_display_album,
        methodLabel = "Album",
        methodLabelRes = R.string.sort_method_album,
        methodKey = "song_album",
        direction = SortDirection.Ascending
    )
    object SongAlbumDesc : SortOption(
        storageKey = "song_album_desc",
        displayName = "Album (Z-A)",
        displayNameRes = R.string.sort_display_album_za,
        methodLabel = "Album",
        methodLabelRes = R.string.sort_method_album,
        methodKey = "song_album",
        direction = SortDirection.Descending
    )
    object SongDateAdded : SortOption(
        storageKey = "song_date_added",
        displayName = "Date Added",
        displayNameRes = R.string.sort_display_date_added,
        methodLabel = "Date Added",
        methodLabelRes = R.string.sort_method_date_added,
        methodKey = "song_date_added",
        direction = SortDirection.Descending
    )
    object SongDateAddedAsc : SortOption(
        storageKey = "song_date_added_asc",
        displayName = "Date Added (Oldest First)",
        displayNameRes = R.string.sort_display_date_added_oldest,
        methodLabel = "Date Added",
        methodLabelRes = R.string.sort_method_date_added,
        methodKey = "song_date_added",
        direction = SortDirection.Ascending
    )
    object SongDuration : SortOption(
        storageKey = "song_duration",
        displayName = "Duration",
        displayNameRes = R.string.sort_display_duration,
        methodLabel = "Duration",
        methodLabelRes = R.string.sort_method_duration,
        methodKey = "song_duration",
        direction = SortDirection.Descending
    )
    object SongDurationAsc : SortOption(
        storageKey = "song_duration_asc",
        displayName = "Duration (Shortest First)",
        displayNameRes = R.string.sort_display_duration_shortest,
        methodLabel = "Duration",
        methodLabelRes = R.string.sort_method_duration,
        methodKey = "song_duration",
        direction = SortDirection.Ascending
    )
    object SongListeningTime : SortOption(
        storageKey = "song_listening_time",
        displayName = "Listening Time",
        displayNameRes = R.string.sort_display_listening_time,
        methodLabel = "Listening Time",
        methodLabelRes = R.string.sort_method_listening_time,
        methodKey = "song_listening_time",
        direction = SortDirection.Descending
    )
    object SongListeningTimeAsc : SortOption(
        storageKey = "song_listening_time_asc",
        displayName = "Listening Time (Least First)",
        displayNameRes = R.string.sort_display_listening_time_least,
        methodLabel = "Listening Time",
        methodLabelRes = R.string.sort_method_listening_time,
        methodKey = "song_listening_time",
        direction = SortDirection.Ascending
    )

    // Album Sort Options
    object AlbumTitleAZ : SortOption(
        storageKey = "album_title_az",
        displayName = "Title (A-Z)",
        displayNameRes = R.string.sort_display_title_az,
        methodLabel = "Title",
        methodLabelRes = R.string.sort_method_title,
        methodKey = "album_title",
        direction = SortDirection.Ascending
    )
    object AlbumTitleZA : SortOption(
        storageKey = "album_title_za",
        displayName = "Title (Z-A)",
        displayNameRes = R.string.sort_display_title_za,
        methodLabel = "Title",
        methodLabelRes = R.string.sort_method_title,
        methodKey = "album_title",
        direction = SortDirection.Descending
    )
    object AlbumArtist : SortOption(
        storageKey = "album_artist",
        displayName = "Artist",
        displayNameRes = R.string.sort_display_artist,
        methodLabel = "Artist",
        methodLabelRes = R.string.sort_method_artist,
        methodKey = "album_artist",
        direction = SortDirection.Ascending
    )
    object AlbumArtistDesc : SortOption(
        storageKey = "album_artist_desc",
        displayName = "Artist (Z-A)",
        displayNameRes = R.string.sort_display_artist_za,
        methodLabel = "Artist",
        methodLabelRes = R.string.sort_method_artist,
        methodKey = "album_artist",
        direction = SortDirection.Descending
    )
    object AlbumReleaseYear : SortOption(
        storageKey = "album_release_year",
        displayName = "Release Year",
        displayNameRes = R.string.sort_display_release_year,
        methodLabel = "Release Year",
        methodLabelRes = R.string.sort_method_release_year,
        methodKey = "album_release_year",
        direction = SortDirection.Descending
    )
    object AlbumReleaseYearAsc : SortOption(
        storageKey = "album_release_year_asc",
        displayName = "Release Year (Oldest First)",
        displayNameRes = R.string.sort_display_release_year_oldest,
        methodLabel = "Release Year",
        methodLabelRes = R.string.sort_method_release_year,
        methodKey = "album_release_year",
        direction = SortDirection.Ascending
    )
    object AlbumDateAdded : SortOption(
        storageKey = "album_date_added",
        displayName = "Date Added",
        displayNameRes = R.string.sort_display_date_added,
        methodLabel = "Date Added",
        methodLabelRes = R.string.sort_method_date_added,
        methodKey = "album_date_added",
        direction = SortDirection.Descending
    )
    object AlbumSizeAsc : SortOption(
        storageKey = "album_size_asc",
        displayName = "Fewest Songs",
        displayNameRes = R.string.sort_display_fewest_songs,
        methodLabel = "Song Count",
        methodLabelRes = R.string.sort_method_song_count,
        methodKey = "album_size",
        direction = SortDirection.Ascending
    )
    object AlbumSizeDesc : SortOption(
        storageKey = "album_size_desc",
        displayName = "Most Songs",
        displayNameRes = R.string.sort_display_most_songs,
        methodLabel = "Song Count",
        methodLabelRes = R.string.sort_method_song_count,
        methodKey = "album_size",
        direction = SortDirection.Descending
    )

    // Artist Sort Options
    object ArtistNameAZ : SortOption(
        storageKey = "artist_name_az",
        displayName = "Name (A-Z)",
        displayNameRes = R.string.sort_display_name_az,
        methodLabel = "Name",
        methodLabelRes = R.string.sort_method_name,
        methodKey = "artist_name",
        direction = SortDirection.Ascending
    )
    object ArtistNameZA : SortOption(
        storageKey = "artist_name_za",
        displayName = "Name (Z-A)",
        displayNameRes = R.string.sort_display_name_za,
        methodLabel = "Name",
        methodLabelRes = R.string.sort_method_name,
        methodKey = "artist_name",
        direction = SortDirection.Descending
    )
    object ArtistNumSongsDesc : SortOption(
        storageKey = "artist_num_songs_desc",
        displayName = "Number of Songs (Most)",
        displayNameRes = R.string.sort_display_num_songs_most,
        methodLabel = "Number of Songs",
        methodLabelRes = R.string.sort_method_num_songs,
        methodKey = "artist_num_songs",
        direction = SortDirection.Descending
    )
    object ArtistNumSongsAsc : SortOption(
        storageKey = "artist_num_songs_asc",
        displayName = "Number of Songs (Fewest)",
        displayNameRes = R.string.sort_display_num_songs_fewest,
        methodLabel = "Number of Songs",
        methodLabelRes = R.string.sort_method_num_songs,
        methodKey = "artist_num_songs",
        direction = SortDirection.Ascending
    )

    // Playlist Sort Options
    object PlaylistNameAZ : SortOption(
        storageKey = "playlist_name_az",
        displayName = "Name (A-Z)",
        displayNameRes = R.string.sort_display_name_az,
        methodLabel = "Name",
        methodLabelRes = R.string.sort_method_name,
        methodKey = "playlist_name",
        direction = SortDirection.Ascending
    )
    object PlaylistNameZA : SortOption(
        storageKey = "playlist_name_za",
        displayName = "Name (Z-A)",
        displayNameRes = R.string.sort_display_name_za,
        methodLabel = "Name",
        methodLabelRes = R.string.sort_method_name,
        methodKey = "playlist_name",
        direction = SortDirection.Descending
    )
    object PlaylistDateCreated : SortOption(
        storageKey = "playlist_date_created",
        displayName = "Date Created",
        displayNameRes = R.string.sort_display_date_created,
        methodLabel = "Date Created",
        methodLabelRes = R.string.sort_method_date_created,
        methodKey = "playlist_date_created",
        direction = SortDirection.Descending
    )
    object PlaylistDateCreatedAsc : SortOption(
        storageKey = "playlist_date_created_asc",
        displayName = "Date Created (Oldest First)",
        displayNameRes = R.string.sort_display_date_created_oldest,
        methodLabel = "Date Created",
        methodLabelRes = R.string.sort_method_date_created,
        methodKey = "playlist_date_created",
        direction = SortDirection.Ascending
    )

    // Liked Sort Options (similar to Songs)
    object LikedSongTitleAZ : SortOption(
        storageKey = "liked_title_az",
        displayName = "Title (A-Z)",
        displayNameRes = R.string.sort_display_title_az,
        methodLabel = "Title",
        methodLabelRes = R.string.sort_method_title,
        methodKey = "liked_title",
        direction = SortDirection.Ascending
    )
    object LikedSongTitleZA : SortOption(
        storageKey = "liked_title_za",
        displayName = "Title (Z-A)",
        displayNameRes = R.string.sort_display_title_za,
        methodLabel = "Title",
        methodLabelRes = R.string.sort_method_title,
        methodKey = "liked_title",
        direction = SortDirection.Descending
    )
    object LikedSongArtist : SortOption(
        storageKey = "liked_artist",
        displayName = "Artist",
        displayNameRes = R.string.sort_display_artist,
        methodLabel = "Artist",
        methodLabelRes = R.string.sort_method_artist,
        methodKey = "liked_artist",
        direction = SortDirection.Ascending
    )
    object LikedSongArtistDesc : SortOption(
        storageKey = "liked_artist_desc",
        displayName = "Artist (Z-A)",
        displayNameRes = R.string.sort_display_artist_za,
        methodLabel = "Artist",
        methodLabelRes = R.string.sort_method_artist,
        methodKey = "liked_artist",
        direction = SortDirection.Descending
    )
    object LikedSongAlbum : SortOption(
        storageKey = "liked_album",
        displayName = "Album",
        displayNameRes = R.string.sort_display_album,
        methodLabel = "Album",
        methodLabelRes = R.string.sort_method_album,
        methodKey = "liked_album",
        direction = SortDirection.Ascending
    )
    object LikedSongAlbumDesc : SortOption(
        storageKey = "liked_album_desc",
        displayName = "Album (Z-A)",
        displayNameRes = R.string.sort_display_album_za,
        methodLabel = "Album",
        methodLabelRes = R.string.sort_method_album,
        methodKey = "liked_album",
        direction = SortDirection.Descending
    )
    object LikedSongDateLiked : SortOption(
        storageKey = "liked_date_liked",
        displayName = "Date Liked",
        displayNameRes = R.string.sort_display_date_liked,
        methodLabel = "Date Liked",
        methodLabelRes = R.string.sort_method_date_liked,
        methodKey = "liked_date_liked",
        direction = SortDirection.Descending
    )
    object LikedSongDateLikedAsc : SortOption(
        storageKey = "liked_date_liked_asc",
        displayName = "Date Liked (Oldest First)",
        displayNameRes = R.string.sort_display_date_liked_oldest,
        methodLabel = "Date Liked",
        methodLabelRes = R.string.sort_method_date_liked,
        methodKey = "liked_date_liked",
        direction = SortDirection.Ascending
    )

    // Folder Sort Options
    object FolderNameAZ : SortOption(
        storageKey = "folder_name_az",
        displayName = "Name (A-Z)",
        displayNameRes = R.string.sort_display_name_az,
        methodLabel = "Name",
        methodLabelRes = R.string.sort_method_name,
        methodKey = "folder_name",
        direction = SortDirection.Ascending
    )
    object FolderNameZA : SortOption(
        storageKey = "folder_name_za",
        displayName = "Name (Z-A)",
        displayNameRes = R.string.sort_display_name_za,
        methodLabel = "Name",
        methodLabelRes = R.string.sort_method_name,
        methodKey = "folder_name",
        direction = SortDirection.Descending
    )
    object FolderSongCountAsc : SortOption(
        storageKey = "folder_song_count_asc",
        displayName = "Fewest Songs",
        displayNameRes = R.string.sort_display_fewest_songs,
        methodLabel = "Song Count",
        methodLabelRes = R.string.sort_method_song_count,
        methodKey = "folder_song_count",
        direction = SortDirection.Ascending
    )
    object FolderSongCountDesc : SortOption(
        storageKey = "folder_song_count_desc",
        displayName = "Most Songs",
        displayNameRes = R.string.sort_display_most_songs,
        methodLabel = "Song Count",
        methodLabelRes = R.string.sort_method_song_count,
        methodKey = "folder_song_count",
        direction = SortDirection.Descending
    )
    object FolderSubdirCountAsc : SortOption(
        storageKey = "folder_subdir_count_asc",
        displayName = "Fewest Subfolders",
        displayNameRes = R.string.sort_display_fewest_subfolders,
        methodLabel = "Subfolder Count",
        methodLabelRes = R.string.sort_method_subfolder_count,
        methodKey = "folder_subdir_count",
        direction = SortDirection.Ascending
    )
    object FolderSubdirCountDesc : SortOption(
        storageKey = "folder_subdir_count_desc",
        displayName = "Most Subfolders",
        displayNameRes = R.string.sort_display_most_subfolders,
        methodLabel = "Subfolder Count",
        methodLabelRes = R.string.sort_method_subfolder_count,
        methodKey = "folder_subdir_count",
        direction = SortDirection.Descending
    )

    val canFlipDirection: Boolean
        get() = direction != null && flipDirection().storageKey != storageKey

    fun methodOption(): SortOption = defaultOptionByMethodKey[methodKey] ?: this

    fun resolveForDirection(targetDirection: SortDirection?): SortOption {
        if (targetDirection == null) {
            return methodOption()
        }
        return optionByMethodAndDirection[methodKey to targetDirection] ?: methodOption()
    }

    fun flipDirection(): SortOption {
        val currentDirection = direction ?: return this
        val targetDirection = when (currentDirection) {
            SortDirection.Ascending -> SortDirection.Descending
            SortDirection.Descending -> SortDirection.Ascending
        }
        return resolveForDirection(targetDirection)
    }

    companion object {

        val SONGS: List<SortOption> by lazy {
            listOf(
                SongDefaultOrder,
                SongTitleAZ,
                SongTitleZA,
                SongArtist,
                SongArtistDesc,
                SongAlbum,
                SongAlbumDesc,
                SongDateAdded,
                SongDateAddedAsc,
                SongDuration,
                SongDurationAsc,
                SongListeningTime,
                SongListeningTimeAsc
            )
        }

        val ALBUMS: List<SortOption> by lazy {
            listOf(
                AlbumTitleAZ,
                AlbumTitleZA,
                AlbumArtist,
                AlbumArtistDesc,
                AlbumReleaseYear,
                AlbumReleaseYearAsc,
                AlbumDateAdded,
                AlbumSizeAsc,
                AlbumSizeDesc
            )
        }

        val ARTISTS: List<SortOption> by lazy {
            listOf(
                ArtistNameAZ,
                ArtistNameZA,
                ArtistNumSongsDesc,
                ArtistNumSongsAsc
            )
        }

        val PLAYLISTS: List<SortOption> by lazy {
            listOf(
                PlaylistNameAZ,
                PlaylistNameZA,
                PlaylistDateCreated,
                PlaylistDateCreatedAsc
            )
        }

        val FOLDERS: List<SortOption> by lazy {
            listOf(
                FolderNameAZ,
                FolderNameZA,
                FolderSongCountAsc,
                FolderSongCountDesc,
                FolderSubdirCountAsc,
                FolderSubdirCountDesc
            )
        }

        val LIKED: List<SortOption> by lazy {
            listOf(
                LikedSongTitleAZ,
                LikedSongTitleZA,
                LikedSongArtist,
                LikedSongArtistDesc,
                LikedSongAlbum,
                LikedSongAlbumDesc,
                LikedSongDateLiked,
                LikedSongDateLikedAsc
            )
        }

        private val ALL: List<SortOption> by lazy {
            SONGS + ALBUMS + ARTISTS + PLAYLISTS + FOLDERS + LIKED
        }

        private val defaultOptionByMethodKey: Map<String, SortOption> by lazy {
            ALL.groupBy { it.methodKey }
                .mapValues { (_, options) -> options.first() }
        }

        private val optionByMethodAndDirection: Map<Pair<String, SortDirection>, SortOption> by lazy {
            ALL.asSequence()
                .filter { it.direction != null }
                .associateBy { it.methodKey to requireNotNull(it.direction) }
        }

        fun fromStorageKey(
            rawValue: String?,
            allowed: Collection<SortOption>,
            fallback: SortOption
        ): SortOption {
            if (rawValue.isNullOrBlank()) {
                return fallback
            }

            val sanitized = allowed.filterIsInstance<SortOption>()
            if (sanitized.isEmpty()) {
                return fallback
            }

            sanitized.firstOrNull { option -> option.storageKey == rawValue }?.let { matched ->
                return matched
            }

            // Legacy values used display names; fall back to matching within the allowed group.
            return sanitized.firstOrNull { option -> option.displayName == rawValue } ?: fallback
        }
    }
}

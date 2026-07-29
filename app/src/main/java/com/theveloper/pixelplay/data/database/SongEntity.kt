package com.theveloper.pixelplay.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.theveloper.pixelplay.data.model.ArtistRef
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.utils.LocalArtworkUri
import com.theveloper.pixelplay.utils.normalizeMetadataText
import com.theveloper.pixelplay.utils.normalizeMetadataTextOrEmpty
import org.json.JSONArray
import org.json.JSONObject

/** Integer constants for the `source_type` column — faster than LIKE checks on URI scheme. */
object SourceType {
    const val LOCAL = 0
    const val TELEGRAM = 1
    const val NETEASE = 2
    const val GDRIVE = 3
    const val QQMUSIC = 4
    const val NAVIDROME = 5
    const val JELLYFIN = 6

    /** Derive source type from a content URI string (fallback for migration / conversion). */
    fun fromContentUri(uri: String): Int = when {
        uri.startsWith("telegram://") -> TELEGRAM
        uri.startsWith("netease://") -> NETEASE
        uri.startsWith("gdrive://") -> GDRIVE
        uri.startsWith("qqmusic://") -> QQMUSIC
        uri.startsWith("navidrome://") -> NAVIDROME
        uri.startsWith("jellyfin://") -> JELLYFIN
        else -> LOCAL
    }
}

@Entity(
    tableName = "songs",
    indices = [
        Index(value = ["title"], unique = false),
        Index(value = ["album_id"], unique = false),
        Index(value = ["artist_id"], unique = false),
        Index(value = ["artist_name"], unique = false),
        Index(value = ["genre"], unique = false),
        Index(value = ["parent_directory_path"], unique = false),
        Index(value = ["file_path"], unique = false),
        Index(value = ["content_uri_string"], unique = false),
        Index(value = ["date_added"], unique = false),
        Index(value = ["duration"], unique = false),
        Index(value = ["source_type"], unique = false),
        Index(value = ["parent_directory_path", "source_type", "album_id"], unique = false),
        Index(value = ["parent_directory_path", "source_type", "id"], unique = false)
    ],
    foreignKeys = [
        ForeignKey(
            entity = AlbumEntity::class,
            parentColumns = ["id"],
            childColumns = ["album_id"],
            onDelete = ForeignKey.CASCADE // Si un álbum se borra, sus canciones también
        ),
        ForeignKey(
            entity = ArtistEntity::class,
            parentColumns = ["id"],
            childColumns = ["artist_id"],
            onDelete = ForeignKey.SET_NULL // Si un artista se borra, el artist_id de la canción se pone a null
                                          // o podrías elegir CASCADE si las canciones no deben existir sin artista.
                                          // SET_NULL es más flexible si las canciones pueden ser de "Artista Desconocido".
        )
    ]
)
data class SongEntity(
    @PrimaryKey val id: Long,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "artist_name") val artistName: String, // Display string (combined or primary)
    @ColumnInfo(name = "artist_id") val artistId: Long, // Primary artist ID for backward compatibility
    @ColumnInfo(name = "album_artist") val albumArtist: String? = null, // Album artist from metadata
    @ColumnInfo(name = "album_name") val albumName: String,
    @ColumnInfo(name = "album_id") val albumId: Long, // index = true eliminado
    @ColumnInfo(name = "content_uri_string") val contentUriString: String,
    @ColumnInfo(name = "album_art_uri_string") val albumArtUriString: String?,
    @ColumnInfo(name = "duration") val duration: Long,
    @ColumnInfo(name = "genre") val genre: String?,
    @ColumnInfo(name = "file_path") val filePath: String, // Added filePath
    @ColumnInfo(name = "parent_directory_path") val parentDirectoryPath: String, // Added for directory filtering
    @ColumnInfo(name = "is_favorite", defaultValue = "0") val isFavorite: Boolean = false,
    @ColumnInfo(name = "lyrics", defaultValue = "null") val lyrics: String? = null,
    @ColumnInfo(name = "track_number", defaultValue = "0") val trackNumber: Int = 0,
    @ColumnInfo(name = "disc_number", defaultValue = "null") val discNumber: Int? = null,
    @ColumnInfo(name = "year", defaultValue = "0") val year: Int = 0,
    @ColumnInfo(name = "date_added", defaultValue = "0") val dateAdded: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "mime_type") val mimeType: String? = null,
    @ColumnInfo(name = "bitrate") val bitrate: Int? = null, // bits per second
    @ColumnInfo(name = "sample_rate") val sampleRate: Int? = null, // Hz
    @ColumnInfo(name = "telegram_chat_id") val telegramChatId: Long? = null,
    @ColumnInfo(name = "telegram_file_id") val telegramFileId: Int? = null,
    @ColumnInfo(name = "artists_json") val artistsJson: String? = null,
    @ColumnInfo(name = "source_type", defaultValue = "0") val sourceType: Int = SourceType.LOCAL
)

private fun SongEntity.toSongInternal(artists: List<ArtistRef>): Song {
    return Song(
        id = this.id.toString(),
        title = this.title.normalizeMetadataTextOrEmpty(),
        artist = this.artistName.normalizeMetadataTextOrEmpty(),
        artistId = this.artistId,
        artists = artists,
        album = this.albumName.normalizeMetadataTextOrEmpty(),
        albumId = this.albumId,
        albumArtist = this.albumArtist?.normalizeMetadataText(),
        path = this.filePath, // Map the file path
        contentUriString = this.contentUriString,
        albumArtUriString = LocalArtworkUri.resolveSongArtworkUri(
            storedUri = this.albumArtUriString,
            songId = this.id,
            contentUriString = this.contentUriString
        ),
        duration = this.duration,
        genre = this.genre.normalizeMetadataText(),
        lyrics = this.lyrics?.normalizeMetadataText(),
        isFavorite = this.isFavorite,
        trackNumber = this.trackNumber,
        discNumber = this.discNumber,
        dateAdded = this.dateAdded,
        year = this.year,
        // Parse Telegram metadata from contentUriString
        telegramChatId = if (this.contentUriString.startsWith("telegram://")) {
            this.contentUriString.removePrefix("telegram://").split("/").getOrNull(0)?.toLongOrNull()
        } else null,
        telegramFileId = if (this.contentUriString.startsWith("telegram://")) {
            this.contentUriString.removePrefix("telegram://").split("/").getOrNull(1)?.toIntOrNull()
        } else null,
        neteaseId = if (this.contentUriString.startsWith("netease://")) {
            this.contentUriString.removePrefix("netease://").toLongOrNull()
        } else null,
        gdriveFileId = if (this.contentUriString.startsWith("gdrive://")) {
            this.contentUriString.removePrefix("gdrive://")
        } else null,
        qqMusicMid = if (this.contentUriString.startsWith("qqmusic://")) {
            this.contentUriString.removePrefix("qqmusic://")
        } else null,
        navidromeId = if (this.contentUriString.startsWith("navidrome://")) {
            this.contentUriString.removePrefix("navidrome://")
        } else null,
        jellyfinId = if (this.contentUriString.startsWith("jellyfin://")) {
            this.contentUriString.removePrefix("jellyfin://")
        } else null,
        mimeType = this.mimeType,
        bitrate = this.bitrate,
        sampleRate = this.sampleRate
    )
}

fun SongEntity.toSong(): Song {
    val artists = parseArtistsJson(this.artistsJson)
    return toSongInternal(artists = artists)
}

/**
 * Parses the artists_json column back into a list of ArtistRef.
 */
private fun parseArtistsJson(json: String?): List<ArtistRef> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            ArtistRef(
                id = obj.getLong("id"),
                name = obj.getString("name"),
                isPrimary = obj.optBoolean("primary", false)
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}

/**
 * Serializes a list of ArtistRef to JSON for storage in artists_json column.
 */
fun serializeArtistRefs(artists: List<ArtistRef>): String {
    val arr = JSONArray()
    artists.forEach { ref ->
        arr.put(JSONObject().apply {
            put("id", ref.id)
            put("name", ref.name)
            put("primary", ref.isPrimary)
        })
    }
    return arr.toString()
}

/**
 * Converts a SongEntity to Song with artists from the junction table.
 */
fun SongEntity.toSongWithArtistRefs(artists: List<ArtistEntity>, crossRefs: List<SongArtistCrossRef>): Song {
    val crossRefByArtistId = crossRefs.associateBy { it.artistId }
    val artistRefs = artists.map { artist ->
        val crossRef = crossRefByArtistId[artist.id]
        ArtistRef(
            id = artist.id,
            name = artist.name.normalizeMetadataTextOrEmpty(),
            isPrimary = crossRef?.isPrimary ?: false
        )
    }.sortedByDescending { it.isPrimary }

    return toSongInternal(artists = artistRefs)
}

fun List<SongEntity>.toSongs(): List<Song> {
    return this.map { it.toSong() }
}

// El modelo Song usa id como String, pero la entidad lo necesita como Long (de MediaStore)
// El modelo Song no tiene filePath, así que no se puede mapear desde ahí directamente.
// filePath y parentDirectoryPath se poblarán desde MediaStore en el SyncWorker.
fun Song.toEntity(filePathFromMediaStore: String, parentDirFromMediaStore: String): SongEntity {
    return SongEntity(
        id = this.id.toLongOrNull() ?: throw IllegalArgumentException("Cannot convert Song.id '${this.id}' to Long"),
        title = this.title,
        artistName = this.artist,
        artistId = this.artistId,
        albumArtist = this.albumArtist,
        albumName = this.album,
        albumId = this.albumId,
        contentUriString = this.contentUriString,
        albumArtUriString = this.albumArtUriString,
        duration = this.duration,
        genre = this.genre,
        isFavorite = this.isFavorite,
        lyrics = this.lyrics,
        trackNumber = this.trackNumber,
        discNumber = this.discNumber,
        filePath = filePathFromMediaStore,
        parentDirectoryPath = parentDirFromMediaStore,
        dateAdded = this.dateAdded,
        year = this.year,
        mimeType = this.mimeType,
        bitrate = this.bitrate,
        sampleRate = this.sampleRate,
        sourceType = SourceType.fromContentUri(this.contentUriString)
    )
}

/** Lightweight projection for backup song matching. */
data class SongSummary(
    val id: Long,
    val title: String,
    @ColumnInfo(name = "artist_name") val artistName: String,
    @ColumnInfo(name = "album_name") val albumName: String,
    val duration: Long
)

// Sobrecarga o alternativa si los paths no están disponibles o no son necesarios al convertir de Modelo a Entidad
// (menos probable que se use si la entidad siempre requiere los paths)
fun Song.toEntityWithoutPaths(): SongEntity {
    return SongEntity(
        id = this.id.toLongOrNull() ?: throw IllegalArgumentException("Cannot convert Song.id '${this.id}' to Long"),
        title = this.title,
        artistName = this.artist,
        artistId = this.artistId,
        albumArtist = this.albumArtist,
        albumName = this.album,
        albumId = this.albumId,
        contentUriString = this.contentUriString,
        albumArtUriString = this.albumArtUriString,
        duration = this.duration,
        genre = this.genre,
        isFavorite = this.isFavorite,
        lyrics = this.lyrics,
        trackNumber = this.trackNumber,
        discNumber = this.discNumber,
        filePath = "",
        parentDirectoryPath = "",
        dateAdded = this.dateAdded,
        year = this.year,
        mimeType = this.mimeType,
        bitrate = this.bitrate,
        sampleRate = this.sampleRate,
        sourceType = SourceType.fromContentUri(this.contentUriString)
    )
}

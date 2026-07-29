package com.theveloper.pixelplay.data.media

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Base64
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import com.kyant.taglib.Picture
import com.kyant.taglib.TagLib
import com.theveloper.pixelplay.data.database.ArtistEntity
import com.theveloper.pixelplay.data.database.MusicDao
import com.theveloper.pixelplay.data.database.SongArtistCrossRef
import com.theveloper.pixelplay.data.database.TelegramDao // Added
import com.theveloper.pixelplay.data.database.TelegramSongEntity // Added
import com.theveloper.pixelplay.data.database.serializeArtistRefs
import com.theveloper.pixelplay.data.model.ArtistRef
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.data.worker.collectArtistNames
import com.theveloper.pixelplay.utils.AlbumArtUtils
import com.theveloper.pixelplay.utils.LocalArtworkUri
import com.theveloper.pixelplay.utils.MediaStorePermissionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first // Added
import kotlinx.coroutines.withContext
import org.gagravarr.opus.OpusFile
import org.gagravarr.opus.OpusTags
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.flac.FlacTag
import org.jaudiotagger.tag.id3.AbstractID3v2Frame
import org.jaudiotagger.tag.id3.AbstractID3v2Tag
import org.jaudiotagger.tag.id3.ID3v23Frame
import org.jaudiotagger.tag.id3.ID3v23Frames
import org.jaudiotagger.tag.id3.ID3v23Tag
import org.jaudiotagger.tag.id3.ID3v24Frame
import org.jaudiotagger.tag.id3.ID3v24Frames
import org.jaudiotagger.tag.id3.ID3v24Tag
import org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX
import org.jaudiotagger.tag.images.AndroidArtwork
import org.jaudiotagger.tag.mp4.Mp4Tag
import org.jaudiotagger.tag.mp4.field.Mp4TagReverseDnsField
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag
import org.jaudiotagger.tag.wav.WavTag
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale

private const val TAG = "SongMetadataEditor"

/**
 * Error types for metadata editing operations
 */
enum class MetadataEditError {
    FILE_NOT_FOUND,
    NO_WRITE_PERMISSION,
    INVALID_INPUT,
    UNSUPPORTED_FORMAT,
    TAGLIB_ERROR,
    TIMEOUT,
    FILE_CORRUPTED,
    IO_ERROR,
    UNKNOWN
}

private const val REPLAYGAIN_TRACK_GAIN_KEY = "REPLAYGAIN_TRACK_GAIN"
private const val REPLAYGAIN_ALBUM_GAIN_KEY = "REPLAYGAIN_ALBUM_GAIN"
private const val MP4_REVERSE_DNS_ISSUER = "com.apple.iTunes"

private sealed interface ReplayGainUpdate {
    data object Keep : ReplayGainUpdate
    data object Clear : ReplayGainUpdate
    data class Set(val formattedValue: String) : ReplayGainUpdate
}


class SongMetadataEditor(
    private val context: Context,
    private val musicDao: MusicDao,
    private val telegramDao: TelegramDao, // Added
    private val userPreferencesRepository: UserPreferencesRepository
) {

    /**
     * Maximum allowed length for metadata fields to prevent buffer overflows
     */
    private object MetadataLimits {
        const val MAX_TITLE_LENGTH = 500
        const val MAX_ARTIST_LENGTH = 500
        const val MAX_ALBUM_LENGTH = 500
        const val MAX_ALBUM_ARTIST_LENGTH = 500
        const val MAX_COMPOSER_LENGTH = 500
        const val MAX_GENRE_LENGTH = 100
        const val MAX_LYRICS_LENGTH = 50_000
    }

    /**
     * Validates metadata input and returns error message if invalid
     */
    private fun validateMetadataInput(
        title: String,
        artist: String,
        album: String,
        albumArtist: String?,
        composer: String?,
        genre: String,
        lyrics: String
    ): String? {
        if (title.isBlank()) return "Title cannot be empty"
        if (title.length > MetadataLimits.MAX_TITLE_LENGTH) return "Title too long"
        if (artist.length > MetadataLimits.MAX_ARTIST_LENGTH) return "Artist name too long"
        if (album.length > MetadataLimits.MAX_ALBUM_LENGTH) return "Album name too long"
        if (!albumArtist.isNullOrBlank() && albumArtist.length > MetadataLimits.MAX_ALBUM_ARTIST_LENGTH) return "Album artist name too long"
        if (!composer.isNullOrBlank() && composer.length > MetadataLimits.MAX_COMPOSER_LENGTH) return "Composer name too long"
        if (genre.length > MetadataLimits.MAX_GENRE_LENGTH) return "Genre too long"
        if (lyrics.length > MetadataLimits.MAX_LYRICS_LENGTH) return "Lyrics too long"
        return null
    }

    private fun parseReplayGainUpdate(rawValue: String?, fieldName: String): Result<ReplayGainUpdate> {
        if (rawValue == null) return Result.success(ReplayGainUpdate.Keep)

        val trimmedValue = rawValue.trim()
        if (trimmedValue.isEmpty()) return Result.success(ReplayGainUpdate.Clear)

        val normalizedValue = trimmedValue
            .replace(',', '.')
            .replace(Regex("(?i)\\s*d\\s*b\\s*$"), "")
            .trim()

        val gainDb = normalizedValue.toFloatOrNull()
            ?: return Result.failure(IllegalArgumentException("$fieldName must be a valid dB value"))

        return Result.success(
            ReplayGainUpdate.Set(
                formattedValue = String.format(Locale.US, "%.2f dB", gainDb)
            )
        )
    }

    private suspend fun updateSongArtistMetadata(
        songId: Long,
        title: String,
        artist: String,
        album: String,
        albumArtist: String?,
        genre: String?,
        trackNumber: Int,
        discNumber: Int?
    ) {
        val existingArtists = musicDao.getAllArtistsListRaw()
        val existingByNormalizedName =
            existingArtists.associateBy { it.name.trim().lowercase(Locale.ROOT) }.toMutableMap()
        var nextArtistId = (musicDao.getMaxArtistId() ?: 0L) + 1L

        val artistDelimiters = userPreferencesRepository.artistDelimitersFlow.first()
        val wordDelimiters = userPreferencesRepository.artistWordDelimitersFlow.first()
        val extractFromTitle = userPreferencesRepository.extractArtistsFromTitleFlow.first()

        val artistNames =
            collectArtistNames(
                rawArtistName = artist,
                title = title,
                artistDelimiters = artistDelimiters,
                wordDelimiters = wordDelimiters,
                extractFromTitle = extractFromTitle
            ).ifEmpty {
                listOf(artist.trim().ifEmpty { "Unknown Artist" })
            }

        val artistsToEnsure = mutableListOf<ArtistEntity>()
        val artistRefs = artistNames.mapIndexed { index, rawName ->
            val normalizedName = rawName.trim()
            val normalizedKey = normalizedName.lowercase(Locale.ROOT)
            val existingArtist = existingByNormalizedName[normalizedKey]
            val artistId =
                if (existingArtist != null) {
                    existingArtist.id
                } else {
                    val newId = nextArtistId++
                    val newArtist = ArtistEntity(
                        id = newId,
                        name = normalizedName,
                        trackCount = 0,
                        imageUrl = null,
                        customImageUri = null
                    )
                    existingByNormalizedName[normalizedKey] = newArtist
                    artistsToEnsure += newArtist
                    newId
                }

            ArtistRef(
                id = artistId,
                name = normalizedName,
                isPrimary = index == 0
            )
        }.filter { it.name.isNotEmpty() }

        val primaryArtistId = artistRefs.firstOrNull()?.id ?: -1L
        val artistsJson = serializeArtistRefs(artistRefs)
        val crossRefs = artistRefs.map { ref ->
            SongArtistCrossRef(
                songId = songId,
                artistId = ref.id,
                isPrimary = ref.isPrimary
            )
        }

        musicDao.updateSongMetadataAndArtistLinks(
            songId = songId,
            title = title,
            artist = artist,
            artistId = primaryArtistId,
            artistsJson = artistsJson,
            album = album,
            albumArtist = albumArtist,
            genre = genre,
            trackNumber = trackNumber,
            discNumber = discNumber,
            artistsToEnsure = artistsToEnsure,
            crossRefs = crossRefs
        )
    }

    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun editSongMetadata(
        songId: Long,
        newTitle: String,
        newArtist: String,
        newAlbum: String,
        newAlbumArtist: String? = null,
        newComposer: String? = null,
        newGenre: String,
        newLyrics: String,
        newTrackNumber: Int,
        newDiscNumber: Int?,
        newReplayGainTrackGainDb: String? = null,
        newReplayGainAlbumGainDb: String? = null,
        coverArtUpdate: CoverArtUpdate? = null,
    ): SongMetadataEditResult = withContext(Dispatchers.IO) {
        val validationError = validateMetadataInput(newTitle, newArtist, newAlbum, newAlbumArtist, newComposer, newGenre, newLyrics)
        if (validationError != null) {
            Timber.w("Metadata validation failed: $validationError")
            return@withContext SongMetadataEditResult(
                success = false,
                updatedAlbumArtUri = null,
                error = MetadataEditError.INVALID_INPUT,
                errorMessage = validationError
            )
        }

        try {
            val trimmedLyrics = newLyrics.trim()
            val trimmedGenre = newGenre.trim()
            val normalizedGenre = trimmedGenre.takeIf { it.isNotBlank() }
            val replayGainTrackUpdate = parseReplayGainUpdate(
                rawValue = newReplayGainTrackGainDb,
                fieldName = "Track ReplayGain"
            ).getOrElse { error ->
                return@withContext SongMetadataEditResult(
                    success = false,
                    updatedAlbumArtUri = null,
                    error = MetadataEditError.INVALID_INPUT,
                    errorMessage = error.message ?: "Invalid Track ReplayGain value"
                )
            }
            val replayGainAlbumUpdate = parseReplayGainUpdate(
                rawValue = newReplayGainAlbumGainDb,
                fieldName = "Album ReplayGain"
            ).getOrElse { error ->
                return@withContext SongMetadataEditResult(
                    success = false,
                    updatedAlbumArtUri = null,
                    error = MetadataEditError.INVALID_INPUT,
                    errorMessage = error.message ?: "Invalid Album ReplayGain value"
                )
            }

            val isTelegramSong = songId < 0
            val filePath = if (isTelegramSong) {
                musicDao.getSongById(songId).first()?.filePath
            } else {
                getFilePathFromMediaStore(songId)
            }

            if (filePath.isNullOrBlank() && !isTelegramSong) {
                Timber.tag(TAG).e("Could not get file path for songId: $songId")
                return@withContext SongMetadataEditResult(
                    success = false,
                    updatedAlbumArtUri = null,
                    error = MetadataEditError.FILE_NOT_FOUND,
                    errorMessage = "Could not find file in media library"
                )
            }

            // Write permission is now handled upstream via MediaStore.createWriteRequest()
            // before this method is called. No File.canWrite() check needed.

            val finalFilePath = filePath ?: ""
            val extension = finalFilePath.substringAfterLast('.', "").lowercase(Locale.ROOT)
            val detectedContainer = if (finalFilePath.isNotBlank() && File(finalFilePath).exists()) {
                detectContainerFormat(finalFilePath)
            } else {
                DetectedContainer.UNKNOWN
            }
            val effectiveExtension = when {
                detectedContainer == DetectedContainer.OGG_OPUS -> {
                    if (extension != detectedContainer.canonicalExtension) {
                        Timber.tag(TAG).d(
                            "METADATA_EDIT: Detected Ogg Opus stream in .$extension file. " +
                                "Routing write through the Opus-safe metadata path."
                        )
                    }
                    detectedContainer.canonicalExtension
                }
                detectedContainer != DetectedContainer.UNKNOWN &&
                    detectedContainer.canonicalExtension != extension -> {
                    Timber.tag(TAG).w(
                        "METADATA_EDIT: Extension mismatch — filename has .$extension but magic bytes " +
                            "indicate ${detectedContainer.name}. Routing write as .${detectedContainer.canonicalExtension} " +
                            "via temp-file swap to avoid container corruption."
                    )
                    detectedContainer.canonicalExtension
                }
                else -> extension
            }
            val needsExtensionSwap =
                effectiveExtension != extension && detectedContainer != DetectedContainer.OGG_OPUS
            val flacAnalysis = isProblematicFlacFile(finalFilePath)
            val isHighResFlac = flacAnalysis is FlacAnalysisResult.Problematic
            val useVorbisJavaPrimary = effectiveExtension == "opus"
            val useJAudioTaggerPrimary = effectiveExtension in setOf("wav", "ogg") || isHighResFlac
            val fileExists = finalFilePath.isNotBlank() && File(finalFilePath).exists()

            val runPipeline: (String) -> Boolean = { path ->
                if (useVorbisJavaPrimary) {
                    Timber.tag(TAG).d("METADATA_EDIT: Using VorbisJava Opus writer for $effectiveExtension: $path")
                    updateFileMetadataWithVorbisJava(
                        filePath = path,
                        newTitle = newTitle,
                        newArtist = newArtist,
                        newAlbum = newAlbum,
                        newAlbumArtist = newAlbumArtist,
                        newComposer = newComposer,
                        newGenre = trimmedGenre,
                        newLyrics = trimmedLyrics,
                        newTrackNumber = newTrackNumber,
                        newDiscNumber = newDiscNumber,
                        replayGainTrackUpdate = replayGainTrackUpdate,
                        replayGainAlbumUpdate = replayGainAlbumUpdate,
                        coverArtUpdate = coverArtUpdate
                    )
                } else if (useJAudioTaggerPrimary) {
                    Timber.tag(TAG).d("METADATA_EDIT: Using JAudioTagger as primary for $effectiveExtension: $path")
                    updateFileMetadataWithJAudioTagger(
                        filePath = path,
                        newTitle = newTitle,
                        newArtist = newArtist,
                        newAlbum = newAlbum,
                        newAlbumArtist = newAlbumArtist,
                        newComposer = newComposer,
                        newGenre = trimmedGenre,
                        newLyrics = trimmedLyrics,
                        newTrackNumber = newTrackNumber,
                        newDiscNumber = newDiscNumber,
                        replayGainTrackUpdate = replayGainTrackUpdate,
                        replayGainAlbumUpdate = replayGainAlbumUpdate,
                        coverArtUpdate = coverArtUpdate
                    )
                } else {
                    Timber.tag(TAG).d("METADATA_EDIT: Using TagLib for $effectiveExtension: $path")
                    val tagLibSuccess = updateFileMetadataWithTagLib(
                        filePath = path,
                        newTitle = newTitle,
                        newArtist = newArtist,
                        newAlbum = newAlbum,
                        newAlbumArtist = newAlbumArtist,
                        newComposer = newComposer,
                        newGenre = trimmedGenre,
                        newLyrics = trimmedLyrics,
                        newTrackNumber = newTrackNumber,
                        newDiscNumber = newDiscNumber,
                        replayGainTrackUpdate = replayGainTrackUpdate,
                        replayGainAlbumUpdate = replayGainAlbumUpdate,
                        coverArtUpdate = coverArtUpdate
                    )
                    if (!tagLibSuccess) {
                        Timber.tag(TAG)
                            .w("METADATA_EDIT: TagLib failed for $effectiveExtension, falling back to JAudioTagger")
                        updateFileMetadataWithJAudioTagger(
                            filePath = path,
                            newTitle = newTitle,
                            newArtist = newArtist,
                            newAlbum = newAlbum,
                            newAlbumArtist = newAlbumArtist,
                            newComposer = newComposer,
                            newGenre = trimmedGenre,
                            newLyrics = trimmedLyrics,
                            newTrackNumber = newTrackNumber,
                            newDiscNumber = newDiscNumber,
                            replayGainTrackUpdate = replayGainTrackUpdate,
                            replayGainAlbumUpdate = replayGainAlbumUpdate,
                            coverArtUpdate = coverArtUpdate
                        )
                    } else true
                }
            }

            val fileUpdateSuccess = if (!fileExists) {
                if (isTelegramSong) {
                    Timber.tag(TAG)
                        .w("METADATA_EDIT: Telegram file not found (streaming?). Skipping file tags, updating DB only.")
                    true
                } else {
                    Timber.tag(TAG).e("METADATA_EDIT: File does not exist: $finalFilePath")
                    false
                }
            } else {
                val tempFile = File(
                    context.cacheDir,
                    "metadata_edit_${System.nanoTime()}.$effectiveExtension"
                )
                try {
                    File(finalFilePath).inputStream().use { input ->
                        FileOutputStream(tempFile).use { out -> input.copyTo(out) }
                    }
                    val writeOk = runPipeline(tempFile.absolutePath)
                    if (writeOk) {
                        var writeBackSuccess = false
                        try {
                            val originalFile = File(finalFilePath)
                            if (originalFile.canWrite()) {
                                tempFile.inputStream().use { input ->
                                    FileOutputStream(originalFile, false).use { out ->
                                        input.copyTo(out)
                                        out.fd.sync()
                                    }
                                }
                                writeBackSuccess = true
                                Timber.tag(TAG).d("Successfully wrote metadata directly to raw file path")
                            } else {
                                val uri = if (!isTelegramSong) MediaStorePermissionHelper.getMediaStoreUri(context, songId) else null
                                if (uri != null) {
                                    context.contentResolver.openFileDescriptor(uri, "rwt")?.use { pfd ->
                                        FileOutputStream(pfd.fileDescriptor).use { output ->
                                            tempFile.inputStream().use { input ->
                                                input.copyTo(output)
                                            }
                                        }
                                    }
                                    writeBackSuccess = true
                                    Timber.tag(TAG).d("Successfully wrote metadata via ContentResolver (rwt)")
                                } else {
                                    Timber.tag(TAG).e("Cannot write back: file is not writeable and no MediaStore URI resolved")
                                }
                            }
                        } catch (e: Exception) {
                            Timber.tag(TAG).e(e, "Failed to write edited bytes back to destination")
                        }
                        writeBackSuccess
                    } else {
                        false
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error processing file metadata via temp file")
                    false
                } finally {
                    if (tempFile.exists()) {
                        tempFile.delete()
                    }
                }
            }

            if (!fileUpdateSuccess) {
                Timber.tag(TAG).e("Failed to update file metadata for songId: $songId")
                return@withContext SongMetadataEditResult(
                    success = false,
                    updatedAlbumArtUri = null,
                    error = MetadataEditError.TAGLIB_ERROR,
                    errorMessage = "Failed to write metadata to file"
                )
            }

            if (isTelegramSong) {
                val songEntity = musicDao.getSongById(songId).first()
                if (songEntity?.telegramChatId != null && songEntity.telegramFileId != null) {
                    val telegramId = "${songEntity.telegramChatId}_${songEntity.telegramFileId}"
                    val telegramSong = telegramDao.getSongsByIds(listOf(telegramId)).first().firstOrNull()
                    if (telegramSong != null) {
                        val updatedTelegramSong = telegramSong.copy(
                            title = newTitle,
                            artist = newArtist,
                        )
                        telegramDao.insertSongs(listOf(updatedTelegramSong))
                        Timber.d("Updated TelegramDao for song: $telegramId")
                    }
                }
            } else {
                val mediaStoreSuccess = updateMediaStoreMetadata(
                    songId = songId,
                    title = newTitle,
                    artist = newArtist,
                    album = newAlbum,
                    albumArtist = newAlbumArtist,
                    genre = trimmedGenre,
                    trackNumber = newTrackNumber,
                    discNumber = newDiscNumber
                )
                if (!mediaStoreSuccess) {
                    Timber.w("MediaStore update failed, but file was updated for songId: $songId")
                }
            }

            var storedCoverArtUri: String? = null
            updateSongArtistMetadata(
                songId = songId,
                title = newTitle,
                artist = newArtist,
                album = newAlbum,
                albumArtist = newAlbumArtist,
                genre = normalizedGenre,
                trackNumber = newTrackNumber,
                discNumber = newDiscNumber
            )

            coverArtUpdate?.let {
                AlbumArtUtils.clearCacheForSong(context, songId)
                storedCoverArtUri = if (it.isDeletion) null else LocalArtworkUri.buildSongUriWithTimestamp(songId)
                musicDao.updateSongAlbumArt(songId, storedCoverArtUri)
            }

            if (finalFilePath.isNotBlank()) {
                forceMediaRescan(finalFilePath)
            }

            Timber.tag(TAG).e("METADATA_EDIT: Successfully updated metadata for songId: $songId")
            SongMetadataEditResult(success = true, updatedAlbumArtUri = storedCoverArtUri)
        } catch (e: SecurityException) {
            Timber.e(e, "Security exception editing metadata for songId: $songId")
            SongMetadataEditResult(
                success = false,
                updatedAlbumArtUri = null,
                error = MetadataEditError.NO_WRITE_PERMISSION,
                errorMessage = "Permission denied: ${e.localizedMessage}"
            )
        } catch (e: IOException) {
            Timber.e(e, "IO exception editing metadata for songId: $songId")
            SongMetadataEditResult(
                success = false,
                updatedAlbumArtUri = null,
                error = MetadataEditError.IO_ERROR,
                errorMessage = "Error accessing file: ${e.localizedMessage}"
            )
        } catch (e: OutOfMemoryError) {
            Timber.e(e, "OOM editing metadata for songId: $songId")
            SongMetadataEditResult(
                success = false,
                updatedAlbumArtUri = null,
                error = MetadataEditError.FILE_CORRUPTED,
                errorMessage = "File too large or corrupted"
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to update metadata for songId: $songId")
            val errorType = when {
                e.message?.contains("corrupt", ignoreCase = true) == true -> MetadataEditError.FILE_CORRUPTED
                e.message?.contains("unsupported", ignoreCase = true) == true -> MetadataEditError.UNSUPPORTED_FORMAT
                else -> MetadataEditError.UNKNOWN
            }
            SongMetadataEditResult(
                success = false,
                updatedAlbumArtUri = null,
                error = errorType,
                errorMessage = e.localizedMessage ?: "Unknown error occurred"
            )
        }
    }


    /**
     * FLAC files with high sample rates (>96kHz) or bit depths (>24bit) can cause issues with TagLib.
     * This function detects such files and logs warnings.
     */
    private fun isProblematicFlacFile(filePath: String): FlacAnalysisResult {
        val extension = filePath.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension != "flac") {
            return FlacAnalysisResult.NotFlac
        }
        
        // Try to read FLAC header to detect sample rate and bit depth
        return try {
            val file = File(filePath)
            file.inputStream().use { inputStream ->
                val header = ByteArray(42)
                val bytesRead = inputStream.read(header)
                
                if (bytesRead < 42) {
                    return FlacAnalysisResult.NotFlac
                }
                
                // Check FLAC signature "fLaC"
                if (header[0].toInt().toChar() != 'f' ||
                    header[1].toInt().toChar() != 'L' ||
                    header[2].toInt().toChar() != 'a' ||
                    header[3].toInt().toChar() != 'C'
                ) {
                    return FlacAnalysisResult.NotFlac
                }
                
                // STREAMINFO starts at byte 8 (after 4 byte magic + 4 byte block header)
                // Sample rate is at bytes 18-20 (bits 0-19 of STREAMINFO)
                // Bit depth is in byte 20-21
                val sampleRate = ((header[18].toInt() and 0xFF) shl 12) or
                    ((header[19].toInt() and 0xFF) shl 4) or
                    ((header[20].toInt() and 0xF0) shr 4)
                
                val bitsPerSample = (((header[20].toInt() and 0x01) shl 4) or
                    ((header[21].toInt() and 0xF0) shr 4)) + 1

                Timber.tag(TAG)
                    .d("FLAC analysis: sampleRate=$sampleRate, bitsPerSample=$bitsPerSample")
                
                // Consider problematic if sample rate > 96kHz or bit depth > 24
                val isProblematic = sampleRate > 96000 || bitsPerSample > 24
                
                if (isProblematic) {
                    Timber.tag(TAG)
                        .w("FLAC file may be problematic: $filePath (${sampleRate}Hz, ${bitsPerSample}bit)")
                    FlacAnalysisResult.Problematic(sampleRate, bitsPerSample)
                } else {
                    FlacAnalysisResult.Safe(sampleRate, bitsPerSample)
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not analyze FLAC file: $filePath")
            // If we can't analyze, assume it might be problematic
            FlacAnalysisResult.Unknown
        }
    }

    private sealed class FlacAnalysisResult {
        object NotFlac : FlacAnalysisResult()
        data class Safe(val sampleRate: Int, val bitsPerSample: Int) : FlacAnalysisResult()
        data class Problematic(val sampleRate: Int, val bitsPerSample: Int) : FlacAnalysisResult()
        object Unknown : FlacAnalysisResult()
    }

    private enum class DetectedContainer(val canonicalExtension: String) {
        MP3("mp3"),
        MP4("m4a"),
        FLAC("flac"),
        OGG_OPUS("opus"),
        OGG_VORBIS("ogg"),
        OGG("ogg"),
        WAV("wav"),
        UNKNOWN("")
    }

    /**
     * Detects the actual audio container by reading the file's magic bytes.
     * Many files in the wild have wrong extensions (e.g. MP4/M4A served as .mp3 by YouTube rippers
     * or Telegram). Writing ID3v2 tags to an MP4 container corrupts it irreversibly, so the
     * tag-writing pipeline must route by real content, not by extension.
     */
    private fun detectContainerFormat(filePath: String): DetectedContainer {
        return try {
            File(filePath).inputStream().use { input ->
                val header = ByteArray(512)
                var bytesRead = 0
                while (bytesRead < header.size) {
                    val read = input.read(header, bytesRead, header.size - bytesRead)
                    if (read <= 0) break
                    bytesRead += read
                }
                if (bytesRead < 4) return DetectedContainer.UNKNOWN
                when {
                    // "ID3" marker → MP3 with ID3v2 tag
                    header[0] == 'I'.code.toByte() &&
                        header[1] == 'D'.code.toByte() &&
                        header[2] == '3'.code.toByte() -> DetectedContainer.MP3
                    // MP3 frame sync (0xFFE... 11-bit sync word)
                    header[0] == 0xFF.toByte() &&
                        (header[1].toInt() and 0xE0) == 0xE0 -> DetectedContainer.MP3
                    // "ftyp" at offset 4 → ISO BMFF (MP4/M4A)
                    bytesRead >= 8 &&
                        header[4] == 'f'.code.toByte() &&
                        header[5] == 't'.code.toByte() &&
                        header[6] == 'y'.code.toByte() &&
                        header[7] == 'p'.code.toByte() -> DetectedContainer.MP4
                    // "fLaC"
                    header[0] == 'f'.code.toByte() &&
                        header[1] == 'L'.code.toByte() &&
                        header[2] == 'a'.code.toByte() &&
                        header[3] == 'C'.code.toByte() -> DetectedContainer.FLAC
                    // "OggS"
                    header[0] == 'O'.code.toByte() &&
                        header[1] == 'g'.code.toByte() &&
                        header[2] == 'g'.code.toByte() &&
                        header[3] == 'S'.code.toByte() -> detectOggContainer(header, bytesRead)
                    // "RIFF" + "WAVE"
                    bytesRead >= 12 &&
                        header[0] == 'R'.code.toByte() &&
                        header[1] == 'I'.code.toByte() &&
                        header[2] == 'F'.code.toByte() &&
                        header[3] == 'F'.code.toByte() &&
                        header[8] == 'W'.code.toByte() &&
                        header[9] == 'A'.code.toByte() &&
                        header[10] == 'V'.code.toByte() &&
                        header[11] == 'E'.code.toByte() -> DetectedContainer.WAV
                    else -> DetectedContainer.UNKNOWN
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Container detection failed for $filePath")
            DetectedContainer.UNKNOWN
        }
    }

    private fun detectOggContainer(header: ByteArray, bytesRead: Int): DetectedContainer {
        if (bytesRead < 28) return DetectedContainer.OGG

        val segmentCount = header[26].toInt() and 0xFF
        val bodyOffset = 27 + segmentCount
        if (bodyOffset >= bytesRead) return DetectedContainer.OGG

        return when {
            header.matchesAscii(bodyOffset, "OpusHead", bytesRead) -> DetectedContainer.OGG_OPUS
            header[bodyOffset] == 0x01.toByte() &&
                header.matchesAscii(bodyOffset + 1, "vorbis", bytesRead) -> DetectedContainer.OGG_VORBIS
            else -> DetectedContainer.OGG
        }
    }

    private fun ByteArray.matchesAscii(offset: Int, value: String, bytesRead: Int): Boolean {
        if (offset < 0 || offset + value.length > bytesRead) return false
        for (index in value.indices) {
            if (this[offset + index] != value[index].code.toByte()) return false
        }
        return true
    }

    private fun updateFileMetadataWithTagLib(
        filePath: String,
        newTitle: String,
        newArtist: String,
        newAlbum: String,
        newAlbumArtist: String?,
        newComposer: String?,
        newGenre: String,
        newLyrics: String,
        newTrackNumber: Int,
        newDiscNumber: Int?,
        replayGainTrackUpdate: ReplayGainUpdate = ReplayGainUpdate.Keep,
        replayGainAlbumUpdate: ReplayGainUpdate = ReplayGainUpdate.Keep,
        coverArtUpdate: CoverArtUpdate? = null
    ): Boolean {
        // Check for problematic FLAC files first
        when (val flacResult = isProblematicFlacFile(filePath)) {
            is FlacAnalysisResult.Problematic -> {
                Timber.tag(TAG)
                    .w("TAGLIB: Skipping file modification for high-resolution FLAC (${flacResult.sampleRate}Hz, ${flacResult.bitsPerSample}bit)")
                Timber.tag(TAG)
                    .w("TAGLIB: High-res FLAC files may not work correctly with TagLib. Falling back to JAudioTagger.")
                return false
            }
            else -> { /* Continue with normal processing */ }
        }
        
        return try {
            val audioFile = File(filePath)
            if (!audioFile.exists()) {
                Timber.tag(TAG).e("TAGLIB: Audio file does not exist: $filePath")
                return false
            }
            Timber.tag(TAG).e("TAGLIB: Opening file: $filePath")

            // Open file with read/write permissions
            ParcelFileDescriptor.open(audioFile, ParcelFileDescriptor.MODE_READ_WRITE).use { fd ->
                // Get existing metadata or create empty map
                Timber.tag(TAG).e("TAGLIB: Getting existing metadata...")
                val metadataFd = fd.dup()
                val existingMetadata = TagLib.getMetadata(metadataFd.detachFd())
                Timber.tag(TAG)
                    .e("TAGLIB: Existing metadata: ${existingMetadata?.propertyMap?.keys}")
                val propertyMap = HashMap(existingMetadata?.propertyMap ?: emptyMap())

                // Update metadata fields
                propertyMap["TITLE"] = arrayOf(newTitle)
                propertyMap["ARTIST"] = arrayOf(newArtist)
                propertyMap["ALBUM"] = arrayOf(newAlbum)
                if (!newAlbumArtist.isNullOrBlank()) {
                    propertyMap["ALBUMARTIST"] = arrayOf(newAlbumArtist)
                }
                propertyMap.upsertOrRemove("COMPOSER", newComposer)
                propertyMap.upsertOrRemove("GENRE", newGenre)
                propertyMap.upsertOrRemove("LYRICS", newLyrics)
                propertyMap["TRACKNUMBER"] = arrayOf(newTrackNumber.toString())
                if (newDiscNumber != null && newDiscNumber > 0) {
                    propertyMap["DISCNUMBER"] = arrayOf(newDiscNumber.toString())
                } else {
                    propertyMap.remove("DISCNUMBER")
                }
                propertyMap.applyReplayGainUpdate(REPLAYGAIN_TRACK_GAIN_KEY, replayGainTrackUpdate)
                propertyMap.applyReplayGainUpdate(REPLAYGAIN_ALBUM_GAIN_KEY, replayGainAlbumUpdate)
                Timber.tag(TAG).e("TAGLIB: Updated property map, saving...")

                // Save metadata
                val saveFd = fd.dup()
                val metadataSaved = TagLib.savePropertyMap(saveFd.detachFd(), propertyMap)
                Timber.tag(TAG).e("TAGLIB: savePropertyMap result: $metadataSaved")
                if (!metadataSaved) {
                    Timber.tag(TAG).e("TAGLIB: Failed to save metadata for file: $filePath")
                    return false
                }

                // Update cover art if provided
                coverArtUpdate?.let { update ->
                    val pictures = if (update.isDeletion) {
                        emptyArray<Picture>()
                    } else if (update.bytes != null) {
                        arrayOf(
                            Picture(
                                data = update.bytes,
                                description = "Front Cover",
                                pictureType = "Front Cover",
                                mimeType = update.mimeType
                            )
                        )
                    } else {
                        null
                    }

                    pictures?.let {
                        val pictureFd = fd.dup()
                        val coverSaved = TagLib.savePictures(pictureFd.detachFd(), it)
                        if (!coverSaved) {
                            Timber.tag(TAG)
                                .w("TAGLIB: Failed to save cover art, but metadata was saved")
                        } else {
                            Timber.tag(TAG)
                                .d("TAGLIB: Successfully ${if (update.isDeletion) "removed" else "embedded"} cover art")
                        }
                    }
                }
            }

            // Force file system sync to ensure data is written to disk
            try {
                java.io.RandomAccessFile(audioFile, "rw").use { raf ->
                    raf.fd.sync()
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Could not sync file, changes should still be persisted")
            }

            Timber.tag(TAG).e("TAGLIB: SUCCESS - Updated file metadata: ${audioFile.path}")
            true

        } catch (e: Exception) {
            Timber.tag(TAG).e("TAGLIB ERROR: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    private fun updateFileMetadataWithJAudioTagger(
        filePath: String,
        newTitle: String,
        newArtist: String,
        newAlbum: String,
        newAlbumArtist: String?,
        newComposer: String?,
        newGenre: String,
        newLyrics: String,
        newTrackNumber: Int,
        newDiscNumber: Int?,
        replayGainTrackUpdate: ReplayGainUpdate = ReplayGainUpdate.Keep,
        replayGainAlbumUpdate: ReplayGainUpdate = ReplayGainUpdate.Keep,
        coverArtUpdate: CoverArtUpdate? = null
    ): Boolean {
        val targetFile = File(filePath)
        
        return try {
            // Suppress JAudioTagger's verbose logging
            java.util.logging.Logger.getLogger("org.jaudiotagger").level = java.util.logging.Level.OFF
            
            val audioFile = AudioFileIO.read(targetFile)
            val tag = audioFile.tag ?: audioFile.createDefaultTag()
            
            // Update text fields
            tag.setField(FieldKey.TITLE, newTitle)
            tag.setField(FieldKey.ARTIST, newArtist)
            tag.setField(FieldKey.ALBUM, newAlbum)
            if (!newAlbumArtist.isNullOrBlank()) {
                tag.setField(FieldKey.ALBUM_ARTIST, newAlbumArtist)
            }
            if (!newComposer.isNullOrBlank()) {
                tag.setField(FieldKey.COMPOSER, newComposer)
            } else {
                tag.deleteField(FieldKey.COMPOSER)
            }
            
            if (newGenre.isNotBlank()) {
                tag.setField(FieldKey.GENRE, newGenre)
            } else {
                tag.deleteField(FieldKey.GENRE)
            }
            
            if (newLyrics.isNotBlank()) {
                tag.setField(FieldKey.LYRICS, newLyrics)
            } else {
                tag.deleteField(FieldKey.LYRICS)
            }
            
            tag.setField(FieldKey.TRACK, newTrackNumber.toString())
            if (newDiscNumber != null && newDiscNumber > 0) {
                tag.setField(FieldKey.DISC_NO, newDiscNumber.toString())
            } else {
                tag.deleteField(FieldKey.DISC_NO)
            }
            tag.applyReplayGainUpdate(REPLAYGAIN_TRACK_GAIN_KEY, replayGainTrackUpdate)
            tag.applyReplayGainUpdate(REPLAYGAIN_ALBUM_GAIN_KEY, replayGainAlbumUpdate)

            // Update cover art if provided
            coverArtUpdate?.let { update ->
                if (update.isDeletion) {
                    tag.deleteArtworkField()
                    Timber.tag(TAG).d("JAUDIOTAGGER: Removed cover art")
                } else if (update.bytes != null) {
                    try {
                        tag.deleteArtworkField()
                        val artwork = AndroidArtwork()
                        artwork.binaryData = update.bytes
                        artwork.mimeType = update.mimeType
                        artwork.pictureType = 3 // Standard value for "Front Cover"
                        
                        // Extract dimensions using Android native BitmapFactory to avoid ImageIO crash
                        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(update.bytes, 0, update.bytes.size, options)
                        artwork.width = options.outWidth
                        artwork.height = options.outHeight
                        
                        tag.setField(artwork)
                        Timber.tag(TAG)
                            .d("JAUDIOTAGGER: Embedded new cover art (${update.mimeType}, ${options.outWidth}x${options.outHeight})")
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "JAUDIOTAGGER: Failed to create artwork from bytes")
                    }
                } else {
                    Timber.tag(TAG).w("JAUDIOTAGGER: Ignoring invalid CoverArtUpdate with no bytes and no deletion flag")
                }
            }

            audioFile.commit()
            
            // Verify the write succeeded for FLAC files which can be problematic
            if (targetFile.extension.equals("flac", ignoreCase = true)) {
                val verifyFile = AudioFileIO.read(targetFile)
                val verifyTag = verifyFile?.tag
                if (verifyTag == null || verifyTag.getFirst(FieldKey.TITLE) != newTitle) {
                    Timber.tag(TAG).w("JAUDIOTAGGER: FLAC verification failed - tag not persisted, falling back to TagLib")
                    return false
                }
            }
            
            Timber.tag(TAG).d("JAUDIOTAGGER: SUCCESS - Updated file metadata: $filePath")
            true
        } catch (e: Exception) {
            Timber.tag(TAG).e("JAUDIOTAGGER ERROR: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    private fun updateFileMetadataWithVorbisJava(
        filePath: String,
        newTitle: String,
        newArtist: String,
        newAlbum: String,
        newAlbumArtist: String?,
        newComposer: String?,
        newGenre: String,
        newLyrics: String,
        newTrackNumber: Int,
        newDiscNumber: Int?,
        replayGainTrackUpdate: ReplayGainUpdate = ReplayGainUpdate.Keep,
        replayGainAlbumUpdate: ReplayGainUpdate = ReplayGainUpdate.Keep,
        coverArtUpdate: CoverArtUpdate? = null
    ): Boolean {
        val audioFile = File(filePath)
        val originalExtension = audioFile.extension.ifBlank { "opus" }
        var tempFile: File? = null
        var opusFile: OpusFile? = null
        
        return try {
            if (!audioFile.exists()) {
                Timber.tag(TAG).e("VORBISJAVA: Audio file does not exist: $filePath")
                return false
            }

            Timber.tag(TAG).e("VORBISJAVA: Reading Opus file: $filePath")
            
            // Read existing file
            val sourceOpusFile = OpusFile(audioFile)
            opusFile = sourceOpusFile
            val tags = sourceOpusFile.tags ?: OpusTags()

            Timber.tag(TAG).e("VORBISJAVA: Existing tags: ${tags.allComments}")
            
            tags.replaceSingleComment("TITLE", newTitle)
            tags.replaceSingleComment("ARTIST", newArtist)
            tags.replaceSingleComment("ALBUMARTIST", newAlbumArtist?.takeIf { it.isNotBlank() })
            tags.replaceSingleComment("COMPOSER", newComposer)
            tags.replaceSingleComment("ALBUM", newAlbum)
            tags.replaceSingleComment("GENRE", newGenre)
            tags.replaceSingleComment("LYRICS", newLyrics)
            tags.replaceSingleComment("TRACKNUMBER", newTrackNumber.takeIf { it > 0 }?.toString())
            tags.replaceSingleComment("DISCNUMBER", newDiscNumber?.takeIf { it > 0 }?.toString())
            tags.applyReplayGainUpdate(REPLAYGAIN_TRACK_GAIN_KEY, replayGainTrackUpdate)
            tags.applyReplayGainUpdate(REPLAYGAIN_ALBUM_GAIN_KEY, replayGainAlbumUpdate)
            coverArtUpdate?.let { update ->
                tags.applyCoverArtUpdate(update)
            }

            Timber.tag(TAG).e("VORBISJAVA: Updated tags: ${tags.allComments}")
            
            tempFile = File(
                context.cacheDir,
                "metadata_edit_opus_${System.nanoTime()}.$originalExtension"
            )
            
            Timber.tag(TAG).e("VORBISJAVA: Writing to temp file: ${tempFile.path}")
            FileOutputStream(tempFile).use { fos ->
                OpusFile(fos, sourceOpusFile.info, tags).use { newOpusFile ->
                    var packet = sourceOpusFile.nextAudioPacket
                    while (packet != null) {
                        newOpusFile.writeAudioData(packet)
                        packet = sourceOpusFile.nextAudioPacket
                    }
                }
            }
            sourceOpusFile.close()
            opusFile = null
            
            // Verify temp file was created and has content
            if (!tempFile.exists() || tempFile.length() == 0L) {
                Timber.tag(TAG).e("VORBISJAVA: Temp file creation failed or is empty")
                return false
            }
            Timber.tag(TAG)
                .e("VORBISJAVA: Temp file size: ${tempFile.length()} bytes, original: ${audioFile.length()} bytes")
            
            tempFile.inputStream().use { input ->
                FileOutputStream(audioFile, false).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }

            Timber.tag(TAG).e("VORBISJAVA: SUCCESS - Updated file metadata: ${audioFile.path}")
            true

        } catch (e: Exception) {
            Timber.tag(TAG).e("VORBISJAVA ERROR: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
            false
        } finally {
            try {
                opusFile?.close()
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "VORBISJAVA: Could not close source Opus file")
            }
            if (tempFile != null && tempFile.exists() && tempFile.delete() == false) {
                Timber.tag(TAG).w("VORBISJAVA: Could not delete temp file ${tempFile.absolutePath}")
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun updateMediaStoreMetadata(
        songId: Long,
        title: String,
        artist: String,
        album: String,
        albumArtist: String?,
        genre: String,
        trackNumber: Int,
        discNumber: Int?
    ): Boolean {
        return try {
            val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, songId)

            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.TITLE, title)
                put(MediaStore.Audio.Media.ARTIST, artist)
                put(MediaStore.Audio.Media.ALBUM, album)
                put(MediaStore.Audio.Media.GENRE, genre)
                val encodedTrack = ((discNumber ?: 0) * 1000) + trackNumber
                put(MediaStore.Audio.Media.TRACK, encodedTrack)
                put(MediaStore.Audio.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
                if (!albumArtist.isNullOrBlank()) {
                    put(MediaStore.Audio.Media.ALBUM_ARTIST, albumArtist)
                }
            }

            val rowsUpdated = context.contentResolver.update(uri, values, null, null)
            val success = rowsUpdated > 0

            Timber.d("MediaStore update: $rowsUpdated row(s) affected")
            success

        } catch (e: Exception) {
            Timber.e(e, "Failed to update MediaStore for songId: $songId")
            false
        }
    }

    private fun forceMediaRescan(filePath: String) {
        try {
            val file = File(filePath)
            if (file.exists()) {
                Timber.tag(TAG).e("RESCAN: Starting MediaScanner for: $filePath")
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(filePath),
                    null
                ) { path, uri ->
                    Timber.tag(TAG).e("RESCAN: Completed for: $path, new URI: $uri")
                }
            } else {
                Timber.tag(TAG).e("RESCAN: File does not exist: $filePath")
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e("RESCAN ERROR: ${e.message}")
        }
    }

    private fun getFilePathFromMediaStore(songId: Long): String? {
        Timber.tag(TAG).e("getFilePathFromMediaStore: Looking up songId: $songId")
        return try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media.DATA),
                "${MediaStore.Audio.Media._ID} = ?",
                arrayOf(songId.toString()),
                null
            )?.use { cursor ->
                Timber.tag(TAG).e("getFilePathFromMediaStore: Cursor count: ${cursor.count}")
                if (cursor.moveToFirst()) {
                    val path = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA))
                    Timber.tag(TAG).e("getFilePathFromMediaStore: Found path: $path")
                    path
                } else {
                    Timber.tag(TAG)
                        .e("getFilePathFromMediaStore: No file found for songId: $songId")
                    null
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e("getFilePathFromMediaStore: Error querying MediaStore: ${e.message}")
            null
        }
    }
    private fun saveCoverArtPreview(songId: Long, coverArtUpdate: CoverArtUpdate): String? {
        return try {
            val extension = imageExtensionFromMimeType(coverArtUpdate.mimeType) ?: "jpg"
            val directory = File(context.cacheDir, "").apply {
                if (!exists()) mkdirs()
            }

            // Clean up old cover art files for this song
            directory.listFiles { file ->
                file.name.startsWith("song_art_${songId}")
            }?.forEach { it.delete() }

            // Save new cover art
            val file = File(directory, "song_art_${songId}_${System.currentTimeMillis()}.$extension")
            FileOutputStream(file).use { outputStream ->
                outputStream.write(coverArtUpdate.bytes)
            }

            file.toUri().toString()
        } catch (e: Exception) {
            Timber.e(e, "Error saving cover art preview for songId: $songId")
            null
        }
    }

    private fun imageExtensionFromMimeType(mimeType: String): String? {
        return when (mimeType) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> null
        }
    }
}

private fun OpusTags.replaceSingleComment(key: String, value: String?) {
    removeComments(key)
    if (!value.isNullOrBlank()) {
        addComment(key, value)
    }
}

private fun OpusTags.applyReplayGainUpdate(key: String, update: ReplayGainUpdate) {
    when (update) {
        ReplayGainUpdate.Keep -> Unit
        ReplayGainUpdate.Clear -> removeComments(key)
        is ReplayGainUpdate.Set -> replaceSingleComment(key, update.formattedValue)
    }
}

private fun OpusTags.applyCoverArtUpdate(update: CoverArtUpdate) {
    removeComments("METADATA_BLOCK_PICTURE")
    removeComments("COVERART")
    removeComments("COVERARTMIME")

    if (update.isDeletion) {
        Timber.tag(TAG).d("VORBISJAVA: Removed cover art")
        return
    }

    val imageBytes = update.bytes
    if (imageBytes == null) {
        Timber.tag(TAG).w("VORBISJAVA: Ignoring invalid CoverArtUpdate with no bytes and no deletion flag")
        return
    }

    addComment("METADATA_BLOCK_PICTURE", buildVorbisPictureBlock(imageBytes, update.mimeType))
    Timber.tag(TAG).d("VORBISJAVA: Embedded cover art (${update.mimeType}, ${imageBytes.size} bytes)")
}

private fun buildVorbisPictureBlock(imageBytes: ByteArray, mimeType: String): String {
    val safeMimeType = mimeType.takeIf { it.isNotBlank() } ?: "image/jpeg"
    val mimeBytes = safeMimeType.toByteArray(Charsets.UTF_8)
    val descriptionBytes = "Front Cover".toByteArray(Charsets.UTF_8)
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)
    val width = options.outWidth.takeIf { it > 0 } ?: 0
    val height = options.outHeight.takeIf { it > 0 } ?: 0

    val output = ByteArrayOutputStream()
    output.writeIntBigEndian(3)
    output.writeIntBigEndian(mimeBytes.size)
    output.write(mimeBytes)
    output.writeIntBigEndian(descriptionBytes.size)
    output.write(descriptionBytes)
    output.writeIntBigEndian(width)
    output.writeIntBigEndian(height)
    output.writeIntBigEndian(0)
    output.writeIntBigEndian(0)
    output.writeIntBigEndian(imageBytes.size)
    output.write(imageBytes)
    return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
}

private fun ByteArrayOutputStream.writeIntBigEndian(value: Int) {
    write((value ushr 24) and 0xFF)
    write((value ushr 16) and 0xFF)
    write((value ushr 8) and 0xFF)
    write(value and 0xFF)
}

private fun MutableMap<String, Array<String>>.upsertOrRemove(key: String, value: String?) {
    if (value.isNullOrBlank()) {
        remove(key)
    } else {
        this[key] = arrayOf(value)
    }
}

private fun MutableMap<String, Array<String>>.applyReplayGainUpdate(
    key: String,
    update: ReplayGainUpdate
) {
    when (update) {
        ReplayGainUpdate.Keep -> Unit
        ReplayGainUpdate.Clear -> remove(key)
        is ReplayGainUpdate.Set -> this[key] = arrayOf(update.formattedValue)
    }
}

private fun Tag.applyReplayGainUpdate(key: String, update: ReplayGainUpdate) {
    when (update) {
        ReplayGainUpdate.Keep -> Unit
        ReplayGainUpdate.Clear -> removeReplayGainField(key)
        is ReplayGainUpdate.Set -> upsertReplayGainField(key, update.formattedValue)
    }
}

private fun Tag.upsertReplayGainField(key: String, value: String) {
    when (this) {
        is AbstractID3v2Tag -> upsertReplayGainId3Field(key, value)
        is WavTag -> {
            val id3Tag = getID3Tag() ?: ID3v24Tag().also(::setID3Tag)
            id3Tag.upsertReplayGainId3Field(key, value)
        }
        is FlacTag -> setField(key, value)
        is VorbisCommentTag -> setField(key, value)
        is Mp4Tag -> {
            val fieldId = replayGainMp4FieldId(key)
            deleteField(fieldId)
            setField(Mp4TagReverseDnsField(fieldId, MP4_REVERSE_DNS_ISSUER, key, value))
        }
        else -> Timber.tag(TAG).w("ReplayGain update is not supported for tag type: ${this::class.java.simpleName}")
    }
}

private fun Tag.removeReplayGainField(key: String) {
    when (this) {
        is AbstractID3v2Tag -> removeReplayGainId3Field(key)
        is WavTag -> getID3Tag()?.removeReplayGainId3Field(key)
        is FlacTag -> deleteField(key)
        is VorbisCommentTag -> deleteField(key)
        is Mp4Tag -> deleteField(replayGainMp4FieldId(key))
        else -> Timber.tag(TAG).w("ReplayGain removal is not supported for tag type: ${this::class.java.simpleName}")
    }
}

private fun AbstractID3v2Tag.upsertReplayGainId3Field(key: String, value: String) {
    val frame = if (this is ID3v23Tag) {
        ID3v23Frame(ID3v23Frames.FRAME_ID_V3_USER_DEFINED_INFO)
    } else {
        ID3v24Frame(ID3v24Frames.FRAME_ID_USER_DEFINED_INFO)
    }
    frame.body = FrameBodyTXXX().apply {
        setDescription(key)
        setText(value)
    }
    setField(frame)
}

private fun AbstractID3v2Tag.removeReplayGainId3Field(key: String) {
    val frameId = if (this is ID3v23Tag) {
        ID3v23Frames.FRAME_ID_V3_USER_DEFINED_INFO
    } else {
        ID3v24Frames.FRAME_ID_USER_DEFINED_INFO
    }
    val frames = getFields(frameId)
    val iterator = frames.listIterator()
    while (iterator.hasNext()) {
        val frame = iterator.next() as? AbstractID3v2Frame ?: continue
        val body = frame.body as? FrameBodyTXXX ?: continue
        if (body.description == key) {
            if (frames.size == 1) {
                removeFrame(frameId)
            } else {
                iterator.remove()
            }
        }
    }
}

private fun replayGainMp4FieldId(key: String): String = "----:$MP4_REVERSE_DNS_ISSUER:$key"

// Data classes
data class SongMetadataEditResult(
    val success: Boolean,
    val updatedAlbumArtUri: String?,
    val error: MetadataEditError? = null,
    val errorMessage: String? = null
)

data class CoverArtUpdate(
    val bytes: ByteArray? = null,
    val mimeType: String = "image/jpeg",
    val isDeletion: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CoverArtUpdate

        if (!bytes.contentEquals(other.bytes)) return false
        if (mimeType != other.mimeType) return false

        return true
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + mimeType.hashCode()
        return result
    }
}

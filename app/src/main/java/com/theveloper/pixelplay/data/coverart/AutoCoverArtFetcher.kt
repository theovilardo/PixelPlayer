package com.theveloper.pixelplay.data.coverart

import android.content.Context
import com.theveloper.pixelplay.data.database.MusicDao
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.data.repository.CoverArtSearchRepository
import com.theveloper.pixelplay.utils.DirectoryFilterUtils
import com.theveloper.pixelplay.utils.AlbumArtUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Outcome of one auto fetch pass, for the worker's log line.
 */
data class AutoCoverArtResult(
    val albumsChecked: Int = 0,
    val coversApplied: Int = 0,
    val notFound: Int = 0,
    /** True when the pass stopped at its cap, so albums are still waiting. */
    val reachedLimit: Boolean = false
)

/**
 * Fills in covers for albums that have none.
 *
 * Nothing reaches the user's audio files: the image goes into the applied
 * store, one per album. Only confident matches are applied -- a cover chosen
 * without anyone looking is worse than no cover -- and albums no catalog
 * matched are remembered so the next pass skips them.
 */
@Singleton
class AutoCoverArtFetcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val musicDao: MusicDao,
    private val coverArtSearchRepository: CoverArtSearchRepository,
    private val appArtworkWriter: AppArtworkWriter,
    private val userPreferencesRepository: UserPreferencesRepository
) {

    /**
     * @param isStopped consulted between albums so a pass the system is taking
     * back stops at an album boundary instead of part way through one.
     */
    suspend fun fetchMissingCovers(
        albumLimit: Int = DEFAULT_ALBUM_LIMIT,
        isStopped: () -> Boolean = { false },
        /** Exposed so tests do not have to wait out the real pacing. */
        perAlbumDelayMs: Long = PER_ALBUM_DELAY_MS
    ): AutoCoverArtResult =
        withContext(Dispatchers.IO) {
            val alreadyMissed = userPreferencesRepository.albumArtNotFoundIdsFlow.first()

            // Excluded folders stay out of it: their album names would
            // otherwise be sent to third-party catalogs. Resolved through the
            // shared helper because exclusion is the blocked set, not the
            // allowed one, and the query matches parent directories rather
            // than the roots the user configured.
            val (allowedParentDirs, applyDirectoryFilter) = DirectoryFilterUtils.computeAllowedParentDirs(
                allowedDirs = userPreferencesRepository.allowedDirectoriesFlow.first(),
                blockedDirs = userPreferencesRepository.blockedDirectoriesFlow.first(),
                getAllParentDirs = { musicDao.getDistinctParentDirectories() },
                normalizePath = { path -> java.io.File(path).absolutePath }
            )
            val albums = musicDao.getAllAlbumsList(
                allowedParentDirs = allowedParentDirs,
                applyDirectoryFilter = applyDirectoryFilter,
                minTracks = 1
            )

            // Dead ends are MediaStore album ids, which a re-index does not
            // preserve, so the set is pruned to the ones still present. Only
            // against a listing that speaks for the whole library: a filtered
            // or empty one is missing albums that are still there, and pruning
            // against it would send the next pass back to the catalogs.
            if (!applyDirectoryFilter && albums.isNotEmpty()) {
                val stillPresent = alreadyMissed intersect albums.mapTo(mutableSetOf()) { it.id }
                if (stillPresent.size != alreadyMissed.size) {
                    userPreferencesRepository.setAlbumArtNotFoundIds(stillPresent)
                }
            }

            var checked = 0
            var applied = 0
            var notFound = 0
            var failures = 0
            // MusicBrainz asks for roughly one request per second, and this runs
            // unattended, so there is no reason to push it.
            var searchedPreviousAlbum = false
            var reachedLimit = false
            val missed = mutableSetOf<Long>()

            // Recorded as the pass goes: one the system stops part way would
            // otherwise persist nothing and be re-queried from the start. In
            // batches, because each write rewrites the whole preferences file.
            suspend fun checkpointMissed(force: Boolean = false) {
                if (missed.isEmpty()) return
                if (!force && missed.size < MISSED_CHECKPOINT_SIZE) return
                userPreferencesRepository.addAlbumArtNotFoundIds(missed)
                missed.clear()
            }

            for (album in albums) {
                if (applied + notFound >= albumLimit) {
                    reachedLimit = true
                    break
                }
                if (isStopped()) {
                    checkpointMissed(force = true)
                    break
                }
                if (album.id in alreadyMissed) continue

                // With no artist to compare against, scoring falls to the
                // title alone and any release sharing a common one -- "Greatest
                // Hits" -- comes back an exact match. Fine for the picker, where
                // a person decides; here it would apply an unrelated cover with
                // nobody watching. Not a dead end: nothing was asked.
                if (isUnidentifiable(album.title) || isUnidentifiable(album.artistName)) continue

                // Paced before the album rather than after, so every path that
                // spent a request pays the wait -- including the ones that give
                // up early, which are what reach the slow catalog.
                if (searchedPreviousAlbum) {
                    searchedPreviousAlbum = false
                    delay(perAlbumDelayMs)
                }

                // Cloud tracks look art-less here whatever cover the user sees:
                // theirs lives on the server, behind a scheme this pass cannot
                // resolve. Applying would overwrite a working remote URI with a
                // guess, and the remove action skips them, leaving no way back.
                val songs = musicDao.getSongsByAlbumIdOnce(album.id).filter { it.id > 0 }
                if (songs.isEmpty()) continue
                // Every track, not just the first: an applied cover outranks
                // extracted art and covers the whole album, so a compilation
                // whose opener lacks embedded art would lose the real artwork
                // on all the others.
                if (songs.any { !isMissingArtwork(it.id, it.filePath) }) continue

                checked++
                searchedPreviousAlbum = true
                val candidate = bestCandidateFor(album.title, album.artistName).getOrElse { error ->
                    // Left alone rather than remembered: a search that got no
                    // answer says nothing about whether a cover exists, and the
                    // not-found list is never revisited on its own. The counter
                    // resets only once an album goes through cleanly, below --
                    // resetting on search alone hid a run of storage failures.
                    Timber.tag(TAG).w("Auto cover art search failed for ${album.title}: ${error.message}")
                    if (++failures >= MAX_CONSECUTIVE_FAILURES) break else continue
                }
                if (candidate == null) {
                    // No match is a legitimate outcome, not a failure: the
                    // catalogs answered, and the run is healthy.
                    failures = 0
                    missed += album.id
                    notFound++
                    checkpointMissed()
                    continue
                }

                val application = applyCover(candidate, album.id, songs.map { it.id })
                if (application.isSuccess) {
                    failures = 0
                    applied++
                } else {
                    // A cover was found; only fetching it failed. Remembering the
                    // album here would blacklist it over a download that a later
                    // pass would have completed.
                    Timber.tag(TAG).w(
                        "Could not apply cover for ${album.title}: " +
                            "${application.exceptionOrNull()?.message}"
                    )
                    if (++failures >= MAX_CONSECUTIVE_FAILURES) break else continue
                }
            }

            checkpointMissed(force = true)
            AutoCoverArtResult(
                albumsChecked = checked,
                coversApplied = applied,
                notFound = notFound,
                reachedLimit = reachedLimit
            )
        }

    /**
     * Whether [value] says nothing about which release this is.
     *
     * MediaStore fills unreadable fields in with a placeholder rather than
     * leaving them empty, so both forms have to be recognised as the absence
     * they are.
     */
    private fun isUnidentifiable(value: String): Boolean {
        val normalized = value.trim().lowercase(java.util.Locale.ROOT)
        return normalized.isEmpty() ||
            normalized == "<unknown>" ||
            normalized == "unknown" ||
            normalized == "unknown artist" ||
            normalized == "unknown album"
    }

    /**
     * Whether this one track yields no artwork, resolved the same way the UI
     * resolves it when it draws the track.
     */
    private fun isMissingArtwork(songId: Long, path: String?): Boolean =
        AlbumArtUtils.ensureAlbumArtCachedFile(
            appContext = context,
            songId = songId,
            filePath = path
        ) == null

    /**
     * The cover to apply, or null when the catalogs answered and none had one
     * worth taking.
     *
     * A failure stays a failure rather than folding into "no match", which the
     * caller remembers for good. That includes one catalog failing while
     * another answered, unless what arrived is good enough to apply: the
     * catalogs barely overlap, so the one that timed out may be the only one
     * carrying the release.
     */
    private suspend fun bestCandidateFor(album: String, artist: String): Result<CoverArtCandidate?> {
        val outcome = coverArtSearchRepository
            // Anything below the bar is discarded anyway, so once a direct
            // catalog has answered this well there is nothing to gain by
            // waiting on the slow one.
            .search(album = album, artist = artist, confidentMatchScore = MIN_AUTO_SCORE)
        val best = outcome.candidates.firstOrNull()?.takeIf { it.score >= MIN_AUTO_SCORE }
        return when {
            best != null -> Result.success(best)
            outcome.failure != null -> Result.failure(outcome.failure)
            else -> Result.success(null)
        }
    }

    /**
     * Applies [candidate] to [songIds], or reports why it could not.
     *
     * Always through the app's own store, whatever the user chose for manual
     * applies: embedding needs consent per file and there is nobody to ask
     * here. A failure is a download or a disk, not an album without a cover.
     */
    private suspend fun applyCover(
        candidate: CoverArtCandidate,
        albumId: Long,
        songIds: List<Long>
    ): Result<Unit> {
        val downloaded = coverArtSearchRepository.downloadCandidate(candidate)
            .getOrElse { error -> return Result.failure(error) }
        val bytes = try {
            downloaded.path?.let { File(it).readBytes() }
                ?: return Result.failure(IOException("Downloaded cover had no path"))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            return Result.failure(error)
        }

        val stored = appArtworkWriter.apply(bytes = bytes, songIds = songIds, albumId = albumId)
        // A cover the store failed to write is a cover this pass did not apply,
        // whatever the download did: treating it as a download failure is what
        // keeps a full disk from being remembered as forty found covers and
        // chaining the pass into re-fetching them forever.
        return if (stored) Result.success(Unit) else Result.failure(IOException("Could not store the cover"))
    }

    companion object {
        private const val TAG = "AutoCoverArtFetcher"

        /**
         * Confidence required to apply a cover unattended, on the 0..1 scale
         * [CoverArtQuery] produces. Comfortably above a coincidental match,
         * below the near-exact score an identical title and artist would give.
         */
        internal const val MIN_AUTO_SCORE = 0.7f

        /**
         * Searches or applies that can fail in a row before the pass gives up
         * on this run.
         *
         * A handful in a row is a network or storage problem rather than a
         * library one, and there is nothing to learn by asking about every
         * remaining album.
         */
        private const val MAX_CONSECUTIVE_FAILURES = 5

        /** Albums touched per pass, so an unattended run stays bounded. */
        private const val DEFAULT_ALBUM_LIMIT = 40

        /** Dead ends held back before a preferences write, which rewrites the file. */
        private const val MISSED_CHECKPOINT_SIZE = 5
        private const val PER_ALBUM_DELAY_MS = 1_100L
    }
}

package com.theveloper.pixelplay.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.theveloper.pixelplay.data.database.EngagementDao
import com.theveloper.pixelplay.data.database.SongEngagementEntity
import com.theveloper.pixelplay.data.model.Song
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.io.File
import java.util.Calendar
import java.util.LinkedHashSet
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DailyMixManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engagementDao: EngagementDao
) {

    private val gson = Gson()
    private val legacyScoresFile = File(context.filesDir, "song_scores.json")
    private val fileLock = Any()
    private val statsType = object : TypeToken<MutableMap<String, SongEngagementStats>>() {}.type

    // P2-2: Async migration scope — migration runs on IO without blocking the main thread.
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Deferred result: null if no migration needed, completes when done.
    private val migrationDeferred: Deferred<Unit>

    // Flag to track if we've migrated legacy data
    private var legacyMigrationComplete = false

    data class SongEngagementStats(
        val playCount: Int = 0,
        val totalPlayDurationMs: Long = 0L,
        val lastPlayedTimestamp: Long = 0L
    )

    init {
        // P2-2: Launch migration asynchronously — does NOT block the calling thread.
        // Any method that needs migrated data should call migrationDeferred.await() first.
        migrationDeferred = managerScope.async {
            migrateLegacyDataIfNeeded()
        }
    }

    /**
     * Migrates engagements from legacy JSON file to Room database.
     * This runs once on startup if the legacy file exists.
     * P2-2: This is now a suspend fun running on an IO coroutine, so no runBlocking needed.
     * The synchronized block only guards the file read (no suspension points inside).
     */
    private suspend fun migrateLegacyDataIfNeeded() {
        if (legacyMigrationComplete || !legacyScoresFile.exists()) {
            legacyMigrationComplete = true
            return
        }

        // Read legacy data within the lock (only file I/O —  no suspend calls allowed inside synchronized)
        val entitiesToInsert: List<SongEngagementEntity>? = synchronized(fileLock) {
            if (legacyMigrationComplete) return@synchronized null

            try {
                val legacyData = readLegacyEngagementsLocked()
                if (legacyData.isNotEmpty()) {
                    legacyData.map { (songId, stats) ->
                        SongEngagementEntity(
                            songId = songId,
                            playCount = stats.playCount.coerceAtLeast(0),
                            totalPlayDurationMs = stats.totalPlayDurationMs.coerceAtLeast(0L),
                            lastPlayedTimestamp = stats.lastPlayedTimestamp.coerceAtLeast(0L)
                        )
                    }
                } else null
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read legacy engagement data", e)
                null
            }
        }

        // Perform the suspend DB call outside the synchronized block
        if (entitiesToInsert != null) {
            try {
                engagementDao.upsertEngagements(entitiesToInsert)
                Log.i(TAG, "Migrated ${entitiesToInsert.size} engagement records from JSON to Room")

                // Rename legacy file as backup instead of deleting
                val backupFile = File(context.filesDir, "song_scores.json.bak")
                legacyScoresFile.renameTo(backupFile)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to insert legacy engagement data into Room", e)
            }
        }

        legacyMigrationComplete = true
    }

    /**
     * Reads engagements from Room database.
     * P2-2: Awaits migration completion before querying, ensuring data consistency
     * without blocking any thread at startup.
     */
    private suspend fun readEngagements(): Map<String, SongEngagementStats> {
        migrationDeferred.await() // Only waits if migration is still running
        return engagementDao.getAllEngagements().associate { entity ->
            entity.songId to SongEngagementStats(
                playCount = entity.playCount,
                totalPlayDurationMs = entity.totalPlayDurationMs,
                lastPlayedTimestamp = entity.lastPlayedTimestamp
            )
        }
    }

    /**
     * Legacy method to read from JSON file during migration.
     */
    private fun readLegacyEngagementsLocked(): MutableMap<String, SongEngagementStats> {
        if (!legacyScoresFile.exists()) {
            return mutableMapOf()
        }

        val raw = runCatching { legacyScoresFile.readText() }
            .onFailure { Log.e(TAG, "Failed to read legacy song scores file", it) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return mutableMapOf()

        return runCatching {
            val element = gson.fromJson(raw, JsonElement::class.java)
            parseEngagementElement(element)
        }.getOrElse { throwable ->
            Log.e(TAG, "Failed to parse legacy song scores file", throwable)
            mutableMapOf()
        }
    }

    private fun parseEngagementElement(element: JsonElement?): MutableMap<String, SongEngagementStats> {
        if (element == null || element.isJsonNull) {
            return mutableMapOf()
        }

        if (element.isJsonObject) {
            return parseEngagementObject(element.asJsonObject)
        }

        return runCatching {
            val parsed: MutableMap<String, SongEngagementStats> = gson.fromJson(element, statsType)
            parsed.mapValuesTo(mutableMapOf()) { (_, stats) -> sanitizeStats(stats) }
        }.getOrElse {
            Log.w(TAG, "Unsupported song engagement format, ignoring it")
            mutableMapOf()
        }
    }

    private fun parseEngagementObject(obj: JsonObject): MutableMap<String, SongEngagementStats> {
        val result = mutableMapOf<String, SongEngagementStats>()
        for ((key, value) in obj.entrySet()) {
            val stats = parseStatsValue(key, value)
            if (stats != null) {
                result[key] = stats
            } else {
                Log.w(TAG, "Skipping song engagement entry for \"$key\" because it could not be parsed: $value")
            }
        }
        return result
    }

    private fun parseStatsValue(key: String, value: JsonElement): SongEngagementStats? {
        if (value.isJsonObject) {
            val parsedStats = runCatching {
                gson.fromJson(value, SongEngagementStats::class.java)
            }.getOrNull()

            if (parsedStats != null) {
                return sanitizeStats(parsedStats)
            }

            val extracted = extractScore(value)
            if (extracted != null) {
                return SongEngagementStats(playCount = extracted)
            }
        } else {
            val extracted = extractScore(value)
            if (extracted != null) {
                return SongEngagementStats(playCount = extracted)
            }
        }

        Log.w(TAG, "Encountered unsupported engagement value for \"$key\": $value")
        return null
    }

    private fun extractScore(value: JsonElement): Int? {
        if (value.isJsonPrimitive) {
            val primitive = value.asJsonPrimitive
            return when {
                primitive.isNumber -> primitive.asNumber.toInt()
                primitive.isString -> primitive.asString.toIntOrNull()
                else -> null
            }
        }

        if (value.isJsonObject) {
            val obj = value.asJsonObject
            for (key in SCORE_KEY_CANDIDATES) {
                val candidate = obj.get(key)
                if (candidate != null && candidate.isJsonPrimitive) {
                    val primitive = candidate.asJsonPrimitive
                    val parsed = when {
                        primitive.isNumber -> primitive.asNumber.toInt()
                        primitive.isString -> primitive.asString.toIntOrNull()
                        else -> null
                    }
                    if (parsed != null) {
                        return parsed
                    }
                }
            }
        }

        return null
    }

    private fun sanitizeStats(stats: SongEngagementStats): SongEngagementStats {
        return stats.copy(
            playCount = stats.playCount.coerceAtLeast(0),
            totalPlayDurationMs = stats.totalPlayDurationMs.coerceAtLeast(0L),
            lastPlayedTimestamp = stats.lastPlayedTimestamp.coerceAtLeast(0L)
        )
    }

    /**
     * Records a song play using Room's atomic upsert operation.
     * More efficient than JSON read-modify-write.
     */
    suspend fun recordPlay(
        songId: String,
        songDurationMs: Long = 0L,
        timestamp: Long = System.currentTimeMillis()
    ) {
        engagementDao.recordPlay(
            songId = songId,
            durationMs = songDurationMs.coerceAtLeast(0L),
            timestamp = timestamp.coerceAtLeast(0L)
        )
    }

    suspend fun incrementScore(songId: String) {
        recordPlay(songId)
    }

    suspend fun getScore(songId: String): Int {
        return engagementDao.getPlayCount(songId) ?: 0
    }

    suspend fun getEngagementStats(songId: String): SongEngagementStats? {
        return engagementDao.getEngagement(songId)?.let { entity ->
            SongEngagementStats(
                playCount = entity.playCount,
                totalPlayDurationMs = entity.totalPlayDurationMs,
                lastPlayedTimestamp = entity.lastPlayedTimestamp
            )
        }
    }

    suspend fun getAllEngagementStats(): Map<String, SongEngagementStats> {
        return readEngagements()
    }

    private suspend fun computeRankedSongs(
        allSongs: List<Song>,
        favoriteSongIds: Set<String>,
        random: java.util.Random
    ): List<RankedSong> {
        if (allSongs.isEmpty()) return emptyList()

        val engagements = readEngagements()
        val songById = allSongs.associateBy { it.id }
        val now = System.currentTimeMillis()

        val artistAffinity = mutableMapOf<Long, Double>()
        val genreAffinity = mutableMapOf<String, Double>()

        engagements.forEach { (songId, stats) ->
            val song = songById[songId] ?: return@forEach
            val weight = stats.playCount.toDouble() + (stats.totalPlayDurationMs / 60000.0)
            if (weight <= 0) return@forEach
            artistAffinity.merge(song.artistId, weight, Double::plus)
            normalizeGenreKey(song.genre)?.let { genreAffinity.merge(it, weight, Double::plus) }
        }

        val favoriteArtistWeights = mutableMapOf<Long, Int>()
        favoriteSongIds.forEach { id ->
            val song = songById[id] ?: return@forEach
            favoriteArtistWeights.merge(song.artistId, 1, Int::plus)
        }

        val maxPlayCount = engagements.values.maxOfOrNull { it.playCount }?.takeIf { it > 0 } ?: 1
        val maxDuration = engagements.values.maxOfOrNull { it.totalPlayDurationMs }?.takeIf { it > 0L } ?: 1L
        val maxArtistAffinity = artistAffinity.values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
        val maxGenreAffinity = genreAffinity.values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
        val maxFavoriteArtist = favoriteArtistWeights.values.maxOrNull()?.takeIf { it > 0 } ?: 1

        return allSongs.map { song ->
            val stats = engagements[song.id]
            val playCountScore = (stats?.playCount?.toDouble() ?: 0.0) / maxPlayCount
            val durationScore = (stats?.totalPlayDurationMs?.toDouble() ?: 0.0) / maxDuration
            val affinityScore = (playCountScore * 0.7 + durationScore * 0.3).coerceIn(0.0, 1.0)

            val genreKey = normalizeGenreKey(song.genre)
            val artistPreference = artistAffinity[song.artistId]?.div(maxArtistAffinity) ?: 0.0
            val genrePreference = genreKey?.let { (genreAffinity[it] ?: 0.0) / maxGenreAffinity } ?: 0.0
            val favoriteArtistPreference = favoriteArtistWeights[song.artistId]?.toDouble()?.div(maxFavoriteArtist) ?: 0.0
            val preferenceScore = if (genreKey == null) {
                (artistPreference * 0.6) + (favoriteArtistPreference * 0.4)
            } else {
                (artistPreference * 0.45) +
                    (genrePreference * 0.35) +
                    (favoriteArtistPreference * 0.20)
            }

            val recencyScore = computeRecencyScore(stats?.lastPlayedTimestamp, now)
            val noveltyScore = computeNoveltyScore(song.dateAdded, now)
            val favoriteScore = if (favoriteSongIds.contains(song.id)) 1.0 else 0.0
            val baselineScore = if (stats == null) 0.1 else 0.0
            val noise = random.nextDouble() * 0.005 // Significantly reduced noise

            val finalScore = (preferenceScore * 0.45) +
                (affinityScore * 0.25) +
                (recencyScore * 0.15) +
                (favoriteScore * 0.1) +
                (noveltyScore * 0.05) +
                baselineScore +
                noise

            val discoveryScore = ((1.0 - affinityScore).coerceIn(0.0, 1.0) * 0.6) +
                (noveltyScore * 0.25) +
                (preferenceScore * 0.15)

            RankedSong(
                song = song,
                finalScore = if (finalScore.isNaN() || finalScore.isInfinite()) 0.0 else finalScore,
                discoveryScore = if (discoveryScore.isNaN() || discoveryScore.isInfinite()) 0.0 else discoveryScore,
                affinityScore = affinityScore,
                recencyScore = recencyScore,
                noveltyScore = noveltyScore,
                favoriteScore = favoriteScore
            )
        }
            .sortedWith(compareByDescending<RankedSong> { it.finalScore }.thenBy { it.song.id })
    }

    suspend fun generateDailyMix(
        allSongs: List<Song>,
        favoriteSongIds: Set<String> = emptySet(),
        limit: Int = 30
    ): List<Song> {
        if (allSongs.isEmpty()) {
            return emptyList()
        }

        val calendar = Calendar.getInstance()
        val seed = calendar.get(Calendar.YEAR) * 1000 + calendar.get(Calendar.DAY_OF_YEAR)
        val random = java.util.Random(seed.toLong())

        val rankedSongs = computeRankedSongs(allSongs, favoriteSongIds, random)
        if (rankedSongs.isEmpty()) {
            return allSongs.shuffled(random).take(limit.coerceAtMost(allSongs.size))
        }

        val diversityState = DiversityState()
        val selected = pickWithDiversity(rankedSongs, favoriteSongIds, limit, diversityState)
        if (selected.size >= limit || selected.size == rankedSongs.size) {
            return selected
        }

        val remainingRanked = rankedSongs.filterNot { candidate ->
            selected.any { it.id == candidate.song.id }
        }
        val quotaFill = pickWithDiversity(remainingRanked, favoriteSongIds, limit - selected.size, diversityState)
        val combined = (selected + quotaFill).distinctBy { it.id }.toMutableList()

        if (combined.size < limit) {
            val remaining = allSongs
                .filterNot { song -> combined.any { it.id == song.id } }
                .shuffled(random)
            for (song in remaining) {
                combined.add(song)
                if (combined.size >= limit) break
            }
        }

        return combined.take(limit.coerceAtMost(combined.size))
    }

    suspend fun generateYourMix(
        allSongs: List<Song>,
        favoriteSongIds: Set<String> = emptySet(),
        limit: Int = 60,
        favoriteWeightPercent: Int = 30,
        coreWeightPercent: Int = 45
    ): List<Song> {
        if (allSongs.isEmpty()) {
            return emptyList()
        }

        val calendar = Calendar.getInstance()
        val seed = calendar.get(Calendar.YEAR) * 1000 + calendar.get(Calendar.DAY_OF_YEAR) + 17
        val random = java.util.Random(seed.toLong())
        val rankedSongs = computeRankedSongs(allSongs, favoriteSongIds, random)

        if (rankedSongs.isEmpty()) {
            return allSongs.shuffled(random).take(limit.coerceAtMost(allSongs.size))
        }

        val favoriteSectionSize = (limit * (favoriteWeightPercent / 100.0)).toInt().coerceAtLeast(0).coerceAtMost(limit)
        val coreSectionSize = (limit * (coreWeightPercent / 100.0)).toInt().coerceAtLeast(0).coerceAtMost(limit - favoriteSectionSize)
        val discoverySectionSize = (limit - favoriteSectionSize - coreSectionSize).coerceAtLeast(0)

        val diversityState = DiversityState()

        val favoriteSection = pickWithDiversity(
            rankedSongs.filter { favoriteSongIds.contains(it.song.id) },
            favoriteSongIds,
            favoriteSectionSize,
            diversityState
        )

        val alreadySelectedIds = favoriteSection.map { it.id }.toMutableSet()

        val coreSection = pickWithDiversity(
            rankedSongs.filterNot { alreadySelectedIds.contains(it.song.id) },
            favoriteSongIds,
            coreSectionSize,
            diversityState
        )

        alreadySelectedIds.addAll(coreSection.map { it.id })

        val discoveryCandidates = rankedSongs
            .filterNot { alreadySelectedIds.contains(it.song.id) }
            .sortedWith(compareByDescending<RankedSong> { it.discoveryScore }.thenBy { it.song.id })

        val discoverySection = pickWithDiversity(
            discoveryCandidates,
            favoriteSongIds,
            discoverySectionSize,
            diversityState
        )

        val orderedResult = LinkedHashSet<Song>()
        orderedResult.addAll(favoriteSection)
        orderedResult.addAll(coreSection)
        orderedResult.addAll(discoverySection)

        if (orderedResult.size < limit) {
            val quotaFill = pickWithDiversity(
                rankedSongs.filterNot { orderedResult.any { selected -> selected.id == it.song.id } },
                favoriteSongIds,
                limit - orderedResult.size,
                diversityState
            )
            orderedResult.addAll(quotaFill)
        }

        if (orderedResult.size < limit) {
            val filler = allSongs
                .filterNot { orderedResult.any { selected -> selected.id == it.id } }
                .shuffled(random)
            for (song in filler) {
                orderedResult.add(song)
                if (orderedResult.size >= limit) break
            }
        }

        return orderedResult.take(limit.coerceAtMost(orderedResult.size))
    }

    /**
     * Gets a localized, non-randomized, highly curated list of candidates
     * optimized to be sent to an LLM for thematic playlist generation.
     */
    suspend fun getTopCandidatesForAi(
        allSongs: List<Song>,
        favoriteSongIds: Set<String> = emptySet(),
        limit: Int = 100
    ): List<Song> {
        if (allSongs.isEmpty()) {
            return emptyList()
        }

        // Use a static seed per day to ensure the AI doesn't get wildly different lists
        // if called multiple times in one day, preserving prompt caching.
        val calendar = Calendar.getInstance()
        val seed = calendar.get(Calendar.YEAR) * 1000 + calendar.get(Calendar.DAY_OF_YEAR) + 42
        val random = java.util.Random(seed.toLong())

        val rankedSongs = computeRankedSongs(allSongs, favoriteSongIds, random)
        if (rankedSongs.isEmpty()) {
            return allSongs.take(limit.coerceAtMost(allSongs.size))
        }

        return rankedSongs.take(limit).map { it.song }
    }

    private fun pickWithDiversity(
        rankedSongs: List<RankedSong>,
        favoriteSongIds: Set<String>,
        limit: Int,
        state: DiversityState = DiversityState()
    ): List<Song> {
        if (limit <= 0 || rankedSongs.isEmpty()) return emptyList()

        val selected = mutableListOf<Song>()

        for (candidate in rankedSongs) {
            if (selected.size >= limit) break
            val artistId = candidate.song.artistId
            val maxPerArtist = if (favoriteSongIds.contains(candidate.song.id)) 3 else 2
            val currentCount = state.artistCounts.getOrDefault(artistId, 0)
            if (currentCount >= maxPerArtist) continue

            val genreKey = normalizeGenreKey(candidate.song.genre)
            val maxPerGenre = if (genreKey == null) {
                maxUnknownGenreCount(limit, favoriteSongIds.contains(candidate.song.id))
            } else {
                maxKnownGenreCount(limit, favoriteSongIds.contains(candidate.song.id))
            }
            if (genreKey == null) {
                if (state.unknownGenreCount >= maxPerGenre) continue
            } else {
                val currentGenreCount = state.genreCounts.getOrDefault(genreKey, 0)
                if (currentGenreCount >= maxPerGenre) continue
            }

            selected += candidate.song
            state.artistCounts[artistId] = currentCount + 1
            if (genreKey == null) {
                state.unknownGenreCount += 1
            } else {
                state.genreCounts[genreKey] = state.genreCounts.getOrDefault(genreKey, 0) + 1
            }
        }

        if (selected.size < limit) {
            for (candidate in rankedSongs) {
                if (selected.size >= limit) break
                if (selected.any { it.id == candidate.song.id }) continue
                selected += candidate.song
            }
        }

        return selected.take(limit)
    }

    private fun normalizeGenreKey(rawGenre: String?): String? {
        val normalized = rawGenre
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
            ?: return null

        return if (normalized.contains("unknown")) {
            null
        } else {
            normalized
        }
    }

    private fun maxKnownGenreCount(limit: Int, isFavorite: Boolean): Int {
        val baseCap = when {
            limit <= 12 -> 2
            limit <= 30 -> 3
            else -> 4
        }
        return baseCap + if (isFavorite) 1 else 0
    }

    private fun maxUnknownGenreCount(limit: Int, isFavorite: Boolean): Int {
        val baseCap = when {
            limit <= 12 -> 1
            limit <= 30 -> 2
            else -> 3
        }
        return baseCap + if (isFavorite) 1 else 0
    }

    private fun computeRecencyScore(lastPlayedTimestamp: Long?, now: Long): Double {
        if (lastPlayedTimestamp == null || lastPlayedTimestamp <= 0L) return 0.6
        val daysSinceLastPlay = ((now - lastPlayedTimestamp).coerceAtLeast(0L) / TimeUnit.DAYS.toMillis(1)).toDouble()
        return when {
            daysSinceLastPlay < 1 -> 0.2
            daysSinceLastPlay < 3 -> 0.5
            daysSinceLastPlay < 7 -> 0.7
            daysSinceLastPlay < 14 -> 0.85
            else -> 1.0
        }
    }

    private fun computeNoveltyScore(dateAdded: Long, now: Long): Double {
        if (dateAdded <= 0L) return 0.0
        val dateAddedMillis = if (dateAdded < 10_000_000_000L) {
            TimeUnit.SECONDS.toMillis(dateAdded)
        } else {
            dateAdded
        }
        val daysSinceAdded = ((now - dateAddedMillis).coerceAtLeast(0L) / TimeUnit.DAYS.toMillis(1)).toDouble()
        return (1.0 - (daysSinceAdded / 60.0)).coerceIn(0.0, 1.0)
    }

    private data class RankedSong(
        val song: Song,
        val finalScore: Double,
        val discoveryScore: Double,
        val affinityScore: Double,
        val recencyScore: Double,
        val noveltyScore: Double,
        val favoriteScore: Double
    )

    private data class DiversityState(
        val artistCounts: MutableMap<Long, Int> = mutableMapOf(),
        val genreCounts: MutableMap<String, Int> = mutableMapOf(),
        var unknownGenreCount: Int = 0
    )

    companion object {
        private const val TAG = "DailyMixManager"
        private val SCORE_KEY_CANDIDATES = listOf("score", "count", "value")
    }
}

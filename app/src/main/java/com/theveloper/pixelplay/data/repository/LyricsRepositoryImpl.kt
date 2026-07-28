package com.theveloper.pixelplay.data.repository

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.util.LruCache
import androidx.core.net.toUri
import com.google.gson.Gson
import com.kyant.taglib.TagLib
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.database.MusicDao
import com.theveloper.pixelplay.data.model.Lyrics
import com.theveloper.pixelplay.data.model.SyncedLine
import com.theveloper.pixelplay.data.model.LyricsSourcePreference
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.network.lyrics.LrcLibApiService
import com.theveloper.pixelplay.data.network.lyrics.LrcLibResponse
import com.theveloper.pixelplay.utils.LyricsImportSecurity
import com.theveloper.pixelplay.utils.LyricsImportValidationResult
import com.theveloper.pixelplay.utils.LogUtils
import com.theveloper.pixelplay.utils.LyricsUtils
import com.theveloper.pixelplay.utils.MultiLangRomanizer
import com.theveloper.pixelplay.utils.NetworkRetryUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import okhttp3.OkHttpClient
import okhttp3.Request

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private val EMBEDDED_LYRICS_KEYS = listOf("LYRICS", "SYNCEDLYRICS", "TTML", "UNSYNCEDLYRICS")

private fun Lyrics.isValid(): Boolean = !synced.isNullOrEmpty() || !plain.isNullOrEmpty()

internal fun parseBestEmbeddedLyricsField(propertyMap: Map<String, Array<String>>?): Lyrics? {
    var firstPlainLyrics: Lyrics? = null

    EMBEDDED_LYRICS_KEYS.forEach { key ->
        propertyMap?.get(key).orEmpty().forEach { field ->
            if (field.isBlank()) return@forEach

            val parsedLyrics = LyricsUtils.parseLyrics(field)
            if (!parsedLyrics.isValid()) return@forEach

            val localLyrics = parsedLyrics.copy(areFromRemote = false)
            if (!localLyrics.synced.isNullOrEmpty()) return localLyrics
            if (firstPlainLyrics == null) firstPlainLyrics = localLyrics
        }
    }

    return firstPlainLyrics
}

/**
 * LyricsData for JSON disk cache (matches Rhythm's format)
 */
private data class LyricsData(
    val plainLyrics: String?,
    val syncedLyrics: String?,
    val wordByWordLyrics: String? = null
) {
    fun hasLyrics(): Boolean =
        !plainLyrics.isNullOrBlank() ||
            !syncedLyrics.isNullOrBlank() ||
            !wordByWordLyrics.isNullOrBlank()
}

private data class RemoteSearchStrategy(
    val name: String,
    val request: suspend () -> Array<LrcLibResponse>?
)

private data class RemoteSearchBatch(
    val strategyName: String,
    val responses: List<LrcLibResponse>
)

private enum class RemoteLyricsMatchMode {
    AUTOMATIC,
    CANDIDATE
}

private data class RemoteLyricsMatch(
    val response: LrcLibResponse,
    val score: Int
)

@Singleton
class LyricsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val lrcLibApiService: LrcLibApiService,
    private val lyricsDao: com.theveloper.pixelplay.data.database.LyricsDao,
    private val okHttpClient: OkHttpClient
) : LyricsRepository {


    companion object {
        private const val TAG = "LyricsRepository"
        
        // Cache sizes (matching Rhythm)
        private const val MAX_LYRICS_CACHE_SIZE = 150
        
        // API rate limiting constants (matching Rhythm)
        private const val LRCLIB_MIN_DELAY = 100L
        private const val MAX_CALLS_PER_MINUTE = 30
        private const val AMLLDB_NCM_LYRICS_BASE_URL = "https://amlldb.bikonoo.com/lyrics/ncm-lyrics/"
        private const val NETWORK_RETRY_ATTEMPTS = 3
        private const val NETWORK_RETRY_INITIAL_DELAY_MS = 500L

        private val BRACKETED_QUALIFIER_REGEX = Regex("""[\(\[\{\uFF08\uFF3B\uFF5B\u3010\u300E\u300C\u3014\u3008\u300A]([^)\]\}\uFF09\uFF3D\uFF5D\u3011\u300F\u300D\u3015\u3009\u300B]*)[\)\]\}\uFF09\uFF3D\uFF5D\u3011\u300F\u300D\u3015\u3009\u300B]""")
        private val FEATURE_QUALIFIER_REGEX = Regex("""\b(feat(?:uring)?|ft)\.?\b""", RegexOption.IGNORE_CASE)
        private val TITLE_SEPARATOR_REGEX = Regex("""\s*[-\u2013\u2014:\uFF0D\u00B7\u30FB]\s*""")
        private val TIMING_VARIANT_KEYWORDS = setOf(
            "remix",
            "mix",
            "mashup",
            "bootleg",
            "edit",
            "extended",
            "radio",
            "club",
            "vip",
            "dub",
            "live",
            "acoustic",
            "unplugged",
            "sped",
            "slowed",
            "nightcore",
            "instrumental",
            "karaoke",
            "cover",
            "demo",
            "version",
            "rework",
            "flip",
            "refix",
            "opening",
            "ending",
            "op",
            "ed",
            "theme",
            "tv",
            "size",
            "ver",
            "full",
            "movie",
            "ost",
            "soundtrack",
            "background",
            "bgm",
            "short",
            "long",
            "reprise",
            "intro",
            "outro",
            "medley",
            "bonus"
        )
        private val TITLE_DROP_QUALIFIERS = setOf(
            "explicit",
            "clean",
            "mono",
            "stereo",
            "official audio",
            "official video",
            "hi-res",
            "high-res",
            "mqa"
        )
        private val UNKNOWN_ARTISTS = setOf(
            "",
            "<unknown>",
            "unknown",
            "unknown artist",
            "various artists",
            "various"
        )
        private val ARTIST_CONNECTOR_TOKENS = setOf(
            "feat",
            "featuring",
            "ft",
            "and",
            "with",
            "x",
            "vs",
            "the"
        )
    }

    // Repository scope for background tasks
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Thread-safe LRU cache to avoid race conditions across concurrent lyrics requests.
    private val lyricsCache = LruCache<String, Lyrics>(MAX_LYRICS_CACHE_SIZE)

    // Thread-safe rate limiting state.
    private val lastApiCalls = ConcurrentHashMap<String, Long>()
    private val apiCallCounts = ConcurrentHashMap<String, RateLimitWindow>()

    // Gson for JSON cache
    private val gson = Gson()

    /**
     * Executes multiple remote search strategies in parallel and returns the first non-empty result set.
     * This keeps search responsive while preserving the existing "first useful strategy wins" behavior.
     */
    private suspend fun runSearchStrategiesFast(
        strategies: List<RemoteSearchStrategy>
    ): List<LrcLibResponse> = coroutineScope {
        if (strategies.isEmpty()) return@coroutineScope emptyList()

        val channel = Channel<RemoteSearchBatch>(capacity = strategies.size)
        val jobs = strategies.map { strategy ->
            launch {
                val responses = runCatching {
                    withNetworkRetry(operationName = "lrclib_strategy:${strategy.name}") {
                        strategy.request()
                    }
                }.getOrElse { error ->
                    Log.d(
                        TAG,
                        "Strategy ${strategy.name} failed after retries: ${error.message}"
                    )
                    null
                }
                    ?.toList()
                    .orEmpty()
                channel.trySend(
                    RemoteSearchBatch(
                        strategyName = strategy.name,
                        responses = responses
                    )
                )
            }
        }

        repeat(strategies.size) {
            val batch = channel.receive()
            if (batch.responses.isNotEmpty()) {
                Log.d(TAG, "Fast search hit from strategy: ${batch.strategyName} (${batch.responses.size} results)")
                jobs.forEach { it.cancel() }
                channel.close()
                return@coroutineScope batch.responses.distinctBy { it.id }
            }
        }

        channel.close()
        emptyList()
    }

    private suspend fun <T> withNetworkRetry(
        operationName: String,
        maxAttempts: Int = NETWORK_RETRY_ATTEMPTS,
        initialDelayMs: Long = NETWORK_RETRY_INITIAL_DELAY_MS,
        shouldRetry: (Throwable) -> Boolean = { it is IOException || (it is HttpException && (it.code() == 429 || it.code() >= 500)) },
        block: suspend () -> T
    ): T {
        return NetworkRetryUtils.withNetworkRetry(
            operationName = operationName,
            maxAttempts = maxAttempts,
            initialDelayMs = initialDelayMs,
            shouldRetry = shouldRetry,
            onRetry = { attempt, attempts, throwable ->
                Log.d(
                    TAG,
                    "Retrying $operationName after failure ($attempt/$attempts): ${throwable.message}"
                )
            },
            block = block
        )
    }

    private fun Int.isRetryableHttpStatusCode(): Boolean {
        return this == 429 || this in 500..599
    }

    /**
     * Calculate delay needed before next API call (matching Rhythm)
     */
    private data class RateLimitWindow(
        val windowStartMillis: Long,
        val count: Int
    )

    private fun calculateApiDelay(apiName: String, currentTime: Long): Long {
        val lastCall = lastApiCalls[apiName] ?: 0L
        val minDelay = when (apiName.lowercase()) {
            "lrclib" -> LRCLIB_MIN_DELAY
            else -> 250L
        }

        val timeSinceLastCall = currentTime - lastCall
        if (timeSinceLastCall < minDelay) {
            return minDelay - timeSinceLastCall
        }

        // Check if we're making too many calls in the current fixed 60s window
        val window = apiCallCounts[apiName]
        val callsInLastMinute = if (window != null && (currentTime - window.windowStartMillis) < 60000L) {
            window.count
        } else {
            0
        }
        if (callsInLastMinute >= MAX_CALLS_PER_MINUTE) {
            // Exponential backoff
            return minDelay * 2
        }

        return 0L
    }

    /**
     * Update last API call timestamp (matching Rhythm)
     */
    private fun updateLastApiCall(apiName: String, timestamp: Long) {
        lastApiCalls[apiName] = timestamp

        val currentWindow = apiCallCounts[apiName]
        val updatedWindow = if (currentWindow == null || (timestamp - currentWindow.windowStartMillis) >= 60000L) {
            RateLimitWindow(
                windowStartMillis = timestamp,
                count = 1
            )
        } else {
            currentWindow.copy(count = currentWindow.count + 1)
        }
        apiCallCounts[apiName] = updatedWindow
    }

    /**
     * Main lyrics fetching method with source preference support (matching Rhythm)
     */
    override suspend fun getLyrics(
        song: Song,
        sourcePreference: LyricsSourcePreference,
        forceRefresh: Boolean
    ): Lyrics? = withContext(Dispatchers.IO) {
        val cacheKey = generateCacheKey(song.id)
        val isNeteaseTrack = isNeteaseSong(song)
        
        Log.d(TAG, "===== FETCH LYRICS START: ${song.displayArtist} - ${song.title} (forceRefresh=$forceRefresh, source=$sourcePreference) =====")

        // Check in-memory cache unless force refresh (early return - matching Rhythm)
        if (!forceRefresh && !isNeteaseTrack) {
            lyricsCache.get(cacheKey)?.let { cached ->
                Log.d(TAG, "===== RETURNING IN-MEMORY CACHED LYRICS =====")
                return@withContext cached
            }
            Log.d(TAG, "===== NO IN-MEMORY CACHE HIT, proceeding to fetch =====")
        } else if (!forceRefresh && isNeteaseTrack) {
            Log.d(TAG, "===== BYPASSING IN-MEMORY CACHE FOR NETEASE TRACK =====")
        } else {
            Log.d(TAG, "===== FORCE REFRESH - BYPASSING IN-MEMORY CACHE =====")
        }

        if (!forceRefresh) {
            loadStoredLyrics(song, cacheKey, includeMemoryCache = false)?.let { stored ->
                lyricsCache.put(cacheKey, stored.first)
                Log.d(TAG, "===== RETURNING STORED LYRICS WITHOUT REMOTE FETCH =====")
                return@withContext stored.first
            }
        }

        // Define source fetchers (matching Rhythm pattern)
        val fetchFromLocal: suspend () -> Lyrics? = {
            findLocalLyricsFile(song)
        }

        val fetchFromEmbedded: suspend () -> Lyrics? = {
            loadEmbeddedLyricsFromMetadata(song)
        }

        val fetchFromAPI: suspend () -> Lyrics? = {
            fetchLyricsFromAPI(song)
        }

        // Try sources in order based on preference, with fallback (matching Rhythm)
        val sourceFetchers = when (sourcePreference) {
            LyricsSourcePreference.API_FIRST -> listOf(fetchFromAPI, fetchFromEmbedded, fetchFromLocal)
            LyricsSourcePreference.EMBEDDED_FIRST -> listOf(fetchFromEmbedded, fetchFromAPI, fetchFromLocal)
            LyricsSourcePreference.LOCAL_FIRST -> listOf(fetchFromLocal, fetchFromEmbedded, fetchFromAPI)
        }

        // Try each source in order until we find lyrics (early return on success)
        for ((index, fetcher) in sourceFetchers.withIndex()) {
            try {
                val lyrics = fetcher()
                if (lyrics != null && lyrics.isValid()) {
                    val sourceName = when (index) {
                        0 -> when (sourcePreference) {
                            LyricsSourcePreference.API_FIRST -> "API"
                            LyricsSourcePreference.EMBEDDED_FIRST -> "Embedded"
                            LyricsSourcePreference.LOCAL_FIRST -> "Local"
                        }
                        1 -> when (sourcePreference) {
                            LyricsSourcePreference.API_FIRST -> "Embedded"
                            LyricsSourcePreference.EMBEDDED_FIRST -> "API"
                            LyricsSourcePreference.LOCAL_FIRST -> "Embedded"
                        }
                        else -> when (sourcePreference) {
                            LyricsSourcePreference.API_FIRST -> "Local"
                            LyricsSourcePreference.EMBEDDED_FIRST -> "Local"
                            LyricsSourcePreference.LOCAL_FIRST -> "API"
                        }
                    }
                    Log.d(TAG, "Found lyrics from $sourceName for: ${song.displayArtist} - ${song.title}")
                    
                    // Cache the result
                    lyricsCache.put(cacheKey, lyrics)
                    
                    // Save to JSON disk cache if from API
                    if (sourceName == "API") {
                        saveLocalLyricsJson(song, lyrics)
                    }
                    
                    return@withContext lyrics
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error fetching from source ${index + 1}: ${e.message}")
                // Continue to next source
            }
        }

        // No lyrics found from any source
        Log.d(TAG, "No lyrics found from any source for: ${song.displayArtist} - ${song.title}")
        return@withContext null
    }

    override suspend fun getStoredLyrics(song: Song): Pair<Lyrics, String>? = withContext(Dispatchers.IO) {
        val cacheKey = generateCacheKey(song.id)
        loadStoredLyrics(song, cacheKey, includeMemoryCache = true)?.also { stored ->
            lyricsCache.put(cacheKey, stored.first)
        }
    }

    /**
     * Fetches lyrics from LRCLIB API with rate limiting (matching Rhythm)
     */
    private suspend fun fetchLyricsFromAPI(song: Song): Lyrics? = withContext(Dispatchers.IO) {
        val isNetease = isNeteaseSong(song)

        if (isNetease) {
            val amlLyrics = fetchFromAmlldb(song)
            if (amlLyrics != null) {
                Log.d(TAG, "===== LOADED WORD-BY-WORD LYRICS FROM AMLLDB =====")
                return@withContext amlLyrics
            }
            Log.d(TAG, "AMLLDB unavailable for Netease song, falling back to cache/LRCLIB")
        }

        // Check JSON disk cache first (matching Rhythm)
        val cachedJson = loadLocalLyricsJson(song)
        if (cachedJson != null) {
            Log.d(TAG, "===== LOADED LYRICS FROM JSON DISK CACHE =====")
            return@withContext cachedJson
        }

        // Apply rate limiting
        val currentTime = System.currentTimeMillis()
        val delayNeeded = calculateApiDelay("lrclib", currentTime)
        if (delayNeeded > 0) {
            Log.d(TAG, "Rate limiting: waiting ${delayNeeded}ms before API call")
            delay(delayNeeded)
        }
        updateLastApiCall("lrclib", System.currentTimeMillis())

        try {
            val cleanArtist = song.displayArtist.trim().replace(BRACKETED_QUALIFIER_REGEX, "").trim()
            val cleanTitle = song.title.trim().replace(BRACKETED_QUALIFIER_REGEX, "").trim()
            val simplifiedArtist = cleanArtist.split(" feat.", " ft.", " featuring", " & ", " , ").first().trim()
            val simplifiedTitle = cleanTitle.split(" feat.", " ft.", " featuring", " (").first().trim()
            val useSimplifiedStrategy =
                simplifiedArtist != cleanArtist || simplifiedTitle != cleanTitle

            val searchStrategies = buildList {
                add(
                    RemoteSearchStrategy("track+artist") {
                        lrcLibApiService.searchLyrics(trackName = cleanTitle, artistName = cleanArtist)
                    }
                )
                add(
                    RemoteSearchStrategy("combined_query") {
                        lrcLibApiService.searchLyrics(query = "$cleanArtist $cleanTitle")
                    }
                )
                if (useSimplifiedStrategy) {
                    add(
                        RemoteSearchStrategy("simplified_track+artist") {
                            lrcLibApiService.searchLyrics(trackName = simplifiedTitle, artistName = simplifiedArtist)
                        }
                    )
                }

                // Romanized search strategy for non-Latin titles
                if (MultiLangRomanizer.isScriptThatNeedsRomanization(cleanTitle)) {
                    val romanTitle = romanizeForMatch(cleanTitle)
                    if (romanTitle != cleanTitle) {
                        add(RemoteSearchStrategy("romanized_track") {
                            lrcLibApiService.searchLyrics(trackName = romanTitle, artistName = cleanArtist)
                        })
                    }
                }
                // Romanized search strategy for non-Latin artists
                if (MultiLangRomanizer.isScriptThatNeedsRomanization(cleanArtist)) {
                    val romanArtist = romanizeForMatch(cleanArtist)
                    if (romanArtist != cleanArtist) {
                        add(RemoteSearchStrategy("romanized_artist") {
                            lrcLibApiService.searchLyrics(trackName = cleanTitle, artistName = romanArtist)
                        })
                    }
                }
                
                // Smart title cleanup strategy (removes leading digits/spaces and truncates at -, (, ))
                val smartTitle = cleanTitleSmart(cleanTitle)
                if (smartTitle != cleanTitle && smartTitle.isNotBlank()) {
                    Log.d(TAG, "Adding smart search strategy for: '$smartTitle' (orig: '$cleanTitle')")
                    add(RemoteSearchStrategy("smart_track_only") {
                        lrcLibApiService.searchLyrics(trackName = smartTitle)
                    })
                }
            }

            var results = runSearchStrategiesFast(searchStrategies)

            // Strategy 4: Aggressive fallback - remove artist and trim title at separators
            if (results.isEmpty()) {
                 // Include common CJK/Unicode separators: \uFF0D (fullwidth hyphen-minus), \u00B7 (middle dot), \u30FB (katakana middle dot)
                 val separators = charArrayOf('-', ',', '(', ')', ':', '\uFF0D', '\u00B7', '\u30FB')
                 val index = cleanTitle.indexOfAny(separators)
                 if (index != -1) {
                     val superCleanTitle = cleanTitle.substring(0, index).trim()
                     if (superCleanTitle.isNotEmpty()) {
                          Log.d(TAG, "Strategy 4: Searching with super simplified title: '$superCleanTitle' (no artist)")
                          val fallbackResults = runCatching {
                                withNetworkRetry(operationName = "lrclib_super_clean_search") {
                                    lrcLibApiService.searchLyrics(trackName = superCleanTitle)
                                }
                          }.getOrNull()
                          if (!fallbackResults.isNullOrEmpty()) {
                              results = fallbackResults.toList()
                          }
                     }
                 }
            }

            if (results.isEmpty()) {
                Log.d(TAG, "No results from LRCLIB API")
                return@withContext null
            }

            val bestMatch = rankRemoteLyricsMatches(
                song = song,
                responses = results,
                mode = RemoteLyricsMatchMode.AUTOMATIC
            ).firstOrNull()?.response

            if (bestMatch != null) {
                val rawLyrics = bestMatch.syncedLyrics ?: bestMatch.plainLyrics
                if (!rawLyrics.isNullOrBlank()) {
                    val parsedLyrics = LyricsUtils.parseLyrics(rawLyrics).copy(areFromRemote = true)
                    if (parsedLyrics.isValid()) {
                        Log.d(TAG, "LRCLIB lyrics found - Synced: ${!bestMatch.syncedLyrics.isNullOrBlank()}, Plain: ${!bestMatch.plainLyrics.isNullOrBlank()}")
                        
                        // Save to database
                        try {
                            val songIdLong = song.id.toLongOrNull()
                            if (songIdLong == null) {
                                Log.w(TAG, "Cannot persist lyrics to DB: non-numeric Song.id '${song.id}'")
                            } else {
                                lyricsDao.insert(
                                    com.theveloper.pixelplay.data.database.LyricsEntity(
                                        songId = songIdLong,
                                        content = rawLyrics,
                                        isSynced = !bestMatch.syncedLyrics.isNullOrBlank(),
                                        source = "remote"
                                    )
                                )
                            }
                        } catch (e: NumberFormatException) {
                            Log.w(TAG, "Skipping database save for non-numeric song ID: ${song.id} (possible streaming or external source). Lyrics will be cached in JSON.")
                        }
                        
                        return@withContext parsedLyrics
                    }
                }
            }

            return@withContext null
        } catch (e: Exception) {
            Log.e(TAG, "LRCLIB lyrics fetch failed: ${e.message}", e)
            return@withContext null
        }
    }

    private fun hasLyrics(response: LrcLibResponse): Boolean =
        !response.plainLyrics.isNullOrBlank() || !response.syncedLyrics.isNullOrBlank()

    private fun hasSyncedLyrics(response: LrcLibResponse): Boolean =
        !response.syncedLyrics.isNullOrBlank()

    private fun rankRemoteLyricsMatches(
        song: Song,
        responses: List<LrcLibResponse>,
        mode: RemoteLyricsMatchMode
    ): List<RemoteLyricsMatch> {
        val songDurationSeconds = song.duration / 1000.0
        if (songDurationSeconds <= 0.0) return emptyList()

        return responses
            .mapNotNull { response ->
                val score = remoteLyricsMatchScore(
                    song = song,
                    response = response,
                    mode = mode,
                    songDurationSeconds = songDurationSeconds
                ) ?: return@mapNotNull null
                RemoteLyricsMatch(response, score)
            }
            .sortedWith(
                compareByDescending<RemoteLyricsMatch> { it.score }
                    .thenByDescending { hasSyncedLyrics(it.response) }
                    .thenBy { abs(it.response.duration - songDurationSeconds) }
            )
    }

    private fun remoteLyricsMatchScore(
        song: Song,
        response: LrcLibResponse,
        mode: RemoteLyricsMatchMode,
        songDurationSeconds: Double
    ): Int? {
        if (!hasLyrics(response) || response.duration <= 0.0) return null
        if (!variantDescriptorsCompatible(song, response)) return null

        val hasSynced = hasSyncedLyrics(response)
        val durationTolerance = remoteDurationToleranceSeconds(songDurationSeconds, hasSynced, mode)
        val durationDiff = abs(response.duration - songDurationSeconds)
        if (durationDiff > durationTolerance) return null

        val titleScore = titleMatchScore(song.title, response.name, mode) ?: return null
        val artistScore = artistMatchScore(song.displayArtist, response.artistName)
        if (!isUnknownArtist(song.displayArtist) && artistScore == null) return null

        val durationScore = (durationTolerance - durationDiff).coerceAtLeast(0.0).toInt()
        val syncedScore = if (hasSynced) 10 else 0
        return titleScore + (artistScore ?: 0) + durationScore + syncedScore
    }

    private fun remoteDurationToleranceSeconds(
        songDurationSeconds: Double,
        hasSyncedLyrics: Boolean,
        mode: RemoteLyricsMatchMode
    ): Double {
        return when (mode) {
            RemoteLyricsMatchMode.AUTOMATIC -> {
                if (hasSyncedLyrics) {
                    (songDurationSeconds * 0.02).coerceIn(5.0, 8.0)
                } else {
                    (songDurationSeconds * 0.04).coerceIn(8.0, 15.0)
                }
            }
            RemoteLyricsMatchMode.CANDIDATE -> 15.0
        }
    }

    private fun titleMatchScore(songTitle: String, responseTitle: String, mode: RemoteLyricsMatchMode): Int? {
        val songBase = baseTitleForMatching(songTitle)
        val responseBase = baseTitleForMatching(responseTitle)
        if (songBase.isBlank() || responseBase.isBlank()) return null

        if (songBase == responseBase) return 70

        // Attempt Romanized match for non-Latin scripts
        if (MultiLangRomanizer.isScriptThatNeedsRomanization(songBase) || 
            MultiLangRomanizer.isScriptThatNeedsRomanization(responseBase)) {
            val songRoman = normalizeForMatch(romanizeForMatch(songBase))
            val responseRoman = normalizeForMatch(romanizeForMatch(responseBase))
            if (songRoman == responseRoman && songRoman.isNotBlank()) return 65
        }

        val songTokens = matchTokens(songBase)
        val responseTokens = matchTokens(responseBase)
        if (songTokens.isEmpty() || responseTokens.isEmpty()) return null

        if (songTokens.size == 1 || responseTokens.size == 1) {
            if (songTokens == responseTokens) return 60
            
            // Fuzzy match for single token CJK: if one contains the other
            val s1 = songBase.replace(" ", "")
            val s2 = responseBase.replace(" ", "")
            if (s1.isNotBlank() && s2.isNotBlank()) {
                if (s1.contains(s2) || s2.contains(s1)) return 55
            }
            
            return null
        }

        if (containsWholePhrase(responseBase, songBase) || containsWholePhrase(songBase, responseBase)) {
            return if (mode == RemoteLyricsMatchMode.AUTOMATIC) 58 else 54
        }

        val overlap = songTokens.intersect(responseTokens).size
        val songCoverage = overlap.toDouble() / songTokens.size
        val responseCoverage = overlap.toDouble() / responseTokens.size
        val requiredSongCoverage = if (mode == RemoteLyricsMatchMode.AUTOMATIC) 0.85 else 0.75
        val requiredResponseCoverage = if (mode == RemoteLyricsMatchMode.AUTOMATIC) 0.70 else 0.55

        return if (songCoverage >= requiredSongCoverage && responseCoverage >= requiredResponseCoverage) {
            45
        } else {
            null
        }
    }

    private fun artistMatchScore(songArtist: String, responseArtist: String): Int? {
        if (isUnknownArtist(songArtist)) return 0

        val songBase = normalizeForMatch(songArtist)
        val responseBase = normalizeForMatch(responseArtist)
        if (songBase.isBlank() || responseBase.isBlank()) return null

        if (songBase == responseBase) return 30
        
        // Attempt Romanized match
        if (MultiLangRomanizer.isScriptThatNeedsRomanization(songBase) || 
            MultiLangRomanizer.isScriptThatNeedsRomanization(responseBase)) {
            val songRoman = normalizeForMatch(romanizeForMatch(songBase))
            val responseRoman = normalizeForMatch(romanizeForMatch(responseBase))
            if (songRoman == responseRoman && songRoman.isNotBlank()) return 28
        }

        if (containsWholePhrase(responseBase, songBase) || containsWholePhrase(songBase, responseBase)) {
            return 22
        }

        val songTokens = artistTokens(songBase)
        val responseTokens = artistTokens(responseBase)
        if (songTokens.isEmpty() || responseTokens.isEmpty()) return null

        val overlap = songTokens.intersect(responseTokens).size
        val smallerArtistCoverage = overlap.toDouble() / minOf(songTokens.size, responseTokens.size)
        return if (smallerArtistCoverage >= 0.5) 12 else null
    }

    private fun variantDescriptorsCompatible(song: Song, response: LrcLibResponse): Boolean {
        val songVariants = timingVariantTokens(song.title) + timingVariantTokensFromFileName(song)
        val responseVariants = timingVariantTokens(response.name)

        if (songVariants.isEmpty()) {
            return responseVariants.isEmpty()
        }

        return responseVariants == songVariants
    }

    private fun baseTitleForMatching(title: String): String {
        var base = title.replace(Regex("""^\s*\d{1,3}\s*[\._-]\s+"""), "")

        base = BRACKETED_QUALIFIER_REGEX.replace(base) { match ->
            val qualifier = match.groupValues.getOrNull(1).orEmpty()
            if (shouldDropTitleQualifier(qualifier)) " " else " $qualifier "
        }

        var parts = TITLE_SEPARATOR_REGEX.split(base)
        while (parts.size > 1 && shouldDropTitleQualifier(parts.last())) {
            parts = parts.dropLast(1)
        }

        return normalizeForMatch(parts.joinToString(" "))
    }

    private fun shouldDropTitleQualifier(value: String): Boolean {
        val normalized = normalizeForMatch(value)
        if (normalized.isBlank()) return true
        return FEATURE_QUALIFIER_REGEX.containsMatchIn(value) ||
            timingVariantTokens(value).isNotEmpty() ||
            normalized in TITLE_DROP_QUALIFIERS
    }

    private fun timingVariantTokens(value: String): Set<String> {
        val normalized = normalizeForMatch(value)
        if (normalized.isBlank()) return emptySet()

        val tokens = matchTokens(normalized)
        val variants = tokens
            .filter { it in TIMING_VARIANT_KEYWORDS }
            .toMutableSet()

        if (Regex("""\bmash\s+up\b""").containsMatchIn(normalized)) {
            variants += "mashup"
        }
        if ("versus" in tokens || "vs" in tokens) {
            variants += "mashup"
        }

        return variants
    }

    private fun timingVariantTokensFromFileName(song: Song): Set<String> {
        val fileName = songFileName(song)
        if (fileName.isBlank()) return emptySet()

        val variants = BRACKETED_QUALIFIER_REGEX
            .findAll(fileName)
            .flatMap { match -> timingVariantTokens(match.groupValues.getOrNull(1).orEmpty()) }
            .toMutableSet()

        val titleBase = baseTitleForMatching(song.title)
        if (titleBase.isBlank()) return variants

        TITLE_SEPARATOR_REGEX.split(fileName).forEach { part ->
            val normalizedPart = normalizeForMatch(part)
            if (normalizedPart.startsWith("$titleBase ")) {
                variants += timingVariantTokens(normalizedPart.removePrefix(titleBase).trim())
            }
        }

        return variants
    }

    private fun songFileName(song: Song): String {
        if (song.path.isBlank()) return ""
        return runCatching { File(song.path).nameWithoutExtension }.getOrDefault("")
    }

    private fun artistTokens(normalizedArtist: String): Set<String> =
        matchTokens(normalizedArtist)
            .filterNot { it in ARTIST_CONNECTOR_TOKENS }
            .toSet()

    private fun matchTokens(normalizedValue: String): Set<String> =
        normalizedValue
            .split(' ')
            .filter { it.isNotBlank() }
            .toSet()

    private fun containsWholePhrase(haystack: String, needle: String): Boolean {
        if (needle.isBlank()) return false
        return Regex("""(?:^|\s)${Regex.escape(needle)}(?:\s|$)""").containsMatchIn(haystack)
    }

    private fun normalizeForMatch(value: String): String {
        val withoutDiacritics = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")

        return withoutDiacritics
            .replace("&", " and ")
            .replace(Regex("""[\u2019'`]"""), "")
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            .trim()
            .replace(Regex("""\s+"""), " ")
    }

    private fun isUnknownArtist(value: String): Boolean =
        normalizeForMatch(value) in UNKNOWN_ARTISTS

    private fun isNeteaseSong(song: Song): Boolean =
        song.neteaseId != null || song.contentUriString.startsWith("netease://")

    private fun resolveNeteaseSongId(song: Song): Long? {
        song.neteaseId?.let { return it }
        if (!song.contentUriString.startsWith("netease://")) return null
        return Uri.parse(song.contentUriString).host?.toLongOrNull()
    }

    private suspend fun fetchFromAmlldb(song: Song): Lyrics? = withContext(Dispatchers.IO) {
        val neteaseSongId = resolveNeteaseSongId(song) ?: return@withContext null
        val request = Request.Builder()
            .url("$AMLLDB_NCM_LYRICS_BASE_URL$neteaseSongId")
            .get()
            .build()

        try {
            val ttml = withNetworkRetry(
                operationName = "amlldb_fetch:$neteaseSongId",
                shouldRetry = { throwable -> throwable is IOException }
            ) {
                okHttpClient.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful -> response.body.string()
                        response.code.isRetryableHttpStatusCode() ->
                            throw IOException("AMLLDB HTTP ${response.code} for songId=$neteaseSongId")
                        else -> ""
                    }
                }
            }

            if (ttml.isBlank() || ttml.contains("歌词不存在")) return@withContext null
            val lrc = convertAmlTtmlToLrc(ttml) ?: return@withContext null
            val parsed = LyricsUtils.parseLyrics(lrc)
            if (!parsed.isValid()) return@withContext null
            return@withContext parsed.copy(areFromRemote = true)
        } catch (e: Exception) {
            Log.w(TAG, "AMLLDB fetch failed for $neteaseSongId: ${e.message}")
            return@withContext null
        }
    }

    private fun convertAmlTtmlToLrc(ttml: String): String? {
        val lineRegex = Regex(
            "<p\\b[^>]*\\bbegin=\"([^\"]+)\"[^>]*>(.*?)</p>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val spanRegex = Regex(
            "<span\\b([^>]*)>(.*?)</span>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val beginAttrRegex = Regex("\\bbegin=\"([^\"]+)\"")
        val roleAttrRegex = Regex("\\bttm:role=\"([^\"]+)\"")

        val lrcLines = mutableListOf<String>()
        lineRegex.findAll(ttml).forEach { lineMatch ->
            val lineStartMs = parseTtmlTimeToMs(lineMatch.groupValues[1]) ?: return@forEach
            var inner = lineMatch.groupValues[2]
            val markerRegex = Regex("§§TS\\(([^)]+)\\)§§")

            inner = spanRegex.replace(inner) { spanMatch ->
                val attributes = spanMatch.groupValues[1]
                val role = roleAttrRegex.find(attributes)?.groupValues?.getOrNull(1)?.lowercase()
                if (role == "x-roman") {
                    return@replace ""
                }
                val wordStartMs = beginAttrRegex
                    .find(attributes)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.let(::parseTtmlTimeToMs)
                val text = decodeXmlEntities(spanMatch.groupValues[2])

                if (wordStartMs == null) {
                    // Keep visible text (e.g. translation) but do not inject word timing.
                    return@replace text
                }

                "§§TS(${formatTimestamp(wordStartMs.toInt())})§§$text"
            }

            val withoutXmlTags = decodeXmlEntities(inner.replace(Regex("<[^>]+>"), ""))
            val lrcInlineTagged = markerRegex.replace(withoutXmlTags, "<$1>")
            if (lrcInlineTagged.isBlank()) return@forEach

            lrcLines += "[${formatTimestamp(lineStartMs.toInt())}]$lrcInlineTagged"
        }

        return lrcLines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    private fun parseTtmlTimeToMs(value: String): Long? {
        val raw = value.trim()
        if (raw.isEmpty()) return null

        if (raw.endsWith("s")) {
            val seconds = raw.removeSuffix("s").toDoubleOrNull() ?: return null
            return (seconds * 1000.0).toLong()
        }

        val parts = raw.split(":")
        val secondsPart = parts.lastOrNull()?.toDoubleOrNull() ?: return null
        return when (parts.size) {
            3 -> {
                val hours = parts[0].toLongOrNull() ?: return null
                val minutes = parts[1].toLongOrNull() ?: return null
                (hours * 3_600_000L) + (minutes * 60_000L) + (secondsPart * 1000.0).toLong()
            }
            2 -> {
                val minutes = parts[0].toLongOrNull() ?: return null
                (minutes * 60_000L) + (secondsPart * 1000.0).toLong()
            }
            1 -> (secondsPart * 1000.0).toLong()
            else -> null
        }
    }

    private fun decodeXmlEntities(value: String): String =
        value
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")

    /**
     * Find local .lrc file next to the music file (matching Rhythm)
     */
    private suspend fun findLocalLyricsFile(song: Song): Lyrics? = withContext(Dispatchers.IO) {
        try {
            val songFile = File(song.path)
            val directory = songFile.parentFile ?: return@withContext null
            val songNameWithoutExt = songFile.nameWithoutExtension

            if (directory.exists()) {
                for (extension in LyricsImportSecurity.supportedFileExtensions()) {
                    val lyricsFile = File(directory, "$songNameWithoutExt.$extension")
                    if (!lyricsFile.exists() || !lyricsFile.canRead()) continue

                    val validated = readValidatedLocalLyrics(lyricsFile)
                    if (validated != null) {
                        Log.d(TAG, "===== FOUND LOCAL LYRICS FILE: ${lyricsFile.name} =====")
                        return@withContext validated.parsedLyrics
                    }
                }

                val cleanArtist = song.displayArtist.replace(Regex("[^\\p{L}\\p{N}]"), "_")
                val cleanTitle = song.title.replace(Regex("[^\\p{L}\\p{N}]"), "_")

                for (extension in LyricsImportSecurity.supportedFileExtensions()) {
                    val alternativeLyricsFile = File(directory, "${cleanArtist}_${cleanTitle}.$extension")
                    if (!alternativeLyricsFile.exists() || !alternativeLyricsFile.canRead()) continue

                    val validated = readValidatedLocalLyrics(alternativeLyricsFile)
                    if (validated != null) {
                        Log.d(TAG, "===== FOUND LOCAL LYRICS FILE (alt pattern): ${alternativeLyricsFile.name} =====")
                        return@withContext validated.parsedLyrics
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error searching for local lyrics file", e)
        }
        return@withContext null
    }

    private fun readValidatedLocalLyrics(file: File): com.theveloper.pixelplay.utils.ValidatedLyricsImport? {
        return when (val validation = LyricsImportSecurity.validateLocalLyricsFile(file)) {
            is LyricsImportValidationResult.Valid -> validation.value
            is LyricsImportValidationResult.Invalid -> null
        }
    }

    /**
     * Save lyrics to JSON disk cache (matching Rhythm)
     */
    private fun saveLocalLyricsJson(song: Song, lyrics: Lyrics) {
        try {
            val fileName = "${song.id}.json"
            val lyricsDir = File(context.filesDir, "lyrics")
            lyricsDir.mkdirs()

            val wordByWordLyrics = lyrics.synced
                ?.takeIf { lines -> lines.any { !it.words.isNullOrEmpty() } }
                ?.let(::toWordByWordLrc)

            val lyricsData = LyricsData(
                plainLyrics = lyrics.plain?.joinToString("\n"),
                syncedLyrics = lyrics.synced?.joinToString("\n") { line ->
                    buildString {
                        append("[${formatTimestamp(line.time)}]${line.line}")
                        if (!line.translation.isNullOrBlank()) {
                            append("\n[${formatTimestamp(line.time)}]${line.translation}")
                        }
                    }
                },
                wordByWordLyrics = wordByWordLyrics
            )

            val file = File(lyricsDir, fileName)
            val json = gson.toJson(lyricsData)
            file.writeText(json)
            Log.d(TAG, "Saved lyrics to JSON cache: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving lyrics to JSON cache: ${e.message}", e)
        }
    }

    /**
     * Load lyrics from JSON disk cache (matching Rhythm)
     */
    private suspend fun loadLocalLyricsJson(song: Song): Lyrics? {
        try {
            val data = readLyricsJsonCache(song) ?: return null
            if (data.hasLyrics()) {
                val rawLyrics = data.wordByWordLyrics ?: data.syncedLyrics ?: data.plainLyrics
                val parsed = LyricsUtils.parseLyrics(rawLyrics)
                if (parsed.isValid()) {
                    val hasWordTimestamps = parsed.synced?.any { !it.words.isNullOrEmpty() } == true
                    if (!hasWordTimestamps && data.wordByWordLyrics.isNullOrBlank()) {
                        // Legacy cache may have flattened word-by-word lines.
                        // Recover richer raw lyrics from DB when available.
                        val persistedContent = song.id.toLongOrNull()
                            ?.let { lyricsDao.getLyrics(it)?.content }
                            ?.takeIf { it.isNotBlank() }
                        if (persistedContent != null) {
                            val recovered = LyricsUtils.parseLyrics(persistedContent)
                            val recoveredHasWords = recovered.synced?.any { !it.words.isNullOrEmpty() } == true
                            if (recovered.isValid() && recoveredHasWords) {
                                saveLocalLyricsJson(song, recovered)
                                return recovered
                            }
                        }

                        if (looksLikeFlattenedWordByWordCache(parsed)) {
                            // Force a remote re-fetch instead of serving degraded cache.
                            return null
                        }
                    }
                    return parsed
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading JSON cache: ${e.message}", e)
        }
        return null
    }

    private fun formatTimestamp(timeMs: Int): String {
        val totalSeconds = timeMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val hundredths = (timeMs % 1000) / 10
        return String.format("%02d:%02d.%02d", minutes, seconds, hundredths)
    }

    private fun toWordByWordLrc(lines: List<SyncedLine>): String {
        return lines.joinToString("\n") { line ->
            val linePrefix = "[${formatTimestamp(line.time)}]"
            val words = line.words
            if (words.isNullOrEmpty()) {
                linePrefix + line.line
            } else {
                val wordsPart = words.mapIndexed { index, word ->
                    val separator = if (index > 0 && word.startsNewWord) " " else ""
                    "$separator<${formatTimestamp(word.time)}>${word.word}"
                }
                    .joinToString("")
                linePrefix + wordsPart
            }
        }
    }

    private fun looksLikeFlattenedWordByWordCache(lyrics: Lyrics): Boolean {
        val synced = lyrics.synced ?: return false
        var suspiciousLines = 0

        for (line in synced) {
            val text = line.line
            if (text.isBlank() || text.any { it.isWhitespace() }) continue

            val hasLongLatinRun = Regex("[A-Za-z]{10,}").containsMatchIn(text)
            if (hasLongLatinRun) {
                suspiciousLines += 1
                if (suspiciousLines >= 2) return true
            }
        }

        return false
    }

    /**
     * Load embedded lyrics from audio file metadata
     */
    private suspend fun loadEmbeddedLyricsFromMetadata(song: Song): Lyrics? = withContext(Dispatchers.IO) {
        // Skip embedded lyrics for Telegram songs (not supported yet/streamed)
        if (song.contentUriString.startsWith("telegram://") || song.contentUriString.isEmpty()) {
            return@withContext null
        }

        // Then try to read from file metadata
        return@withContext try {
            val uri = song.contentUriString.toUri()
            val tempFile = createTempFileFromUri(uri)
            if (tempFile == null) {
                LogUtils.w(this@LyricsRepositoryImpl, "Could not create temp file from URI: ${song.contentUriString}")
                return@withContext null
            }

            try {
                ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    val metadata = TagLib.getMetadata(fd.detachFd())
                    val propertyMap = metadata?.propertyMap
                    val parsedLyrics = parseBestEmbeddedLyricsField(propertyMap)

                    if (parsedLyrics != null) {
                        Log.d(TAG, "===== FOUND EMBEDDED LYRICS =====")
                        parsedLyrics
                    } else {
                        null
                    }
                }
            } finally {
                tempFile.delete()
            }
        } catch (e: Exception) {
            LogUtils.e(this@LyricsRepositoryImpl, e, "Error reading lyrics from file metadata")
            null
        }
    }

    private suspend fun loadStoredLyrics(
        song: Song,
        cacheKey: String,
        includeMemoryCache: Boolean
    ): Pair<Lyrics, String>? = withContext(Dispatchers.IO) {
        song.lyrics
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { rawLyrics ->
                parseStoredLyrics(rawLyrics)?.let { return@withContext it to rawLyrics }
            }

        song.id.toLongOrNull()
            ?.let { lyricsDao.getLyrics(it)?.content }
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { rawLyrics ->
                parseStoredLyrics(rawLyrics)?.let { return@withContext it to rawLyrics }
            }

        readLyricsJsonCache(song)
            ?.takeIf { it.hasLyrics() }
            ?.let { data ->
                val rawLyrics = data.wordByWordLyrics ?: data.syncedLyrics ?: data.plainLyrics
                if (!rawLyrics.isNullOrBlank()) {
                    parseStoredLyrics(rawLyrics)?.let { return@withContext it to rawLyrics }
                }
            }

        if (includeMemoryCache) {
            lyricsCache.get(cacheKey)?.let { cached ->
                lyricsToRawContent(cached)?.let { rawLyrics ->
                    return@withContext cached to rawLyrics
                }
            }
        }

        null
    }

    private fun parseStoredLyrics(rawLyrics: String): Lyrics? {
        val parsedLyrics = LyricsUtils.parseLyrics(rawLyrics)
        return parsedLyrics
            .takeIf { it.isValid() }
            ?.copy(areFromRemote = false)
    }

    private fun lyricsToRawContent(lyrics: Lyrics): String? {
        val syncedLyrics = lyrics.synced
        if (!syncedLyrics.isNullOrEmpty()) {
            val hasWordTimestamps = syncedLyrics.any { !it.words.isNullOrEmpty() }
            return if (hasWordTimestamps) {
                toWordByWordLrc(syncedLyrics)
            } else {
                syncedLyrics.joinToString("\n") { line ->
                    buildString {
                        append("[${formatTimestamp(line.time)}]${line.line}")
                        if (!line.translation.isNullOrBlank()) {
                            append("\n[${formatTimestamp(line.time)}]${line.translation}")
                        }
                    }
                }
            }
        }

        return lyrics.plain
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString("\n")
            ?.takeIf { it.isNotBlank() }
    }

    private fun readLyricsJsonCache(song: Song): LyricsData? {
        val fileName = "${song.id}.json"
        val file = File(context.filesDir, "lyrics/$fileName")
        if (!file.exists()) return null

        val json = file.readText()
        return gson.fromJson(json, LyricsData::class.java)
    }

    // ========== Original methods (kept for backward compatibility) ==========

    override suspend fun fetchFromRemote(song: Song): Result<Pair<Lyrics, String>> = withContext(Dispatchers.IO) {
        try {
            LogUtils.d(this@LyricsRepositoryImpl, "Fetching lyrics from remote for: ${song.title}")

            val cacheKey = generateCacheKey(song.id)
            loadStoredLyrics(song, cacheKey, includeMemoryCache = true)?.let { stored ->
                lyricsCache.put(cacheKey, stored.first)
                LogUtils.d(
                    this@LyricsRepositoryImpl,
                    "Skipping remote lyrics fetch because stored lyrics already exist for: ${song.title}"
                )
                return@withContext Result.success(stored)
            }

            // First, try the search API which is more flexible, then pick the best match
            val searchResult = searchRemote(song)
            if (searchResult.isSuccess) {
                val (_, results) = searchResult.getOrThrow()
                if (results.isNotEmpty()) {
                    // Pick the first result (already sorted by synced priority)
                    val best = results.first()
                    val rawLyricsToSave = best.rawLyrics

                    try {
                        val songIdLong = song.id.toLongOrNull()
                        if (songIdLong == null) {
                            Log.w(TAG, "Cannot persist lyrics to DB: non-numeric Song.id '${song.id}'")
                        } else {
                            lyricsDao.insert(
                                 com.theveloper.pixelplay.data.database.LyricsEntity(
                                     songId = songIdLong,
                                     content = rawLyricsToSave,
                                     isSynced = !best.lyrics.synced.isNullOrEmpty(),
                                     source = "remote"
                                 )
                            )
                        }
                    } catch (e: NumberFormatException) {
                        Log.w(TAG, "Skipping DB update for non-numeric ID: ${song.id}")
                    }

                    lyricsCache.put(cacheKey, best.lyrics)
                    saveLocalLyricsJson(song, best.lyrics)
                    LogUtils.d(this@LyricsRepositoryImpl, "Fetched and cached remote lyrics for: ${song.title}")

                    return@withContext Result.success(Pair(best.lyrics, rawLyricsToSave))
                }
            }

            // Fallback: Try the exact match API (less likely to succeed, but worth a shot)
            val response = withNetworkRetry(operationName = "lrclib_get_lyrics") {
                lrcLibApiService.getLyrics(
                    trackName = song.title,
                    artistName = song.displayArtist,
                    albumName = song.album,
                    duration = (song.duration / 1000).toInt()
                )
            }

            val exactMatch = response
                ?.let { rankRemoteLyricsMatches(song, listOf(it), RemoteLyricsMatchMode.AUTOMATIC).firstOrNull()?.response }

            if (exactMatch != null) {
                val rawLyricsToSave = exactMatch.syncedLyrics ?: exactMatch.plainLyrics
                    ?: return@withContext Result.failure(NoLyricsFoundException())

                val parsedLyrics = LyricsUtils.parseLyrics(rawLyricsToSave).copy(areFromRemote = true)
                if (!parsedLyrics.isValid()) {
                    return@withContext Result.failure(LyricsException("Parsed lyrics are empty"))
                }

                try {
                    val songIdLong = song.id.toLongOrNull()
                    if (songIdLong == null) {
                        Log.w(TAG, "Cannot persist lyrics to DB: non-numeric Song.id '${song.id}'")
                        return@withContext Result.failure(LyricsException("Non-numeric Song.id"))
                    }
                    lyricsDao.insert(
                        com.theveloper.pixelplay.data.database.LyricsEntity(
                            songId = songIdLong,
                            content = rawLyricsToSave,
                            isSynced = !parsedLyrics.synced.isNullOrEmpty(),
                            source = "remote"
                        )
                    )
                } catch (e: NumberFormatException) {
                    Log.w(TAG, "Skipping DB update for non-numeric ID in fallback: ${song.id}")
                }

                lyricsCache.put(cacheKey, parsedLyrics)
                saveLocalLyricsJson(song, parsedLyrics)
                LogUtils.d(this@LyricsRepositoryImpl, "Fetched and cached remote lyrics (exact match) for: ${song.title}")

                return@withContext Result.success(Pair(parsedLyrics, rawLyricsToSave))
            } else {
                LogUtils.d(this@LyricsRepositoryImpl, "No lyrics found remotely for: ${song.title}")
                Result.failure(NoLyricsFoundException())
            }
        } catch (e: Exception) {
            LogUtils.e(this@LyricsRepositoryImpl, e, "Error fetching lyrics from remote")
            when {
                e is HttpException && e.code() == 404 -> Result.failure(NoLyricsFoundException())
                e is SocketTimeoutException -> Result.failure(LyricsException(context.getString(R.string.lyrics_fetch_timeout), e))
                e is UnknownHostException -> Result.failure(LyricsException(context.getString(R.string.lyrics_network_error), e))
                e is IOException -> Result.failure(LyricsException(context.getString(R.string.lyrics_network_error), e))
                e is HttpException -> Result.failure(LyricsException(context.getString(R.string.lyrics_server_error, e.code()), e))
                else -> Result.failure(LyricsException(context.getString(R.string.lyrics_failed_to_fetch_from_remote), e))
            }
        }
    }

    override suspend fun searchRemote(song: Song): Result<Pair<String, List<LyricsSearchResult>>> = withContext(Dispatchers.IO) {
        try {
            LogUtils.d(this@LyricsRepositoryImpl, "Searching remote for lyrics for: ${song.title} by ${song.displayArtist}")

            val combinedQuery = "${song.title} ${song.displayArtist}"
            val cleanTitle = song.title.trim()
            val cleanArtist = song.displayArtist.trim()

            // FAST STRATEGY: run all requests in parallel, keep first non-empty batch
            // FAST STRATEGY: run all requests in parallel, keep first non-empty batch
            val strategies = buildList {
                add(RemoteSearchStrategy("query+artist") {
                    lrcLibApiService.searchLyrics(query = combinedQuery, artistName = cleanArtist)
                })
                add(RemoteSearchStrategy("track+artist") {
                    lrcLibApiService.searchLyrics(trackName = cleanTitle, artistName = cleanArtist)
                })
                
                // Romanized search strategy for non-Latin titles
                if (MultiLangRomanizer.isScriptThatNeedsRomanization(cleanTitle)) {
                    val romanTitle = romanizeForMatch(cleanTitle)
                    if (romanTitle != cleanTitle) {
                        add(RemoteSearchStrategy("romanized_track") {
                            lrcLibApiService.searchLyrics(trackName = romanTitle, artistName = cleanArtist)
                        })
                    }
                }
                if (MultiLangRomanizer.isScriptThatNeedsRomanization(cleanArtist)) {
                    val romanArtist = romanizeForMatch(cleanArtist)
                    if (romanArtist != cleanArtist) {
                        add(RemoteSearchStrategy("romanized_artist") {
                            lrcLibApiService.searchLyrics(trackName = cleanTitle, artistName = romanArtist)
                        })
                    }
                }

                // Smart title cleanup strategy
                val smartTitle = cleanTitleSmart(cleanTitle)
                if (smartTitle != cleanTitle && smartTitle.isNotBlank()) {
                    LogUtils.d(this@LyricsRepositoryImpl, "Adding smart search strategy for: '$smartTitle' (orig: '$cleanTitle')")
                    add(RemoteSearchStrategy("smart_track_only") {
                        lrcLibApiService.searchLyrics(trackName = smartTitle)
                    })
                }

                add(RemoteSearchStrategy("track_only") {
                    lrcLibApiService.searchLyrics(trackName = cleanTitle)
                })
                add(RemoteSearchStrategy("query_title_only") {
                    lrcLibApiService.searchLyrics(query = cleanTitle)
                })
            }

            val uniqueResults = runSearchStrategiesFast(strategies)

            if (uniqueResults.isNotEmpty()) {
                val rankedMatches = rankRemoteLyricsMatches(
                    song = song,
                    responses = uniqueResults,
                    mode = RemoteLyricsMatchMode.CANDIDATE
                )
                val results = rankedMatches.mapNotNull { match ->
                    val response = match.response
                    val rawLyrics = response.syncedLyrics ?: response.plainLyrics ?: return@mapNotNull null
                    val parsedLyrics = LyricsUtils.parseLyrics(rawLyrics).copy(areFromRemote = true)
                    if (!parsedLyrics.isValid()) {
                        LogUtils.w(this@LyricsRepositoryImpl, "Parsed lyrics are empty for: ${song.title}")
                        return@mapNotNull null
                    }
                    val hasSynced = !response.syncedLyrics.isNullOrEmpty()
                    LogUtils.d(this@LyricsRepositoryImpl, "  Found: ${response.name} by ${response.artistName} (synced: $hasSynced)")
                    LyricsSearchResult(response, parsedLyrics, rawLyrics)
                }

                if (results.isNotEmpty()) {
                    val syncedCount = results.count { !it.record.syncedLyrics.isNullOrEmpty() }
                    LogUtils.d(this@LyricsRepositoryImpl, "Found ${results.size} lyrics for: ${song.title} ($syncedCount with synced)")
                    Result.success(Pair(combinedQuery, results))
                } else {
                    LogUtils.d(this@LyricsRepositoryImpl, "No matching lyrics found for: ${song.title}")
                    Result.failure(NoLyricsFoundException(combinedQuery))
                }
            } else {
                LogUtils.d(this@LyricsRepositoryImpl, "No lyrics found remotely for: ${song.title}")
                Result.failure(NoLyricsFoundException(combinedQuery))
            }
        } catch (e: Exception) {
            LogUtils.e(this@LyricsRepositoryImpl, e, "Error searching remote for lyrics")
            when {
                e is SocketTimeoutException -> Result.failure(LyricsException(context.getString(R.string.lyrics_fetch_timeout), e))
                e is UnknownHostException -> Result.failure(LyricsException(context.getString(R.string.lyrics_network_error), e))
                e is IOException -> Result.failure(LyricsException(context.getString(R.string.lyrics_network_error), e))
                e is HttpException -> Result.failure(LyricsException(context.getString(R.string.lyrics_server_error, e.code()), e))
                else -> Result.failure(LyricsException(context.getString(R.string.lyrics_failed_to_search), e))
            }
        }
    }

    override suspend fun searchRemoteByQuery(title: String, artist: String?): Result<Pair<String, List<LyricsSearchResult>>> = withContext(Dispatchers.IO) {
        try {
            val cleanTitle = title.trim()
            val cleanArtist = artist?.trim()?.takeIf { it.isNotBlank() }
            val query = listOfNotNull(
                cleanTitle.takeIf { it.isNotBlank() },
                cleanArtist
            ).joinToString(" ")

            LogUtils.d(this@LyricsRepositoryImpl, "Manual lyrics search: title=$title, artist=$artist")

            val strategies = buildList {
                add(RemoteSearchStrategy("manual_query") { lrcLibApiService.searchLyrics(query = query) })
                if (!cleanArtist.isNullOrBlank()) {
                    add(
                        RemoteSearchStrategy("manual_track+artist") {
                            lrcLibApiService.searchLyrics(trackName = cleanTitle, artistName = cleanArtist)
                        }
                    )
                }
            }

            // Run both in parallel and take the first non-empty result set.
            val responses = runSearchStrategiesFast(strategies)

            if (responses.isEmpty()) {
                return@withContext Result.failure(NoLyricsFoundException(query))
            }

            val results = responses.mapNotNull { response ->
                val rawLyrics = response.syncedLyrics ?: response.plainLyrics ?: return@mapNotNull null
                val parsed = LyricsUtils.parseLyrics(rawLyrics).copy(areFromRemote = true)
                if (!parsed.isValid()) return@mapNotNull null

                LyricsSearchResult(response, parsed, rawLyrics)
            }.sortedByDescending { !it.record.syncedLyrics.isNullOrEmpty() }

            if (results.isEmpty()) {
                Result.failure(NoLyricsFoundException(query))
            } else {
                Result.success(Pair(query, results))
            }
        } catch (e: Exception) {
            LogUtils.e(this@LyricsRepositoryImpl, e, "Manual search failed")
            Result.failure(LyricsException(context.getString(R.string.lyrics_failed_to_search), e)
            )
        }
    }

    override suspend fun updateLyrics(songId: Long, lyricsContent: String): Unit = withContext(Dispatchers.IO) {
        LogUtils.d(this@LyricsRepositoryImpl, "Updating lyrics for songId: $songId")

        val parsedLyrics = LyricsUtils.parseLyrics(lyricsContent)
        if (!parsedLyrics.isValid()) {
            LogUtils.w(this@LyricsRepositoryImpl, "Attempted to save empty lyrics for songId: $songId")
            return@withContext
        }

        lyricsDao.insert(
             com.theveloper.pixelplay.data.database.LyricsEntity(
                 songId = songId,
                 content = lyricsContent,
                 isSynced = parsedLyrics.synced?.isNotEmpty() == true,
                 source = "manual"
             )
        )

        val cacheKey = generateCacheKey(songId.toString())
        lyricsCache.put(cacheKey, parsedLyrics)
        LogUtils.d(this@LyricsRepositoryImpl, "Updated and cached lyrics for songId: $songId")
    }

    override suspend fun resetLyrics(songId: Long): Unit = withContext(Dispatchers.IO) {
        LogUtils.d(this, "Resetting lyrics for songId: $songId")
        val cacheKey = generateCacheKey(songId.toString())
        lyricsCache.remove(cacheKey)
        try {
            lyricsDao.deleteLyrics(songId)
        } catch (e: Exception) {
            Log.w(TAG, "Error removing lyrics from DB for ID: $songId", e)
        }
        
        // Also remove JSON cache
        try {
            val file = File(context.filesDir, "lyrics/${songId}.json")
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "Failed to delete JSON cache for songId: $songId")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error deleting JSON cache: ${e.message}")
        }
    }

    override suspend fun resetAllLyrics(): Unit = withContext(Dispatchers.IO) {
        LogUtils.d(this, "Resetting all lyrics")
        lyricsCache.evictAll()
        lyricsDao.deleteAll()
        
        // Also clear JSON cache directory
        try {
            val lyricsDir = File(context.filesDir, "lyrics")
            if (lyricsDir.exists()) {
                lyricsDir.listFiles()?.forEach { it.delete() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error clearing JSON cache: ${e.message}")
        }
    }

    override suspend fun scanAndAssignLocalLrcFiles(
        songs: List<Song>,
        onProgress: suspend (current: Int, total: Int) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        LogUtils.d(this@LyricsRepositoryImpl, "Starting bulk scan for .lrc files for ${songs.size} songs")
        val updatedCount = AtomicInteger(0)
        val processedCount = AtomicInteger(0)
        val total = songs.size

        val idsWithPersistedLyrics = songs
            .mapNotNull { it.id.toLongOrNull() }
            .chunked(900)
            .flatMap { chunk -> lyricsDao.getSongIdsWithLyrics(chunk) }
            .toHashSet()

        // Only scan songs that don't already have persisted lyrics.
        val songsToScan = songs.filter { song ->
            val songId = song.id.toLongOrNull()
            song.lyrics.isNullOrBlank() && (songId == null || songId !in idsWithPersistedLyrics)
        }
        val skippedCount = total - songsToScan.size
        processedCount.addAndGet(skippedCount)
        
        LogUtils.d(this@LyricsRepositoryImpl, "Skipping $skippedCount songs that already have lyrics. Scanning ${songsToScan.size} songs.")
        
        onProgress(processedCount.get(), total)
        
        if (songsToScan.isEmpty()) {
            return@withContext 0
        }

        val semaphore = Semaphore(8) // Limit concurrency

        coroutineScope {
            songsToScan.map { song ->
                async {
                    semaphore.withPermit {
                        try {
                            // Find lyrics file
                            val songFile = File(song.path)
                            val directory = songFile.parentFile
                            
                            if (directory != null && directory.exists()) {
                                var foundFile: File? = null
                                
                                // Strategy 1: Exact match name
                                for (extension in LyricsImportSecurity.supportedFileExtensions()) {
                                    val exactMatch = File(directory, "${songFile.nameWithoutExtension}.$extension")
                                    if (exactMatch.exists() && exactMatch.canRead()) {
                                        foundFile = exactMatch
                                        break
                                    }
                                }
                                
                                // Strategy 2: Artist - Title
                                if (foundFile == null) {
                                    val cleanArtist = song.displayArtist.replace(Regex("[^\\p{L}\\p{N}]"), "_")
                                    val cleanTitle = song.title.replace(Regex("[^\\p{L}\\p{N}]"), "_")
                                    for (extension in LyricsImportSecurity.supportedFileExtensions()) {
                                        val altMatch = File(directory, "${cleanArtist}_${cleanTitle}.$extension")
                                        if (altMatch.exists() && altMatch.canRead()) {
                                            foundFile = altMatch
                                            break
                                        }
                                    }
                                }
                                
                                if (foundFile != null) {
                                    val validated = readValidatedLocalLyrics(foundFile)
                                    if (validated != null) {
                                        try {
                                            val songIdLong = song.id.toLongOrNull()
                                            if (songIdLong == null) {
                                                Log.w(TAG, "Cannot persist lyrics to DB: non-numeric Song.id '${song.id}'")
                                            } else {
                                                lyricsDao.insert(
                                                     com.theveloper.pixelplay.data.database.LyricsEntity(
                                                         songId = songIdLong,
                                                         content = validated.sanitizedContent,
                                                         isSynced = validated.parsedLyrics.synced?.isNotEmpty() == true,
                                                         source = "local_file"
                                                     )
                                                )
                                            }
                                            updatedCount.incrementAndGet()
                                            LogUtils.d(this@LyricsRepositoryImpl, "Auto-assigned lyrics from ${foundFile.name}")
                                        } catch (e: Exception) {
                                            Log.w(TAG, "Skipping DB update for ID in scanner: ${song.id}", e)
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Error scanning lyrics for ${song.title}: ${e.message}")
                        }
                        
                        val current = processedCount.incrementAndGet()
                        if (current % 20 == 0 || current == total) {
                            onProgress(current, total)
                        }
                    }
                }
            }.awaitAll()
        }
        
        LogUtils.d(this@LyricsRepositoryImpl, "Bulk scan complete. Updated ${updatedCount.get()} songs.")
        return@withContext updatedCount.get()
    }

    override fun clearCache() {
        LogUtils.d(this, "Clearing lyrics in-memory cache")
        lyricsCache.evictAll()
    }

    private fun generateCacheKey(songId: String): String = songId

    private fun createTempFileFromUri(uri: Uri): File? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val fileName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex >= 0) cursor.getString(nameIndex) else "temp_audio"
                    } else "temp_audio"
                } ?: "temp_audio"

                val tempFile = File.createTempFile("lyrics_", "_$fileName", context.cacheDir)
                FileOutputStream(tempFile).use { output ->
                    inputStream.copyTo(output)
                }
                tempFile
            }
        } catch (e: Exception) {
            LogUtils.e(this, e, "Error creating temp file from URI")
            null
        }
    }

    private fun cleanTitleSmart(title: String): String {
        // 1. Remove leading digits/spaces/dots/hyphens (e.g., "01 ", "01. ", "01 - ")
        // \uFF0D is the fullwidth hyphen-minus character used in fullwidth/CJK text.
        var cleaned = title.replace(Regex("^[\\d\\s.\\-\\uFF0D]+"), "")
        
        // 2. Truncate at first special char (-, (, ), Asian brackets)
        // "Taare Ginn - Envy" -> "Taare Ginn "
        // "Song (Feat. X)" -> "Song "
        val splitRegex = Regex("""[-\(\[\{\uFF08\uFF3B\uFF5B\u3010\u300E\u300C\u3014\u3008\u300A]""")
        cleaned = cleaned.split(splitRegex).firstOrNull() ?: cleaned
        
        // 3. Trim whitespace
        return cleaned.trim()
    }

    private fun romanizeForMatch(text: String): String {
        return when {
            MultiLangRomanizer.isJapanese(text) -> MultiLangRomanizer.romanizeJapanese(text) ?: text
            MultiLangRomanizer.isChinese(text) -> MultiLangRomanizer.romanizeChinese(text) ?: text
            MultiLangRomanizer.isKorean(text) -> MultiLangRomanizer.romanizeKorean(text)
            MultiLangRomanizer.isCyrillic(text) -> MultiLangRomanizer.romanizeCyrillic(text) ?: text
            MultiLangRomanizer.isVietnamese(text) -> MultiLangRomanizer.romanizeVietnamese(text)
            else -> text
        }
    }
}

data class LyricsSearchResult(val record: LrcLibResponse, val lyrics: Lyrics, val rawLyrics: String)

data class NoLyricsFoundException(val query: String? = null) : Exception()

class LyricsException(message: String, cause: Throwable? = null) : Exception(message, cause)

package com.theveloper.pixelplay.presentation.viewmodel


import android.content.Context
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.DailyMixManager
import com.theveloper.pixelplay.data.ai.AiNotificationManager
import com.theveloper.pixelplay.data.ai.AiPlaylistGenerator
import com.theveloper.pixelplay.data.ai.AiResponseCleaner
import com.theveloper.pixelplay.data.ai.AiSystemPromptType
import com.theveloper.pixelplay.data.ai.provider.AiProviderException
import com.theveloper.pixelplay.data.preferences.PlaylistPreferencesRepository
import com.theveloper.pixelplay.data.model.Song
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages AI-powered features: AI Playlist Generation and AI Metadata Generation.
 * Extracted from PlayerViewModel.
 */
@Singleton
class AiStateHolder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val aiPlaylistGenerator: AiPlaylistGenerator,
    private val dailyMixManager: DailyMixManager,
    private val playlistPreferencesRepository: PlaylistPreferencesRepository,
    private val dailyMixStateHolder: DailyMixStateHolder,
    private val notificationManager: AiNotificationManager,
    private val aiHandler: com.theveloper.pixelplay.data.ai.AiHandler
) {
    // State
    // AI State Management: Observables for tracking background generation progress
    private val _showAiPlaylistSheet = MutableStateFlow(false)
    val showAiPlaylistSheet = _showAiPlaylistSheet.asStateFlow()

    private val _isGeneratingAiPlaylist = MutableStateFlow(false)
    val isGeneratingAiPlaylist = _isGeneratingAiPlaylist.asStateFlow()

    private val _aiSuccess = MutableStateFlow(false)
    val aiSuccess = _aiSuccess.asStateFlow()

    private val _aiStatus = MutableStateFlow<String?>(null)
    val aiStatus = _aiStatus.asStateFlow()

    private val _aiError = MutableStateFlow<String?>(null)
    val aiError = _aiError.asStateFlow()

    private var _lastPlaylistPrompt: String? = null
    private var _lastMinLength: Int = 5
    private var _lastMaxLength: Int = 15

    private var scope: CoroutineScope? = null
    private var allSongsProvider: (suspend () -> List<Song>)? = null
    private var favoriteSongIdsProvider: (() -> Set<String>)? = null
    
    // Callbacks to interact with PlayerViewModel/UI
    private var toastEmitter: ((String) -> Unit)? = null
    private var playSongsCallback: ((List<Song>, Song, String) -> Unit)? = null // songs, startSong, queueName
    private var openPlayerSheetCallback: (() -> Unit)? = null

    private val titleStopWords = setOf(
        "a", "an", "the", "and", "or", "for", "to", "of", "in", "on", "with", "by", "from",
        "de", "la", "el", "los", "las", "y", "o", "para", "con", "por", "del", "al", "un", "una",
        "core", "request", "mood", "target", "activity", "context", "era", "focus", "prioritize",
        "genres", "avoid", "preferred", "language", "energy", "level", "discovery", "where",
        "familiar", "deep", "cuts", "keep", "transitions", "smooth", "repetitive", "artist",
        "clustering", "songs", "listener", "favorites", "explicit", "lyrics", "alternatives",
        "whenever", "possible"
    )

    fun initialize(
        scope: CoroutineScope,
        allSongsProvider: suspend () -> List<Song>,
        favoriteSongIdsProvider: () -> Set<String>,
        toastEmitter: (String) -> Unit,
        playSongsCallback: (List<Song>, Song, String) -> Unit,
        openPlayerSheetCallback: () -> Unit
    ) {
        this.scope = scope
        this.allSongsProvider = allSongsProvider
        this.favoriteSongIdsProvider = favoriteSongIdsProvider
        this.toastEmitter = toastEmitter
        this.playSongsCallback = playSongsCallback
        this.openPlayerSheetCallback = openPlayerSheetCallback
    }

    fun showAiPlaylistSheet() {
        _showAiPlaylistSheet.value = true
    }

    fun dismissAiPlaylistSheet() {
        _showAiPlaylistSheet.value = false
        _aiError.value = null
        _aiSuccess.value = false
        _isGeneratingAiPlaylist.value = false
        _aiStatus.value = null
    }

    fun retryLastPlaylistGeneration() {
        // Safe retry using cached prompt and length constraints
        val prompt = _lastPlaylistPrompt ?: return
        generateAiPlaylist(prompt, _lastMinLength, _lastMaxLength)
    }

    fun clearAiPlaylistError() {
        _aiError.value = null
    }

    /**
     * Entry point for generating an AI-curated playlist based on a user prompt.
     * Orchestrates library scanning, candidate selection, and the AI curation process.
     */
    fun generateAiPlaylist(
        prompt: String,
        minLength: Int,
        maxLength: Int,
        saveAsPlaylist: Boolean = false,
        playlistName: String? = null
    ) {
        _lastPlaylistPrompt = prompt
        _lastMinLength = minLength
        _lastMaxLength = maxLength

        val scope = this.scope ?: return

        scope.launch {
            val allSongs = allSongsProvider?.invoke() ?: emptyList()
            val favoriteIds = favoriteSongIdsProvider?.invoke() ?: emptySet()
            _isGeneratingAiPlaylist.value = true
            _aiError.value = null
            _aiSuccess.value = false

            // Step 1: Pre-generation analysis
            try {
                _aiStatus.value = "Analyzing your library stats..."
                val existingPlaylistNames = playlistPreferencesRepository.userPlaylistsFlow.first()
                    .map { it.name.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet()

                // Generate candidate pool using DailyMixManager logic
                _aiStatus.value = "Selecting best candidates..."
                val candidatePool = dailyMixManager.generateDailyMix(
                    allSongs = allSongs,
                    favoriteSongIds = favoriteIds,
                    limit = 120
                )

                // Step 2: Invoke AI Generation Engine
                _aiStatus.value = "Consulting the Daily Mix guide..."
                notificationManager.showProgress("AI Curation", "Synthesizing your Daily Mix...", 50)
                
                val result = aiPlaylistGenerator.generate(
                    userPrompt = prompt,
                    allSongs = allSongs,
                    minLength = minLength,
                    maxLength = maxLength,
                    candidateSongs = candidatePool
                )

                result.onSuccess { generatedSongs ->
                    if (generatedSongs.isNotEmpty()) {
                        if (saveAsPlaylist) {
                            val resolvedPlaylistName = resolveAiPlaylistName(
                                requestedName = playlistName,
                                prompt = prompt,
                                existingNames = existingPlaylistNames
                            )
                            val songIds = generatedSongs.map { it.id }
                            playlistPreferencesRepository.createPlaylist(
                                name = resolvedPlaylistName,
                                songIds = songIds,
                                isAiGenerated = true
                            )
                            _aiStatus.value = "Success! Your mix is ready."
                            _aiSuccess.value = true
                            notificationManager.showCompletion("Generation Complete", "Your AI Mix is ready to play.")
                            toastEmitter?.invoke("AI Playlist created!")
                            kotlinx.coroutines.delay(1200) // AI UI Optimization: Let the success animation breathe
                            dismissAiPlaylistSheet()
                        } else {
                            // Play immediately logic
                            _aiStatus.value = "Starting playback..."
                            _aiSuccess.value = true
                            notificationManager.showCompletion("Generation Complete", "Starting your personalized session.")
                            dailyMixStateHolder.setDailyMixSongs(generatedSongs)
                            playSongsCallback?.invoke(generatedSongs, generatedSongs.first(), "AI: $prompt")
                            openPlayerSheetCallback?.invoke()
                            kotlinx.coroutines.delay(800)
                            dismissAiPlaylistSheet()
                        }
                    } else {
                        _aiStatus.value = null
                        _aiError.value = context.getString(R.string.ai_state_no_songs_found)
                        notificationManager.hideProgress()
                    }
                }.onFailure { error ->
                    Timber.tag("AiPlaylist").e(error, "AI playlist generation failed")
                    _aiStatus.value = null
                    val detail = extractAiErrorDetail(error)
                    _aiError.value = resolveAiErrorMessage(error)
                    notificationManager.showCompletion("Generation Failed", detail.take(140))
                }
            } catch (e: Exception) {
                Timber.tag("AiPlaylist").e(e, "AI playlist generation threw unhandled exception")
                _aiStatus.value = null
                _aiError.value = resolveAiErrorMessage(e)
                notificationManager.showCompletion("Generation Failed", extractAiErrorDetail(e).take(140))
            } finally {
                _isGeneratingAiPlaylist.value = false
                _aiStatus.value = null
            }
        }
    }

    /**
     * Refines the existing Daily Mix playlist using an AI prompt.
     * Uses the current mix as a vibe seed and applies AI filters to find similar tracks.
     */
    fun regenerateDailyMixWithPrompt(prompt: String) {
        val scope = this.scope ?: return
        val currentDailyMixSongs = dailyMixStateHolder.dailyMixSongs.value

        scope.launch {
            val allSongs = allSongsProvider?.invoke() ?: emptyList()
            val favoriteIds = favoriteSongIdsProvider?.invoke() ?: emptySet()
            if (prompt.isBlank()) {
                toastEmitter?.invoke(context.getString(R.string.ai_state_prompt_empty))
                return@launch
            }

            _isGeneratingAiPlaylist.value = true
            _aiError.value = null

            try {
                _aiStatus.value = "Refining your Daily Mix..."
                val desiredSize = currentDailyMixSongs.size.takeIf { it > 0 } ?: 25
                val minLength = (desiredSize * 0.6).toInt().coerceAtLeast(10)
                val maxLength = desiredSize.coerceAtLeast(20)
                
                _aiStatus.value = "Scanning for vibes..."
                val candidatePool = dailyMixManager.generateDailyMix(
                    allSongs = allSongs,
                    favoriteSongIds = favoriteIds,
                    limit = 100
                )

                _aiStatus.value = "Applying AI filters..."
                val result = aiPlaylistGenerator.generate(
                    userPrompt = prompt,
                    allSongs = allSongs,
                    minLength = minLength,
                    maxLength = maxLength,
                    candidateSongs = candidatePool, type = AiSystemPromptType.DAILY_MIX
                )

                result.onSuccess { generatedSongs ->
                    if (generatedSongs.isNotEmpty()) {
                        dailyMixStateHolder.setDailyMixSongs(generatedSongs)
                        toastEmitter?.invoke(context.getString(R.string.ai_state_daily_mix_updated))
                    } else {
                        toastEmitter?.invoke(context.getString(R.string.ai_state_no_songs_for_mix))
                    }
                }.onFailure { error ->
                    Timber.tag("AiPlaylist").e(error, "Daily Mix refinement failed")
                    val detail = extractAiErrorDetail(error)
                    _aiError.value = resolveAiErrorMessage(error)
                    toastEmitter?.invoke(context.getString(R.string.ai_state_could_not_update, detail))
                }
            } catch (e: Exception) {
                Timber.tag("AiPlaylist").e(e, "Daily Mix refinement threw unhandled exception")
                _aiError.value = resolveAiErrorMessage(e)
                toastEmitter?.invoke(context.getString(R.string.ai_state_could_not_update, extractAiErrorDetail(e)))
            } finally {
                _isGeneratingAiPlaylist.value = false
                _aiStatus.value = null
            }
        }
    }

    suspend fun translateLyrics(lyricsText: String): Result<String> {
        return try {
            val targetLanguage = context.resources.configuration.locales[0].displayLanguage
            val xmlPrompt = """
<task>Translate song lyrics into $targetLanguage.</task>

<rules>
- Preserve ALL timestamps [mm:ss.xx] exactly — never modify, merge, or drop them.
- Output TWO lines per original line: the original, then the translation with the same timestamp.
- NEVER add explanations, labels, numbering, section headers, or formatting.
- NEVER remove, merge, split, or reorder lines.
- If lyrics are ALREADY mostly in $targetLanguage, output ONLY: ALREADY_IN_TARGET_LANGUAGE
</rules>

<format>
[original timestamp] original text
[same timestamp] translated text
</format>

<lyrics>
$lyricsText
</lyrics>
            """.trimIndent()

            val response = aiHandler.generateContent(
                prompt = xmlPrompt,
                type = AiSystemPromptType.LYRICS,
                temperature = 0.1f
            )
            val cleaned = AiResponseCleaner.cleanTextResponse(response)
            Result.success(cleaned)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun onCleared() {
        scope = null
        allSongsProvider = null
        favoriteSongIdsProvider = null
        toastEmitter = null
        playSongsCallback = null
        openPlayerSheetCallback = null
    }

    private fun resolveAiErrorMessage(error: Throwable): String {
        val providerFailure = error.findProviderFailure()
        val detail = extractAiErrorDetail(error)

        return when {
            providerFailure?.isApiKeyIssue() == true ||
                detail.contains("api key not valid", ignoreCase = true) ||
                detail.contains("invalid api key", ignoreCase = true) ||
                detail.contains("incorrect api key", ignoreCase = true) ||
                detail.contains("invalid key", ignoreCase = true) ->
                context.getString(R.string.ai_state_error_api_key)

            providerFailure?.isBillingIssue() == true ->
                context.getString(R.string.ai_state_error_quota)

            providerFailure?.isModelUnavailable() == true ->
                context.getString(R.string.ai_state_error_model_unavailable)

            // Timeout errors
            detail.contains("timed out", ignoreCase = true) || 
            detail.contains("timeout", ignoreCase = true) ->
                "Request timed out. The AI provider is slow or overloaded. Try again in a moment."

            // Network/WiFi errors
            detail.contains("network", ignoreCase = true) ||
            detail.contains("connect", ignoreCase = true) ||
            detail.contains("resolve host", ignoreCase = true) ||
            detail.contains("SocketException", ignoreCase = true) ||
            detail.contains("no internet", ignoreCase = true) ||
            detail.contains("offline", ignoreCase = true) ||
            detail.contains("wifi", ignoreCase = true) ->
                "No Internet Connection. Please check your WiFi or mobile data and try again."

            // Airplane mode specific
            detail.contains("airplane", ignoreCase = true) ->
                "Airplane mode is on. Turn it off to use AI features."

            // Permission/Auth errors
            detail.contains("permission", ignoreCase = true) ||
            detail.contains("denied", ignoreCase = true) ||
            detail.contains("forbidden", ignoreCase = true) ||
            detail.contains("403", ignoreCase = true) ->
                "Permission denied by the AI provider. Check that this API key has access to the selected model and that the provider API is enabled."

            detail.contains("unauthorized", ignoreCase = true) ||
            detail.contains("401", ignoreCase = true) ->
                context.getString(R.string.ai_state_error_api_key)

            // Rate limiting
            detail.contains("rate limit", ignoreCase = true) ||
            detail.contains("429", ignoreCase = true) ||
            detail.contains("too many requests", ignoreCase = true) ->
                "Rate limited. The AI provider needs a short break. Wait 30 seconds and try again."

            // Safety filter
            detail.contains("safety", ignoreCase = true) ||
            detail.contains("blocked", ignoreCase = true) ||
            detail.contains("filtered", ignoreCase = true) ->
                "Content was blocked by the AI's safety filters. Try rephrasing your request."

            // Invalid response format
            detail.contains("valid playlist", ignoreCase = true) ||
            detail.contains("JSON array", ignoreCase = true) ||
            detail.contains("invalid response", ignoreCase = true) ->
                "The AI returned an unexpected format. Try again or switch to a more capable model."

            // No API key configured
            detail.contains("No API key", ignoreCase = true) ||
            detail.contains("not configured", ignoreCase = true) ->
                context.getString(R.string.ai_state_error_api_key)

            // Cooldown
            detail.contains("cooldown", ignoreCase = true) ->
                "AI providers are cooling down after recent errors. Wait a few minutes and try again."

            // Empty response
            detail.contains("empty response", ignoreCase = true) ->
                "The AI returned an empty response. This typically means the model filtered the content. Try a different prompt."

            else ->
                context.getString(R.string.ai_state_error_generic, detail)
        }
    }

    private fun extractAiErrorDetail(error: Throwable): String {
        return generateSequence(error) { it.cause }
            .flatMap { throwable ->
                sequenceOf(throwable.message.orEmpty())
            }
            .map { raw ->
                raw.replace(Regex("^AI\\s*Error:\\s*", RegexOption.IGNORE_CASE), "").trim()
            }
            .firstOrNull { it.isNotBlank() }
            ?: "Unknown error"
    }

    private fun Throwable.findProviderFailure(): AiProviderException? {
        return generateSequence(this) { it.cause }
            .filterIsInstance<AiProviderException>()
            .firstOrNull()
    }

    private fun resolveAiPlaylistName(
        requestedName: String?,
        prompt: String,
        existingNames: Set<String>
    ): String {
        val normalizedExisting = existingNames.map { it.lowercase() }.toSet()
        val baseName = requestedName?.trim().takeUnless { it.isNullOrEmpty() }
            ?: generateShortAiTitle(prompt)

        var candidate = baseName.ifBlank { "AI Mix" }
        if (candidate.lowercase() !in normalizedExisting) {
            return candidate
        }

        var counter = 2
        while ("$candidate $counter".lowercase() in normalizedExisting) {
            counter++
        }
        return "$candidate $counter"
    }

    private fun generateShortAiTitle(prompt: String): String {
        val coreRequest = Regex("(?i)core request:\\s*([^.]*)")
            .find(prompt)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            .orEmpty()

        val source = if (coreRequest.isNotBlank()) coreRequest else prompt
        val normalizedText = source
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        val tokens = normalizedText
            .split(" ")
            .filter { token ->
                token.length >= 3 && token !in titleStopWords
            }

        val compactTitle = when {
            tokens.size >= 2 -> tokens.take(2).joinToString(" ")
            tokens.size == 1 -> "${tokens.first()} mix"
            else -> fallbackTitleByKeyword(normalizedText)
        }

        return compactTitle
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            .split(" ")
            .joinToString(" ") { part ->
                part.replaceFirstChar { ch -> if (ch.isLowerCase()) ch.titlecase() else ch.toString() }
            }
            .take(26)
            .trim()
            .ifBlank { "AI Mix" }
    }

    private fun fallbackTitleByKeyword(text: String): String {
        return when {
            listOf("workout", "gym", "run", "cardio").any { text.contains(it) } -> "Workout Mix"
            listOf("focus", "study", "work", "productivity").any { text.contains(it) } -> "Focus Flow"
            listOf("chill", "relax", "calm", "lofi").any { text.contains(it) } -> "Chill Vibes"
            listOf("party", "dance", "club").any { text.contains(it) } -> "Party Mix"
            listOf("night", "late", "sleep").any { text.contains(it) } -> "Night Vibes"
            listOf("road", "trip", "drive").any { text.contains(it) } -> "Road Trip"
            listOf("romantic", "love").any { text.contains(it) } -> "Love Notes"
            listOf("sad", "melancholic").any { text.contains(it) } -> "Blue Hour"
            else -> "Fresh Mix"
        }
    }
}

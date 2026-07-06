package com.theveloper.pixelplay.data.ai

import com.theveloper.pixelplay.data.ai.provider.UnifiedModelFilter
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.data.worker.AiWorkerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

data class GeminiModel(
    val name: String,
    val displayName: String
)

@Singleton
class GeminiModelService @Inject constructor(
    private val handler: AiHandler,
    private val digestGenerator: UserProfileDigestGenerator,
    private val musicRepository: MusicRepository,
    private val workerManager: AiWorkerManager
) {

    companion object {
        // Use UnifiedModelFilter for consistent model filtering across all providers.
    }

    suspend fun fetchAvailableModels(apiKey: String): Result<List<GeminiModel>> {
        return withContext(Dispatchers.IO) {
            try {
                if (apiKey.isBlank()) {
                    return@withContext Result.failure(Exception("API Key is required"))
                }
                val response = makeModelsListRequest(apiKey)
                Result.success(response)
            } catch (e: Exception) {
                Timber.e(e, "Error fetching Gemini models")
                Result.failure(e)
            }
        }
    }

    private suspend fun makeModelsListRequest(apiKey: String): List<GeminiModel> {
        return withContext(Dispatchers.IO) {
            try {
                val url = "https://generativelanguage.googleapis.com/v1beta/models"
                val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection

                connection.requestMethod = "GET"
                connection.setRequestProperty("x-goog-api-key", apiKey)
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                val responseCode = connection.responseCode
                val apiModels = if (responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    parseModelsResponse(response)
                } else emptyList()

                val defaults = getDefaultModels()
                (apiModels + defaults).distinctBy { it.name }.sortedWith(
                    compareBy<GeminiModel> { model ->
                        val preferred = defaults.map { it.name }
                        preferred.indexOf(model.name).takeIf { it >= 0 } ?: Int.MAX_VALUE
                    }.thenBy { it.displayName.lowercase() }
                )
            } catch (e: Exception) {
                getDefaultModels()
            }
        }
    }

    private fun parseModelsResponse(jsonResponse: String): List<GeminiModel> {
        try {
            val models = mutableListOf<GeminiModel>()
            val modelPattern = """"name":\s*"(models/[^"]+)"""".toRegex()
            val matches = modelPattern.findAll(jsonResponse)

            for (match in matches) {
                val fullName = match.groupValues[1]
                val modelName = fullName.removePrefix("models/")

                // Only exclude models that can't do text generation. Never filter by
                // version — let the user pick any chat-capable model their key supports.
                if ((modelName.startsWith("gemini", ignoreCase = true) ||
                     modelName.startsWith("gemma", ignoreCase = true)) &&
                    UnifiedModelFilter.isModelUsableForChat(modelName)) {
                    models.add(GeminiModel(
                        name = modelName,
                        displayName = formatDisplayName(modelName)
                    ))
                }
            }
            return models
        } catch (e: Exception) {
            return emptyList()
        }
    }

    fun estimateTokens(text: String): Int {
        return (text.length / 4).coerceAtLeast(1)
    }

    suspend fun performAiTask(
        prompt: String,
        type: AiSystemPromptType,
        runInBackground: Boolean = false,
        temperature: Float = 0.7f
    ): String? {
        if (runInBackground) {
            workerManager.enqueueAiTask(prompt, type, temperature)
            return null
        } else {
            val allSongs = musicRepository.getAllSongsOnce()
            val context = if (type == AiSystemPromptType.PLAYLIST || 
                            type == AiSystemPromptType.TAGGING || 
                            type == AiSystemPromptType.PERSONA) {
                digestGenerator.generateDigest(allSongs)
            } else ""

            return handler.generateContent(
                prompt = prompt,
                type = type,
                temperature = temperature,
                context = context
            )
        }
    }

    private fun formatDisplayName(modelName: String): String {
        return modelName
            .split("-")
            .joinToString(" ") { word ->
                word.replaceFirstChar { it.uppercase() }
            }
    }

    private fun getDefaultModels(): List<GeminiModel> {
        return listOf(
            GeminiModel("gemini-3.1-flash-lite", "Gemini 3.1 Flash Lite (Recommended Default)"),
            GeminiModel("gemini-3.5-flash", "Gemini 3.5 Flash"),
            GeminiModel("gemini-3.1-pro-preview", "Gemini 3.1 Pro (Preview)"),
            GeminiModel("gemini-flash-lite-latest", "Gemini Flash Lite Latest"),
            GeminiModel("gemini-flash-latest", "Gemini Flash Latest"),
            GeminiModel("gemma-4-31b-it", "Gemma 4 31B IT"),
            GeminiModel("gemma-4-26b-a4b-it", "Gemma 4 26B MoE")
        )
    }
}

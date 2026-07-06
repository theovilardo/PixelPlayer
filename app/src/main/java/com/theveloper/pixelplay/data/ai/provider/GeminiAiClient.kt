package com.theveloper.pixelplay.data.ai.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Gemini provider implementation.
 *
 * Generation talks directly to the Generative Language REST API (the same endpoint
 * used for model listing) instead of the deprecated `com.google.ai.client.generativeai`
 * SDK. This keeps generation in lockstep with whatever models the API actually serves,
 * so any model the user selects works as long as their key supports it.
 */
class GeminiAiClient(private val apiKey: String, private val httpClient: OkHttpClient) : AiClient {

    companion object {
        private const val DEFAULT_GEMINI_MODEL = "gemini-3.1-flash-lite"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Serializable
    private data class Part(val text: String)

    @Serializable
    private data class Content(val role: String? = null, val parts: List<Part>)

    @Serializable
    private data class GenerationConfig(
        val temperature: Double,
        val topK: Int = 64,
        val topP: Double = 0.95,
        @SerialName("maxOutputTokens") val maxOutputTokens: Int = 8192,
        @SerialName("presencePenalty") val presencePenalty: Double? = null,
        @SerialName("frequencyPenalty") val frequencyPenalty: Double? = null
    )

    @Serializable
    private data class GenerateRequest(
        val contents: List<Content>,
        val systemInstruction: Content? = null,
        val generationConfig: GenerationConfig
    )

    @Serializable
    private data class Candidate(
        val content: Content? = null,
        val finishReason: String? = null
    )

    @Serializable
    private data class PromptFeedback(
        val blockReason: String? = null
    )

    @Serializable
    private data class GenerateResponse(
        val candidates: List<Candidate> = emptyList(),
        val promptFeedback: PromptFeedback? = null
    )

    override suspend fun generateContent(
        model: String,
        systemPrompt: String,
        prompt: String,
        temperature: Float,
        topP: Float,
        topK: Int,
        maxTokens: Int,
        presencePenalty: Float,
        frequencyPenalty: Float
    ): String {
        return withContext(Dispatchers.IO) {
            val resolvedModel = model.ifBlank { DEFAULT_GEMINI_MODEL }

            val requestBody = GenerateRequest(
                contents = listOf(Content(role = "user", parts = listOf(Part(prompt)))),
                systemInstruction = systemPrompt
                    .takeIf { it.isNotBlank() }
                    ?.let { Content(parts = listOf(Part(it))) },
                generationConfig = GenerationConfig(
                    temperature = temperature.toDouble(),
                    topK = topK,
                    topP = topP.toDouble(),
                    maxOutputTokens = maxTokens,
                    presencePenalty = presencePenalty.toDouble().takeIf { it != 0.0 },
                    frequencyPenalty = frequencyPenalty.toDouble().takeIf { it != 0.0 }
                )
            )

            val jsonBody = json.encodeToString(GenerateRequest.serializer(), requestBody)
            val body = jsonBody.toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$BASE_URL/models/$resolvedModel:generateContent")
                .addHeader("x-goog-api-key", apiKey)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()

            try {
                httpClient.newCall(request).execute().use { response ->
                    val responseBody = response.body.string()

                    if (!response.isSuccessful) {
                        throw AiProviderSupport.createException(
                            providerName = "Gemini",
                            statusCode = response.code,
                            transportMessage = response.message,
                            responseBody = responseBody,
                            requestedModel = resolvedModel
                        )
                    }

                    val parsed = json.decodeFromString<GenerateResponse>(responseBody)

                    parsed.promptFeedback?.blockReason?.let { reason ->
                        throw AiProviderSupport.createException(
                            providerName = "Gemini",
                            statusCode = response.code,
                            transportMessage = "Request was blocked by Gemini (reason: $reason). Try rephrasing your prompt.",
                            responseBody = responseBody,
                            requestedModel = resolvedModel
                        )
                    }

                    val text = parsed.candidates
                        .firstOrNull()
                        ?.content
                        ?.parts
                        ?.joinToString("") { it.text }
                        ?.takeIf { it.isNotBlank() }

                    text ?: throw AiProviderSupport.createException(
                        providerName = "Gemini",
                        statusCode = response.code,
                        transportMessage = "Gemini returned an empty response. The model may have filtered the content.",
                        responseBody = responseBody,
                        requestedModel = resolvedModel
                    )
                }
            } catch (e: Exception) {
                throw AiProviderSupport.wrapThrowable("Gemini", e, resolvedModel)
            }
        }
    }

    override suspend fun getAvailableModels(apiKey: String): List<String> {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$BASE_URL/models")
                    .addHeader("x-goog-api-key", apiKey)
                    .get()
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        parseModelsFromResponse(response.body.string())
                    } else {
                        getDefaultModels()
                    }
                }
            } catch (e: Exception) {
                getDefaultModels()
            }
        }
    }

    override suspend fun validateApiKey(apiKey: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$BASE_URL/models")
                    .addHeader("x-goog-api-key", apiKey)
                    .get()
                    .build()
                httpClient.newCall(request).execute().use { it.isSuccessful }
            } catch (e: Exception) {
                false
            }
        }
    }

    override fun getDefaultModel(): String = DEFAULT_GEMINI_MODEL

    private fun parseModelsFromResponse(jsonResponse: String): List<String> {
        try {
            val models = mutableListOf<String>()
            val modelPattern = """"name":\s*"(models/[^"]+)"""".toRegex()
            val matches = modelPattern.findAll(jsonResponse)

            for (match in matches) {
                val fullName = match.groupValues[1]
                val modelName = fullName.removePrefix("models/")

                // Only exclude models that can't do text generation. Never filter by
                // version — let the user pick any chat-capable model their key supports.
                if ((modelName.startsWith("gemini", ignoreCase = true) ||
                     modelName.startsWith("gemma", ignoreCase = true)) &&
                    !isNonChatModel(modelName)) {
                    models.add(modelName)
                }
            }

            val defaults = getDefaultModels()
            return (models + defaults).distinct().sorted()
        } catch (e: Exception) {
            return getDefaultModels()
        }
    }

    private fun isNonChatModel(modelName: String): Boolean {
        return !UnifiedModelFilter.isModelUsableForChat(modelName)
    }

    private fun getDefaultModels(): List<String> {
        return listOf(
            "gemini-3.1-flash-lite",
            "gemini-3.5-flash",
            "gemini-3.1-pro-preview",
            "gemini-flash-latest"
        )
    }
}

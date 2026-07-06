package com.theveloper.pixelplay.data.ai


import com.theveloper.pixelplay.data.ai.provider.AiClient
import com.theveloper.pixelplay.data.ai.provider.AiClientFactory
import com.theveloper.pixelplay.data.ai.provider.AiProvider
import com.theveloper.pixelplay.data.ai.provider.AiProviderException
import com.theveloper.pixelplay.data.ai.provider.AiProviderSupport
import com.theveloper.pixelplay.data.database.AiCacheDao
import com.theveloper.pixelplay.data.database.AiCacheEntity
import com.theveloper.pixelplay.data.preferences.AiPreferencesRepository
import com.theveloper.pixelplay.data.database.AiUsageDao
import com.theveloper.pixelplay.data.database.AiUsageEntity
import com.theveloper.pixelplay.di.AppScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiHandler @Inject constructor(
    private val preferencesRepo: AiPreferencesRepository,
    private val clientFactory: AiClientFactory,
    private val cacheDao: AiCacheDao,
    private val usageDao: AiUsageDao,
    private val promptEngine: AiSystemPromptEngine,
    @AppScope private val appScope: CoroutineScope
) {
    // Cooldown timer: Provider -> Expiry Timestamp (thread-safe, auto-cleans expired)
    private val providerCooldowns = ConcurrentHashMap<AiProvider, Long>()
    private val COOLDOWN_DURATION_MS = 1000L * 60 * 2 // 2 minutes (was 5)

    // Cache TTL: 30 minutes — prevents stale results from being served indefinitely
    private val CACHE_TTL_MS = 1000L * 60 * 30

    // Request timeout: 60 seconds max per provider attempt
    private val REQUEST_TIMEOUT_MS = 60_000L

    private fun String.sha256(): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(this.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private suspend fun getBasePersona(provider: AiProvider): String {
        return preferencesRepo.getSystemPrompt(provider).first()
            .ifBlank { AiPreferencesRepository.DEFAULT_SYSTEM_PROMPT }
    }

    private suspend fun getApiKey(provider: AiProvider): String {
        return preferencesRepo.getApiKey(provider).first()
    }

    private suspend fun getModel(provider: AiProvider): String {
        return preferencesRepo.getModel(provider).first()
    }

    private suspend fun setModel(provider: AiProvider, model: String) {
        preferencesRepo.setModel(provider, model)
    }

    private data class GenerationParams(
        val temperature: Float,
        val topP: Float,
        val topK: Int,
        val maxTokens: Int,
        val presencePenalty: Float,
        val frequencyPenalty: Float,
    )

    private data class GenerationResult(
        val response: String,
        val modelUsed: String,
    )

    private suspend fun getGenerationParams(): GenerationParams {
        return GenerationParams(
            temperature = preferencesRepo.aiTemperature.first(),
            topP = preferencesRepo.aiTopP.first(),
            topK = preferencesRepo.aiTopK.first(),
            maxTokens = preferencesRepo.aiMaxTokens.first(),
            presencePenalty = preferencesRepo.aiPresencePenalty.first(),
            frequencyPenalty = preferencesRepo.aiFrequencyPenalty.first(),
        )
    }

    private suspend fun generateWithRecovery(
        provider: AiProvider,
        apiKey: String,
        systemPrompt: String,
        prompt: String,
        temperature: Float,
        topP: Float,
        topK: Int,
        maxTokens: Int,
        presencePenalty: Float,
        frequencyPenalty: Float,
    ): GenerationResult {
        val client = if (provider.hasConfigurableUrl) {
            val configuredUrl = preferencesRepo.getBaseUrl(provider).first()
            if (configuredUrl.isNotBlank()) clientFactory.createClientWithUrl(provider, apiKey, configuredUrl)
            else clientFactory.createClient(provider, apiKey)
        } else {
            clientFactory.createClient(provider, apiKey)
        }
        val requestedModel = getModel(provider).ifBlank { client.getDefaultModel() }

        suspend fun callWithModel(model: String): String {
            return try {
                withTimeout(REQUEST_TIMEOUT_MS) {
                    client.generateContent(
                        model, systemPrompt, prompt, temperature,
                        topP, topK, maxTokens, presencePenalty, frequencyPenalty,
                    )
                }
            } catch (e: TimeoutCancellationException) {
                throw AiProviderSupport.createException(
                    providerName = provider.displayName,
                    statusCode = null,
                    transportMessage = "Request timed out after ${REQUEST_TIMEOUT_MS / 1000}s. The model may be overloaded.",
                    responseBody = null,
                    requestedModel = model
                )
            }
        }

        return try {
            val response = callWithModel(requestedModel)
            GenerationResult(response, requestedModel)
        } catch (e: Exception) {
            val failure = AiProviderSupport.wrapThrowable(
                provider.displayName, e, requestedModel
            )

            val recoveredModel = recoverModelIfNeeded(
                provider, apiKey, requestedModel, client, failure
            ) ?: throw failure

            val response = callWithModel(recoveredModel)
            GenerationResult(response, recoveredModel)
        }
    }

    private suspend fun recoverModelIfNeeded(
        provider: AiProvider,
        apiKey: String,
        requestedModel: String,
        client: AiClient,
        failure: AiProviderException
    ): String? {
        if (!failure.isModelUnavailable()) return null

        val availableModels = runCatching { client.getAvailableModels(apiKey) }.getOrDefault(emptyList())
        val recoveredModel = AiProviderSupport.selectRecoveryModel(
            currentModel = requestedModel,
            defaultModel = client.getDefaultModel(),
            availableModels = availableModels
        ) ?: return null

        setModel(provider, recoveredModel)
        return recoveredModel
    }

    suspend fun generateContent(
        prompt: String,
        type: AiSystemPromptType = AiSystemPromptType.GENERAL,
        temperature: Float = 0.7f,
        context: String = ""
    ): String {
        val params = getGenerationParams()
        val effectiveMaxTokens = if (type == AiSystemPromptType.LYRICS) {
            val estimatedInputChars = prompt.length
            // Translation doubles output (original + translated per line), plus formatting overhead
            val estimatedOutputChars = estimatedInputChars * 3
            val cjkRatio = prompt.count { it.code in 0x4E00..0x9FFF || it.code in 0x3040..0x30FF || it.code in 0xAC00..0xD7AF || it.code in 0x0E00..0x0E7F || it.code in 0x0600..0x06FF || it.code in 0x0370..0x03FF || it.code in 0x0590..0x05FF || it.code in 0x0900..0x097F || it.code in 0x0A00..0x0A7F }.toFloat() / estimatedInputChars.coerceAtLeast(1)
            val charsPerToken = (4f - cjkRatio * 3f).coerceIn(1f, 4f)
            val estimatedOutputTokens = (estimatedOutputChars / charsPerToken).toInt().coerceAtLeast(8192)
            estimatedOutputTokens.coerceAtMost(32768)
        } else {
            params.maxTokens
        }
        val effectiveTemperature = if (params.temperature == 0.7f) {
            if (temperature == 0.7f) {
                when (type) {
                    AiSystemPromptType.METADATA -> 0.1f
                    AiSystemPromptType.MOOD_ANALYSIS -> 0.2f
                    AiSystemPromptType.TAGGING -> 0.4f
                    AiSystemPromptType.PLAYLIST, AiSystemPromptType.DAILY_MIX -> 0.6f
                    AiSystemPromptType.PERSONA -> 0.85f
                    AiSystemPromptType.LYRICS -> 0.7f
                    AiSystemPromptType.GENERAL -> 0.7f
                }
            } else temperature
        } else params.temperature

        val userProviderStr = preferencesRepo.aiProvider.first()
        val userProvider = AiProvider.fromString(userProviderStr)

        val basePersona = getBasePersona(userProvider)
        val combinedSystemPrompt = promptEngine.buildPrompt(basePersona, type, context)

        val hash = (userProvider.name + combinedSystemPrompt + prompt).sha256()

        cacheDao.getCache(hash)?.let { cached ->
            val age = System.currentTimeMillis() - cached.timestamp
            if (age < CACHE_TTL_MS) {
                return cached.responseJson
            }
        }

        // Clean up expired cooldowns so stale entries never accumulate
        val now = System.currentTimeMillis()
        providerCooldowns.entries.removeIf { it.value < now }

        val providersToTry = AiProviderSupport.buildProviderChain(userProvider)
        val failedProviders = mutableListOf<String>()

        for (provider in providersToTry) {
            val cooldownExpiry = providerCooldowns[provider] ?: 0L
            if (now < cooldownExpiry) {
                failedProviders.add("${provider.name}: on cooldown (${((cooldownExpiry - now) / 1000)}s remaining)")
                continue
            }

            try {
                val apiKey = getApiKey(provider)
                if (apiKey.isBlank() && provider.requiresApiKey) {
                    failedProviders.add("${provider.name}: no API key configured")
                    continue
                }

                val providerPersona = getBasePersona(provider)
                val finalSystemPrompt = promptEngine.buildPrompt(providerPersona, type, context)

                val result = generateWithRecovery(
                    provider = provider,
                    apiKey = apiKey,
                    systemPrompt = finalSystemPrompt,
                    prompt = prompt,
                    temperature = effectiveTemperature,
                    topP = params.topP,
                    topK = params.topK,
                    maxTokens = effectiveMaxTokens,
                    presencePenalty = params.presencePenalty,
                    frequencyPenalty = params.frequencyPenalty,
                )

                if (result.response.isBlank()) {
                    failedProviders.add("${provider.name}: returned empty response")
                    continue
                }

                val isThinkingModel = result.modelUsed.contains("think", true) || result.modelUsed.contains("reason", true) || provider.name.contains("reasoning", true)
                val estimatedPromptTokens = (finalSystemPrompt.length + prompt.length) / 4
                val estimatedOutputTokens = result.response.length / 4
                val estimatedThoughtTokens = if (isThinkingModel) (estimatedOutputTokens * 1.5).toInt() else 0

                appScope.launch {
                    runCatching {
                        usageDao.insertUsage(
                            AiUsageEntity(
                                timestamp = now,
                                provider = provider.displayName,
                                model = result.modelUsed,
                                promptType = type.name,
                                promptTokens = estimatedPromptTokens,
                                outputTokens = estimatedOutputTokens,
                                thoughtTokens = estimatedThoughtTokens
                            )
                        )
                    }.onFailure { error ->
                        Timber.tag("AiHandler").e(error, "Failed to persist AI usage")
                    }
                }

                cacheDao.insert(AiCacheEntity(promptHash = hash, responseJson = result.response, timestamp = System.currentTimeMillis()))

                // Evict cache entries older than 7 days
                val sevenDaysAgo = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)
                try { cacheDao.clearOldCache(sevenDaysAgo) } catch (_: Exception) {}

                return result.response
            } catch (e: Exception) {
                // AI Optimization: Robust failover logic—if one provider fails, we log and try the next in the chain
                val failure = AiProviderSupport.wrapThrowable(provider.displayName, e)
                Timber.tag("AiHandler").w(e, "Provider ${provider.name} failed: ${failure.message}")
                failedProviders.add("${provider.name}: ${failure.message ?: "Unknown error"}")
                // Trigger cooldown only on provider-level outages and account problems.
                if (failure.shouldCooldown()) {
                    providerCooldowns[provider] = now + COOLDOWN_DURATION_MS
                }
            }
        }
        
        // AI Integration: Bubble up a detailed, user-friendly error if all providers fail
        val errorMessage = when {
            failedProviders.all { it.contains("no API key") } ->
                "No API key configured. Go to Settings → AI Integration to set up your API key."
            
            failedProviders.all { it.contains("cooldown") } ->
                "All AI providers are on cooldown after recent errors. Wait a few minutes and try again."
            
            failedProviders.size == 1 ->
                "AI generation failed: ${failedProviders.first()}"
            
            else ->
                "AI generation failed after trying ${failedProviders.size} providers:\n${failedProviders.joinToString("\n• ", prefix = "• ")}"
        }
        
        Timber.tag("AiHandler").e("All providers failed. Details: %s", failedProviders.joinToString(" | "))
        throw Exception(errorMessage)
    }
}

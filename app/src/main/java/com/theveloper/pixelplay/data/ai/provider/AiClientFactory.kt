package com.theveloper.pixelplay.data.ai.provider

import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Factory for creating AI client instances based on provider type
 */
@Singleton
class AiClientFactory @Inject constructor(
    @com.theveloper.pixelplay.di.AiOkHttpClient private val sharedHttpClient: OkHttpClient
) {
    
    /**
     * Create an AI client for the specified provider
     * @param provider The AI provider type
     * @param apiKey The API key for the provider
     * @return AiClient instance
     */
    fun createClient(provider: AiProvider, apiKey: String): AiClient {
        if (apiKey.isBlank() && provider.requiresApiKey) {
            throw IllegalArgumentException("API Key cannot be blank for ${provider.displayName}")
        }
        
        return when (provider) {
            AiProvider.GEMINI -> GeminiAiClient(apiKey, sharedHttpClient)
            AiProvider.DEEPSEEK -> GenericOpenAiClient(
                apiKey = apiKey,
                baseUrl = "https://api.deepseek.com/v1",
                defaultModelId = "deepseek-chat",
                providerName = provider.displayName,
                httpClient = sharedHttpClient
            )
            AiProvider.GROQ -> GenericOpenAiClient(
                apiKey = apiKey,
                baseUrl = "https://api.groq.com/openai/v1",
                defaultModelId = "llama-3.3-70b-versatile",
                providerName = provider.displayName,
                httpClient = sharedHttpClient
            )
            AiProvider.MISTRAL -> GenericOpenAiClient(
                apiKey = apiKey,
                baseUrl = "https://api.mistral.ai/v1",
                defaultModelId = "mistral-large-2411",
                providerName = provider.displayName,
                httpClient = sharedHttpClient
            )
            AiProvider.NVIDIA -> GenericOpenAiClient(
                apiKey = apiKey,
                baseUrl = "https://integrate.api.nvidia.com/v1",
                defaultModelId = "nvidia/llama-3.1-nemotron-70b-instruct",
                providerName = provider.displayName,
                httpClient = sharedHttpClient
            )
            AiProvider.KIMI -> GenericOpenAiClient(
                apiKey = apiKey,
                baseUrl = "https://api.moonshot.cn/v1",
                defaultModelId = "moonshot-v1-auto",
                providerName = provider.displayName,
                httpClient = sharedHttpClient
            )
            AiProvider.GLM -> GenericOpenAiClient(
                apiKey = apiKey,
                baseUrl = "https://open.bigmodel.cn/api/paas/v4",
                defaultModelId = "glm-4-plus",
                providerName = provider.displayName,
                httpClient = sharedHttpClient
            )
            AiProvider.OPENAI -> GenericOpenAiClient(
                apiKey = apiKey,
                baseUrl = "https://api.openai.com/v1",
                defaultModelId = "gpt-4o-mini",
                providerName = provider.displayName,
                httpClient = sharedHttpClient
            )
            AiProvider.OPENROUTER -> GenericOpenAiClient(
                apiKey = apiKey,
                baseUrl = "https://openrouter.ai/api/v1",
                defaultModelId = "google/gemini-2.5-flash-preview-04-17:free",
                providerName = provider.displayName,
                httpClient = sharedHttpClient
            )
            AiProvider.OLLAMA -> GenericOpenAiClient(
                apiKey = apiKey,
                baseUrl = "https://api.ollama.ai/v1",
                defaultModelId = "llama3",
                providerName = provider.displayName,
                httpClient = sharedHttpClient
            )
            AiProvider.CUSTOM -> GenericOpenAiClient(
                apiKey = apiKey,
                baseUrl = "",
                defaultModelId = "",
                providerName = provider.displayName,
                httpClient = sharedHttpClient
            )
        }
    }

    fun createClientWithUrl(provider: AiProvider, apiKey: String, baseUrl: String): AiClient {
        val displayName = provider.displayName
        return GenericOpenAiClient(apiKey, baseUrl.trimEnd('/'), "", displayName, sharedHttpClient)
    }
}

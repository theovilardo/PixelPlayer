package com.theveloper.pixelplay.data.ai.provider

/**
 * Abstract interface for AI providers
 * Defines common operations for text generation and metadata completion
 */
interface AiClient {

    suspend fun generateContent(
        model: String, 
        systemPrompt: String, 
        prompt: String,
        temperature: Float = 0.7f,
        topP: Float = 0.95f,
        topK: Int = 64,
        maxTokens: Int = 4096,
        presencePenalty: Float = 0.0f,
        frequencyPenalty: Float = 0.0f
    ): String
    
    /**
     * Get list of available models for this provider
     */
    suspend fun getAvailableModels(apiKey: String): List<String>
    
    /**
     * Validate the API key
     */
    suspend fun validateApiKey(apiKey: String): Boolean
    
    /**
     * Get the default model for this provider
     */
    fun getDefaultModel(): String
}

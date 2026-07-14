package com.theveloper.pixelplay.data.ai.provider

enum class AiProvider(
    val displayName: String,
    val requiresApiKey: Boolean,
    val hasConfigurableUrl: Boolean = false,
    val description: String = ""
) {
    GEMINI("Google Gemini", requiresApiKey = true, description = "Gemini 2.5 Pro/Flash — Google's latest multimodal models"),
    DEEPSEEK("DeepSeek", requiresApiKey = true, description = "DeepSeek-V3 & R1 — competitive open-weight reasoning models"),
    GROQ("Groq", requiresApiKey = true, description = "Llama 3, Mixtral, Gemma — ultra-fast LPU inference"),
    MISTRAL("Mistral", requiresApiKey = true, description = "Mistral Large, Small, Codestral — efficient European models"),
    NVIDIA("NVIDIA NIM", requiresApiKey = true, description = "NVIDIA-optimized Llama, Nemotron, and community models"),
    KIMI("Kimi (Moonshot)", requiresApiKey = true, description = "Kimi k1.5 — long-context reasoning by Moonshot AI"),
    GLM("Zhipu GLM", requiresApiKey = true, description = "GLM-4 — bilingual (Chinese/English) models by Zhipu AI"),
    OPENAI("OpenAI", requiresApiKey = true, description = "GPT-4o, GPT-4.1, o3, o4-mini — industry-standard models"),
    OPENROUTER("OpenRouter", requiresApiKey = true, description = "Single API for 300+ models across all major providers"),
    OLLAMA("Ollama", requiresApiKey = true, hasConfigurableUrl = true, description = "Ollama-compatible API endpoint (configurable URL)"),
    CUSTOM("Custom Provider", requiresApiKey = true, hasConfigurableUrl = true, description = "Any OpenAI-compatible API endpoint");
    
    companion object {
        fun fromString(value: String): AiProvider {
            return entries.find { it.name == value } ?: GEMINI
        }
    }
}

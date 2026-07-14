package com.theveloper.pixelplay.presentation.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.backup.BackupManager
import com.theveloper.pixelplay.data.backup.model.BackupSection
import com.theveloper.pixelplay.data.backup.model.BackupOperationType
import com.theveloper.pixelplay.data.backup.model.BackupTransferProgressUpdate
import com.theveloper.pixelplay.data.backup.model.BackupHistoryEntry
import com.theveloper.pixelplay.data.backup.model.RestorePlan
import com.theveloper.pixelplay.data.backup.model.RestoreResult
import com.theveloper.pixelplay.data.backup.model.ValidationError
import com.theveloper.pixelplay.data.preferences.AppThemeMode
import com.theveloper.pixelplay.data.preferences.CarouselStyle
import com.theveloper.pixelplay.data.preferences.LibraryNavigationMode
import com.theveloper.pixelplay.data.preferences.ThemePreference
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.data.database.AiUsageDao
import com.theveloper.pixelplay.data.database.AiUsageEntity
import com.theveloper.pixelplay.data.preferences.AiPreferencesRepository
import com.theveloper.pixelplay.data.preferences.AlbumArtQuality
import com.theveloper.pixelplay.data.preferences.AlbumArtColorAccuracy
import com.theveloper.pixelplay.data.preferences.AlbumArtPaletteStyle
import com.theveloper.pixelplay.data.preferences.AppLanguage
import com.theveloper.pixelplay.data.preferences.CollagePattern
import com.theveloper.pixelplay.data.preferences.FullPlayerLoadingTweaks
import com.theveloper.pixelplay.data.preferences.ThemePreferencesRepository
import com.theveloper.pixelplay.data.repository.LyricsRepository
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.data.model.LyricsSourcePreference
import com.theveloper.pixelplay.data.worker.SyncManager
import com.theveloper.pixelplay.data.worker.SyncProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.preferences.NavBarStyle
import com.theveloper.pixelplay.data.ai.GeminiModel
import com.theveloper.pixelplay.data.ai.provider.AiClientFactory
import com.theveloper.pixelplay.data.ai.provider.AiProvider
import com.theveloper.pixelplay.data.preferences.LaunchTab
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.service.player.HiFiCapabilityChecker
import com.theveloper.pixelplay.utils.AppLocaleManager
import java.io.File

data class SettingsUiState(
    val isLoadingDirectories: Boolean = false,
    val appLanguageTag: String = AppLanguage.SYSTEM.tag,
    val appThemeMode: String = AppThemeMode.FOLLOW_SYSTEM,
    val playerThemePreference: String = ThemePreference.ALBUM_ART,
    val albumArtPaletteStyle: AlbumArtPaletteStyle = AlbumArtPaletteStyle.default,
    val albumArtColorAccuracy: Int = AlbumArtColorAccuracy.DEFAULT,
    val mockGenresEnabled: Boolean = false,
    val navBarCornerRadius: Int = 32,
    val navBarStyle: String = NavBarStyle.DEFAULT,
    val navBarCompactMode: Boolean = false,
    val carouselStyle: String = CarouselStyle.NO_PEEK,
    val libraryNavigationMode: String = LibraryNavigationMode.TAB_ROW,
    val launchTab: String = LaunchTab.HOME,
    val keepPlayingInBackground: Boolean = true,
    val disableCastAutoplay: Boolean = false,
    val pauseOnVolumeZero: Boolean = false,
    val resumeOnHeadsetReconnect: Boolean = false,
    val showQueueHistory: Boolean = true,
    val isCrossfadeEnabled: Boolean = false,
    val hiFiModeEnabled: Boolean = false,
    val hiFiModeDeviceSupported: Boolean = true,
    val crossfadeDuration: Int = 2000,
    val persistentShuffleEnabled: Boolean = false,
    val folderBackGestureNavigation: Boolean = true,
    val lyricsSourcePreference: LyricsSourcePreference = LyricsSourcePreference.EMBEDDED_FIRST,
    val autoScanLrcFiles: Boolean = false,
    val blockedDirectories: Set<String> = emptySet(),
    val availableModels: List<GeminiModel> = emptyList(),
    val isLoadingModels: Boolean = false,
    val modelsFetchError: String? = null,
    val appRebrandDialogShown: Boolean = false,
    val beta05CleanInstallDisclaimerDismissed: Boolean? = null,
    val fullPlayerLoadingTweaks: FullPlayerLoadingTweaks = FullPlayerLoadingTweaks(),
    val showPlayerFileInfo: Boolean = true,
    // Developer Options
    val albumArtQuality: AlbumArtQuality = AlbumArtQuality.MEDIUM,
    val albumArtCacheLimitMb: Int = 200,
    val tapBackgroundClosesPlayer: Boolean = false,
    val hapticsEnabled: Boolean = true,
    val immersiveLyricsEnabled: Boolean = false,
    val immersiveLyricsTimeout: Long = 4000L,
    val useAnimatedLyrics: Boolean = false,
    val animatedLyricsBlurEnabled: Boolean = true,
    val animatedLyricsBlurStrength: Float = 2.5f,
    val disableBlurAllOver: Boolean = false,
    val backupInfoDismissed: Boolean = false,
    val isDataTransferInProgress: Boolean = false,
    val restorePlan: RestorePlan? = null,
    val backupHistory: List<BackupHistoryEntry> = emptyList(),
    val backupValidationErrors: List<ValidationError> = emptyList(),
    val isInspectingBackup: Boolean = false,
    val collagePattern: CollagePattern = CollagePattern.default,
    val collageAutoRotate: Boolean = false,
    val minSongDuration: Int = 10000,
    val minTracksPerAlbum: Int = 1,
    val replayGainEnabled: Boolean = false,
    val replayGainUseAlbumGain: Boolean = false,
    val isSafeTokenLimitEnabled: Boolean = true,
    val showScrollbar: Boolean = true
)

data class FailedSongInfo(
    val id: String,
    val title: String,
    val artist: String
)

data class LyricsRefreshProgress(
    val totalSongs: Int = 0,
    val currentCount: Int = 0,
    val savedCount: Int = 0,
    val notFoundCount: Int = 0,
    val skippedCount: Int = 0,
    val isComplete: Boolean = false,
    val failedSongs: List<FailedSongInfo> = emptyList()
) {
    val hasProgress: Boolean get() = totalSongs > 0
    val progress: Float get() = if (totalSongs > 0) currentCount.toFloat() / totalSongs else 0f
    val hasFailedSongs: Boolean get() = failedSongs.isNotEmpty()
}

// Helper classes for consolidated combine() collectors to reduce coroutine overhead
private sealed interface SettingsUiUpdate {
    data class Group1(
        val appRebrandDialogShown: Boolean,
        val appThemeMode: String,
        val playerThemePreference: String,
        val albumArtPaletteStyle: AlbumArtPaletteStyle,
        val albumArtColorAccuracy: Int,
        val mockGenresEnabled: Boolean,
        val navBarCornerRadius: Int,
        val navBarStyle: String,
        val navBarCompactMode: Boolean,
        val libraryNavigationMode: String,
        val carouselStyle: String,
        val launchTab: String,
        val showPlayerFileInfo: Boolean
    ) : SettingsUiUpdate
    
    data class Group2(
        val keepPlayingInBackground: Boolean,
        val disableCastAutoplay: Boolean,
        val pauseOnVolumeZero: Boolean,
        val resumeOnHeadsetReconnect: Boolean,
        val showQueueHistory: Boolean,
        val isCrossfadeEnabled: Boolean,
        val hiFiModeEnabled: Boolean,
        val crossfadeDuration: Int,
        val persistentShuffleEnabled: Boolean,
        val folderBackGestureNavigation: Boolean,
        val lyricsSourcePreference: LyricsSourcePreference,
        val autoScanLrcFiles: Boolean,
        val blockedDirectories: Set<String>,
        val hapticsEnabled: Boolean,
        val immersiveLyricsEnabled: Boolean,
        val immersiveLyricsTimeout: Long,
        val animatedLyricsBlurEnabled: Boolean,
        val animatedLyricsBlurStrength: Float,
        val disableBlurAllOver: Boolean,
        val showScrollbar: Boolean
    ) : SettingsUiUpdate
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val userPreferencesRepository: UserPreferencesRepository,
    private val aiPreferencesRepository: AiPreferencesRepository,
    private val themePreferencesRepository: ThemePreferencesRepository,
    private val colorSchemeProcessor: ColorSchemeProcessor,
    private val syncManager: SyncManager,
    private val aiClientFactory: AiClientFactory,
    private val geminiModelService: com.theveloper.pixelplay.data.ai.GeminiModelService,
    private val aiUsageDao: AiUsageDao,
    private val lyricsRepository: LyricsRepository,
    private val musicRepository: MusicRepository,
    private val backupManager: BackupManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    // AI Provider State
    val aiProvider: StateFlow<String> = aiPreferencesRepository.aiProvider
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "GEMINI")

    // Generic AI settings reactive to the selected provider
    val currentAiApiKey: StateFlow<String> = aiProvider
        .flatMapLatest { provider -> aiPreferencesRepository.getApiKey(AiProvider.fromString(provider)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val currentAiModel: StateFlow<String> = aiProvider
        .flatMapLatest { provider -> aiPreferencesRepository.getModel(AiProvider.fromString(provider)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val currentAiSystemPrompt: StateFlow<String> = aiProvider
        .flatMapLatest { provider -> aiPreferencesRepository.getSystemPrompt(AiProvider.fromString(provider)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AiPreferencesRepository.DEFAULT_SYSTEM_PROMPT)

    // Specific Provider StateFlows for UI Compatibility
    val geminiApiKey: StateFlow<String> = aiPreferencesRepository.geminiApiKey
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val geminiModel: StateFlow<String> = aiPreferencesRepository.geminiModel
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val geminiSystemPrompt: StateFlow<String> = aiPreferencesRepository.geminiSystemPrompt
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AiPreferencesRepository.DEFAULT_SYSTEM_PROMPT)

    val deepseekApiKey: StateFlow<String> = aiPreferencesRepository.deepseekApiKey
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val deepseekModel: StateFlow<String> = aiPreferencesRepository.deepseekModel
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val deepseekSystemPrompt: StateFlow<String> = aiPreferencesRepository.deepseekSystemPrompt
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AiPreferencesRepository.DEFAULT_DEEPSEEK_SYSTEM_PROMPT)

    val groqApiKey: StateFlow<String> = aiPreferencesRepository.groqApiKey
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val groqModel: StateFlow<String> = aiPreferencesRepository.groqModel
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val groqSystemPrompt: StateFlow<String> = aiPreferencesRepository.groqSystemPrompt
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AiPreferencesRepository.DEFAULT_GROQ_SYSTEM_PROMPT)

    val mistralApiKey: StateFlow<String> = aiPreferencesRepository.mistralApiKey
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val mistralModel: StateFlow<String> = aiPreferencesRepository.mistralModel
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val mistralSystemPrompt: StateFlow<String> = aiPreferencesRepository.mistralSystemPrompt
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AiPreferencesRepository.DEFAULT_MISTRAL_SYSTEM_PROMPT)

    val nvidiaApiKey: StateFlow<String> = aiPreferencesRepository.nvidiaApiKey
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val nvidiaModel: StateFlow<String> = aiPreferencesRepository.nvidiaModel
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val nvidiaSystemPrompt: StateFlow<String> = aiPreferencesRepository.nvidiaSystemPrompt
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AiPreferencesRepository.DEFAULT_NVIDIA_SYSTEM_PROMPT)

    val kimiApiKey: StateFlow<String> = aiPreferencesRepository.kimiApiKey
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val kimiModel: StateFlow<String> = aiPreferencesRepository.kimiModel
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val kimiSystemPrompt: StateFlow<String> = aiPreferencesRepository.kimiSystemPrompt
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AiPreferencesRepository.DEFAULT_KIMI_SYSTEM_PROMPT)

    val glmApiKey: StateFlow<String> = aiPreferencesRepository.glmApiKey
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val glmModel: StateFlow<String> = aiPreferencesRepository.glmModel
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val glmSystemPrompt: StateFlow<String> = aiPreferencesRepository.glmSystemPrompt
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AiPreferencesRepository.DEFAULT_GLM_SYSTEM_PROMPT)

    val openaiApiKey: StateFlow<String> = aiPreferencesRepository.openaiApiKey
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val openaiModel: StateFlow<String> = aiPreferencesRepository.openaiModel
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val openaiSystemPrompt: StateFlow<String> = aiPreferencesRepository.openaiSystemPrompt
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AiPreferencesRepository.DEFAULT_OPENAI_SYSTEM_PROMPT)

    val openrouterApiKey: StateFlow<String> = aiPreferencesRepository.openrouterApiKey
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val openrouterModel: StateFlow<String> = aiPreferencesRepository.openrouterModel
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val openrouterSystemPrompt: StateFlow<String> = aiPreferencesRepository.openrouterSystemPrompt
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AiPreferencesRepository.DEFAULT_OPENROUTER_SYSTEM_PROMPT)

    val ollamaApiKey: StateFlow<String> = aiPreferencesRepository.ollamaApiKey
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val ollamaModel: StateFlow<String> = aiPreferencesRepository.ollamaModel
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val ollamaSystemPrompt: StateFlow<String> = aiPreferencesRepository.ollamaSystemPrompt
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AiPreferencesRepository.DEFAULT_SYSTEM_PROMPT)

    val customApiKey: StateFlow<String> = aiPreferencesRepository.customApiKey
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val customModel: StateFlow<String> = aiPreferencesRepository.customModel
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val customSystemPrompt: StateFlow<String> = aiPreferencesRepository.customSystemPrompt
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AiPreferencesRepository.DEFAULT_SYSTEM_PROMPT)
    val customBaseUrl: StateFlow<String> = aiPreferencesRepository.customBaseUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val currentAiBaseUrl: StateFlow<String> = aiProvider
        .flatMapLatest { provider ->
            val p = AiProvider.fromString(provider)
            if (p.hasConfigurableUrl) aiPreferencesRepository.getBaseUrl(p)
            else flowOf("")
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    // Generation Parameters
    val aiTemperature: StateFlow<Float> = aiPreferencesRepository.aiTemperature
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.7f)
    val aiTopP: StateFlow<Float> = aiPreferencesRepository.aiTopP
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.95f)
    val aiTopK: StateFlow<Int> = aiPreferencesRepository.aiTopK
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 64)
    val aiMaxTokens: StateFlow<Int> = aiPreferencesRepository.aiMaxTokens
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 4096)
    val aiPresencePenalty: StateFlow<Float> = aiPreferencesRepository.aiPresencePenalty
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0f)
    val aiFrequencyPenalty: StateFlow<Float> = aiPreferencesRepository.aiFrequencyPenalty
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0f)

    // Song Data Configuration
    val aiSampleSize: StateFlow<Int> = aiPreferencesRepository.aiSampleSize
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 40)
    val aiDigestMode: StateFlow<String> = aiPreferencesRepository.aiDigestMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "safe")
    val aiIncludeExtendedFields: StateFlow<Boolean> = aiPreferencesRepository.aiIncludeExtendedFields
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun onAiApiKeyChange(apiKey: String) {
        viewModelScope.launch {
            val providerStr = aiProvider.value
            val provider = AiProvider.fromString(providerStr)
            aiPreferencesRepository.setApiKey(provider, apiKey)
            if (apiKey.isNotBlank()) fetchAvailableModels(apiKey, providerStr)
            else clearModelsState(providerStr)
        }
    }

    // Specific on-change methods for UI binding
    fun onGeminiApiKeyChange(apiKey: String) {
        viewModelScope.launch {
            aiPreferencesRepository.setApiKey(AiProvider.GEMINI, apiKey)
            if (apiKey.isNotBlank()) fetchAvailableModels(apiKey, "GEMINI")
            else clearModelsState("GEMINI")
        }
    }
    fun onDeepseekApiKeyChange(apiKey: String) {
        viewModelScope.launch {
            aiPreferencesRepository.setApiKey(AiProvider.DEEPSEEK, apiKey)
            if (apiKey.isNotBlank()) fetchAvailableModels(apiKey, "DEEPSEEK")
            else clearModelsState("DEEPSEEK")
        }
    }
    fun onGroqApiKeyChange(apiKey: String) {
        viewModelScope.launch {
            aiPreferencesRepository.setApiKey(AiProvider.GROQ, apiKey)
            if (apiKey.isNotBlank()) fetchAvailableModels(apiKey, "GROQ")
            else clearModelsState("GROQ")
        }
    }
    fun onMistralApiKeyChange(apiKey: String) {
        viewModelScope.launch {
            aiPreferencesRepository.setApiKey(AiProvider.MISTRAL, apiKey)
            if (apiKey.isNotBlank()) fetchAvailableModels(apiKey, "MISTRAL")
            else clearModelsState("MISTRAL")
        }
    }
    fun onNvidiaApiKeyChange(apiKey: String) {
        viewModelScope.launch {
            aiPreferencesRepository.setApiKey(AiProvider.NVIDIA, apiKey)
            if (apiKey.isNotBlank()) fetchAvailableModels(apiKey, "NVIDIA")
            else clearModelsState("NVIDIA")
        }
    }
    fun onKimiApiKeyChange(apiKey: String) {
        viewModelScope.launch {
            aiPreferencesRepository.setApiKey(AiProvider.KIMI, apiKey)
            if (apiKey.isNotBlank()) fetchAvailableModels(apiKey, "KIMI")
            else clearModelsState("KIMI")
        }
    }
    fun onGlmApiKeyChange(apiKey: String) {
        viewModelScope.launch {
            aiPreferencesRepository.setApiKey(AiProvider.GLM, apiKey)
            if (apiKey.isNotBlank()) fetchAvailableModels(apiKey, "GLM")
            else clearModelsState("GLM")
        }
    }
    fun onOpenAiApiKeyChange(apiKey: String) {
        viewModelScope.launch {
            aiPreferencesRepository.setApiKey(AiProvider.OPENAI, apiKey)
            if (apiKey.isNotBlank()) fetchAvailableModels(apiKey, "OPENAI")
            else clearModelsState("OPENAI")
        }
    }
    fun onOpenrouterApiKeyChange(apiKey: String) {
        viewModelScope.launch {
            aiPreferencesRepository.setApiKey(AiProvider.OPENROUTER, apiKey)
            if (apiKey.isNotBlank()) fetchAvailableModels(apiKey, "OPENROUTER")
            else clearModelsState("OPENROUTER")
        }
    }
    fun onOllamaApiKeyChange(apiKey: String) {
        viewModelScope.launch {
            aiPreferencesRepository.setApiKey(AiProvider.OLLAMA, apiKey)
            if (apiKey.isNotBlank()) fetchAvailableModels(apiKey, "OLLAMA")
            else clearModelsState("OLLAMA")
        }
    }
    fun onCustomApiKeyChange(apiKey: String) {
        viewModelScope.launch {
            aiPreferencesRepository.setApiKey(AiProvider.CUSTOM, apiKey)
            if (apiKey.isNotBlank()) fetchAvailableModels(apiKey, "CUSTOM")
            else clearModelsState("CUSTOM")
        }
    }
    fun onCustomBaseUrlChange(baseUrl: String) {
        viewModelScope.launch {
            aiPreferencesRepository.setBaseUrl(AiProvider.CUSTOM, baseUrl)
        }
    }

    fun onAiTemperatureChange(value: Float) {
        viewModelScope.launch { aiPreferencesRepository.setAiTemperature(value) }
    }
    fun onAiTopPChange(value: Float) {
        viewModelScope.launch { aiPreferencesRepository.setAiTopP(value) }
    }
    fun onAiTopKChange(value: Int) {
        viewModelScope.launch { aiPreferencesRepository.setAiTopK(value) }
    }
    fun onAiMaxTokensChange(value: Int) {
        viewModelScope.launch { aiPreferencesRepository.setAiMaxTokens(value) }
    }
    fun onAiPresencePenaltyChange(value: Float) {
        viewModelScope.launch { aiPreferencesRepository.setAiPresencePenalty(value) }
    }
    fun onAiFrequencyPenaltyChange(value: Float) {
        viewModelScope.launch { aiPreferencesRepository.setAiFrequencyPenalty(value) }
    }
    fun onAiSampleSizeChange(value: Int) {
        viewModelScope.launch { aiPreferencesRepository.setAiSampleSize(value) }
    }
    fun onAiDigestModeChange(mode: String) {
        viewModelScope.launch { aiPreferencesRepository.setAiDigestMode(mode) }
    }
    fun onAiIncludeExtendedFieldsChange(enabled: Boolean) {
        viewModelScope.launch { aiPreferencesRepository.setAiIncludeExtendedFields(enabled) }
    }

    fun onAiModelChange(model: String) {
        viewModelScope.launch {
            val provider = AiProvider.fromString(aiProvider.value)
            aiPreferencesRepository.setModel(provider, model)
        }
    }

    fun onGeminiModelChange(model: String) = viewModelScope.launch { aiPreferencesRepository.setModel(AiProvider.GEMINI, model) }
    fun onDeepseekModelChange(model: String) = viewModelScope.launch { aiPreferencesRepository.setModel(AiProvider.DEEPSEEK, model) }
    fun onGroqModelChange(model: String) = viewModelScope.launch { aiPreferencesRepository.setModel(AiProvider.GROQ, model) }
    fun onMistralModelChange(model: String) = viewModelScope.launch { aiPreferencesRepository.setModel(AiProvider.MISTRAL, model) }
    fun onNvidiaModelChange(model: String) = viewModelScope.launch { aiPreferencesRepository.setModel(AiProvider.NVIDIA, model) }
    fun onKimiModelChange(model: String) = viewModelScope.launch { aiPreferencesRepository.setModel(AiProvider.KIMI, model) }
    fun onGlmModelChange(model: String) = viewModelScope.launch { aiPreferencesRepository.setModel(AiProvider.GLM, model) }
    fun onOpenAiModelChange(model: String) = viewModelScope.launch { aiPreferencesRepository.setModel(AiProvider.OPENAI, model) }
    fun onOpenrouterModelChange(model: String) = viewModelScope.launch { aiPreferencesRepository.setModel(AiProvider.OPENROUTER, model) }
    fun onOllamaModelChange(model: String) = viewModelScope.launch { aiPreferencesRepository.setModel(AiProvider.OLLAMA, model) }
    fun onCustomModelChange(model: String) = viewModelScope.launch { aiPreferencesRepository.setModel(AiProvider.CUSTOM, model) }

    fun onAiSystemPromptChange(prompt: String) {
        viewModelScope.launch {
            val provider = AiProvider.fromString(aiProvider.value)
            aiPreferencesRepository.setSystemPrompt(provider, prompt)
        }
    }

    fun onGeminiSystemPromptChange(prompt: String) = viewModelScope.launch { aiPreferencesRepository.setSystemPrompt(AiProvider.GEMINI, prompt) }
    fun onDeepseekSystemPromptChange(prompt: String) = viewModelScope.launch { aiPreferencesRepository.setSystemPrompt(AiProvider.DEEPSEEK, prompt) }
    fun onGroqSystemPromptChange(prompt: String) = viewModelScope.launch { aiPreferencesRepository.setSystemPrompt(AiProvider.GROQ, prompt) }
    fun onMistralSystemPromptChange(prompt: String) = viewModelScope.launch { aiPreferencesRepository.setSystemPrompt(AiProvider.MISTRAL, prompt) }
    fun onNvidiaSystemPromptChange(prompt: String) = viewModelScope.launch { aiPreferencesRepository.setSystemPrompt(AiProvider.NVIDIA, prompt) }
    fun onKimiSystemPromptChange(prompt: String) = viewModelScope.launch { aiPreferencesRepository.setSystemPrompt(AiProvider.KIMI, prompt) }
    fun onGlmSystemPromptChange(prompt: String) = viewModelScope.launch { aiPreferencesRepository.setSystemPrompt(AiProvider.GLM, prompt) }
    fun onOpenAiSystemPromptChange(prompt: String) = viewModelScope.launch { aiPreferencesRepository.setSystemPrompt(AiProvider.OPENAI, prompt) }
    fun onOpenrouterSystemPromptChange(prompt: String) = viewModelScope.launch { aiPreferencesRepository.setSystemPrompt(AiProvider.OPENROUTER, prompt) }
    fun onOllamaSystemPromptChange(prompt: String) = viewModelScope.launch { aiPreferencesRepository.setSystemPrompt(AiProvider.OLLAMA, prompt) }
    fun onCustomSystemPromptChange(prompt: String) = viewModelScope.launch { aiPreferencesRepository.setSystemPrompt(AiProvider.CUSTOM, prompt) }

    fun resetAiSystemPrompt() {
        viewModelScope.launch {
            val provider = AiProvider.fromString(aiProvider.value)
            aiPreferencesRepository.resetSystemPrompt(provider)
        }
    }

    fun resetGeminiSystemPrompt() = viewModelScope.launch { aiPreferencesRepository.resetSystemPrompt(AiProvider.GEMINI) }
    fun resetDeepseekSystemPrompt() = viewModelScope.launch { aiPreferencesRepository.resetSystemPrompt(AiProvider.DEEPSEEK) }
    fun resetGroqSystemPrompt() = viewModelScope.launch { aiPreferencesRepository.resetSystemPrompt(AiProvider.GROQ) }
    fun resetMistralSystemPrompt() = viewModelScope.launch { aiPreferencesRepository.resetSystemPrompt(AiProvider.MISTRAL) }
    fun resetNvidiaSystemPrompt() = viewModelScope.launch { aiPreferencesRepository.resetSystemPrompt(AiProvider.NVIDIA) }
    fun resetKimiSystemPrompt() = viewModelScope.launch { aiPreferencesRepository.resetSystemPrompt(AiProvider.KIMI) }
    fun resetGlmSystemPrompt() = viewModelScope.launch { aiPreferencesRepository.resetSystemPrompt(AiProvider.GLM) }
    fun resetOpenAiSystemPrompt() = viewModelScope.launch { aiPreferencesRepository.resetSystemPrompt(AiProvider.OPENAI) }
    fun resetOpenrouterSystemPrompt() = viewModelScope.launch { aiPreferencesRepository.resetSystemPrompt(AiProvider.OPENROUTER) }
    fun resetOllamaSystemPrompt() = viewModelScope.launch { aiPreferencesRepository.resetSystemPrompt(AiProvider.OLLAMA) }
    fun resetCustomSystemPrompt() = viewModelScope.launch { aiPreferencesRepository.resetSystemPrompt(AiProvider.CUSTOM) }

    fun clearAiUsageData() {
        viewModelScope.launch {
            aiUsageDao.clearUsage()
        }
    }

    val isSafeTokenLimitEnabled: StateFlow<Boolean> = aiPreferencesRepository.isSafeTokenLimitEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val recentAiUsage: StateFlow<List<AiUsageEntity>> = aiUsageDao.getRecentUsages(20)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val totalPromptTokens: StateFlow<Int> = aiUsageDao.getTotalPromptTokens()
        .map { it ?: 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val totalOutputTokens: StateFlow<Int> = aiUsageDao.getTotalOutputTokens()
        .map { it ?: 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val totalThoughtTokens: StateFlow<Int> = aiUsageDao.getTotalThoughtTokens()
        .map { it ?: 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val fileExplorerStateHolder = FileExplorerStateHolder(userPreferencesRepository, viewModelScope, context)

    val currentPath = fileExplorerStateHolder.currentPath
    val currentDirectoryChildren = fileExplorerStateHolder.currentDirectoryChildren
    val flatDirectoryChildren = fileExplorerStateHolder.flatDirectoryChildren
    val blockedDirectories = fileExplorerStateHolder.blockedDirectories
    val availableStorages = fileExplorerStateHolder.availableStorages
    val selectedStorageIndex = fileExplorerStateHolder.selectedStorageIndex
    val isLoadingDirectories = fileExplorerStateHolder.isLoading
    val isExplorerPriming = fileExplorerStateHolder.isPrimingExplorer
    val isExplorerReady = fileExplorerStateHolder.isExplorerReady
    val isCurrentDirectoryResolved = fileExplorerStateHolder.isCurrentDirectoryResolved
    private var hasPendingDirectoryRuleChanges = false
    private var latestDirectoryRuleUpdateJob: Job? = null

    val isSyncing: StateFlow<Boolean> = syncManager.isSyncing
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    val syncProgress: StateFlow<SyncProgress> = syncManager.syncProgress
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = SyncProgress()
        )

    private val _dataTransferEvents = MutableSharedFlow<String>()
    val dataTransferEvents: SharedFlow<String> = _dataTransferEvents.asSharedFlow()

    private val _dataTransferProgress = MutableStateFlow<BackupTransferProgressUpdate?>(null)
    val dataTransferProgress: StateFlow<BackupTransferProgressUpdate?> = _dataTransferProgress.asStateFlow()

    init {
        viewModelScope.launch {
            backupManager.getBackupHistory().collect { history ->
                _uiState.update { it.copy(backupHistory = history) }
            }
        }

        viewModelScope.launch {
            userPreferencesRepository.collagePatternFlow.collect { pattern ->
                _uiState.update { it.copy(collagePattern = pattern) }
            }
        }

        viewModelScope.launch {
            userPreferencesRepository.collageAutoRotateFlow.collect { autoRotate ->
                _uiState.update { it.copy(collageAutoRotate = autoRotate) }
            }
        }

        // One-time device capability check — result is cached inside HiFiCapabilityChecker
        _uiState.update {
            it.copy(
                hiFiModeDeviceSupported = HiFiCapabilityChecker.isSupported(),
                appLanguageTag = AppLocaleManager.currentLanguageTag(context)
            )
        }

        // Consolidated collectors using combine() to reduce coroutine overhead
        // Instead of 20 separate coroutines, we use 2 combined flows
        
        // Group 1: Core UI settings (theme, navigation, appearance)
        viewModelScope.launch {
            combine<Any?, SettingsUiUpdate.Group1>(
                userPreferencesRepository.appRebrandDialogShownFlow,
                themePreferencesRepository.appThemeModeFlow,
                themePreferencesRepository.playerThemePreferenceFlow,
                themePreferencesRepository.albumArtPaletteStyleFlow,
                themePreferencesRepository.albumArtColorAccuracyFlow,
                userPreferencesRepository.mockGenresEnabledFlow,
                userPreferencesRepository.navBarCornerRadiusFlow,
                userPreferencesRepository.navBarStyleFlow,
                userPreferencesRepository.navBarCompactModeFlow,
                userPreferencesRepository.libraryNavigationModeFlow,
                userPreferencesRepository.carouselStyleFlow,
                userPreferencesRepository.launchTabFlow,
                userPreferencesRepository.showPlayerFileInfoFlow
            ) { values ->
                SettingsUiUpdate.Group1(
                    appRebrandDialogShown = values[0] as Boolean,
                    appThemeMode = values[1] as String,
                    playerThemePreference = values[2] as String,
                    albumArtPaletteStyle = values[3] as AlbumArtPaletteStyle,
                    albumArtColorAccuracy = values[4] as Int,
                    mockGenresEnabled = values[5] as Boolean,
                    navBarCornerRadius = values[6] as Int,
                    navBarStyle = values[7] as String,
                    navBarCompactMode = values[8] as Boolean,
                    libraryNavigationMode = values[9] as String,
                    carouselStyle = values[10] as String,
                    launchTab = values[11] as String,
                    showPlayerFileInfo = values[12] as Boolean
                )
            }.collect { update ->
                _uiState.update { state ->
                    state.copy(
                        appRebrandDialogShown = update.appRebrandDialogShown,
                        appThemeMode = update.appThemeMode,
                        playerThemePreference = update.playerThemePreference,
                        albumArtPaletteStyle = update.albumArtPaletteStyle,
                        albumArtColorAccuracy = update.albumArtColorAccuracy,
                        mockGenresEnabled = update.mockGenresEnabled,
                        navBarCornerRadius = update.navBarCornerRadius,
                        navBarStyle = update.navBarStyle,
                        navBarCompactMode = update.navBarCompactMode,
                        libraryNavigationMode = update.libraryNavigationMode,
                        carouselStyle = update.carouselStyle,
                        launchTab = update.launchTab,
                        showPlayerFileInfo = update.showPlayerFileInfo
                    )
                }
            }
        }
        
        // Group 2: Playback and system settings
        viewModelScope.launch {
            combine<Any?, SettingsUiUpdate.Group2>(
                userPreferencesRepository.keepPlayingInBackgroundFlow,
                userPreferencesRepository.disableCastAutoplayFlow,
                userPreferencesRepository.pauseOnVolumeZeroFlow,
                userPreferencesRepository.resumeOnHeadsetReconnectFlow,
                userPreferencesRepository.showQueueHistoryFlow,
                userPreferencesRepository.isCrossfadeEnabledFlow,
                userPreferencesRepository.hiFiModeEnabledFlow,
                userPreferencesRepository.crossfadeDurationFlow,
                userPreferencesRepository.persistentShuffleEnabledFlow,
                userPreferencesRepository.folderBackGestureNavigationFlow,
                userPreferencesRepository.lyricsSourcePreferenceFlow,
                userPreferencesRepository.autoScanLrcFilesFlow,
                userPreferencesRepository.blockedDirectoriesFlow,
                userPreferencesRepository.hapticsEnabledFlow,
                userPreferencesRepository.immersiveLyricsEnabledFlow,
                userPreferencesRepository.immersiveLyricsTimeoutFlow,
                userPreferencesRepository.animatedLyricsBlurEnabledFlow,
                userPreferencesRepository.animatedLyricsBlurStrengthFlow,
                userPreferencesRepository.disableBlurAllOverFlow,
                userPreferencesRepository.showScrollbarFlow
            ) { values ->
                SettingsUiUpdate.Group2(
                    keepPlayingInBackground = values[0] as Boolean,
                    disableCastAutoplay = values[1] as Boolean,
                    pauseOnVolumeZero = values[2] as Boolean,
                    resumeOnHeadsetReconnect = values[3] as Boolean,
                    showQueueHistory = values[4] as Boolean,
                    isCrossfadeEnabled = values[5] as Boolean,
                    hiFiModeEnabled = values[6] as Boolean,
                    crossfadeDuration = values[7] as Int,
                    persistentShuffleEnabled = values[8] as Boolean,
                    folderBackGestureNavigation = values[9] as Boolean,
                    lyricsSourcePreference = values[10] as LyricsSourcePreference,
                    autoScanLrcFiles = values[11] as Boolean,
                    blockedDirectories = @Suppress("UNCHECKED_CAST") (values[12] as Set<String>),
                    hapticsEnabled = values[13] as Boolean,
                    immersiveLyricsEnabled = values[14] as Boolean,
                    immersiveLyricsTimeout = values[15] as Long,
                    animatedLyricsBlurEnabled = values[16] as Boolean,
                    animatedLyricsBlurStrength = values[17] as Float,
                    disableBlurAllOver = values[18] as Boolean,
                    showScrollbar = values[19] as Boolean
                )
            }.collect { update ->
                _uiState.update { state ->
                    state.copy(
                        keepPlayingInBackground = update.keepPlayingInBackground,
                        disableCastAutoplay = update.disableCastAutoplay,
                        pauseOnVolumeZero = update.pauseOnVolumeZero,
                        resumeOnHeadsetReconnect = update.resumeOnHeadsetReconnect,
                        showQueueHistory = update.showQueueHistory,
                        isCrossfadeEnabled = update.isCrossfadeEnabled,
                        hiFiModeEnabled = update.hiFiModeEnabled,
                        crossfadeDuration = update.crossfadeDuration,
                        persistentShuffleEnabled = update.persistentShuffleEnabled,
                        folderBackGestureNavigation = update.folderBackGestureNavigation,
                        lyricsSourcePreference = update.lyricsSourcePreference,
                        autoScanLrcFiles = update.autoScanLrcFiles,
                        blockedDirectories = update.blockedDirectories,
                        hapticsEnabled = update.hapticsEnabled,
                        immersiveLyricsEnabled = update.immersiveLyricsEnabled,
                        immersiveLyricsTimeout = update.immersiveLyricsTimeout,
                        animatedLyricsBlurEnabled = update.animatedLyricsBlurEnabled,
                        animatedLyricsBlurStrength = update.animatedLyricsBlurStrength,
                        disableBlurAllOver = update.disableBlurAllOver,
                        showScrollbar = update.showScrollbar
                    )
                }
            }
        }
        
        // Group 3: Remaining individual collectors (loading state, tweaks)
        viewModelScope.launch {
            userPreferencesRepository.fullPlayerLoadingTweaksFlow.collect { tweaks ->
                _uiState.update { it.copy(fullPlayerLoadingTweaks = tweaks) }
            }
        }

        viewModelScope.launch {
            userPreferencesRepository.useAnimatedLyricsFlow.collect { enabled ->
                _uiState.update { it.copy(useAnimatedLyrics = enabled) }
            }
        }

        viewModelScope.launch {
            userPreferencesRepository.backupInfoDismissedFlow.collect { dismissed ->
                _uiState.update { it.copy(backupInfoDismissed = dismissed) }
            }
        }

        viewModelScope.launch {
            userPreferencesRepository.beta05CleanInstallDisclaimerDismissedFlow.collect { dismissed ->
                _uiState.update { it.copy(beta05CleanInstallDisclaimerDismissed = dismissed) }
            }
        }

        viewModelScope.launch {
            fileExplorerStateHolder.isLoading.collect { loading ->
                _uiState.update { it.copy(isLoadingDirectories = loading) }
            }
        }

        // Beta Features Collectors
        viewModelScope.launch {
            userPreferencesRepository.albumArtQualityFlow.collect { quality ->
                _uiState.update { it.copy(albumArtQuality = quality) }
            }
        }

        viewModelScope.launch {
            userPreferencesRepository.albumArtCacheLimitMbFlow.collect { limitMb ->
                _uiState.update { it.copy(albumArtCacheLimitMb = limitMb) }
            }
        }

        viewModelScope.launch {
            userPreferencesRepository.tapBackgroundClosesPlayerFlow.collect { enabled ->
                _uiState.update { it.copy(tapBackgroundClosesPlayer = enabled) }
            }
        }

        viewModelScope.launch {
            userPreferencesRepository.minSongDurationFlow.collect { duration ->
                _uiState.update { it.copy(minSongDuration = duration) }
            }
        }

        viewModelScope.launch {
            userPreferencesRepository.minTracksPerAlbumFlow.collect { minTracks ->
                _uiState.update { it.copy(minTracksPerAlbum = minTracks) }
            }
        }

        viewModelScope.launch {
            userPreferencesRepository.replayGainEnabledFlow.collect { enabled ->
                _uiState.update { it.copy(replayGainEnabled = enabled) }
            }
        }

        viewModelScope.launch {
            userPreferencesRepository.replayGainUseAlbumGainFlow.collect { useAlbum ->
                _uiState.update { it.copy(replayGainUseAlbumGain = useAlbum) }
            }
        }

        viewModelScope.launch {
            aiPreferencesRepository.isSafeTokenLimitEnabled.collect { enabled ->
                _uiState.update { it.copy(isSafeTokenLimitEnabled = enabled) }
            }
        }
    }

    fun setAppRebrandDialogShown(wasShown: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setAppRebrandDialogShown(wasShown)
        }
    }

    fun setBeta05CleanInstallDisclaimerDismissed(dismissed: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setBeta05CleanInstallDisclaimerDismissed(dismissed)
        }
    }

    fun toggleDirectoryAllowed(file: File) {
        hasPendingDirectoryRuleChanges = true
        latestDirectoryRuleUpdateJob = viewModelScope.launch {
            fileExplorerStateHolder.toggleDirectoryAllowed(file)
        }
    }

    fun applyPendingDirectoryRuleChanges() {
        if (!hasPendingDirectoryRuleChanges) return
        hasPendingDirectoryRuleChanges = false
        viewModelScope.launch {
            latestDirectoryRuleUpdateJob?.join()
            syncManager.forceRefresh()
        }
    }

    fun loadDirectory(file: File) {
        fileExplorerStateHolder.loadDirectory(file)
    }

    fun primeExplorer() {
        fileExplorerStateHolder.primeExplorerRoot()
    }

    fun openExplorer() {
        fileExplorerStateHolder.openExplorerRoot()
    }

    fun navigateUp() {
        fileExplorerStateHolder.navigateUp()
    }

    fun refreshExplorer() {
        fileExplorerStateHolder.refreshCurrentDirectory()
    }

    fun selectStorage(index: Int) {
        fileExplorerStateHolder.selectStorage(index)
    }

    fun refreshAvailableStorages() {
        fileExplorerStateHolder.refreshAvailableStorages()
    }

    fun isAtRoot(): Boolean = fileExplorerStateHolder.isAtRoot()

    fun explorerRoot(): File = fileExplorerStateHolder.rootDirectory()

    // Método para guardar la preferencia de tema del reproductor
    fun setPlayerThemePreference(preference: String) {
        viewModelScope.launch {
            themePreferencesRepository.setPlayerThemePreference(preference)
        }
    }

    fun setAlbumArtPaletteStyle(style: AlbumArtPaletteStyle) {
        viewModelScope.launch {
            themePreferencesRepository.setAlbumArtPaletteStyle(style)
        }
    }

    fun setAlbumArtPaletteSettings(
        style: AlbumArtPaletteStyle,
        accuracyLevel: Int
    ) {
        viewModelScope.launch {
            themePreferencesRepository.setAlbumArtPaletteSettings(style, accuracyLevel)
        }
    }

    suspend fun getAlbumArtPalettePreview(
        uriString: String,
        style: AlbumArtPaletteStyle,
        accuracyLevel: Int
    ): ColorSchemePair? {
        return colorSchemeProcessor.getPreviewColorScheme(
            albumArtUri = uriString,
            paletteStyle = style,
            colorAccuracyLevel = accuracyLevel
        )
    }

    fun setCollagePattern(pattern: CollagePattern) {
        viewModelScope.launch {
            userPreferencesRepository.setCollagePattern(pattern)
        }
    }

    fun setCollageAutoRotate(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setCollageAutoRotate(enabled)
        }
    }

    fun setAppThemeMode(mode: String) {
        viewModelScope.launch {
            themePreferencesRepository.setAppThemeMode(mode)
        }
    }

    fun setAppLanguage(languageTag: String) {
        val normalized = AppLanguage.normalize(languageTag)
        AppLocaleManager.applyLanguage(context, normalized)
        _uiState.update { it.copy(appLanguageTag = normalized) }
    }

    fun setNavBarStyle(style: String) {
        viewModelScope.launch {
            userPreferencesRepository.setNavBarStyle(style)
        }
    }

    fun setNavBarCompactMode(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setNavBarCompactMode(enabled)
        }
    }

    fun setLibraryNavigationMode(mode: String) {
        viewModelScope.launch {
            userPreferencesRepository.setLibraryNavigationMode(mode)
        }
    }

    fun setCarouselStyle(style: String) {
        viewModelScope.launch {
            userPreferencesRepository.setCarouselStyle(style)
        }
    }

    fun setShowPlayerFileInfo(show: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setShowPlayerFileInfo(show)
        }
    }

    fun setShowScrollbar(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setShowScrollbar(enabled)
        }
    }

    fun setLaunchTab(tab: String) {
        viewModelScope.launch {
            userPreferencesRepository.setLaunchTab(tab)
        }
    }

    fun setKeepPlayingInBackground(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setKeepPlayingInBackground(enabled)
        }
    }

    fun setDisableCastAutoplay(disabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setDisableCastAutoplay(disabled)
        }
    }

    fun setPauseOnVolumeZero(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setPauseOnVolumeZero(enabled)
        }
    }

    fun setResumeOnHeadsetReconnect(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setResumeOnHeadsetReconnect(enabled)
        }
    }

    fun setHiFiModeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setHiFiModeEnabled(enabled)
        }
    }

    fun setShowQueueHistory(show: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setShowQueueHistory(show)
        }
    }

    fun setCrossfadeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setCrossfadeEnabled(enabled)
        }
    }

    fun setCrossfadeDuration(duration: Int) {
        viewModelScope.launch {
            userPreferencesRepository.setCrossfadeDuration(duration)
        }
    }

    fun setPersistentShuffleEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setPersistentShuffleEnabled(enabled)
        }
    }

    fun setFolderBackGestureNavigation(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setFolderBackGestureNavigation(enabled)
        }
    }

    fun setLyricsSourcePreference(preference: LyricsSourcePreference) {
        viewModelScope.launch {
            userPreferencesRepository.setLyricsSourcePreference(preference)
        }
    }

    fun setAutoScanLrcFiles(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setAutoScanLrcFiles(enabled)
        }
    }

    fun setDelayAllFullPlayerContent(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setDelayAllFullPlayerContent(enabled)
        }
    }

    fun setDelayAlbumCarousel(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setDelayAlbumCarousel(enabled)
        }
    }

    fun setDelaySongMetadata(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setDelaySongMetadata(enabled)
        }
    }

    fun setDelayProgressBar(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setDelayProgressBar(enabled)
        }
    }

    fun setDelayControls(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setDelayControls(enabled)
        }
    }

    fun setFullPlayerPlaceholders(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setFullPlayerPlaceholders(enabled)
            if (!enabled) {
                userPreferencesRepository.setTransparentPlaceholders(false)
            }
        }
    }

    fun setTransparentPlaceholders(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setTransparentPlaceholders(enabled)
        }
    }

    fun setFullPlayerPlaceholdersOnClose(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setFullPlayerPlaceholdersOnClose(enabled)
        }
    }

    fun setFullPlayerSwitchOnDragRelease(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setFullPlayerSwitchOnDragRelease(enabled)
        }
    }

    fun setFullPlayerAppearThreshold(thresholdPercent: Int) {
        viewModelScope.launch {
            userPreferencesRepository.setFullPlayerAppearThreshold(thresholdPercent)
        }
    }

    fun setFullPlayerCloseThreshold(thresholdPercent: Int) {
        viewModelScope.launch {
            userPreferencesRepository.setFullPlayerCloseThreshold(thresholdPercent)
        }
    }

    fun setUseAnimatedLyrics(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setUseAnimatedLyrics(enabled)
        }
    }

    fun setAnimatedLyricsBlurEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setAnimatedLyricsBlurEnabled(enabled)
        }
    }

    fun setAnimatedLyricsBlurStrength(strength: Float) {
        viewModelScope.launch {
            userPreferencesRepository.setAnimatedLyricsBlurStrength(strength)
        }
    }

    fun setDisableBlurAllOver(disabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setDisableBlurAllOver(disabled)
        }
    }

    fun refreshLibrary() {
        viewModelScope.launch {
            if (isSyncing.value) return@launch
            syncManager.forceRefresh()
        }
    }

    fun setSafeTokenLimitEnabled(enabled: Boolean) {
        viewModelScope.launch {
            aiPreferencesRepository.setSafeTokenLimitEnabled(enabled)
        }
    }



    /**
     * Performs a full library rescan - rescans all files from scratch.
     * Use when songs are missing or metadata is incorrect.
     */
    fun fullSyncLibrary() {
        viewModelScope.launch {
            if (isSyncing.value) return@launch
            syncManager.fullSync()
        }
    }

    fun setMinSongDuration(durationMs: Int) {
        viewModelScope.launch {
            if (durationMs == _uiState.value.minSongDuration) return@launch
            userPreferencesRepository.setMinSongDuration(durationMs)
            // Trigger a library rescan so the change takes effect in the database
            syncManager.fullSync()
        }
    }

    fun setMinTracksPerAlbum(minTracks: Int) {
        viewModelScope.launch {
            userPreferencesRepository.setMinTracksPerAlbum(minTracks)
        }
    }

    fun setReplayGainEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setReplayGainEnabled(enabled)
        }
    }

    fun setReplayGainUseAlbumGain(useAlbumGain: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setReplayGainUseAlbumGain(useAlbumGain)
        }
    }

    fun setImmersiveLyricsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setImmersiveLyricsEnabled(enabled)
        }
    }

    fun setImmersiveLyricsTimeout(timeout: Long) {
        viewModelScope.launch {
            userPreferencesRepository.setImmersiveLyricsTimeout(timeout)
        }
    }

    /**
     * Completely rebuilds the database from scratch.
     * Clears all data including user edits (lyrics, favorites) and rescans.
     * Use when database is corrupted or as a last resort.
     */
    fun rebuildDatabase() {
        viewModelScope.launch {
            if (isSyncing.value) return@launch
            syncManager.rebuildDatabase()
        }
    }

    fun onAiProviderChange(provider: String) {
        viewModelScope.launch {
            aiPreferencesRepository.setAiProvider(provider)

            // Clear existing models immediately to show loading state
            _uiState.update {
                it.copy(
                    availableModels = emptyList(),
                    modelsFetchError = null,
                    isLoadingModels = false
                )
            }

            // Small delay to let the provider preference propagate to StateFlows
            delay(100)

            // Fetch models for the newly selected provider if we have an API key
            val apiKey = aiPreferencesRepository.getApiKey(AiProvider.fromString(provider)).first()

            if (apiKey.isNotBlank()) {
                fetchAvailableModels(apiKey, provider)
            }
        }
    }

    fun loadModelsForCurrentProvider() {
        viewModelScope.launch {
            if (_uiState.value.isLoadingModels) return@launch
            if (_uiState.value.availableModels.isNotEmpty()) return@launch
            
            val provider = aiProvider.value
            val apiKey = aiPreferencesRepository.getApiKey(AiProvider.fromString(provider)).first()
            
            if (apiKey.isNotBlank()) {
                fetchAvailableModels(apiKey, provider)
            }
        }
    }

    private fun clearModelsState(provider: String) {
        _uiState.update {
            it.copy(
                availableModels = emptyList(),
                modelsFetchError = null
            )
        }
        viewModelScope.launch {
            aiPreferencesRepository.setModel(AiProvider.fromString(provider), "")
        }
    }

    private fun fetchAvailableModels(apiKey: String, providerName: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingModels = true, modelsFetchError = null) }
            try {
                val provider = AiProvider.fromString(providerName)
                val models = if (provider == AiProvider.GEMINI) {
                    geminiModelService.fetchAvailableModels(apiKey).getOrThrow()
                } else {
                    val baseUrl = if (provider.hasConfigurableUrl)
                        aiPreferencesRepository.getBaseUrl(provider).first()
                    else ""
                    val aiClient = if (provider.hasConfigurableUrl)
                        aiClientFactory.createClientWithUrl(provider, apiKey, baseUrl)
                    else
                        aiClientFactory.createClient(provider, apiKey)
                    aiClient.getAvailableModels(apiKey)
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .distinct()
                        .map { com.theveloper.pixelplay.data.ai.GeminiModel(it, formatModelDisplayName(it)) }
                }
                
                _uiState.update { 
                    it.copy(
                        availableModels = models, 
                        isLoadingModels = false,
                        modelsFetchError = null
                    ) 
                }

                // Auto-select first model if nothing is selected yet
                val currentModel = aiPreferencesRepository.getModel(provider).first()
                val availableModelNames = models.map { it.name }.toSet()
                if (models.isNotEmpty() && (currentModel.isBlank() || currentModel !in availableModelNames)) {
                    val firstModel = models.first().name
                    aiPreferencesRepository.setModel(provider, firstModel)
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoadingModels = false,
                        modelsFetchError = e.message ?: context.getString(R.string.settings_models_fetch_failed),
                    )
                }
            }
        }
    }

    private fun formatModelDisplayName(modelName: String): String {
        return modelName
            .removePrefix("models/")
            .replace('-', ' ')
            .replace('_', ' ')
            .split(' ')
            .joinToString(" ") { token ->
                token.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
    }


    fun setNavBarCornerRadius(radius: Int) {
        viewModelScope.launch { userPreferencesRepository.setNavBarCornerRadius(radius) }
    }
    /**
     * Triggers a test crash to verify the crash handler is working correctly.
     * This should only be used for testing in Developer Options.
     */
    fun triggerTestCrash() {
        throw RuntimeException(context.getString(R.string.settings_dev_test_crash_message))
    }

    fun resetSetupFlow() {
        viewModelScope.launch {
            userPreferencesRepository.setInitialSetupDone(false)
        }
    }

    // ===== Developer Options =====

    val albumArtQuality: StateFlow<AlbumArtQuality> = userPreferencesRepository.albumArtQualityFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AlbumArtQuality.MEDIUM)

    val useSmoothCorners: StateFlow<Boolean> = userPreferencesRepository.useSmoothCornersFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val tapBackgroundClosesPlayer: StateFlow<Boolean> = userPreferencesRepository.tapBackgroundClosesPlayerFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun setAlbumArtQuality(quality: AlbumArtQuality) {
        viewModelScope.launch {
            userPreferencesRepository.setAlbumArtQuality(quality)
        }
    }

    fun setAlbumArtCacheLimitMb(limitMb: Int) {
        viewModelScope.launch {
            userPreferencesRepository.setAlbumArtCacheLimitMb(limitMb)
            com.theveloper.pixelplay.utils.AlbumArtCacheManager.configuredCacheLimitMb = limitMb.toLong()
        }
    }

    fun setUseSmoothCorners(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setUseSmoothCorners(enabled)
        }
    }

    fun setTapBackgroundClosesPlayer(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setTapBackgroundClosesPlayer(enabled)
        }
    }

    fun setHapticsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setHapticsEnabled(enabled)
        }
    }

    fun setBackupInfoDismissed(dismissed: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setBackupInfoDismissed(dismissed)
        }
    }

    fun exportAppData(uri: Uri, sections: Set<BackupSection>) {
        if (sections.isEmpty() || _uiState.value.isDataTransferInProgress) return
        viewModelScope.launch {
            _uiState.update { it.copy(isDataTransferInProgress = true) }
            _dataTransferProgress.value = BackupTransferProgressUpdate(
                operation = BackupOperationType.EXPORT,
                step = 0,
                totalSteps = 1,
                title = context.getString(R.string.settings_backup_progress_preparing_backup),
                detail = context.getString(R.string.settings_backup_progress_starting_backup_task),
            )
            val result = backupManager.export(uri, sections) { progress ->
                _dataTransferProgress.value = progress
            }
            result.fold(
                onSuccess = { _dataTransferEvents.emit(context.getString(R.string.settings_data_exported_successfully)) },
                onFailure = {
                    _dataTransferEvents.emit(
                        context.getString(
                            R.string.settings_export_failed_format,
                            it.localizedMessage ?: context.getString(R.string.common_error_unknown),
                        ),
                    )
                },
            )
            delay(300)
            _uiState.update { it.copy(isDataTransferInProgress = false) }
            _dataTransferProgress.value = null
        }
    }

    fun inspectBackupFile(uri: Uri) {
        if (_uiState.value.isInspectingBackup) return
        viewModelScope.launch {
            _uiState.update { it.copy(isInspectingBackup = true, backupValidationErrors = emptyList(), restorePlan = null) }
            val result = backupManager.inspectBackup(uri)
            result.fold(
                onSuccess = { plan ->
                    _uiState.update { it.copy(restorePlan = plan, isInspectingBackup = false) }
                },
                onFailure = { error ->
                    _dataTransferEvents.emit(
                        context.getString(
                            R.string.settings_backup_invalid_format,
                            error.localizedMessage ?: context.getString(R.string.common_error_unknown),
                        ),
                    )
                    _uiState.update { it.copy(isInspectingBackup = false) }
                }
            )
        }
    }

    fun updateRestorePlanSelection(selectedModules: Set<BackupSection>) {
        _uiState.update { state ->
            state.restorePlan?.let { plan ->
                state.copy(restorePlan = plan.copy(selectedModules = selectedModules))
            } ?: state
        }
    }

    fun restoreFromPlan(uri: Uri) {
        val plan = _uiState.value.restorePlan ?: return
        if (plan.selectedModules.isEmpty() || _uiState.value.isDataTransferInProgress) return
        viewModelScope.launch {
            _uiState.update { it.copy(isDataTransferInProgress = true) }
            _dataTransferProgress.value = BackupTransferProgressUpdate(
                operation = BackupOperationType.IMPORT,
                step = 0,
                totalSteps = 1,
                title = context.getString(R.string.settings_backup_progress_preparing_restore),
                detail = context.getString(R.string.settings_backup_progress_starting_task),
            )
            val result = backupManager.restore(uri, plan) { progress ->
                _dataTransferProgress.value = progress
            }
            when (result) {
                is RestoreResult.Success -> {
                    _dataTransferEvents.emit(context.getString(R.string.settings_data_restored_successfully))
                    syncManager.sync()
                }
                is RestoreResult.PartialFailure -> {
                    val failedNames = result.failed.entries.joinToString { "${it.key.label}: ${it.value}" }
                    _dataTransferEvents.emit(
                        context.getString(R.string.settings_restore_partial_unresolved_format, failedNames),
                    )
                    if (result.succeeded.isNotEmpty() || !result.rolledBack) {
                        syncManager.sync()
                    }
                }
                is RestoreResult.TotalFailure -> {
                    _dataTransferEvents.emit(context.getString(R.string.settings_restore_failed_format, result.error))
                }
            }
            delay(300)
            _uiState.update { it.copy(isDataTransferInProgress = false, restorePlan = null) }
            _dataTransferProgress.value = null
        }
    }

    fun clearRestorePlan() {
        _uiState.update { it.copy(restorePlan = null, backupValidationErrors = emptyList()) }
    }

    fun removeBackupHistoryEntry(entry: BackupHistoryEntry) {
        viewModelScope.launch {
            backupManager.removeBackupHistoryEntry(entry.uri)
        }
    }

}

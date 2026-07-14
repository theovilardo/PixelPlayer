package com.theveloper.pixelplay.presentation.viewmodel

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.backup.BackupManager
import com.theveloper.pixelplay.data.backup.model.BackupTransferProgressUpdate
import com.theveloper.pixelplay.data.backup.model.BackupOperationType
import com.theveloper.pixelplay.data.backup.model.BackupSection
import com.theveloper.pixelplay.data.backup.model.RestorePlan
import com.theveloper.pixelplay.data.backup.model.RestoreResult
import com.theveloper.pixelplay.data.preferences.AppThemeMode
import com.theveloper.pixelplay.data.preferences.ThemePreferencesRepository
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.data.worker.SyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject
import java.io.File

data class SetupUiState(
    val mediaPermissionGranted: Boolean = false,
    val notificationsPermissionGranted: Boolean = false,
    val isLoadingDirectories: Boolean = false,
    val blockedDirectories: Set<String> = emptySet(),
    val libraryNavigationMode: String = "tab_row",
    val navBarStyle: String = "default",
    val navBarCornerRadius: Int = 28,
    val alarmsPermissionGranted: Boolean = false,
    val appThemeMode: String = AppThemeMode.DARK,
    val isInspectingBackup: Boolean = false,
    val isRestoringBackup: Boolean = false,
    val restorePlan: RestorePlan? = null,
    val backupTransferProgress: BackupTransferProgressUpdate? = null
) {
    val allPermissionsGranted: Boolean
        get() {
            val mediaOk = mediaPermissionGranted
            val notificationsOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notificationsPermissionGranted else true
            return mediaOk && notificationsOk
        }
}

sealed interface SetupEvent {
    data class Message(val value: String) : SetupEvent
    data class RestoreCompleted(val message: String) : SetupEvent
}

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val userPreferencesRepository: UserPreferencesRepository,
    private val themePreferencesRepository: ThemePreferencesRepository,
    private val syncManager: SyncManager,
    private val backupManager: BackupManager,
    private val musicRepository: MusicRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(SetupUiState())
    val uiState = _uiState.asStateFlow()
    private val _events = MutableSharedFlow<SetupEvent>()
    val events = _events.asSharedFlow()
    
    /**
     * Expose sync progress for UI to show during initial setup
     */
    val isSyncing = syncManager.isSyncing

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

    init {
        viewModelScope.launch {
            if (!userPreferencesRepository.initialSetupDoneFlow.first()) {
                themePreferencesRepository.initializeAppThemeMode(AppThemeMode.DARK)
            }
        }

        // Consolidated collectors using combine() to reduce coroutine overhead
        viewModelScope.launch {
            combine(
                userPreferencesRepository.blockedDirectoriesFlow,
                userPreferencesRepository.libraryNavigationModeFlow,
                userPreferencesRepository.navBarStyleFlow,
                userPreferencesRepository.navBarCornerRadiusFlow,
                themePreferencesRepository.appThemeModeFlow
            ) { blocked, mode, style, radius, appThemeMode ->
                SetupPrefsUpdate(blocked, mode, style, radius, appThemeMode)
            }.collect { update ->
                _uiState.update { state ->
                    state.copy(
                        blockedDirectories = update.blocked,
                        libraryNavigationMode = update.mode,
                        navBarStyle = update.style,
                        navBarCornerRadius = update.radius,
                        appThemeMode = update.appThemeMode
                    )
                }
            }
        }

        viewModelScope.launch {
            fileExplorerStateHolder.isLoading.collect { loading ->
                _uiState.update { it.copy(isLoadingDirectories = loading) }
            }
        }
    }
    
    private data class SetupPrefsUpdate(
        val blocked: Set<String>,
        val mode: String,
        val style: String,
        val radius: Int,
        val appThemeMode: String
    )

    fun checkPermissions(context: Context) {
        val mediaPermissionGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

        val notificationsPermissionGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Not required before Android 13 (Tiramisu)
        }

        val alarmsPermissionGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }

        _uiState.update {
            it.copy(
                mediaPermissionGranted = mediaPermissionGranted,
                notificationsPermissionGranted = notificationsPermissionGranted,
                alarmsPermissionGranted = alarmsPermissionGranted
            )
        }
    }

    fun loadMusicDirectories() {
        viewModelScope.launch {
            if (!userPreferencesRepository.initialSetupDoneFlow.first()) {
                // Blacklist model: default is allow all, so no setup needed.
            }

            userPreferencesRepository.blockedDirectoriesFlow.first().let { blocked ->
                _uiState.update { it.copy(blockedDirectories = blocked) }
            }
            fileExplorerStateHolder.primeExplorerRoot()?.join()
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

    fun selectStorage(index: Int) {
        fileExplorerStateHolder.selectStorage(index)
    }

    fun refreshAvailableStorages() {
        fileExplorerStateHolder.refreshAvailableStorages()
    }

    fun refreshCurrentDirectory() {
        fileExplorerStateHolder.refreshCurrentDirectory()
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

    fun isAtRoot(): Boolean = fileExplorerStateHolder.isAtRoot()

    fun explorerRoot(): File = fileExplorerStateHolder.rootDirectory()

    fun setLibraryNavigationMode(mode: String) {
        viewModelScope.launch {
            userPreferencesRepository.setLibraryNavigationMode(mode)
        }
    }

    fun setNavBarStyle(style: String) {
        viewModelScope.launch {
            userPreferencesRepository.setNavBarStyle(style)
        }
    }

    fun setNavBarCornerRadius(radius: Int) {
        viewModelScope.launch {
            userPreferencesRepository.setNavBarCornerRadius(radius)
        }
    }

    fun setAppThemeMode(mode: String) {
        viewModelScope.launch {
            themePreferencesRepository.setAppThemeMode(mode)
        }
    }

    fun setSetupComplete() {
        viewModelScope.launch {
            completeSetup(syncAfter = true)
        }
    }
    
    /**
     * Retry the initial sync if it failed.
     * Can be called from UI when user wants to retry after a failure.
     */
    fun retrySync() {
        viewModelScope.launch {
            syncManager.fullSync()
        }
    }

    fun inspectBackupFile(uri: Uri) {
        if (_uiState.value.isInspectingBackup || _uiState.value.isRestoringBackup) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isInspectingBackup = true,
                    restorePlan = null,
                    backupTransferProgress = null
                )
            }
            val result = backupManager.inspectBackup(uri)
            result.fold(
                onSuccess = { plan ->
                    _uiState.update {
                        it.copy(
                            isInspectingBackup = false,
                            restorePlan = plan
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isInspectingBackup = false) }
                    _events.emit(
                        SetupEvent.Message(
                            context.getString(
                                R.string.settings_backup_invalid_format,
                                error.localizedMessage ?: context.getString(R.string.common_error_unknown),
                            )
                        )
                    )
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

    fun clearRestorePlan() {
        _uiState.update {
            it.copy(
                restorePlan = null,
                isInspectingBackup = false,
                isRestoringBackup = false,
                backupTransferProgress = null
            )
        }
    }

    fun restoreFromPlan(uri: Uri) {
        val plan = _uiState.value.restorePlan ?: return
        if (plan.selectedModules.isEmpty() || _uiState.value.isRestoringBackup) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isRestoringBackup = true,
                    backupTransferProgress = BackupTransferProgressUpdate(
                        operation = BackupOperationType.IMPORT,
                        step = 0,
                        totalSteps = 1,
                        title = context.getString(R.string.settings_backup_progress_preparing_restore),
                        detail = context.getString(R.string.settings_backup_progress_starting_task),
                    )
                )
            }

            val result = backupManager.restore(uri, plan) { progress ->
                _uiState.update { state -> state.copy(backupTransferProgress = progress) }
            }

            when (result) {
                is RestoreResult.Success -> {
                    _events.emit(SetupEvent.RestoreCompleted(context.getString(R.string.settings_restore_completed_success)))
                }
                is RestoreResult.PartialFailure -> {
                    val canFinishSetup = result.succeeded.isNotEmpty() || !result.rolledBack
                    if (canFinishSetup) {
                        _events.emit(
                            SetupEvent.RestoreCompleted(
                                context.getString(R.string.settings_restore_completed_partial_issues),
                            )
                        )
                    } else {
                        _events.emit(
                            SetupEvent.Message(
                                context.getString(
                                    R.string.settings_restore_could_not_complete,
                                    result.failed.values.joinToString(),
                                ),
                            )
                        )
                    }
                }
                is RestoreResult.TotalFailure -> {
                    _events.emit(SetupEvent.Message(context.getString(R.string.settings_restore_failed_format, result.error)))
                }
            }

            _uiState.update {
                it.copy(
                    isRestoringBackup = false,
                    restorePlan = null,
                    backupTransferProgress = null
                )
            }
        }
    }

    private suspend fun completeSetup(syncAfter: Boolean) {
        userPreferencesRepository.setInitialSetupDone(true)
        if (syncAfter) {
            syncManager.fullSync()
        }
    }
}

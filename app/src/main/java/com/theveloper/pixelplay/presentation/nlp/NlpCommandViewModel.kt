package com.theveloper.pixelplay.presentation.nlp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.nlp.NlpCommandIntent
import com.theveloper.pixelplay.data.nlp.NlpCommandParser
import com.theveloper.pixelplay.data.nlp.NlpCommandRepository
import com.theveloper.pixelplay.data.nlp.NlpCommandResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class NlpUiState {
    object Idle : NlpUiState()
    object Loading : NlpUiState()
    data class Success(val message: String) : NlpUiState()
    data class PendingConfirmation(
        val message: String,
        val filePaths: List<String>,
        val intent: NlpCommandIntent
    ) : NlpUiState()
    data class Error(val message: String) : NlpUiState()
}

@HiltViewModel
class NlpCommandViewModel @Inject constructor(
    private val nlpCommandRepository: NlpCommandRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<NlpUiState>(NlpUiState.Idle)
    val uiState: StateFlow<NlpUiState> = _uiState.asStateFlow()

    fun submitCommand(commandText: String) {
        if (commandText.isBlank()) {
            _uiState.update { NlpUiState.Error("Please type a command first.") }
            return
        }

        _uiState.update { NlpUiState.Loading }

        viewModelScope.launch {
            val intent = NlpCommandParser.parse(commandText)
            val result = nlpCommandRepository.execute(intent)
            _uiState.update { result.toUiState() }
        }
    }

    fun confirmDeletion(filePaths: List<String>, intent: NlpCommandIntent) {
        if (intent !is NlpCommandIntent.DeleteArtist) return

        _uiState.update { NlpUiState.Loading }

        viewModelScope.launch {
            val result = nlpCommandRepository.executeDeleteArtist(
                songFilePaths = filePaths,
                artistName = intent.targetQueries.joinToString(" & ")
            )
            _uiState.update { result.toUiState() }
        }
    }

    fun reset() {
        _uiState.update { NlpUiState.Idle }
    }

    private fun NlpCommandResult.toUiState(): NlpUiState = when (this) {
        is NlpCommandResult.Success -> NlpUiState.Success(message)
        is NlpCommandResult.Error   -> NlpUiState.Error(message)
        is NlpCommandResult.PendingConfirmation -> NlpUiState.PendingConfirmation(
            message   = message,
            filePaths = songFilePaths,
            intent    = confirmedIntent
        )
    }
}

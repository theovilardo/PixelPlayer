package com.theveloper.pixelplay.data.nlp

sealed class NlpCommandResult {
    data class Success(val message: String) : NlpCommandResult()

    data class PendingConfirmation(
        val message: String,
        val songFilePaths: List<String>,
        val confirmedIntent: NlpCommandIntent
    ) : NlpCommandResult()

    data class Error(val message: String) : NlpCommandResult()
}

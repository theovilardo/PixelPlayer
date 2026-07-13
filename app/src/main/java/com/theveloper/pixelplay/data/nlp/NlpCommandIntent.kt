package com.theveloper.pixelplay.data.nlp

sealed class NlpCommandIntent {
    data class CreatePlaylist(
        val playlistName: String,
        val targetQueries: List<String>
    ) : NlpCommandIntent()

    data class DeleteArtist(
        val targetQueries: List<String>
    ) : NlpCommandIntent()

    data class CategorizeGenre(
        val genreNames: List<String>
    ) : NlpCommandIntent()

    object Unknown : NlpCommandIntent()
}

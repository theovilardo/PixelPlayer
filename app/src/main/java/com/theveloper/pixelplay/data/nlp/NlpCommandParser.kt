package com.theveloper.pixelplay.data.nlp

object NlpCommandParser {

    private val CREATE_PLAYLIST_REGEX = Regex(
        pattern = """(?:create|make|build|generate)\s+(?:a\s+)?playlist\s+(?:of|for|from|with|named|called)?\s*(.+)""",
        option = RegexOption.IGNORE_CASE
    )

    private val DELETE_ARTIST_REGEX = Regex(
        pattern = """(?:delete|remove|erase)\s+(?:artist|singer|band|musician)?\s*(.+)""",
        option = RegexOption.IGNORE_CASE
    )

    private val CATEGORIZE_GENRE_REGEX = Regex(
        pattern = """(?:categorize|group|organize|sort)\s+(?:songs?|music|tracks?)?\s*(?:by)?\s*(?:genre)?\s+(.+)""",
        option = RegexOption.IGNORE_CASE
    )

    private val CLEAN_SUFFIX_REGEX = Regex(
        pattern = """(?:'s)?\s+(?:songs?|tracks?|music|mix|albums?)$""",
        option = RegexOption.IGNORE_CASE
    )

    private val CLEAN_PREFIX_REGEX = Regex(
        pattern = """^(?:songs?|tracks?|music|mix|albums?)\s+(?:by|of|from|with)\s+""",
        option = RegexOption.IGNORE_CASE
    )

    fun parse(rawInput: String): NlpCommandIntent {
        val cleanedInput = rawInput.trim()
        if (cleanedInput.isBlank()) return NlpCommandIntent.Unknown

        CREATE_PLAYLIST_REGEX.find(cleanedInput)?.let { match ->
            val rawTarget = match.groupValues[1].trim()
            val targets = cleanAndSplit(rawTarget)
            if (targets.isNotEmpty()) {
                return NlpCommandIntent.CreatePlaylist(
                    playlistName = buildPlaylistName(targets),
                    targetQueries = targets
                )
            }
        }

        DELETE_ARTIST_REGEX.find(cleanedInput)?.let { match ->
            val rawTarget = match.groupValues[1].trim()
            val targets = cleanAndSplit(rawTarget)
            if (targets.isNotEmpty()) {
                return NlpCommandIntent.DeleteArtist(targetQueries = targets)
            }
        }

        CATEGORIZE_GENRE_REGEX.find(cleanedInput)?.let { match ->
            val rawGenre = match.groupValues[1].trim()
            val genres = cleanAndSplit(rawGenre)
            if (genres.isNotEmpty()) {
                return NlpCommandIntent.CategorizeGenre(genreNames = genres)
            }
        }

        return NlpCommandIntent.Unknown
    }

    private fun cleanAndSplit(rawTarget: String): List<String> {
        val parts = rawTarget.split(Regex("""\s+and\s+|\s+or\s+|,\s*"""))
        return parts.map { part ->
            var cleaned = part.trim()
            cleaned = CLEAN_PREFIX_REGEX.replace(cleaned, "")
            cleaned = CLEAN_SUFFIX_REGEX.replace(cleaned, "")
            cleaned.trim()
        }.filter { it.isNotBlank() }
    }

    private fun buildPlaylistName(targets: List<String>): String {
        val capitalizedTargets = targets.map { target ->
            target.split(" ").joinToString(" ") { word ->
                word.replaceFirstChar { it.uppercaseChar() }
            }
        }
        return when (capitalizedTargets.size) {
            1 -> "${capitalizedTargets.first()} — Mix"
            2 -> "${capitalizedTargets[0]} & ${capitalizedTargets[1]}"
            else -> "${capitalizedTargets.take(2).joinToString(", ")} & More"
        }
    }
}

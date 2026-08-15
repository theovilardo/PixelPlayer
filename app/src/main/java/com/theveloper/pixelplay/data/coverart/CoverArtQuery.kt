package com.theveloper.pixelplay.data.coverart

import java.text.Normalizer
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Text normalization and match scoring for cover art search.
 *
 * Everything here is pure and free of Android dependencies so the matching
 * rules — the part that decides whether a user sees the right cover — can be
 * unit tested on the JVM.
 *
 * Tags in real libraries carry edition noise ("Abbey Road (Remastered 2019)",
 * "Nevermind - Deluxe Edition") that catalogs do not, so the album title is
 * reduced to its core before being compared.
 */
object CoverArtQuery {

    /** Candidates scoring below this are dropped as unrelated. */
    private const val MIN_SCORE = 0.2f

    /** Cost of the edit distance grows with the product of both lengths. */
    private const val COMPARISON_LENGTH_CAP = 120

    private const val ALBUM_WEIGHT = 0.65f
    private const val ARTIST_WEIGHT = 0.35f
    private const val EXACT_ALBUM_BONUS = 0.03f
    private const val EXACT_ARTIST_BONUS = 0.02f

    private val EDITION_KEYWORDS = setOf(
        "deluxe", "remaster", "remastered", "remasterized", "edition", "version",
        "bonus", "explicit", "clean", "expanded", "anniversary", "reissue",
        "mono", "stereo", "special", "extended", "limited", "collector",
        "collectors", "disc", "disk", "cd", "volume", "vol", "soundtrack",
        "ost", "single", "ep", "live"
    )

    private val BRACKETED = Regex("[(\\[{]([^(){}\\[\\]]*)[)\\]}]")
    private val FEATURING = Regex("\\s+(feat|ft|featuring|con|with)\\b\\.?.*$")
    private val DIACRITICS = Regex("\\p{Mn}+")
    private val APOSTROPHES = Regex("['‘’ʼ`´]")
    private val NON_ALPHANUMERIC = Regex("[^\\p{L}\\p{Nd}\\s]")
    private val WHITESPACE = Regex("\\s+")

    /**
     * Reduces an album title to a comparable core: no diacritics, no edition
     * suffixes, no punctuation.
     */
    fun normalizeAlbum(raw: String): String {
        val withoutDiacritics = stripDiacritics(raw)
        val withoutEditionBrackets = dropEditionBrackets(withoutDiacritics)
        val withoutEditionSuffix = dropEditionSuffix(withoutEditionBrackets)
        return collapse(withoutEditionSuffix)
    }

    /**
     * Reduces an artist name the same way, additionally dropping "feat." style
     * credits and spelling out ampersands so "Simon & Garfunkel" and
     * "Simon and Garfunkel" compare as equal.
     */
    fun normalizeArtist(raw: String): String {
        val withoutDiacritics = stripDiacritics(raw).replace("&", " and ")
        // Brackets first: "Artist (feat. Other)" only exposes the credit to the
        // featuring pattern once the parenthesis is gone.
        val withoutEditionBrackets = dropEditionBrackets(withoutDiacritics)
        val withoutFeaturing = FEATURING.replace(withoutEditionBrackets, "")
        return collapse(withoutFeaturing)
    }

    /**
     * Similarity of two already-normalized strings, in `0f..1f`.
     * Returns `0f` when either side is empty.
     */
    fun similarity(left: String, right: String): Float {
        if (left.isEmpty() || right.isEmpty()) return 0f
        if (left == right) return 1f

        val a = left.take(COMPARISON_LENGTH_CAP)
        val b = right.take(COMPARISON_LENGTH_CAP)
        val distance = levenshtein(a, b)
        val longest = max(a.length, b.length)
        return (1f - distance.toFloat() / longest).coerceIn(0f, 1f)
    }

    /**
     * Scores one candidate against the query. When the query carries no artist,
     * the album similarity alone decides the score instead of penalizing every
     * candidate for a comparison that cannot be made.
     */
    fun score(
        candidateAlbum: String,
        candidateArtist: String,
        queryAlbum: String,
        queryArtist: String
    ): Float {
        val normalizedQueryAlbum = normalizeAlbum(queryAlbum)
        val normalizedQueryArtist = normalizeArtist(queryArtist)
        return scoreNormalized(
            candidateAlbum = candidateAlbum,
            candidateArtist = candidateArtist,
            normalizedQueryAlbum = normalizedQueryAlbum,
            normalizedQueryArtist = normalizedQueryArtist
        )
    }

    /**
     * Scores every candidate, drops the unrelated ones and returns the rest
     * best first. Ties break on album title so results stay stable between
     * identical searches.
     */
    fun rank(
        candidates: List<CoverArtCandidate>,
        queryAlbum: String,
        queryArtist: String
    ): List<CoverArtCandidate> {
        val normalizedQueryAlbum = normalizeAlbum(queryAlbum)
        val normalizedQueryArtist = normalizeArtist(queryArtist)

        return candidates
            .map { candidate ->
                candidate.copy(
                    score = scoreNormalized(
                        candidateAlbum = candidate.albumTitle,
                        candidateArtist = candidate.artistName,
                        normalizedQueryAlbum = normalizedQueryAlbum,
                        normalizedQueryArtist = normalizedQueryArtist
                    )
                )
            }
            .filter { it.score >= MIN_SCORE }
            .sortedWith(
                compareByDescending<CoverArtCandidate> { it.score }
                    .thenBy { it.albumTitle.lowercase(Locale.ROOT) }
            )
    }

    private fun scoreNormalized(
        candidateAlbum: String,
        candidateArtist: String,
        normalizedQueryAlbum: String,
        normalizedQueryArtist: String
    ): Float {
        val albumSimilarity = similarity(normalizeAlbum(candidateAlbum), normalizedQueryAlbum)
        val artistSimilarity = similarity(normalizeArtist(candidateArtist), normalizedQueryArtist)

        val base = when {
            normalizedQueryArtist.isEmpty() -> albumSimilarity
            normalizedQueryAlbum.isEmpty() -> artistSimilarity
            else -> ALBUM_WEIGHT * albumSimilarity + ARTIST_WEIGHT * artistSimilarity
        }

        var score = base
        if (albumSimilarity == 1f) score += EXACT_ALBUM_BONUS
        if (artistSimilarity == 1f) score += EXACT_ARTIST_BONUS
        return score.coerceIn(0f, 1f)
    }

    private fun stripDiacritics(raw: String): String {
        val lowercase = raw.lowercase(Locale.ROOT)
        val decomposed = Normalizer.normalize(lowercase, Normalizer.Form.NFD)
        return DIACRITICS.replace(decomposed, "")
    }

    /**
     * Removes bracketed groups that only carry edition noise, and unwraps the
     * ones that carry real title content ("Blue Train (The Ultimate Blue Train)").
     */
    private fun dropEditionBrackets(value: String): String {
        return BRACKETED.replace(value) { match ->
            val inner = match.groupValues[1]
            if (containsEditionKeyword(inner)) " " else " $inner "
        }
    }

    /**
     * Drops trailing `- Remastered 2011` style suffixes, keeping at least the
     * first segment so a title that is entirely made of keywords survives.
     */
    private fun dropEditionSuffix(value: String): String {
        val segments = value.split(" - ")
        if (segments.size < 2) return value

        val kept = segments.toMutableList()
        while (kept.size > 1 && containsEditionKeyword(kept.last())) {
            kept.removeAt(kept.lastIndex)
        }
        return kept.joinToString(" - ")
    }

    private fun containsEditionKeyword(value: String): Boolean {
        val words = collapse(value).split(" ").filter { it.isNotEmpty() }
        return words.isNotEmpty() && words.any { word ->
            word in EDITION_KEYWORDS || word.toIntOrNull() != null
        }
    }

    private fun collapse(value: String): String {
        // Apostrophes are dropped rather than turned into spaces so "Pepper's"
        // stays one word and still matches a catalog spelling it "Peppers".
        val withoutApostrophes = APOSTROPHES.replace(value, "")
        val withoutPunctuation = NON_ALPHANUMERIC.replace(withoutApostrophes, " ")
        return WHITESPACE.replace(withoutPunctuation, " ").trim()
    }

    private fun levenshtein(left: String, right: String): Int {
        var previous = IntArray(right.length + 1) { it }
        var current = IntArray(right.length + 1)

        for (i in 1..left.length) {
            current[0] = i
            for (j in 1..right.length) {
                val substitution = previous[j - 1] + if (left[i - 1] == right[j - 1]) 0 else 1
                current[j] = min(min(current[j - 1] + 1, previous[j] + 1), substitution)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[right.length]
    }
}

package com.theveloper.pixelplay.data.nlp

object NlpFuzzyMatcher {

    private const val MATCH_THRESHOLD_FRACTION = 0.40

    fun findBestMatch(query: String, candidates: List<String>): String? {
        if (query.isBlank() || candidates.isEmpty()) return null

        val normalizedQuery = query.trim().lowercase()

        val exactMatch = candidates.firstOrNull { it.lowercase() == normalizedQuery }
        if (exactMatch != null) return exactMatch

        val startsWithMatch = candidates.firstOrNull { it.lowercase().startsWith(normalizedQuery) }
        if (startsWithMatch != null) return startsWithMatch

        var bestCandidate: String? = null
        var bestDistance = Int.MAX_VALUE

        for (candidate in candidates) {
            val normalizedCandidate = candidate.lowercase()
            val dist = levenshteinDistance(normalizedQuery, normalizedCandidate)
            val threshold = (maxOf(normalizedQuery.length, normalizedCandidate.length) * MATCH_THRESHOLD_FRACTION).toInt()

            if (dist <= threshold && dist < bestDistance) {
                bestDistance = dist
                bestCandidate = candidate
            }
        }

        return bestCandidate
    }

    fun findAllMatches(query: String, candidates: List<String>): List<String> {
        if (query.isBlank() || candidates.isEmpty()) return emptyList()

        val normalizedQuery = query.trim().lowercase()

        return candidates
            .filter { candidate ->
                val normalized = candidate.lowercase()
                val dist = levenshteinDistance(normalizedQuery, normalized)
                val threshold = (maxOf(normalizedQuery.length, normalized.length) * MATCH_THRESHOLD_FRACTION).toInt()
                dist <= threshold
            }
            .sortedBy { candidate ->
                levenshteinDistance(normalizedQuery, candidate.lowercase())
            }
    }

    fun levenshteinDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        val (shorter, longer) = if (a.length <= b.length) a to b else b to a

        var previousRow = IntArray(shorter.length + 1) { it }

        for (i in longer.indices) {
            val currentRow = IntArray(shorter.length + 1)
            currentRow[0] = i + 1

            for (j in shorter.indices) {
                val insertCost = previousRow[j + 1] + 1
                val deleteCost = currentRow[j] + 1
                val replaceCost = previousRow[j] + if (shorter[j] == longer[i]) 0 else 1
                currentRow[j + 1] = minOf(insertCost, deleteCost, replaceCost)
            }

            previousRow = currentRow
        }

        return previousRow[shorter.length]
    }
}

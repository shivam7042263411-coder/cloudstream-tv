package com.lagradost.cloudstream3.ui.kumo.catalog

import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType

/**
 * Title-matching rules for bridging catalog items to provider results.
 * Compact port of the proven TV matching strategy (similarity floor,
 * year tolerance, type gates) — pure logic, no network.
 */
object StremioMatcher {
    const val MIN_TITLE_SIMILARITY = 0.5

    private val movieTypes = setOf(TvType.Movie, TvType.AnimeMovie, TvType.Documentary)
    private val tvTypes = setOf(TvType.TvSeries, TvType.Anime, TvType.OVA, TvType.Cartoon, TvType.AsianDrama)
    private val catchAllTvTypes = setOf(TvType.Anime, TvType.OVA, TvType.AsianDrama)

    fun normalizeTitle(s: String): String {
        return s.lowercase().trim()
            .replace(Regex("\\(\\d{4}\\)"), " ")
            .replace(Regex("\\b\\d{4}\\b"), " ")
            .replace(Regex("\\b(temporada|season)\\s*\\d+\\b"), " ")
            .replace(Regex("\\b[st]\\d{1,2}\\b"), " ")
            .replace(Regex("\\b(part|parte)\\s*\\d+\\b"), " ")
            .replace(Regex("[:\\-–—]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                curr[j] = minOf(
                    prev[j] + 1,
                    curr[j - 1] + 1,
                    prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                )
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[b.length]
    }

    fun similarity(s1: String, s2: String): Double {
        val aNorm = normalizeTitle(s1)
        val bNorm = normalizeTitle(s2)
        if (aNorm == bNorm) return 1.0
        if (aNorm.isEmpty() || bNorm.isEmpty()) return 0.0
        if (aNorm.contains(bNorm) || bNorm.contains(aNorm)) {
            val ratio = minOf(aNorm.length, bNorm.length).toDouble() /
                maxOf(aNorm.length, bNorm.length).toDouble()
            if (ratio >= 0.8) return 0.85
        }
        val maxLen = maxOf(aNorm.length, bNorm.length)
        return 1.0 - (levenshtein(aNorm, bNorm).toDouble() / maxLen)
    }

    /**
     * Best provider result for a catalog item, or null.
     * Mirrors the TV gates: exact title overrides type, catch-all anime types
     * need strong titles, year reject beyond ±1, floor at 0.5.
     */
    fun findBestMatch(
        results: List<SearchResponse>,
        titles: List<String>,
        year: Int?,
        isMovie: Boolean,
    ): SearchResponse? {
        return results.mapNotNull { result ->
            val sim = titles.maxOf { similarity(result.name, it) }
            val isExact = sim >= 0.95
            val resultType = result.type
            if (resultType != null && !isExact) {
                val typeOk = if (isMovie) resultType in movieTypes else resultType in tvTypes
                if (!typeOk) return@mapNotNull null
            }
            if (!isMovie && resultType in catchAllTvTypes && sim < 0.9) {
                return@mapNotNull null
            }
            val resultYear = (result as? com.lagradost.cloudstream3.AnimeSearchResponse)?.year
            if (year != null && resultYear != null && kotlin.math.abs(year - resultYear) > 1) {
                return@mapNotNull null
            }
            var score = sim
            if (year != null && resultYear == year) score += 0.15
            if (resultType != null && ((isMovie && resultType in movieTypes) || (!isMovie && resultType in tvTypes))) {
                score += 0.05
            }
            result to score
        }.filter { it.second >= MIN_TITLE_SIMILARITY }
            .maxByOrNull { it.second }
            ?.first
    }
}

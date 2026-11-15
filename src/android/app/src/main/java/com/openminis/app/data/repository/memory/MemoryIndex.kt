package com.openminis.app.data.repository.memory

import kotlin.math.ln

data class MemoryHit(
    val score: Double,
    val entry: MemoryEntry,
    val bm25: Double,
    val jaccard: Double,
    val entity: Double,
)

/**
 * Immutable in-memory index over [MemoryEntry] objects.
 *
 * Ranking is a weighted sum of normalized BM25, token-set Jaccard, entity-tag
 * hit rate, title overlap, `###` heading overlap, recency, and importance.
 * IDF is computed over the whole index; `scope` only filters candidates,
 * matching the prototype's documented simplification.
 */
class MemoryIndex(val entries: List<MemoryEntry>) {
    private val postings: Map<String, Map<Int, Int>>
    private val documentFrequency: Map<String, Int>
    private val documentLength: IntArray
    private val averageDocumentLength: Double
    private val headingTokenSets: List<Set<String>>
    private val titleTokenSets: List<Set<String>>

    init {
        val postingsBuilder = HashMap<String, HashMap<Int, Int>>()
        val dfBuilder = HashMap<String, Int>()
        val lengths = IntArray(entries.size)
        val headings = ArrayList<Set<String>>(entries.size)
        val titles = ArrayList<Set<String>>(entries.size)

        entries.forEachIndexed { index, entry ->
            lengths[index] = maxOf(1, entry.tokens.size)
            for ((token, frequency) in entry.tf) {
                postingsBuilder.getOrPut(token) { HashMap() }[index] = frequency
                dfBuilder[token] = (dfBuilder[token] ?: 0) + 1
            }
            headings.add(MemoryTokenizer.tokenSet(entry.headings.joinToString("\n")))
            titles.add(MemoryTokenizer.tokenSet(entry.title))
        }

        postings = postingsBuilder
        documentFrequency = dfBuilder
        documentLength = lengths
        averageDocumentLength = if (entries.isEmpty()) {
            1.0
        } else {
            lengths.sum().toDouble() / entries.size
        }
        headingTokenSets = headings
        titleTokenSets = titles
    }

    fun search(
        query: String,
        scope: MemoryScope = MemoryScope.ALL,
        limit: Int = MemoryIndexConfig.TOP_K,
    ): List<MemoryHit> {
        if (entries.isEmpty()) return emptyList()

        val cleaned = MemoryQueryCleaner.clean(query)
        val queryTokens = MemoryTokenizer.tokenize(cleaned)
        if (queryTokens.isEmpty()) return emptyList()

        val uniqueQueryTokens = queryTokens.distinct()
        val querySet = queryTokens.toHashSet()
        val queryTags = MemoryTagger.tagsFor(cleaned)
        val take = limit.coerceIn(1, MemoryIndexConfig.MAX_LIMIT)

        val candidates = ArrayList<Int>(entries.size)
        for (index in entries.indices) {
            if (scopeAllows(scope, entries[index].kind)) candidates.add(index)
        }
        if (candidates.isEmpty()) return emptyList()

        val rawScores = DoubleArray(entries.size)
        var maxRaw = 0.0
        for (index in candidates) {
            val score = bm25(uniqueQueryTokens, index)
            rawScores[index] = score
            if (score > maxRaw) maxRaw = score
        }

        val ranked = ArrayList<MemoryHit>()
        for (index in candidates) {
            val entry = entries[index]
            val rawBm25 = rawScores[index]
            val bm25 = if (maxRaw > 0.0) rawBm25 / maxRaw else 0.0
            val jaccard = jaccard(querySet, entry.tokenSet)
            val entity = if (queryTags.isEmpty()) {
                0.0
            } else {
                queryTags.count { it in entry.tags }.toDouble() / queryTags.size
            }
            val titleOverlap = overlapRatio(querySet, titleTokenSets[index])
            val headingOverlap = overlapRatio(querySet, headingTokenSets[index])

            // Recency and importance are always positive; without this gate a
            // query with no lexical signal would still return arbitrary
            // entries. Require at least one real match signal.
            if (rawBm25 <= 0.0 && jaccard <= 0.0 && entity <= 0.0) continue

            val score = MemoryIndexConfig.W_BM25 * bm25 +
                MemoryIndexConfig.W_JACCARD * jaccard +
                MemoryIndexConfig.W_ENTITY * entity +
                MemoryIndexConfig.W_TITLE * titleOverlap +
                MemoryIndexConfig.W_HEADING * headingOverlap +
                MemoryIndexConfig.W_RECENCY * entry.recency +
                MemoryIndexConfig.W_IMPORTANCE * entry.importance
            ranked.add(MemoryHit(score, entry, bm25, jaccard, entity))
        }

        ranked.sortWith(
            compareByDescending<MemoryHit> { it.score }
                .thenBy { it.entry.title },
        )

        val out = ArrayList<MemoryHit>(take)
        for (hit in ranked) {
            if (out.any { jaccard(hit.entry.tokenSet, it.entry.tokenSet) > MemoryIndexConfig.MMR_DEDUP }) {
                continue
            }
            out.add(hit)
            if (out.size >= take) break
        }
        return out
    }

    private fun bm25(queryTokens: List<String>, index: Int): Double {
        var score = 0.0
        val length = documentLength[index].toDouble()
        for (token in queryTokens) {
            val frequency = postings[token]?.get(index) ?: 0
            if (frequency == 0) continue
            val df = documentFrequency[token] ?: 0
            val idf = ln(1.0 + (entries.size - df + 0.5) / (df + 0.5))
            val denominator = frequency + MemoryIndexConfig.BM25_K1 * (
                1.0 - MemoryIndexConfig.BM25_B +
                    MemoryIndexConfig.BM25_B * length / averageDocumentLength
                )
            score += idf * (frequency * (MemoryIndexConfig.BM25_K1 + 1.0)) / denominator
        }
        return score
    }

    private fun scopeAllows(scope: MemoryScope, kind: MemoryKind): Boolean = when (scope) {
        MemoryScope.ALL -> true
        MemoryScope.DAILY -> kind == MemoryKind.DAILY
    }

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var intersection = 0
        for (token in a) {
            if (token in b) intersection++
        }
        if (intersection == 0) return 0.0
        return intersection.toDouble() / (a.size + b.size - intersection)
    }

    private fun overlapRatio(query: Set<String>, target: Set<String>): Double {
        if (query.isEmpty() || target.isEmpty()) return 0.0
        var intersection = 0
        for (token in query) {
            if (token in target) intersection++
        }
        return intersection.toDouble() / query.size
    }
}

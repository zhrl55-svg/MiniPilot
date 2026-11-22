package com.openminis.app.data.repository.memory

/**
 * Tuning constants for the in-memory memory index (Phase A).
 *
 * The weights are the ones validated by the Python prototype on the 35-query
 * set (see scripts/memory_index_prototype/). Keep them here, not at call
 * sites, so a future real-query evaluation can adjust one place without
 * touching ranking code.
 */
object MemoryIndexConfig {
    // Ranking weights. The seven terms sum to 1.0. These are the prototype's
    // validated values and stay unchanged — authority is applied separately as
    // a multiplier (see AUTHORITY_GAIN), not by re-splitting these weights.
    const val W_BM25 = 0.46
    const val W_JACCARD = 0.18
    const val W_ENTITY = 0.14
    const val W_TITLE = 0.08
    const val W_HEADING = 0.10
    const val W_RECENCY = 0.02
    const val W_IMPORTANCE = 0.02

    /**
     * [T-memory-authority-gain] Authority as a MULTIPLIER on the lexical score.
     *
     * Why not an additive weight: with `W_IMPORTANCE = 0.02` the authority term
     * was worth ~0.003 of score, so a fresh daily log with one extra matching
     * token always buried the GLOBAL section that actually documents the fact.
     * Raising the additive weight instead (0.34/0.14) also worked on the eval
     * set, but it hands a fixed bonus to GLOBAL even when its lexical match is
     * weak — that is how a reference file starts crowding out real daily hits.
     *
     * A multiplier scales WITH the evidence: no lexical match → no boost, and
     * `scope = daily` is untouched because every candidate has importance 1.0.
     *
     * Measured on the 35 device queries (scripts/memory_index_prototype):
     * recall@5 0.943 → 0.971, MRR 0.720 → 0.910, 13 queries improved,
     * 0 regressed; daily-target queries stayed 5/6. Stable for gain 0.5–0.8 and
     * for IMPORTANCE_GLOBAL 1.8–2.2, so this is not a knife-edge fit.
     */
    const val AUTHORITY_GAIN = 0.65

    // BM25 parameters, matching the prototype.
    const val BM25_K1 = 1.2
    const val BM25_B = 0.75

    // Drop a lower-ranked entry when its token-set Jaccard with an already
    // selected entry is above this value (simplified MMR).
    const val MMR_DEDUP = 0.90

    const val TOP_K = 5
    const val MAX_LIMIT = 10
    const val MAX_RECALL_CHARS = 6_000
    const val MAX_ENTRY_CHARS = 1_200

    /** A single memory file larger than this is read only up to the cap. */
    const val MAX_FILE_BYTES = 256 * 1024

    /** Index at most this many characters of one entry's body. */
    const val MAX_ENTRY_TEXT_CHARS = 8_192

    /** GLOBAL.md and USER.md are stable reference material. */
    const val STATIC_RECENCY = 0.9
    const val RECENCY_HALF_LIFE_DAYS = 14.0

    // Relative authority of the three sources. Consumed by both the small
    // additive term above and the AUTHORITY_GAIN multiplier; DAILY must stay
    // 1.0 so a daily-only scope is unaffected by the multiplier.
    const val IMPORTANCE_GLOBAL = 2.20
    const val IMPORTANCE_USER = 1.90
    const val IMPORTANCE_DAILY = 1.00
}

package com.openminis.app.agent.skill

/**
 * Pure ground-truth evaluator behind `agent.skills.matchBench mode=store`.
 *
 * The old bench treated "any record above threshold" as a hit, so an ASR
 * fragment could make an unrelated query look successful. Every pair here has
 * an expected record (explicit `expectedId`, or the deterministic id / goal of
 * the first wording). A hit means the matcher returned THAT record; a record
 * that is missing, dirty, or merely a different record is reported as such and
 * never counted as a hit.
 *
 * Kept out of the RPC layer so the semantics are unit-testable without an
 * Android Context or a device.
 */
internal object SkillBench {

    data class Pair(
        val storedGoal: String,
        val query: String,
        val expectedId: String? = null,
    )

    data class Row(
        val storedGoal: String,
        val query: String,
        val expectedId: String,
        val expectedGoal: String,
        val expectedStatus: String,
        val expectedReason: String?,
        val matchedId: String?,
        val matchedGoal: String?,
        val matchedMissing: List<String>,
        val sameRecord: Boolean,
        val falseHit: Boolean,
        val outcome: String,
        val score: Double,
        val topScore: Double,
    )

    data class Result(
        val recordCount: Int,
        val matchingPoolCount: Int,
        val eligibleCount: Int,
        val expectedMissing: Int,
        val expectedDirty: Int,
        val expectedIncomplete: Int,
        val evaluatedPairs: Int,
        val injectablePairs: Int,
        val injectableHits: Int,
        val correctHits: Int,
        val wrongMatches: Int,
        val noMatches: Int,
        val rows: List<Row>,
    ) {
        val injectableHitRate: Double
            get() = if (injectablePairs == 0) 0.0 else injectableHits.toDouble() / injectablePairs

        /** True record-level accuracy over pairs whose expected record exists. */
        val hitRate: Double
            get() = if (evaluatedPairs == 0) 0.0 else correctHits.toDouble() / evaluatedPairs

        /** Conservative pipeline accuracy; missing expected records count as misses. */
        fun hitRateAll(pairCount: Int): Double =
            if (pairCount == 0) 0.0 else correctHits.toDouble() / pairCount
    }

    /**
     * Evaluate [pairs] against [records]. Matching uses the same pool as hint
     * retrieval: dirty goals are excluded; readable-but-incomplete records are
     * candidates (so their matching quality is measured) but cannot count as
     * injectable hits.
     */
    fun evaluateStore(
        records: List<SkillRecord>,
        pairs: List<Pair>,
        appPackage: String?,
        config: SkillMatcher.MatchConfig,
    ): Result {
        val readable = records.filter { SkillStore.goalProblem(it.goal) == null }
        val eligible = readable.filter { SkillStore.missingHintParts(it).isEmpty() }
        val rows = ArrayList<Row>(pairs.size)
        var correct = 0
        var wrong = 0
        var noMatch = 0
        var expectedMissing = 0
        var expectedDirty = 0
        var expectedIncomplete = 0
        var evaluated = 0
        var injectablePairs = 0
        var injectableHits = 0

        for (pair in pairs) {
            val expected = findExpected(records, pair)
            val goalProblem = expected?.let { SkillStore.goalProblem(it.goal) }
            val expectedMissingParts = expected?.let { SkillStore.missingHintParts(it) } ?: emptyList()
            val expectedStatus = when {
                expected == null -> "missing"
                goalProblem != null -> "dirty"
                expectedMissingParts.isNotEmpty() -> "incomplete"
                else -> "eligible"
            }
            when (expectedStatus) {
                "missing" -> expectedMissing++
                "dirty" -> expectedDirty++
                "incomplete" -> expectedIncomplete++
            }
            if (expectedStatus == "eligible" || expectedStatus == "incomplete") evaluated++
            if (expectedStatus == "eligible") injectablePairs++

            val match = SkillMatcher.best(pair.query, readable, appPackage, config)
            val top = readable.maxOfOrNull {
                SkillMatcher.score(pair.query, it, appPackage, config.algorithm)
            } ?: 0.0
            val same = expected != null && match?.record?.id == expected.id
            if (same) {
                correct++
                if (expectedStatus == "eligible") injectableHits++
            } else if (match != null) {
                // The old bench counted this as a hit. It is the exact
                // failure being fixed: another record crossed the line.
                wrong++
            } else {
                noMatch++
            }
            val matchedMissing = match?.record?.let { SkillStore.missingHintParts(it) } ?: emptyList()
            val outcome = when {
                expectedStatus == "missing" -> "expected-missing"
                expectedStatus == "dirty" -> "expected-dirty"
                same -> "correct"
                match != null -> "wrong-record"
                else -> "no-match"
            }
            val expectedId = expected?.id
                ?: pair.expectedId?.trim()?.takeIf { it.isNotEmpty() }
                ?: SkillStore.idForGoal(pair.storedGoal)
            rows.add(
                Row(
                    storedGoal = pair.storedGoal,
                    query = pair.query,
                    expectedId = expectedId,
                    expectedGoal = expected?.goal ?: pair.storedGoal,
                    expectedStatus = expectedStatus,
                    expectedReason = when (expectedStatus) {
                        "dirty" -> goalProblem
                        "incomplete" -> "missing-" + expectedMissingParts.joinToString(",")
                        else -> null
                    },
                    matchedId = match?.record?.id,
                    matchedGoal = match?.record?.goal,
                    matchedMissing = matchedMissing,
                    sameRecord = same,
                    falseHit = match != null && !same,
                    outcome = outcome,
                    score = match?.score ?: top,
                    topScore = top,
                ),
            )
        }

        return Result(
            recordCount = records.size,
            matchingPoolCount = readable.size,
            eligibleCount = eligible.size,
            expectedMissing = expectedMissing,
            expectedDirty = expectedDirty,
            expectedIncomplete = expectedIncomplete,
            evaluatedPairs = evaluated,
            injectablePairs = injectablePairs,
            injectableHits = injectableHits,
            correctHits = correct,
            wrongMatches = wrong,
            noMatches = noMatch,
            rows = rows,
        )
    }

    /**
     * Resolve a pair's expected record. An explicit id is authoritative (a
     * stale id is a `missing` result, not a silent fallback). Without one, the
     * deterministic record id derived from the goal is tried first, then
     * normalized goal equality (covers records written before normalization).
     */
    fun findExpected(records: List<SkillRecord>, pair: Pair): SkillRecord? {
        val explicit = pair.expectedId?.trim().orEmpty()
        if (explicit.isNotEmpty()) return records.firstOrNull { it.id == explicit }
        val expectedGoal = SkillStore.normalizeGoal(pair.storedGoal)
        return records.firstOrNull { it.id == SkillStore.idForGoal(expectedGoal) }
            ?: records.firstOrNull { SkillStore.normalizeGoal(it.goal) == expectedGoal }
    }
}

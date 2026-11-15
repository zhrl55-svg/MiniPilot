package com.openminis.app.debug

import android.content.Context
import com.openminis.app.agent.guard.AuditLog
import com.openminis.app.agent.guard.Guard
import com.openminis.app.agent.skill.SkillBench
import com.openminis.app.agent.skill.SkillMatcher
import com.openminis.app.agent.skill.SkillRecord
import com.openminis.app.agent.skill.SkillSettings
import com.openminis.app.agent.skill.SkillStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * `agent.*` RPC surface for the skill store and the guard audit — the
 * acceptance hooks for both capabilities. Read-only except
 * `agent.guard.setEnforce` (the opt-in switch; false by default),
 * `agent.skills.clear`, and the retrieval knobs (`agent.skills.config.set`).
 *
 * All file work runs on [Dispatchers.IO]; `audit.jsonl` can be a few MB by the
 * time a harness tails it.
 */
internal object AgentDebugMethods {

    /** `agent.skills.list {limit?=20}` — newest first, summaries only. */
    suspend fun skillsList(context: Context, params: JSONObject): JSONArray = withContext(Dispatchers.IO) {
        val limit = params.optInt("limit", 20).coerceIn(1, 200)
        val eligibleOnly = params.optBoolean("eligibleOnly", false)
        val arr = JSONArray()
        val records = SkillStore.load(context)
            .sortedByDescending { it.lastUsedAt }
            .filter { !eligibleOnly || SkillStore.isEligible(it) }
            .take(limit)
        for (record in records) {
            val ineligible = SkillStore.ineligibleReason(record)
            val missing = SkillStore.missingHintParts(record)
            arr.put(JSONObject().apply {
                put("id", record.id)
                put("goal", record.goal)
                put("appPackage", record.appPackage ?: JSONObject.NULL)
                put("successCount", record.successCount)
                put("lastUsedAt", record.lastUsedAt)
                put("stepCount", record.steps.size)
                // Schema 3 provenance: does this record know the actual
                // command and output, or only the old action summary? These
                // flags use the same distilled steps as the hint gate, so
                // `hasInput && hasOutput && hasConclusion` is exactly the
                // element half of `eligible`.
                put("hasInput", missing.none { it == "inputs" })
                put("hasOutput", missing.none { it == "keyOutput" })
                put("hasConclusion", missing.none { it == "conclusion" })
                // Eligibility is the same gate retrieval uses: readable goal
                // AND all three hint elements. `ineligibleReason` is the
                // pending-cleanup inventory (nothing is deleted automatically).
                put("eligible", ineligible == null)
                put("ineligibleReason", ineligible ?: JSONObject.NULL)
                put("missingParts", JSONArray().also { parts -> missing.forEach { parts.put(it) } })
                put("toolChain", JSONArray().also { chain -> record.toolChain.forEach { chain.put(it) } })
            })
        }
        arr
    }

    /** `agent.skills.get {id}` — one full record, including step input/output. */
    suspend fun skillsGet(context: Context, params: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val id = params.optString("id", "").trim()
        if (id.isEmpty()) throw RPCException(-32602, "Missing 'id' param")
        val record = SkillStore.load(context).firstOrNull { it.id == id }
            ?: throw RPCException(-32602, "No skill record with id=$id")
        record.toJson().apply {
            val ineligible = SkillStore.ineligibleReason(record)
            put("eligible", ineligible == null)
            put("ineligibleReason", ineligible ?: JSONObject.NULL)
            put("missingParts", JSONArray().also { parts ->
                SkillStore.missingHintParts(record).forEach { parts.put(it) }
            })
        }
    }

    /** `agent.skills.clear {}` — deletes the store, returns how many records went. */
    suspend fun skillsClear(context: Context, params: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        JSONObject().put("cleared", SkillStore.clear(context))
    }

    /**
     * `agent.skills.hint {goal, appPackage?}` — run the same retrieval +
     * rendering path the chat loop uses, and return the exact prompt section.
     * This is how a harness proves the injected text is a method, not a list
     * of tool names.
     */
    suspend fun skillsHint(context: Context, params: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val goal = params.optString("goal", "").trim()
        if (goal.isEmpty()) throw RPCException(-32602, "Missing 'goal' param")
        val appPackage = params.optString("appPackage", "").trim()
            .ifEmpty { context.packageName }
        val config = SkillSettings.config(context)
        // Same path as the chat loop. `hit=true` is only possible when the
        // matched record carries commands/inputs, key output and conclusion;
        // an incomplete candidate is reported with `missing` so the harness
        // can see exactly which contract item failed.
        val decision = SkillStore.hintFor(context, goal, appPackage, config)
        JSONObject().apply {
            put("hit", decision.hit)
            put("score", decision.score)
            put("threshold", decision.threshold)
            put("algorithm", decision.algorithm.wire)
            put("recordCount", decision.recordCount)
            put("matchingPoolCount", decision.matchingPoolCount)
            put("eligibleCount", decision.eligibleCount)
            put("matchedGoal", decision.record?.goal ?: JSONObject.NULL)
            put("matchedId", decision.record?.id ?: JSONObject.NULL)
            put("missing", JSONArray().also { arr -> decision.missing.forEach { arr.put(it) } })
            put("reason", decision.reason)
            put("hint", decision.hint ?: JSONObject.NULL)
        }
    }

    /** `agent.skills.config.get` — the retrieval knobs in force. */
    suspend fun skillsConfigGet(context: Context): JSONObject = withContext(Dispatchers.IO) {
        val config = SkillSettings.config(context)
        JSONObject().apply {
            put("threshold", config.threshold)
            put("algorithm", config.algorithm.wire)
            put("legacyThreshold", SkillMatcher.THRESHOLD)
            put("hybridThreshold", SkillMatcher.DEFAULT_HYBRID_THRESHOLD)
        }
    }

    /**
     * `agent.skills.config.set {threshold?, algorithm?, reset?}` — adjust the
     * matching threshold and/or switch to the legacy algorithm. Omitted
     * fields are untouched; `reset=true` clears both first.
     */
    suspend fun skillsConfigSet(context: Context, params: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        if (params.optBoolean("reset", false)) SkillSettings.reset(context)
        if (params.has("algorithm")) {
            SkillSettings.setAlgorithm(context, params.optString("algorithm", ""))
        }
        if (params.has("threshold")) {
            val value = params.optDouble("threshold", Double.NaN)
            if (value.isNaN()) throw RPCException(-32602, "Invalid 'threshold'")
            SkillSettings.setThreshold(context, value)
        }
        val config = SkillSettings.config(context)
        JSONObject().apply {
            put("threshold", config.threshold)
            put("algorithm", config.algorithm.wire)
        }
    }

    /**
     * `agent.skills.matchBench {pairs?, threshold?, algorithm?, mode?}` —
     * measure intent matching on constructed pairs (P1 acceptance: 5+ groups
     * of different Chinese wording for the same task, hit rate >= 50%).
     *
     * `mode=synthetic` (default) turns the first half of every pair into an
     * in-memory record and asks the matcher to retrieve it from the query
     * half. All pairs are candidates at once, so a cross-pair confusion counts
     * as a miss.
     *
     * `mode=store` runs the same queries against the real skills.json WITH a
     * ground truth: `hit` is true only when the matched record IS the expected
     * one (`sameRecord`). Pairs may carry an explicit `expectedId` (from
     * `agent.skills.list`) as the third array element or an object field; when
     * omitted, the expected record is derived from the pair's goal. A pair
     * whose expected record is absent is reported as `expected-missing`, never
     * as a hit, so "some other record crossed the threshold" can no longer
     * inflate the rate. Accuracy is reported over `evaluatedPairs` (expected
     * record present and usable as a candidate).
     */
    suspend fun skillsMatchBench(context: Context, params: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val stored = SkillSettings.config(context)
        val algorithm = if (params.has("algorithm")) {
            SkillMatcher.Algorithm.fromWire(params.optString("algorithm", ""))
        } else {
            stored.algorithm
        }
        val threshold = if (params.has("threshold")) {
            val value = params.optDouble("threshold", Double.NaN)
            if (value.isNaN()) throw RPCException(-32602, "Invalid 'threshold'")
            value
        } else {
            if (algorithm == stored.algorithm) stored.threshold
            else SkillMatcher.Algorithm.defaultThreshold(algorithm)
        }
        val config = SkillMatcher.MatchConfig(threshold, algorithm)
        val appPackage = params.optString("appPackage", "").trim().ifEmpty { context.packageName }
        val pairs = parseBenchPairs(params) ?: BUILT_IN_BENCH_PAIRS
        val mode = params.optString("mode", "synthetic").trim().lowercase()

        val result = JSONObject().apply {
            put("mode", mode)
            put("algorithm", algorithm.wire)
            put("threshold", threshold)
            put("appPackage", appPackage)
            put("pairCount", pairs.size)
        }

        if (mode == "store") {
            val all = SkillStore.load(context)
            val bench = SkillBench.evaluateStore(
                records = all,
                pairs = pairs,
                appPackage = appPackage,
                config = config,
            )
            val arr = JSONArray()
            for (row in bench.rows) {
                arr.put(JSONObject().apply {
                    put("storedGoal", row.storedGoal)
                    put("query", row.query)
                    put("expectedId", row.expectedId)
                    put("expectedGoal", row.expectedGoal)
                    put("expectedStatus", row.expectedStatus)
                    put("expectedReason", row.expectedReason ?: JSONObject.NULL)
                    put("matchedId", row.matchedId ?: JSONObject.NULL)
                    put("matchedGoal", row.matchedGoal ?: JSONObject.NULL)
                    put("matchedMissing", JSONArray().also { a ->
                        row.matchedMissing.forEach { a.put(it) }
                    })
                    put("sameRecord", row.sameRecord)
                    put("hit", row.sameRecord)
                    put("falseHit", row.falseHit)
                    put("outcome", row.outcome)
                    put("score", row.score)
                    put("topScore", row.topScore)
                })
            }
            result.put("recordCount", bench.recordCount)
            result.put("matchingPoolCount", bench.matchingPoolCount)
            result.put("eligibleCount", bench.eligibleCount)
            result.put("ineligibleCount", bench.recordCount - bench.eligibleCount)
            result.put("expectedMissing", bench.expectedMissing)
            result.put("expectedDirty", bench.expectedDirty)
            result.put("expectedIncomplete", bench.expectedIncomplete)
            result.put("evaluatedPairs", bench.evaluatedPairs)
            result.put("injectablePairs", bench.injectablePairs)
            result.put("injectableHits", bench.injectableHits)
            result.put("injectableHitRate", bench.injectableHitRate)
            result.put("correctHits", bench.correctHits)
            result.put("wrongMatches", bench.wrongMatches)
            result.put("falseHits", bench.wrongMatches)
            result.put("noMatches", bench.noMatches)
            result.put("hits", bench.correctHits)
            result.put("hitRate", bench.hitRate)
            result.put("hitRateAll", bench.hitRateAll(pairs.size))
            result.put("pairs", arr)
            if (bench.evaluatedPairs == 0 && pairs.isNotEmpty()) {
                result.put(
                    "note",
                    "No expected record exists in skills.json for these pairs; hitRate is 0/0. " +
                        "Supply pairs as [recordGoal, query, expectedId] (expectedId from agent.skills.list) " +
                        "or with an object {storedGoal, query, expectedId} to measure true accuracy.",
                )
            }
            return@withContext result
        }

        val records = pairs.mapIndexed { index, pair ->
            SkillRecord(
                id = "bench-$index",
                goal = pair.storedGoal,
                appPackage = appPackage,
                keywords = SkillMatcher.keywords(pair.storedGoal),
                steps = emptyList(),
                toolChain = emptyList(),
                createdAt = 0L,
                lastUsedAt = 0L,
            )
        }
        val arr = JSONArray()
        var hits = 0
        var wrong = 0
        var noMatch = 0
        for ((index, pair) in pairs.withIndex()) {
            val match = SkillMatcher.best(pair.query, records, appPackage, config)
            val expected = records[index]
            val hit = match?.record?.id == expected.id
            if (hit) {
                hits++
            } else if (match != null) {
                wrong++
            } else {
                noMatch++
            }
            val detail = SkillMatcher.explain(pair.query, expected, appPackage, algorithm)
            arr.put(JSONObject().apply {
                put("storedGoal", pair.storedGoal)
                put("query", pair.query)
                put("expectedId", expected.id)
                put("expectedStatus", "synthetic")
                put("sameRecord", hit)
                put("hit", hit)
                put("falseHit", match != null && !hit)
                put("outcome", if (hit) "correct" else if (match != null) "wrong-record" else "no-match")
                put("score", match?.score ?: 0.0)
                put("expectedScore", detail.score)
                put("matchedGoal", match?.record?.goal ?: JSONObject.NULL)
                put("conceptScore", detail.conceptScore)
                put("bigramDice", detail.bigramDice)
                put("queryConcepts", JSONArray().also { a -> detail.queryConcepts.forEach { a.put(it) } })
                put("recordConcepts", JSONArray().also { a -> detail.recordConcepts.forEach { a.put(it) } })
            })
        }
        result.put("correctHits", hits)
        result.put("wrongMatches", wrong)
        result.put("falseHits", wrong)
        result.put("noMatches", noMatch)
        result.put("evaluatedPairs", pairs.size)
        result.put("hits", hits)
        result.put("hitRate", if (pairs.isEmpty()) 0.0 else hits.toDouble() / pairs.size)
        result.put("hitRateAll", if (pairs.isEmpty()) 0.0 else hits.toDouble() / pairs.size)
        result.put("pairs", arr)
        result
    }

    private fun parseBenchPairs(params: JSONObject): List<SkillBench.Pair>? {
        val arr = params.optJSONArray("pairs") ?: return null
        val out = mutableListOf<SkillBench.Pair>()
        for (i in 0 until arr.length()) {
            when (val item = arr.opt(i)) {
                is JSONArray -> {
                    val stored = item.optString(0, "").trim()
                    val query = item.optString(1, "").trim()
                    val expectedId = item.optString(2, "").trim().takeIf { it.isNotEmpty() }
                    if (stored.isNotEmpty() && query.isNotEmpty()) {
                        out.add(SkillBench.Pair(stored, query, expectedId))
                    }
                }
                is JSONObject -> {
                    val stored = item.optString("storedGoal", "")
                        .ifBlank { item.optString("goal", "") }
                        .trim()
                    val query = item.optString("query", "").trim()
                    val expectedId = item.optString("expectedId", "").trim().takeIf { it.isNotEmpty() }
                    if (stored.isNotEmpty() && query.isNotEmpty()) {
                        out.add(SkillBench.Pair(stored, query, expectedId))
                    }
                }
            }
        }
        return out.takeIf { it.isNotEmpty() }
    }

    /**
     * Ten realistic pairs: first half is the recorded goal, second half is a
     * different Chinese wording of the same intent. Kept in the build so the
     * acceptance number is reproducible on any install. `mode=store` treats
     * the first half as the expected record's goal; on a store without those
     * records the pair is reported as `expected-missing`, never as a hit.
     */
    private val BUILT_IN_BENCH_PAIRS: List<SkillBench.Pair> = listOf(
        SkillBench.Pair("看看手机存储都被什么占满了", "查询一下 App 私有目录的占用构成"),
        SkillBench.Pair("打开手电筒", "把闪光灯打开"),
        SkillBench.Pair("帮我把屏幕截个图", "截屏保存一下"),
        SkillBench.Pair("手机还剩多少电", "查一下电池电量"),
        SkillBench.Pair("清理一下没用的缓存", "把垃圾文件删掉释放空间"),
        SkillBench.Pair("帮我记一下明天要开会", "添加一个明天开会的提醒"),
        SkillBench.Pair("现在连着哪个WiFi", "查一下无线网络状态"),
        SkillBench.Pair("把音量调到一半", "声音小一点"),
        SkillBench.Pair("看一下今天的天气", "今天会下雨吗"),
        SkillBench.Pair("打开地图导航去公司", "帮我规划到公司的路线"),
    )

    /** `agent.audit.tail {limit?=20}` — the last N lines as raw JSON objects, oldest first. */
    suspend fun auditTail(context: Context, params: JSONObject): JSONArray = withContext(Dispatchers.IO) {
        AuditLog.tail(context, params.optInt("limit", 20))
    }

    /** `agent.guard.setEnforce {value: Bool}` — the opt-in enforcement switch. */
    suspend fun guardSetEnforce(context: Context, params: JSONObject): JSONObject {
        if (!params.has("value")) throw RPCException(-32602, "Missing 'value' param")
        Guard.setEnforce(context, params.optBoolean("value", false))
        return JSONObject().put("enforce", Guard.enforceEnabled(context))
    }

    /** `agent.guard.status` — switch state, both file paths, current sizes. */
    suspend fun guardStatus(context: Context): JSONObject = withContext(Dispatchers.IO) {
        JSONObject().apply {
            put("enforce", Guard.enforceEnabled(context))
            put("auditPath", AuditLog.auditFile(context)?.absolutePath ?: "")
            put("skillsPath", SkillStore.skillsFile(context)?.absolutePath ?: "")
            put("auditLines", AuditLog.lineCount(context))
            put("skillCount", SkillStore.load(context).size)
        }
    }
}

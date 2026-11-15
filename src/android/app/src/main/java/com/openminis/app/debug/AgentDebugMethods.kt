package com.openminis.app.debug

import android.content.Context
import com.openminis.app.agent.guard.AuditLog
import com.openminis.app.agent.guard.Guard
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
        val arr = JSONArray()
        for (record in SkillStore.load(context).sortedByDescending { it.lastUsedAt }.take(limit)) {
            arr.put(JSONObject().apply {
                put("id", record.id)
                put("goal", record.goal)
                put("appPackage", record.appPackage ?: JSONObject.NULL)
                put("successCount", record.successCount)
                put("lastUsedAt", record.lastUsedAt)
                put("stepCount", record.steps.size)
                // Schema 3 provenance: does this record know the actual
                // command and output, or only the old action summary?
                put("hasInput", record.steps.any { !it.input.isNullOrBlank() })
                put("hasOutput", record.steps.any { !it.output.isNullOrBlank() })
                put("hasConclusion", !record.conclusion.isNullOrBlank())
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
        record.toJson()
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
        val records = SkillStore.load(context)
        val match = SkillMatcher.best(goal, records, appPackage, config)
        val top = records.maxOfOrNull { SkillMatcher.score(goal, it, appPackage, config.algorithm) } ?: 0.0
        JSONObject().apply {
            put("hit", match != null)
            put("score", match?.score ?: top)
            put("threshold", config.threshold)
            put("algorithm", config.algorithm.wire)
            put("recordCount", records.size)
            put("matchedGoal", match?.record?.goal ?: JSONObject.NULL)
            put("matchedId", match?.record?.id ?: JSONObject.NULL)
            put("hint", match?.let { SkillStore.renderHint(it.record, goal) } ?: JSONObject.NULL)
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
     * as a miss. `mode=store` runs the same queries against the real
     * skills.json and reports the top score/goal (there is no ground truth
     * there, so `hit` only means "above threshold").
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
            val records = SkillStore.load(context)
            val arr = JSONArray()
            var above = 0
            for ((storedGoal, query) in pairs) {
                val match = SkillMatcher.best(query, records, appPackage, config)
                val top = records.maxOfOrNull {
                    SkillMatcher.score(query, it, appPackage, algorithm)
                } ?: 0.0
                if (match != null) above++
                arr.put(JSONObject().apply {
                    put("storedGoal", storedGoal)
                    put("query", query)
                    put("hit", match != null)
                    put("score", match?.score ?: top)
                    put("matchedGoal", match?.record?.goal ?: JSONObject.NULL)
                })
            }
            result.put("recordCount", records.size)
            result.put("hits", above)
            result.put("hitRate", if (pairs.isEmpty()) 0.0 else above.toDouble() / pairs.size)
            result.put("pairs", arr)
            return@withContext result
        }

        val records = pairs.mapIndexed { index, (storedGoal, _) ->
            SkillRecord(
                id = "bench-$index",
                goal = storedGoal,
                appPackage = appPackage,
                keywords = SkillMatcher.keywords(storedGoal),
                steps = emptyList(),
                toolChain = emptyList(),
                createdAt = 0L,
                lastUsedAt = 0L,
            )
        }
        val arr = JSONArray()
        var hits = 0
        for ((index, pair) in pairs.withIndex()) {
            val (storedGoal, query) = pair
            val match = SkillMatcher.best(query, records, appPackage, config)
            val expected = records[index]
            val hit = match?.record?.id == expected.id
            if (hit) hits++
            val detail = SkillMatcher.explain(query, expected, appPackage, algorithm)
            arr.put(JSONObject().apply {
                put("storedGoal", storedGoal)
                put("query", query)
                put("hit", hit)
                put("score", match?.score ?: 0.0)
                put("expectedScore", detail.score)
                put("matchedGoal", match?.record?.goal ?: JSONObject.NULL)
                put("conceptScore", detail.conceptScore)
                put("bigramDice", detail.bigramDice)
                put("queryConcepts", JSONArray().also { a -> detail.queryConcepts.forEach { a.put(it) } })
                put("recordConcepts", JSONArray().also { a -> detail.recordConcepts.forEach { a.put(it) } })
            })
        }
        result.put("hits", hits)
        result.put("hitRate", if (pairs.isEmpty()) 0.0 else hits.toDouble() / pairs.size)
        result.put("pairs", arr)
        result
    }

    private fun parseBenchPairs(params: JSONObject): List<Pair<String, String>>? {
        val arr = params.optJSONArray("pairs") ?: return null
        val out = mutableListOf<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val pair = arr.optJSONArray(i) ?: continue
            val stored = pair.optString(0, "").trim()
            val query = pair.optString(1, "").trim()
            if (stored.isNotEmpty() && query.isNotEmpty()) out.add(stored to query)
        }
        return out.takeIf { it.isNotEmpty() }
    }

    /**
     * Ten realistic pairs: first half is the recorded goal, second half is a
     * different Chinese wording of the same intent. Kept in the build so the
     * acceptance number is reproducible on any install.
     */
    private val BUILT_IN_BENCH_PAIRS: List<Pair<String, String>> = listOf(
        "看看手机存储都被什么占满了" to "查询一下 App 私有目录的占用构成",
        "打开手电筒" to "把闪光灯打开",
        "帮我把屏幕截个图" to "截屏保存一下",
        "手机还剩多少电" to "查一下电池电量",
        "清理一下没用的缓存" to "把垃圾文件删掉释放空间",
        "帮我记一下明天要开会" to "添加一个明天开会的提醒",
        "现在连着哪个WiFi" to "查一下无线网络状态",
        "把音量调到一半" to "声音小一点",
        "看一下今天的天气" to "今天会下雨吗",
        "打开地图导航去公司" to "帮我规划到公司的路线",
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

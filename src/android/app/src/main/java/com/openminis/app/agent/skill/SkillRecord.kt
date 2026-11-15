package com.openminis.app.agent.skill

import org.json.JSONArray
import org.json.JSONObject

/**
 * org.json's `optString` turns an explicit `JSONObject.NULL` into the literal
 * text "null"; treat that (and an empty string) as absent.
 */
internal fun JSONObject.optText(key: String): String? =
    optString(key, "").takeIf { it.isNotEmpty() && it != "null" }

/**
 * One step of a successful run, kept so a later run with a similar goal can be
 * told "last time this worked".
 *
 * [target] is a semantic locator (visible text / package name / path) — never
 * a raw coordinate, which would break on the next screen. [viewId] is the
 * accessibility view id when the call carried one. [summary] is the human line
 * shown in the injected hint (<= 80 chars).
 *
 * [input] and [output] carry the ACTUAL command and the key part of what it
 * printed. They are what makes a hint actionable: "run `du -sh …`, it printed
 * X, therefore Y". Both fields are optional so records written before schema 3
 * still decode (a v2 record simply has neither).
 */
data class SkillStep(
    val tool: String,
    val target: String? = null,
    val viewId: String? = null,
    val summary: String,
    val input: String? = null,
    val output: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("tool", tool)
        put("target", target ?: JSONObject.NULL)
        put("viewId", viewId ?: JSONObject.NULL)
        put("summary", summary)
        put("input", input ?: JSONObject.NULL)
        put("output", output ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(o: JSONObject): SkillStep? {
            val tool = o.optText("tool")?.trim().orEmpty()
            val summary = o.optText("summary")?.trim().orEmpty()
            if (tool.isEmpty() || summary.isEmpty()) return null
            return SkillStep(
                tool = tool,
                target = o.optText("target"),
                viewId = o.optText("viewId"),
                summary = summary,
                input = o.optText("input"),
                output = o.optText("output"),
            )
        }
    }
}

/**
 * A successful trajectory for one goal. `id` is sha1(goal) truncated to 16
 * hex chars, so re-running the same goal updates the record instead of
 * appending a duplicate.
 *
 * `keywords` holds the goal's char bigrams (first 12) for inspection; matching
 * itself recomputes features from [goal] so long goals are not penalised by
 * the 12-item cap.
 *
 * Schema 3 adds [conclusion] (what the successful run established) and the
 * per-step `input`/`output` fields. Schema-2 records load unchanged; the new
 * fields simply read as null. Saving after a schema-2 load writes schema 3 and
 * keeps every existing record.
 */
data class SkillRecord(
    val id: String,
    val goal: String,
    val appPackage: String?,
    val keywords: List<String>,
    val steps: List<SkillStep>,
    val toolChain: List<String>,
    val successCount: Int = 1,
    val createdAt: Long,
    val lastUsedAt: Long,
    val conclusion: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("goal", goal)
        put("appPackage", appPackage ?: JSONObject.NULL)
        put("keywords", JSONArray().also { arr -> keywords.forEach { arr.put(it) } })
        put("steps", JSONArray().also { arr -> steps.forEach { arr.put(it.toJson()) } })
        put("toolChain", JSONArray().also { arr -> toolChain.forEach { arr.put(it) } })
        put("successCount", successCount)
        put("createdAt", createdAt)
        put("lastUsedAt", lastUsedAt)
        put("conclusion", conclusion ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(o: JSONObject): SkillRecord? {
            val id = o.optText("id")?.trim().orEmpty()
            val goal = o.optText("goal")?.trim().orEmpty()
            if (id.isEmpty() || goal.isEmpty()) return null
            val steps = mutableListOf<SkillStep>()
            o.optJSONArray("steps")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { step -> SkillStep.fromJson(step)?.let(steps::add) }
                }
            }
            val keywords = mutableListOf<String>()
            o.optJSONArray("keywords")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optString(i, "").takeIf { it.isNotEmpty() }?.let(keywords::add)
                }
            }
            val toolChain = mutableListOf<String>()
            o.optJSONArray("toolChain")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optString(i, "").takeIf { it.isNotEmpty() }?.let(toolChain::add)
                }
            }
            val createdAt = o.optLong("createdAt", 0L)
            return SkillRecord(
                id = id,
                goal = goal,
                appPackage = o.optText("appPackage"),
                keywords = keywords,
                steps = steps,
                toolChain = toolChain,
                successCount = o.optInt("successCount", 1).coerceAtLeast(1),
                createdAt = createdAt,
                lastUsedAt = o.optLong("lastUsedAt", createdAt),
                conclusion = o.optText("conclusion"),
            )
        }
    }
}

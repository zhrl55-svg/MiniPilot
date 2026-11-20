package com.openminis.app.hub

import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-hub-receipt] The machine-readable receipt schema (`minis.receipt/1`).
 *
 * WHY: a receipt that only says "done, see the file" leaves the other side
 * parsing prose. The schema gives both sides the same fields to read:
 *
 *     {
 *       "schema":    "minis.receipt/1",
 *       "re":        "m-20261120-003",
 *       "status":    "working" | "done" | "blocked",
 *       "summary":   "one line, meant to be read by a program",
 *       "artifacts": [{"path": "...", "sha256": "...", "lines": 12}],
 *       "needs":     ["what the other side has to do next"]
 *     }
 *
 * It rides ALONGSIDE the human-readable text: the jsonl line keeps its
 * `message`, the `notify` action keeps its title/body. The schema is an
 * addition, never a replacement — a reader that only knows the old fields
 * still reads the old fields.
 *
 * The Python twin is `tools/hub/receipt.py`; the two must agree on the field
 * names, so each side has its own test.
 */
data class HubReceipt(
    val re: String,
    val status: String,
    val summary: String = "",
    val artifacts: List<HubArtifact> = emptyList(),
    val needs: List<String> = emptyList(),
) {

    fun toJson(): JSONObject {
        val list = JSONArray()
        artifacts.forEach { list.put(it.toJson()) }
        return JSONObject()
            .put("schema", SCHEMA)
            .put("re", re)
            .put("status", status)
            .put("summary", summary)
            .put("artifacts", list)
            .put("needs", JSONArray(needs))
    }

    companion object {
        const val SCHEMA = "minis.receipt/1"

        /** The three states a receipt can be in. */
        val STATUSES = listOf("working", "done", "blocked")

        /**
         * Every problem with [json], empty when it is a valid receipt.
         *
         * Unknown fields are ignored: the schema may grow, and an old reader
         * must not reject a newer receipt over a field it does not know.
         */
        fun problems(json: JSONObject?): List<String> {
            if (json == null) return listOf("receipt is not an object")
            val problems = mutableListOf<String>()
            if (json.optString("re").isBlank()) problems.add("re is missing")
            val status = json.optString("status")
            if (status !in STATUSES) {
                problems.add("status must be one of ${STATUSES.joinToString(", ")}")
            }
            if (json.has("summary") && json.opt("summary") !is String) {
                problems.add("summary must be a string")
            }
            val artifacts = json.optJSONArray("artifacts")
            if (json.has("artifacts") && artifacts == null) {
                problems.add("artifacts must be a list")
            } else if (artifacts != null) {
                for (index in 0 until artifacts.length()) {
                    val item = artifacts.optJSONObject(index)
                    if (item == null) {
                        problems.add("artifacts[$index] is not an object")
                        continue
                    }
                    if (item.optString("path").isBlank()) {
                        problems.add("artifacts[$index].path is missing")
                    }
                    val digest = item.optString("sha256")
                    if (digest.isNotEmpty() && !SHA256.matches(digest)) {
                        problems.add("artifacts[$index].sha256 is not a sha256 hex digest")
                    }
                    val lines = item.opt("lines")
                    if (lines != null && lines !is Int) {
                        problems.add("artifacts[$index].lines must be a non-negative integer")
                    }
                }
            }
            val needs = json.optJSONArray("needs")
            if (json.has("needs") && needs == null) {
                problems.add("needs must be a list")
            } else if (needs != null) {
                for (index in 0 until needs.length()) {
                    if (needs.opt(index) !is String) {
                        problems.add("needs[$index] must be a string")
                    }
                }
            }
            return problems
        }

        /** The receipt, or null when the object does not hold one. */
        fun fromJson(json: JSONObject?): HubReceipt? {
            if (problems(json).isNotEmpty()) return null
            val source = json ?: return null
            val artifacts = mutableListOf<HubArtifact>()
            source.optJSONArray("artifacts")?.let { list ->
                for (index in 0 until list.length()) {
                    val item = list.optJSONObject(index) ?: continue
                    artifacts.add(
                        HubArtifact(
                            path = item.optString("path"),
                            sha256 = item.optString("sha256"),
                            lines = item.optInt("lines", -1),
                        ),
                    )
                }
            }
            val needs = mutableListOf<String>()
            source.optJSONArray("needs")?.let { list ->
                for (index in 0 until list.length()) {
                    list.optString(index).takeIf { it.isNotEmpty() }?.let { needs.add(it) }
                }
            }
            return HubReceipt(
                re = source.optString("re"),
                status = source.optString("status"),
                summary = source.optString("summary"),
                artifacts = artifacts,
                needs = needs,
            )
        }

        private val SHA256 = Regex("^[0-9a-f]{64}$")
    }
}

/** One file a receipt points at. [lines] is -1 when the count is unknown. */
data class HubArtifact(
    val path: String,
    val sha256: String = "",
    val lines: Int = -1,
) {
    fun toJson(): JSONObject {
        val json = JSONObject().put("path", path)
        if (sha256.isNotEmpty()) json.put("sha256", sha256)
        if (lines >= 0) json.put("lines", lines)
        return json
    }
}

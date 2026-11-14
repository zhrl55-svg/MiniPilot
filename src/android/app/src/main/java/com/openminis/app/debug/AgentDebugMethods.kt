package com.openminis.app.debug

import android.content.Context
import com.openminis.app.agent.guard.AuditLog
import com.openminis.app.agent.guard.Guard
import com.openminis.app.agent.skill.SkillStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * `agent.*` RPC surface for the skill store and the guard audit — the
 * acceptance hooks for both capabilities. Read-only except
 * `agent.guard.setEnforce` (the opt-in switch; false by default) and
 * `agent.skills.clear`.
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
                put("toolChain", JSONArray().also { chain -> record.toolChain.forEach { chain.put(it) } })
            })
        }
        arr
    }

    /** `agent.skills.clear {}` — deletes the store, returns how many records went. */
    suspend fun skillsClear(context: Context, params: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        JSONObject().put("cleared", SkillStore.clear(context))
    }

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

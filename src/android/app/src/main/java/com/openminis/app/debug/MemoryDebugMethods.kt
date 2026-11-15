package com.openminis.app.debug

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.memory.MemoryIndexConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * `memory.*` RPC surface for the Phase A acceptance pass.
 *
 * These methods read and rebuild the same in-memory index used by the
 * `memory_get` tool, so a harness can compare the Kotlin ranking with the
 * Python prototype without going through a chat turn.
 */
internal object MemoryDebugMethods {

    private fun repository(context: Context): MemoryRepository {
        val app = context.applicationContext as? MinisApp
            ?: throw RPCException(-32603, "MinisApp context unavailable")
        return try {
            app.memoryRepository
        } catch (_: UninitializedPropertyAccessException) {
            throw RPCException(-32603, "Memory repository not initialized")
        }
    }

    suspend fun indexStatus(context: Context): JSONObject = withContext(Dispatchers.IO) {
        val status = repository(context).indexStatus()
        JSONObject().apply {
            put("entryCount", status.entryCount)
            put("globalCount", status.globalCount)
            put("userCount", status.userCount)
            put("dailyCount", status.dailyCount)
            put("chars", status.chars)
            put("buildMs", status.buildMs)
            put("lastQueryMs", status.lastQueryMs)
            put("fingerprint", status.fingerprint)
        }
    }

    suspend fun indexRebuild(context: Context): JSONObject = withContext(Dispatchers.IO) {
        val repo = repository(context)
        repo.rebuildIndex()
        val status = repo.indexStatus()
        JSONObject().apply {
            put("entryCount", status.entryCount)
            put("globalCount", status.globalCount)
            put("userCount", status.userCount)
            put("dailyCount", status.dailyCount)
            put("chars", status.chars)
            put("buildMs", status.buildMs)
            put("fingerprint", status.fingerprint)
        }
    }

    suspend fun search(context: Context, params: JSONObject): JSONArray = withContext(Dispatchers.IO) {
        val query = params.optString("query", "").trim()
        if (query.isEmpty()) throw RPCException(-32602, "Missing 'query' param")
        val scope = params.optString("scope", "all")
        val limit = params.optInt("limit", MemoryIndexConfig.TOP_K)
            .coerceIn(1, MemoryIndexConfig.MAX_LIMIT)

        val result = repository(context).searchIndex(query, scope, limit)
        val arr = JSONArray()
        for (hit in result.hits) {
            arr.put(JSONObject().apply {
                put("file", hit.entry.file)
                put("title", hit.entry.title)
                put("kind", hit.entry.kind.name.lowercase())
                put("date", hit.entry.date ?: JSONObject.NULL)
                put("score", hit.score)
                put("bm25", hit.bm25)
                put("jaccard", hit.jaccard)
                put("entity", hit.entity)
                put("tags", JSONArray().also { tags -> hit.entry.tags.sorted().forEach { tags.put(it) } })
            })
        }
        arr
    }
}

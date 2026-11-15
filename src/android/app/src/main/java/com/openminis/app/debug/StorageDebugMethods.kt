package com.openminis.app.debug

import android.content.Context
import com.openminis.app.storage.PrivateStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * `storage.*` RPC surface (P0 storage acceptance):
 * one command for the private-directory composition, one for a preview, one
 * for a controlled cleanup, one for the audit trail.
 *
 * The safety policy lives in [PrivateStorage]; these methods only translate
 * params and shapes. Deletion always runs on [Dispatchers.IO].
 */
internal object StorageDebugMethods {

    /** `storage.breakdown` — the composition of every app-private root. */
    suspend fun breakdown(context: Context, params: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val result = PrivateStorage.scan(
            context = context,
            maxDepth = params.optInt("maxDepth", 3),
            maxEntries = params.optInt("maxEntries", 120),
            minBytes = params.optLong("minBytes", 1L shl 20),
            includeFiles = params.optBoolean("includeFiles", true),
            maxFiles = params.optInt("maxFiles", 400_000),
        )
        PrivateStorage.scanToJson(result)
    }

    /** `storage.cleanupPreview` — evaluate paths, delete nothing. */
    suspend fun cleanupPreview(context: Context, params: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val report = PrivateStorage.cleanup(
            context = context,
            requested = stringList(params, "paths"),
            all = params.optBoolean("all", false),
            confirm = false,
            minAgeMs = minAgeMs(params),
        )
        PrivateStorage.reportToJson(report)
    }

    /** `storage.cleanup` — preview by default, delete only with confirm=true. */
    suspend fun cleanup(context: Context, params: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val paths = stringList(params, "paths")
        val all = params.optBoolean("all", false)
        if ((paths == null || paths.isEmpty()) && !all) {
            throw RPCException(-32602, "Provide 'paths' or set 'all' to true")
        }
        val report = PrivateStorage.cleanup(
            context = context,
            requested = paths,
            all = all,
            confirm = params.optBoolean("confirm", false),
            minAgeMs = minAgeMs(params),
        )
        PrivateStorage.reportToJson(report)
    }

    /** `storage.cleanupAudit` — the last N audit lines, oldest first. */
    suspend fun cleanupAudit(context: Context, params: JSONObject): JSONArray = withContext(Dispatchers.IO) {
        val limit = params.optInt("limit", 20).coerceIn(1, 500)
        JSONArray().also { arr ->
            PrivateStorage.auditTail(context, limit).forEach { arr.put(it) }
        }
    }

    private fun minAgeMs(params: JSONObject): Long {
        val minutes = params.optInt("minAgeMinutes", 60).coerceIn(0, 60 * 24 * 30)
        return minutes * 60_000L
    }

    private fun stringList(params: JSONObject, key: String): List<String>? {
        val arr = params.optJSONArray(key) ?: return null
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            arr.optString(i, "").trim().takeIf { it.isNotEmpty() }?.let(out::add)
        }
        return out
    }
}

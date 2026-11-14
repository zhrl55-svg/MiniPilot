package com.openminis.app.agent.guard

import android.content.Context
import com.openminis.app.logging.AppLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Append-only audit trail of risky tool calls.
 *
 * File: `getExternalFilesDir(null)/agent/audit.jsonl` (external storage so the
 * debug acceptance harness can read it over adb). One JSON object per line:
 *
 * ```
 * {"ts":1763…,"iso":"2026-11-14T19:52:03+08:00","sessionId":"…","tool":"shell_execute",
 *  "risk":"IRREVERSIBLE","rule":"rm -rf","evidence":"rm -rf /tmp/x","decision":"allowed"}
 * ```
 *
 * The user's task text is never written here — only the action itself.
 * Rotation: when the file would grow past [MAX_BYTES] it is renamed to
 * `audit.1.jsonl` (replacing the previous one) and a fresh file is started.
 * Every failure is swallowed: auditing must never affect tool execution.
 */
object AuditLog {
    private const val TAG = "GuardAudit"

    const val FILE_NAME = "audit.jsonl"
    const val ROTATED_FILE_NAME = "audit.1.jsonl"
    const val MAX_BYTES = 5L * 1024 * 1024

    private val lock = Any()

    fun auditFile(context: Context): File? =
        context.getExternalFilesDir(null)?.let { File(File(it, "agent"), FILE_NAME) }

    /** Append one record. Fields are exactly the documented ones. */
    fun append(
        context: Context,
        sessionId: String?,
        tool: String,
        risk: RiskLevel,
        rule: String,
        evidence: String,
        decision: String,
    ) {
        val now = System.currentTimeMillis()
        val entry = JSONObject().apply {
            put("ts", now)
            put("iso", iso(now))
            put("sessionId", sessionId.orEmpty())
            put("tool", tool)
            put("risk", risk.name)
            put("rule", rule)
            put("evidence", evidence)
            put("decision", decision)
        }
        synchronized(lock) {
            runCatching {
                val f = auditFile(context) ?: return@runCatching
                f.parentFile?.mkdirs()
                if (f.exists() && f.length() > MAX_BYTES) rotate(f)
                f.appendText(entry.toString() + "\n", Charsets.UTF_8)
            }.onFailure { t ->
                AppLogger.warning(TAG, "audit append failed: ${t.javaClass.simpleName}")
            }
        }
    }

    private fun rotate(f: File) {
        val rotated = File(f.parentFile, ROTATED_FILE_NAME)
        runCatching { if (rotated.exists()) rotated.delete() }
        // renameTo failing is acceptable: the next append then rotates again;
        // the file simply keeps growing rather than losing lines.
        runCatching { f.renameTo(rotated) }
    }

    /** The last [limit] parseable records, oldest first. */
    fun tail(context: Context, limit: Int): JSONArray {
        val out = JSONArray()
        val n = limit.coerceIn(1, 1000)
        runCatching {
            val f = auditFile(context) ?: return@runCatching
            if (!f.exists()) return@runCatching
            val lines = f.readLines(Charsets.UTF_8)
            for (line in lines.takeLast(n)) {
                if (line.isBlank()) continue
                val obj = runCatching { JSONObject(line) }.getOrNull() ?: continue
                out.put(obj)
            }
        }.onFailure { t ->
            AppLogger.warning(TAG, "audit read failed: ${t.javaClass.simpleName}")
        }
        return out
    }

    /** Number of lines currently in the live file (0 when absent/unreadable). */
    fun lineCount(context: Context): Int = runCatching {
        val f = auditFile(context) ?: return@runCatching 0
        if (!f.exists()) 0 else f.useLines { it.count() }
    }.getOrDefault(0)

    private fun iso(ts: Long): String = runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date(ts))
    }.getOrDefault("")
}

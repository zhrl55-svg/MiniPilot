package com.openminis.app.accessibility

import android.content.Context
import com.openminis.app.logging.AppLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Durable journal of UI actions performed through `android-a11y-cli`.
 *
 * The chat transcript already stores tool calls, but it does not know what a
 * `tap node a3f2` actually hit. After a long task is interrupted (turn limit,
 * process death, user stop) the next turn needs a cheap way to recover the
 * thread: which app, which target, whether the screen changed. This journal
 * records exactly that, and nothing else.
 *
 * Privacy rules:
 *  - typed text is never stored, only its length (`chars`);
 *  - password fields are stored as `target.password=true` with an empty label;
 *  - any target label matching a verification/credential word is dropped
 *    entirely rather than partially masked.
 *
 * File: `<external-files>/a11y/journal.jsonl` (falls back to filesDir). It is
 * a bounded append-only log: at most [MAX_RECORDS] records, compacted when the
 * file grows past [MAX_BYTES]. Read with
 * `android-a11y-cli journal tail --limit N`, clear with `journal clear`.
 */
object A11yJournal {
    private const val TAG = "A11yJournal"
    private const val DIR = "a11y"
    private const val FILE = "journal.jsonl"
    private const val MAX_RECORDS = 200
    private const val MAX_BYTES = 96 * 1024

    private val lock = Any()

    /**
     * Target labels that must never reach the journal. Matches the anti-replay
     * vocabulary already used by SkillStore, plus the Chinese UI words.
     */
    private val SENSITIVE_TARGET = Regex(
        "密码|验证码|口令|支付密码|passcode|(?<![a-z])otp(?![a-z])|(?<![a-z])pin(?![a-z])|token|secret",
        RegexOption.IGNORE_CASE,
    )

    fun append(
        context: Context,
        sessionId: String?,
        action: String,
        packageName: String?,
        target: JSONObject?,
        via: String?,
        changed: Boolean?,
        chars: Int? = null,
    ) {
        val label = target?.optString("label").orEmpty()
        val password = target?.optBoolean("password") == true
        if (password || SENSITIVE_TARGET.containsMatchIn(label)) return

        val record = JSONObject().apply {
            put("ts", System.currentTimeMillis())
            sessionId?.takeIf { it.isNotBlank() }?.let { put("session", it.take(8)) }
            put("action", action)
            packageName?.takeIf { it.isNotBlank() }?.let { put("package", it) }
            target?.let { put("target", it) }
            via?.takeIf { it.isNotBlank() }?.let { put("via", it) }
            changed?.let { put("changed", it) }
            chars?.let { put("chars", it) }
        }

        synchronized(lock) {
            try {
                val file = journalFile(context) ?: return
                val dir = file.parentFile
                if (dir != null && !dir.exists()) dir.mkdirs()
                if (file.exists() && file.length() > MAX_BYTES) compact(file)
                file.appendText(record.toString() + "\n")
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "append failed: ${t.message}")
            }
        }
    }

    fun tail(context: Context, limit: Int): JSONArray = synchronized(lock) {
        val arr = JSONArray()
        try {
            val file = journalFile(context)
            if (file != null && file.exists()) {
                val lines = file.readLines().filter { it.isNotBlank() }
                val start = (lines.size - limit.coerceAtLeast(1)).coerceAtLeast(0)
                for (i in start until lines.size) {
                    try {
                        arr.put(JSONObject(lines[i]))
                    } catch (_: Throwable) {
                    }
                }
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "tail failed: ${t.message}")
        }
        arr
    }

    fun clear(context: Context): Boolean = synchronized(lock) {
        try {
            journalFile(context)?.delete() ?: false
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "clear failed: ${t.message}")
            false
        }
    }

    private fun compact(file: File) {
        val keep = file.readLines()
            .filter { it.isNotBlank() }
            .takeLast(MAX_RECORDS / 2)
        file.writeText(keep.joinToString("\n") + if (keep.isNotEmpty()) "\n" else "")
    }

    private fun journalFile(context: Context): File? {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(File(base, DIR), FILE)
    }
}

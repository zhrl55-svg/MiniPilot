package com.openminis.app.sandbox.offload

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.ShizukuManager

/**
 * [T-android-settings-direct-write] One verified writer for Android system
 * settings, with a Shizuku tier and a permission-based direct tier.
 *
 * Historically every in-app "change a system setting" path went through
 * Shizuku (`settings put ...`). When the Shizuku user service was stopped the
 * whole capability vanished, and callers that only inspected the process exit
 * code reported success while nothing had actually changed — the 2026-11-23
 * power-save incident (readback empty, still reported success).
 *
 * This bridge adds a second tier and verifies every write:
 *
 *   1. Shizuku shell   — preferred, identical to `adb shell settings put`.
 *   2. Direct write    — `Settings.Global/Secure/System.putString` using the
 *                        `WRITE_SECURE_SETTINGS` permission, granted once with
 *                        `adb shell pm grant <pkg> android.permission.WRITE_SECURE_SETTINGS`
 *                        and declared in AndroidManifest.xml. Needs no root,
 *                        no Shizuku and no accessibility service.
 *
 * A write is only reported as [WriteResult.ok] when the stored value really
 * equals the requested value after a readback. The readback is retried a few
 * times (settings propagation is asynchronous), and an empty or mismatched
 * value after the retries is an error, never a silent success.
 */
object SystemSettingsBridge {

    private const val TAG = "SystemSettingsBridge"

    /** Readback polls before declaring a write failed (settings are async). */
    private const val READBACK_ATTEMPTS = 3
    private const val READBACK_INTERVAL_MS = 150L

    private val NAMESPACES = setOf("global", "secure", "system")

    enum class Backend { SHIZUKU, DIRECT }

    data class ReadResult(val value: String?, val backend: Backend)

    data class WriteResult(
        val ok: Boolean,
        val backend: Backend?,
        val expected: String,
        val actual: String?,
        val error: String? = null,
    )

    fun isValidNamespace(ns: String?): Boolean = ns in NAMESPACES

    /** True when the app holds WRITE_SECURE_SETTINGS (granted via `pm grant`). */
    fun directWritable(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Read a setting. Prefers a fresh shell read when Shizuku is ready (avoids
     * the app-side NameValueCache going stale after a shell write); otherwise
     * reads directly, which needs no permission.
     */
    fun read(context: Context, ns: String, key: String): ReadResult {
        if (ShizukuManager.isReady()) {
            shellGet(ns, key)?.let { return ReadResult(it, Backend.SHIZUKU) }
        }
        return ReadResult(getDirect(context, ns, key), Backend.DIRECT)
    }

    /**
     * Write [value] to settings `[ns]/[key]`, preferring Shizuku and falling
     * back to a direct WRITE_SECURE_SETTINGS write, then verify by readback.
     */
    fun write(context: Context, ns: String, key: String, value: String): WriteResult {
        requireValid(ns, key)
        val wanted = value.trim()

        // Tier 1 — Shizuku shell.
        if (ShizukuManager.isReady()) {
            val put = ShizukuManager.runProcess(arrayOf("settings", "put", ns, key, wanted))
            if (put.exitCode == 0) {
                val actual = readbackUntil(wanted) { shellGet(ns, key) }
                if (actual == wanted) return WriteResult(true, Backend.SHIZUKU, wanted, actual)
                AppLogger.info(TAG, "shizuku write $ns/$key readback='$actual' != '$wanted'; trying direct")
            } else {
                AppLogger.info(TAG, "shizuku put $ns/$key exit=${put.exitCode}; trying direct")
            }
        }

        // Tier 2 — direct permission-based write.
        if (directWritable(context)) {
            val putOk = try {
                putDirect(context, ns, key, wanted)
            } catch (se: SecurityException) {
                return WriteResult(
                    false, Backend.DIRECT, wanted, null,
                    "WRITE_SECURE_SETTINGS denied for $ns/$key: ${se.message}",
                )
            }
            val actual = readbackUntil(wanted) { getDirect(context, ns, key) }
            val ok = putOk && actual == wanted
            return WriteResult(
                ok, Backend.DIRECT, wanted, actual,
                if (ok) null else "readback '$actual' did not match requested '$wanted' (put=$putOk)",
            )
        }

        return WriteResult(
            false, null, wanted, null,
            "No writer available: Shizuku is not running and WRITE_SECURE_SETTINGS is not granted. " +
                "Run: adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS",
        )
    }

    /** Delete a setting (`put … null`) and verify the readback is empty. */
    fun delete(context: Context, ns: String, key: String): WriteResult {
        requireValid(ns, key)

        if (ShizukuManager.isReady()) {
            val r = ShizukuManager.runProcess(arrayOf("settings", "delete", ns, key))
            if (r.exitCode == 0) {
                val actual = readbackUntil(null) { shellGet(ns, key) }
                if (actual.isNullOrEmpty() || actual == "null") {
                    return WriteResult(true, Backend.SHIZUKU, "", actual)
                }
            }
        }

        if (directWritable(context)) {
            val ok = try {
                putDirect(context, ns, key, null)
            } catch (se: SecurityException) {
                return WriteResult(false, Backend.DIRECT, "", null, "WRITE_SECURE_SETTINGS denied: ${se.message}")
            }
            val actual = readbackUntil(null) { getDirect(context, ns, key) }
            val cleared = actual.isNullOrEmpty() || actual == "null"
            return WriteResult(
                ok && cleared, Backend.DIRECT, "", actual,
                if (ok && cleared) null else "delete readback '$actual' was not empty",
            )
        }

        return WriteResult(false, null, "", null, "No writer available (Shizuku down, WRITE_SECURE_SETTINGS not granted)")
    }

    /**
     * List a whole namespace. Uses the shell when Shizuku is ready, otherwise a
     * direct provider query (readable without permission). Returns null when
     * neither path worked.
     */
    fun list(context: Context, ns: String, filter: String? = null): Map<String, String>? {
        if (!isValidNamespace(ns)) return null

        if (ShizukuManager.isReady()) {
            val r = ShizukuManager.runProcess(arrayOf("settings", "list", ns), timeoutMs = 8_000)
            if (r.exitCode == 0) {
                return r.stdout.lineSequence().mapNotNull { line ->
                    val eq = line.indexOf('=')
                    if (eq <= 0) null else line.substring(0, eq) to line.substring(eq + 1)
                }.filter { filter == null || it.first.contains(filter) }.toMap()
            }
        }

        val uri = when (ns) {
            "global" -> Settings.Global.CONTENT_URI
            "secure" -> Settings.Secure.CONTENT_URI
            "system" -> Settings.System.CONTENT_URI
            else -> return null
        }
        return try {
            val out = LinkedHashMap<String, String>()
            context.contentResolver.query(
                uri,
                arrayOf(Settings.NameValueTable.NAME, Settings.NameValueTable.VALUE),
                null, null, null,
            )?.use { c ->
                val nameIdx = c.getColumnIndex(Settings.NameValueTable.NAME)
                val valueIdx = c.getColumnIndex(Settings.NameValueTable.VALUE)
                while (c.moveToNext()) {
                    val k = c.getString(nameIdx) ?: continue
                    if (filter != null && !k.contains(filter)) continue
                    out[k] = c.getString(valueIdx) ?: ""
                }
            }
            out
        } catch (_: Throwable) {
            null
        }
    }

    private fun shellGet(ns: String, key: String): String? {
        val g = ShizukuManager.runProcess(arrayOf("settings", "get", ns, key))
        return if (g.exitCode == 0) g.stdout.trim() else null
    }

    /**
     * Poll [reader] until it returns [expected] (or [READBACK_ATTEMPTS] runs
     * out) and return the last observed value. Settings writes propagate
     * asynchronously, so a single immediate read can be a false failure.
     */
    private fun readbackUntil(expected: String?, reader: () -> String?): String? {
        var last: String? = null
        var i = 0
        while (i < READBACK_ATTEMPTS) {
            last = reader()
            if (last == expected) return last
            if (i < READBACK_ATTEMPTS - 1) {
                try {
                    Thread.sleep(READBACK_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            i++
        }
        return last
    }

    private fun requireValid(ns: String, key: String) {
        require(isValidNamespace(ns)) { "invalid namespace '$ns' (global|secure|system)" }
        require(key.isNotBlank()) { "empty key" }
    }

    private fun putDirect(context: Context, ns: String, key: String, value: String?): Boolean = when (ns) {
        "global" -> Settings.Global.putString(context.contentResolver, key, value)
        "secure" -> Settings.Secure.putString(context.contentResolver, key, value)
        "system" -> Settings.System.putString(context.contentResolver, key, value)
        else -> false
    }

    private fun getDirect(context: Context, ns: String, key: String): String? = try {
        when (ns) {
            "global" -> Settings.Global.getString(context.contentResolver, key)
            "secure" -> Settings.Secure.getString(context.contentResolver, key)
            "system" -> Settings.System.getString(context.contentResolver, key)
            else -> null
        }
    } catch (_: Throwable) {
        null
    }
}

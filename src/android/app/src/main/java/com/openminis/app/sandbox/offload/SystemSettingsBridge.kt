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
 * This bridge adds two more tiers and verifies every write:
 *
 *   1. Shizuku shell   — preferred, identical to `adb shell settings put`.
 *   2. Direct write    — `Settings.Global/Secure/System.putString` using the
 *                        `WRITE_SECURE_SETTINGS` permission, granted once with
 *                        `adb shell pm grant <pkg> android.permission.WRITE_SECURE_SETTINGS`
 *                        and declared in AndroidManifest.xml. Needs no root,
 *                        no Shizuku and no accessibility service.
 *   3. Shell helper    — [ShellSettingsHelper], a detached uid=shell loop the
 *                        app spawns while Shizuku is available. Only needed for
 *                        *non-public* `Settings.System` keys (`SmartModeStatus`
 *                        and friends): `SettingsProvider` rejects those for any
 *                        app-uid caller no matter what permission is granted —
 *                        the restricted-mutation check sits behind the
 *                        WRITE_SECURE_SETTINGS gate. See the helper's docs.
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

    enum class Backend { SHIZUKU, DIRECT, HELPER }

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
     * Write [value] to settings `[ns]/[key]`, preferring Shizuku, then a direct
     * WRITE_SECURE_SETTINGS write, then the detached shell helper (needed for
     * non-public `Settings.System` keys), and verify by readback.
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
        var directError: String? = null
        var platformRestricted = false
        if (directWritable(context)) {
            val putOk = try {
                putDirect(context, ns, key, wanted)
            } catch (se: SecurityException) {
                directError = "WRITE_SECURE_SETTINGS denied for $ns/$key: ${se.message}"
                false
            } catch (re: RuntimeException) {
                // SettingsProvider throws IllegalArgumentException for
                // non-public Settings.System keys — a platform rule that no
                // permission or appop can lift (see ShellSettingsHelper).
                platformRestricted = isPlatformRestriction(re)
                directError = "direct write rejected for $ns/$key: ${re.message}"
                false
            }
            if (directError == null) {
                val actual = readbackUntil(wanted) { getDirect(context, ns, key) }
                if (putOk && actual == wanted) {
                    return WriteResult(true, Backend.DIRECT, wanted, actual)
                }
                directError = "readback '$actual' did not match requested '$wanted' (put=$putOk)"
            }
        } else {
            directError = "WRITE_SECURE_SETTINGS is not granted"
        }

        // Tier 3 — detached shell helper (only useful for restricted system keys,
        // but harmless as a fallback for any failed write).
        helperWrite(context, ns, key, wanted)?.let { return it }

        return WriteResult(
            false, null, wanted, null,
            failureHint(context, ns, key, directError, platformRestricted),
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

        var directError: String? = null
        var platformRestricted = false
        if (directWritable(context)) {
            val ok = try {
                putDirect(context, ns, key, null)
            } catch (se: SecurityException) {
                directError = "WRITE_SECURE_SETTINGS denied: ${se.message}"
                false
            } catch (re: RuntimeException) {
                platformRestricted = isPlatformRestriction(re)
                directError = "direct delete rejected for $ns/$key: ${re.message}"
                false
            }
            if (directError == null) {
                val actual = readbackUntil(null) { getDirect(context, ns, key) }
                val cleared = actual.isNullOrEmpty() || actual == "null"
                if (ok && cleared) return WriteResult(true, Backend.DIRECT, "", actual)
                directError = "delete readback '$actual' was not empty"
            }
        } else {
            directError = "WRITE_SECURE_SETTINGS is not granted"
        }

        helperWrite(context, ns, key, null)?.let { return it }

        return WriteResult(false, null, "", null, failureHint(context, ns, key, directError, platformRestricted))
    }

    /**
     * Tier 3. Returns null when the helper is unavailable (so the caller can
     * report the Shizuku/direct error), otherwise the verified result.
     * Seeding is deliberately NOT attempted here: a live helper is enough, and
     * spawning requires Shizuku — which tier 1 would already have used.
     */
    private fun helperWrite(context: Context, ns: String, key: String, value: String?): WriteResult? {
        if (!ShellSettingsHelper.isAlive(context)) return null
        val op = if (value == null) "del" else "put"
        val accepted = ShellSettingsHelper.exec(context, op, ns, key, value ?: "") ?: return null
        if (!accepted) {
            return WriteResult(
                false, Backend.HELPER, value ?: "", null,
                "helper rejected $op $ns/$key (validation or settings exit code)",
            )
        }
        val actual = readbackUntil(value) { getDirect(context, ns, key) }
        val ok = if (value == null) {
            actual.isNullOrEmpty() || actual == "null"
        } else {
            actual == value
        }
        return WriteResult(
            ok, Backend.HELPER, value ?: "", actual,
            if (ok) null else "helper readback '$actual' did not match '${value ?: "<deleted>"}'",
        )
    }

    /**
     * SettingsProvider throws this when a non system/shell/root, non-privileged
     * caller mutates a `Settings.System` key outside `Settings.System.PUBLIC_SETTINGS`.
     * It is a platform rule, not a permission problem.
     */
    internal fun isPlatformRestriction(t: Throwable): Boolean {
        val m = t.message ?: return false
        return m.contains("You cannot keep your settings in the secure settings") ||
            m.contains("You cannot change private secure settings") ||
            m.contains("You cannot delete system defined secure settings")
    }

    private fun failureHint(
        context: Context,
        ns: String,
        key: String,
        directError: String?,
        platformRestricted: Boolean,
    ): String {
        val detail = directError?.let { " ($it)" } ?: ""
        if (ns == "system" && platformRestricted) {
            return "系统键 $key 属于非公开的 Settings.System，Android SettingsProvider 只允许 " +
                "system/shell/root 或特权应用写入；WRITE_SECURE_SETTINGS 和 appops 都解不开这道限制，不是权限问题。$detail " +
                "当前 Shizuku 未运行、settings 助手也未常驻。启动一次 Shizuku 即可自动注入助手" +
                "（重启手机后需再启动一次），或在系统设置的「性能模式」页手动切换。"
        }
        return "No writer available for $ns/$key$detail. Shizuku is not running and the " +
            "settings helper is not alive. Start Shizuku once to seed the helper, then retry."
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

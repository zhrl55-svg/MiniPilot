package com.openminis.app.sandbox.offload

import android.content.Context
import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.ShizukuManager
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

/**
 * [T-android-settings-shell-helper] A narrow settings writer that keeps working
 * after the Shizuku server is stopped.
 *
 * Why this exists: `SettingsProvider` refuses non-public `Settings.System`
 * writes from any non system/shell/root, non-privileged caller
 * (`enforceRestrictedSystemSettingsMutationForCallingPackage`,
 * "You cannot keep your settings in the secure settings."). That check runs
 * *after* the `WRITE_SECURE_SETTINGS` permission gate, so no permission and no
 * appop can unlock a key such as `SmartModeStatus` — measured on the ELS-AN00
 * (SHIZUKU down, `WRITE_SECURE_SETTINGS granted=true`, `system` public keys
 * write, `SmartModeStatus` throws). The write therefore has to happen from an
 * uid=2000 (shell) process.
 *
 * Shizuku is one such process but dies on "stop". This helper is a second,
 * detached one: while Shizuku is READY the app spawns
 * `setsid sh helper.sh` as shell uid, which escapes Shizuku's process group and
 * keeps running afterwards. The helper understands exactly one thing —
 * `settings put/delete global|secure|system <key> <value>` — with a token and
 * strict charset validation; it cannot run arbitrary commands. It exits when
 * the token file is deleted (or the app is uninstalled, which removes the
 * directory) and is re-seeded automatically the next time the app starts with
 * Shizuku available. A reboot loses it until Shizuku (or an `adb shell` seed)
 * runs again.
 *
 * Transport: request/response files in the app's own external files dir
 * (`Android/data/<pkg>/files/settings-helper`), which both the app and shell
 * uid can read and write. No sockets, no exposed port.
 */
object ShellSettingsHelper {

    private const val TAG = "ShellSettingsHelper"
    private const val DIR_NAME = "settings-helper"

    /** Per-request wait; the caller verifies by readback afterwards. */
    private const val EXEC_TIMEOUT_MS = 2_500L

    /** Liveness ping budget — a live helper answers in ~40-200 ms. */
    private const val PING_TIMEOUT_MS = 700L

    private val ioLock = Any()
    private val seedLock = Any()
    private val seqCounter = AtomicInteger(0)

    @Volatile private var lastError: String? = null

    data class Status(
        val alive: Boolean,
        val pid: Int?,
        val dir: String?,
        val lastError: String? = null,
    )

    fun dir(context: Context): File? =
        context.getExternalFilesDir(null)?.let { File(it, DIR_NAME) }

    /** True when a live helper answers a ping. */
    fun isAlive(context: Context): Boolean =
        request(context, "ping", "", "", "", PING_TIMEOUT_MS) == "ok"

    /**
     * Spawn the helper when Shizuku is READY and nothing is answering yet.
     * Idempotent: a live helper running the current script short-circuits before
     * any file is touched. When the shipped script changed (an app upgrade), the
     * old loop is replaced — but only while Shizuku is available to respawn it;
     * otherwise the working old helper is kept rather than downgraded to none.
     */
    fun ensureSeeded(context: Context): Boolean {
        val d = dir(context) ?: run {
            lastError = "external files dir unavailable"
            return false
        }
        if (!d.isDirectory && !d.mkdirs()) {
            lastError = "cannot create $d"
            return false
        }
        val script = File(d, "helper.sh")
        val scriptChanged = !script.isFile ||
            runCatching { script.readText() != SCRIPT }.getOrDefault(true)

        if (!scriptChanged && isAlive(context)) return true

        synchronized(seedLock) {
            if (!scriptChanged && isAlive(context)) return true

            if (scriptChanged && isAlive(context)) {
                if (!ShizukuManager.isReady()) {
                    // Keep the older loop: it still only runs `settings`, and
                    // losing it would lose the capability entirely.
                    lastError = "helper is running an older script; start Shizuku once to upgrade"
                    return true
                }
                killRecordedPid(d)
            }

            writeIfChanged(script, SCRIPT)
            val tokenFile = File(d, "token")
            if (!tokenFile.isFile || tokenFile.readText().trim().isEmpty()) {
                if (!runCatching { tokenFile.writeText(randomToken()) }.isSuccess) {
                    lastError = "cannot write token"
                    return false
                }
            }
            cleanupStale(d)

            if (!ShizukuManager.isReady()) {
                lastError = "Shizuku not ready (${ShizukuManager.snapshot.value.state}); cannot spawn helper"
                return false
            }

            // Kill a stale helper recorded in the pid file so a respawn cannot
            // leave two loops racing over the same request directory.
            killRecordedPid(d)
            runCatching { File(d, "helper.pid").delete() }

            val cmd = "/system/bin/setsid /system/bin/sh '${script.absolutePath}' </dev/null >/dev/null 2>&1 &"
            val spawned = ShizukuManager.runProcess(arrayOf("/system/bin/sh", "-c", cmd), timeoutMs = 4_000)
            if (spawned.exitCode != 0) {
                lastError = "spawn exit=${spawned.exitCode} ${spawned.combined}".trim()
            }

            val deadline = System.currentTimeMillis() + 3_000
            while (System.currentTimeMillis() < deadline) {
                if (isAlive(context)) {
                    lastError = null
                    AppLogger.info(TAG, "helper seeded via Shizuku (pid=${pid(context) ?: -1})")
                    return true
                }
                Thread.sleep(150)
            }
            if (lastError == null) lastError = "helper did not answer within 3 s of spawn"
            AppLogger.warning(TAG, "seed failed: $lastError")
            return false
        }
    }

    /** Best-effort SIGKILL of the pid recorded by a previous helper (needs Shizuku). */
    private fun killRecordedPid(d: File) {
        val pid = runCatching { File(d, "helper.pid").readText().trim().toIntOrNull() }.getOrNull()
        if (pid == null || pid <= 0) return
        ShizukuManager.runProcess(arrayOf("/system/bin/kill", "-9", pid.toString()), timeoutMs = 2_000)
        Thread.sleep(150)
    }

    /**
     * Delete the token so a running helper exits; returns true when it had been
     * alive and no longer answers. Works without Shizuku — the app owns the
     * token file.
     */
    fun stop(context: Context): Boolean {
        val d = dir(context) ?: return false
        val wasAlive = isAlive(context)
        runCatching { File(d, "token").delete() }
        var i = 0
        while (i < 10) {
            if (!isAlive(context)) return wasAlive
            Thread.sleep(100)
            i++
        }
        return false
    }

    fun status(context: Context): Status {
        val d = dir(context)
        val alive = isAlive(context)
        return Status(alive, if (alive) pid(context) else null, d?.absolutePath, lastError)
    }

    /**
     * Run one helper op. Returns true/false from the helper, or null when no
     * helper answered (dead, absent, or timed out).
     */
    fun exec(
        context: Context,
        op: String,
        ns: String,
        key: String,
        value: String,
        timeoutMs: Long = EXEC_TIMEOUT_MS,
    ): Boolean? = when (request(context, op, ns, key, value, timeoutMs)) {
        "ok" -> true
        "fail", "bad", "auth", "err" -> false
        else -> null
    }

    private fun pid(context: Context): Int? = runCatching {
        dir(context)?.let { File(it, "helper.pid").readText().trim().toIntOrNull() }
    }.getOrNull()

    private fun request(
        context: Context,
        op: String,
        ns: String,
        key: String,
        value: String,
        timeoutMs: Long,
    ): String? {
        synchronized(ioLock) {
            val d = dir(context) ?: return null
            if (!d.isDirectory) return null
            val tokenFile = File(d, "token")
            if (!tokenFile.isFile) return null
            val token = runCatching { tokenFile.readText().trim() }.getOrNull().orEmpty()
            if (token.isEmpty()) return null

            val seq = nextSeq()
            val body = encodeRequest(token, op, seq, ns, key, value)
            val tmp = File(d, "q.$seq.tmp")
            val req = File(d, "q.$seq")
            val written = runCatching { tmp.writeText(body) }.isSuccess
            if (!written) return null
            if (!tmp.renameTo(req)) {
                runCatching { tmp.delete() }
                return null
            }

            val resp = File(d, "a.$seq")
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (resp.isFile) {
                    val text = runCatching { resp.readText() }.getOrNull()
                    runCatching { resp.delete() }
                    if (text != null) return parseResponse(text)
                }
                try {
                    Thread.sleep(40)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            runCatching { req.delete() }
            return null
        }
    }

    private fun nextSeq(): String =
        System.currentTimeMillis().toString(36) + "-" + seqCounter.incrementAndGet()

    private fun randomToken(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun writeIfChanged(file: File, text: String) {
        runCatching {
            if (!file.isFile || file.readText() != text) file.writeText(text)
        }
    }

    private fun cleanupStale(d: File) {
        runCatching {
            d.listFiles()?.forEach { f ->
                val n = f.name
                if (n.startsWith("q.") || n.startsWith("a.")) runCatching { f.delete() }
            }
        }
    }

    // ---- pure helpers (unit-tested) ----

    internal fun encodeRequest(token: String, op: String, seq: String, ns: String, key: String, value: String): String {
        fun oneLine(s: String) = s.replace('\r', ' ').replace('\n', ' ')
        return buildString {
            append("tok=").append(oneLine(token)).append('\n')
            append("op=").append(oneLine(op)).append('\n')
            append("seq=").append(oneLine(seq)).append('\n')
            append("ns=").append(oneLine(ns)).append('\n')
            append("key=").append(oneLine(key)).append('\n')
            append("val=").append(oneLine(value)).append('\n')
        }
    }

    /** The `res=` field of a helper response, or null when malformed. */
    internal fun parseResponse(text: String): String? {
        for (line in text.lineSequence()) {
            if (line.startsWith("res=")) return line.substring(4).trim()
        }
        return null
    }

    /**
     * Helper script. `~` stands in for `$` so this stays a readable Kotlin raw
     * string; no literal `~` occurs anywhere else in it.
     *
     * Guarantees: refuses to run unless uid is shell(2000) or root; validates
     * namespace/key/value charsets; only ever runs `settings put|delete`; exits
     * when the token file disappears.
     */
    private val SCRIPT: String = """
#!/system/bin/sh
D=$(dirname "~0" 2>/dev/null)
[ -n "~D" ] || exit 0
case "$(id -u)" in
  2000|0) ;;
  *) exit 0 ;;
esac
[ -d "~D" ] || exit 0
[ -f "~D/token" ] || exit 0
T=$(cat "~D/token" 2>/dev/null)
[ -n "~T" ] || exit 0
if [ -f "~D/helper.pid" ]; then
  P=$(cat "~D/helper.pid" 2>/dev/null)
  case "~P" in
    ''|*[!0-9]*) ;;
    *) [ "~P" != "~~" ] && kill -0 "~P" 2>/dev/null && exit 0 ;;
  esac
fi
printf '%s\n' "~~" > "~D/helper.pid"
trap 'rm -f "~D/helper.pid"' EXIT HUP INT TERM

ok_seq() { case "~1" in ''|*[!A-Za-z0-9_-]*) return 1 ;; esac; return 0; }
ok_ns() { case "~1" in global|secure|system) return 0 ;; esac; return 1; }
ok_key() { case "~1" in ''|*[!A-Za-z0-9_.-]*) return 1 ;; esac; return 0; }
ok_val() { case "~1" in *[![:print:]]*) return 1 ;; esac; return 0; }

while [ -d "~D" ] && [ -f "~D/token" ]; do
  busy=0
  for f in "~D"/q.*; do
    [ -f "~f" ] || continue
    case "~f" in *.tmp) continue ;; esac
    busy=1
    op=; seq=; ns=; key=; val=; tok=
    while IFS= read -r line || [ -n "~line" ]; do
      case "~line" in
        op=*) op=~{line#op=} ;;
        seq=*) seq=~{line#seq=} ;;
        ns=*) ns=~{line#ns=} ;;
        key=*) key=~{line#key=} ;;
        val=*) val=~{line#val=} ;;
        tok=*) tok=~{line#tok=} ;;
      esac
    done < "~f"
    r=err
    if [ "~tok" != "~T" ] || ! ok_seq "~seq"; then
      r=auth
    else
      case "~op" in
        ping) r=ok ;;
        put)
          if ok_ns "~ns" && ok_key "~key" && ok_val "~val"; then
            if settings put "~ns" "~key" "~val" >/dev/null 2>&1; then r=ok; else r=fail; fi
          else r=bad; fi ;;
        del)
          if ok_ns "~ns" && ok_key "~key"; then
            if settings delete "~ns" "~key" >/dev/null 2>&1; then r=ok; else r=fail; fi
          else r=bad; fi ;;
        *) r=bad ;;
      esac
    fi
    printf 'seq=%s\nres=%s\npid=%s\n' "~seq" "~r" "~~" > "~D/a.~seq.tmp" 2>/dev/null
    mv -f "~D/a.~seq.tmp" "~D/a.~seq" 2>/dev/null
    rm -f "~f"
  done
  [ "~busy" = "0" ] && sleep 0.2
done
""".trimIndent().replace('~', '$')

    /** Exposed for the app's seed test: the exact script that gets written. */
    internal val scriptForTest: String get() = SCRIPT
}

package com.openminis.app.debug

import android.content.Context
import android.content.SharedPreferences
import com.openminis.app.BuildConfig
import com.openminis.app.logging.AppLogger
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-android-debug-prefs-rpc] DEV_TOOLS-only RPCs that let an automation agent
 * read and write the app's own SharedPreferences precisely, in bulk, with
 * readback — replacing brittle UI slider tapping.
 *
 * Four methods: `debug.prefs.list/read/write/delete`. All are registered only
 * in DEV_TOOLS builds (see [DebugMethodRegistry] and [DebugRPCHandler]) and
 * every entry point re-checks [BuildConfig.DEV_TOOLS] so a release build can
 * neither discover nor dispatch them. The existing DebugServer token/auth gate
 * applies unchanged — this file adds no new transport.
 *
 * Security: secret-shaped keys (password/token/secret/api key/cookie/...) are
 * NEVER returned in plain text and never logged. Values are replaced by a
 * redaction marker that keeps only the type and length; writes to such keys
 * still work (the same readback is redacted). If a specific preference holds
 * a sensitive value under an innocuous name, add it to [isSecretKey].
 *
 * Live-state sync: settings screens read StateFlow caches, not disk. When a
 * key maps to a known live object (today: VoiceOutputState's voice_prefs keys),
 * the write is routed through its setter so disk AND the in-memory flow agree.
 * New cached settings must be added to [writeLiveState] or a subsequent UI
 * action can overwrite the RPC's value from the stale flow.
 */
internal object PrefsDebugMethods {

    val names: Set<String> = setOf(
        "debug.prefs.list",
        "debug.prefs.read",
        "debug.prefs.write",
        "debug.prefs.delete",
    )

    private fun p(name: String, type: String, required: Boolean, description: String, default: Any? = null) =
        DebugMethodRegistry.ParamSpec(name, type, required, default, description)

    private fun spec(name: String, description: String, params: List<DebugMethodRegistry.ParamSpec>, returns: String, example: JSONObject) =
        DebugMethodRegistry.MethodSpec(name, description, params, returns, example)

    val specs: List<DebugMethodRegistry.MethodSpec> by lazy {
        listOf(
            spec(
                "debug.prefs.list",
                "DEV_TOOLS-only: list every SharedPreferences XML file of this app with its key count. Encrypted/unreadable files are reported with readable=false, never decrypted.",
                emptyList(),
                "{files:[{file,keys,readable,error?}],count}",
                JSONObject(),
            ),
            spec(
                "debug.prefs.read",
                "DEV_TOOLS-only: read a SharedPreferences file. Pass file only for all keys, or file+key for one. Secret-shaped values are redacted (type+length only).",
                listOf(
                    p("file", "string", true, "Bare prefs file name, e.g. voice_prefs (no .xml, no path)."),
                    p("key", "string", false, "Optional key; omit to read the whole file."),
                ),
                "All keys: {file,values:{key:{type,value}},count}; one key: {file,key,value:{type,value}}",
                JSONObject().put("file", "voice_prefs"),
            ),
            spec(
                "debug.prefs.write",
                "DEV_TOOLS-only: write one preference with readback verification. JSON scalar (bool/int/long/float/string) or JSON array of strings (Set). Integers use the existing type when known; pass type=long to force. Known live keys (VoiceOutputState) refresh their StateFlow so the UI updates. Secret values are never logged.",
                listOf(
                    p("file", "string", true, "Bare prefs file name."),
                    p("key", "string", true, "Key to write."),
                    p("value", "any", true, "bool/int/float/string, or an array of strings for a Set."),
                    p("type", "string", false, "Optional: 'long' to store an integer as long."),
                ),
                "{ok,file,key,type,expected,actual}",
                JSONObject().put("file", "voice_prefs").put("key", "readReplies.speed").put("value", 1.25),
            ),
            spec(
                "debug.prefs.delete",
                "DEV_TOOLS-only: delete one preference key with readback verification.",
                listOf(
                    p("file", "string", true, "Bare prefs file name."),
                    p("key", "string", true, "Key to delete."),
                ),
                "{ok,file,key,existed}",
                JSONObject().put("file", "voice_prefs").put("key", "readReplies.speed"),
            ),
        )
    }

    /** Marker returned instead of a secret-shaped value (no value data leaks). */
    private const val REDACTED = "<redacted>"

    /** A file name must be a bare name, never a path. */
    private val SAFE_FILE = Regex("^[A-Za-z0-9._-]+$")

    /** Key names that must never be returned (case-insensitive, substring). */
    private val SECRET_HINTS = listOf(
        "password", "passwd", "secret", "token", "apikey", "api_key", "api-key",
        "cookie", "credential", "private_key", "privatekey", "private-key",
        "sessionkey", "session_key", "session-key", "authorization", "accesskey",
        "access_key", "access-key", "secretkey", "secret_key", "secret-key",
        "authkey", "auth_key", "auth-key", "auth.",
    )

    fun isSecretKey(key: String): Boolean {
        val lower = key.lowercase()
        return SECRET_HINTS.any { lower.contains(it) }
    }

    private fun requireDevTools(method: String) {
        if (!BuildConfig.DEV_TOOLS) {
            throw RPCException(-32601, "Method not found: $method. Call 'rpc.discover' to list available methods.")
        }
    }

    suspend fun dispatch(context: Context, method: String, params: JSONObject): Any {
        requireDevTools(method)
        val app = context.applicationContext
        return when (method) {
            "debug.prefs.list" -> list(app)
            "debug.prefs.read" -> read(app, params)
            "debug.prefs.write" -> write(app, params)
            "debug.prefs.delete" -> delete(app, params)
            else -> throw RPCException(-32601, "Method not found: $method")
        }
    }

    private fun safeFile(params: JSONObject): String {
        val file = params.optString("file").trim()
        if (file.isEmpty()) throw RPCException(-32602, "file is required")
        if (!SAFE_FILE.matches(file) || file == "." || file == "..") {
            throw RPCException(-32602, "file must be a plain name (letters, digits, '.', '_', '-')")
        }
        return file.removeSuffix(".xml")
    }

    private fun key(params: JSONObject): String {
        val k = params.optString("key").trim()
        if (k.isEmpty()) throw RPCException(-32602, "key is required")
        return k
    }

    private fun prefs(app: Context, file: String): SharedPreferences =
        app.getSharedPreferences(file, Context.MODE_PRIVATE)

    // ── list ────────────────────────────────────────────────────────────────

    /**
     * Enumerate every SharedPreferences XML file of this app. Encrypted or
     * otherwise unreadable files are reported with readable=false instead of
     * failing the whole call — we never try to decrypt them.
     */
    private fun list(app: Context): JSONObject {
        val dir = app.filesDir.parentFile?.resolve("shared_prefs")
        val files = dir?.listFiles()?.filter { it.isFile && it.name.endsWith(".xml") }.orEmpty()
        val arr = JSONArray()
        for (f in files.sortedBy { it.name }) {
            val name = f.name.removeSuffix(".xml")
            val entry = JSONObject().put("file", name)
            runCatching {
                val p = app.getSharedPreferences(name, Context.MODE_PRIVATE)
                entry.put("keys", p.all.size)
                entry.put("readable", true)
            }.getOrElse {
                entry.put("keys", JSONObject.NULL)
                entry.put("readable", false)
                entry.put("error", it::class.simpleName ?: "unreadable")
            }
            arr.put(entry)
        }
        // Some prefs live only in memory until first write; include those the
        // app has touched via getSharedPreferences (its own data dir is the
        // authoritative source, so the XML scan above is normally enough).
        return JSONObject().apply {
            put("files", arr)
            put("count", arr.length())
        }
    }

    // ── read ────────────────────────────────────────────────────────────────

    private fun read(app: Context, params: JSONObject): JSONObject {
        val file = safeFile(params)
        val p = prefs(app, file)
        val single = params.has("key")
        val result = JSONObject().put("file", file)
        if (single) {
            val k = key(params)
            if (!p.contains(k)) throw RPCException(-32602, "key not found: $k in $file")
            result.put("key", k)
            result.put("value", valueJson(p.all[k]!!, k))
            return result
        }
        val values = JSONObject()
        for ((k, v) in p.all.toSortedMap()) {
            values.put(k, valueJson(v, k))
        }
        return result.put("values", values).put("count", values.length())
    }

    /** Convert a stored value to its JSON representation, redacting secrets. */
    private fun valueJson(value: Any?, key: String): Any = when {
        isSecretKey(key) -> JSONObject().put("type", "redacted").put("value", "$REDACTED(len=${value.toString().length})")
        value == null -> JSONObject.NULL
        value is Set<*> -> JSONObject().put("type", "set").put("value", JSONArray(value.toList()))
        value is Boolean || value is Int || value is Long || value is Float || value is String ->
            JSONObject().put("type", value::class.simpleName!!.lowercase()).put("value", value)
        else -> JSONObject().put("type", value::class.simpleName ?: "unknown").put("value", value.toString())
    }

    // ── write ───────────────────────────────────────────────────────────────

    private fun write(app: Context, params: JSONObject): JSONObject {
        val file = safeFile(params)
        val k = key(params)
        if (!params.has("value")) throw RPCException(-32602, "value is required")
        val raw = params.get("value")

        val p = prefs(app, file)
        val existing = p.all[k]
        val value: Any = when {
            raw === JSONObject.NULL -> throw RPCException(-32602, "value cannot be null; use debug.prefs.delete")
            raw is Boolean -> raw
            raw is String -> raw
            raw is JSONObject || raw is JSONArray ->
                throw RPCException(-32602, "value must be a scalar or an array of strings (Set)")
            raw is Int -> {
                val explicit = params.optString("type", "")
                when {
                    explicit.equals("long", ignoreCase = true) -> raw.toLong()
                    existing is Long -> raw.toLong()
                    else -> raw
                }
            }
            raw is Long -> raw
            raw is Double -> {
                val f = raw.toFloat()
                if (f.isInfinite()) throw RPCException(-32602, "value out of float range")
                f
            }
            else -> throw RPCException(-32602, "unsupported value type: ${raw::class.simpleName}")
        }

        // Set needs an explicit JSONArray to distinguish it from other arrays.
        val finalValue: Any = if (raw is JSONArray) {
            List(raw.length()) { i ->
                if (raw.isNull(i)) throw RPCException(-32602, "Set elements must be strings")
                raw.optString(i)
            }.toSet()
        } else value

        AppLogger.info("DebugRPC", "debug.prefs.write file=$file key=$k type=${finalValue::class.simpleName}")

        writeLiveState(file, k, finalValue, p)

        // Readback verification — never report success without it.
        val actual = p.all[k]
        val ok = actual != null && valuesEqual(actual, finalValue)
        return JSONObject().apply {
            put("ok", ok)
            put("file", file)
            put("key", k)
            put("type", finalValue::class.simpleName?.lowercase())
            put("expected", valueJson(finalValue, k))
            put("actual", if (actual == null) JSONObject.NULL else valueJson(actual, k))
        }
    }

    private fun valuesEqual(a: Any, b: Any): Boolean = when {
        a is Set<*> && b is Set<*> -> a == b
        a is Float && b is Float -> a == b || (a.isNaN() && b.isNaN())
        else -> a == b
    }

    /**
     * Route a known preference through the live object's setter so its StateFlow
     * cache stays in sync with disk. Unknown keys are written straight to disk.
     */
    private fun writeLiveState(file: String, key: String, value: Any, p: SharedPreferences) {
        if (file == "voice_prefs") {
            val f = (value as? Number)?.toFloat()
            when (key) {
                "readReplies.speed" -> if (f != null) {
                    com.openminis.app.speech.VoiceOutputState.setSpeed(f)
                    return ensureDiskValue(p, key, value, f)
                }
                "readReplies.pitch" -> if (f != null) {
                    com.openminis.app.speech.VoiceOutputState.setPitch(f)
                    return ensureDiskValue(p, key, value, f)
                }
                "readReplies.volume" -> if (f != null) {
                    com.openminis.app.speech.VoiceOutputState.setVolume(f)
                    return ensureDiskValue(p, key, value, f)
                }
                "readReplies.muted" -> if (value is Boolean) {
                    com.openminis.app.speech.VoiceOutputState.setMuted(value)
                    return ensureDiskValue(p, key, value, value)
                }
                "readReplies" -> if (value is Boolean) {
                    com.openminis.app.speech.VoiceOutputState.setEnabled(value)
                    return ensureDiskValue(p, key, value, value)
                }
            }
        }
        // Generic path: this file/key is not known to have a live cache.
        writeDisk(p, key, value)
    }

    /**
     * Live setters short-circuit when their in-memory value already matches
     * (`setSpeed` etc. return early). That is correct for UI actions but would
     * skip the disk write if disk had drifted. Force the disk value afterwards.
     */
    private fun ensureDiskValue(p: SharedPreferences, key: String, original: Any, stored: Any) {
        val current = p.all[key]
        if (current == null || !valuesEqual(current, stored)) writeDisk(p, key, original)
    }

    private fun writeDisk(p: SharedPreferences, key: String, value: Any) {
        p.edit().apply {
            when (value) {
                is Boolean -> putBoolean(key, value)
                is Int -> putInt(key, value)
                is Long -> putLong(key, value)
                is Float -> putFloat(key, value)
                is String -> putString(key, value)
                is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                else -> throw RPCException(-32602, "unsupported value type: ${value::class.simpleName}")
            }
        }.apply()
    }

    // ── delete ──────────────────────────────────────────────────────────────

    private fun delete(app: Context, params: JSONObject): JSONObject {
        val file = safeFile(params)
        val k = key(params)
        val p = prefs(app, file)
        val existed = p.contains(k)
        p.edit().remove(k).apply()
        AppLogger.info("DebugRPC", "debug.prefs.delete file=$file key=$k existed=$existed")
        return JSONObject().apply {
            put("ok", !p.contains(k))
            put("file", file)
            put("key", k)
            put("existed", existed)
        }
    }
}

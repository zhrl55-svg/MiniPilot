package com.openminis.app.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * T-android-keystore-aead-fail: self-healing wrapper around
 * [EncryptedSharedPreferences.create].
 *
 * The default flow throws `AEADBadTagException` on open when the Tink keyset
 * stored inside the prefs file was wrapped by a different AndroidKeystore key
 * than the one now under the alias - e.g. the XML came back from a system
 * backup / device transfer, which never carries Keystore keys. Uncaught, that
 * killed the app in a relaunch loop (Samsung One UI / Android 16 reports).
 *
 * [T-android-keystore-no-cascade] The first version of this wrapper turned one
 * unreadable store into the loss of EVERY credential, repeatedly (user report:
 * API keys gone after an update, then env var values and the WebDAV password,
 * reproduced on a Pixel 4a by corrupting one keyset byte):
 *  - it deleted the master-key alias, which all seven stores share, so every
 *    other store became unreadable at its next open and did the same in turn;
 *  - it deleted the XML with File.delete(), which leaves the process-wide
 *    SharedPreferences cache holding the old keyset, so the rebuild re-read it
 *    and always failed, dropping to the empty plain-text fallback;
 *  - callers that construct a store per call (backup page, OAuth routing
 *    checks) re-ran the whole wipe on every call, and concurrent opens on
 *    startup threads deleted the key out from under each other.
 *
 * Strategy now:
 *  1. One open per file per process, under a lock ([cache]).
 *  2. Try the normal create, then once more (a Keystore hiccup must not cost
 *     data).
 *  3. Still failing: probe the master key with an encrypt/decrypt round trip.
 *     If it works, only THIS file's keyset is orphaned - drop just this file
 *     via deleteSharedPreferences (clears the in-memory cache too). Only a
 *     master key that cannot encrypt at all is recreated; every store under
 *     it is unreadable already, so that costs nothing extra.
 *  4. Rebuild failing even then: an empty plain-text store named
 *     "<file>_plain_fallback", so the app keeps running. Whatever the user
 *     enters there is moved into the encrypted store at the next successful
 *     open, so it is neither lost again nor left in plain text.
 */
object EncryptedPrefsFactory {
    private const val TAG = "EncryptedPrefsFactory"
    internal const val FALLBACK_SUFFIX = "_plain_fallback"

    /**
     * [T-android-keystore-plaintext-marker] Set to true in a plain-text
     * fallback store for as long as that store is the live one, so the
     * degradation is visible on disk (diagnostics / support triage), not
     * only in a log line that rotates away. Cleared when the entries move
     * back into the encrypted store (see [moveEntries]).
     */
    const val PLAINTEXT_FALLBACK_MARKER = "plaintext_fallback_active"

    private val lock = Any()
    private val cache = HashMap<String, SharedPreferences>()

    fun safeCreate(context: Context, fileName: String): SharedPreferences =
        cached(fileName) { open(AndroidOps(context.applicationContext), fileName) }

    /** One instance per file per process; the lock also serialises every create. */
    internal fun cached(fileName: String, create: () -> SharedPreferences): SharedPreferences =
        synchronized(lock) { cache.getOrPut(fileName, create) }

    /** Test hook: forget opened stores. */
    internal fun resetCacheForTest() = synchronized(lock) { cache.clear() }

    /** Everything [open] needs from the platform; a fake drives it in unit tests. */
    internal interface Ops {
        fun build(fileName: String): SharedPreferences
        /** True when the shared master key can still encrypt and decrypt. */
        fun masterKeyWorks(): Boolean
        /** Delete this one store, on disk and in the process-wide prefs cache. */
        fun discardStore(fileName: String)
        fun deleteMasterKey()
        fun plainFallback(fileName: String): SharedPreferences
        /** Move entries left in this file's plain fallback into [into]. */
        fun adoptFallback(fileName: String, into: SharedPreferences)
        fun log(priority: Int, msg: String, t: Throwable? = null)
    }

    internal fun open(ops: Ops, fileName: String): SharedPreferences {
        fun attempt(label: String, priority: Int = Log.WARN): SharedPreferences? = try {
            ops.build(fileName)
        } catch (t: Throwable) {
            ops.log(priority, "$label($fileName) failed: ${t.javaClass.simpleName}: ${t.message}", t)
            null
        }
        fun ready(p: SharedPreferences) = p.also { ops.adoptFallback(fileName, it) }

        attempt("first create")?.let { return ready(it) }
        attempt("retry create")?.let { return ready(it) }

        if (ops.masterKeyWorks()) {
            ops.log(Log.WARN, "master key OK - discarding only $fileName (its keyset was wrapped by another key)")
            ops.discardStore(fileName)
        } else {
            ops.log(Log.ERROR, "master key unusable - recreating it; discarding $fileName")
            ops.deleteMasterKey()
            ops.discardStore(fileName)
        }

        attempt("rebuild", Log.ERROR)?.let { return ready(it) }

        // [T-android-keystore-plaintext-marker] The fallback store is plain
        // text: say so loudly, and leave a durable marker in the store itself.
        ops.log(Log.ERROR, "falling back to plain SharedPreferences for $fileName - credentials lost; secrets will be stored UNENCRYPTED in $fileName$FALLBACK_SUFFIX")
        val plain = ops.plainFallback(fileName)
        runCatching { plain.edit().putBoolean(PLAINTEXT_FALLBACK_MARKER, true).commit() }
            .onFailure { ops.log(Log.WARN, "writing plaintext fallback marker for $fileName failed: ${it.message}") }
        return plain
    }

    /**
     * Copy every entry of [plain] into [into], then clear [plain]. Returns how
     * many entries moved. Clears only after the copy is committed, so a failed
     * write keeps the values where they were.
     *
     * The plaintext fallback marker ([PLAINTEXT_FALLBACK_MARKER]) never moves:
     * it only describes the fallback store itself, and is dropped together
     * with the clear below (or with the whole file by [adoptFallback]'s
     * delete-once-empty).
     */
    internal fun moveEntries(plain: SharedPreferences, into: SharedPreferences): Int {
        val all = plain.all
        val movable = all.filterKeys { it != PLAINTEXT_FALLBACK_MARKER }
        if (movable.isEmpty()) {
            // Nothing but the marker (or nothing at all): clear it so a
            // marker-only fallback file doesn't linger on disk forever.
            if (all.isNotEmpty()) plain.edit().clear().commit()
            return 0
        }
        val ed = into.edit()
        for ((k, v) in movable) {
            @Suppress("UNCHECKED_CAST")
            when (v) {
                is String -> ed.putString(k, v)
                is Boolean -> ed.putBoolean(k, v)
                is Int -> ed.putInt(k, v)
                is Long -> ed.putLong(k, v)
                is Float -> ed.putFloat(k, v)
                is Set<*> -> ed.putStringSet(k, v as Set<String>)
            }
        }
        if (!ed.commit()) return 0
        plain.edit().clear().commit()
        return movable.size
    }

    private class AndroidOps(private val context: Context) : Ops {
        override fun build(fileName: String): SharedPreferences {
            val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                fileName,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }

        override fun masterKeyWorks(): Boolean = (1..2).any { roundTrip() }

        private fun roundTrip(): Boolean = runCatching {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            // No key at all: nothing to recreate, and nothing to blame on it.
            val key = ks.getKey(MasterKey.DEFAULT_MASTER_KEY_ALIAS, null) as? SecretKey
                ?: return@runCatching true
            val probe = "minis-keystore-probe".toByteArray()
            val enc = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
            val sealed = enc.doFinal(probe)
            val dec = Cipher.getInstance("AES/GCM/NoPadding")
                .apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, enc.iv)) }
            dec.doFinal(sealed).contentEquals(probe)
        }.onFailure { log(Log.WARN, "master key probe failed: ${it.javaClass.simpleName}: ${it.message}") }
            .getOrDefault(false)

        override fun discardStore(fileName: String) {
            runCatching { context.deleteSharedPreferences(fileName) }
                .onFailure { log(Log.WARN, "discard $fileName failed: ${it.message}") }
        }

        override fun deleteMasterKey() {
            runCatching {
                val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                if (ks.containsAlias(MasterKey.DEFAULT_MASTER_KEY_ALIAS)) {
                    ks.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                }
            }.onFailure { log(Log.WARN, "delete master key failed: ${it.message}") }
        }

        override fun plainFallback(fileName: String): SharedPreferences =
            context.getSharedPreferences(fileName + FALLBACK_SUFFIX, Context.MODE_PRIVATE)

        override fun adoptFallback(fileName: String, into: SharedPreferences) {
            runCatching {
                val name = fileName + FALLBACK_SUFFIX
                val dir = java.io.File(context.applicationInfo.dataDir, "shared_prefs")
                if (!java.io.File(dir, "$name.xml").exists()) return
                val plain = context.getSharedPreferences(name, Context.MODE_PRIVATE)
                val moved = moveEntries(plain, into)
                // Delete only once nothing is left in it: a failed copy keeps the values.
                if (plain.all.isEmpty()) context.deleteSharedPreferences(name)
                log(Log.INFO, "moved $moved entr(ies) from $name back into encrypted $fileName")
            }.onFailure { log(Log.WARN, "adopt fallback for $fileName failed: ${it.message}") }
        }

        override fun log(priority: Int, msg: String, t: Throwable?) {
            if (t != null && priority >= Log.ERROR) Log.println(priority, TAG, msg + "\n" + Log.getStackTraceString(t))
            else Log.println(priority, TAG, msg)
        }
    }
}

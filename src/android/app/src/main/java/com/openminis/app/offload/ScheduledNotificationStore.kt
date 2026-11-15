package com.openminis.app.offload

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable registry of `android-notification schedule` reminders.
 *
 * [T-android-reminder-delivery-state] The store used to be a plain list of
 * pending entries: the receiver removed the entry before posting, so a failed
 * post left no trace, and a process death between "alarm fired" and "notify"
 * could neither be detected nor retried. It now carries an explicit delivery
 * state per record:
 *
 *   pending -> delivering -> delivered
 *                         -> failed
 *   pending -> cancelled
 *
 * The state transition into [State.DELIVERING] is a durable claim written with
 * `commit()` *before* the notification is posted. Two alarm deliveries for the
 * same id can therefore never both post, and a crash leaves a `delivering`
 * record that startup recovery can mark `failed` with an honest
 * unknown-outcome error instead of silently forgetting the reminder.
 *
 * Terminal records are retained (bounded) so `android-notification history`
 * can answer "did my reminder fire?". Old records written before this change
 * have no `state` field and read as [State.PENDING].
 */
internal class ScheduledNotificationStore(context: Context) {

    enum class State(val wire: String) {
        PENDING("pending"),
        DELIVERING("delivering"),
        DELIVERED("delivered"),
        CANCELLED("cancelled"),
        FAILED("failed");

        val isTerminal: Boolean
            get() = this == DELIVERED || this == CANCELLED || this == FAILED

        companion object {
            fun from(raw: String?): State = when (raw?.trim()?.lowercase()) {
                "delivering" -> DELIVERING
                "delivered" -> DELIVERED
                "cancelled", "canceled" -> CANCELLED
                "failed" -> FAILED
                else -> PENDING
            }
        }
    }

    data class Record(
        val id: String,
        val title: String,
        val body: String,
        val triggerAtMs: Long,
        val triggerAt: String,
        val requestCode: Int,
        val state: State,
        val createdAtMs: Long,
        val claimedAtMs: Long? = null,
        val deliveredAtMs: Long? = null,
        val error: String? = null,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id)
            .put("title", title)
            .put("body", body)
            .put("trigger_at_ms", triggerAtMs)
            .put("trigger_at", triggerAt)
            .put("request_code", requestCode)
            .put("state", state.wire)
            .put("created_at_ms", createdAtMs)
            .put("claimed_at_ms", claimedAtMs ?: JSONObject.NULL)
            .put("delivered_at_ms", deliveredAtMs ?: JSONObject.NULL)
            .put("error", error ?: JSONObject.NULL)

        companion object {
            fun from(json: JSONObject): Record? {
                val id = json.optString("id").trim()
                if (id.isEmpty()) return null
                val triggerAtMs = json.optLong("trigger_at_ms", 0L)
                if (triggerAtMs <= 0L) return null
                val requestCode = json.optInt(
                    "request_code",
                    id.hashCode() and 0x7FFFFFFF,
                )
                val stateRaw = if (json.has("state") && !json.isNull("state")) {
                    json.optString("state")
                } else {
                    null
                }
                return Record(
                    id = id,
                    title = json.optString("title"),
                    body = json.optString("body"),
                    triggerAtMs = triggerAtMs,
                    triggerAt = json.optString("trigger_at"),
                    requestCode = requestCode,
                    state = State.from(stateRaw),
                    createdAtMs = json.optLong("created_at_ms", 0L),
                    claimedAtMs = json.optNullableLong("claimed_at_ms"),
                    deliveredAtMs = json.optNullableLong("delivered_at_ms"),
                    error = optNullableString(json, "error"),
                )
            }
        }
    }

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Adds or replaces one record. Replacing is intentional: scheduling the
     * same id again must update the existing row rather than create a second
     * alarm with the same PendingIntent.
     */
    fun add(record: Record): Boolean = synchronized(lock) {
        val list = loadLocked().toMutableList()
        list.removeAll { it.id == record.id }
        list.add(record)
        pruneLocked(list)
        persistLocked(list)
    }

    fun get(id: String): Record? = synchronized(lock) {
        loadLocked().firstOrNull { it.id == id }
    }

    /** Pending and in-flight records, oldest trigger first. */
    fun active(): List<Record> = synchronized(lock) {
        loadLocked()
            .filter { it.state == State.PENDING || it.state == State.DELIVERING }
            .sortedBy { it.triggerAtMs }
    }

    /** Newest-first history, including terminal states. */
    fun history(limit: Int = 50): List<Record> = synchronized(lock) {
        loadLocked()
            .sortedWith(
                compareByDescending<Record> { it.createdAtMs }
                    .thenByDescending { it.triggerAtMs },
            )
            .take(limit.coerceIn(1, MAX_HISTORY))
    }

    /**
     * Atomically transitions pending -> delivering and durably persists the
     * claim. Returns null when the record is gone, already terminal, or another
     * delivery already claimed it.
     */
    fun claim(id: String, now: Long = System.currentTimeMillis()): Record? =
        synchronized(lock) {
            val list = loadLocked().toMutableList()
            val index = list.indexOfFirst { it.id == id }
            if (index < 0) return@synchronized null
            val current = list[index]
            if (current.state != State.PENDING) return@synchronized null
            val claimed = current.copy(
                state = State.DELIVERING,
                claimedAtMs = now,
                error = null,
            )
            list[index] = claimed
            if (!persistLocked(list)) {
                Log.w(TAG, "claim persist failed id=$id")
                return@synchronized null
            }
            claimed
        }

    fun markDelivered(id: String, now: Long = System.currentTimeMillis()): Boolean =
        synchronized(lock) {
            val list = loadLocked().toMutableList()
            val index = list.indexOfFirst { it.id == id }
            if (index < 0) return@synchronized false
            val current = list[index]
            if (current.state != State.DELIVERING && current.state != State.PENDING) {
                return@synchronized false
            }
            list[index] = current.copy(
                state = State.DELIVERED,
                deliveredAtMs = now,
                claimedAtMs = null,
                error = null,
            )
            pruneLocked(list)
            persistLocked(list)
        }

    fun markFailed(
        id: String,
        error: String,
        now: Long = System.currentTimeMillis(),
    ): Boolean = synchronized(lock) {
        val list = loadLocked().toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return@synchronized false
        val current = list[index]
        if (current.state.isTerminal) return@synchronized false
        list[index] = current.copy(
            state = State.FAILED,
            claimedAtMs = null,
            deliveredAtMs = now,
            error = error.take(MAX_ERROR_CHARS),
        )
        pruneLocked(list)
        persistLocked(list)
    }

    /**
     * Records a retryable scheduling failure on a still-pending record. Used
     * by boot recovery when the exact-alarm permission is temporarily absent:
     * the reminder stays pending so a later launch can arm it instead of being
     * permanently buried in `failed`.
     */
    fun noteError(id: String, error: String): Boolean = synchronized(lock) {
        val list = loadLocked().toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return@synchronized false
        val current = list[index]
        if (current.state != State.PENDING) return@synchronized false
        list[index] = current.copy(error = error.take(MAX_ERROR_CHARS))
        persistLocked(list)
    }

    /** Cancels a pending record. A delivery already in flight cannot be cancelled. */
    fun markCancelled(id: String): Boolean = synchronized(lock) {
        val list = loadLocked().toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return@synchronized false
        val current = list[index]
        if (current.state != State.PENDING) return@synchronized false
        list[index] = current.copy(
            state = State.CANCELLED,
            claimedAtMs = null,
            error = null,
        )
        pruneLocked(list)
        persistLocked(list)
    }

    /** Cancels every still-pending record; returns how many changed. */
    fun markAllCancelled(): Int = synchronized(lock) {
        val list = loadLocked().toMutableList()
        var changed = 0
        for (i in list.indices) {
            val current = list[i]
            if (current.state != State.PENDING) continue
            list[i] = current.copy(state = State.CANCELLED, claimedAtMs = null)
            changed++
        }
        if (changed > 0) {
            pruneLocked(list)
            persistLocked(list)
        }
        changed
    }

    /**
     * Recovers deliveries interrupted by process death. A `delivering` claim
     * older than [staleAfterMs] is marked failed with an explicit unknown
     * outcome; the notification may or may not have been posted.
     */
    fun recoverInterrupted(
        now: Long = System.currentTimeMillis(),
        staleAfterMs: Long = 2 * 60 * 1000L,
    ): Int = synchronized(lock) {
        val list = loadLocked().toMutableList()
        var changed = 0
        for (i in list.indices) {
            val current = list[i]
            if (current.state != State.DELIVERING) continue
            val claimedAt = current.claimedAtMs ?: 0L
            if (claimedAt > 0L && now - claimedAt < staleAfterMs) continue
            list[i] = current.copy(
                state = State.FAILED,
                claimedAtMs = null,
                deliveredAtMs = now,
                error = INTERRUPTED_ERROR,
            )
            changed++
        }
        if (changed > 0) {
            pruneLocked(list)
            persistLocked(list)
        }
        changed
    }

    fun clear(): Boolean = synchronized(lock) {
        prefs.edit().remove(KEY).commit()
    }

    // ── persistence ──────────────────────────────────────────────────────

    private fun loadLocked(): List<Record> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        val array = try {
            JSONArray(raw)
        } catch (e: Exception) {
            Log.w(TAG, "stored reminders are not valid JSON: ${e.message}")
            return emptyList()
        }
        val out = ArrayList<Record>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val record = try {
                Record.from(obj)
            } catch (e: Exception) {
                Log.w(TAG, "skipping malformed reminder at $i: ${e.message}")
                null
            } ?: continue
            out.add(record)
        }
        return out
    }

    /** Keeps at most [MAX_TERMINAL_RECORDS] delivered/cancelled/failed rows. */
    private fun pruneLocked(list: MutableList<Record>) {
        val terminal = list.filter { it.state.isTerminal }
            .sortedByDescending { it.deliveredAtMs ?: it.createdAtMs }
        if (terminal.size <= MAX_TERMINAL_RECORDS) return
        val dropIds = terminal.drop(MAX_TERMINAL_RECORDS).map { it.id }.toSet()
        list.removeAll { it.id in dropIds }
    }

    @Suppress("ApplySharedPref")
    private fun persistLocked(list: List<Record>): Boolean {
        val array = JSONArray()
        for (record in list) array.put(record.toJson())
        val ok = prefs.edit().putString(KEY, array.toString()).commit()
        if (!ok) Log.w(TAG, "failed to persist ${list.size} reminder(s)")
        return ok
    }

    companion object {
        internal const val PREFS = "minis_scheduled_notifications"
        internal const val KEY = "scheduled"
        internal const val MAX_TERMINAL_RECORDS = 100
        private const val MAX_HISTORY = 500
        private const val MAX_ERROR_CHARS = 500
        private const val TAG = "ScheduledNotifStore"
        internal const val INTERRUPTED_ERROR =
            "delivery_interrupted: the app stopped before the notification outcome was recorded"

        private val lock = Any()
    }
}

private fun JSONObject.optNullableLong(name: String): Long? =
    if (!has(name) || isNull(name)) null else optLong(name).takeIf { it > 0L }

private fun optNullableString(json: JSONObject, name: String): String? =
    if (!json.has(name) || json.isNull(name)) {
        null
    } else {
        json.optString(name).takeIf { it.isNotBlank() }
    }

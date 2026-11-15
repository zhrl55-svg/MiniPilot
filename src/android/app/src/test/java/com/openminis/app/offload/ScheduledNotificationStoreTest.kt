package com.openminis.app.offload

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [T-android-reminder-delivery-state] The reminder store is the source of
 * truth for "did this fire?" — a wrong claim can double-post, and a lost
 * record can silently drop a reminder. These tests pin the state transitions,
 * durability and crash recovery.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScheduledNotificationStoreTest {

    private lateinit var context: Context
    private lateinit var store: ScheduledNotificationStore

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(ScheduledNotificationStore.PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        store = ScheduledNotificationStore(context)
    }

    private fun record(
        id: String,
        triggerAtMs: Long = System.currentTimeMillis() + 60_000,
        state: ScheduledNotificationStore.State = ScheduledNotificationStore.State.PENDING,
        createdAtMs: Long = System.currentTimeMillis(),
        claimedAtMs: Long? = null,
    ) = ScheduledNotificationStore.Record(
        id = id,
        title = "提醒 $id",
        body = "body $id",
        triggerAtMs = triggerAtMs,
        triggerAt = "2026-11-15T20:00:00+08:00",
        requestCode = id.hashCode() and 0x7FFFFFFF,
        state = state,
        createdAtMs = createdAtMs,
        claimedAtMs = claimedAtMs,
    )

    @Test
    fun `legacy record without state reads as pending`() {
        val legacy = JSONObject()
            .put("id", "legacy")
            .put("title", "旧提醒")
            .put("body", "body")
            .put("trigger_at_ms", 1_900_000_000_000L)
            .put("trigger_at", "2030-01-01T08:00:00+08:00")
            .put("request_code", 7)
        context.getSharedPreferences(ScheduledNotificationStore.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(
                ScheduledNotificationStore.KEY,
                JSONArray().put(legacy).toString(),
            )
            .commit()

        val loaded = ScheduledNotificationStore(context).get("legacy")
        assertNotNull(loaded)
        assertEquals(ScheduledNotificationStore.State.PENDING, loaded!!.state)
        assertNull(loaded.claimedAtMs)
    }

    @Test
    fun `null delivery fields round trip as null`() {
        store.add(record("nullerr"))
        val loaded = ScheduledNotificationStore(context).get("nullerr")!!
        assertNull(loaded.error)
        assertNull(loaded.claimedAtMs)
        assertNull(loaded.deliveredAtMs)
    }

    @Test
    fun `claim is durable and only succeeds once`() {
        store.add(record("a"))
        val claimed = store.claim("a", now = 1_000L)
        assertNotNull(claimed)
        assertEquals(ScheduledNotificationStore.State.DELIVERING, claimed!!.state)
        assertEquals(1_000L, claimed.claimedAtMs)

        // A second store instance sees the claim, so a duplicate alarm or a
        // process restart cannot post the same reminder twice.
        assertEquals(
            ScheduledNotificationStore.State.DELIVERING,
            ScheduledNotificationStore(context).get("a")!!.state,
        )
        assertNull(store.claim("a", now = 2_000L))
    }

    @Test
    fun `delivered requires a claim and cannot be claimed again`() {
        store.add(record("b"))
        store.claim("b")
        assertTrue(store.markDelivered("b", now = 5_000L))
        val delivered = store.get("b")!!
        assertEquals(ScheduledNotificationStore.State.DELIVERED, delivered.state)
        assertEquals(5_000L, delivered.deliveredAtMs)
        assertNull(store.claim("b"))
    }

    @Test
    fun `failed records the delivery error from pending or delivering`() {
        store.add(record("c"))
        assertTrue(store.markFailed("c", "post_denied"))

        store.add(record("d"))
        store.claim("d")
        assertTrue(store.markFailed("d", "post_failed"))
        assertEquals(
            ScheduledNotificationStore.State.FAILED,
            store.get("d")!!.state,
        )
        assertEquals("post_failed", store.get("d")!!.error)
    }

    @Test
    fun `a pending reminder can be cancelled but a delivering one cannot`() {
        store.add(record("e"))
        assertTrue(store.markCancelled("e"))
        assertEquals(
            ScheduledNotificationStore.State.CANCELLED,
            store.get("e")!!.state,
        )

        store.add(record("f"))
        store.claim("f")
        assertFalse(store.markCancelled("f"))
        assertEquals(
            ScheduledNotificationStore.State.DELIVERING,
            store.get("f")!!.state,
        )
    }

    @Test
    fun `interrupted delivering claims become failed and fresh claims survive`() {
        val now = 10_000_000L
        store.add(
            record("stale", state = ScheduledNotificationStore.State.DELIVERING)
                .copy(claimedAtMs = now - ScheduledNotificationScheduler.STALE_CLAIM_MS - 1),
        )
        store.add(
            record("fresh", state = ScheduledNotificationStore.State.DELIVERING)
                .copy(claimedAtMs = now - 1_000L),
        )

        assertEquals(1, store.recoverInterrupted(now, ScheduledNotificationScheduler.STALE_CLAIM_MS))
        assertEquals(
            ScheduledNotificationStore.State.FAILED,
            store.get("stale")!!.state,
        )
        assertEquals(
            ScheduledNotificationStore.State.DELIVERING,
            store.get("fresh")!!.state,
        )
    }

    @Test
    fun `history is newest first and keeps only bounded terminal records`() {
        store.add(record("old", createdAtMs = 1))
        store.add(record("new", createdAtMs = 3))
        store.add(record("mid", createdAtMs = 2))
        assertEquals(
            listOf("new", "mid", "old"),
            store.history(10).map { it.id },
        )

        for (i in 0 until ScheduledNotificationStore.MAX_TERMINAL_RECORDS + 5) {
            val id = "done-$i"
            store.add(
                record(id, createdAtMs = 1_000L + i)
                    .copy(
                        state = ScheduledNotificationStore.State.DELIVERED,
                        deliveredAtMs = 1_000L + i,
                    ),
            )
        }
        val terminal = store.history(500)
            .count { it.state.isTerminal }
        assertTrue(terminal <= ScheduledNotificationStore.MAX_TERMINAL_RECORDS)
    }

    @Test
    fun `a retryable scheduling error leaves the reminder pending`() {
        store.add(record("retry"))
        assertTrue(store.noteError("retry", "exact_alarm_denied"))
        val record = store.get("retry")!!
        assertEquals(ScheduledNotificationStore.State.PENDING, record.state)
        assertEquals("exact_alarm_denied", record.error)
    }

    @Test
    fun `cancel all only changes pending records`() {
        store.add(record("p1"))
        store.add(record("p2"))
        store.add(
            record("done", state = ScheduledNotificationStore.State.DELIVERED)
                .copy(deliveredAtMs = 5L),
        )
        assertEquals(2, store.markAllCancelled())
        assertEquals(
            ScheduledNotificationStore.State.CANCELLED,
            store.get("p1")!!.state,
        )
        assertEquals(
            ScheduledNotificationStore.State.DELIVERED,
            store.get("done")!!.state,
        )
    }
}

package com.openminis.app.offload

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * [T-android-reminder-delivery-state] P0's actual promise: a restart must
 * re-arm future reminders and deliver overdue ones instead of dropping them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScheduledNotificationSchedulerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(ScheduledNotificationStore.PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private fun record(id: String, triggerAtMs: Long) =
        ScheduledNotificationStore.Record(
            id = id,
            title = "提醒 $id",
            body = "body $id",
            triggerAtMs = triggerAtMs,
            triggerAt = "2026-11-15T20:00:00+08:00",
            requestCode = id.hashCode() and 0x7FFFFFFF,
            state = ScheduledNotificationStore.State.PENDING,
            createdAtMs = System.currentTimeMillis(),
        )

    @Test
    fun `future reminders are re-armed and overdue ones are delivered`() {
        val store = ScheduledNotificationStore(context)
        val now = System.currentTimeMillis()
        val future = record("future", now + 60_000)
        val overdue = record("overdue", now - 1_000)
        store.add(future)
        store.add(overdue)

        val report = ScheduledNotificationScheduler.rescheduleAll(context)

        assertEquals(1, report.rescheduled)
        assertEquals(1, report.deliveredLate)
        assertEquals(
            ScheduledNotificationStore.State.PENDING,
            store.get("future")!!.state,
        )
        assertEquals(
            ScheduledNotificationStore.State.DELIVERED,
            store.get("overdue")!!.state,
        )

        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val armed = Shadows.shadowOf(am).scheduledAlarms
        assertTrue(armed.any { it.triggerAtTime == future.triggerAtMs })

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val posted = Shadows.shadowOf(nm).allNotifications
        assertTrue(
            posted.any {
                it.extras.getString(Notification.EXTRA_TITLE) == "提醒 overdue"
            },
        )
    }

    @Test
    fun `an interrupted delivery is marked failed before rescheduling`() {
        val store = ScheduledNotificationStore(context)
        val stale = record("stale", System.currentTimeMillis() + 60_000).copy(
            state = ScheduledNotificationStore.State.DELIVERING,
            claimedAtMs = System.currentTimeMillis() -
                ScheduledNotificationScheduler.STALE_CLAIM_MS - 1,
        )
        store.add(stale)

        val report = ScheduledNotificationScheduler.rescheduleAll(context)

        assertEquals(1, report.recovered)
        assertEquals(
            ScheduledNotificationStore.State.FAILED,
            store.get("stale")!!.state,
        )
    }
}

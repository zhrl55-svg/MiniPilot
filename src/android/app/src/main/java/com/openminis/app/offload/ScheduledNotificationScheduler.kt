package com.openminis.app.offload

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.openminis.app.logging.AppLogger

/**
 * AlarmManager side of scheduled notifications.
 *
 * [T-android-reminder-delivery-state] Scheduling, cancellation and reboot
 * recovery live here so the offload handler, [ScheduledNotificationReceiver]
 * and [com.openminis.app.offload.AlarmReceiver] all use one PendingIntent
 * shape. The receiver reads title/body from the store by id, so re-arming an
 * existing record cannot drift from its persisted content.
 *
 * One-shot exact alarms do not survive a reboot, a force-stop or an app
 * update. [rescheduleAll] is called from all three recovery entry points; it
 * re-arms future reminders, immediately delivers overdue ones, and marks
 * interrupted `delivering` claims failed instead of forgetting them.
 */
internal object ScheduledNotificationScheduler {

    private const val TAG = "ScheduledNotifSched"

    /**
     * A claim older than this is treated as interrupted by process death.
     * Posting should take well under a second; the margin only avoids racing a
     * live receiver during a concurrent cold start.
     */
    internal const val STALE_CLAIM_MS = 10_000L
    private const val MAX_ERROR_CHARS = 500

    data class Result(
        val success: Boolean,
        val error: String? = null,
        val exactAlarmDenied: Boolean = false,
    )

    data class Report(
        val recovered: Int,
        val rescheduled: Int,
        val deliveredLate: Int,
        val failed: Int,
    ) {
        override fun toString(): String =
            "recovered=$recovered rescheduled=$rescheduled deliveredLate=$deliveredLate failed=$failed"
    }

    private fun pendingIntent(
        context: Context,
        id: String,
        requestCode: Int,
    ): PendingIntent {
        val intent = Intent(context, ScheduledNotificationReceiver::class.java).apply {
            putExtra(ScheduledNotificationReceiver.EXTRA_ID, id)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun schedule(context: Context, record: ScheduledNotificationStore.Record): Result {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: return Result(false, "alarm_service_unavailable")
        val pi = pendingIntent(context, record.id, record.requestCode)
        return try {
            am.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                record.triggerAtMs,
                pi,
            )
            Result(true)
        } catch (e: SecurityException) {
            Result(
                false,
                exactAlarmDeniedMessage(context, e).take(MAX_ERROR_CHARS),
                exactAlarmDenied = true,
            )
        } catch (t: Throwable) {
            Result(
                false,
                "schedule_failed: ${t.message ?: t.javaClass.simpleName}".take(MAX_ERROR_CHARS),
            )
        }
    }

    fun cancel(context: Context, record: ScheduledNotificationStore.Record) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = pendingIntent(context, record.id, record.requestCode)
        am.cancel(pi)
        pi.cancel()
    }

    /**
     * Reconciles every persisted reminder after a reboot, an app update or a
     * cold process start. Idempotent: PendingIntents use the record's stable
     * request code, so re-arming replaces rather than duplicates an alarm.
     */
    fun rescheduleAll(context: Context): Report {
        val store = ScheduledNotificationStore(context)
        val now = System.currentTimeMillis()
        val recovered = store.recoverInterrupted(now, STALE_CLAIM_MS)
        var rescheduled = 0
        var deliveredLate = 0
        var failed = 0

        for (record in store.active()) {
            // A fresh delivering claim belongs to a live receiver; leave it.
            if (record.state == ScheduledNotificationStore.State.DELIVERING) continue
            if (record.triggerAtMs <= now) {
                if (ScheduledNotificationDelivery.deliver(context, record.id)) {
                    deliveredLate++
                } else {
                    failed++
                }
            } else {
                val result = schedule(context, record)
                if (result.success) {
                    rescheduled++
                } else {
                    // Keep the record pending with the real error. Exact-alarm
                    // access can be granted later; marking it failed here would
                    // bury a reminder the user still expects to fire.
                    store.noteError(record.id, result.error ?: "schedule_failed")
                    failed++
                }
            }
        }

        val report = Report(recovered, rescheduled, deliveredLate, failed)
        if (report.recovered > 0 || report.rescheduled > 0 ||
            report.deliveredLate > 0 || report.failed > 0
        ) {
            AppLogger.info(TAG, "reminder recovery: $report")
        }
        return report
    }

    fun exactAlarmDeniedMessage(context: Context, cause: Throwable? = null): String {
        val canSchedule = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                (context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)
                    ?.canScheduleExactAlarms() == true
            } catch (_: Throwable) {
                false
            }
        } else {
            true
        }
        return if (!canSchedule) {
            "exact_alarm_denied: grant 'Alarms & reminders' in Settings; on Xiaomi/Huawei/Oppo/OnePlus/Vivo also enable autostart and disable battery optimization."
        } else {
            "exact_alarm_denied: ${cause?.message ?: "AlarmManager rejected the exact alarm"}"
        }
    }
}

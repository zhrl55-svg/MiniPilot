package com.openminis.app.offload

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.openminis.app.R
import com.openminis.app.logging.AppLogger

/**
 * Delivers one scheduled notification exactly once.
 *
 * [T-android-reminder-delivery-state] The claim is written before the post, so
 * a duplicate alarm, a second process wake-up or a user cancel cannot produce
 * two notifications. The outcome is written back afterwards: `delivered` only
 * when `NotificationManager.notify` returned, `failed` with the real error
 * otherwise. The record is never deleted on fire; history keeps it.
 */
internal object ScheduledNotificationDelivery {

    private const val TAG = "ScheduledNotifDelivery"
    internal const val CHANNEL_ID = "minis_agent_notifications"
    private const val CHANNEL_NAME = "Agent Notifications"

    fun deliver(context: Context, id: String): Boolean {
        val store = ScheduledNotificationStore(context)
        val record = store.claim(id) ?: return false
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as? NotificationManager
        if (nm == null) {
            store.markFailed(id, "notification_service_unavailable")
            return false
        }
        ensureChannel(nm)

        return try {
            val notifId = record.id.hashCode() and 0x7FFFFFFF
            val title = record.title.ifBlank { "MiniPilot" }
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(record.body)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setContentIntent(
                    ScheduledNotificationReceiver.contentIntentFor(context, notifId),
                )
                .build()
            nm.notify(notifId, notification)
            store.markDelivered(id)
            AppLogger.info(TAG, "delivered id=$id title='$title'")
            true
        } catch (e: SecurityException) {
            val error = "post_denied: ${e.message ?: "POST_NOTIFICATIONS missing"}"
            AppLogger.error(TAG, "post denied id=$id — $error")
            store.markFailed(id, error)
            false
        } catch (t: Throwable) {
            val error = "post_failed: ${t.message ?: t.javaClass.simpleName}"
            AppLogger.warning(TAG, "post failed id=$id — $error")
            store.markFailed(id, error)
            false
        }
    }

    private fun ensureChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
    }
}

package com.openminis.app.offload

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.openminis.app.logging.AppLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Listens for active status-bar notifications so the agent can read them.
 *
 * Requires the user to manually grant "Notification access" in system
 * settings (Android enforces this — it can't be requested via the normal
 * runtime permission flow). [isEnabled] / [openSettings] help the caller
 * guide the user through that one-time grant.
 */
class MinisNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        connected = true
    }

    override fun onListenerDisconnected() {
        instance = null
        connected = false
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // The system fans posts to bound listeners on its own thread, so a
        // notification we just sent via `NotificationManager.notify` won't
        // appear in `getActiveNotifications()` synchronously. `handleSend`
        // registers a latch keyed by (package, id) here so it can block
        // until the listener has actually observed its own post.
        val key = postKey(sbn.packageName, sbn.id)
        postedLatches.remove(key)?.countDown()

        // [T-android-notification-summary] Keep a short in-memory copy so the
        // `notifications` tool can answer "刚才谁发消息". Memory only.
        recordToInbox(sbn)
    }

    private fun recordToInbox(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val notification = sbn.notification ?: return
        // Ongoing cards (music, navigation, charging) are not messages.
        if ((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) return
        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)
            ?.toString()?.trim().orEmpty().take(MAX_TITLE)
        val body = buildList {
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.let(::add)
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.let(::add)
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { add(it.toString()) }
        }.distinct().joinToString(" ").trim().take(MAX_TEXT)
        if (title.isBlank() && body.isBlank()) return
        NotificationInbox.add(
            NotificationInbox.Entry(
                packageName = sbn.packageName,
                appName = appLabel(sbn.packageName),
                title = title,
                text = body,
                postedAt = sbn.postTime,
            ),
        )
    }

    /** Application label ("微信"), falling back to the package name. */
    private fun appLabel(pkg: String): String = appLabels.getOrPut(pkg) {
        runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {}

    companion object {
        private const val TAG = "NotifListener"
        private const val MAX_TITLE = 100
        private const val MAX_TEXT = 500

        /** Resolved app labels, so a burst of posts does not re-hit PackageManager. */
        private val appLabels = ConcurrentHashMap<String, String>()

        @Volatile private var connected: Boolean = false
        @Volatile private var instance: MinisNotificationListenerService? = null

        /** Latches awaiting a specific (package, id) post. */
        private val postedLatches = ConcurrentHashMap<String, CountDownLatch>()

        private fun postKey(pkg: String, id: Int): String = "$pkg#$id"

        /** True once the system has bound our listener (implies access granted). */
        fun isConnected(): Boolean = connected

        /**
         * Block (up to [timeoutMs]) until [onNotificationPosted] fires for
         * `(pkg, id)`. Returns true if observed, false on timeout. If the
         * post has *already* arrived (visible in `activeNotifications`),
         * returns true immediately. Used by `android-notification send` so
         * a follow-up `list` always sees the freshly-posted notification.
         */
        fun awaitPosted(pkg: String, id: Int, timeoutMs: Long = 2_000L): Boolean {
            val svc = instance ?: run {
                AppLogger.warning(TAG, "awaitPosted($pkg#$id) called but listener not connected")
                return false
            }
            // Already there? `activeNotifications` is a snapshot of what the
            // system has fanned out to us, so a hit here means the post has
            // already propagated and no waiting is needed.
            try {
                if (svc.activeNotifications.any { it.packageName == pkg && it.id == id }) {
                    return true
                }
            } catch (_: SecurityException) {
                // Listener bound but not yet authorized — fall through to wait.
            }
            val key = postKey(pkg, id)
            val latch = postedLatches.computeIfAbsent(key) { CountDownLatch(1) }
            // Re-check after registering the latch — closes the race where the
            // post lands between our snapshot read and the latch insertion.
            try {
                if (svc.activeNotifications.any { it.packageName == pkg && it.id == id }) {
                    postedLatches.remove(key)
                    return true
                }
            } catch (_: SecurityException) {}
            val ok = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            postedLatches.remove(key)
            if (!ok) AppLogger.warning(TAG, "awaitPosted($key) timed out after ${timeoutMs}ms")
            return ok
        }

        /**
         * Returns the live list of active status-bar notifications across all
         * apps, or null if the listener isn't bound yet. Unlike
         * `NotificationManager.activeNotifications` (which only returns this
         * app's own notifications), this reads via `NotificationListenerService`
         * which has access to everyone else's.
         */
        fun getActiveNotifications(): Array<StatusBarNotification>? {
            return try {
                instance?.activeNotifications
            } catch (_: SecurityException) {
                null
            }
        }

        /** True if the user has granted Notification Access for this app. */
        fun isEnabled(context: Context): Boolean {
            val pkg = context.packageName
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            ) ?: return false
            return flat.split(':').any { entry ->
                val cn = ComponentName.unflattenFromString(entry) ?: return@any false
                cn.packageName == pkg
            }
        }

        /** Intent action for opening the Notification Access settings page. */
        const val SETTINGS_ACTION: String = Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
    }
}

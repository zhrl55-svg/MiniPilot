package com.openminis.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.openminis.app.R
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.chat.LocalActionApprovalCenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [T-android-approval-overlay] 本地写操作审批的后台通知。
 *
 * 审批卡在应用内由 [com.openminis.app.ui.chat.LocalActionApprovalHost] 根级
 * 渲染，但 App 退到后台后没有界面可看——而这张卡只有十几秒的有效期，
 * 错过了就等于用户拒绝了操作。这里在 App 不在前台时补一条高优先级通知：
 * 点击回到发起会话，审批仍在应用内完成（不在通知里直接批准，避免锁屏
 * 误触）。
 *
 * 与 [UserAskNotifier] 同一套协调方式：收集审批状态 + 前后台，前台有卡
 * 就不打扰，回到前台自动撤掉通知。
 */
object LocalActionNotifier {

    private const val TAG = "ActionApproval"
    private const val CHANNEL_ID = "minis_action_approval"
    private const val NOTIFICATION_ID = 7302

    private val installed = AtomicBoolean(false)
    private lateinit var app: Context

    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return
        app = context.applicationContext
        ensureChannel()
        val foreground = ProcessLifecycleOwner.get().lifecycle.currentStateFlow
            .map { it.isAtLeast(Lifecycle.State.STARTED) }
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            combine(LocalActionApprovalCenter.pending, foreground) { pending, fg -> pending to fg }
                .collect { (pending, fg) ->
                    runCatching {
                        // Foreground: the dialog itself is on screen, a
                        // notification would only duplicate it.
                        if (pending == null || fg) cancel() else post(pending)
                    }.onFailure {
                        AppLogger.warning(TAG, "sync failed: ${it.javaClass.simpleName}: ${it.message}")
                    }
                }
        }
    }

    private fun post(pending: LocalActionApprovalCenter.Pending) {
        val nm = NotificationManagerCompat.from(app)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !nm.areNotificationsEnabled()) {
            AppLogger.info(TAG, "notify skipped: notifications disabled")
            return
        }
        val body = if (pending.action.summary.isBlank()) {
            pending.action.title
        } else {
            "${pending.action.title}：${pending.action.summary}"
        }
        val builder = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_question)
            .setContentTitle(app.getString(R.string.local_action_notif_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openChat(pending.sessionId))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            // The card cancels itself; so must its notification.
            .setTimeoutAfter(pending.action.timeoutSeconds * 1000L)
        nm.notify(NOTIFICATION_ID, builder.build())
        AppLogger.info(TAG, "notified action=${pending.action.capability}/${pending.action.action}")
    }

    private fun cancel() {
        runCatching {
            NotificationManagerCompat.from(app).cancel(NOTIFICATION_ID)
        }
    }

    private fun openChat(sessionId: String): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("minis://session/$sessionId"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            app, "action-approval".hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = ContextCompat.getSystemService(app, NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        // High: the card times out in ~15 s, so it has to pop, not wait in the shade.
        val channel = NotificationChannel(
            CHANNEL_ID,
            app.getString(R.string.local_action_notif_channel),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = app.getString(R.string.local_action_notif_channel_desc)
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }
}

package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.offload.MinisNotificationListenerService
import com.openminis.app.offload.NotificationInbox
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

/**
 * [T-android-notification-summary] Read the most recent status-bar
 * notifications.
 *
 * The data comes from [NotificationInbox], an in-memory cache filled by
 * [MinisNotificationListenerService] while the user has granted notification
 * access. Nothing is persisted, so the answer can only cover notifications
 * received since the process started.
 */
object NotificationsTool {

    const val NAME = "notifications"

    private const val DEFAULT_LIMIT = 5
    private const val MAX_LIMIT = 20

    const val NO_PERMISSION_MESSAGE =
        "还没有通知读取权限，请在系统设置 → 通知管理里给 Minis 开启通知访问权限。"
    const val EMPTY_MESSAGE =
        "暂时没有可读的新通知（只记录开启通知访问后收到的通知）。"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Read the user's most recent status-bar notifications (app, title, text " +
            "and time). Use for \"谁发消息了\", \"读一下通知\", \"有什么通知\". Requires the " +
            "user to have granted notification access; only notifications received since the " +
            "app process started are available.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user. Use the same language as the user."),
            "limit" to AgentToolParam("integer", "Maximum notifications to return (default 5, max 20)."),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title", "limit"),
    )

    /** Blocking call — the caller runs it on an IO dispatcher. */
    fun execute(context: Context, argsJson: String): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrNull()
            ?: return ToolExecutionResult("参数不是合法 JSON", false)
        val limit = args.optInt("limit", DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

        if (!MinisNotificationListenerService.isEnabled(context)) {
            return ToolExecutionResult(NO_PERMISSION_MESSAGE, false)
        }
        val entries = NotificationInbox.recent(limit)
        if (entries.isEmpty()) {
            return ToolExecutionResult(EMPTY_MESSAGE, true, spokenSummary = "暂时没有可读的新通知。")
        }
        return ToolExecutionResult(
            format(entries, limit),
            true,
            spokenSummary = spokenSummary(entries),
        )
    }

    /**
     * Pure formatter: newest first, `HH:mm` clock, app name falling back to
     * the package name when the label could not be resolved.
     */
    internal fun format(entries: List<NotificationInbox.Entry>, limit: Int): String {
        val shown = entries.take(limit.coerceIn(1, MAX_LIMIT))
        if (shown.isEmpty()) return EMPTY_MESSAGE
        val clock = SimpleDateFormat("HH:mm", Locale.getDefault())
        return buildString {
            append("最近的通知（最多 ").append(shown.size).append(" 条）：")
            shown.forEachIndexed { index, entry ->
                append('\n').append(index + 1).append(". ")
                append(entry.appName.ifBlank { entry.packageName.ifBlank { "未知应用" } })
                if (entry.title.isNotBlank()) append(" · ").append(entry.title)
                if (entry.text.isNotBlank()) append("：").append(entry.text)
                append("（").append(clock.format(Date(entry.postedAt))).append("）")
            }
        }
    }

    private fun spokenSummary(entries: List<NotificationInbox.Entry>): String {
        if (entries.isEmpty()) return ""
        val first = entries.first()
        val app = first.appName.ifBlank { first.packageName }
        return "最近有 ${entries.size} 条通知。第一条来自 $app。"
    }
}

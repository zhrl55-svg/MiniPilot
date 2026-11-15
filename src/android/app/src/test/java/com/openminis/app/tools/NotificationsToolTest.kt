package com.openminis.app.tools

import com.openminis.app.offload.NotificationInbox
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-notification-summary] Formatting is pure: time is rendered as
 * HH:mm, a missing app label degrades to the package name, and `limit` cuts
 * the list from the newest end.
 */
class NotificationsToolTest {

    private fun at(hour: Int, minute: Int): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private val entries = listOf(
        NotificationInbox.Entry(
            packageName = "com.tencent.mm",
            appName = "微信",
            title = "张三",
            text = "今晚八点",
            postedAt = at(15, 42),
        ),
        NotificationInbox.Entry(
            packageName = "com.example.app",
            appName = "",
            title = "标题",
            text = "正文",
            postedAt = at(9, 5),
        ),
    )

    @Test
    fun `formats app title body and HH mm time`() {
        val text = NotificationsTool.format(entries, 5)
        assertTrue(text.startsWith("最近的通知（最多 2 条）："))
        assertTrue(text.contains("1. 微信 · 张三：今晚八点（15:42）"))
        assertTrue(text.contains("（09:05）"))
    }

    @Test
    fun `missing app name falls back to the package name`() {
        val text = NotificationsTool.format(entries, 5)
        assertTrue(text.contains("2. com.example.app · 标题：正文"))
    }

    @Test
    fun `limit truncates from the newest end`() {
        val text = NotificationsTool.format(entries, 1)
        assertTrue(text.startsWith("最近的通知（最多 1 条）："))
        assertTrue(text.contains("1. 微信"))
        assertFalse(text.contains("com.example.app"))
    }

    @Test
    fun `empty cache has a dedicated message`() {
        assertEquals(NotificationsTool.EMPTY_MESSAGE, NotificationsTool.format(emptyList(), 5))
        assertTrue(NotificationsTool.EMPTY_MESSAGE.contains("暂时没有可读的新通知"))
    }

    @Test
    fun `missing permission message points at notification access`() {
        assertTrue(NotificationsTool.NO_PERMISSION_MESSAGE.contains("通知访问权限"))
    }
}

package com.openminis.app.assistant.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-alarm-truthful-result] 闹钟设置的返回值必须反映闹钟**真的**被创建了。
 *
 * 真机反馈：用户说「设个 7 点闹钟」，华为时钟弹出了设置界面、值填好了
 * 7:00，用户没点勾（自己取消了），闹钟根本没建 —— 而端侧报的是
 * `handled: alarm/set success=true`。`ACTION_SET_ALARM` 是 fire-and-forget
 * 意图，`startActivity` 成功只说明系统时钟被拉起来了。
 */
class AlarmTruthTest {

    private fun readBack(
        supported: Boolean,
        count: Int = 0,
        hour: Int = 7,
    ) = AlarmTruth.ReadBack(
        readBackSupported = supported,
        alarmsAtTargetHour = count,
        targetHour = hour,
    )

    @Test
    fun `a confirmed alarm counts as created`() {
        val verdict = AlarmTruth.judge(
            dispatched = true,
            readBack = readBack(supported = true, count = 1),
            oemLabel = "HUAWEI",
        )
        assertEquals(AlarmTruth.Outcome.CREATED, verdict.outcome)
        assertTrue(verdict.succeeded)
    }

    @Test
    fun `a dispatch failure is a failure`() {
        val verdict = AlarmTruth.judge(
            dispatched = false,
            readBack = readBack(supported = true, count = 1),
            oemLabel = "HUAWEI",
        )
        assertEquals(AlarmTruth.Outcome.FAILED, verdict.outcome)
        assertFalse(verdict.succeeded)
    }

    /**
     * 回读可用但目标小时上没有任何闹钟 —— 闹钟确实没建（如用户在系统
     * 时钟里点了取消）。这必须报失败，不能报成功。
     */
    @Test
    fun `a confirmed absence is a failure`() {
        val verdict = AlarmTruth.judge(
            dispatched = true,
            readBack = readBack(supported = true, count = 0),
            oemLabel = "HUAWEI",
        )
        assertEquals(AlarmTruth.Outcome.FAILED, verdict.outcome)
        assertFalse(verdict.succeeded)
    }

    /**
     * OEM 时钟要在自己的 UI 里点一下才成立，回读不到时不能报成功，
     * 必须如实告诉调用方「需要你确认」。
     */
    @Test
    fun `an unverifiable alarm on an oem clock needs user confirmation`() {
        val verdict = AlarmTruth.judge(
            dispatched = true,
            readBack = readBack(supported = false),
            oemLabel = "HUAWEI",
        )
        assertEquals(AlarmTruth.Outcome.NEEDS_USER_CONFIRMATION, verdict.outcome)
        assertFalse(verdict.succeeded)
        assertTrue(verdict.message.contains("系统时钟"))
    }

    /**
     * AOSP / Pixel 认 EXTRA_SKIP_UI：派发即插入，回读不到就是真没建，
     * 所以这里报失败而不是「需要确认」。
     */
    @Test
    fun `an unverifiable alarm on an aosp clock is a failure`() {
        val verdict = AlarmTruth.judge(
            dispatched = true,
            readBack = readBack(supported = false),
            oemLabel = "Google",
        )
        assertEquals(AlarmTruth.Outcome.FAILED, verdict.outcome)
        assertFalse(verdict.succeeded)
    }

    @Test
    fun `aosp vendors honor skip ui and oem vendors do not`() {
        assertTrue(AlarmTruth.oemHonorsSkipUi("Google"))
        assertTrue(AlarmTruth.oemHonorsSkipUi("Google Pixel"))
        assertFalse(AlarmTruth.oemHonorsSkipUi("HUAWEI"))
        assertFalse(AlarmTruth.oemHonorsSkipUi("Xiaomi"))
        assertFalse(AlarmTruth.oemHonorsSkipUi("OPPO"))
        assertFalse(AlarmTruth.oemHonorsSkipUi("vivo"))
        assertFalse(AlarmTruth.oemHonorsSkipUi("samsung"))
    }
}

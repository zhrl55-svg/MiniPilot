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
        baseline: Int = 0,
    ) = AlarmTruth.ReadBack(
        readBackSupported = supported,
        alarmsAtTargetHour = count,
        targetHour = hour,
        baselineAlarmsAtHour = baseline,
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
     * 回读可用但目标小时上没有任何**新增**的闹钟 —— 闹钟还没生效。
     *
     * 厂商时钟此刻正开着界面等用户点勾，所以这是「还没生效」而不是
     * 「建失败」：说成失败会让用户以为闹钟没设上。
     */
    @Test
    fun `a confirmed absence on an oem clock needs user confirmation`() {
        val verdict = AlarmTruth.judge(
            dispatched = true,
            readBack = readBack(supported = true, count = 0),
            oemLabel = "HUAWEI",
        )
        assertEquals(AlarmTruth.Outcome.NEEDS_USER_CONFIRMATION, verdict.outcome)
        assertFalse(verdict.succeeded)
    }

    /**
     * AOSP / Pixel 认 EXTRA_SKIP_UI：派发即插入，回读不到新增就是真没建。
     */
    @Test
    fun `a confirmed absence on an aosp clock is a failure`() {
        val verdict = AlarmTruth.judge(
            dispatched = true,
            readBack = readBack(supported = true, count = 0),
            oemLabel = "Google",
        )
        assertEquals(AlarmTruth.Outcome.FAILED, verdict.outcome)
        assertFalse(verdict.succeeded)
    }

    /**
     * 用户早先就设过一个同小时的闹钟时，「目标小时上有闹钟」**不能**
     * 证明这次派发成功了 —— 只认新增的那些。
     *
     * 第一次真机验证就撞上了这个坑：上一轮遗留的 7 点闹钟让回读立刻判成
     * 成功，而新的闹钟其实还要用户在系统时钟里点勾。
     */
    @Test
    fun `a pre-existing alarm at the same hour does not count as created`() {
        val verdict = AlarmTruth.judge(
            dispatched = true,
            readBack = readBack(supported = true, count = 1, baseline = 1),
            oemLabel = "HUAWEI",
        )
        assertEquals(AlarmTruth.Outcome.NEEDS_USER_CONFIRMATION, verdict.outcome)
        assertFalse(verdict.succeeded)
    }

    @Test
    fun `a pre-existing alarm at the same hour is a failure on an aosp clock`() {
        val verdict = AlarmTruth.judge(
            dispatched = true,
            readBack = readBack(supported = true, count = 1, baseline = 1),
            oemLabel = "Google",
        )
        assertEquals(AlarmTruth.Outcome.FAILED, verdict.outcome)
        assertFalse(verdict.succeeded)
    }

    @Test
    fun `an alarm added on top of a pre-existing one counts as created`() {
        val verdict = AlarmTruth.judge(
            dispatched = true,
            readBack = readBack(supported = true, count = 2, baseline = 1),
            oemLabel = "HUAWEI",
        )
        assertEquals(AlarmTruth.Outcome.CREATED, verdict.outcome)
        assertTrue(verdict.succeeded)
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

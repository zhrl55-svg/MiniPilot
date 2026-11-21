package com.openminis.app.assistant.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-alarm-truthful-result] 解析 `dumpsys alarm`，找出系统里真正注册的
 * 闹钟 —— 这是闹钟回读校验的数据来源。
 *
 * 样本取自真机（华为 ELS-AN00 / com.huawei.deskclock）的真实排版：
 * ```
 * RTC_WAKEUP #4: Alarm{9da12d4 type 0 origWhen 1795241600693 ...}
 *   tag=*walarm*:com.huawei.push.alarm.HEARTBEAT
 *   type=RTC_WAKEUP origWhen=2026-11-21 14:13:20.693 window=0 ...
 * ```
 * 以及「Next alarm clock information:」段的单行排版。
 */
class AlarmDumpParserTest {

    private val dump = """
        Current Alarm Manager state:
          Pending alarm batches: 1
          Batch{9d1c0a1 num=1 start=+30m}:
            RTC_WAKEUP #0: Alarm{3f2a1b0 type 0 origWhen 1795242400000 whenElapsed 2132260664 PendingIntent{7a2b3c4: PendingIntentRecord{17cfb19 com.huawei.deskclock broadcastIntent}} com.huawei.deskclock procName com.huawei.deskclock}
              tag=*walarm*:huawei.deskclock.action.alarm_alert
              type=RTC_WAKEUP origWhen=2026-11-21 07:00:00.000 window=0 exactAllowReason=permission repeatInterval=0 count=0 flags=0x0
              policyWhenElapsed: requester=-1m58s175ms
            RTC_WAKEUP #1: Alarm{5e6f7a8 type 0 origWhen 1795139722000 whenElapsed 2132318770 PendingIntent{9b0c1d2: PendingIntentRecord{7218f40 com.huawei.deskclock broadcastIntent}} com.huawei.deskclock procName com.huawei.deskclock}
              tag=*walarm*:huawei.deskclock.action.timer_alert
              type=RTC_WAKEUP origWhen=2026-11-13 23:40:45.286 window=0 repeatInterval=0 count=0 flags=0x0
              policyWhenElapsed: requester=-1m58s175ms
            RTC_WAKEUP #2: Alarm{1a2b3c4 type 0 origWhen 1795139723000 whenElapsed 2132318838 PendingIntent{3c4d5e6: PendingIntentRecord{314fc2b com.huawei.deskclock broadcastIntent}} com.huawei.deskclock procName com.huawei.deskclock}
              tag=*walarm*:huawei.deskclock.action.alarm_alert reason=alarm_cancelled
              type=RTC_WAKEUP origWhen=2026-11-13 23:41:05.207 window=0 repeatInterval=0 count=0 flags=0x0
              policyWhenElapsed: requester=-1m58s175ms
            RTC #4: Alarm{9da12d4 type 1 origWhen 1795241472295 whenElapsed 2132260664 PendingIntent{e2e101b: PendingIntentRecord{17cfb19 com.huawei.android.thememanager broadcastIntent}} com.huawei.android.thememanager procName com.huawei.android.thememanager}
              tag=*alarm*:com.huawei.android.action.ALARM_TASK_REDPOINT
              type=RTC origWhen=2026-11-21 14:11:12.295 window=+16h55m33s332ms repeatInterval=81244443 count=0 flags=0x0
              policyWhenElapsed: requester=-1m58s175ms
          Next alarm clock information:
              [tag=*walarm*:huawei.deskclock.action.timer_alert reason=unknown:6 elapsed=-7d14h27m58s975ms rtc=2026-11-13 23:37:12.720]
    """.trimIndent()

    @Test
    fun `pending alarms are parsed with their hour and minute`() {
        val entries = AlarmDumpParser.parse(dump)
        // 取消的那条不算数
        assertEquals(3, entries.size)
        assertEquals(7, entries[0].hour)
        assertEquals(0, entries[0].minute)
        assertEquals(23, entries[1].hour)
        assertEquals(40, entries[1].minute)
        // 「Next alarm clock information:」段的单行记录
        assertEquals(23, entries[2].hour)
        assertEquals(37, entries[2].minute)
    }

    @Test
    fun `cancelled alarms are not counted`() {
        val entries = AlarmDumpParser.parse(dump)
        assertTrue(entries.none { it.minute == 41 })
    }

    /**
     * `*alarm*:` 是系统与厂商自己的心跳 / 任务唤醒，不是用户的闹钟。
     * 把它们算进去会让回读校验误判「闹钟已建」。
     */
    @Test
    fun `system heartbeats are not user alarms`() {
        val entries = AlarmDumpParser.parse(dump)
        assertTrue(entries.none { it.hour == 14 && it.minute == 11 })
    }

    @Test
    fun `alarms at the target hour are counted`() {
        val entries = AlarmDumpParser.parse(dump)
        assertEquals(1, AlarmDumpParser.countAtHour(entries, 7))
        assertEquals(2, AlarmDumpParser.countAtHour(entries, 23))
        assertEquals(0, AlarmDumpParser.countAtHour(entries, 8))
    }

    @Test
    fun `an empty dump yields no alarms`() {
        assertTrue(AlarmDumpParser.parse("").isEmpty())
        assertTrue(AlarmDumpParser.parse("no alarms here").isEmpty())
    }

    @Test
    fun `blocks without a wall clock time are ignored`() {
        val dump = """
            RTC_WAKEUP #5: Alarm{abcdef0 type 0 origWhen 1795241472295}
              tag=*walarm*:com.example.something
              type=RTC_WAKEUP origWhen=not-a-time window=0
        """.trimIndent()
        assertTrue(AlarmDumpParser.parse(dump).isEmpty())
    }
}

package com.openminis.app.assistant.execution

import com.openminis.app.offload.ShizukuManager

/**
 * [T-alarm-truthful-result] Android 侧的回读校验门面：派发闹钟之后，确认
 * 系统里真的多了一条闹钟。
 *
 * 纯逻辑在 [AlarmTruth] 与 [AlarmDumpParser] 里，这里只负责「拿到数据」。
 * 拿不到（没有 Shizuku / 命令不可用 / 输出解析不出闹钟）时如实告诉
 * [AlarmTruth.judge]「回读不可用」，由它按厂商分支决定结论。
 */
object AlarmVerification {

    /**
     * 回读系统里当前注册在 [hour] 上的闹钟条数。
     *
     * 系统时钟是异步落盘的：派发后闹钟要过一会儿才注册上，所以这里轮询
     * 几次（与日历读回同一套节奏），期间任何一次读到就算数。
     */
    fun readBackAlarmsAtHour(hour: Int): AlarmTruth.ReadBack {
        if (!ShizukuManager.isReady()) {
            return AlarmTruth.ReadBack(
                readBackSupported = false,
                alarmsAtTargetHour = 0,
                targetHour = hour,
            )
        }
        repeat(READBACK_ATTEMPTS) { attempt ->
            val result = ShizukuManager.runProcess(
                arrayOf("dumpsys", "alarm"),
                timeoutMs = 8_000L,
            )
            if (result.exitCode == 0) {
                val entries = AlarmDumpParser.parse(result.stdout)
                if (entries.isNotEmpty()) {
                    // 读到了系统闹钟数据 —— 回读可用，条数就是结论。
                    return AlarmTruth.ReadBack(
                        readBackSupported = true,
                        alarmsAtTargetHour = AlarmDumpParser.countAtHour(entries, hour),
                        targetHour = hour,
                    )
                }
            }
            if (attempt < READBACK_ATTEMPTS - 1) Thread.sleep(READBACK_INTERVAL_MS)
        }
        // 命令跑了但一条闹钟都读不到：要么系统里真的没有闹钟，要么这条
        // 设备上的 dumpsys 格式不认。两种情况都按「回读不可用」处理，
        // 交给 [AlarmTruth.judge] 按厂商分支定结论。
        return AlarmTruth.ReadBack(
            readBackSupported = false,
            alarmsAtTargetHour = 0,
            targetHour = hour,
        )
    }

    /**
     * 派发之后判定闹钟到底建没建。
     *
     * @param hour 目标小时。
     */
    fun verifyCreated(hour: Int): AlarmTruth.Verdict = AlarmTruth.judge(
        dispatched = true,
        readBack = readBackAlarmsAtHour(hour),
        oemLabel = com.openminis.app.sandbox.offload.OsCompat.oemLabel(),
    )

    /** 轮询次数与间隔（状态异步落盘，给系统一点时间）。 */
    private const val READBACK_ATTEMPTS = 6
    private const val READBACK_INTERVAL_MS = 400L
}

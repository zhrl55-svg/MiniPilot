package com.openminis.app.assistant.execution

import android.os.SystemClock
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
     * 派发**之前**系统里注册在 [hour] 上的闹钟条数。
     *
     * 这条基线是必需的：用户早先就设过一个 7 点闹钟时，「目标小时上有
     * 闹钟」并不能证明刚才那一次派发成功了 —— 第一次真机验证就撞上了
     * 这个坑：上一轮遗留的 7 点闹钟让回读立刻判成成功，而新的闹钟其实
     * 还要用户在系统时钟里点勾。只看「有没有」会撒谎，看「有没有变多」
     * 才是真的。
     */
    fun baselineAlarmsAtHour(hour: Int): Int {
        if (!ShizukuManager.isReady()) return 0
        val result = ShizukuManager.runProcess(
            arrayOf("dumpsys", "alarm"),
            timeoutMs = 8_000L,
        )
        if (result.exitCode != 0) return 0
        return AlarmDumpParser.countAtHour(AlarmDumpParser.parse(result.stdout), hour)
    }

    /**
     * 回读系统里当前注册在 [hour] 上的闹钟条数。
     *
     * 系统时钟是异步落盘的：派发后闹钟要过一会儿才注册上，所以这里轮询
     * 几次（与日历读回同一套节奏），期间任何一次读到就算数。回读受总
     * 预算限制：预算耗尽时和「次数用尽」一样按回读不可用处理，不让异常
     * 设备的慢 dumpsys 把语音轮拖住几十秒。
     */
    fun readBackAlarmsAtHour(hour: Int, baseline: Int = 0): AlarmTruth.ReadBack {
        if (!ShizukuManager.isReady()) {
            return AlarmTruth.ReadBack(
                readBackSupported = false,
                alarmsAtTargetHour = 0,
                targetHour = hour,
                baselineAlarmsAtHour = baseline,
            )
        }
        val startedAt = SystemClock.elapsedRealtime()
        var attempt = 0
        while (attempt < READBACK_ATTEMPTS &&
            SystemClock.elapsedRealtime() - startedAt <=
            READBACK_BUDGET_MS - READBACK_TIMEOUT_MS
        ) {
            val result = ShizukuManager.runProcess(
                arrayOf("dumpsys", "alarm"),
                timeoutMs = READBACK_TIMEOUT_MS,
            )
            if (result.exitCode == 0) {
                val entries = AlarmDumpParser.parse(result.stdout)
                if (entries.isNotEmpty()) {
                    // 读到了系统闹钟数据 —— 回读可用，条数就是结论。
                    return AlarmTruth.ReadBack(
                        readBackSupported = true,
                        alarmsAtTargetHour = AlarmDumpParser.countAtHour(entries, hour),
                        targetHour = hour,
                        baselineAlarmsAtHour = baseline,
                    )
                }
            }
            if (attempt < READBACK_ATTEMPTS - 1) Thread.sleep(READBACK_INTERVAL_MS)
            attempt++
        }
        // 命令跑了但一条闹钟都读不到：要么系统里真的没有闹钟，要么这条
        // 设备上的 dumpsys 格式不认。两种情况都按「回读不可用」处理，
        // 交给 [AlarmTruth.judge] 按厂商分支定结论。
        return AlarmTruth.ReadBack(
            readBackSupported = false,
            alarmsAtTargetHour = 0,
            targetHour = hour,
            baselineAlarmsAtHour = baseline,
        )
    }

    /**
     * 派发之后判定闹钟到底建没建。
     *
     * @param hour 目标小时。
     * @param baseline 派发前该小时上的闹钟条数（见 [baselineAlarmsAtHour]）。
     *   只有**新增**的闹钟才算这次派发的成果。
     */
    fun verifyCreated(hour: Int, baseline: Int): AlarmTruth.Verdict = AlarmTruth.judge(
        dispatched = true,
        readBack = readBackAlarmsAtHour(hour, baseline),
        oemLabel = com.openminis.app.sandbox.offload.OsCompat.oemLabel(),
    )

    /** 轮询次数与间隔（状态异步落盘，给系统一点时间）。 */
    private const val READBACK_ATTEMPTS = 6
    private const val READBACK_INTERVAL_MS = 400L

    /** 单次 dumpsys 的超时；与总预算一起决定最多能轮询几轮。 */
    private const val READBACK_TIMEOUT_MS = 8_000L

    /** 回读总预算：最多允许阻塞语音轮这么久，超了就走回读不可用。 */
    private const val READBACK_BUDGET_MS = 10_000L
}

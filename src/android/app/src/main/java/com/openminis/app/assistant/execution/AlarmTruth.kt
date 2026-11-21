package com.openminis.app.assistant.execution

/**
 * [T-alarm-truthful-result] 判定「闹钟到底有没有被创建」。
 *
 * ## 为什么需要它
 *
 * `ACTION_SET_ALARM` 是一个 **fire-and-forget** 意图：调用方
 * `startActivity(intent)` 成功只说明「系统时钟这个 Activity 被拉起来了」，
 * 不代表闹钟已经写进系统时钟的数据库。华为 / 小米等 OEM 的时钟会在
 * 自己的 UI 里要求用户手动点勾（源码注释 T262-1 已记：deskclock 的
 * `HandleSetApiCalls` 受 `SET_ALARM` 权限保护，不认静默插入），用户
 * 一点「取消」，闹钟就**根本没建**，而端侧却报了 `success=true`。
 *
 * 真机上已经出现过：用户说「设个 7 点闹钟」，界面弹出时钟、值填好了
 * 7:00，用户没点勾（自己取消了），而端侧日志是
 * `handled: alarm/set success=true`。
 *
 * ## 判定规则
 *
 * 三种结局，不再只有「成功 / 失败」：
 *
 * - [Outcome.CREATED] —— 回读校验确认闹钟真的在系统里（同一小时的闹钟
 *   出现在回读结果里）。
 * - [Outcome.NEEDS_USER_CONFIRMATION] —— 派发成功但**校验不到**（设备
 *   没有可读回的系统闹钟数据，或厂商时钟要求人工确认）。这种情况**不能**
 *   报成功：如实告诉调用方「需要在系统时钟里确认」。
 * - [Outcome.FAILED] —— 派发本身失败（没有时钟应用 / 权限缺失）。
 *
 * ## 厂商分支
 *
 * AOSP / Pixel 的 Clock 认 `EXTRA_SKIP_UI` 并静默插入，那种设备上回读
 * 一定能查到，回读失败就说明闹钟真的没建 —— 所以 [Outcome.FAILED] 在
 * 「认 SKIP_UI」的设备上才是正确的结论。OEM 厂商的时钟不认静默插入，
 * 闹钟要用户在系统时钟里点一下才成立，回读查不到时必须走
 * [Outcome.NEEDS_USER_CONFIRMATION]。
 *
 * 这个对象是纯逻辑，没有 Android 依赖，可以直接跑单测。
 */
object AlarmTruth {

    /** 闹钟设置的最终结局。 */
    enum class Outcome {
        /** 闹钟真的被创建了。 */
        CREATED,

        /**
         * 已经把系统时钟的设置界面拉起来了，但**无法确认闹钟是否已建**。
         * 调用方必须告诉用户「需要在系统时钟里确认」，不能报成功。
         */
        NEEDS_USER_CONFIRMATION,

        /** 派发失败，闹钟肯定没建。 */
        FAILED,
    }

    /** 一次闹钟设置的结果。 */
    data class Verdict(
        val outcome: Outcome,
        /** 给用户看的一句话（中文）。 */
        val message: String,
    ) {
        /** 只有真的建成才算成功。 */
        val succeeded: Boolean get() = outcome == Outcome.CREATED
    }

    /**
     * 回读校验的结果。
     *
     * @param readBackSupported 设备上能不能拿到「系统里有哪些闹钟」这份数据。
     *   拿不到（没有 Shizuku、命令不可用、输出解析不出来）时为 false。
     * @param alarmsAtTargetHour 回读结果里，落在目标小时上的闹钟条数。
     * @param targetHour 目标小时（0..23）。
     * @param baselineAlarmsAtHour 派发**之前**目标小时上的条数。只有**新增**
     *   的闹钟才算这次派发的成果 —— 用户早先就设过一个同小时的闹钟时，
     *   「目标小时上有闹钟」并不能证明刚才那次派发成功了。
     */
    data class ReadBack(
        val readBackSupported: Boolean,
        val alarmsAtTargetHour: Int,
        val targetHour: Int,
        val baselineAlarmsAtHour: Int = 0,
    ) {
        /** 这次派发新增了几条闹钟。 */
        val newAlarms: Int get() = alarmsAtTargetHour - baselineAlarmsAtHour
    }

    /**
     * 这个厂商的系统时钟认不认 `EXTRA_SKIP_UI`（静默插入）。
     *
     * - AOSP / Pixel：认。闹钟会直接写进系统时钟，回读一定能查到。
     * - 华为 / 小米 / OPPO / vivo / 三星等 OEM：不认或行为不确定，闹钟
     *   要用户在系统时钟里点一下才成立。
     *
     * OEM 的分派表放在这里而不是散落各处，是为了让「哪些厂商不认静默
     * 插入」这条知识只有一个来源。
     */
    fun oemHonorsSkipUi(oemLabel: String): Boolean {
        val oem = oemLabel.lowercase()
        // AOSP 系厂商：Pixel / Google 系，以及其它套用 AOSP Clock 的设备。
        val honors = oem.contains("google") ||
            oem.contains("pixel") ||
            oem.contains("aosp") ||
            oem.contains("android")
        return honors
    }

    /**
     * 综合派发结果与回读结果，给出最终结局。
     *
     * @param dispatched 派发本身是否成功（`startActivity` 没抛异常）。
     * @param readBack 回读校验的结果（基线也在里面）。
     * @param oemLabel 厂商标签，用于决定回读失败时的结论。
     */
    fun judge(
        dispatched: Boolean,
        readBack: ReadBack,
        oemLabel: String,
    ): Verdict {
        if (!dispatched) {
            return Verdict(
                Outcome.FAILED,
                "系统时钟没有接受这个闹钟（可能没有时钟应用，或缺少 SET_ALARM 权限）。",
            )
        }
        if (readBack.readBackSupported) {
            // 只认**新增**的闹钟：用户早先就设过一个同小时的闹钟时，
            // 「目标小时上有闹钟」并不能证明这次派发成功了。
            return if (readBack.newAlarms > 0) {
                Verdict(
                    Outcome.CREATED,
                    "闹钟已创建。",
                )
            } else {
                Verdict(
                    Outcome.FAILED,
                    "系统时钟没有接受这个闹钟，没有创建成功。",
                )
            }
        }
        // 回读不可用 —— 这是 OEM 的常态：时钟要求用户在自己 UI 里确认。
        return if (oemHonorsSkipUi(oemLabel)) {
            // AOSP/Pixel 认 SKIP_UI：派发即插入，回读却查不到，说明真的没建。
            Verdict(
                Outcome.FAILED,
                "系统时钟没有接受这个闹钟，没有创建成功。",
            )
        } else {
            Verdict(
                Outcome.NEEDS_USER_CONFIRMATION,
                "已经打开系统时钟并填好闹钟，但需要你在系统时钟里点确认才会生效。",
            )
        }
    }
}

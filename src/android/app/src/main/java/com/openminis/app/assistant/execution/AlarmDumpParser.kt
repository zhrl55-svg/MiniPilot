package com.openminis.app.assistant.execution

/**
 * [T-alarm-truthful-result] 解析 `dumpsys alarm` 的输出，找出系统里已经
 * 注册的闹钟。
 *
 * ## 输出长什么样
 *
 * `dumpsys alarm` 里每个闹钟是一个 `Alarm{...}` 块，**信息分布在相邻
 * 几行**：
 *
 * ```
 * RTC_WAKEUP #4: Alarm{9da12d4 type 0 origWhen 1795241600693 ...}
 *   tag=*walarm*:com.huawei.push.alarm.HEARTBEAT
 *   type=RTC_WAKEUP origWhen=2026-11-21 14:13:20.693 window=0 ...
 *   policyWhenElapsed: requester=+5s223ms ...
 * ```
 *
 * 时间在 `origWhen=`（墙钟形式 `YYYY-MM-DD HH:mm:ss.SSS`）；只有「Next
 * alarm clock information」那段用另一种单行排版，时间键是 `rtc=`。两种
 * 都认。
 *
 * 所以解析以「`tag=*walarm*:` 开头的一个块」为单位，块里找时间与
 * `reason=`。
 *
 * ## 解析口径
 *
 * - 只认带墙钟时间的条目：只有墙钟闹钟能被用户读到，秒表（timer）与
 *   ELAPSED 相对闹钟不是用户意义上的「闹钟」，不参与校验。
 * - 排除 `reason=alarm_cancelled` / `alarm_received` / `alarm_missed`：
 *   这三种都是已经结束的闹钟（取消掉 / 已经响过 / 错过），留在系统里不
 *   代表闹钟存在。
 * - 小时从时间字段的小时部分取（0..23）。
 *
 * 纯逻辑，没有 Android 依赖，可直接跑单测。
 */
object AlarmDumpParser {

    /** 一条待响闹钟。 */
    data class AlarmEntry(
        val tag: String,
        /** 触发时刻的小时（0..23）。 */
        val hour: Int,
        /** 触发时刻的分钟（0..59）。 */
        val minute: Int,
    )

    /** 已结束、不该算作「闹钟存在」的 reason。 */
    private val FINISHED_REASONS = setOf(
        "alarm_cancelled",
        "alarm_received",
        "alarm_missed",
    )

    /**
     * 解析 `dumpsys alarm` 的输出。
     *
     * @param dump 完整输出；空串表示没有可读数据（调用方据此认为回读不可用）。
     */
    fun parse(dump: String): List<AlarmEntry> {
        val out = mutableListOf<AlarmEntry>()
        var block: StringBuilder? = null

        for (rawLine in dump.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            // 一条闹钟从 `Alarm{...}` 那行开始。
            if (line.contains("Alarm{")) {
                block?.let { entryOf(it.toString())?.let { e -> out += e } }
                block = StringBuilder(line)
                continue
            }

            // 「Next alarm clock information:」段是单行排版：整条记录在一行
            // 里，以 `]` 收尾。它自己就是一个完整的块 —— 但要先收尾正在
            // 累积的多行块，否则两条会被并成一块。
            //
            // 多行块的 `tag=` 行不含 `]`，所以不会落进这个分支。
            if (line.contains("tag=*walarm*:") && line.contains("]")) {
                block?.let { entryOf(it.toString())?.let { e -> out += e } }
                block = null
                entryOf(line)?.let { e -> out += e }
                continue
            }

            val current = block ?: continue
            current.append(' ').append(line)
        }
        block?.let { entryOf(it.toString())?.let { e -> out += e } }
        return out
    }

    /**
     * 数一数目标时刻附近有多少条待响闹钟。
     *
     * 判「有没有建成功」只精确到小时：用户说的是「7 点」，分钟由系统自己
     * 取整到最近的匹配时刻（系统时钟的闹钟总是对齐到分钟），精确到分钟
     * 会因为秒数的舍入而误判。
     */
    fun countAtHour(entries: List<AlarmEntry>, hour: Int): Int =
        entries.count { it.hour == hour }

    /** 把一个块解析成一条闹钟；块不完整时返回 null。 */
    private fun entryOf(block: String): AlarmEntry? {
        val tag = extractValue(block, "tag=") ?: return null
        // 只有 `*walarm*:` 才是「闹钟」；`*alarm*:` 是系统/厂商自己的
        // 心跳、任务唤醒之类，与用户的闹钟无关。
        if (!tag.contains("*walarm*:")) return null

        val reason = extractValue(block, "reason=")
        if (reason != null && FINISHED_REASONS.any { it == reason }) return null

        // 墙钟时间：`origWhen=2026-11-21 14:13:20.693`（Alarm 块）或
        // `rtc=2026-11-13 23:37:12.720`（Next alarm 段）。
        val wallClock = extractValue(block, "origWhen=") ?: extractValue(block, "rtc=")
        val (hour, minute) = parseWallClock(wallClock) ?: return null
        return AlarmEntry(tag = tag, hour = hour, minute = minute)
    }

    /**
     * 取 `key=` 的值。
     *
     * 值的结束边界不是空格：`origWhen=2026-11-21 07:00:00.000` 里日期和
     * 时间之间就有空格。所以一直取到下一个 `key=`（形如 ` word=`）或行尾
     * 为止，再去掉尾部。
     */
    private fun extractValue(line: String, key: String): String? {
        val start = line.indexOf(key)
        if (start < 0) return null
        val valueStart = start + key.length
        val rest = line.substring(valueStart)
        // 下一个 key= 的位置：形如 " word="（前面有空格）。
        val nextKey = Regex("""\s[A-Za-z_][A-Za-z0-9_]*=""").find(rest)
        val raw = if (nextKey != null) rest.substring(0, nextKey.range.first) else rest
        return raw.trim().takeIf { it.isNotBlank() }
    }

    /**
     * 从墙钟时间里取出小时与分钟。
     *
     * 两种形态：
     * - `2026-11-21 14:13:20.693` —— 日期 + 时间
     * - `2026-11-13 23:37:12.720` —— 同上
     *
     * 也可能只给时间部分，所以按「最后一个冒号分隔的字段组」取。
     */
    private fun parseWallClock(value: String?): Pair<Int, Int>? {
        if (value.isNullOrBlank()) return null
        // 时间部分：日期之后的部分，或整个值（已经是 HH:mm:ss）。
        val timePart = value.substringAfter(' ').replace('T', ' ').trim()
        val segments = timePart.split(':')
        if (segments.size < 2) return null
        val hour = segments[0].toIntOrNull() ?: return null
        val minute = segments[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour to minute
    }
}

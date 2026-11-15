package com.openminis.app.agent.skill

/**
 * Goal similarity for the skill store.
 *
 * Two algorithms are kept side by side:
 *
 *  - [Algorithm.LEGACY] — the original character-bigram + Jaccard score, kept
 *    verbatim as a fallback. Its measured weakness is the reason this file
 *    exists: six semantically related Chinese tasks on 2026-11-15 scored at
 *    most 0.036 against each other, so with the 0.55 threshold nothing ever
 *    matched.
 *  - [Algorithm.HYBRID] — the default. It maps surface forms to a small,
 *    hand-curated concept lexicon (存储/占用/构成, 手电筒/闪光灯, 截屏/截图 …)
 *    and combines concept overlap with character overlap, so "看看手机存储
 *    都被什么占满了" and "查询一下 App 私有目录的占用构成" are recognised as
 *    the same task without sharing a single two-character run.
 *
 * No dependency beyond the stdlib, no model file, no network. The lexicon is
 * intentionally small and fact-based; an unknown task simply falls back to
 * character overlap.
 */
object SkillMatcher {
    /** Threshold of the original algorithm; also the legacy fallback default. */
    const val THRESHOLD = 0.55

    /**
     * Default threshold of the hybrid algorithm (see AgentDebugMethods
     * benches). 0.43 sits above the "shared cleanup verb" near-neighbour
     * score (0.42 for 清理缓存 / 清理聊天记录) and below every built-in
     * paraphrase pair (weakest 0.459).
     */
    const val DEFAULT_HYBRID_THRESHOLD = 0.43

    private const val SAME_APP_WEIGHT = 1.0
    private const val CROSS_APP_WEIGHT = 0.82
    /** Hybrid: one side without an app id is still a usable hint, just weaker. */
    private const val NEUTRAL_APP_WEIGHT = 0.95

    enum class Algorithm(val wire: String) {
        HYBRID("hybrid"),
        LEGACY("legacy"),
        ;

        companion object {
            fun fromWire(value: String?): Algorithm =
                entries.firstOrNull { it.wire == value?.trim()?.lowercase() } ?: HYBRID

            fun defaultThreshold(algorithm: Algorithm): Double = when (algorithm) {
                HYBRID -> DEFAULT_HYBRID_THRESHOLD
                LEGACY -> THRESHOLD
            }
        }
    }

    data class MatchConfig(
        val threshold: Double = DEFAULT_HYBRID_THRESHOLD,
        val algorithm: Algorithm = Algorithm.HYBRID,
    )

    data class Match(val record: SkillRecord, val score: Double)

    /**
     * Explain one score. Returned by `agent.skills.matchBench` so a harness can
     * see WHY a pair matched instead of trusting a single number.
     */
    data class ScoreBreakdown(
        val score: Double,
        val conceptScore: Double,
        val bigramDice: Double,
        val unigramDice: Double,
        val appWeight: Double,
        val queryConcepts: List<String>,
        val recordConcepts: List<String>,
    )

    // ── Concept lexicon ─────────────────────────────────────────────────────

    /**
     * Surface form → canonical concept. Keep entries concrete (a noun or a
     * named operation), not generic politeness ("请", "帮我"), otherwise every
     * task would look alike. Latin forms are matched lowercased after [clean].
     */
    private val LEXICON: Map<String, List<String>> = linkedMapOf(
        "storage" to listOf(
            "存储空间", "存储", "储存", "空间", "容量", "占用", "占满", "占空间",
            "磁盘", "硬盘", "剩余空间", "可用空间", "总空间", "体积", "存储占用",
        ),
        "composition" to listOf(
            "构成", "组成", "明细", "分布", "分类", "细分", "详情", "清单", "列表",
            "都被什么占", "什么占", "占用构成", "构成明细", "哪些占",
        ),
        "cleanup" to listOf(
            "清理", "清除", "删掉", "删除", "清空", "打扫", "释放", "腾出",
            "冗余", "没用的", "无用", "回收", "可清理", "清理掉",
        ),
        "cache" to listOf(
            "缓存", "cache", "垃圾文件", "垃圾", "残留", "临时文件", "tmp", "temp", "临时",
        ),
        "directory" to listOf("私有目录", "目录", "文件夹", "路径"),
        "flashlight" to listOf(
            "手电筒", "手电", "闪光灯", "电筒", "照明", "补光", "torch", "flashlight",
        ),
        "screenshot" to listOf("截图", "截屏", "屏幕截图", "抓屏", "截个图", "screenshot"),
        "battery" to listOf(
            "剩余电量", "电池电量", "电量", "电池", "充电", "耗电", "用电", "续航",
            "battery", "剩多少电", "还有多少电",
        ),
        "network" to listOf(
            "无线网络", "wifi", "无线", "流量", "联网", "断网", "飞行模式", "热点",
            "信号", "网络",
        ),
        "volume" to listOf("音量", "声音大小", "声音", "响铃", "静音", "震动", "外放"),
        "brightness" to listOf("屏幕亮度", "亮度", "调亮", "调暗", "护眼"),
        "bluetooth" to listOf("蓝牙", "耳机"),
        "camera" to listOf("相机", "拍照", "照片", "相册", "摄影", "拍张照"),
        "contacts" to listOf("通讯录", "联系人", "电话簿"),
        "message" to listOf(
            "短信", "消息", "通知", "发消息", "聊天记录", "聊天", "对话记录", "对话",
        ),
        "alarm" to listOf("闹钟", "闹铃"),
        "reminder" to listOf(
            "提醒", "备忘", "待办", "日程", "日历", "会议", "预约", "记一下", "记住", "帮我记",
        ),
        "map" to listOf("地图", "导航", "路线", "地点", "位置", "附近"),
        "weather" to listOf("天气", "气温", "温度", "下雨", "降雨", "预报"),
        "media" to listOf("播放", "放首歌", "听歌"),
        "music" to listOf("音乐", "歌曲", "歌单", "网易云"),
        "video" to listOf("视频", "影片"),
        "file_read" to listOf("读取文件", "读文件", "查看文件", "文件内容", "看一下文件"),
        "file_write" to listOf("写入文件", "写文件", "保存文件", "新建文件", "编辑文件", "修改文件"),
        "search" to listOf(
            "搜索", "查询", "查找", "搜一下", "查一下", "看看", "看一下", "找一下",
            "查查", "了解", "确认", "查",
        ),
        "install" to listOf("安装", "装个", "卸载", "软件", "apk"),
        "settings" to listOf("设置", "配置", "选项", "开关"),
        "download" to listOf("下载", "上传", "传输"),
        "share" to listOf("分享", "发送", "转发"),
        "backup" to listOf("备份", "恢复", "还原"),
        "memory" to listOf("记忆库", "记忆", "回忆"),
        "skill" to listOf("技能", "流程", "经验", "做法", "步骤"),
        "subagent" to listOf("子代理", "委派", "后台任务"),
        "phone" to listOf("手机", "设备", "本机"),
    )

    /** Longest form first: "剩余电量" must win over "电量" for display, though both map to the same concept. */
    private val SURFACES: List<Pair<String, String>> = buildList {
        for ((concept, forms) in LEXICON) for (form in forms) add(form.lowercase() to concept)
        sortByDescending { it.first.length }
    }

    /** Generic concepts count less than a concrete task noun. */
    private val GENERIC_CONCEPTS = setOf("search", "phone", "directory", "install", "settings")

    // ── Text features ───────────────────────────────────────────────────────

    /**
     * Keep only CJK characters, letters and digits — strips punctuation,
     * whitespace and full-width symbols. Latin letters are lowercased so
     * "Open APP" and "open app" produce the same features.
     */
    fun clean(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            if (isCjk(ch) || ch.isLetterOrDigit()) sb.append(ch.lowercaseChar())
        }
        return sb.toString()
    }

    /** Every adjacent character pair; a 0/1-character string has none. */
    fun bigrams(cleaned: String): Set<String> {
        if (cleaned.length < 2) return emptySet()
        val out = LinkedHashSet<String>(cleaned.length)
        for (i in 0 until cleaned.length - 1) out.add(cleaned.substring(i, i + 2))
        return out
    }

    /** Canonical concepts found in [text], in longest-surface-first scan order. */
    fun concepts(text: String): LinkedHashSet<String> {
        val s = clean(text)
        val out = LinkedHashSet<String>()
        for ((form, concept) in SURFACES) {
            if (form.isNotEmpty() && s.contains(form)) out.add(concept)
        }
        return out
    }

    /** Intersection over union; 0 when either side has no bigrams. */
    fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var intersection = 0
        for (s in a) if (s in b) intersection++
        if (intersection == 0) return 0.0
        val union = a.size + b.size - intersection
        return intersection.toDouble() / union
    }

    private fun dice(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var intersection = 0
        for (s in a) if (s in b) intersection++
        return 2.0 * intersection / (a.size + b.size)
    }

    /** The goal's first [limit] unique bigrams, stored with the record for inspection. */
    fun keywords(goal: String, limit: Int = 12): List<String> =
        bigrams(clean(goal)).take(limit)

    private fun conceptWeight(concept: String): Double =
        if (concept in GENERIC_CONCEPTS) 0.35 else 1.0

    /**
     * Weighted overlap coefficient over concepts: intersection weight divided
     * by the SMALLER side's total. Containment matters here because the query
     * is usually shorter than the recorded goal, and a short query that names
     * every concept of a longer goal is a strong match.
     *
     * A shared GENERIC concept is not evidence of the same task. The live
     * case: "看看私有目录都被什么占满了" against an ASR fragment "你看看这此
     * 快捷工具使用有什么问题" shares only `search`; because `search` is the
     * smaller side's whole weight, raw containment scored 1.0 and the fragment
     * beat every real storage record. Requiring at least one concrete shared
     * concept zeroes that signal; character overlap can still carry a genuine
     * near-duplicate (e.g. "查询缓存" vs "查询一下缓存").
     */
    private fun conceptScore(q: Set<String>, r: Set<String>): Double {
        if (q.isEmpty() || r.isEmpty()) return 0.0
        var inter = 0.0
        var concreteInter = 0.0
        for (c in q) if (c in r) {
            val weight = conceptWeight(c)
            inter += weight
            if (c !in GENERIC_CONCEPTS) concreteInter += weight
        }
        if (inter == 0.0 || concreteInter == 0.0) return 0.0
        val qWeight = q.sumOf { conceptWeight(it) }
        val rWeight = r.sumOf { conceptWeight(it) }
        return inter / minOf(qWeight, rWeight)
    }

    // ── Scoring ─────────────────────────────────────────────────────────────

    /** Jaccard on cleaned goal bigrams — the original algorithm, unchanged. */
    fun legacyScore(query: String, record: SkillRecord, appPackage: String?): Double {
        val similarity = jaccard(bigrams(clean(query)), bigrams(clean(record.goal)))
        if (similarity <= 0.0) return 0.0
        val weight = if (appPackage != null && appPackage == record.appPackage) {
            SAME_APP_WEIGHT
        } else {
            CROSS_APP_WEIGHT
        }
        return similarity * weight
    }

    private fun appWeight(queryApp: String?, recordApp: String?, algorithm: Algorithm): Double {
        return when {
            queryApp != null && queryApp == recordApp -> SAME_APP_WEIGHT
            algorithm == Algorithm.LEGACY -> CROSS_APP_WEIGHT
            queryApp == null || recordApp == null -> NEUTRAL_APP_WEIGHT
            else -> CROSS_APP_WEIGHT
        }
    }

    fun explain(
        query: String,
        record: SkillRecord,
        appPackage: String?,
        algorithm: Algorithm = Algorithm.HYBRID,
    ): ScoreBreakdown {
        val q = clean(query)
        val r = clean(record.goal)
        val weight = appWeight(appPackage, record.appPackage, algorithm)
        if (algorithm == Algorithm.LEGACY) {
            val score = legacyScore(query, record, appPackage)
            return ScoreBreakdown(
                score = score,
                conceptScore = 0.0,
                bigramDice = dice(bigrams(q), bigrams(r)),
                unigramDice = dice(q.map { it.toString() }.toSet(), r.map { it.toString() }.toSet()),
                appWeight = weight,
                queryConcepts = emptyList(),
                recordConcepts = emptyList(),
            )
        }
        val qConcepts = concepts(query)
        val rConcepts = concepts(record.goal)
        val cs = conceptScore(qConcepts, rConcepts)
        val bd = dice(bigrams(q), bigrams(r))
        val ud = dice(q.map { it.toString() }.toSet(), r.map { it.toString() }.toSet())
        val score = (0.62 * cs + 0.28 * bd + 0.10 * ud) * weight
        return ScoreBreakdown(
            score = score,
            conceptScore = cs,
            bigramDice = bd,
            unigramDice = ud,
            appWeight = weight,
            queryConcepts = qConcepts.toList(),
            recordConcepts = rConcepts.toList(),
        )
    }

    /** Score with the chosen algorithm. */
    fun score(
        query: String,
        record: SkillRecord,
        appPackage: String?,
        algorithm: Algorithm = Algorithm.HYBRID,
    ): Double = explain(query, record, appPackage, algorithm).score

    /** Top-1 above [MatchConfig.threshold], or null. */
    fun best(
        query: String,
        records: List<SkillRecord>,
        appPackage: String?,
        config: MatchConfig = MatchConfig(),
    ): Match? {
        var best: Match? = null
        for (record in records) {
            val s = score(query, record, appPackage, config.algorithm)
            if (s > (best?.score ?: 0.0)) best = Match(record, s)
        }
        return best?.takeIf { it.score >= config.threshold }
    }

    private fun isCjk(ch: Char): Boolean {
        val c = ch.code
        return (c in 0x4E00..0x9FFF) ||   // CJK Unified Ideographs
            (c in 0x3400..0x4DBF) ||      // CJK Extension A
            (c in 0xF900..0xFAFF) ||      // CJK Compatibility Ideographs
            (c in 0x3040..0x309F) ||      // Hiragana
            (c in 0x30A0..0x30FF) ||      // Katakana
            (c in 0xAC00..0xD7AF)         // Hangul syllables
    }
}

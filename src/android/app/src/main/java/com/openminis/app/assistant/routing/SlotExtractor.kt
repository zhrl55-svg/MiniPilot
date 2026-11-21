package com.openminis.app.assistant.routing

import com.openminis.app.assistant.routing.ChineseTimeParser.RepeatMode
import com.openminis.app.tools.AmapPlaceQuery
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

data class SlotExtraction(
    val args: JSONObject,
    val missing: Set<String>,
    val summary: String,
    val parsedTime: ChineseTimeParser.Parsed? = null,
    val ambiguous: Boolean = false,
)

/**
 * Deterministic slot extraction. This class never calls a model: wording,
 * paths, note bodies and reminder titles are taken from the user's original
 * text, while dates and durations come from [ChineseTimeParser].
 */
class SlotExtractor(
    private val nowProvider: () -> LocalDateTime = { LocalDateTime.now() },
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val pathRegex = Regex("""/(?:[A-Za-z0-9_.\-~]+/)*[A-Za-z0-9_.\-~]+""")
    private val urlRegex = Regex("""https?://\S+|\bwww\.[A-Za-z0-9.\-]+\S*""")
    private val numberIdRegex = Regex("""(?:id|编号|序号)\s*[:：]?\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val trainTimeRangeRegex =
        Regex("""(\d{1,2}):(\d{2})\s*[-~—–至到]\s*(\d{1,2}):(\d{2})""")
    private val trainRouteRegex = Regex(
        """(?:从|由)?([\p{IsHan}A-Za-z0-9·]{2,15}?)(?:到|至|去)([\p{IsHan}A-Za-z0-9·]{2,15}?)(?=\s|的|$|[，,.;；])""",
    )
    private val trainNounRegex = Regex(
        """(?:的)?(?:火车票余票|高铁票余票|动车票余票|火车余票|车票余票|火车票|高铁票|动车票|城际票|车票|余票|车次)""",
    )
    private val trainIsoDateRegex = Regex("""(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})""")
    private val trainMonthDayRegex = Regex("""(\d{1,2})月(\d{1,2})[日号]?""")

    fun extract(userText: String, route: AssistantRoute): SlotExtraction {
        val text = AssistantRouteMatcher.normalizeTextPreservingCase(userText)
        val raw = normalizeContent(userText)
        val args = JSONObject()
        val parsed = ChineseTimeParser.parse(text, nowProvider(), zone)
        val now = nowProvider()

        when (route.capability) {
            "calendar" -> extractCalendar(text, route.action, parsed, args)
            "alarm" -> extractAlarm(text, route.action, parsed, args)
            "reminder" -> extractReminder(text, raw, route.action, parsed, now, args)
            "note" -> extractNote(text, raw, route.action, args)
            "settings" -> extractSettings(text, route.action, args)
            "scheduled_task" -> extractScheduled(text, route.action, parsed, now, args)
            "weather" -> Unit
            "device" -> extractDevice(text, route.action, args)
            "volume" -> extractVolume(text, route.action, args)
            "media" -> extractMedia(text, route.action, args)
            "contacts" -> extractContacts(text, route.action, args)
            "app_launch" -> extractApp(text, route.action, args)
            "shell_execute" -> extractShell(raw, args)
            "file_read", "read_image" -> pathRegex.find(text)?.let { args.put("path", it.value) }
            "file_write" -> {
                pathRegex.find(text)?.let { args.put("path", it.value) }
                extractWriteContent(raw, args)
            }
            "browser_use" -> urlRegex.find(text)?.let {
                args.put("action", "navigate")
                args.put("url", it.value)
            }
            "memory_write" -> extractAfterTrigger(
                raw,
                listOf("记住", "记一下", "存进记忆", "别忘了", "remember that", "remember"),
            )?.let { args.put("content", it) }
            "memory_get" -> extractAfterTrigger(
                raw,
                listOf("我说过", "我的记忆", "之前说过", "查记忆", "what did i say", "my memory"),
            )?.let { args.put("keywords", it) }
            "amap_search" -> args.put("query", extractPlaceQuery(text))
            // The tools own query normalization (news strips command words,
            // stocks scan for an index keyword), so passing the full sentence
            // keeps the local path deterministic and model-free.
            "news_search", "stock_quote", "web_search" -> args.put("query", text)
            // [T-android-train-quote] 站名/日期能确定性抽出就抽；抽不到的
            // 可选槽交给工具默认值，from/to 缺失时路由会退回云端。
            "train_12306" -> extractTrain(text, args)
            // [T-android-notification-summary] 「最近 10 条通知」→ limit。
            "notifications" -> {
                Regex("""(?:最近|最新)?\s*(\d{1,2})\s*条(?:通知|消息)""").find(text)?.let { match ->
                    match.groupValues[1].toIntOrNull()?.let { args.put("limit", it) }
                }
            }
            "file_edit" -> {
                pathRegex.find(text)?.let { args.put("path", it.value) }
            }
        }


        if (!args.has("tool_title") || args.optString("tool_title").isBlank()) {
            val title = titleFor(route, args, text)
            args.put("tool_title", title)
        }

        val spec = LocalCapabilityRegistry.action(route.capability, route.action)
        val missing = spec?.requiredSlots.orEmpty()
            .filterTo(linkedSetOf()) { args.optString(it).isBlank() }
        val ambiguousTime = parsed?.ambiguous == true ||
            (parsed != null && parsed.confidence < 0.8)
        return SlotExtraction(
            args = args,
            missing = missing,
            summary = summaryFor(route, args),
            parsedTime = parsed,
            ambiguous = ambiguousTime,
        )
    }

    private fun extractCalendar(
        text: String,
        action: String,
        parsed: ChineseTimeParser.Parsed?,
        args: JSONObject,
    ) {
        when (action) {
            "list" -> {
                val start = parsed?.dateTime?.toLocalDate()?.atStartOfDay()
                    ?: nowProvider().toLocalDate().atStartOfDay()
                val end = if (parsed?.dateTime != null) {
                    start.plusDays(1)
                } else {
                    start.plusDays(7)
                }
                args.put("start", start.format(ISO))
                args.put("end", end.format(ISO))
            }
            "freebusy" -> {
                val start = parsed?.dateTime ?: nowProvider()
                args.put("start", start.format(ISO))
                args.put("end", start.plusHours(8).format(ISO))
            }
            "create" -> {
                if (parsed != null) {
                    args.put("start", parsed.iso())
                    val end = if (parsed.allDay) {
                        parsed.dateTime.toLocalDate().plusDays(1).atStartOfDay()
                    } else {
                        parsed.dateTime.plusHours(1)
                    }
                    args.put("end", end.format(ISO))
                    args.put("allDay", parsed.allDay)
                    if (parsed.repeat == RepeatMode.DAILY) args.put("repeat", "DAILY")
                }
                args.put("title", calendarTitle(text))
                locationFrom(text)?.let { args.put("location", it) }
                Regex("""提前\s*(\d{1,3})\s*分钟""").find(text)?.let {
                    args.put("alarmMinutes", it.groupValues[1].toInt())
                }
            }
            "delete" -> {
                numberIdRegex.find(text)?.let { args.put("id", it.groupValues[1]) }
                args.put("id_or_title", calendarTitle(text))
            }
            "update" -> {
                numberIdRegex.find(text)?.let { args.put("id", it.groupValues[1]) }
                val rename = Regex(
                    """^(.+?)(?:改名为|改名成|标题改为|名称改为|改成|改为|rename\s+to)\s*(.+)$""",
                    RegexOption.IGNORE_CASE,
                ).find(text)
                val queryText = if (rename != null) rename.groupValues[1] else text
                args.put("id_or_title", calendarTitle(queryText))
                val renamedTitle = rename?.groupValues?.get(2)
                    ?.trim()
                    ?.trim('，', ',', '。', '.', ':', '：', '"', '“', '”')
                    ?.takeIf { it.isNotBlank() }
                if (renamedTitle != null &&
                    ChineseTimeParser.stripTimeExpressions(renamedTitle).isNotBlank()
                ) {
                    args.put("title", renamedTitle.take(80))
                }
                parsed?.let {
                    args.put("start", it.iso())
                    args.put("end", it.dateTime.plusHours(1).format(ISO))
                }
            }
        }
    }

    private fun extractAlarm(
        text: String,
        action: String,
        parsed: ChineseTimeParser.Parsed?,
        args: JSONObject,
    ) {
        when (action) {
            "set" -> {
                parsed?.let {
                    // The Clock handler accepts ISO and can therefore detect
                    // an explicit date that does not match "the next HH:mm".
                    // Passing only HH:mm silently turned "today 7am" (already
                    // past) or "next Friday 7am" into the wrong day.
                    args.put("time", it.iso())
                    args.put("repeat", when (it.repeat) {
                        RepeatMode.DAILY -> "DAILY"
                        RepeatMode.WEEKDAYS -> "WEEKDAYS"
                        else -> "ONCE"
                    })
                    args.put("targetDate", it.dateIso())
                }
                args.put("label", alarmLabel(text))
            }
            "timer" -> {
                val duration = ChineseTimeParser.parseDuration(text)
                if (duration != null) {
                    args.put("duration", duration.seconds)
                    args.put("durationText", duration.matchedText)
                }
                args.put("label", timerLabel(text))
            }
            "open" -> Unit
        }
    }

    private fun extractReminder(
        text: String,
        raw: String,
        action: String,
        parsed: ChineseTimeParser.Parsed?,
        now: LocalDateTime,
        args: JSONObject,
    ) {
        when (action) {
            "schedule" -> {
                if (parsed != null) {
                    if (parsed.relative) {
                        val seconds = ChineseTimeParser.secondsUntil(parsed.dateTime, now)
                        args.put("after", seconds)
                    } else {
                        args.put("at", parsed.iso())
                    }
                }
                args.put("title", reminderTitle(text))
                args.put("body", raw)
            }
            "cancel" -> {
                numberIdRegex.find(text)?.let { args.put("id", it.groupValues[1]) }
                if (!args.has("id")) {
                    extractAfterTrigger(text, listOf("取消提醒", "删掉提醒", "cancel reminder"))
                        ?.let { args.put("id", it) }
                }
            }
        }
    }

    private fun extractNote(text: String, raw: String, action: String, args: JSONObject) {
        when (action) {
            "create" -> {
                val body = noteBody(raw)
                if (body.isNotBlank()) {
                    args.put("content", body)
                    args.put("title", body.lineSequence().firstOrNull().orEmpty().take(40))
                }
            }
            "search" -> extractAfterTrigger(
                text,
                listOf("搜笔记", "找笔记", "查找笔记", "搜索笔记", "search notes", "find note"),
            )?.let { args.put("query", it) }
            "delete" -> {
                numberIdRegex.find(text)?.let { args.put("id", it.groupValues[1]) }
                if (!args.has("id")) {
                    extractAfterTrigger(text, listOf("删除笔记", "删掉笔记", "delete note"))
                        ?.let { args.put("id_or_query", it) }
                }
            }
        }
    }

    private fun extractSettings(text: String, action: String, args: JSONObject) {
        val target = detectSettingsTarget(text)
        val lower = text.lowercase(Locale.ROOT)
        if (action == "open") {
            if (target != null) args.put("target", target)
            return
        }
        if (target != null) args.put("target", target)
        val value = when (target) {
            "brightness" -> Regex("""(?:亮度|brightness).{0,8}?(\d{1,3})\s*%?""")
                .find(text)?.groupValues?.get(1)
            "screen_timeout" -> Regex("""(\d{1,4})\s*(秒|分钟|minute|second)""")
                .find(text)?.let { m ->
                    val n = m.groupValues[1].toInt()
                    if (m.groupValues[2].contains("分") || m.groupValues[2].startsWith("minute")) {
                        (n * 60).toString()
                    } else {
                        n.toString()
                    }
                }
            "rotation" -> if (lower.contains("关闭") || lower.contains("锁定") || lower.contains("off")) "off" else "on"
            else -> when {
                lower.contains("打开") || lower.contains("开启") || lower.contains("turn on") ||
                    lower.contains("enable") -> "on"
                lower.contains("关闭") || lower.contains("关掉") || lower.contains("turn off") ||
                    lower.contains("disable") -> "off"
                lower.contains("勿扰") -> "on"
                else -> null
            }
        }
        value?.let { args.put("value", it) }
    }

    private fun extractScheduled(
        text: String,
        action: String,
        parsed: ChineseTimeParser.Parsed?,
        now: LocalDateTime,
        args: JSONObject,
    ) {
        when (action) {
            "create" -> {
                parsed?.let {
                    if (it.relative) {
                        args.put("after", ChineseTimeParser.secondsUntil(it.dateTime, now))
                    } else {
                        args.put("at", it.iso())
                        args.put("hour", it.dateTime.hour)
                        args.put("minute", it.dateTime.minute)
                    }
                    args.put("repeat", it.repeat.name)
                }
                args.put("prompt", scheduledPrompt(text))
                args.put("label", scheduledPrompt(text).take(40))
            }
            "cancel" -> {
                numberIdRegex.find(text)?.let { args.put("id", it.groupValues[1]) }
                if (!args.has("id")) {
                    extractAfterTrigger(text, listOf("取消定时任务", "删掉定时任务", "cancel scheduled task"))
                        ?.let { args.put("id", it) }
                }
            }
        }
    }

    private fun extractDevice(text: String, action: String, args: JSONObject) {
        if (action == "info") Unit
        if (text.contains("内存") || text.contains("memory", ignoreCase = true)) {
            args.put("includeMemory", true)
        }
    }

    private fun extractMedia(text: String, action: String, args: JSONObject) {
        val lower = text.lowercase(Locale.ROOT)
        val resolved = when {
            lower.contains("继续播放") || lower.contains("恢复播放") || lower.contains("resume") -> "resume"
            lower.contains("停止播放") || lower.contains("stop") -> "stop"
            lower.contains("下一首") || lower.contains("next") -> "next"
            lower.contains("上一首") || lower.contains("previous") -> "previous"
            lower.contains("状态") || lower.contains("status") -> "status"
            lower.contains("列表") || lower.contains("list") -> "list"
            else -> action
        }
        args.put("action", resolved)
        if (resolved == "play") {
            // [T-music-slot-semantics] 语义（kind）在抽取层定好，执行层只消费，
            // 不再拿关键词到执行层 contain() 猜意图。
            val request = musicRequest(text)
            args.put("kind", request.kind)
            if (request.query.isNotBlank()) args.put("query", request.query)
            if (request.artist.isNotBlank()) args.put("artist", request.artist)
            if (request.chart.isNotBlank()) args.put("chart", request.chart)
        }
        Regex(
            """(?:会话|session)\s*[:：]?\s*([A-Za-z0-9_.-]+)""",
            RegexOption.IGNORE_CASE,
        ).find(text)?.let {
            args.put("session", it.groupValues[1])
        }
    }

    /**
     * [T-local-volume] 「音量调到百分之六十」→ set:60；
     * 「音量调大一点」→ adjust:+10。解析不出具体数值就不写 percent，
     * 必需槽缺失会让路由退回云端——「调到 60 分」不是音量指令。
     *
     * @param action set = 绝对值；adjust = 相对增减；status = 只看不改。
     */
    private fun extractVolume(text: String, action: String, args: JSONObject) {
        if (text.contains("铃声") || text.contains("来电")) args.put("stream", "ring")
        if (action == "status") return
        val normalized = normalizeVolumePercent(text)
        when (action) {
            "set" -> {
                val absolute = absoluteVolumePercent(normalized) ?: when {
                    normalized.contains("最大") || normalized.contains("最响") -> 100
                    normalized.contains("最小") || normalized.contains("最轻") -> 0
                    else -> null
                }
                absolute?.let { args.put("percent", it.coerceIn(0, 100)) }
            }
            "adjust" -> volumeDeltaPercent(normalized)?.let { args.put("percent", it) }
        }
    }

    /**
     * 把中文百分比归一成 `N%`：`百分之六十` → `60%`，
     * `调到六十` → `调到 60%`。数字后面跟「分 / 块 / 元…」时不做替换，
     * 那些是别的量词，不是音量百分比。
     */
    private fun normalizeVolumePercent(input: String): String {
        var out = input
        out = out.replace(Regex("百分百"), "100%")
        Regex("百分之([零一二两三四五六七八九十百]{1,4})")
            .findAll(out).toList().reversed().forEach { match ->
                val value = chineseNumber(match.groupValues[1]) ?: return@forEach
                out = out.replaceRange(match.range, "$value%")
            }
        Regex(
            "(调到|调至|调成|设成|设为|设置为|改成|改为|变成|变到)\\s*" +
                "([零一二两三四五六七八九十百]{1,4})(?!\\s*[分块元秒度个次])",
        ).findAll(out).toList().reversed().forEach { match ->
            val value = chineseNumber(match.groupValues[2]) ?: return@forEach
            out = out.replaceRange(match.range, "${match.groupValues[1]} $value%")
        }
        return out
    }

    /** 绝对值：只认百分比或句末的「调到 60」；「调到 60 分」不算。 */
    private fun absoluteVolumePercent(text: String): Int? {
        Regex(
            """(?:调到|调至|调成|设成|设为|设置为|改成|改为|变成|变到)\s*(\d{1,3})\s*(?:%|$|[,.!?;])""",
        ).find(text)?.groupValues?.get(1)?.toIntOrNull()?.let {
            return it.coerceIn(0, 100)
        }
        return Regex("""(?:音量|声音|响度|铃声)\s*(\d{1,3})\s*%""")
            .find(text)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 100)
    }

    /** 相对增减：显式「调大 20%」优先，否则按方向词给步长。 */
    private fun volumeDeltaPercent(text: String): Int? {
        Regex("""(?:调大|增大|加大|增加|提高|升高|上调)\s*(\d{1,3})\s*%""")
            .find(text)?.groupValues?.get(1)?.toIntOrNull()?.let {
                return it.coerceIn(0, 100)
            }
        Regex("""(?:调小|减小|减少|降低|调低|下调)\s*(\d{1,3})\s*%""")
            .find(text)?.groupValues?.get(1)?.toIntOrNull()?.let {
                return -it.coerceIn(0, 100)
            }
        val up = listOf(
            "调大", "增大", "加大", "增加", "提高", "升高", "上调",
            "大一点", "大一些", "大点", "大很多", "大声", "高一点",
        ).any { text.contains(it) }
        val down = listOf(
            "调小", "减小", "减少", "降低", "调低", "下调",
            "小一点", "小一些", "小点", "小很多", "小声", "低一点",
        ).any { text.contains(it) }
        if (up == down) return null
        val step = when {
            text.contains("一点") || text.contains("一些") || text.contains("稍微") ||
                text.contains("点儿") || text.contains("点") -> 10
            text.contains("很多") || text.contains("大幅") || text.contains("大量") ||
                text.contains("一大") -> 40
            else -> 20
        }
        return if (up) step else -step
    }

    /**
     * [T-music-slot-semantics] 把一句点歌拆成语义请求，而不是做字符串手术。
     *
     * 只剥白名单触发词（播放/放/来/听/play…），实体一个字都不丢：
     * 「播放轻音乐」→ genre=轻音乐（不是歌名「轻」）；
     * 「play music」→ none（走榜单，不是歌名 Music）。
     */
    private fun musicRequest(text: String): MusicRequest {
        var t = text.trim()
        val triggers = listOf(
            "播放一首", "播放一首儿", "播放一下", "播放", "放一首", "放一首儿", "放个", "放",
            "来一首", "来一首儿", "来首", "来点", "想听一首", "想听", "要听一首", "要听",
            "听一首", "听首", "听个", "听", "唱一首", "唱首", "唱",
            "play", "put on",
        )
        for (trigger in triggers.sortedByDescending { it.length }) {
            val index = t.indexOf(trigger, ignoreCase = true)
            if (index >= 0) {
                t = t.substring(index + trigger.length)
                break
            }
        }
        t = t.trim().trimStart(':', '：', ',', '，', '.', '。', '!', '！', '?', '？', ' ')
        // 「放首歌」→「」；「放首歌周杰伦」→「周杰伦」
        t = t.replace(Regex("""^一?[首歌]\s*"""), "").trim()
        t = t.replace(Regex("""^(?:给我|帮我|帮忙|麻烦)\s*"""), "").trim()
        t = t.trimEnd('了', '吧', '呀', '啊', '呢', '哦')

        // 泛指目标：没有点具体的东西 → 榜单，不拿 "music" 去搜歌名。
        if (MusicVocabulary.isGenericTarget(t)) {
            return MusicRequest(kind = "none")
        }

        // 榜单意图是白名单整词，不是模糊嗅探。
        MusicVocabulary.chartOf(t)?.let { chart ->
            return MusicRequest(kind = "chart", chart = chart)
        }

        // 流派/场景：整词保留，绝不剥尾部的「音乐」。
        MusicVocabulary.genreOf(t)?.let { genre ->
            return MusicRequest(kind = "genre", query = genre)
        }
        MusicVocabulary.sceneOf(t)?.let { scene ->
            return MusicRequest(kind = "scene", query = scene)
        }

        // 「周杰伦的稻香」→ song=稻香 + artist=周杰伦；「周杰伦的歌」→ artist。
        splitArtistSong(t)?.let { return it }

        // 普通歌名：尾缀剥离带守卫，剥坏了就保留原串。
        val original = t
        val candidate = t.replace(Regex("""(?:的)?(?:歌曲|音乐|曲子|歌)$"""), "").trim()
        if (candidate.endsWith('的') && candidate.length > 1) {
            // 「播放周杰伦的」→ 歌手意图，而不是歌名「周杰伦的」。
            return MusicRequest(kind = "artist", query = candidate.removeSuffix("的").trim())
        }
        val stripped = guardedStrip(original) {
            it.replace(Regex("""(?:的)?(?:歌曲|音乐|曲子|歌)$"""), "")
        }
        if (MusicVocabulary.isGenericTarget(stripped) || stripped.isBlank()) {
            return MusicRequest(kind = "none")
        }
        return MusicRequest(kind = "song", query = stripped.take(40))
    }

    /**
     * 歌手/歌名拆分上提到抽取层：「周杰伦的稻香」→ artist=周杰伦, song=稻香。
     * 执行层不再自己 substringBefore/AfterLast('的') 猜。
     */
    private fun splitArtistSong(t: String): MusicRequest? {
        val index = t.indexOf('的')
        if (index <= 0) return null
        val head = t.substring(0, index).trim()
        val tail = t.substring(index + 1).trim()
        if (head.length < 2 || tail.isEmpty()) return null
        if (tail in ARTIST_TAIL_WORDS) {
            return MusicRequest(kind = "artist", query = head)
        }
        val songPart = tail.replace(Regex("""(?:歌曲|音乐|曲子)$"""), "").trim()
        if (songPart.length < 2) return null
        return MusicRequest(kind = "song", query = songPart.take(40), artist = head)
    }

    /**
     * [T-music-slot-semantics] 剥离守卫：白名单剥离可以，启发式截断不行。
     * 剥完为空 / 只剩 1 个字 / 剩下的仍是流派词 → 认为破坏了实体，恢复原串。
     */
    private fun guardedStrip(original: String, transform: (String) -> String): String {
        val stripped = transform(original).trim()
        return when {
            stripped.isBlank() -> original.trim()
            stripped.length == 1 && original.trim().length > 1 -> original.trim()
            MusicVocabulary.isGenreOrScene(stripped) -> original.trim()
            else -> stripped
        }
    }

    private data class MusicRequest(
        val kind: String,
        val query: String = "",
        val artist: String = "",
        val chart: String = "",
    )

    private fun extractContacts(text: String, action: String, args: JSONObject) {
        val triggers = if (action == "search") {
            listOf("搜索联系人", "查找联系人", "找联系人", "search contacts", "find contact")
        } else {
            listOf("打电话给", "拨打", "call", "给")
        }
        val rawName = extractAfterTrigger(text, triggers)
            ?.trim()
            ?.trim('。', '.', ',', '，', '!', '！')
        // [T-music-slot-semantics] 剥离守卫：别把联系人名截成一个字或截空。
        val name = rawName?.let { value ->
            guardedStrip(value) {
                it.replace(Regex("""(?:打电话|电话|call)$""", RegexOption.IGNORE_CASE), "")
            }
        }
        if (!name.isNullOrBlank()) {
            args.put("name", name)
            args.put("query", name)
        }
        args.put("action", action)
        if (action == "dial" && Regex(
                """(?:直接|立刻|马上|现在)\s*(?:拨打|打|呼叫)|(?:call|dial)\s+now""",
                RegexOption.IGNORE_CASE,
            ).containsMatchIn(text)
        ) {
            args.put("directCall", true)
        }
    }

    private fun extractApp(text: String, action: String, args: JSONObject) {
        val rawApp = extractAfterTrigger(
            text,
            listOf("打开应用", "启动应用", "启动app", "打开app", "launch app", "打开", "启动", "launch"),
        )
        // [T-music-slot-semantics] 「打开app」→ 原文为空：保留原串而不是截空。
        val app = rawApp?.let { value ->
            guardedStrip(value) {
                it.replace(Regex("""^(?:应用|app)\s*""", RegexOption.IGNORE_CASE), "")
            }
        }
        if (!app.isNullOrBlank()) args.put("app", app)
        args.put("action", action)
    }

    private fun extractShell(text: String, args: JSONObject) {
        var s = text
        val prefixes = listOf(
            "运行命令", "执行命令", "运行一下", "执行一下", "跑一下", "跑个",
            "运行", "执行", "在终端里", "终端", "shell", "bash", "cmd",
            "run command", "execute",
        )
        prefixes.forEach { s = s.replaceFirst(Regex("""^\s*${Regex.escape(it)}\s*""", RegexOption.IGNORE_CASE), "") }
        s = s.trim().trimStart(':', '：', ',', '，').trim()
        s = s.removeSuffix("命令").removeSuffix("脚本").trim()
        if (s.isNotEmpty() && s.all { it.code < 128 }) args.put("command", s)
    }

    private fun extractWriteContent(text: String, args: JSONObject) {
        Regex(
            """(?:内容为|内容是|写入|写|save)\s+(.+?)\s+(?:到|to)\s+/""",
            RegexOption.IGNORE_CASE,
        )
            .find(text)?.let { args.put("content", it.groupValues[1].trim()) }
        if (!args.has("content")) {
            Regex("""(?:把|将)\s+(.+?)\s+(?:写入|写到|保存到|存到)""")
                .find(text)?.let { args.put("content", it.groupValues[1].trim()) }
        }
    }

    private fun calendarTitle(text: String): String {
        var t = ChineseTimeParser.stripTimeExpressions(text)
        t = extractAfterTrigger(
            t,
            listOf(
                "安排日程", "创建日程", "新建日程", "添加日程", "加个会议", "安排会议",
                "删除日程", "取消日程", "删掉会议", "取消会议", "修改日程", "更新日程",
                "删除", "取消", "修改", "更新", "安排", "创建", "新建", "添加", "加个",
                "calendar add", "schedule event", "delete event", "cancel meeting",
                "update event", "reschedule meeting", "schedule", "calendar",
            ),
        ).orEmpty()
        t = t.replace(Regex("""^(?:一个|个|下|一下)\s*"""), "")
        t = t.replace(Regex("""^(?:a|an|the)\s+""", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("""\s+(?:at|on|for)\s*$""", RegexOption.IGNORE_CASE), "")
        // [T-music-slot-semantics] 尾缀剥离守卫：剥空/只剩一字就保留原串。
        t = guardedStrip(t) { it.replace(Regex("""(?:的)?(?:日程|事件)$"""), "") }
        return t.trim().trim('，', ',', '。', '.', ':', '：').ifBlank { "日程" }.take(80)
    }

    private fun alarmLabel(text: String): String {
        var t = ChineseTimeParser.stripTimeExpressions(text)
        listOf("设个闹钟", "设一个闹钟", "设置闹钟", "定个闹钟", "闹钟", "叫我起床", "叫我起来", "叫醒我", "我记得", "alarm", "wake me up")
            .forEach { t = t.replace(it, " ", ignoreCase = true) }
        return t.replace(Regex("""\s+"""), " ").trim().ifBlank { "闹钟" }.take(60)
    }

    private fun timerLabel(text: String): String {
        var t = text
        ChineseTimeParser.parseDuration(text)?.matchedText?.let { t = t.replace(it, " ") }
        listOf("开始一个", "开始", "计时器", "倒计时", "计时", "timer", "countdown")
            .forEach { t = t.replace(it, " ", ignoreCase = true) }
        return t.replace(Regex("""\s+"""), " ").trim().ifBlank { "计时器" }.take(60)
    }

    private fun reminderTitle(text: String): String {
        var t = ChineseTimeParser.stripTimeExpressions(text)
        listOf("记得提醒我", "提醒我", "提醒一下", "到点提醒", "记得提醒", "remind me to", "remind me", "reminder")
            .forEach { t = t.replace(it, " ", ignoreCase = true) }
        // [T-music-slot-semantics] 前缀剥离守卫：剥空/只剩一字就保留原串。
        t = guardedStrip(t) { value ->
            value
                .replace(Regex("""^(?:要|去|一下|我)\s*"""), "")
                .replace(Regex("""^in\s+""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""^to\s+""", RegexOption.IGNORE_CASE), "")
        }
        return t.replace(Regex("""\s+"""), " ").trim()
            .trim('，', ',', '。', '.', ':', '：')
            .ifBlank { "提醒" }
            .take(80)
    }

    private fun noteBody(text: String): String {
        val colon = text.indexOfFirst { it == ':' || it == '：' }
        if (colon >= 0 && colon < text.length - 1) {
            return text.substring(colon + 1).trim()
        }
        var t = text
        listOf("记个笔记", "记笔记", "做个笔记", "新建笔记", "创建笔记", "记事本", "备忘", "take a note", "note this")
            .forEach { t = t.replaceFirst(it, "", ignoreCase = true) }
        return t.trim().trim('，', ',', '。', '.', ':', '：')
    }

    private fun scheduledPrompt(text: String): String {
        var t = ChineseTimeParser.stripTimeExpressions(text)
        listOf(
            "创建定时任务", "新建定时任务", "定时任务", "定时帮我", "定时", "每天帮我", "每日帮我",
            "每周帮我", "工作日帮我", "帮我", "替我", "请", "schedule task", "every day help me",
        ).forEach { t = t.replace(it, " ", ignoreCase = true) }
        return t.replace(Regex("""\s+"""), " ").trim().ifBlank { text.take(120) }
    }

    private fun detectSettingsTarget(text: String): String? {
        val lower = text.lowercase(Locale.ROOT)
        return when {
            lower.contains("wifi") || lower.contains("无线网") || lower.contains("wi-fi") -> "wifi"
            lower.contains("蓝牙") || lower.contains("bluetooth") -> "bluetooth"
            lower.contains("飞行模式") || lower.contains("airplane") -> "airplane"
            lower.contains("亮度") || lower.contains("brightness") -> "brightness"
            lower.contains("息屏") || lower.contains("屏幕超时") ||
                lower.contains("screen timeout") -> "screen_timeout"
            lower.contains("旋转") || lower.contains("rotation") -> "rotation"
            lower.contains("勿扰") || lower.contains("do not disturb") || lower.contains("dnd") -> "dnd"
            lower.contains("电池") || lower.contains("battery") -> "battery"
            lower.contains("显示") || lower.contains("display") -> "display"
            lower.contains("声音") || lower.contains("sound") -> "sound"
            lower.contains("位置") || lower.contains("定位") || lower.contains("location") -> "location"
            lower.contains("通知") || lower.contains("notification") -> "notifications"
            lower.contains("无障碍") || lower.contains("accessibility") -> "accessibility"
            lower.contains("应用详情") || lower.contains("app info") -> "app"
            lower.contains("设置") || lower.contains("settings") -> "settings"
            else -> null
        }
    }

    /**
     * [T-amap-colloquial] 地点查询的槽位。
     *
     * 剥掉疑问句的壳：「附近有什么好吃的」→「好吃」，「附近有超市吗」→「超市」。
     * 剥不掉的泛指（好吃/美食/馆子）**留在这里** —— 展开成餐厅/饭店/小吃是
     * 工具的活（[AmapPlaceQuery.plan]），端侧和云端共用同一套口径。
     *
     * 剥完还像一句话（太长、带分句标点）就**交回云端**：这里返回空串，
     * 必需槽位 `query` 缺失，路由自然放弃本地。这里绝不截断后硬搜 ——
     * 截出来的关键词只会得到错误结果。
     */
    private fun extractPlaceQuery(text: String): String {
        val cleaned = AmapPlaceQuery.normalize(text)
        return if (AmapPlaceQuery.isPlaceKeyword(cleaned)) cleaned else ""
    }

    /**
     * [T-android-train-quote] 「明天北京南到上海虹桥的车票」→ from/to/date。
     *
     * Only deterministic pieces are taken: an explicit departure window, a
     * recognizable date, and the first `X到Y` span after command words and
     * ticket nouns are removed. Station validation belongs to the tool, so an
     * unparseable sentence simply leaves the required slots missing and the
     * router sends it to the cloud.
     */
    private fun extractTrain(text: String, args: JSONObject) {
        trainTimeRangeRegex.find(text)?.let { match ->
            args.put(
                "time_range",
                "${match.groupValues[1]}:${match.groupValues[2]}-" +
                    "${match.groupValues[3]}:${match.groupValues[4]}",
            )
        }
        if (text.contains("有票") || text.contains("可售")) {
            args.put("only_available", true)
        }

        val today = nowProvider().toLocalDate()
        val date = when {
            text.contains("大后天") -> today.plusDays(3)
            text.contains("后天") -> today.plusDays(2)
            text.contains("明天") || text.contains("明日") -> today.plusDays(1)
            text.contains("今天") || text.contains("今日") -> today
            else -> trainIsoDateRegex.find(text)?.let { match ->
                runCatching {
                    LocalDate.of(
                        match.groupValues[1].toInt(),
                        match.groupValues[2].toInt(),
                        match.groupValues[3].toInt(),
                    )
                }.getOrNull()
            } ?: trainMonthDayRegex.find(text)?.let { match ->
                runCatching {
                    val candidate = LocalDate.of(
                        today.year,
                        match.groupValues[1].toInt(),
                        match.groupValues[2].toInt(),
                    )
                    // 「1月2日」in November means next year, not a past date.
                    if (candidate.isBefore(today)) candidate.plusYears(1) else candidate
                }.getOrNull()
            }
        }
        date?.let { args.put("date", it.toString()) }

        // An explicit window ("08:00-10:00") is already captured as
        // time_range; leaving it in the routing text lets the from/to regex
        // start inside the clock ("00从张掖西"). Remove the whole window BEFORE
        // stripTimeExpressions: that pass strips "08:00" alone (the boundary
        // before "10:00" is lost against the following Han character) and
        // leaves a "-10:00" that still poisons the route regex.
        var routing = ChineseTimeParser.stripTimeExpressions(
            text.replace(trainTimeRangeRegex, " "),
        )
        var stripped = true
        while (stripped) {
            stripped = false
            for (prefix in TRAIN_QUERY_PREFIXES) {
                if (routing.startsWith(prefix)) {
                    routing = routing.removePrefix(prefix).trim()
                    stripped = true
                    break
                }
            }
        }
        routing = routing.replace(trainNounRegex, " ")
        routing = routing.replace(
            Regex("""^(?:帮我|给我|请|麻烦|我想|我要|想|要|买|坐)\s*"""),
            "",
        ).trim()

        trainRouteRegex.find(routing)?.let { match ->
            val from = cleanTrainPlace(match.groupValues[1])
            val to = cleanTrainPlace(match.groupValues[2])
            if (from.isNotBlank()) args.put("from", from)
            if (to.isNotBlank()) args.put("to", to)
        }
    }

    private fun cleanTrainPlace(raw: String): String {
        var value = raw.trim()
        listOf("从", "由", "在").forEach { if (value.startsWith(it)) value = value.removePrefix(it) }
        listOf("出发", "到站", "到达", "前往", "的票", "的车", "的").forEach {
            if (value.endsWith(it) && value.length > it.length) value = value.removeSuffix(it)
        }
        return value.trim()
    }

    private fun extractAfterTrigger(text: String, triggers: List<String>): String? {
        for (trigger in triggers.sortedByDescending { it.length }) {
            val index = text.indexOf(trigger, ignoreCase = true)
            if (index >= 0) {
                val start = index + trigger.length
                val tail = text.substring(start)
                    .trim()
                    .trimStart(':', '：', ',', '，', '。', '.', '-', ' ')
                if (tail.isNotBlank()) return tail
            }
        }
        return null
    }

    private fun normalizeContent(input: String): String =
        Normalizer.normalize(input, Normalizer.Form.NFKC)
            .replace('\u3000', ' ')
            .trim()

    private fun locationFrom(text: String): String? {
        val m = Regex(
            """(?:地点|location)\s*[:：]?\s*(.{1,40}?)(?=\s*(?:提前|提醒|alarm)|[,，。;；]|$)""",
            RegexOption.IGNORE_CASE,
        )
            .find(text)
        return m?.groupValues?.get(1)?.trim()
    }

    private fun titleFor(route: AssistantRoute, args: JSONObject, text: String): String {
        val key = args.optString("title").ifBlank {
            args.optString("path").ifBlank {
                args.optString("url").ifBlank {
                    args.optString("command").ifBlank {
                        args.optString("content").ifBlank {
                            args.optString("query").ifBlank {
                                args.optString("name").ifBlank {
                                    args.optString("app")
                                }
                            }
                        }
                    }
                }
            }
        }
        return if (key.isBlank()) route.label else "${route.label}: ${key.replace(Regex("\\s+"), " ").take(32)}"
    }

    private fun summaryFor(route: AssistantRoute, args: JSONObject): String {
        val target = args.optString("title").ifBlank {
            args.optString("label").ifBlank {
                args.optString("content").ifBlank {
                    args.optString("query").ifBlank {
                        args.optString("name").ifBlank {
                            args.optString("app").ifBlank {
                                args.optString("path").ifBlank { args.optString("url") }
                            }
                        }
                    }
                }
            }
        }
        val whenText = args.optString("start").ifBlank {
            args.optString("time").ifBlank {
                args.optString("at").ifBlank {
                    args.optString("durationText")
                }
            }
        }
        return listOf(route.label, target, whenText)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
            .take(160)
    }

    companion object {
        private val ISO = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

        /** 「周杰伦的歌 / 歌曲 / 曲子 / 音乐」→ 歌手意图，不是歌名。 */
        private val ARTIST_TAIL_WORDS = setOf("歌", "歌曲", "曲子", "音乐")

        private val CHINESE_DIGITS = mapOf(
            '零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
        )

        /** 中文数字 → 整数，覆盖 0~100（五、十五、五十、五十五、一百）。 */
        private fun chineseNumber(src: String): Int? {
            if (src.isEmpty()) return null
            if (src == "一百" || src == "百") return 100
            if (src.length == 1) return CHINESE_DIGITS[src[0]]
            val ten = src.indexOf('十')
            if (ten >= 0) {
                val tens = if (ten == 0) 1 else (CHINESE_DIGITS[src[0]] ?: return null)
                val ones = if (ten == src.length - 1) {
                    0
                } else {
                    CHINESE_DIGITS[src[ten + 1]] ?: return null
                }
                return tens * 10 + ones
            }
            return null
        }

        /** Command prefixes stripped before looking for the `X到Y` span. */
        private val TRAIN_QUERY_PREFIXES = listOf(
            "帮我查一下", "帮我查询", "帮我查", "帮我看看", "帮我",
            "给我", "请", "麻烦", "查询", "查一下", "查查", "看看", "看一下",
            "搜一下", "搜索",
        )
    }
}

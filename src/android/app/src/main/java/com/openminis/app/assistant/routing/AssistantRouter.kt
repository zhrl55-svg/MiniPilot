package com.openminis.app.assistant.routing

import org.json.JSONObject

sealed class RouteDecision {
    data class Local(
        val route: AssistantRoute,
        val capability: String,
        val action: String,
        val args: JSONObject,
        val needsConfirmation: Boolean,
        val needsModelExtraction: Boolean,
        val missingSlots: Set<String>,
        val score: Int,
        val ranked: List<RouteMatch> = emptyList(),
        val summary: String = "",
        val ambiguousTime: Boolean = false,
    ) : RouteDecision()

    data class Cloud(
        val reason: String,
        val note: String? = null,
        val ranked: List<RouteMatch> = emptyList(),
    ) : RouteDecision()
}

/**
 * Single entry point from "a sentence" to either a local call or the cloud.
 * This class does not spawn coroutines or touch the model; free-text model
 * filling is an explicit follow-up step in ChatViewModel so the deterministic
 * path never waits on model loading.
 */
class AssistantRouter(
    private val extractor: SlotExtractor = SlotExtractor(),
) {
    fun route(
        userText: String,
        routes: List<AssistantRoute>,
    ): RouteDecision {
        // [F-002] [FROM: X] … [END X] 是设备间交接/回执的信封，不是用户
        // 对助手说的话。整段跳过端侧路由，避免内部内容被当成指令执行。
        if (isBridgeEnvelope(userText)) {
            return RouteDecision.Cloud("internal-message")
        }
        val ranked = AssistantRouteMatcher.rank(userText, routes, limit = 3)
        val best = ranked.firstOrNull()
            ?: return RouteDecision.Cloud("no-match")
        if (best.score < AssistantRouteMatcher.MIN_SCORE) {
            return RouteDecision.Cloud("low-confidence", ranked = ranked)
        }

        val capability = LocalCapabilityRegistry.get(best.route.capability)
            ?: return RouteDecision.Cloud("unknown-capability", ranked = ranked)
        val action = capability.action(best.route.action)
            ?: return RouteDecision.Cloud("unknown-action", ranked = ranked)

        // [T-media-route-long-text] 媒体指令只接受短句 / 语音转写。一段长文本
        // 里提到「暂停音乐」或「播放轻音乐」不等于用户要控制播放器 —— 那是
        // 讨论、引用或汇报。超过 20 个字或带换行时交给云端按整段语境判断，
        // 避免把一封长消息执行成一次点歌/暂停。
        if (capability.id == "media" &&
            (userText.contains('\n') ||
                AssistantRouteMatcher.normalizeText(userText).length > MEDIA_COMMAND_MAX_LENGTH)
        ) {
            return RouteDecision.Cloud("long-text", ranked = ranked)
        }

        val extraction = extractor.extract(userText, best.route)
        // [F-001] 写文件必须有明确的 path 槽。普通句子里出现「写入」二字
        // （例如汇报里说「写入经 ConfigBridge 确认卡」）不是写文件指令；
        // 抽不出 path 就交云端按整句判断 —— 不弹确认卡，也绝不会落到
        // 某个默认路径上。与 AmapPlaceQuery.isPlaceKeyword 同一思路：
        // 槽位值不合法时，端侧不硬执行。
        //
        // file_edit 同理，而且更危险：它带 needsLlm，槽位缺了会交给端侧
        // 模型去猜路径 —— 「你一并整理成一个文档放桌面」这种句子曾被猜成
        // /etc/hosts。猜出来的路径一律不认。
        if (capability.id in PATH_REQUIRED_WRITE_CAPABILITIES &&
            extraction.args.optString("path").isBlank()
        ) {
            return RouteDecision.Cloud("no-args", note = "no-args", ranked = ranked)
        }
        if (capability.id == "reminder" && action.name == "schedule" &&
            extraction.args.optString("at").isBlank() &&
            extraction.args.optLong("after", 0L) <= 0L
        ) {
            return RouteDecision.Cloud("no-args", note = "no-args", ranked = ranked)
        }
        if (capability.id == "scheduled_task" && action.name == "create" &&
            extraction.args.optString("at").isBlank() &&
            extraction.args.optLong("after", 0L) <= 0L
        ) {
            return RouteDecision.Cloud("no-args", note = "no-args", ranked = ranked)
        }
        val readOnly = action.readOnly
        val highRisk = action.risk == CapabilityRisk.HIGH
        // [T-assistant-confirm-destructive-only] 只有破坏性操作才弹确认卡。
        //
        // 之前的口径是「任何写操作都要确认」，结果「暂停播放」「设个闹钟」
        // 「记个笔记」这类一句话就能撤销的操作全都要先点一次确认 —— 助手
        // 变得又慢又啰嗦，比它做的事还费事。
        //
        // 现在按破坏性分级：CapabilityRisk.HIGH（删除、取消、覆盖文件、
        // 改系统设置、拨号、跑命令）仍然确认；其余（创建日程、提醒、笔记、
        // 定时任务、播放控制）直接执行。ALWAYS 仍然强制确认，NEVER 则
        // 连破坏性操作也不问 —— 用户自己设的开关。
        val needsConfirmation = when (best.route.confirm) {
            ConfirmMode.ALWAYS -> true
            ConfirmMode.NEVER -> false
            ConfirmMode.WRITE -> highRisk
        }

        // 「句子里出现『天气』两个字」不等于「用户要查天气」。
        // 只命中关键词、没有命中任何模式时，只有关键词在句子里占足够大的
        // 比重才算指令；长句里顺带提一个短词退回云端（例如
        // 「可以直接对接高德地图的天气接口」）。状态/控制类能力（天气、
        // 设备、媒体、日历）没有自由文本槽，讨论性词汇出现时直接视为话题讨论。
        //
        // 带自由文本槽的能力不适用长度/占比判断：槽值就是用户内容
        // （「找笔记 Project Alpha」里关键词只占一小段，但这是在搜索）。
        //
        // [T-fastpath-topic-guard] 检索类能力例外：`news_search` /
        // `stock_quote` / `web_search` 的 query 由 SlotExtractor 把整句原样
        // 回填，那不是「抽到了槽位」，不能拿它豁免保护 —— 否则长句里提到
        // 「新闻 / 网页搜索」就会直接播报/检索。
        val hasExtractedSlot = capability.id !in QUERY_ECHO_CAPABILITIES &&
            action.requiredSlots.any {
                extraction.args.optString(it).isNotBlank()
            }
        if (readOnly && !hasExtractedSlot) {
            val normalized = AssistantRouteMatcher.normalizeText(userText)
            val discussingReadOnlyTopic =
                normalized.length > TOPIC_DISCUSSION_MIN_LENGTH &&
                    DISCUSSION_CUES.any { normalized.contains(it) }
            if (userText.contains('\n') || discussingReadOnlyTopic ||
                !looksLikeReadOnlyCommand(normalized, best, capability.id)
            ) {
                return RouteDecision.Cloud("topic-mention", ranked = ranked)
            }
        }

        // A write route built only from a one-character/weak keyword is too
        // easy to trigger by accident. Let the stronger cloud agent handle it.
        if (!readOnly && !best.isStrong) {
            return RouteDecision.Cloud("weak-write-match", ranked = ranked)
        }
        if (extraction.ambiguous && !readOnly) {
            // An ambiguous time used to raise a confirmation card so the user
            // could correct the guess. [T-assistant-confirm-destructive-only]
            // Only destructive actions ask now: 「记笔记：明天买牛奶」has a
            // 「明天」in the note text, not a time, and the card for it was
            // pure friction. The parsed time is stated in the reply, so a
            // wrong guess is visible and correctable.
            return RouteDecision.Local(
                route = best.route,
                capability = capability.id,
                action = action.name,
                args = extraction.args,
                needsConfirmation = needsConfirmation,
                needsModelExtraction = false,
                missingSlots = extraction.missing,
                score = best.score,
                ranked = ranked,
                summary = extraction.summary,
                ambiguousTime = true,
            )
        }

        if (extraction.missing.isNotEmpty()) {
            if (capability.needsLlm) {
                return RouteDecision.Local(
                    route = best.route,
                    capability = capability.id,
                    action = action.name,
                    args = extraction.args,
                    needsConfirmation = needsConfirmation,
                    needsModelExtraction = true,
                    missingSlots = extraction.missing,
                    score = best.score,
                    ranked = ranked,
                    summary = extraction.summary,
                    ambiguousTime = extraction.ambiguous,
                )
            }
            return RouteDecision.Cloud("no-args", note = "no-args", ranked = ranked)
        }

        return RouteDecision.Local(
            route = best.route,
            capability = capability.id,
            action = action.name,
            args = extraction.args,
            needsConfirmation = needsConfirmation,
            needsModelExtraction = false,
            missingSlots = emptySet(),
            score = best.score,
            ranked = ranked,
            summary = extraction.summary,
            ambiguousTime = extraction.ambiguous,
        )
    }

    private companion object {
        /**
         * [F-001] 这些能力会写文件，path 槽抽不出来就不该本地执行。
         */
        val PATH_REQUIRED_WRITE_CAPABILITIES = setOf("file_write", "file_edit")

        /**
         * [F-002] Bridge handoff messages carry `[FROM: X] … [END X]` around
         * their text. They are internal traffic, never user commands.
         */
        fun isBridgeEnvelope(text: String): Boolean {
            val head = text.trimStart()
            if (!head.startsWith("[")) return false
            return head.startsWith("[FROM:", ignoreCase = true) ||
                head.startsWith("[FROM：", ignoreCase = true) ||
                head.startsWith("[END ", ignoreCase = true)
        }

        /** [T-media-route-long-text] 媒体路由的最大指令长度，超出走云端。 */
        const val MEDIA_COMMAND_MAX_LENGTH = 20

        /**
         * [T-fastpath-topic-guard] 这些只读能力的 query 是 SlotExtractor
         * 把整句原样回填的（见 SlotExtractor.extract），不是真实抽取的槽位。
         * 不能拿它豁免「话题提及保护」。
         */
        val QUERY_ECHO_CAPABILITIES = setOf("news_search", "stock_quote", "web_search")

        /** 以检索/播报动词开头的句子，这些能力可以按指令处理。 */
        val RETRIEVAL_CAPABILITIES = QUERY_ECHO_CAPABILITIES + setOf("weather")

        /** 只读指令的短句上限：超过这个长度就要看命中位置。 */
        const val READ_ONLY_COMMAND_MAX_LENGTH = 18

        /** 带明确检索动词的指令可以稍长（「帮我搜一下 Kotlin 协程教程」）。 */
        const val READ_ONLY_COMMAND_MAX_LENGTH_WITH_VERB = 24

        /** 命中区段距句首超过这个字数，就是在长文中部提了一句。 */
        const val READ_ONLY_SPAN_HEAD_MAX = 8

        /** 中文命中之后还剩这么多字，就认为指令已经说完了。 */
        const val READ_ONLY_TAIL_MAX = 4

        /** 英文按空格分词，尾部允许多一个词（「battery level」）。 */
        const val READ_ONLY_TAIL_MAX_ASCII = 10

        /** 命中之后紧跟疑问/请求时，尾部可以长一点。 */
        const val READ_ONLY_TAIL_QUESTION_MAX = 10

        /** 只看命中之后的这一小段里有没有疑问/请求词。 */
        const val READ_ONLY_TAIL_QUESTION_HEAD = 4

        /** 命中区段至少覆盖句子的这个比例，才算「整句都在请求」。 */
        const val READ_ONLY_MIN_COVERAGE = 0.6f

        const val TOPIC_DISCUSSION_MIN_LENGTH = 8

        /** 「帮我看看新闻」这类请求前缀，判动词前先剥掉。 */
        val REQUEST_PREFIXES = listOf("麻烦你", "麻烦", "帮我", "给我", "帮忙", "请你", "请")

        /** 只读检索类能力的明确执行动词。 */
        val RETRIEVAL_VERBS = listOf(
            "搜一下", "搜下", "搜搜", "搜索", "查一下", "查查", "查找",
            "播报", "播一下", "来点", "告诉我", "读一下", "念一下",
        )

        /**
         * 命中之后紧跟这些词，说明用户是在问数据（「天气怎么样」「需要
         * 带伞吗」「深证成指涨了还是跌了」），而不是在讨论这个能力。
         * 英文给 freebusy / weather 这类查询留几个时间词。
         */
        val QUESTION_TAILS = listOf(
            "怎么样", "如何", "多少", "几度", "有哪些", "是什么", "什么",
            "吗", "呢", "带伞", "穿什么", "要不要", "涨", "跌",
            "tomorrow", "today", "now", "latest",
        )

        /**
         * [T-fastpath-topic-guard] 句子里出现「接口 / 方案 / 设计 / 文案 /
         * 排序」这类词时，用户多半在讨论这个能力，而不是要它执行。
         * 「触发」是既有提醒路由的关键词，短句（≤8 字）不参与判断。
         */
        val DISCUSSION_CUES = listOf(
            "讨论", "方案", "接口", "规则", "触发", "代码", "实现",
            "优化", "排查", "日志", "设计", "开发", "对接",
            "文案", "推送", "排序", "界面", "页面", "模块", "按钮", "样式",
            "体验", "产品", "需求", "架构", "性能", "交互", "流程", "逻辑",
            "原理", "重构", "测试", "上线", "点击率", "功能",
        )

        /**
         * 只读能力（天气 / 新闻 / 行情 / 网页搜索 …）的一次命中是「指令」
         * 还是「提到」。
         *
         * 判据只有一条主线：命中的模式/关键词必须落在句首附近，并且
         * 句子在命中之后就该结束了 —— 后面要么没内容，要么是「怎么样 /
         * 吗 / 需要带伞」这类疑问尾巴。长文里中部的一次命中、命中之后
         * 大段叙述，都视为话题提及，交回云端按上下文判断。
         */
        fun looksLikeReadOnlyCommand(
            text: String,
            match: RouteMatch,
            capability: String,
        ): Boolean {
            if (capability in RETRIEVAL_CAPABILITIES &&
                text.length <= READ_ONLY_COMMAND_MAX_LENGTH_WITH_VERB
            ) {
                val head = stripRequestPrefix(text)
                if (RETRIEVAL_VERBS.any { head.startsWith(it) }) return true
            }
            if (match.matchedRanges.isEmpty()) return false
            val asciiHeavy = text.count { it.code < 128 } * 2 >= text.length
            val tailMax = if (asciiHeavy) READ_ONLY_TAIL_MAX_ASCII else READ_ONLY_TAIL_MAX
            return match.matchedRanges.any { range ->
                if (range.first > READ_ONLY_SPAN_HEAD_MAX) return@any false
                if (range.last >= text.lastIndex) return@any true
                val tail = text.substring(range.last + 1)
                val covered = range.last - range.first + 1
                if (text.length <= READ_ONLY_COMMAND_MAX_LENGTH && tail.length <= tailMax) {
                    return@any true
                }
                if (covered.toFloat() / text.length.coerceAtLeast(1) >= READ_ONLY_MIN_COVERAGE) {
                    return@any true
                }
                if (tail.length <= READ_ONLY_TAIL_QUESTION_MAX) {
                    val head = tail.take(READ_ONLY_TAIL_QUESTION_HEAD)
                    if (QUESTION_TAILS.any { head.contains(it) }) return@any true
                }
                false
            }
        }

        fun stripRequestPrefix(text: String): String {
            for (prefix in REQUEST_PREFIXES) {
                if (text.startsWith(prefix)) return text.removePrefix(prefix).trimStart()
            }
            return text
        }
    }
}

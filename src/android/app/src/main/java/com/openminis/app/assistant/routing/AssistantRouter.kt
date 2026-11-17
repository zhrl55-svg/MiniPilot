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
        val hasExtractedSlot = action.requiredSlots.any {
            extraction.args.optString(it).isNotBlank()
        }
        if (readOnly && !hasExtractedSlot && best.matchedPatterns.isEmpty()) {
            val normalized = AssistantRouteMatcher.normalizeText(userText)
            val coverage = best.longestKeywordLength.toFloat() /
                normalized.length.coerceAtLeast(1)
            val topicMention = normalized.length > TOPIC_MENTION_MIN_LENGTH &&
                coverage < TOPIC_MENTION_MIN_COVERAGE
            val discussingStatusCapability =
                capability.id in STATUS_CAPABILITIES &&
                    normalized.length > TOPIC_DISCUSSION_MIN_LENGTH &&
                    DISCUSSION_CUES.any { normalized.contains(it) }
            if (topicMention || discussingStatusCapability) {
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

        /** 超过这个长度还只命中一个短关键词，就当成「提到」而非「命令」。 */
        const val TOPIC_MENTION_MIN_LENGTH = 14

        /** [T-media-route-long-text] 媒体路由的最大指令长度，超出走云端。 */
        const val MEDIA_COMMAND_MAX_LENGTH = 20

        /** 关键词至少要占句子的这个比例，纯关键词匹配才可信。 */
        const val TOPIC_MENTION_MIN_COVERAGE = 0.30f

        /**
         * 状态/控制类能力没有自由文本槽：句子里出现「接口 / 方案 / 规则」
         * 这类讨论词汇时，用户是在聊这个能力，不是在调用它。
         */
        val STATUS_CAPABILITIES = setOf("weather", "device", "media", "calendar", "flashlight", "volume")

        const val TOPIC_DISCUSSION_MIN_LENGTH = 8

        val DISCUSSION_CUES = listOf(
            "讨论", "方案", "接口", "规则", "触发", "代码", "实现",
            "优化", "排查", "日志", "设计", "开发", "对接",
        )
    }
}

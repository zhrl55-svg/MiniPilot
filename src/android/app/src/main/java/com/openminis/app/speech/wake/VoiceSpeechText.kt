package com.openminis.app.speech.wake

/**
 * [T-voice-single-speak] 从要朗读的文本里剥掉「给眼睛看」的部分。
 *
 * ## 为什么
 *
 * 快路径把结果写进聊天时，正文是：
 *
 * ```
 * ✅ Weather
 *
 * 现在（15:15）：晴，6.1°C（体感 0.7°C），湿度 26%，风速 13.1 m/s，降水 0.0 mm。
 * ```
 *
 * 第一行是**气泡标题**（路由 label + tool_title），是给眼睛看的：屏幕上
 * 有图标有标题，一眼就知道是哪个能力的结果。念出来没有意义 —— 「✅
 * Weather」既不是答案也不是句子，而且和端侧 announce 播的真实结果重复。
 *
 * 只有气泡标题、没有正文时（`answer` 为空），整段都不该念。
 *
 * ## 剥什么
 *
 * - 开头的 `✅` / `⚠️` 标记行（气泡标题行）；
 * - 标题行后面空一行隔开的正文 —— 这才是要念的；
 * - 剥完如果什么都不剩 → 返回空串（调用方保持安静）。
 *
 * 纯逻辑，没有 Android 依赖，可直接跑单测。
 */
object VoiceSpeechText {

    /**
     * 一行气泡标题的判定：图标标记 + 标题文字。
     *
     * `✅ Weather` / `⚠️ Set alarm 失败` 都是标题；正文里正常句子不会
     * 以图标标记开头。
     */
    private val TITLE_LINE = Regex("""^\s*[✅⚠️❌⛔]\s*\S.*$""")

    /**
     * 取出该念的部分。
     *
     * @param text 快路径写进聊天的助手消息正文，或模型的回复。
     * @return 要朗读的文本；没有可念的内容时返回空串。
     */
    fun speakable(text: String): String {
        val body = text.trim()
        if (body.isEmpty()) return ""
        val lines = body.lines()
        // 不是气泡标题 → 整段都是模型的话，原样念。
        if (!TITLE_LINE.matches(lines.first())) return body
        // 是气泡标题：标题行之后，正文从第一个非空行开始。
        val after = lines.drop(1)
        val firstContent = after.indexOfFirst { it.isNotBlank() }
        if (firstContent < 0) return ""
        return after.drop(firstContent).joinToString("\n").trim()
    }
}

package com.openminis.app.speech

/**
 * [T-speech-aside] 解释性补注 —— 给眼睛看的内容，播报时不念。
 *
 * WHY: 端侧能力的结果文本里会带一句「这句话被理解成了什么」的补注，例如
 * 「已播放：稻香 — 周杰伦（理解为：流派=轻音乐）」。补注在屏幕上很有用
 * （误解一秒内可见），但念出来只是噪音：语音用户要的是「放了什么」，
 * 不是「我怎么理解的」。
 *
 * 补注由生产者用 [wrap] 生成，播报前由 [strip] 整体去掉；两边共用
 * [MARKER]，所以任何新能力只要用 [wrap] 写补注，播报就自动不念，
 * 屏幕上的原文照旧。
 */
object SpeechAside {

    /** 补注正文的开头标记。生产者与消费者共用。 */
    const val MARKER = "理解为"

    /** 一条补注：全角括号包裹、以 [MARKER] 起头。 */
    private val ASIDE = Regex("""（\s*$MARKER[：:]?[^）]*）""")

    /**
     * 生成一条补注。[text] 为空时返回空串 —— 调用方据此决定要不要接补注。
     */
    fun wrap(text: String): String {
        val body = text.trim()
        if (body.isEmpty()) return ""
        return "（$MARKER：$body）"
    }

    /**
     * 从一段文字里去掉所有解释性补注。补注换成空格而不是空串，
     * 这样「A（注）B」不会粘成「AB」。
     */
    fun strip(text: String): String {
        if (text.isEmpty()) return text
        return ASIDE.replace(text, " ")
    }
}

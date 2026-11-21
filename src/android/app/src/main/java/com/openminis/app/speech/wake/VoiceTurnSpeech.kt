package com.openminis.app.speech.wake

/**
 * [T-voice-single-speak] 一轮语音只能播一次。
 *
 * ## 为什么需要它
 *
 * 语音一轮里有两条播报通道：
 *
 *  1. **端侧 announce** —— 快路径命中时由 [com.openminis.app.ui.chat.voice.
 *     LocalActionVoiceConfirmer] 播报真实结果（「找到 5 条新闻。第 1 条…」、
 *     「现在（15:15）：晴，6.1°C…」）。
 *  2. **模型文本朗读** —— [WakeConversation.ask] 返回的回复由
 *     [WakeWordService] 整段念出来。
 *
 * 快路径那一轮里，ask() 用的就是同一个 ChatViewModel，所以两条通道都会
 * 触发，同一句话被播两遍。真机日志：
 *
 * ```
 * [15:19:39] 回复：✅ Search news: 搜一下今天的新闻      ← 通道 2
 * [15:19:41] voice: announce 找到 5 条新闻。第 1 条…   ← 通道 1
 * ```
 *
 * 而通道 2 念的那个 `✅ Search news: …` 是快路径写进聊天的**气泡标题**，
 * 本来是给眼睛看的（正文在下一段），念出来既没信息量又和真实结果重复。
 *
 * ## 约定
 *
 * 一轮语音（一次 [WakeConversation.ask]）里，先播的通道说了算：
 *
 * - 端侧 announce 播了 → 模型文本**不再**朗读；
 * - 模型文本先播了（纯对话轮，没有快路径结果）→ 端侧 announce **不播**。
 *
 * 标记的粒度是「一次语音轮次」：[WakeWordService] 在调 ask() 之前
 * [beginTurn]，本轮结束时 [endTurn]。
 */
object VoiceTurnSpeech {

    /** 本轮端侧 announce 是否已经播报过。 */
    @Volatile
    private var announcedThisTurn: Boolean = false

    /** 本轮是否由语音发起（只有语音轮次才做通道互斥）。 */
    @Volatile
    private var inVoiceTurn: Boolean = false

    /**
     * 一轮语音开始。上一轮的标记作废。
     *
     * 由 [WakeWordService] 在调 [WakeConversation.ask] 之前调用。
     */
    @Synchronized
    fun beginTurn() {
        announcedThisTurn = false
        inVoiceTurn = true
    }

    /**
     * 一轮语音结束（无论成功失败）。
     *
     * 语音之外发起的轮次不受影响 —— 聊天窗口里的文字对话不经过这里。
     *
     * @return 本轮端侧是否播报过结果。标记在 [endTurn] 里就被清掉了，
     *   所以这里直接交出来 —— 调用方事后再去读 [alreadyAnnounced] 只会拿到
     *   false，抑制分支就永远不会触发。
     */
    @Synchronized
    fun endTurn(): Boolean {
        val wasAnnounced = announcedThisTurn
        announcedThisTurn = false
        inVoiceTurn = false
        return wasAnnounced
    }

    /** 当前是否处于语音轮次。 */
    val isVoiceTurn: Boolean get() = inVoiceTurn

    /** 本轮端侧是否已经播报过结果。 */
    val alreadyAnnounced: Boolean get() = announcedThisTurn

    /**
     * 端侧 announce 播报前登记。
     *
     * 只有语音轮次才登记 —— 聊天窗口里的 announce 是给用户看的浮层，
     * 不该影响语音通道。
     */
    @Synchronized
    fun markAnnounced() {
        if (!inVoiceTurn) return
        announcedThisTurn = true
    }
}

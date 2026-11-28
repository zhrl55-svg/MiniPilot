package com.openminis.app.assistant.routing

import com.openminis.app.data.model.ChatMode

/**
 * [T-android-chat-mode] 语音/文字里的模式口令。
 *
 * 只认整句（允许尾部标点与「吧/啊/呀/哈」这类语气词），避免把普通聊天
 * 里顺口提到的「聊聊」当成切换指令。口令命中后不进模型、不产生一轮
 * 对话，直接在客户端切换模式并给一句提示。
 *
 * 这是语音场景的主通道：确定性、零延迟、零 token。模型自己的
 * `switch_mode` 工具只是兜底（见 ChatMode 的文档）。
 */
object ChatModeCommand {

    private val TO_CHAT = listOf(
        "进入闲聊", "闲聊模式", "切换到闲聊", "切到闲聊", "开始闲聊",
        "随便聊聊", "陪我聊聊", "陪我聊一会", "陪我聊一会儿",
        "和我聊聊", "跟我聊聊", "聊天模式",
    )

    private val TO_ASSISTANT = listOf(
        "切回助手", "回到助手", "退出闲聊", "结束闲聊", "助手模式",
        "切换到助手", "切到助手", "回到助理", "切回助理",
    )

    /** 尾部语气词与标点：识别结果常带，去掉后再做整句比较。 */
    private val TRAILING = charArrayOf(
        '。', '！', '!', '.', '，', ',', '、', ' ', '\t', '\n',
        '～', '~', '…',
        '吧', '啊', '呀', '哈', '呢', '哦', '噢', '嘛',
    )

    /** @return 目标模式；不是口令时返回 null。 */
    fun parse(text: String): ChatMode? {
        val normalized = text.trim().trimEnd(*TRAILING).trim()
        if (normalized.isEmpty()) return null
        return when {
            TO_CHAT.any { normalized.equals(it, ignoreCase = true) } -> ChatMode.CHAT
            TO_ASSISTANT.any { normalized.equals(it, ignoreCase = true) } -> ChatMode.ASSISTANT
            else -> null
        }
    }
}

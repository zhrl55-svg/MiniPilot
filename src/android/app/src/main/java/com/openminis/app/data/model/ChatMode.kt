package com.openminis.app.data.model

/**
 * [T-android-chat-mode] 会话级对话模式。
 *
 * ASSISTANT 是全功能助手；CHAT 是闲聊：请求里只保留本地轻能力工具、
 * 思考档位强制 OFF、回复长度收紧，并在系统提示末尾追加口语化规则。
 *
 * 模式随会话持久化（`sessions.chat_mode`），UI 的 chip 与语音口令都能
 * 切换；`null`（老会话）按 ASSISTANT 处理，升级后行为不变。
 */
enum class ChatMode {
    ASSISTANT,

    CHAT;

    /** 写入 `sessions.chat_mode` 的稳定值（改名前必须加迁移）。 */
    val wireValue: String get() = if (this == CHAT) "chat" else "assistant"

    companion object {
        /** 解析磁盘/备份里的值；未知值返回 null，由调用方决定默认。 */
        fun parseOrNull(raw: String?): ChatMode? = when (raw?.trim()?.lowercase()) {
            "chat", "casual", "闲聊" -> CHAT
            "assistant", "agent", "助手" -> ASSISTANT
            else -> null
        }
    }
}

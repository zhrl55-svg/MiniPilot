package com.openminis.app.assistant.routing

import com.openminis.app.data.model.ChatMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-android-chat-mode] 口令只认整句：语音转写常带语气词和标点，但
 * 普通句子里顺口提到的「聊聊」不能被当成切换指令。
 */
class ChatModeCommandTest {

    @Test
    fun `whole sentence switches to chat`() {
        assertEquals(ChatMode.CHAT, ChatModeCommand.parse("进入闲聊"))
        assertEquals(ChatMode.CHAT, ChatModeCommand.parse("进入闲聊吧。"))
        assertEquals(ChatMode.CHAT, ChatModeCommand.parse(" 随便聊聊啊"))
        assertEquals(ChatMode.CHAT, ChatModeCommand.parse("陪我聊一会儿～"))
    }

    @Test
    fun `whole sentence switches back to assistant`() {
        assertEquals(ChatMode.ASSISTANT, ChatModeCommand.parse("切回助手"))
        assertEquals(ChatMode.ASSISTANT, ChatModeCommand.parse("退出闲聊吧"))
        assertEquals(ChatMode.ASSISTANT, ChatModeCommand.parse("回到助手！"))
    }

    @Test
    fun `ordinary conversation is not a command`() {
        assertNull(ChatModeCommand.parse("今天天气怎么样"))
        assertNull(ChatModeCommand.parse("我们进入闲聊模式吧，聊聊天气"))
        assertNull(ChatModeCommand.parse("帮我查一下服务器日志"))
        assertNull(ChatModeCommand.parse(""))
        assertNull(ChatModeCommand.parse("   "))
    }
}

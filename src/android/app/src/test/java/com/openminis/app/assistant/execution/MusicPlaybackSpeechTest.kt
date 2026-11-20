package com.openminis.app.assistant.execution

import com.openminis.app.speech.VoiceTextSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * media/play 的两条契约：屏幕保留解释性补注，语音播报只念主内容。
 *
 * 播报文本就是 [VoiceTextSanitizer.sanitize] 的输出 —— 唤醒对话、聊天朗读
 * 和端侧播报都走这一个入口，所以这里断言的就是用户真正听到的内容。
 */
class MusicPlaybackSpeechTest {

    @Test
    fun `play success keeps the resolution aside on screen but not in the announcement`() {
        val display = musicPlaybackText(
            title = "稻香",
            artist = "周杰伦",
            understood = "流派=轻音乐 · 匹配歌单「轻音乐」· 12 首",
        )

        // 屏幕：主句和补注都在。
        assertTrue(display.contains("已播放：稻香 — 周杰伦"))
        assertTrue(display.contains("（理解为："))

        // 播报：只有主句，补注不念。
        val spoken = VoiceTextSanitizer.sanitize(display)
        assertTrue(spoken.contains("已播放：稻香 — 周杰伦"))
        assertFalse(spoken.contains("理解为"))
    }

    @Test
    fun `play success without a resolution has no aside at all`() {
        assertEquals(
            "已播放：稻香 — 周杰伦",
            musicPlaybackText(title = "稻香", artist = "周杰伦", understood = ""),
        )
    }

    @Test
    fun `play success without an artist keeps the bare title`() {
        val display = musicPlaybackText(
            title = "稻香",
            artist = null,
            understood = "歌名=稻香",
        )
        assertTrue(display.contains("已播放：稻香"))
        assertFalse(VoiceTextSanitizer.sanitize(display).contains("理解为"))
    }

    @Test
    fun `parenthetical content that is not an aside is still spoken`() {
        val spoken = VoiceTextSanitizer.sanitize("现在（10:30）：气温 20°C（体感 19°C）。")
        assertTrue(spoken.contains("体感 19°C"))
    }
}

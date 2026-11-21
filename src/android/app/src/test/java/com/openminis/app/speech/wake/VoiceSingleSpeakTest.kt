package com.openminis.app.speech.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-voice-single-speak] 一轮语音只播一次，只播有信息量的最终答案。
 *
 * 真机日志（同一轮播了两遍）：
 * ```
 * [15:19:39] 回复：✅ Search news: 搜一下今天的新闻      ← 模型文本朗读
 * [15:19:41] voice: announce 找到 5 条新闻。第 1 条…   ← 端侧 announce
 * ```
 */
class VoiceSingleSpeakTest {

    @Test
    fun `an announced turn suppresses the model text`() {
        VoiceTurnSpeech.beginTurn()
        assertFalse(VoiceTurnSpeech.alreadyAnnounced)

        // 端侧 announce 播了真实结果
        VoiceTurnSpeech.markAnnounced()
        assertTrue(VoiceTurnSpeech.alreadyAnnounced)

        // 这一轮不能再念模型文本
        VoiceTurnSpeech.endTurn()
    }

    @Test
    fun `a plain conversation turn keeps the model text`() {
        VoiceTurnSpeech.beginTurn()
        assertFalse(VoiceTurnSpeech.alreadyAnnounced)
        VoiceTurnSpeech.endTurn()
    }

    @Test
    fun `the announcement mark does not leak into the next turn`() {
        VoiceTurnSpeech.beginTurn()
        VoiceTurnSpeech.markAnnounced()
        VoiceTurnSpeech.endTurn()

        VoiceTurnSpeech.beginTurn()
        assertFalse("上一轮的标记不能带进这一轮", VoiceTurnSpeech.alreadyAnnounced)
        VoiceTurnSpeech.endTurn()
    }

    @Test
    fun `announcements outside a voice turn are not recorded`() {
        // 聊天窗口里的 announce 是给用户看的浮层，不该影响语音通道。
        VoiceTurnSpeech.endTurn()
        VoiceTurnSpeech.markAnnounced()
        assertFalse(VoiceTurnSpeech.alreadyAnnounced)
    }

    // ── 标记的交接 ──────────────────────────────────────────────────────

    @Test
    fun `end turn hands back the announcement flag`() {
        // [T-voice-single-speak] endTurn() 会清掉标记，所以必须由它把标记
        // 交出来。调用方事后去读 alreadyAnnounced 只会拿到 false —— 抑制
        // 分支就永远进不去，同一句话会被播两遍。
        VoiceTurnSpeech.beginTurn()
        VoiceTurnSpeech.markAnnounced()
        assertTrue("endTurn 必须交回 true", VoiceTurnSpeech.endTurn())
        assertFalse("标记在 endTurn 之后就没了", VoiceTurnSpeech.alreadyAnnounced)
    }

    @Test
    fun `end turn of a plain conversation reports no announcement`() {
        VoiceTurnSpeech.beginTurn()
        assertFalse(VoiceTurnSpeech.endTurn())
    }

    @Test
    fun `the handed back flag does not leak into the next turn`() {
        VoiceTurnSpeech.beginTurn()
        VoiceTurnSpeech.markAnnounced()
        assertTrue(VoiceTurnSpeech.endTurn())

        VoiceTurnSpeech.beginTurn()
        assertFalse("上一轮的标记不能带进这一轮", VoiceTurnSpeech.endTurn())
    }

    // ── 前导文本剥离 ────────────────────────────────────────────────────

    @Test
    fun `a bubble title alone is not spoken`() {
        assertEquals("", VoiceSpeechText.speakable("✅ Weather"))
    }

    @Test
    fun `a bubble title with a tool title is not spoken`() {
        assertEquals("", VoiceSpeechText.speakable("✅ Search news: 搜一下今天的新闻"))
    }

    @Test
    fun `the body under a bubble title is spoken`() {
        val text = """
            ✅ Weather

            现在（15:15）：晴，6.1°C（体感 0.7°C），湿度 26%，风速 13.1 m/s，降水 0.0 mm。
        """.trimIndent()
        assertEquals(
            "现在（15:15）：晴，6.1°C（体感 0.7°C），湿度 26%，风速 13.1 m/s，降水 0.0 mm。",
            VoiceSpeechText.speakable(text),
        )
    }

    @Test
    fun `a failed bubble title keeps its body`() {
        val text = """
            ⚠️ Set alarm 失败

            系统时钟没有接受这个闹钟，没有创建成功。
        """.trimIndent()
        assertEquals(
            "系统时钟没有接受这个闹钟，没有创建成功。",
            VoiceSpeechText.speakable(text),
        )
    }

    @Test
    fun `a plain model reply is spoken as is`() {
        val text = "今天多云，最高气温 18 度。"
        assertEquals(text, VoiceSpeechText.speakable(text))
    }

    @Test
    fun `a multi line model reply keeps its content`() {
        val text = """
            第一行
            第二行
        """.trimIndent()
        assertEquals(text, VoiceSpeechText.speakable(text))
    }

    @Test
    fun `empty text yields nothing to speak`() {
        assertEquals("", VoiceSpeechText.speakable(""))
        assertEquals("", VoiceSpeechText.speakable("   \n  "))
    }
}

package com.openminis.app.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechAsideTest {

    @Test
    fun `wrap builds a marked aside`() {
        assertEquals(
            "（理解为：流派=轻音乐）",
            SpeechAside.wrap("流派=轻音乐"),
        )
    }

    @Test
    fun `wrap of blank text is empty`() {
        assertEquals("", SpeechAside.wrap(""))
        assertEquals("", SpeechAside.wrap("   "))
    }

    @Test
    fun `strip removes the aside and keeps the surrounding text`() {
        val text = "已播放：稻香 — 周杰伦\n（理解为：流派=轻音乐 · 匹配歌单「轻音乐」）"
        val stripped = SpeechAside.strip(text)
        assertFalse(stripped.contains("理解为"))
        assertTrue(stripped.contains("已播放：稻香 — 周杰伦"))
    }

    @Test
    fun `strip leaves ordinary parentheses alone`() {
        val text = "现在（10:30）：气温 20°C（体感 19°C）。"
        assertEquals(text, SpeechAside.strip(text))
    }
}

package com.openminis.app.assistant.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-android-cjk-punct-repair] The provider's tool-call template flattens
 * full-width Chinese punctuation to ASCII and can drop whole spans of text.
 * These tests pin what the device side restores silently and what it reports
 * as damage (so the agent re-creates the task instead of trusting it).
 */
class CjkPunctuationRepairTest {

    @Test
    fun `restores full-width commas between CJK characters`() {
        assertEquals("甲，乙，丙", repairCjkPunctuation("甲,乙,丙"))
    }

    @Test
    fun `restores colon and semicolon between CJK characters`() {
        assertEquals("时间：明天；地点：家里", repairCjkPunctuation("时间:明天;地点:家里"))
    }

    @Test
    fun `leaves ASCII punctuation between ASCII alone`() {
        // Paths, URLs, code fragments and JSON must not be rewritten.
        assertEquals("a,b,c", repairCjkPunctuation("a,b,c"))
        assertEquals("https://example.com/a,b", repairCjkPunctuation("https://example.com/a,b"))
    }

    @Test
    fun `leaves ascii punctuation at the edge next to CJK`() {
        assertEquals("甲,", repairCjkPunctuation("甲,"))
        assertEquals(",甲", repairCjkPunctuation(",甲"))
    }

    @Test
    fun `leaves text without CJK untouched`() {
        assertEquals("hello, world: 1; 2", repairCjkPunctuation("hello, world: 1; 2"))
    }

    @Test
    fun `detects consecutive separators as damage`() {
        assertNotNull(detectDamagedText(", ,检查壁挂炉水压"))
        assertNotNull(detectDamagedText("甲，，乙"))
    }

    @Test
    fun `detects leading separator as damage`() {
        assertNotNull(detectDamagedText("，检查水压"))
    }

    @Test
    fun `normal text is not damaged`() {
        assertNull(detectDamagedText("每天晚上7点半，检查壁挂炉水压，并用语音播报"))
        // A single ASCII comma between CJK is repaired, not reported.
        assertNull(detectDamagedText("甲,乙"))
    }
}

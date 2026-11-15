package com.openminis.app.data.repository.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryTokenizerTest {

    @Test
    fun chineseRunsBecomeAdjacentBigrams() {
        assertEquals(
            listOf("小窗", "窗怎", "怎么", "么开"),
            MemoryTokenizer.tokenize("小窗怎么开"),
        )
    }

    @Test
    fun singleChineseCharacterIsKept() {
        assertEquals(listOf("窗"), MemoryTokenizer.tokenize("窗"))
    }

    @Test
    fun latinWordsAreLowercasedAndSnakeCaseIsSplit() {
        val tokens = MemoryTokenizer.tokenize("MCP_Server v2")
        assertTrue(tokens.contains("mcp_server"))
        assertTrue(tokens.contains("mcp"))
        assertTrue(tokens.contains("server"))
        assertTrue(tokens.contains("v2"))
    }

    @Test
    fun mixedTextKeepsBothTokenStreams() {
        val tokens = MemoryTokenizer.tokenize("buntu 服务")
        assertTrue(tokens.contains("buntu"))
        assertTrue(tokens.contains("服务"))
    }

    @Test
    fun emptyTextYieldsNoTokens() {
        assertTrue(MemoryTokenizer.tokenize("").isEmpty())
    }
}

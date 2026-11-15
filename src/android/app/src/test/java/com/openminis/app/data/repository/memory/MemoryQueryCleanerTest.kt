package com.openminis.app.data.repository.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryQueryCleanerTest {

    @Test
    fun gluedQuestionSuffixIsRemoved() {
        assertEquals("buntu 服务", MemoryQueryCleaner.clean("buntu 上有哪些服务"))
    }

    @Test
    fun functionWordsAreRemovedButContentWordsRemain() {
        assertEquals("回复 偏好", MemoryQueryCleaner.clean("我的 回复 偏好"))
    }

    @Test
    fun synonymExpansionAddsKnownAliases() {
        val cleaned = MemoryQueryCleaner.clean("小窗")
        assertTrue(cleaned.contains("小窗"))
        assertTrue(cleaned.contains("freeform"))
        assertTrue(cleaned.contains("浮窗"))
    }

    @Test
    fun singleCharacterContentWordIsNotDropped() {
        assertEquals("上证 指数", MemoryQueryCleaner.clean("上证 指数"))
    }

    @Test
    fun allStopwordQueryFallsBackToOriginalText() {
        assertEquals("怎么", MemoryQueryCleaner.clean("怎么"))
    }
}

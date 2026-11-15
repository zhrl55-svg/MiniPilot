package com.openminis.app.data.repository

import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MemoryRepositoryIndexTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun rankedSearchReturnsSourceAndSection() {
        val repository = MemoryRepository(seed())

        val output = repository.getMemory("magpie 401", "all")

        assertTrue(output.contains("Recalled"))
        assertTrue(output.contains("GLOBAL.md › 已知的坑"))
        assertTrue(output.contains("401"))
    }

    @Test
    fun emptyKeywordsKeepTheLegacyFullDump() {
        val repository = MemoryRepository(seed())

        val output = repository.getMemory("", "all")

        assertTrue(output.startsWith("[GLOBAL.md"))
        assertTrue(output.contains("freeform 小窗"))
    }

    @Test
    fun noIndexHitFallsBackToTheLegacyNoMatchMessage() {
        val repository = MemoryRepository(seed())

        val output = repository.getMemory("zzzz-no-such-token", "all")

        assertTrue(output.startsWith("No matches found for keywords:"))
    }

    @Test
    fun saveGlobalMdInvalidatesTheIndex() {
        val repository = MemoryRepository(seed())
        repository.getMemory("magpie 401", "all")

        repository.saveGlobalMd("## 新章节\nnewunique token\n")

        val output = repository.getMemory("newunique", "all")
        assertTrue(output.contains("新章节"))
        assertTrue(output.contains("newunique"))
    }

    private fun seed(): File {
        val dir = tmp.newFolder("memory")
        File(dir, "GLOBAL.md").writeText(
            """
            ## 屏幕操作
            freeform 小窗 使用 am start --windowingMode 5

            ## 已知的坑
            magpie 控制台 401 用 curl 访问
            """.trimIndent() + "\n",
        )
        File(dir, "USER.md").writeText(
            """
            ## 核心偏好
            回复要简洁
            """.trimIndent() + "\n",
        )
        return dir
    }
}

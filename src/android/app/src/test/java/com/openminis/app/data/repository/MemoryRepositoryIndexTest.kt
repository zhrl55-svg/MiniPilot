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

    // -- [T-memory-structured-write] write path --

    @Test
    fun writeMemoryStoresCanonicalTagsAndSummary() {
        val repository = MemoryRepository(seed())

        val result = repository.writeMemory(
            content = "## 直连验证\ntsnet 可用。",
            summary = "tailscale 直连已验证",
            tags = "玛格派, 小窗, made-up-tag",
        )

        assertTrue(result.startsWith("Memory saved"))
        val log = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()) + ".md"
        val text = repository.readFile(log)
        assertTrue(text.contains("Summary: tailscale 直连已验证"))
        assertTrue(text.contains("Tags: freeform, magpie"))
        assertTrue(!text.contains("made-up-tag"))
    }

    @Test
    fun writeMemoryWithoutMetadataKeepsTheLegacyFormat() {
        val repository = MemoryRepository(seed())

        repository.writeMemory("## 旧格式\n正文")

        val log = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()) + ".md"
        val text = repository.readFile(log)
        assertTrue(!text.contains("Summary:"))
        assertTrue(!text.contains("Tags:"))
    }

    /** Undo/edit match the human body, not the metadata prefix. */
    @Test
    fun revokeMatchesBodyEvenWhenMetadataIsPresent() {
        val repository = MemoryRepository(seed())
        repository.writeMemory("## 可撤销\n正文内容", summary = "摘要", tags = "hub")

        val outcome = repository.revokeEntry("## 可撤销\n正文内容")

        assertTrue(outcome is MemoryRepository.EntryMutationResult.Success)
    }

    @Test
    fun replaceEntryBodyKeepsTheMetadataPrefix() {
        val repository = MemoryRepository(seed())
        repository.writeMemory("## 可编辑\n旧正文", summary = "摘要保留", tags = "hub")

        val outcome = repository.replaceEntryBody("## 可编辑\n旧正文", "## 可编辑\n新正文")

        assertTrue(outcome is MemoryRepository.EntryMutationResult.Success)
        val log = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()) + ".md"
        val text = repository.readFile(log)
        assertTrue(text.contains("Summary: 摘要保留"))
        assertTrue(text.contains("新正文"))
        assertTrue(!text.contains("旧正文"))
    }

    /** Explicit tags must make an otherwise untagged entry retrievable. */
    @Test
    fun explicitTagsMakeAnEntryRetrievableByAlias() {
        val repository = MemoryRepository(seed())
        repository.writeMemory("## 记录\n某个结论。", tags = "magpie")

        val output = repository.getMemory("玛格派", "daily")
        assertTrue(output.contains("记录"))
    }
}

package com.openminis.app.data.repository.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MemoryIndexRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun fingerprintPicksUpExternalWrites() {
        val dir = seed()
        val repository = MemoryIndexRepository(dir)

        assertEquals("已知的坑", repository.search("magpie 401", MemoryScope.ALL, 5).hits.first().entry.title)

        File(dir, "GLOBAL.md").appendText("\n## 新增段落\nzzunique token\n")

        val hits = repository.search("zzunique", MemoryScope.ALL, 5).hits
        assertEquals(1, hits.size)
        assertEquals("新增段落", hits.first().entry.title)
    }

    @Test
    fun invalidateDoesNotBreakTheNextSearch() {
        val dir = seed()
        val repository = MemoryIndexRepository(dir)
        repository.search("freeform 小窗", MemoryScope.ALL, 5)

        repository.invalidate()

        assertEquals("屏幕操作", repository.search("freeform 小窗", MemoryScope.ALL, 5).hits.first().entry.title)
    }

    @Test
    fun secretFileNeverEntersTheIndex() {
        val dir = seed()
        File(dir, ".workbuddy_phone").writeText("WorkBuddy login phone: +86 00000000000\n")
        val repository = MemoryIndexRepository(dir)

        assertTrue(repository.search("workbuddy phone", MemoryScope.ALL, 5).hits.isEmpty())
        assertEquals(4, repository.status().entryCount)
    }

    @Test
    fun statusAndRebuildReportTheSameEntrySet() {
        val repository = MemoryIndexRepository(seed())

        val before = repository.status()
        assertEquals(4, before.entryCount)
        assertEquals(2, before.globalCount)
        assertEquals(1, before.userCount)
        assertEquals(1, before.dailyCount)

        repository.rebuild()
        assertEquals(before.entryCount, repository.status().entryCount)
        assertFalse(repository.status().fingerprint.isEmpty())
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
        File(dir, "2026-11-14.md").writeText(
            """
            <!-- 2026-11-14 09:00:00 -->
            ## amap.py 双后端
            支持高德和百度地图导航
            """.trimIndent() + "\n",
        )
        return dir
    }
}

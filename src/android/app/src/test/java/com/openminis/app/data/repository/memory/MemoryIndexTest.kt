package com.openminis.app.data.repository.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MemoryIndexTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var index: MemoryIndex

    @Before
    fun setUp() {
        index = MemoryIndex(MemoryEntryParser.parse(seed()))
    }

    @Test
    fun ranksTheMatchingGlobalSectionFirst() {
        assertEquals("屏幕操作", top("freeform 小窗"))
        assertEquals("已知的坑", top("magpie 401"))
    }

    @Test
    fun userProfileIsSearchableInAllScope() {
        assertEquals("核心偏好", top("回复 偏好"))
    }

    @Test
    fun dailyEntryIsRankedForItsOwnQuery() {
        assertEquals("amap.py 双后端", top("amap 百度"))
    }

    @Test
    fun dailyScopeExcludesGlobalAndUserEntries() {
        val hits = index.search("回复 偏好", MemoryScope.DAILY)
        assertTrue(hits.none { it.entry.kind == MemoryKind.USER })
        assertTrue(hits.none { it.entry.kind == MemoryKind.GLOBAL })
    }

    @Test
    fun emptyQueryReturnsNothing() {
        assertTrue(index.search("", MemoryScope.ALL).isEmpty())
    }

    @Test
    fun queryWithoutAnyLexicalSignalReturnsNothing() {
        assertTrue(index.search("zzzz-no-such-token", MemoryScope.ALL).isEmpty())
    }

    @Test
    fun limitIsHonored() {
        assertEquals(1, index.search("freeform 小窗", MemoryScope.ALL, limit = 1).size)
    }

    private fun top(query: String): String? =
        index.search(query, MemoryScope.ALL).firstOrNull()?.entry?.title

    private fun seed(): File {
        val dir = tmp.newFolder("memory")

        File(dir, "GLOBAL.md").writeText(
            """
            ## 屏幕操作
            freeform 小窗 使用 am start --windowingMode 5

            ## 已知的坑
            magpie 控制台 401 用 curl 访问

            ## 网络
            ping 可以，nc -z 有假阳性
            """.trimIndent() + "\n",
        )
        File(dir, "USER.md").writeText(
            """
            ## 核心偏好
            回复要简洁，选择项用编号
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

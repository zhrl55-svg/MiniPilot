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

    /**
     * [T-memory-authority-gain] The regression this multiplier exists for: a
     * daily note that repeats a keyword must not outrank the GLOBAL section
     * that documents it. Before the change the additive authority term was
     * worth ~0.003 and the daily note won on raw BM25.
     */
    @Test
    fun globalSectionOutranksADailyNoteRepeatingTheSameKeyword() {
        val dir = tmp.newFolder("authority")
        File(dir, "GLOBAL.md").writeText("## 网络\nping 可以用，nc -z 有假阳性\n")
        File(dir, "2026-11-22.md").writeText(
            "<!-- 2026-11-22 09:00:00 -->\n" +
                "## 顺手记一笔\n" +
                "今天 ping 了一下 ping 通了 ping 没问题 ping 正常\n",
        )
        val ranked = MemoryIndex(MemoryEntryParser.parse(dir))
            .search("ping 能不能用", MemoryScope.ALL)

        assertTrue(ranked.isNotEmpty())
        assertEquals(MemoryKind.GLOBAL, ranked.first().entry.kind)
    }

    /** A daily-only scope must be untouched by the authority multiplier. */
    @Test
    fun authorityGainDoesNotReorderDailyOnlyResults() {
        val dir = tmp.newFolder("daily-only")
        File(dir, "GLOBAL.md").writeText("## 网络\nping 可以用\n")
        File(dir, "2026-11-22.md").writeText(
            "<!-- 2026-11-22 09:00:00 -->\n## 甲\nping 通了\n\n" +
                "<!-- 2026-11-22 10:00:00 -->\n## 乙\nping 也通了\n",
        )
        val entries = MemoryEntryParser.parse(dir)
        val hits = MemoryIndex(entries).search("ping", MemoryScope.DAILY)

        assertTrue(hits.isNotEmpty())
        assertTrue(hits.all { it.entry.kind == MemoryKind.DAILY })
        // Every daily candidate has importance 1.0 → gain factor is exactly 1.
        assertTrue(hits.all { it.entry.importance == MemoryIndexConfig.IMPORTANCE_DAILY })
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

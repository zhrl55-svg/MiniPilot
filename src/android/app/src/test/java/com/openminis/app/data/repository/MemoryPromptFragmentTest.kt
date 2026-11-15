package com.openminis.app.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [T-memory-prompt-index] The fixed prompt carries a section index, not the
 * full memory files: GLOBAL.md contributes `## title — first line` and older
 * daily logs contribute their `## ` titles only. Exact wording stays one
 * file_read / memory_get away.
 */
class MemoryPromptFragmentTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun globalFragmentIsAnIndexNotTheFullText() {
        val dir = tmp.newFolder("memory")
        val body = "正文细节 ".repeat(400)
        File(dir, "GLOBAL.md").writeText(
            buildString {
                for (i in 1..8) {
                    append("## 章节$i\n")
                    append("第${i}段摘要\n")
                    append(body).append("\n\n")
                }
            },
        )
        val repository = MemoryRepository(dir)

        val fragment = repository.loadGlobalMemoryFragment()
        assertNotNull(fragment)
        requireNotNull(fragment)

        // Titles and their one-line summaries are present…
        assertTrue(fragment.contains("## 章节1 — 第1段摘要"))
        assertTrue(fragment.contains("GLOBAL.md"))
        // …but the 10k+ characters of section bodies are not.
        assertFalse(fragment.contains(body))
        assertTrue("index must stay small, was ${fragment.length}", fragment.length < 4_500)
    }

    @Test
    fun globalFragmentFallsBackToTheHeadWithoutHeadings() {
        val dir = tmp.newFolder("flat-memory")
        File(dir, "GLOBAL.md").writeText(
            (1..80).joinToString("\n") { "line $it" },
        )
        val repository = MemoryRepository(dir)

        val fragment = repository.loadGlobalMemoryFragment()
        assertNotNull(fragment)
        requireNotNull(fragment)
        assertTrue(fragment.contains("line 1"))
        assertTrue(fragment.contains("line 40"))
        assertFalse(fragment.contains("line 60"))
    }

    @Test
    fun dailyFragmentKeepsTodaysBodyAndOlderTitlesOnly() {
        val dir = tmp.newFolder("daily-memory")
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val now = Date()
        val today = fmt.format(now)
        val yesterday = fmt.format(Date(now.time - 86400_000L))
        val before = fmt.format(Date(now.time - 2 * 86400_000L))

        File(dir, "GLOBAL.md").writeText("## 占位\n内容\n")
        File(dir, "$today.md").writeText(
            "<!-- $today 09:00:00 -->\n## 今日任务\n今天要处理的事情\n",
        )
        File(dir, "$yesterday.md").writeText(
            "<!-- $yesterday 09:00:00 -->\n## 昨日标题甲\n昨日正文细节甲\n\n" +
                "<!-- $yesterday 10:00:00 -->\n## 昨日标题乙\n昨日正文细节乙\n",
        )
        File(dir, "$before.md").writeText(
            "<!-- $before 09:00:00 -->\n## 前日标题\n前日正文细节\n",
        )

        val repository = MemoryRepository(dir)
        val fragment = repository.loadRecentDailyMemoryFragment()
        assertNotNull(fragment)
        requireNotNull(fragment)

        // Today keeps its body.
        assertTrue(fragment.contains("今天要处理的事情"))
        // Older logs keep titles, not bodies.
        assertTrue(fragment.contains("昨日标题甲"))
        assertTrue(fragment.contains("昨日标题乙"))
        assertTrue(fragment.contains("前日标题"))
        assertFalse(fragment.contains("昨日正文细节甲"))
        assertFalse(fragment.contains("前日正文细节"))
        assertTrue(fragment.contains("titles only"))
    }

    @Test
    fun olderLogWithoutHeadingsFallsBackToItsFirstLines() {
        val dir = tmp.newFolder("untitled-daily")
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val yesterday = fmt.format(Date(System.currentTimeMillis() - 86400_000L))
        File(dir, "GLOBAL.md").writeText("## 占位\n内容\n")
        File(dir, "$yesterday.md").writeText(
            (1..30).joinToString("\n") { "未标注条目 $it" },
        )

        val repository = MemoryRepository(dir)
        val fragment = repository.loadRecentDailyMemoryFragment()
        assertNotNull(fragment)
        requireNotNull(fragment)
        assertTrue(fragment.contains("未标注条目 1"))
        assertFalse(fragment.contains("未标注条目 25"))
    }
}

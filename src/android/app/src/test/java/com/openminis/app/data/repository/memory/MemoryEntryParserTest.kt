package com.openminis.app.data.repository.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MemoryEntryParserTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun parsesSectionsDailyBlocksAndExcludesPrivateFiles() {
        val dir = seed()

        val entries = MemoryEntryParser.parse(dir)

        assertEquals(6, entries.size)
        assertEquals("（前言）", entries.first().title)
        assertTrue(entries.any { it.kind == MemoryKind.GLOBAL && it.title == "屏幕操作" })
        assertTrue(entries.any { it.kind == MemoryKind.USER && it.title == "核心偏好" })
        assertTrue(entries.any { it.kind == MemoryKind.DAILY && it.title == "amap.py 双后端" })
        assertTrue(entries.any { it.kind == MemoryKind.DAILY && it.title == "语音播报" })

        val screen = entries.first { it.title == "屏幕操作" }
        assertEquals(listOf("步骤"), screen.headings)

        val daily = entries.first { it.title == "amap.py 双后端" }
        assertEquals("2026-11-14", daily.date)

        // Hard exclusion: SOUL.md, dotfiles, backups, and unrelated .md files.
        assertTrue(entries.none { it.file == "SOUL.md" })
        assertTrue(entries.none { it.file == ".workbuddy_phone" })
        assertTrue(entries.none { it.file.contains(".bak.") })
        assertTrue(entries.none { it.file == "notes.md" })
        assertTrue(entries.none { it.fullText.contains("login phone") })
    }

    @Test
    fun emptyGlobalFileProducesNoEntries() {
        val dir = tmp.newFolder("empty-memory")
        File(dir, "GLOBAL.md").writeText("")

        assertTrue(MemoryEntryParser.parse(dir).isEmpty())
    }

    @Test
    fun dailyBlockWithoutHeadingUsesPlaceholderTitle() {
        val dir = tmp.newFolder("untitled-memory")
        File(dir, "2026-11-14.md").writeText(
            "<!-- 2026-11-14 08:00:00 -->\n" +
                "没有标题的一行\n",
        )

        val entries = MemoryEntryParser.parse(dir)
        assertEquals(1, entries.size)
        assertEquals("（无标题）", entries[0].title)
        assertNotNull(entries[0].date)
    }

    @Test
    fun isMemoryFileMatchesTheParserAllowlist() {
        assertTrue(MemoryEntryParser.isMemoryFile("GLOBAL.md"))
        assertTrue(MemoryEntryParser.isMemoryFile("USER.md"))
        assertTrue(MemoryEntryParser.isMemoryFile("2026-11-14.md"))
        assertTrue(!MemoryEntryParser.isMemoryFile("SOUL.md"))
        assertTrue(!MemoryEntryParser.isMemoryFile(".workbuddy_phone"))
        assertTrue(!MemoryEntryParser.isMemoryFile("GLOBAL.md.bak.20261114T131125Z"))
        assertTrue(!MemoryEntryParser.isMemoryFile("notes.md"))
    }

    private fun seed(): File {
        val dir = tmp.newFolder("memory")

        File(dir, "GLOBAL.md").writeText(
            """
            |Preamble line.
            |
            |## 屏幕操作
            |freeform 小窗说明
            |### 步骤
            |am task resize
            |
            |## 已知的坑
            |magpie 401
            """.trimMargin() + "\n",
        )
        File(dir, "USER.md").writeText(
            """
            |## 核心偏好
            |回复要简洁
            """.trimMargin() + "\n",
        )
        File(dir, "2026-11-14.md").writeText(
            """
            |<!-- 2026-11-14 09:00:00 -->
            |## amap.py 双后端
            |支持高德和百度地图
            |
            |<!-- 2026-11-14 10:00:00 -->
            |## 语音播报
            |不要读出星号
            """.trimMargin() + "\n",
        )
        File(dir, "SOUL.md").writeText("## identity\nbe direct\n")
        File(dir, ".workbuddy_phone").writeText("WorkBuddy login phone: +86 00000000000\n")
        File(dir, "GLOBAL.md.bak.20260101T000000Z").writeText("## 已知的坑\nmagpie 401\n")
        File(dir, "notes.md").writeText("## 随便\nprivate notes\n")
        return dir
    }
}

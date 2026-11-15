package com.openminis.app.agent.skill

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-3 / P1-4: what gets sedimented and what survives an upgrade.
 *
 * These tests are Context-free on purpose: [SkillStore] keeps decode/encode,
 * distillation and hint rendering as pure helpers, so the migration promise
 * can be verified without an Android runtime.
 */
class SkillStoreTest {

    private fun v2Document(): String {
        val step = JSONObject().apply {
            put("tool", "shell_execute")
            put("target", "du -sh /data/user/0/com.openminis.app.dev/files/* | sort -h")
            put("viewId", JSONObject.NULL)
            put("summary", "执行命令：du -sh /data/user/0/com.openminis.app.dev/files/* | sort -h")
        }
        val record = JSONObject().apply {
            put("id", "abc123")
            put("goal", "看看手机存储被什么占满了")
            put("appPackage", "com.openminis.app.dev")
            put("keywords", JSONArray().put("看看").put("看手"))
            put("steps", JSONArray().put(step))
            put("toolChain", JSONArray().put("shell_execute"))
            put("successCount", 2)
            put("createdAt", 1000L)
            put("lastUsedAt", 2000L)
        }
        return JSONObject().apply {
            put("version", 2)
            put("records", JSONArray().put(record))
        }.toString()
    }

    @Test
    fun `schema 2 file loads and upgrades with every field preserved`() {
        val records = SkillStore.decodeRoot(v2Document())
        assertNotNull(records)
        assertEquals(1, records!!.size)
        val old = records[0]
        assertEquals("abc123", old.id)
        assertEquals("看看手机存储被什么占满了", old.goal)
        assertEquals("com.openminis.app.dev", old.appPackage)
        assertEquals(2, old.successCount)
        assertEquals(1, old.steps.size)
        assertNull(old.steps[0].input)
        assertNull(old.steps[0].output)
        assertNull(old.conclusion)

        val upgraded = SkillStore.encodeRoot(records)
        val root = JSONObject(upgraded)
        assertEquals(3, root.getInt("version"))
        val reloaded = SkillStore.decodeRoot(upgraded)
        assertNotNull(reloaded)
        assertEquals(1, reloaded!!.size)
        assertEquals("abc123", reloaded[0].id)
        assertEquals(old.goal, reloaded[0].goal)
        assertEquals(old.steps[0].summary, reloaded[0].steps[0].summary)
        assertEquals(2, reloaded[0].successCount)
    }

    @Test
    fun `foreign or damaged versions read as no data`() {
        assertNull(SkillStore.decodeRoot("""{"version":1,"records":[]}"""))
        assertNull(SkillStore.decodeRoot("""{"version":3}"""))
    }

    @Test
    fun `waste removal collapses repeated calls and keeps the last one`() {
        val first = SkillStep("shell_execute", "ls /tmp", null, "执行命令：ls /tmp", "ls /tmp", "a b")
        val second = SkillStep("shell_execute", "du -sh /tmp", null, "执行命令：du -sh /tmp", "du -sh /tmp", "1G /tmp")
        val repeated = first.copy(output = "a b c")
        val distilled = SkillStore.distillSteps(listOf(first, second, repeated))
        assertEquals(2, distilled.size)
        assertEquals("du -sh /tmp", distilled[0].input)
        assertEquals("a b c", distilled[1].output)
    }

    @Test
    fun `bare tool-call lines are dropped when a concrete step exists`() {
        val bare = SkillStep("some_tool", null, null, "调用 some_tool")
        val concrete = SkillStep("shell_execute", "pwd", null, "执行命令：pwd", "pwd", "/data")
        val distilled = SkillStore.distillSteps(listOf(bare, concrete))
        assertEquals(1, distilled.size)
        assertEquals("pwd", distilled[0].input)
    }

    @Test
    fun `a run with no actionable step yields nothing to record`() {
        val ask = SkillStep("user_ask", "要继续吗", null, "向用户确认：要继续吗")
        val memory = SkillStep("memory_write", null, null, "写入记忆")
        assertTrue(SkillStore.distillSteps(listOf(ask, memory)).isEmpty())
    }

    @Test
    fun `hint renders a followable method, not a tool-name list`() {
        val record = SkillRecord(
            id = "r1",
            goal = "查询 App 私有目录的存储构成",
            appPackage = "com.openminis.app.dev",
            keywords = emptyList(),
            steps = listOf(
                SkillStep(
                    tool = "shell_execute",
                    target = "du -sh /data/user/0/com.openminis.app.dev/files/* | sort -h",
                    summary = "执行命令：du -sh /data/user/0/com.openminis.app.dev/files/* | sort -h",
                    input = "du -sh /data/user/0/com.openminis.app.dev/files/* | sort -h",
                    output = "1.2G /data/user/0/com.openminis.app.dev/files/alpine-rootfs\n212M .../models",
                ),
            ),
            toolChain = listOf("shell_execute"),
            createdAt = 0L,
            lastUsedAt = 0L,
            conclusion = "最大的是 alpine-rootfs，约 1.2 GB；其次是 models。",
        )
        val hint = SkillStore.renderHint(record, "看看私有目录里什么最占空间")
        assertTrue(hint, hint.contains("du -sh /data/user/0/com.openminis.app.dev/files/*"))
        assertTrue(hint, hint.contains("关键输出："))
        assertTrue(hint, hint.contains("alpine-rootfs"))
        assertTrue(hint, hint.contains("结论："))
        assertTrue(hint, hint.contains("先核对再执行"))
        // The old failure mode was a list of tool names. The rendered hint
        // must not expose the dispatcher's internal name at all.
        assertFalse(hint, hint.contains("shell_execute"))
    }

    @Test
    fun `opposite direction is called out rather than copied blindly`() {
        val record = SkillRecord(
            id = "r2",
            goal = "打开手电筒",
            appPackage = null,
            keywords = emptyList(),
            steps = listOf(SkillStep("shell_execute", "cmd", null, "执行命令：cmd", "cmd", "ok")),
            toolChain = listOf("shell_execute"),
            createdAt = 0L,
            lastUsedAt = 0L,
            conclusion = "手电筒已打开",
        )
        val hint = SkillStore.renderHint(record, "把闪光灯关掉")
        assertTrue(hint, hint.contains("方向与上次记录相反"))
    }

    @Test
    fun `long command output keeps both ends`() {
        val raw = (1..20).joinToString("\n") { "line$it" }
        val excerpt = SkillStore.outputExcerpt(raw)
        assertNotNull(excerpt)
        assertTrue(excerpt!!, excerpt.startsWith("line1"))
        assertTrue(excerpt, excerpt.contains("line20"))
        assertTrue(excerpt, excerpt.contains("…"))
        assertTrue("must stay bounded: ${excerpt.length}", excerpt.length <= 600)
    }
}

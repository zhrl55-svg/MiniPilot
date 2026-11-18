package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-port-fold-tools] Port of iOS ToolFoldPlannerTests: which runs of tool
 * calls the chat folds into one summary row, and when.
 *
 * [T-android-fold-activity] Android folds more than iOS did: a run also folds
 * when it ends at a barrier, at an unfinished tool, or at the end of the
 * message, and thinking blocks count as members (thinking-only runs fold). The
 * cases below carry the Android expectations; the ones that differ from iOS
 * are the no-reply / thinking-only ones.
 *
 * Block notation: T = succeeded tool, F = failed, C = cancelled, R = unfinished
 * (running / streaming), K = thinking, t = non-empty multi-line text, o =
 * non-empty ONE-line text, e = empty text, B = barrier (info / sub agent /
 * user_ask). Output: one "lo-hi:tools/thinking/failed/cancelled" per group, or
 * "none". Like the list, [show] runs markingNotes first.
 */
class ToolFoldPlannerTest {

    private fun parse(s: String): List<ToolFoldPlanner.Input> = s.map { c ->
        when (c) {
            'T' -> ToolFoldPlanner.Input.Tool(ToolFoldPlanner.ToolOutcome.SUCCEEDED)
            'F' -> ToolFoldPlanner.Input.Tool(ToolFoldPlanner.ToolOutcome.FAILED)
            'C' -> ToolFoldPlanner.Input.Tool(ToolFoldPlanner.ToolOutcome.CANCELLED)
            'R' -> ToolFoldPlanner.Input.Tool(ToolFoldPlanner.ToolOutcome.UNFINISHED)
            'K' -> ToolFoldPlanner.Input.Thinking
            't', 'o' -> ToolFoldPlanner.Input.Text(isEmpty = false)
            'e' -> ToolFoldPlanner.Input.Text(isEmpty = true)
            else -> ToolFoldPlanner.Input.Barrier
        }
    }

    private fun show(s: String, enabled: Boolean = true): String {
        val inputs = ToolFoldPlanner.markingNotes(parse(s)) { s[it] == 'o' }
        val g = ToolFoldPlanner.plan(inputs, enabled)
        if (g.isEmpty()) return "none"
        return g.joinToString(",") {
            "${it.range.first}-${it.range.last}:${it.toolCount}/${it.thinkingCount}/${it.failedCount}/${it.cancelledCount}"
        }
    }

    private val cases = mapOf(
        "three" to ("TTTt" to "0-2:3/0/0/0"),
        "two" to ("TTt" to "0-1:2/0/0/0"),
        "one" to ("Tt" to "none"),
        "oneThink" to ("TKKt" to "0-2:1/2/0/0"),
        "leadText" to ("tTTTTt" to "1-4:4/0/0/0"),
        "interleaved" to ("KTKTTt" to "0-4:3/2/0/0"),
        "thinkOnly" to ("TKKTt" to "0-3:2/2/0/0"),
        "thinkMid" to ("TKTKTt" to "0-4:3/2/0/0"),
        "emptyMid" to ("TTeTt" to "0-3:3/0/0/0"),
        "infoMid" to ("TTBTTt" to "0-1:2/0/0/0,3-4:2/0/0/0"),
        "delegateSplit" to ("TTTBTTTt" to "0-2:3/0/0/0,4-6:3/0/0/0"),
        "noReply" to ("tTTTT" to "1-4:4/0/0/0"),
        "noReplyEmpty" to ("TTTe" to "0-2:3/0/0/0"),
        "running" to ("TTTRt" to "0-2:3/0/0/0"),
        "runningFirst" to ("RTTTt" to "1-3:3/0/0/0"),
        "failCancel" to ("TFCTt" to "0-3:4/0/1/1"),
        "twoRuns" to ("TTTtTTTTt" to "0-2:3/0/0/0,4-7:4/0/0/0"),
        "trailingThink" to ("TTTKt" to "0-3:3/1/0/0"),
        "allThinking" to ("KKKt" to "0-2:0/3/0/0"),
        "noteExample" to ("ToTo" to "0-2:2/0/0/0"),
        "noteThenReply" to ("ToTt" to "0-2:2/0/0/0"),
        "notesLong" to ("ToToTTt" to "0-5:4/0/0/0"),
        "multiLineMid" to ("TTtTTt" to "0-1:2/0/0/0,3-4:2/0/0/0"),
        "noteLive" to ("TToR" to "0-2:2/0/0/0"),
        "noteNoReply" to ("TToTT" to "0-4:4/0/0/0"),
        "noteOneTool" to ("ToR" to "none"),
        "noteLead" to ("oTTt" to "1-2:2/0/0/0"),
        "noteBeforeBarrier" to ("TToBt" to "0-1:2/0/0/0"),
        "noteThink" to ("TKoTt" to "0-3:2/1/0/0"),
        "noteAfterSoftEnd" to ("TToTTR" to "0-4:4/0/0/0"),
        "leadThink" to ("KTTt" to "0-2:2/1/0/0"),
        "leadThinks" to ("KKeTTt" to "0-4:2/2/0/0"),
        "leadThinkAfterText" to ("tKTTt" to "1-3:2/1/0/0"),
        "leadThinkSecondRun" to ("TTtKTTt" to "0-1:2/0/0/0,3-5:2/1/0/0"),
        "leadThinkOneTool" to ("KTt" to "0-1:1/1/0/0"),
        "leadThinkNote" to ("KoTTt" to "0-3:2/1/0/0"),
        "leadThinkBarrier" to ("KBTTt" to "0-0:0/1/0/0,2-3:2/0/0/0"),
        "leadThinkRunning" to ("KRTTt" to "0-0:0/1/0/0,2-3:2/0/0/0"),
        "singleThink" to ("Kt" to "0-0:0/1/0/0"),
    )

    @Test
    fun `every planner case gives the expected groups`() {
        for ((name, c) in cases) assertEquals(name, c.second, show(c.first))
    }

    @Test
    fun `threshold, setting off`() {
        assertEquals(2, ToolFoldPlanner.MINIMUM_TOOL_COUNT)
        assertEquals(2, ToolFoldPlanner.MINIMUM_FOLD_MEMBERS)
        assertEquals("none", show("TTTTt", enabled = false))
    }

    @Test
    fun `single line and line count`() {
        assertTrue(ToolFoldPlanner.isSingleLine("  Now checking the logs.  \n"))
        assertFalse(ToolFoldPlanner.isSingleLine("line one\nline two"))
        assertTrue(ToolFoldPlanner.isSingleLine("\n\nOne line after blank lines\n\n"))
        assertTrue(ToolFoldPlanner.isSingleLine(""))
        assertEquals(0, ToolFoldPlanner.nonEmptyLineCount(""))
        assertEquals(1, ToolFoldPlanner.nonEmptyLineCount("Checking the logs now."))
        assertEquals(1, ToolFoldPlanner.nonEmptyLineCount("\n  \nOnly one line\n\n   \n"))
        assertEquals(2, ToolFoldPlanner.nonEmptyLineCount("First line\nSecond line"))
        assertEquals(2, ToolFoldPlanner.nonEmptyLineCount("First\n\n\n   \nSecond\n"))
        assertEquals(2, ToolFoldPlanner.nonEmptyLineCount("First\r\nSecond"))
        assertEquals(3, ToolFoldPlanner.nonEmptyLineCount("- a\n- b\n- c"))
    }

    @Test
    fun `browse hold, row lookup only when needed`() {
        var probed = 0
        fun hb(b: Boolean, f: Boolean, r: Boolean, shown: Boolean) =
            ToolFoldPlanner.holdsBack(b, f, r) { probed++; shown }
        assertFalse(hb(false, false, true, true))
        assertFalse(hb(true, true, true, true))
        assertTrue(hb(true, false, true, false))
        assertTrue(hb(true, false, false, true))
        assertFalse(hb(true, false, false, false))
        probed = 0
        hb(false, false, false, true); hb(true, true, false, true); hb(true, false, true, true)
        assertEquals(0, probed)
    }

    // --- AssistantBlock adapters and the live message ---------------------------

    private var n = 0
    private fun tool(status: ToolBlockStatus = ToolBlockStatus.SUCCESS, name: String = "shell_execute", ms: Long = 0) =
        AssistantBlock(id = "tu${n++}", kind = "tool_use", toolStatus = status, toolName = name, durationMs = ms)
    private fun text(s: String) = AssistantBlock(id = "x${n++}", kind = "text", content = s)
    private fun think() = AssistantBlock(id = "k${n++}", kind = "thinking", content = "hmm")

    @Test
    fun `block inputs - barriers, statuses and notes`() {
        assertEquals(ToolFoldPlanner.Input.Barrier, ToolFoldPlanner.input(AssistantBlock(id = "i", kind = "info")))
        assertEquals(ToolFoldPlanner.Input.Barrier, ToolFoldPlanner.input(tool(name = "subagent_task")))
        assertEquals(ToolFoldPlanner.Input.Barrier, ToolFoldPlanner.input(tool(name = "delegate_task")))
        assertEquals(ToolFoldPlanner.Input.Barrier, ToolFoldPlanner.input(tool(name = "user_ask")))
        assertEquals(ToolFoldPlanner.Input.Tool(ToolFoldPlanner.ToolOutcome.FAILED), ToolFoldPlanner.input(tool(ToolBlockStatus.TIMEOUT)))
        for (s in listOf(ToolBlockStatus.STREAMING, ToolBlockStatus.PENDING, ToolBlockStatus.RUNNING, null)) {
            assertEquals(ToolFoldPlanner.Input.Tool(ToolFoldPlanner.ToolOutcome.UNFINISHED), ToolFoldPlanner.input(tool(s ?: ToolBlockStatus.RUNNING).copy(toolStatus = s)))
        }
        assertEquals(ToolFoldPlanner.Input.Text(isEmpty = true), ToolFoldPlanner.input(text("  \n ")))
        val blocks = listOf(tool(), text("Now the logs."), tool(), text("Done.\nAll good."))
        assertEquals(ToolFoldPlanner.Input.Note, ToolFoldPlanner.inputs(blocks)[1])
        assertEquals(ToolFoldPlanner.Input.Text(isEmpty = false), ToolFoldPlanner.inputs(blocks)[3])
    }

    @Test
    fun `folded runs carry the first tool's id as key, members and summed time`() {
        val k = think()
        val a = tool(ms = 1200)
        val b = tool(ToolBlockStatus.FAILED, ms = 800)
        val reply = text("Done.\nAll good.")
        val runs = ToolFoldPlanner.foldedRuns(listOf(k, a, b, reply))
        assertEquals(1, runs.size)
        val r = runs[0]
        assertEquals(a.id, r.key)
        assertEquals(k.id, r.firstBlockId)
        assertEquals(listOf(k.id, a.id, b.id), r.memberIds)
        assertEquals(2000L, r.durationMs)
        assertEquals(1, r.group.failedCount)
        assertEquals(2, r.group.toolCount)
        assertEquals(1, r.group.thinkingCount)
        // No recorded time at all: no duration.
        assertEquals(null, ToolFoldPlanner.foldedRuns(listOf(tool(), tool(), reply)).single().durationMs)
    }

    @Test
    fun `android folds activity that never sees a reply, the live tail stays visible`() {
        val t1 = tool(); val t2 = tool()
        // An ended turn with no reply text: the finished stretch folds.
        assertEquals(listOf(t1.id, t2.id), ToolFoldPlanner.foldedRuns(listOf(t1, t2)).single().memberIds)
        // Thinking-only turn: folds into one thinking run keyed by its first block.
        val k1 = think(); val k2 = think()
        val only = ToolFoldPlanner.foldedRuns(listOf(k1, k2)).single()
        assertEquals(0, only.group.toolCount)
        assertEquals(2, only.group.thinkingCount)
        assertEquals(k1.id, only.key)
        // A lone thinking card folds too (it was the largest part of the
        // reported wall); a lone tool capsule keeps its pill.
        assertTrue(ToolFoldPlanner.foldedRuns(listOf(tool())).isEmpty())
        val loneThink = ToolFoldPlanner.foldedRuns(listOf(think())).single()
        assertEquals(1, loneThink.group.thinkingCount)
        assertEquals(0, loneThink.group.toolCount)
        // A streaming reply ends the run and renders below the group row.
        val streaming = ChatMessage(id = "m", role = "assistant", content = "", isStreaming = true,
            toolBlocks = listOf(t1, t2, text("Working on it")))
        assertEquals(1, ToolFoldPlanner.foldedRuns(streaming).size)
        // The trailing thinking block of a running turn is still streaming:
        // it stays out of the fold, everything before it folds.
        val live = streaming.copy(toolBlocks = listOf(t1, t2, think()))
        assertEquals(listOf(t1.id, t2.id), ToolFoldPlanner.foldedRuns(live).single().memberIds)
        // The running tool is never a member either.
        val running = streaming.copy(toolBlocks = listOf(t1, t2, tool(ToolBlockStatus.RUNNING)))
        assertEquals(listOf(t1.id, t2.id), ToolFoldPlanner.foldedRuns(running).single().memberIds)
    }

    // --- Rows ------------------------------------------------------------------

    private fun rowsFor(m: ChatMessage) = buildFlatChatItems(listOf(m))

    @Test
    fun `a folded run becomes one group row where its first member was, members back when expanded`() {
        val k = think(); val a = tool(ms = 1000); val b = tool(ms = 2000); val reply = text("Done.\nAll good.")
        val m = ChatMessage(id = "m", role = "assistant", content = "", toolBlocks = listOf(k, a, b, reply))
        val rows = rowsFor(m)
        val runs = mapOf("m" to ToolFoldPlanner.foldedRuns(m.toolBlocks))
        val folded = applyToolFolds(rows, runs, emptySet())
        val keys = folded.map { it.key }
        assertTrue(keys.toString(), "toolgroup:m:${a.id}" in keys)
        assertFalse("thinking:m:${k.id}" in keys || "tool:m:${a.id}" in keys || "tool:m:${b.id}" in keys)
        assertTrue(keys.any { it.startsWith("mdblock:m:${reply.id}") })
        assertEquals(keys.indexOf("toolgroup:m:${a.id}"), rows.indexOfFirst { it.key == "thinking:m:${k.id}" })
        val g = folded.filterIsInstance<FlatChatItem.AssistantToolGroup>().single()
        assertEquals(2, g.toolCount); assertEquals(1, g.thinkingCount)
        assertEquals(3000L, g.durationMs); assertFalse(g.expanded)

        val open = applyToolFolds(rows, runs, setOf(a.id)).map { it.key }
        val gi = open.indexOf("toolgroup:m:${a.id}")
        assertEquals(listOf("thinking:m:${k.id}", "tool:m:${a.id}", "tool:m:${b.id}"), open.subList(gi + 1, gi + 4))
        // Nothing to fold: the same list instance.
        assertSame(rows, applyToolFolds(rows, emptyMap(), emptySet()))
    }

    @Test
    fun `controller - off passes through, running turns fold, a shown run is held while browsing`() {
        val a = tool(); val b = tool(); val reply = text("Done.\nAll good.")
        val m = ChatMessage(id = "m", role = "assistant", content = "", toolBlocks = listOf(a, b, reply))
        val rows = rowsFor(m)
        val off = ToolFoldController()
        assertSame(rows, off.fold(rows, listOf(m), emptyList()))
        // A fresh controller folds at once, even when scrolled up: nothing has
        // been shown as rows yet, so nothing moves under the reader.
        val freshBrowsing = ToolFoldController().apply { enabled = true; browsing = true }
        assertTrue(freshBrowsing.fold(rows, listOf(m), emptyList()).any { it is FlatChatItem.AssistantToolGroup })

        // [T-android-fold-activity] A running turn folds its finished stretch
        // as it goes; the live copy is what is planned.
        val d = ToolFoldController().apply { enabled = true }
        val live = m.copy(isStreaming = true, toolBlocks = listOf(a, b, text("Done.\nAll")))
        assertTrue(d.fold(rowsFor(live), listOf(live), listOf(live)).any { it is FlatChatItem.AssistantToolGroup })

        // Browse hold: a run first SHOWN as separate rows, with the user
        // scrolled up, is not folded away under them...
        val hold = ToolFoldController().apply { enabled = true }
        val oneTool = ChatMessage(id = "h", role = "assistant", content = "", toolBlocks = listOf(a))
        val oneRows = rowsFor(oneTool)
        assertSame(oneRows, hold.fold(oneRows, listOf(oneTool), emptyList()))
        hold.browsing = true
        val grown = oneTool.copy(toolBlocks = listOf(a, b, reply))
        assertFalse(hold.fold(rowsFor(grown), listOf(grown), emptyList()).any { it is FlatChatItem.AssistantToolGroup })
        // ...back at the bottom it folds, and stays folded when they scroll up again.
        hold.browsing = false
        assertTrue(hold.fold(rowsFor(grown), listOf(grown), emptyList()).any { it is FlatChatItem.AssistantToolGroup })
        hold.browsing = true
        assertTrue(hold.fold(rowsFor(grown), listOf(grown), emptyList()).any { it is FlatChatItem.AssistantToolGroup })
        // A tap opens it in place and ticks `changes`.
        val before = hold.changes.value
        hold.toggle(a.id)
        assertTrue(hold.changes.value > before)
        assertTrue(hold.fold(rowsFor(grown), listOf(grown), emptyList()).any { it.key == "tool:h:${a.id}" })
    }

    @Test
    fun `a running task folds its finished requests - the live copy is checked, not the empty stored one`() {
        // [T-fold-live-turn] During a turn the stored message has no tool blocks
        // (they stream into the overlay); the fold used to test the stored copy's
        // block count first, so a running task never folded until it ended.
        val a = tool(); val b = tool()
        val c = tool()
        val stored = ChatMessage(id = "m", role = "assistant", content = "", toolBlocks = emptyList(), isStreaming = true)
        val live = stored.copy(toolBlocks = listOf(a, b, c))
        val d = ToolFoldController().apply { enabled = true }
        val out = d.fold(rowsFor(live), listOf(stored), listOf(live))
        assertTrue(out.map { it.key }.toString(), out.any { it is FlatChatItem.AssistantToolGroup && it.runKey == a.id })
    }

    // --- Wiring ----------------------------------------------------------------

    @Test
    fun `the chat list folds its rows, renders and toggles the group row`() {
        val screen = ProductionSources.read("ui/chat/ChatScreen.kt")
        assertTrue(screen.contains("flatItems = toolFold.fold("))
        assertTrue(screen.contains("toolFold.changes,"))
        // [T-user-ask-resolved-expand] The tap also anchors the row's top first (ToggleAnchorTest).
        val tap = screen.substringAfter("is FlatChatItem.AssistantToolGroup -> ToolGroupRow(item) {").substringBefore("\n                            }")
        assertTrue(tap.contains("toolFold.toggle(item.runKey)"))
        assertTrue(screen.contains("snapshotFlow { userScrolledAway }.collect { toolFold.browsing = it }"))
        assertTrue(screen.contains("com.openminis.app.ui.settings.KEY_FOLD_TOOLS, com.openminis.app.ui.settings.FOLD_TOOLS_DEFAULT,"))
        assertTrue(screen.contains("sp.getBoolean(key, com.openminis.app.ui.settings.FOLD_TOOLS_DEFAULT)"))
        assertFalse(screen.contains("KEY_FOLD_TOOLS, false"))
    }

    @Test
    fun `the setting is on by default, in Appearance and the config registry`() {
        // [PAR-261022-05] Every read site goes through one default, so an
        // untouched install folds while a stored false is kept.
        assertTrue(com.openminis.app.ui.settings.FOLD_TOOLS_DEFAULT)
        val app = ProductionSources.read("ui/settings/AppearanceScreen.kt")
        assertTrue(app.contains("const val KEY_FOLD_TOOLS = \"fold_consecutive_tools\""))
        assertTrue(app.contains("mutableStateOf(prefs.getBoolean(KEY_FOLD_TOOLS, FOLD_TOOLS_DEFAULT))"))
        val reg = ProductionSources.read("config/ConfigBuiltins.kt")
        val entry = reg.substringAfter("path = \"chat.foldTools\"").substringBefore(")")
        assertTrue(entry.contains("key = com.openminis.app.ui.settings.KEY_FOLD_TOOLS"))
        assertTrue(entry.contains("defaultValue = com.openminis.app.ui.settings.FOLD_TOOLS_DEFAULT"))
    }

    @Test
    fun `every locale has the fold strings`() {
        val res = File(ProductionSources.mainRoot()!!, "../../../../res")
        val locales = listOf("values", "values-de", "values-es", "values-fil", "values-fr", "values-hr", "values-in", "values-ja",
            "values-ko", "values-ms", "values-pl", "values-pt-rBR", "values-ro", "values-ru", "values-th", "values-tr", "values-zh", "values-zh-rTW")
        val keys = listOf("tool_fold_setting_title", "tool_fold_section", "tool_fold_setting_footer", "tool_fold_ran_n_tools",
            "tool_fold_n_failed", "tool_fold_n_cancelled", "tool_fold_expand_hint", "tool_fold_collapse_hint")
        for (l in locales) {
            val f = File(res, "$l/strings_tool_fold.xml")
            assertTrue("missing ${f.path}", f.isFile)
            val text = f.readText()
            for (k in keys) assertTrue("$l lacks $k", text.contains("name=\"$k\""))
            assertTrue("$l count placeholder", text.contains("%1\$d"))
        }
        assertFalse(File(res, "values/strings_tool_fold.xml").readText().lowercase().contains("default"))
    }
}

package com.openminis.app.sandbox.offload

import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import com.openminis.app.tools.AgentTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [T-self-manual] `minis-self` must cover every tool and both CLI families. */
class SelfManualTest {

    private fun run(vararg argv: String): NativeOffloadResult =
        SelfOffloadHandler().handle(
            NativeOffloadRequest(
                pid = 0,
                argv = listOf("minis-self") + argv,
                env = emptyMap(),
                cwd = "/",
            ),
        )

    @Test
    fun helpListsEverySubcommand() {
        val out = run("--help").output
        for (sub in listOf("tools", "cli", "settings", "links", "features", "arch")) {
            assertTrue("help must mention $sub", out.contains(sub))
        }
    }

    @Test
    fun toolsCommandCoversEveryDefinedTool() {
        val out = run("tools").output
        val tools = AgentTools.makeAgentTools()
        assertTrue(tools.isNotEmpty())
        for (tool in tools) {
            assertTrue("tools output must describe ${tool.name}", out.contains("- ${tool.name} "))
        }
    }

    @Test
    fun toolsDefaultIsCompactAndFullExpands() {
        val compact = run("tools").output
        val full = run("tools", "--full").output
        assertTrue("compact must be shorter than full", compact.length < full.length)
        assertTrue(compact.contains("- shell_execute"))
        assertTrue(!compact.contains("params:"))
        assertTrue(full.contains("params:"))
    }

    @Test
    fun toolsFilterNarrowsTheList() {
        val out = run("tools", "--filter", "memory").output
        assertTrue(out.contains("memory_get"))
        assertTrue(out.contains("memory_write"))
        assertTrue(!out.contains("- browser_use"))
    }

    @Test
    fun cliManualCoversAndroidAndMinisClis() {
        val out = run("cli").output
        assertTrue(out.contains("android-calendar"))
        assertTrue(out.contains("android-speech"))
        assertTrue(out.contains("minis-config"))
        assertTrue(out.contains("minis-scheduled"))
    }

    @Test
    fun cliNamePrintsOnlyThatEntry() {
        val out = run("cli", "minis-config").output
        assertTrue(out.contains("audit-list"))
        assertTrue(!out.contains("android-calendar"))
    }

    @Test
    fun featuresAcceptAPositionalKeyword() {
        val out = run("features", "语音").output
        assertTrue(out.contains("语音"))
        assertTrue(!out.contains("记忆 GLOBAL/USER"))
    }

    @Test
    fun featuresAndArchitectureAreAvailable() {
        assertTrue(run("features").output.contains("记忆"))
        assertTrue(run("arch").output.contains("ChatViewModel"))
        assertTrue(run("links").output.contains("minis://settings/memory"))
    }

    @Test
    fun unknownSubcommandFails() {
        assertEquals(1, run("nope").exitCode)
    }
}

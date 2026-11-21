package com.openminis.app.assistant.routing

import com.openminis.app.assistant.execution.LocalCapabilityExecutor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-cloud-fastpath-catalog] The catalog must be a faithful view of the
 * registry: every capability lands in exactly one bucket, the in-process
 * bucket is the executor's own supported set, and the rendered prompt names
 * every action and risk. These tests are the anti-drift guard the prompt
 * depends on.
 */
class LocalCapabilityCatalogTest {

    @Test
    fun `registry contains the full surface`() {
        assertEquals(28, LocalCapabilityCatalog.totalCapabilityCount)
    }

    @Test
    fun `in-process bucket is exactly the executor supported set`() {
        val fromExecutor = LocalCapabilityExecutor.SUPPORTED_CAPABILITIES
        val fromCatalog = LocalCapabilityCatalog.invokableCapabilityIds.toSet()
        assertEquals(fromExecutor, fromCatalog)
        assertEquals(13, fromCatalog.size)
    }

    @Test
    fun `every capability is in exactly one bucket`() {
        val all = LocalCapabilityRegistry.all.map { it.id }.toSet()
        val local = LocalCapabilityCatalog.invokableCapabilityIds.toSet()
        val direct = LocalCapabilityCatalog.directToolCapabilityIds.toSet()
        assertTrue("buckets overlap: ${local intersect direct}", (local intersect direct).isEmpty())
        assertEquals(all, local + direct)
        assertEquals(15, direct.size)
    }

    @Test
    fun `direct bucket only names capabilities that have an agent tool`() {
        val agentToolNames = com.openminis.app.tools.AgentTools.makeAgentTools(
            hubEnabled = true,
        ).map { it.name }.toSet()
        val direct = LocalCapabilityCatalog.directToolCapabilityIds.toSet()
        // Every direct-tool capability must be dispatchable as a same-named
        // agent tool. hub_call is built by HubCallTool with the same name.
        assertTrue(
            "direct capabilities without a same-named tool: ${direct - agentToolNames}",
            agentToolNames.containsAll(direct),
        )
    }

    @Test
    fun `prompt lists every in-process capability with its actions`() {
        val prompt = LocalCapabilityCatalog.renderPromptSection()
        assertTrue(prompt.contains(LocalCapabilityInvocation.TOOL_NAME))
        for (capability in LocalCapabilityRegistry.all) {
            val line = prompt.lineSequence().firstOrNull { it.startsWith("- ${capability.id} — ") }
            if (capability.id in LocalCapabilityRegistry.inProcessCapabilities) {
                assertTrue("missing prompt line for ${capability.id}", line != null)
                for (action in capability.actions.keys) {
                    assertTrue(
                        "action ${capability.id}/$action missing from prompt",
                        line!!.contains(action),
                    )
                }
            } else {
                assertTrue("direct tool ${capability.id} must not get a callable line", line == null)
                assertTrue(
                    "direct tool ${capability.id} missing from the use-that-tool list",
                    prompt.contains(capability.id),
                )
            }
        }
    }

    @Test
    fun `high risk actions are marked and read only actions are marked`() {
        val calendarLine = LocalCapabilityCatalog.renderPromptSection()
            .lineSequence().first { it.startsWith("- calendar ") }
        assertTrue(calendarLine.contains("delete(id_or_title:TEXT) [HIGH: asks first]"))
        assertTrue(calendarLine.contains("list [read-only]"))
    }

    @Test
    fun `renderAction uses compact slot notation`() {
        val create = LocalCapabilityRegistry.action("calendar", "create")!!
        assertEquals("create(title:TEXT, start:TIME, end?:TIME)", LocalCapabilityCatalog.renderAction(create))

        val timer = LocalCapabilityRegistry.action("alarm", "timer")!!
        assertEquals("timer(duration:DURATION, label?:TEXT)", LocalCapabilityCatalog.renderAction(timer))

        val play = LocalCapabilityRegistry.action("media", "play")!!
        assertTrue(LocalCapabilityCatalog.renderAction(play).contains("kind:ENUM(song|artist|genre|scene|chart|none)"))
        assertTrue(LocalCapabilityCatalog.renderAction(play).contains("chart?:ENUM(hot|new|rising|recent|daily|liked)"))
    }

    @Test
    fun `prompt is deterministic`() {
        assertEquals(
            LocalCapabilityCatalog.renderPromptSection(),
            LocalCapabilityCatalog.renderPromptSection(),
        )
    }

    @Test
    fun `prompt tells the model never to refuse before trying`() {
        val prompt = LocalCapabilityCatalog.renderPromptSection()
        assertTrue(prompt.contains("never answer that it is unavailable before trying it"))
        assertTrue(prompt.contains("Never answer \"cannot do it\""))
        // No matching capability must fall back to the normal agent tools.
        assertTrue(prompt.contains("use the normal agent tools (shell_execute etc.)"))
        assertFalse(prompt.contains("\r"))
    }

    @Test
    fun `dispatcher tool is gated by the fast path switch`() {
        val off = com.openminis.app.tools.AgentTools.makeAgentTools()
        assertTrue(
            "local_capability must be absent when the fast path is off",
            off.none { it.name == LocalCapabilityInvocation.TOOL_NAME },
        )
        val on = com.openminis.app.tools.AgentTools.makeAgentTools(localCapabilityEnabled = true)
        val tool = on.first { it.name == LocalCapabilityInvocation.TOOL_NAME }
        assertEquals(
            LocalCapabilityCatalog.invokableCapabilityIds,
            tool.parameters.getValue("capability").enumValues,
        )
        assertEquals(listOf("tool_title", "capability", "action"), tool.required)
    }
}

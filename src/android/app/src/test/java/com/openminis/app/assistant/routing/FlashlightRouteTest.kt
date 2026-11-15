package com.openminis.app.assistant.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-local-flashlight] The torch is a deterministic on-device capability:
 * on/off/status must route locally, never ask for confirmation, and a
 * sentence that merely discusses the feature must stay on the cloud path.
 */
class FlashlightRouteTest {

    private val router = AssistantRouter()

    private fun route(text: String): RouteDecision =
        router.route(text, DefaultAssistantRoutes.all)

    @Test
    fun opensTheTorchLocally() {
        val decision = route("打开手电筒") as RouteDecision.Local
        assertEquals("flashlight", decision.capability)
        assertEquals("on", decision.action)
        assertFalse(decision.needsConfirmation)
    }

    @Test
    fun closesTheTorchLocally() {
        val decision = route("关掉手电筒") as RouteDecision.Local
        assertEquals("flashlight", decision.capability)
        assertEquals("off", decision.action)
        assertFalse(decision.needsConfirmation)
    }

    @Test
    fun bareNounReportsStatus() {
        val decision = route("手电筒") as RouteDecision.Local
        assertEquals("flashlight", decision.capability)
        assertEquals("status", decision.action)
        assertFalse(decision.needsConfirmation)
    }

    @Test
    fun statusQuestionReportsStatus() {
        val decision = route("手电筒现在开着吗") as RouteDecision.Local
        assertEquals("flashlight", decision.capability)
        assertEquals("status", decision.action)
    }

    @Test
    fun englishPhrasesRouteToo() {
        assertEquals("on", (route("turn on the flashlight") as RouteDecision.Local).action)
        assertEquals("off", (route("turn off the torch") as RouteDecision.Local).action)
    }

    @Test
    fun discussionDoesNotTriggerTheLight() {
        assertTrue(route("我们可以加个手电筒功能") is RouteDecision.Cloud)
        assertTrue(route("手电筒这个功能的实现方案是什么") is RouteDecision.Cloud)
    }

    @Test
    fun registrySupportsOnOffAndStatus() {
        assertTrue(LocalCapabilityRegistry.supports("flashlight", "on"))
        assertTrue(LocalCapabilityRegistry.supports("flashlight", "off"))
        assertTrue(LocalCapabilityRegistry.supports("flashlight", "status"))
        assertTrue(LocalCapabilityRegistry.isReadOnly("flashlight", "status"))
        assertFalse(LocalCapabilityRegistry.isHighRisk("flashlight", "on"))
    }
}

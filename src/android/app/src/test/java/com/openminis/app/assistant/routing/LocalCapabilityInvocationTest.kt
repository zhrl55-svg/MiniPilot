package com.openminis.app.assistant.routing

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-cloud-fastpath-catalog] The cloud → fast-path contract: validate the
 * model's call, refuse the wrong tool for the wrong capability, and keep the
 * destructive-only confirmation policy identical to the deterministic router.
 */
class LocalCapabilityInvocationTest {

    private fun invoke(vararg pairs: Pair<String, Any?>): LocalCapabilityPlan.Invoke {
        val args = JSONObject()
        for ((k, v) in pairs) args.put(k, v)
        val plan = LocalCapabilityInvocation.plan(args)
        assertTrue("expected Invoke, got $plan", plan is LocalCapabilityPlan.Invoke)
        return plan as LocalCapabilityPlan.Invoke
    }

    private fun reject(vararg pairs: Pair<String, Any?>): String {
        val args = JSONObject()
        for ((k, v) in pairs) args.put(k, v)
        val plan = LocalCapabilityInvocation.plan(args)
        assertTrue("expected Reject, got $plan", plan is LocalCapabilityPlan.Reject)
        return (plan as LocalCapabilityPlan.Reject).reason
    }

    @Test
    fun `read only action runs without confirmation`() {
        val decision = invoke(
            "capability" to "calendar",
            "action" to "list",
            "arguments" to JSONObject(),
        ).decision
        assertEquals("calendar", decision.capability)
        assertEquals("list", decision.action)
        assertFalse(decision.needsConfirmation)
        assertEquals(emptySet<String>(), decision.missingSlots)
    }

    @Test
    fun `low risk write runs without confirmation`() {
        val decision = invoke(
            "capability" to "calendar",
            "action" to "create",
            "arguments" to JSONObject()
                .put("title", "Dentist")
                .put("start", "2026-11-22T09:00:00"),
        ).decision
        assertFalse(decision.needsConfirmation)
        assertEquals("Dentist", decision.args.optString("title"))
    }

    @Test
    fun `high risk action still asks for confirmation`() {
        val delete = invoke(
            "capability" to "calendar",
            "action" to "delete",
            "arguments" to JSONObject().put("id_or_title", "Dentist"),
        ).decision
        assertTrue("calendar delete must confirm", delete.needsConfirmation)

        val settingsSet = invoke(
            "capability" to "settings",
            "action" to "set",
            "arguments" to JSONObject().put("target", "wifi").put("value", "off"),
        ).decision
        assertTrue("settings set must confirm", settingsSet.needsConfirmation)

        val noteDelete = invoke(
            "capability" to "note",
            "action" to "delete",
            "arguments" to JSONObject().put("id_or_query", "Project Alpha"),
        ).decision
        assertTrue("note delete must confirm", noteDelete.needsConfirmation)
    }

    @Test
    fun `cancelling a reminder is high risk`() {
        val decision = invoke(
            "capability" to "reminder",
            "action" to "cancel",
        ).decision
        assertTrue(decision.needsConfirmation)
    }

    @Test
    fun `unknown capability is rejected with the valid surface`() {
        val reason = reject("capability" to "teleport", "action" to "go")
        assertTrue(reason.contains("Unknown capability"))
        assertTrue(reason.contains("calendar"))
        assertTrue(reason.contains("app_launch"))
    }

    @Test
    fun `missing capability is rejected`() {
        val reason = reject("action" to "list")
        assertTrue(reason.contains("Missing `capability`"))
    }

    @Test
    fun `capability with a dedicated agent tool is redirected`() {
        val reason = reject(
            "capability" to "shell_execute",
            "action" to "run",
            "arguments" to JSONObject().put("command", "ls"),
        )
        assertTrue(reason.contains("dedicated agent tool"))
        assertTrue(reason.contains("call `shell_execute` directly"))
    }

    @Test
    fun `unknown action is rejected with the valid actions`() {
        val reason = reject("capability" to "calendar", "action" to "explode")
        assertTrue(reason.contains("Unknown action"))
        assertTrue(reason.contains("delete"))
        assertTrue(reason.contains("freebusy"))
    }

    @Test
    fun `missing required slot is rejected before execution`() {
        val reason = reject(
            "capability" to "calendar",
            "action" to "create",
            "arguments" to JSONObject().put("start", "2026-11-22T09:00:00"),
        )
        assertTrue(reason.contains("title"))
        assertTrue(reason.contains("required", ignoreCase = true))
    }

    @Test
    fun `invalid enum value is rejected`() {
        val reason = reject(
            "capability" to "media",
            "action" to "play",
            "arguments" to JSONObject().put("kind", "opera"),
        )
        assertTrue(reason.contains("kind"))
        assertTrue(reason.contains("song"))
    }

    @Test
    fun `valid enum value is accepted`() {
        val decision = invoke(
            "capability" to "media",
            "action" to "play",
            "arguments" to JSONObject().put("kind", "song").put("query", "晴天"),
        ).decision
        assertEquals("play", decision.action)
        assertEquals("song", decision.args.optString("kind"))
    }

    @Test
    fun `arguments accept a JSON string from the provider`() {
        val decision = invoke(
            "capability" to "alarm",
            "action" to "timer",
            "arguments" to "{\"duration\":300,\"label\":\"tea\"}",
        ).decision
        assertEquals(300L, decision.args.optLong("duration"))
        assertEquals("tea", decision.args.optString("label"))
    }

    @Test
    fun `malformed arguments string is rejected`() {
        val reason = reject(
            "capability" to "alarm",
            "action" to "timer",
            "arguments" to "duration=300",
        )
        assertTrue(reason.contains("JSON object"))
    }

    @Test
    fun `omitted arguments are fine for a slotless action`() {
        val decision = invoke(
            "capability" to "weather",
            "action" to "current",
        ).decision
        assertEquals(0, decision.args.length())
    }

    @Test
    fun `tool title becomes the confirmation summary`() {
        val decision = invoke(
            "capability" to "note",
            "action" to "delete",
            "arguments" to JSONObject().put("id_or_query", "shopping"),
            "tool_title" to "Delete shopping note",
        ).decision
        assertEquals("Delete shopping note", decision.summary)
        assertEquals("Notes · delete", decision.route.label)
    }

    @Test
    fun `summary falls back to the capability label`() {
        val decision = invoke("capability" to "flashlight", "action" to "on").decision
        assertEquals("Flashlight: on", decision.summary)
    }
}

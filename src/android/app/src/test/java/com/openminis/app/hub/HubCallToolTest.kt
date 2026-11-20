package com.openminis.app.hub

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-minis-hub] The arguments of a remote action.
 *
 * The schema says "args" is an object, but a model may hand it over as a
 * JSON string. The first version only read the object form, so the string
 * form became an empty args and the PC answered "shell needs
 * args.command" — a failure that looks like the remote side being broken.
 */
class HubCallToolTest {

    @Test
    fun `args as an object are used as they are`() {
        val args = JSONObject().put("args", JSONObject().put("command", "ls -la"))
        assertEquals("ls -la", HubCallTool.actionArgs(args).optString("command"))
    }

    @Test
    fun `args as a json string are parsed`() {
        val args = JSONObject().put("args", "{\"command\":\"echo hi\"}")
        assertEquals("echo hi", HubCallTool.actionArgs(args).optString("command"))
    }

    @Test
    fun `args that make no sense become an empty object`() {
        assertEquals(0, HubCallTool.actionArgs(JSONObject().put("args", "not json")).length())
        assertEquals(0, HubCallTool.actionArgs(JSONObject()).length())
    }

    /**
     * The PC side grew `mode.set` but this allowlist is checked on the phone
     * before anything leaves the device, so a missing entry means the call
     * dies with "不支持的动作" even though the hub is ready for it.
     */
    @Test
    fun `approval mode switching is offered`() {
        assertTrue("mode.set" in HubCallTool.ACTIONS)
    }

    /**
     * Same story for `notify`: the PC executor implements it and the policy
     * allows it, but the phone's allowlist rejected it with
     * "不支持的动作：notify" before the request ever left the device.
     */
    @Test
    fun `desktop notification is offered`() {
        assertTrue("notify" in HubCallTool.ACTIONS)
    }

    /**
     * [T-hub-receipt] A notify may carry a machine-readable handover. A
     * broken one is refused on the phone — before anything goes on the wire
     * — so the model learns why instead of the other side storing half-valid
     * data.
     */
    @Test
    fun `a notify without a receipt is fine`() {
        assertNull(HubCallTool.receiptProblem(JSONObject()))
        assertNull(HubCallTool.receiptProblem(JSONObject().put("title", "done")))
    }

    @Test
    fun `a valid receipt passes`() {
        val args = JSONObject().put(
            "receipt",
            JSONObject()
                .put("re", "m-20261120-003")
                .put("status", "done")
                .put("summary", "finished"),
        )
        assertNull(HubCallTool.receiptProblem(args))
    }

    @Test
    fun `a broken receipt is refused with a reason`() {
        val args = JSONObject().put(
            "receipt",
            JSONObject().put("re", "m-20261120-003").put("status", "finished"),
        )
        val problem = HubCallTool.receiptProblem(args)
        assertNotNull(problem)
        assertTrue(problem!!.contains("status"))
    }

    @Test
    fun `a receipt that is not an object is refused`() {
        val problem = HubCallTool.receiptProblem(JSONObject().put("receipt", "not an object"))
        assertNotNull(problem)
    }
}

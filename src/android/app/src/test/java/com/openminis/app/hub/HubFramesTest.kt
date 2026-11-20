package com.openminis.app.hub

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-minis-hub] The wire shapes.
 *
 * Every frame here once went out with the type as a KEY name instead of the
 * "type" FIELD — `put("hello", …)` — so the relay answered "hello expected"
 * and closed the socket after 15 seconds. The compiler was happy; only the
 * wire was wrong.
 */
class HubFramesTest {

    @Test
    fun `hello carries the type and the device`() {
        val hello = HubFrames.hello("phone-abcd1234", "ELS-AN00", "minis-android/0.1", listOf("info"))
        assertEquals("hello", hello.optString("type"))
        val device = hello.getJSONObject("device")
        assertEquals("phone-abcd1234", device.optString("id"))
        assertEquals("phone", device.optString("kind"))
        assertEquals("ELS-AN00", device.optString("name"))
        assertEquals(1, device.getJSONArray("actions").length())
    }

    @Test
    fun `call carries the id the target and the action`() {
        val call = HubFrames.call("phone-abcd1234:7:1a2b", "pc-ausa", "shell", JSONObject().put("command", "ls"))
        assertEquals("call", call.optString("type"))
        assertEquals("phone-abcd1234:7:1a2b", call.optString("id"))
        assertEquals("pc-ausa", call.optString("to"))
        assertEquals("shell", call.optString("action"))
        assertEquals("ls", call.optJSONObject("args").optString("command"))
    }

    @Test
    fun `ping is a type only frame`() {
        assertEquals("ping", HubFrames.ping().optString("type"))
    }

    /**
     * [H-004] The quotes in a shell command must survive the phone's own
     * encoding. A command that arrives as `echo \"a b\"` was mangled before
     * the wire, not on it — the PC side logs the same value so the two ends
     * can be compared.
     */
    @Test
    fun `a command keeps its quotes all the way into the frame`() {
        val command = "echo \"a b\""
        val frame = HubFrames.call("phone-abcd1234:7:1a2b", "pc-ausa", "shell", JSONObject().put("command", command))
        val parsed = JSONObject(frame.toString())
        assertEquals(command, parsed.getJSONObject("args").getString("command"))
        assertEquals("\"echo \\\"a b\\\"\"", debugRepr(command))
    }

    @Test
    fun `an error frame carries the code`() {
        val err = HubFrames.error("bad_frame", "unparseable frame")
        assertEquals("error", err.optString("type"))
        assertEquals("bad_frame", err.optString("code"))
    }

    /**
     * [T-hub-working] The progress frame carries the call's own id, so the
     * phone can drop one that belongs to a call it no longer waits for.
     */
    @Test
    fun `a working frame carries the call id and the message`() {
        val frame = HubFrames.working("phone-abcd1234:7:1a2b", "PC 已受理，正在执行…")
        assertEquals("working", frame.optString("type"))
        assertEquals("phone-abcd1234:7:1a2b", frame.optString("id"))
        assertEquals("PC 已受理，正在执行…", frame.optJSONObject("body").optString("message"))
    }

    @Test
    fun `a frame that is not an object does not parse`() {
        assertNull(HubFrames.parse("not json at all"))
    }

    @Test
    fun `the device table is read with its flags`() {
        val frame = JSONObject().put("devices", JSONArray().put(
            JSONObject()
                .put("id", "pc-ausa")
                .put("name", "AUSA")
                .put("kind", "computer")
                .put("online", true)
                .put("controllable", true)
                .put("actions", JSONArray().put("shell")),
        ))
        val devices = HubFrames.devices(frame)
        assertEquals(1, devices.size)
        assertEquals("pc-ausa", devices[0].id)
        assertTrue(devices[0].isComputer)
        assertTrue(devices[0].online)
        assertTrue(devices[0].actions.contains("shell"))
    }

    @Test
    fun `a phone is never a target`() {
        val frame = JSONObject().put("devices", JSONArray().put(
            JSONObject().put("id", "phone-x").put("kind", "phone").put("online", true),
        ))
        assertTrue(!HubFrames.devices(frame)[0].isComputer)
    }
}

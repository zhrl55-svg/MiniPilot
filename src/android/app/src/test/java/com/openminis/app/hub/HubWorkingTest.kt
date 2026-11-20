package com.openminis.app.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-hub-working] Which call a progress frame belongs to.
 *
 * The frame names the call. A frame whose id is not in flight is stale —
 * that call already got its result — and must be dropped, so an old hint
 * can never land on a newer call.
 */
class HubWorkingTest {

    @Test
    fun `a working frame names the call it is about`() {
        val frame = HubFrames.working("phone-1:7:ab", "PC 已受理，正在执行…")
        assertEquals("phone-1:7:ab", HubWorking.target(frame, setOf("phone-1:7:ab")))
    }

    @Test
    fun `a working frame for a call that is gone is dropped`() {
        val frame = HubFrames.working("phone-1:7:ab", "PC 已受理，正在执行…")
        assertNull(HubWorking.target(frame, setOf("phone-1:8:cd")))
    }

    @Test
    fun `a working frame without an id is dropped`() {
        assertNull(HubWorking.target(HubFrames.working("", "x"), setOf("phone-1:7:ab")))
    }

    @Test
    fun `the message falls back to the default`() {
        assertEquals(HubWorking.DEFAULT_MESSAGE, HubWorking.message(HubFrames.working("id", "")))
        assertEquals("受理了", HubWorking.message(HubFrames.working("id", "受理了")))
    }
}

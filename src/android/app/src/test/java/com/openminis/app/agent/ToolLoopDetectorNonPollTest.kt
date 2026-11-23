package com.openminis.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-loop-nonpoll-escalation] A non-poll tool that keeps returning the SAME
 * failing result for the SAME arguments must eventually be blocked, not merely
 * warned about (self-improve card 2026-11-22-91f2a642-1: hub_call with empty
 * args failed 14 times in a row).
 */
class ToolLoopDetectorNonPollTest {

    @Test
    fun `non-poll identical no-progress results escalate to CRITICAL at the critical threshold`() {
        val detector = ToolLoopDetector()
        // 19 identical failing results: below the critical bar (20).
        repeat(19) {
            detector.record("hub_call", mapOf("device" to "AUSA", "action" to "shell"),
                result = null, errorMessage = "shell needs args.command")
        }
        val below = detector.check("hub_call", mapOf("device" to "AUSA", "action" to "shell"))
        assertTrue("19 identical failures must not block yet, got ${below.level}",
            below.level != Level.CRITICAL)

        // One more identical failure reaches the bar.
        detector.record("hub_call", mapOf("device" to "AUSA", "action" to "shell"),
            result = null, errorMessage = "shell needs args.command")
        val atBar = detector.check("hub_call", mapOf("device" to "AUSA", "action" to "shell"))
        assertEquals(Level.CRITICAL, atBar.level)
        assertTrue("block message expected, got: ${atBar.message}",
            atBar.message?.contains("LOOP BLOCKED") == true)
    }

    @Test
    fun `varying results never escalate`() {
        val detector = ToolLoopDetector()
        repeat(25) { i ->
            detector.record("shell_execute", mapOf("command" to "stat /tmp/x"),
                result = "size=$i", errorMessage = null)
        }
        val pre = detector.check("shell_execute", mapOf("command" to "stat /tmp/x"))
        assertTrue("varying results must not trip the no-progress block, got ${pre.level}",
            pre.level != Level.CRITICAL)
    }

    @Test
    fun `poll tools keep their own threshold and message`() {
        val detector = ToolLoopDetector()
        repeat(20) {
            detector.record("command_status", mapOf("id" to "job-1"),
                result = "running", errorMessage = null)
        }
        val pre = detector.check("command_status", mapOf("id" to "job-1"))
        assertEquals(Level.CRITICAL, pre.level)
        assertTrue("poll path message expected, got: ${pre.message}",
            pre.message?.contains("identical no-progress results") == true)
    }
}

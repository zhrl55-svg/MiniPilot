package com.openminis.app.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-toolcall-args-integrity] Unit tests for the tool-call argument plumbing
 * behind self-improve card 2026-11-22-91f2a642-1 (args silently degraded to
 * `{}`, 14 identical retries) and 2026-11-22-3998a307-1 (parallel second call
 * corrupted).
 */
class ToolCallArgsTest {

    // ─── parseToolArgs ───────────────────────────────────────────────────────

    @Test
    fun `valid json parses with no invalid reason`() {
        val parsed = parseToolArgs("""{"command":"echo hi","timeout_ms":5}""")
        assertEquals("echo hi", parsed.args.optString("command"))
        assertNull(parsed.invalidReason)
    }

    @Test
    fun `blank text is an empty object and not an error`() {
        // A tool with no arguments is legitimate; only non-blank broken text
        // carries a reason.
        val parsed = parseToolArgs("   ")
        assertEquals(0, parsed.args.length())
        assertNull(parsed.invalidReason)
    }

    @Test
    fun `truncated json keeps the length in the reason`() {
        val raw = """{"command":"echo hi","timeout"""
        val parsed = parseToolArgs(raw)
        assertEquals(0, parsed.args.length())
        assertNotNull(parsed.invalidReason)
        assertTrue("reason should mention received length, got: ${parsed.invalidReason}",
            parsed.invalidReason!!.contains("${raw.length}"))
    }

    // ─── ToolCallBuffers (Anthropic content blocks) ──────────────────────────

    @Test
    fun `parallel block indices keep independent buffers`() {
        val buffers = ToolCallBuffers()
        buffers.start(0, "toolu_a", "file_read")
        buffers.start(1, "toolu_b", "hub_call")
        buffers.append(0, """{"path":""")
        buffers.append(1, """{"device":"AUSA","action":"shell","args":""")
        buffers.append(0, """"/tmp/x"}""")
        buffers.append(1, """{"command":"ls"}}""")

        val first = buffers.finish(0)
        val second = buffers.finish(1)
        assertNotNull(first)
        assertNotNull(second)
        assertEquals("toolu_a", first!!.id)
        assertEquals("""{"path":"/tmp/x"}""", first.buffer.toString())
        assertEquals("toolu_b", second!!.id)
        assertEquals(
            """{"device":"AUSA","action":"shell","args":{"command":"ls"}}""",
            second.buffer.toString(),
        )
    }

    @Test
    fun `unknown index falls back to the most recently started block`() {
        val buffers = ToolCallBuffers()
        buffers.start(3, "toolu_c", "shell_execute")
        buffers.append(-1, """{"command":"pwd"}""")
        val entry = buffers.finish(-1)
        assertNotNull(entry)
        assertEquals("toolu_c", entry!!.id)
        assertEquals("""{"command":"pwd"}""", entry.buffer.toString())
    }

    @Test
    fun `drainUnfinished returns only the blocks still open`() {
        val buffers = ToolCallBuffers()
        buffers.start(0, "toolu_done", "file_read")
        buffers.append(0, """{"path":"/a"}""")
        buffers.finish(0)
        buffers.start(1, "toolu_cut", "file_write")
        buffers.append(1, """{"path":"/b","content":"half""")

        val open = buffers.drainUnfinished()
        assertEquals(1, open.size)
        assertEquals("toolu_cut", open[0].id)
        assertEquals(0, buffers.size())
    }

    @Test
    fun `the same index restarted replaces the stale block`() {
        val buffers = ToolCallBuffers()
        buffers.start(0, "toolu_old", "file_read")
        buffers.append(0, """{"path":"/old"}""")
        buffers.start(0, "toolu_new", "file_write")
        buffers.append(0, """{"path":"/new","content":"x"}""")

        val entry = buffers.finish(0)
        assertNotNull(entry)
        assertEquals("toolu_new", entry!!.id)
        // The old block must not linger in the open set.
        assertEquals(0, buffers.size())
    }

    @Test
    fun `entry lookup by index returns the matching block`() {
        val buffers = ToolCallBuffers()
        val started = buffers.start(2, "toolu_e", "hub_call")
        assertSame(started, buffers.entry(2))
    }
}

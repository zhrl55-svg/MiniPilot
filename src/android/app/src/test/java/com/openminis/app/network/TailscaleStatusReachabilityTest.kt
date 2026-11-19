package com.openminis.app.network

import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-002] 「已注册」 and 「可达」 are different things.
 *
 * The netmap's `online` flag is the control plane's view: it went on saying
 * peer=asus was online for hours while every dial to it failed
 * (minis-2026-11-19.log). Reachability has to come from local evidence — a
 * WireGuard handshake recent enough, on a path that exists — or from an
 * actual dial, whose failure reason now reaches the UI.
 */
class TailscaleStatusReachabilityTest {

    private val now = 1_700_000_000_000L

    private fun iso(ms: Long): String = Instant.ofEpochMilli(ms).toString()

    private fun peerJson(): JSONObject = JSONObject()
        .put("hostName", "asus")
        .put("dnsName", "asus.example.ts.net.")
        .put("tailscaleIPs", JSONArray(listOf("100.64.0.2")))

    private fun status(peer: JSONObject): JSONObject = JSONObject()
        .put("running", true)
        .put("backendState", "Running")
        .put("peers", JSONArray().put(peer))

    private fun peer(json: JSONObject): TailscalePeer =
        TailscaleStatus.parse(status(json)).peers.single()

    @Test
    fun `a fresh handshake on a direct path is reachable`() {
        val p = peer(peerJson().put("curAddr", "192.168.2.32:41641").put("lastHandshake", iso(now - 60_000)))
        assertTrue(p.reachable(now))
        assertEquals("192.168.2.32:41641", p.curAddr)
        assertEquals(now - 60_000, p.lastHandshake)
    }

    @Test
    fun `a fresh handshake over a relay is reachable`() {
        val p = peer(peerJson().put("relay", "nyc").put("lastHandshake", iso(now - 60_000)))
        assertTrue(p.reachable(now))
        assertEquals("nyc", p.relay)
    }

    @Test
    fun `online without a handshake is only registered`() {
        val p = peer(peerJson().put("online", true))
        assertTrue(p.online)
        assertFalse(p.reachable(now))
    }

    @Test
    fun `a handshake older than five minutes is not reachable`() {
        val p = peer(
            peerJson()
                .put("curAddr", "192.168.2.32:41641")
                .put("lastHandshake", iso(now - TailscalePeer.REACHABLE_MS - 1_000)),
        )
        assertFalse(p.reachable(now))
    }

    @Test
    fun `a handshake without a path is not reachable`() {
        val p = peer(peerJson().put("lastHandshake", iso(now - 1_000)))
        assertFalse(p.reachable(now))
    }

    @Test
    fun `an unparsable handshake counts as never`() {
        val p = peer(peerJson().put("curAddr", "1.2.3.4:5").put("lastHandshake", "not-a-time"))
        assertEquals(0L, p.lastHandshake)
        assertFalse(p.reachable(now))
    }

    @Test
    fun `the last dial failure is parsed for the UI and absent when there is none`() {
        val st = TailscaleStatus.parse(
            JSONObject()
                .put("running", true)
                .put(
                    "lastDialError",
                    JSONObject().put("target", "asus:8443").put("error", "peer=asus offline").put("at", "2026-11-19T00:30:00+08:00"),
                ),
        )
        assertEquals("asus:8443", st.lastDialError?.target)
        assertEquals("peer=asus offline", st.lastDialError?.error)
        assertNull(TailscaleStatus.parse(JSONObject()).lastDialError)
    }

    @Test
    fun `the self-heal record is parsed`() {
        val st = TailscaleStatus.parse(
            JSONObject()
                .put("running", true)
                .put("autoRestarts", 2)
                .put("lastAutoRestart", "2026-11-19T08:12:56+08:00")
                .put("lastAutoRestartReason", "The MagicSock function ReceiveIPv4 is not running."),
        )
        assertEquals(2, st.autoRestarts)
        assertEquals("2026-11-19T08:12:56+08:00", st.lastAutoRestart)
        assertEquals("The MagicSock function ReceiveIPv4 is not running.", st.lastAutoRestartReason)
    }
}

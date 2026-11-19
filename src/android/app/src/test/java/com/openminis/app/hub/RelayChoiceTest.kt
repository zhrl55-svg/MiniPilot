package com.openminis.app.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [F-007] The relay list is a preference list: the first entry is the
 * preferred (direct) endpoint, the rest are alternatives.
 *
 * The bug: after failing over to an alternative, the client kept the
 * alternative — so when the PC came back, the phone never went home and
 * the PC side saw no `phone attached` for minutes.
 */
class RelayChoiceTest {

    @Test
    fun `a drop on the preferred endpoint falls back to an alternative`() {
        assertEquals(1, RelayChoice.nextIndex(current = 0, size = 2))
    }

    @Test
    fun `a drop on an alternative goes back to the preferred one`() {
        // The line that used to keep the phone on the fallback.
        assertEquals(0, RelayChoice.nextIndex(current = 1, size = 2))
    }

    @Test
    fun `one endpoint means one choice`() {
        assertEquals(0, RelayChoice.nextIndex(current = 0, size = 1))
        assertEquals(0, RelayChoice.nextIndex(current = 1, size = 1))
        assertEquals(0, RelayChoice.nextIndex(current = 0, size = 0))
    }

    @Test
    fun `the preferred relay is probed only from an alternative`() {
        assertTrue(RelayChoice.shouldProbe(currentIndex = 1, size = 2))
        assertFalse(RelayChoice.shouldProbe(currentIndex = 0, size = 2))
        assertFalse(RelayChoice.shouldProbe(currentIndex = 0, size = 1))
    }

    /**
     * [T-003] The probe used to run every 30 s forever, including at a PC
     * that had been off for hours (105 probes in one log, strictly 35 s
     * apart, all failures). Consecutive failures now double the interval up
     * to five minutes, and one answer restores it.
     */
    @Test
    fun `failed probes back off to the cap and a success resets it`() {
        var d = RelayChoice.PROBE_MS
        val seen = mutableListOf(d)
        repeat(6) {
            d = RelayChoice.nextProbeDelay(alive = false, current = d)
            seen += d
        }
        // 30s, 60s, 120s, 240s, 300s (cap), 300s, 300s
        assertEquals(listOf(30_000L, 60_000L, 120_000L, 240_000L, 300_000L, 300_000L, 300_000L), seen)
        assertEquals(RelayChoice.PROBE_MAX_MS, RelayChoice.nextProbeDelay(alive = false, current = 10_000_000L))
        assertEquals(RelayChoice.PROBE_MS, RelayChoice.nextProbeDelay(alive = true, current = RelayChoice.PROBE_MAX_MS))
    }
}

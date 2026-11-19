package com.openminis.app.hub

/**
 * [F-007] Which relay the phone talks to, and how it gets back.
 *
 * The bug this replaces: the client stayed on whichever relay answered
 * first. When the preferred (direct) endpoint came back after a restart,
 * the phone never went back to it — it sat on the fallback relay until
 * someone edited the phone's configuration by hand. The PC side saw no
 * `phone attached` for minutes, and every `hub_call` said the device was
 * not there.
 *
 * The list is a **preference list**, not a ring: the first entry is the
 * preferred endpoint (the PC's direct one), the rest are alternatives for
 * the same job.
 */
object RelayChoice {

    /** How long to wait between probes while the preferred relay answers. */
    const val PROBE_MS = 30_000L

    /**
     * [T-003] Cap of the failure backoff. A powered-off PC answers nothing,
     * and the probe used to run every 30 s at it for hours
     * (minis-2026-11-19.log: 105 probes, strictly 35 s apart, every one to a
     * machine that was off).
     */
    const val PROBE_MAX_MS = 5 * 60_000L

    /**
     * The endpoint to try after the link on [current] dropped.
     *
     * A drop on the preferred endpoint moves to the first alternative — the
     * phone must keep working while the PC is off. A drop on an alternative
     * goes back to the preferred one: the PC may simply have restarted.
     */
    fun nextIndex(current: Int, size: Int): Int = when {
        size <= 1 -> 0
        current == 0 -> 1
        else -> 0
    }

    /**
     * Whether the preferred relay is worth probing right now: only when we
     * are on an alternative and there is a preferred one to go back to.
     */
    fun shouldProbe(currentIndex: Int, size: Int): Boolean = size > 1 && currentIndex != 0

    /**
     * [T-003] The delay before the next probe: back to [PROBE_MS] once the
     * endpoint answers, doubling up to [PROBE_MAX_MS] while it does not, so a
     * dead PC costs one probe per five minutes instead of one every 30 s.
     */
    fun nextProbeDelay(alive: Boolean, current: Long): Long =
        if (alive) PROBE_MS else (current * 2).coerceAtMost(PROBE_MAX_MS)
}

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
}

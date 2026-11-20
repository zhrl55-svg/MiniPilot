package com.openminis.app.hub

import org.json.JSONObject

/**
 * [T-hub-working] Which call a progress frame is about.
 *
 * The PC sends a `working` frame the moment it accepts a call: the phone
 * waits for the result with a 15-minute ceiling, and without this frame
 * there is nothing on screen but a spinner.
 *
 * The frame names the call it belongs to. A frame whose id is not in flight
 * is stale — that call already got its result — so it is dropped, and an old
 * hint can never land on a newer call.
 */
internal object HubWorking {

    /** What the phone shows when the PC says "working" without wording. */
    const val DEFAULT_MESSAGE = "PC 已受理，正在执行…"

    /**
     * The call the frame is about, or null when that call is not in flight.
     *
     * @param inFlight the ids of the calls the phone is waiting for.
     */
    fun target(frame: JSONObject, inFlight: Set<String>): String? {
        val id = frame.optString("id")
        return if (id.isNotEmpty() && inFlight.contains(id)) id else null
    }

    /** The message the frame carries, or [DEFAULT_MESSAGE]. */
    fun message(frame: JSONObject): String =
        frame.optJSONObject("body")?.optString("message").orEmpty().ifBlank { DEFAULT_MESSAGE }
}

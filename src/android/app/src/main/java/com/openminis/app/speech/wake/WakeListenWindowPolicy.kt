package com.openminis.app.speech.wake

/**
 * The rules that decide whether a listening window may be renewed.
 *
 * ## The rule (owner, 2026-11-22)
 *
 * After the assistant has finished speaking, the conversation listens for
 * one window ([WakeWordSettings.voiceSleepWindowMs], 8 s by default). If the
 * owner's voiceprint has not passed by the time that window expires, the
 * microphone goes back to standby **immediately** — no extra grace, and
 * nothing is handed to the model. Music, a fan or a stranger talking is not
 * a request.
 *
 * A long uninterrupted sentence is still not cut: whenever the tail of the
 * buffered speech passes the voiceprint, the window is renewed for another
 * full window, so a request longer than the window keeps accumulating.
 *
 * "Could not judge" (the model is still loading, no template yet, the tail
 * is too short) is not "not the owner", so it gets a bounded grace — but
 * never an unbounded one.
 *
 * ## Why the flush-on-failure branch is gone
 *
 * 702e429 still handed the buffered segment over once when the voiceprint
 * failed ("长句不腰截") and then gave it a 20 s `FLUSH_GRACE_MS`. On the real
 * device the music playing in the room was flushed into the chat every time
 * that grace expired (log 2026-11-22 16:58:21 / 16:59:39 「唤醒命令进入当前
 * 会话」) and the microphone stayed open for 28–48 s. Long speech is kept
 * alive by the renewal branch above instead, so a failed verdict needs to
 * deliver nothing.
 *
 * ## Why this is a separate, pure object
 *
 * The decision is a pure function so the branches (owner / score too low /
 * could not judge / nothing to judge) can be unit-tested without an Android
 * service.
 */
internal object WakeListenWindowPolicy {

    /**
     * How much of the buffered segment is sent to the voiceprint.
     *
     * The speaker extractor needs at least 0.6 s of audio; 2 s gives it
     * several syllables of the current speaker without dragging in the
     * whole (possibly very long) utterance.
     */
    const val RENEW_TAIL_MS = 2_000

    /**
     * How many "could not judge" listening windows one phase may use.
     *
     * `verify()` returns null when the model is still warming up, the tail
     * is shorter than 0.6 s, or no template is enrolled yet. None of those
     * means "not the owner", so the window is not closed on the spot — but
     * it is not renewed forever either. Two bounded windows, then standby.
     */
    const val MAX_VOICEPRINT_GRACE = 2

    /**
     * Hard ceiling for one listening phase, from entering LISTENING.
     *
     * Independent of every other rule: if the voiceprint, the VAD and the
     * recogniser all fail to end the turn, this still does. A reply starts
     * a fresh phase, so a conversation with many turns is not limited by
     * this — only one long, uninterrupted listening stretch is.
     */
    const val MAX_LISTENING_MS = 120_000L

    /** What to do when the listening window has expired. */
    sealed class Decision {
        /** The owner's voiceprint passed: renew by the full window. */
        data class Renew(val score: Float) : Decision()

        /**
         * There was speech but the voiceprint could not judge it: keep
         * listening for one more bounded window. [attempt] is 1-based and
         * counts the graces used by this listening phase.
         */
        data class NullGrace(val attempt: Int) : Decision()

        /**
         * Nothing renews the window: back to standby. The buffered audio is
         * discarded — it is not a request from the owner.
         */
        object Sleep : Decision()
    }

    /**
     * Decide the fate of an expired listening window.
     *
     * @param hasSpeech whether the VAD has a segment buffered. A quiet
     *   microphone is not a "could not judge" case: there is nothing to
     *   judge, and the window must close as designed.
     * @param score the voiceprint similarity of the segment tail, or null
     *   when the check could not run.
     * @param threshold [WakeWordSettings.voiceprintThreshold], live.
     * @param graceUsed bounded null-verdict windows already spent by this
     *   listening phase.
     */
    fun decide(
        hasSpeech: Boolean,
        score: Float?,
        threshold: Float,
        graceUsed: Int,
    ): Decision {
        if (!hasSpeech) return Decision.Sleep
        if (score == null) {
            // Speech is there but the gate could not read it. Keep the
            // sentence alive for at most MAX_VOICEPRINT_GRACE windows.
            return if (graceUsed < MAX_VOICEPRINT_GRACE) {
                Decision.NullGrace(graceUsed + 1)
            } else {
                Decision.Sleep
            }
        }
        if (score >= threshold) return Decision.Renew(score)
        // Judged, and not the owner. This is the noise that used to renew
        // the window AND get flushed into the chat. Neither happens now.
        return Decision.Sleep
    }

    /** True when one listening phase has run longer than the hard ceiling. */
    fun listeningExpired(startedAt: Long, now: Long): Boolean =
        startedAt > 0L && now - startedAt >= MAX_LISTENING_MS
}

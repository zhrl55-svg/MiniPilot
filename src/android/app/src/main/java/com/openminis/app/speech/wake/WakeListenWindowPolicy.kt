package com.openminis.app.speech.wake

/**
 * The rules that decide whether a listening window may be renewed.
 *
 * ## Why this is a separate, pure object
 *
 * [WakeWordService] used to renew the window on "the VAD is in speech"
 * alone. Energy VAD answers "is the microphone louder than the room", not
 * "is the owner still talking", so a fan, a TV or traffic kept it in speech
 * for five minutes and the window renewed itself forever (device log
 * 2026-11-22: 138 次「正在说话」, window 30 s → 5 min, 峰值 32627 对阈值 260).
 *
 * The window is only renewed by a **voiceprint that passes**, judged once
 * on the tail of the buffered segment at the moment the window expires.
 * Everything else falls back to the original behaviour: hand a half-spoken
 * sentence over with a bounded grace, or go back to standby.
 *
 * The decision is a pure function so the four branches (owner / score too
 * low / could not judge / out of time) can be unit-tested without an
 * Android service.
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
     * How many "could not judge" grace windows one listening phase may use.
     *
     * `verify()` returns null when the model is still warming up, the tail
     * is shorter than 0.6 s, or no template is enrolled yet. None of those
     * means "not the owner", so the window is not closed on the spot — but
     * it is not renewed forever either. Two bounded graces, then standby.
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
         * There was speech but the voiceprint could not judge it: grant one
         * more bounded grace. [attempt] is 1-based and counts the graces used
         * by this listening phase.
         */
        data class NullGrace(val attempt: Int) : Decision()

        /** Speech did not pass the gate: hand the buffered half-sentence over. */
        object FlushGrace : Decision()

        /** Nothing renews the window: back to standby. */
        object Sleep : Decision()
    }

    /**
     * Decide the fate of an expired listening window.
     *
     * @param hasSpeech whether the VAD has a segment buffered — a quiet
     *   microphone is NOT a "could not judge" case: there is nothing to
     *   judge, and the window must close as designed.
     * @param score the voiceprint similarity of the segment tail, or null
     *   when the check could not run.
     * @param threshold [WakeWordSettings.voiceprintThreshold], live.
     * @param graceUsed bounded null-verdict graces already spent by this
     *   listening phase.
     * @param flush forces the buffered utterance out when (and only when)
     *   this decision needs it; returns true when a segment was handed over.
     */
    fun decide(
        hasSpeech: Boolean,
        score: Float?,
        threshold: Float,
        graceUsed: Int,
        flush: () -> Boolean,
    ): Decision {
        if (!hasSpeech) {
            // Nothing buffered: the old flush/sleep rule, unchanged.
            return if (flush()) Decision.FlushGrace else Decision.Sleep
        }
        if (score == null) {
            // Speech is there but the gate could not read it. Keep the
            // sentence alive for at most MAX_VOICEPRINT_GRACE graces.
            return if (graceUsed < MAX_VOICEPRINT_GRACE) {
                Decision.NullGrace(graceUsed + 1)
            } else {
                Decision.Sleep
            }
        }
        if (score >= threshold) return Decision.Renew(score)
        // Judged, and not the owner: this is the noise that used to renew
        // the window. Still hand the buffered audio over once — a long
        // sentence must not be thrown away — but never renew the window
        // itself.
        return if (flush()) Decision.FlushGrace else Decision.Sleep
    }

    /** True when one listening phase has run longer than the hard ceiling. */
    fun listeningExpired(startedAt: Long, now: Long): Boolean =
        startedAt > 0L && now - startedAt >= MAX_LISTENING_MS
}

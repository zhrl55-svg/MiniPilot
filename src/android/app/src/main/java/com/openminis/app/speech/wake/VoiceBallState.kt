package com.openminis.app.speech.wake

/**
 * The three looks of the floating voice ball.
 *
 * The pipeline has more phases than the user needs to see: [VoiceWakePhase.OFF],
 * `PAUSED`, `WAITING` and `ERROR` all mean "the assistant is asleep but the ball
 * is still there", so they share one animation; `WAKING` already means the
 * assistant is listening; only `REPLYING` speaks.
 */
enum class VoiceBallState {
    /** Dormant: microphone dim, slow breathing, no ripples. */
    SLEEP,

    /** Listening: microphone with a heartbeat plus an outgoing pulse. */
    LISTEN,

    /** Replying: speaker with sound waves going out. */
    SPEAK,
}

fun VoiceWakePhase.toBallState(): VoiceBallState = when (this) {
    VoiceWakePhase.OFF,
    VoiceWakePhase.PAUSED,
    VoiceWakePhase.WAITING,
    VoiceWakePhase.ERROR,
    -> VoiceBallState.SLEEP

    VoiceWakePhase.WAKING,
    VoiceWakePhase.LISTENING,
    -> VoiceBallState.LISTEN

    VoiceWakePhase.REPLYING -> VoiceBallState.SPEAK
}

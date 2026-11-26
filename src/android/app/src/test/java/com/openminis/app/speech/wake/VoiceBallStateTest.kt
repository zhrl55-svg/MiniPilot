package com.openminis.app.speech.wake

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The ball has exactly three looks. Every pipeline phase must land in one of
 * them — an unmapped phase would leave the ball frozen in whatever state it
 * had, which is how "waiting" used to look dead.
 */
class VoiceBallStateTest {

    @Test
    fun `every phase maps to a ball state`() {
        // Exhaustive on purpose: a new VoiceWakePhase must fail this test
        // until it is given a look.
        val expected = mapOf(
            VoiceWakePhase.OFF to VoiceBallState.SLEEP,
            VoiceWakePhase.PAUSED to VoiceBallState.SLEEP,
            VoiceWakePhase.WAITING to VoiceBallState.SLEEP,
            VoiceWakePhase.ERROR to VoiceBallState.SLEEP,
            VoiceWakePhase.WAKING to VoiceBallState.LISTEN,
            VoiceWakePhase.LISTENING to VoiceBallState.LISTEN,
            VoiceWakePhase.REPLYING to VoiceBallState.SPEAK,
        )
        assertEquals(VoiceWakePhase.entries.toSet(), expected.keys)
        for ((phase, state) in expected) {
            assertEquals("phase $phase", state, phase.toBallState())
        }
    }

    @Test
    fun `waiting for the wake word sleeps while a wake listens`() {
        assertEquals(VoiceBallState.SLEEP, VoiceWakePhase.WAITING.toBallState())
        assertEquals(VoiceBallState.LISTEN, VoiceWakePhase.LISTENING.toBallState())
        assertEquals(VoiceBallState.SPEAK, VoiceWakePhase.REPLYING.toBallState())
    }
}

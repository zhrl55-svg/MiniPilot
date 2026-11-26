package com.openminis.app.speech.wake

import com.openminis.app.logging.AppLogger
import kotlin.math.sqrt

/**
 * Energy VAD: adaptive noise floor + two thresholds, cutting continuous
 * audio into utterances.
 *
 * Ported from pilot's `EnergyVad`. Energy-based rather than silero VAD on
 * purpose: silero needs onnxruntime plus a 2MB model, and on a phone the
 * noise floor is steady while speech sits clearly above it - energy is
 * enough, with zero extra dependencies and zero extra latency.
 *
 * Two different kinds of "silence" must not be confused:
 *  - [minSilenceMs] (default 800 ms) decides "this sentence is over" and
    triggers transcription;
 *  - session-level sleep is a different thing and lives in
    [WakeWordService].
 *
 * ## [T-voice-wake-noise-floor-adapt] Why there are two floors
 *
 * The old code tracked ONE noise floor and only updated it while nobody was
 * talking. That froze the floor at the moment the user started speaking,
 * and the symptom was a hard deadlock: with a background above that frozen
 * threshold every frame counted as "voiced", the silence counter never
 * advanced, and the sentence was only ever cut by the 60 s safety valve.
 * The device log showed exactly that - floor frozen at 128 (threshold 332)
 * while the microphone sat between 464 and 2136 for half a minute.
 *
 * So the floor is now split:
 *
 *  - [noiseFloor] - the ambient level. Drives the **open** threshold, so it
 *    stays conservative (it only moves while nobody is talking) and a burst
 *    of noise cannot open a segment on its own.
 *  - [adaptiveFloor] - the floor that adapts **while the user is talking**.
 *    It is the quietest recent level, bounded by half the speaker's level so
 *    that it can never climb into the speech itself. It drives the **end**
 *    threshold, which is what decides "the speaker stopped".
 *
 * The end threshold is deliberately lower than the open threshold
 * (hysteresis): once the user is talking, a momentary dip below the open
 * threshold must not end the sentence.
 */
class WakeEnergyVad(
    private val onSpeechStart: () -> Unit,
    private val onSegment: (ShortArray) -> Unit,
    private val minSilenceMs: () -> Int = { DEFAULT_SILENCE_MS },
    /**
     * Longest utterance before it is cut even without a pause.
     *
     * This is a safety valve, NOT the endpointing rule: a request ends when
     * the speaker goes quiet for [minSilenceMs], no matter how long it runs.
     * The old 15 s default was short enough to chop a long dictation in half
     * ("长语音只进去前半段") even though the user never paused. The barge-in
     * listener still overrides this with a much shorter limit: while the
     * assistant talks there is no punctuation guarantee in the captured
     * audio, and a stop command must be recognised within a second or two.
     */
    private val maxSegmentMs: () -> Int = { MAX_SEGMENT_MS },
    /**
     * Diagnostics sink.
     *
     * The VAD decisions (opened / cut / dropped) have to be visible outside
     * logcat — on a real device the logcat buffer rotates away exactly when
     * one needs to know why a sentence was not picked up.
     */
    private val onEvent: ((String) -> Unit)? = null,
) {

    /**
     * Minimum room between two cut segments.
     *
     * The wake service pauses VAD frames while its own "我在" acknowledgement
     * is playing, so a segment cut just before the pause may be followed by
     * the user's request within a few hundred milliseconds. Without this gap
     * the two would be glued into one utterance: the short acknowledgement
     * would dominate the voiceprint score and the request would be rejected.
     */
    private var lastCutAtMs = -1L

    /** Ambient level. Drives the open threshold; only moves while idle. */
    private var noiseFloor = 120.0

    /**
     * Floor that adapts while the user is talking: the quietest recent
     * level, never above half the speaker's own level.
     */
    private var adaptiveFloor = 120.0

    /** EWMA of the level of the frames counted as voiced. */
    private var speechLevel = 0.0

    /** Recent frame levels, used for the quietest-recent-level estimate. */
    private val levelWindow = ArrayDeque<Double>()

    private var voicedFrames = 0
    private var silentFrames = 0
    private val preRoll = ArrayDeque<ShortArray>()
    private val segment = ArrayList<Short>()
    private var inSpeech = false

    /** Ambient level when the current segment opened — the noise reference. */
    private var floorAtOpen = 120.0

    /** Loudest frame of the current segment. */
    private var segmentPeak = 0.0

    /** Loudest frame since the last [reset] — diagnostics only. */
    @get:Synchronized
    var peakRms: Double = 0.0
        private set

    /** The energy threshold currently in use — diagnostics only. */
    @get:Synchronized
    val threshold: Double get() = maxOf(noiseFloor * 2.6, MIN_RMS)

    /** The adaptive noise floor — diagnostics only. */
    @get:Synchronized
    val floor: Double get() = noiseFloor

    /** RMS of the most recent frame — diagnostics only. */
    @get:Synchronized
    val lastRms: Double get() = lastRmsValue

    /** How many frames have been fed since the last [reset]. */
    @get:Synchronized
    val frameCount: Int get() = framesFed

    /** True while an utterance is being accumulated. */
    @get:Synchronized
    val isInSpeech: Boolean get() = inSpeech

    /** Length of the utterance being accumulated — diagnostics only. */
    @get:Synchronized
    val bufferedSegmentMs: Int
        get() = segment.size * 1000 / WakeAudioCapture.SAMPLE_RATE

    private var lastRmsValue = 0.0
    private var framesFed = 0

    @Synchronized
    fun reset() {
        voicedFrames = 0
        silentFrames = 0
        inSpeech = false
        segment.clear()
        preRoll.clear()
        levelWindow.clear()
        peakRms = 0.0
        lastRmsValue = 0.0
        framesFed = 0
        lastCutAtMs = -1L
        adaptiveFloor = noiseFloor
        speechLevel = 0.0
        floorAtOpen = noiseFloor
        segmentPeak = 0.0
    }

    /**
     * Drop any segment opened by the assistant's own acknowledgement and
     * start a minimum inter-segment gap.
     *
     * The wake service gates frames at its source while "我在" is playing,
     * but a segment may already be open from the very first frame before the
     * gate took effect. Calling this when the acknowledgement ends discards
     * that echo segment and keeps the next human syllable from being glued
     * to it. The noise floor is kept so the room estimate stays accurate.
     */
    @Synchronized
    fun pause() {
        // Count the pause itself in the inter-segment gap: the acknowledgement
        // ends with the same echo energy that just played, and the very next
        // human syllable must not be attached to it.
        lastCutAtMs = framesFed.toLong()
        inSpeech = false
        voicedFrames = 0
        silentFrames = 0
        segment.clear()
        preRoll.clear()
        levelWindow.clear()
        adaptiveFloor = noiseFloor
    }

    @Synchronized
    fun feed(frame: ShortArray, len: Int) {
        val rms = rms(frame, len)
        lastRmsValue = rms
        framesFed++
        if (rms > peakRms) peakRms = rms
        levelWindow.addLast(rms)
        while (levelWindow.size > WINDOW_FRAMES) levelWindow.removeFirst()

        // A cut made moments ago (e.g. the acknowledgement, or a sentence
        // ended by a pause) must not be glued to the next utterance just
        // because the new speaker starts before minSilenceMs has passed.
        if (lastCutAtMs >= 0 && framesFed - lastCutAtMs < MIN_GAP_FRAMES) return

        // While nobody is talking the noise floor tracks slowly: it rises
        // slowly and falls fast, so a single cough cannot drag it away.
        if (!inSpeech) {
            noiseFloor = if (rms < noiseFloor) {
                noiseFloor * 0.90 + rms * 0.10
            } else {
                noiseFloor * 0.995 + rms * 0.005
            }.coerceIn(40.0, 3000.0)
        }

        val threshold = maxOf(noiseFloor * 2.6, MIN_RMS)

        if (!inSpeech) {
            preRoll.addLast(frame.copyOf(len))
            while (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()

            if (rms > threshold) {
                voicedFrames++
                if (voicedFrames >= 3) {
                    inSpeech = true
                    silentFrames = 0
                    segment.clear()
                    // Keep the 300 ms before the first sound, so the first
                    // syllable is not clipped.
                    preRoll.forEach { segment.addAll(it.toList()) }
                    preRoll.clear()
                    floorAtOpen = noiseFloor
                    segmentPeak = rms
                    speechLevel = rms
                    // Start from the ambient level: the quietest recent
                    // frame is still the pre-roll noise at this point.
                    adaptiveFloor = noiseFloor
                    onEvent?.invoke(
                        "VAD 开口 rms=${"%.0f".format(rms)} 阈值=${"%.0f".format(threshold)} " +
                            "底噪=${"%.0f".format(noiseFloor)}",
                    )
                    onSpeechStart()
                }
            } else {
                voicedFrames = 0
            }
            return
        }

        segment.addAll(frame.take(len))
        if (rms > segmentPeak) segmentPeak = rms

        // [T-voice-wake-noise-floor-adapt] The floor adapts while the user
        // is talking: it follows the quietest recent level, but never climbs
        // past half the speaker's own level — otherwise a long sentence
        // would raise the floor into the speech and cut itself in half.
        val floorTarget = minOf(
            levelWindow.min(),
            if (speechLevel > 0.0) speechLevel * 0.5 else Double.MAX_VALUE,
        )
        adaptiveFloor = if (floorTarget > adaptiveFloor) {
            adaptiveFloor + (floorTarget - adaptiveFloor) * 0.05
        } else {
            adaptiveFloor + (floorTarget - adaptiveFloor) * 0.20
        }

        // Hysteresis: the end threshold is lower than the open threshold, so
        // a dip below it inside a sentence does not end the sentence.
        val endThreshold = maxOf(adaptiveFloor * 1.3, MIN_RMS)

        if (rms > endThreshold) {
            silentFrames = 0
            speechLevel = if (speechLevel <= 0.0) {
                rms
            } else {
                speechLevel * 0.95 + rms * 0.05
            }
        } else {
            silentFrames++
        }

        val silentMs = silentFrames * WakeAudioCapture.FRAME_MS
        val totalMs = segment.size * 1000 / WakeAudioCapture.SAMPLE_RATE
        val cutBySilence = silentMs >= minSilenceMs()
        val cutByLimit = totalMs >= maxSegmentMs()
        if (!cutBySilence && !cutByLimit) return

        inSpeech = false
        voicedFrames = 0
        silentFrames = 0
        lastCutAtMs = framesFed.toLong()
        val pcm = segment.toShortArray()
        segment.clear()
        commitSegment(pcm, if (cutBySilence) "静音" else "上限")
    }

    /**
     * Snapshot of the tail of the segment being accumulated.
     *
     * The listening window uses this to run the voiceprint once, at the
     * moment the window would otherwise expire: the energy VAD stays "in
     * speech" on a noisy room forever, and only the owner's voice may renew
     * the window. Returns null while no utterance is open — there is nothing
     * to judge, and the caller must not treat "no audio" as "not the owner".
     *
     * Detached copy on purpose: the capture thread keeps appending to
     * [segment] while the extractor runs on another thread.
     */
    @Synchronized
    fun currentSegmentTailPcm(tailMs: Int): ShortArray? {
        if (!inSpeech || segment.isEmpty()) return null
        val ms = segment.size * 1000 / WakeAudioCapture.SAMPLE_RATE
        // A short utterance is all owner's voice; using only its 2 s tail
        // can end up judging a pause or the assistant's echo. Long segments
        // still use the tail so a noisy room cannot dilute the recent voice.
        if (ms <= WHOLE_SEGMENT_VERIFY_MAX_MS) return segment.toShortArray()
        val want = (tailMs.coerceAtLeast(0).toLong() * WakeAudioCapture.SAMPLE_RATE / 1000).toInt()
        if (want <= 0) return null
        val from = (segment.size - want).coerceAtLeast(0)
        return ShortArray(segment.size - from) { segment[from + it] }
    }

    /**
     * The newest [tailMs] of the buffered segment, ALWAYS only the tail.
     *
     * Unlike [currentSegmentTailPcm] this never returns the whole segment
     * for short utterances. The owner-pause probe needs it: judging the
     * whole (still owner-containing) segment keeps passing long after the
     * owner stopped, so a short request in road noise was only released
     * once the segment had grown past 8 s.
     */
    fun currentSegmentRecentPcm(tailMs: Int): ShortArray? {
        if (!inSpeech || segment.isEmpty()) return null
        val want = (tailMs.coerceAtLeast(0).toLong() * WakeAudioCapture.SAMPLE_RATE / 1000).toInt()
        if (want <= 0) return null
        val from = (segment.size - want).coerceAtLeast(0)
        return ShortArray(segment.size - from) { segment[from + it] }
    }

    /**
     * Force the current utterance out (used when the idle window closes).
     *
     * Flush 走与 [feed] 相同的质量判定：过短或噪声片段照丢，不因为窗口
     * 到点就绕过检查。
     *
     * @return true when there was an unfinished utterance that passed the
     *   acceptance checks and was handed over. The listening window has a
     *   hard deadline, and a user who speaks without a 800 ms pause would
     *   otherwise have their sentence thrown away with the segment — the
     *   window ends mid-utterance.
     */
    @Synchronized
    fun flush(): Boolean {
        if (!inSpeech) return false
        inSpeech = false
        val pcm = segment.toShortArray()
        segment.clear()
        if (pcm.isEmpty()) return false
        return commitSegment(pcm, "冲刷")
    }

    /**
     * Apply the same acceptance checks as a [feed] cut before the segment
     * reaches [onSegment], then reset the per-segment state either way.
     *
     * @return true when the segment was handed to the recogniser.
     */
    private fun commitSegment(pcm: ShortArray, reason: String): Boolean {
        // Too short is noise (a door, clothes rustling) - drop it.
        val speechMs = pcm.size * 1000 / WakeAudioCapture.SAMPLE_RATE
        val accepted = if (speechMs < MIN_SPEECH_MS) {
            AppLogger.info(TAG, "VAD 丢弃过短片段 ${speechMs}ms")
            onEvent?.invoke("VAD 丢弃过短片段 ${speechMs}ms 峰值=${"%.0f".format(peakRms)}")
            false
        } else if (segmentPeak < maxOf(MIN_RMS * 1.5, floorAtOpen * 2.0)) {
            // [T-voice-wake-noise-floor-adapt] The segment never rose above
            // the background: it is noise that happened to be loud enough to
            // open the segment, not a sentence. Sending it to the recogniser
            // would only produce garbage.
            AppLogger.info(TAG, "VAD 丢弃噪声片段 ${speechMs}ms 峰值=${"%.0f".format(segmentPeak)}")
            onEvent?.invoke(
                "VAD 丢弃噪声片段 ${speechMs}ms 峰值=${"%.0f".format(segmentPeak)} " +
                    "底噪=${"%.0f".format(floorAtOpen)}",
            )
            false
        } else {
            AppLogger.info(TAG, "VAD 切出一句 ${speechMs}ms")
            onEvent?.invoke(
                "VAD 切句 ${speechMs}ms 原因=$reason 峰值=${"%.0f".format(segmentPeak)}",
            )
            onSegment(pcm)
            true
        }

        // The next segment starts from the ambient level again.
        adaptiveFloor = noiseFloor
        speechLevel = 0.0
        segmentPeak = 0.0
        floorAtOpen = noiseFloor
        return accepted
    }

    private fun rms(buf: ShortArray, len: Int): Double {
        if (len <= 0) return 0.0
        var sum = 0.0
        for (i in 0 until len) {
            val v = buf[i].toDouble()
            sum += v * v
        }
        return sqrt(sum / len)
    }

    companion object {
        private const val TAG = "VoiceWake"
        private const val MIN_RMS = 260.0
        private const val DEFAULT_SILENCE_MS = 600
        private const val MIN_SPEECH_MS = 300
        private const val MAX_SEGMENT_MS = 60_000
        private const val PRE_ROLL_FRAMES = 15 // 300ms

        /**
         * Ignore this long after a cut before opening a new segment.
         *
         * A VAD cut already consumed [minSilenceMs] of quiet; the extra gap
         * covers the acknowledgement / request boundary where the assistant
         * stops speaking and the user starts immediately. 100 ms at 20 ms
         * per frame — short enough not to eat the first syllable.
         */
        private const val MIN_GAP_FRAMES = 5

        /**
         * Segments no longer than this are verified in full at window
         * expiry. Longer ones still use the recent tail only.
         */
        private const val WHOLE_SEGMENT_VERIFY_MAX_MS = 8_000

        /**
         * How many recent frames the "quietest recent level" looks at.
         *
         * 1.5 s at 20 ms per frame: long enough that the gaps between words
         * are covered, short enough that the estimate follows the room.
         */
        private const val WINDOW_FRAMES = 75
    }
}

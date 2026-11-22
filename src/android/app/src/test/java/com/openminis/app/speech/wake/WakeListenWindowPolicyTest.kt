package com.openminis.app.speech.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-voice-window-voiceprint] 聆听窗口只能被「通过声纹的声音」续期。
 *
 * 真机故障 2026-11-22：噪音让能量 VAD 一直判「正在说话」，窗口从 30 s
 * 无限续到 5 分钟（VAD 判说话 138 次，峰值 32627 / 满量程 32767，阈值
 * 仅 260）。e2c0667 之前窗口只在声纹通过、走完一轮回复后才续期；这次
 * 把声纹判据加回窗口到点的决策，同时保留「长句不拦腰截断」。
 */
class WakeListenWindowPolicyTest {

    private val threshold = 0.30f

    // ── WakeEnergyVad.currentSegmentTailPcm ────────────────────────────

    @Test
    fun `the tail accessor returns the newest samples of the buffered segment`() {
        val v = WakeEnergyVad(onSpeechStart = {}, onSegment = {}, maxSegmentMs = { 60_000 })
        repeat(3) { v.feed(speech, speech.size) } // 开口
        repeat(25) { v.feed(speech, speech.size) } // 500ms 语音

        val tail = v.currentSegmentTailPcm(200)
        assertNotNull("说话中的片段必须能取到尾部", tail)
        assertEquals(
            "200ms @ 16kHz",
            WakeAudioCapture.SAMPLE_RATE / 1000 * 200,
            tail!!.size,
        )
        assertTrue("尾部取的是真实缓冲，不是静音占位", tail.all { it.toInt() == 3000 })
    }

    @Test
    fun `the tail accessor returns null when nobody is speaking`() {
        val v = WakeEnergyVad(onSpeechStart = {}, onSegment = {}, maxSegmentMs = { 60_000 })
        assertNull("还没有开口", v.currentSegmentTailPcm(2_000))
        repeat(25) { v.feed(silence, silence.size) }
        assertNull("一直静音", v.currentSegmentTailPcm(2_000))
        assertFalse(v.isInSpeech)
    }

    // ── 四种续期决策 ───────────────────────────────────────────────────

    /** 声纹通过：续期，且不冲刷句子（长句继续攒）。 */
    @Test
    fun `a passing voiceprint renews the window`() {
        var flushed = false
        val d = WakeListenWindowPolicy.decide(
            hasSpeech = true, score = 0.42f, threshold = threshold, graceUsed = 0,
            flush = { flushed = true; true },
        )
        assertTrue(d is WakeListenWindowPolicy.Decision.Renew)
        assertEquals(0.42f, (d as WakeListenWindowPolicy.Decision.Renew).score, 0.0001f)
        assertFalse("续期不需要冲刷，长句不能被截断", flushed)
    }

    /** 分数不足（噪音）：不续期；句子交出去一次，交不出去就休眠。 */
    @Test
    fun `a score below the threshold never renews the window`() {
        var flushes = 0
        val flushable = WakeListenWindowPolicy.decide(
            hasSpeech = true, score = 0.05f, threshold = threshold, graceUsed = 0,
            flush = { flushes++; true },
        )
        assertEquals(WakeListenWindowPolicy.Decision.FlushGrace, flushable)
        assertEquals(1, flushes)

        val empty = WakeListenWindowPolicy.decide(
            hasSpeech = true, score = 0.05f, threshold = threshold, graceUsed = 0,
            flush = { false },
        )
        assertEquals(WakeListenWindowPolicy.Decision.Sleep, empty)
    }

    /** verify 返回 null：最多两次有限宽限，然后休眠（不能无限续期）。 */
    @Test
    fun `an unjudgeable voiceprint gets at most two bounded graces`() {
        val first = WakeListenWindowPolicy.decide(
            hasSpeech = true, score = null, threshold = threshold, graceUsed = 0,
            flush = { error("null 分支不应冲刷") },
        )
        assertEquals(WakeListenWindowPolicy.Decision.NullGrace(1), first)

        val second = WakeListenWindowPolicy.decide(
            hasSpeech = true, score = null, threshold = threshold, graceUsed = 1,
            flush = { error("null 分支不应冲刷") },
        )
        assertEquals(WakeListenWindowPolicy.Decision.NullGrace(2), second)

        val third = WakeListenWindowPolicy.decide(
            hasSpeech = true, score = null, threshold = threshold, graceUsed = 2,
            flush = { error("null 分支用尽后直接休眠") },
        )
        assertEquals(WakeListenWindowPolicy.Decision.Sleep, third)
    }

    /** 没人在说话：不是「判不了」，就是原设计的安静窗口到点。 */
    @Test
    fun `a quiet microphone closes the window without grace`() {
        val d = WakeListenWindowPolicy.decide(
            hasSpeech = false, score = null, threshold = threshold, graceUsed = 0,
            flush = { false },
        )
        assertEquals(WakeListenWindowPolicy.Decision.Sleep, d)
    }

    /** 总时长硬上限：从进入 LISTENING 起算 120s，谁说什么都没用。 */
    @Test
    fun `one listening phase cannot outlive the hard ceiling`() {
        val start = 1_000L
        assertFalse(WakeListenWindowPolicy.listeningExpired(start, start + 119_999L))
        assertTrue(WakeListenWindowPolicy.listeningExpired(start, start + 120_000L))
        assertTrue(WakeListenWindowPolicy.listeningExpired(start, start + 300_000L))
        assertFalse("未进入聆听时不算超时", WakeListenWindowPolicy.listeningExpired(0L, 10_000_000L))
    }

    private companion object {
        const val FRAME_SAMPLES = WakeAudioCapture.SAMPLE_RATE / 1000 * WakeAudioCapture.FRAME_MS
        val speech = ShortArray(FRAME_SAMPLES) { 3000 }
        val silence = ShortArray(FRAME_SAMPLES)
    }
}

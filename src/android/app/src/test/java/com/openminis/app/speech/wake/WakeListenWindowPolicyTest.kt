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
 * [T-voice-wake-sleep-on-fail] 用户 2026-11-22 的真机要求：播报说完后，
 * 一个聆听窗口内没有「本人（声纹匹配）在说话」就立刻回待命 —— 不再给
 * 20 秒宽限，也不把缓冲里的背景音/音乐当成一句话交给模型；同时本人连续
 * 说话超过一个窗口时不能被截断（窗口按声纹通过续期）。
 *
 * 真机故障：16:57–16:59 播报后仍聆听 28–48 秒，VAD 把本机扬声器的轻音乐
 * 判成「正在说话」，每次 20 秒宽限到点就把缓冲音频交成一次「唤醒命令进入
 * 当前会话」。
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
        assertEquals("没有片段时缓冲时长为 0", 0, v.bufferedSegmentMs)
    }

    @Test
    fun `the buffered segment length is reported for the drop diagnostic`() {
        val v = WakeEnergyVad(onSpeechStart = {}, onSegment = {}, maxSegmentMs = { 60_000 })
        repeat(3) { v.feed(speech, speech.size) } // 开口（含 300ms 预滚）
        repeat(25) { v.feed(speech, speech.size) }
        assertTrue("缓冲里必须有一段可丢弃的音频", v.bufferedSegmentMs >= 500)
    }

    // ── 续期决策：三条验收分支 + 安静/硬上限 ───────────────────────────

    /** ① 声纹通过：续期一个完整窗口，且不做任何交付（长句继续攒）。 */
    @Test
    fun `a passing voiceprint renews the window`() {
        val d = WakeListenWindowPolicy.decide(
            hasSpeech = true, score = 0.42f, threshold = threshold, graceUsed = 0,
        )
        assertTrue(d is WakeListenWindowPolicy.Decision.Renew)
        assertEquals(0.42f, (d as WakeListenWindowPolicy.Decision.Renew).score, 0.0001f)
    }

    /** 阈值边界向上：等于阈值也算本人。 */
    @Test
    fun `a score exactly at the threshold renews the window`() {
        val d = WakeListenWindowPolicy.decide(
            hasSpeech = true, score = threshold, threshold = threshold, graceUsed = 0,
        )
        assertTrue(d is WakeListenWindowPolicy.Decision.Renew)
    }

    /** ② 声纹不通过（噪音/音乐/别人说话）：立即休眠，不续期、不交付。 */
    @Test
    fun `a score below the threshold sleeps immediately`() {
        val d = WakeListenWindowPolicy.decide(
            hasSpeech = true, score = 0.05f, threshold = threshold, graceUsed = 0,
        )
        assertEquals(WakeListenWindowPolicy.Decision.Sleep, d)
    }

    /** 即使已经用过宽限，判过且不是本人也直接休眠（宽限只给「判不了」）。 */
    @Test
    fun `a judged non-owner never gets a grace window`() {
        val d = WakeListenWindowPolicy.decide(
            hasSpeech = true, score = 0.05f, threshold = threshold, graceUsed = 1,
        )
        assertEquals(WakeListenWindowPolicy.Decision.Sleep, d)
    }

    /** ③ verify 返回 null：最多两次有限宽限窗口，然后休眠（不能无限续期）。 */
    @Test
    fun `an unjudgeable voiceprint gets at most two bounded graces`() {
        val first = WakeListenWindowPolicy.decide(
            hasSpeech = true, score = null, threshold = threshold, graceUsed = 0,
        )
        assertEquals(WakeListenWindowPolicy.Decision.NullGrace(1), first)

        val second = WakeListenWindowPolicy.decide(
            hasSpeech = true, score = null, threshold = threshold, graceUsed = 1,
        )
        assertEquals(WakeListenWindowPolicy.Decision.NullGrace(2), second)

        val third = WakeListenWindowPolicy.decide(
            hasSpeech = true, score = null, threshold = threshold, graceUsed = 2,
        )
        assertEquals("宽限用尽后必须休眠，不能无限续期", WakeListenWindowPolicy.Decision.Sleep, third)
    }

    /** 没人在说话：不是「判不了」，就是原设计的安静窗口到点。 */
    @Test
    fun `a quiet microphone closes the window without grace`() {
        val d = WakeListenWindowPolicy.decide(
            hasSpeech = false, score = null, threshold = threshold, graceUsed = 0,
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

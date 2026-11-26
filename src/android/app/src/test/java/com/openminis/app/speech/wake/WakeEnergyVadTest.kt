package com.openminis.app.speech.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语音请求的断句规则：由静音时长决定结束，而不是固定时长。
 *
 * 真机反馈「长语音只进去前半段」：旧规则 600ms 静音就切句、15s 强制切段，
 * 长听写被切成多段（每段重新过声纹，被拒的那段直接丢），唤醒对话里
 * 8s 窗口还会把没说完的句子提前送走。
 *
 * [T-voice-wake-noise-floor-adapt] 另一个真机反馈：「唤醒后只要声纹静音
 * 超过1秒就开始识别并回复，不要憋到60秒」。根因是噪声底子只在没人说话
 * 时更新，开口后被冻结在开口那一瞬间的值；背景音一旦高过这个冻结值，
 * 静音计数就永远归零，句子只能等 60s 上限才切。
 */
class WakeEnergyVadTest {

    private val speech = ShortArray(FRAME_SAMPLES) { 3000 }
    private val silence = ShortArray(FRAME_SAMPLES)

    private fun vad(
        minSilenceMs: Int,
        maxSegmentMs: Int,
        onSegment: (ShortArray) -> Unit,
    ) = WakeEnergyVad(
        onSpeechStart = {},
        onSegment = onSegment,
        minSilenceMs = { minSilenceMs },
        maxSegmentMs = { maxSegmentMs },
    )

    @Test
    fun `a natural pause shorter than the silence window keeps the sentence open`() {
        val segments = mutableListOf<ShortArray>()
        val v = vad(1_200, 60_000) { segments += it }
        repeat(3) { v.feed(speech, speech.size) } // 开口
        repeat(25) { v.feed(speech, speech.size) } // 560ms 语音
        repeat(29) { v.feed(silence, silence.size) } // 580ms 句中停顿
        assertEquals("0.58s 的停顿不应断句", 0, segments.size)

        repeat(40) { v.feed(silence, silence.size) } // 累计 1.38s 静音
        assertEquals(1, segments.size)
    }

    @Test
    fun `continuous speech is not cut at the old fifteen second limit`() {
        val segments = mutableListOf<ShortArray>()
        val v = vad(1_200, 60_000) { segments += it }
        repeat(3 + 750) { v.feed(speech, speech.size) } // 约 15s 连续说话
        assertEquals(0, segments.size)
    }

    @Test
    fun `the maximum segment length is only a safety valve`() {
        val segments = mutableListOf<ShortArray>()
        val v = vad(1_200, 20_000) { segments += it }
        repeat(3 + 1_000) { v.feed(speech, speech.size) } // 约 20s 不停顿
        assertEquals(1, segments.size)
        assertTrue(segments[0].size * 1000 / WakeAudioCapture.SAMPLE_RATE >= 19_000)
    }

    // ── [T-voice-wake-noise-floor-adapt] 1 秒静音切句 ──────────────────

    @Test
    fun `one second of silence ends the request`() {
        val segments = mutableListOf<ShortArray>()
        val v = vad(1_000, 60_000) { segments += it }
        repeat(3) { v.feed(speech, speech.size) } // 开口
        repeat(10) { v.feed(speech, speech.size) } // 200ms 语音
        repeat(49) { v.feed(silence, silence.size) } // 980ms 静音
        assertEquals("0.98s 的静音还没到 1s，不应断句", 0, segments.size)

        repeat(2) { v.feed(silence, silence.size) } // 累计 1.02s 静音
        assertEquals(1, segments.size)
    }

    /**
     * 真机上的死锁：背景音一直高于「开口瞬间冻结的噪声底子」，静音计数
     * 永远归零，句子只能等 60s 上限才切（真机日志：底噪冻在 128、阈值
     * 332，而麦克风一直在 464~2136）。
     *
     * 修复后噪声底子在说话期间也能自适应抬升，1 秒静音必须能切句。
     */
    @Test
    fun `background noise above the frozen floor still ends the request in one second`() {
        val segments = mutableListOf<ShortArray>()
        val v = vad(1_000, 60_000) { segments += it }
        // 安静底噪下开口
        repeat(3) { v.feed(speech, speech.size) }
        // 背景音（rms 800）持续高于旧的冻结阈值（底噪 120 → 阈值 312）
        val background = ShortArray(FRAME_SAMPLES) { 800 }
        repeat(20) { v.feed(background, background.size) }
        repeat(60) { v.feed(background, background.size) } // 再 1.2s 背景音
        assertEquals("背景音持续时，1 秒静音后必须切句", 1, segments.size)
    }

    /**
     * 自适应不能把噪声底子抬进用户自己的声音里 —— 否则长句会被自己切碎
     * （真机反馈「长语音只进去前半段」的老问题）。
     */
    @Test
    fun `the adaptive floor never climbs into the speech itself`() {
        val segments = mutableListOf<ShortArray>()
        val v = vad(1_000, 60_000) { segments += it }
        repeat(3) { v.feed(speech, speech.size) } // 开口
        repeat(3 + 750) { v.feed(speech, speech.size) } // 约 15s 连续说话
        assertEquals("连续说话不应被自适应底子切碎", 0, segments.size)
    }

    /**
     * 纯噪声（响度够高能开口，但整体没有语音的动态）不该被当成一句话送进
     * 识别器 —— 那只会产生一段乱码。
     */
    @Test
    fun `a steady noise burst is dropped instead of transcribed`() {
        val segments = mutableListOf<ShortArray>()
        val v = vad(1_000, 60_000) { segments += it }
        val noise = ShortArray(FRAME_SAMPLES) { 900 }
        repeat(3) { v.feed(noise, noise.size) } // 开口
        repeat(100) { v.feed(noise, noise.size) } // 2s 稳定噪声
        assertEquals("稳定噪声不应被切成句子", 0, segments.size)
    }

    /**
     * 反过来：真实语音（峰值远高于底噪）必须照常切出来。
     */
    @Test
    fun `real speech over a noisy floor is still transcribed`() {
        val segments = mutableListOf<ShortArray>()
        val v = vad(1_000, 60_000) { segments += it }
        val background = ShortArray(FRAME_SAMPLES) { 800 }
        repeat(30) { v.feed(background, background.size) } // 先让底噪升到背景水平
        repeat(3) { v.feed(speech, speech.size) } // 开口
        repeat(20) { v.feed(speech, speech.size) } // 400ms 语音
        repeat(60) { v.feed(background, background.size) } // 1.2s 停顿
        assertEquals(1, segments.size)
    }

    // ── 唤醒确认音不得混进请求句 ────────────────────────────────────

    /**
     * 真机日志 2026-11-26：唤醒声纹 0.55 通过，但 VAD 同秒就开口（rms 5832
     * 是「我在」），请求句声纹只有 0.10。确认音播放期间暂停喂帧后，切出的
     * 片段只能包含确认音之后的人声。
     */
    @Test
    fun `frames fed while paused do not contaminate the request segment`() {
        val segments = mutableListOf<ShortArray>()
        val v = vad(1_000, 60_000) { segments += it }
        val ack = ShortArray(FRAME_SAMPLES) { 6_000 }
        val quiet = ShortArray(FRAME_SAMPLES)
        repeat(3) { v.feed(ack, ack.size) } // 若未暂停，这里会开出一段
        v.pause()
        // 服务层在 ackPlaying 期间完全不喂帧；这里只模拟确认音结束后紧接
        // 的人声（含 300ms 的回声尾巴静音）。
        repeat(15) { v.feed(silence, silence.size) }
        repeat(3) { v.feed(speech, speech.size) } // 用户紧接着开口
        repeat(10) { v.feed(speech, speech.size) }
        repeat(60) { v.feed(quiet, quiet.size) } // 1.2s 静音切句
        assertEquals(1, segments.size)
        assertEquals(
            "片段里不应残留确认音的 6000 幅值帧",
            3_000,
            segments[0].maxOf { it.toInt() },
        )
    }

    /**
     * 切句后用户马上说下一句（不超过 100ms 间隔）时，两段不能粘成一段 ——
     * 否则刚说过的确认音/上一句会被算进新句子的声纹。
     */
    @Test
    fun `a new segment does not open immediately after a cut`() {
        val segments = mutableListOf<ShortArray>()
        val v = vad(1_000, 60_000) { segments += it }
        repeat(3) { v.feed(speech, speech.size) }
        repeat(60) { v.feed(silence, silence.size) } // 第一句 1.2s 静音切出
        assertEquals(1, segments.size)
        repeat(3) { v.feed(silence, silence.size) } // 切句后 60ms 的间隔
        repeat(3) { v.feed(speech, speech.size) } // 紧接着开口
        assertEquals("100ms 内的帧不应重开片段", 1, segments.size)
        repeat(7) { v.feed(silence, silence.size) } // 间隔过后照常收第二句
        repeat(3) { v.feed(speech, speech.size) }
        repeat(60) { v.feed(silence, silence.size) }
        assertEquals(2, segments.size)
    }

    /**
     * 窗口到点判定用整段（≤8s）而不是最后 2s：短请求里尾巴可能只有停顿
     * 或尾音，声纹分数会被压到阈值以下。
     */
    @Test
    fun `short segments are returned in full for voiceprint verification`() {
        val v = vad(1_000, 60_000) {}
        repeat(3 + 197) { v.feed(speech, speech.size) } // 约 4s，未切句
        val sample = v.currentSegmentTailPcm(2_000)
        assertEquals("短句应整段返回（不是 2s 尾巴）", 4_000, sample!!.size * 1000 / WakeAudioCapture.SAMPLE_RATE)
    }

    private companion object {
        const val FRAME_SAMPLES = WakeAudioCapture.SAMPLE_RATE / 1000 * WakeAudioCapture.FRAME_MS
    }
}

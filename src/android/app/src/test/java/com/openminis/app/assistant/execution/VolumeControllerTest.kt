package com.openminis.app.assistant.execution

import android.content.Context
import android.media.AudioManager
import androidx.annotation.VisibleForTesting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [T-local-volume] 音量快路径的回执格式。
 *
 * WHY: 用户要的是「现在多少」，不是档位账。回执只留调整后的音量
 * （`音乐音量73%`），改前的值、档位、增量、是否到顶全部删掉——屏幕上一行
 * 字要能一眼看完，语音上也只念一个数字。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VolumeControllerTest {

    private lateinit var context: Context
    private lateinit var audio: AudioManager
    private lateinit var controller: VolumeController

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        audio = context.getSystemService(AudioManager::class.java)
        // 单例会跨用例持有旧的 AudioManager（Robolectric 每个用例重建
        // Application），所以测试里每次都新建一个绑定当前 context 的实例。
        controller = VolumeController.newInstanceForTest(context)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 9, 0)
        audio.setStreamVolume(AudioManager.STREAM_RING, 3, 0)
    }

    @Test
    fun setReportsOnlyTheResultingPercent() {
        val result = controller.set(73)
        assertTrue(result.success)
        assertEquals("音乐音量73%", result.message)
    }

    @Test
    fun adjustReportsOnlyTheResultingPercent() {
        // 起点 9/15 = 60%
        assertEquals("音乐音量80%", controller.adjust(20).message)
        assertEquals("音乐音量60%", controller.adjust(-20).message)
    }

    @Test
    fun statusReportsOnlyTheCurrentPercent() {
        assertEquals("音乐音量60%", controller.status().message)
    }

    @Test
    fun ringStreamKeepsItsOwnLabel() {
        assertEquals("铃声音量100%", controller.set(100, "ring").message)
    }

    @Test
    fun maximumAndMinimumStillReportThePercent() {
        assertEquals("音乐音量100%", controller.set(100).message)
        assertEquals("音乐音量0%", controller.set(0).message)
    }

    @Test
    fun receiptDropsStepAndDeltaDetails() {
        val message = controller.set(73).message
        assertFalse(message.contains("档"))
        assertFalse(message.contains("→"))
        assertFalse(message.contains("+"))
        assertEquals("音乐音量73%", message)
    }
}

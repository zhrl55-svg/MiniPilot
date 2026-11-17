package com.openminis.app.assistant.execution

import android.content.Context
import android.media.AudioManager
import androidx.annotation.VisibleForTesting
import com.openminis.app.logging.AppLogger
import java.util.Locale

/**
 * [T-local-volume] 音量的进程内直调。
 *
 * 华为（ELS-AN00）实测：`cmd media_session volume --set` 只在日志里成功，
 * 实时流值不变；`settings put system volume_ring` 写入成功但同样不生效。
 * 可靠路径只有 App 进程内的 [AudioManager.setStreamVolume] —— 和音量键、
 * 设置页滑块走的是同一个系统 API。
 *
 * 因此本控制器不做 shell、不打开设置页：一次调用把音乐流（或铃声流）
 * 设到目标档位，并把调整后的音量写成回执。
 * 权限：AndroidManifest 里的 MODIFY_AUDIO_SETTINGS（安装时授予）。
 */
class VolumeController private constructor(context: Context) {

    data class VolumeResult(val success: Boolean, val message: String)

    private val audio: AudioManager? =
        context.getSystemService(AudioManager::class.java)

    /** 绝对设置：用户说「音量调到 60%」「最大」「最小」。 */
    fun set(percent: Int, streamName: String? = null): VolumeResult =
        apply(percent.coerceIn(0, 100), absolute = true, streamName = streamName)

    /** 相对调节：用户说「调大一点」「调小 20%」。 */
    fun adjust(deltaPercent: Int, streamName: String? = null): VolumeResult =
        apply(deltaPercent.coerceIn(-100, 100), absolute = false, streamName = streamName)

    /** 当前音量：「现在音量多少」。 */
    fun status(streamName: String? = null): VolumeResult {
        val am = audio ?: return VolumeResult(false, "这台设备没有可用的音量服务。")
        val stream = streamOf(streamName)
        return try {
            val max = am.getStreamMaxVolume(stream)
            if (max <= 0) return VolumeResult(false, "无法读取${label(stream)}的档位。")
            val current = am.getStreamVolume(stream)
            VolumeResult(true, receipt(stream, current, max))
        } catch (e: SecurityException) {
            permissionDenied()
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "read volume failed: ${t.message}")
            VolumeResult(false, "读取音量失败：${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun apply(
        requestedPercent: Int,
        absolute: Boolean,
        streamName: String?,
    ): VolumeResult {
        val am = audio ?: return VolumeResult(false, "这台设备没有可用的音量服务。")
        val stream = streamOf(streamName)
        return try {
            val max = am.getStreamMaxVolume(stream)
            if (max <= 0) return VolumeResult(false, "无法读取${label(stream)}的档位。")
            val from = am.getStreamVolume(stream)
            val step = Math.round(max * requestedPercent / 100f)
            val target = (if (absolute) step else from + step).coerceIn(0, max)
            // flag=0：静默修改，不弹系统音量条。用户是在跟助理说话，
            // 屏幕上再盖一条系统 UI 只会打断对话。
            am.setStreamVolume(stream, target, 0)
            // 回读实际值：回执必须说真实结果，而不是「我请求了什么」。
            val to = am.getStreamVolume(stream)
            AppLogger.info(
                TAG,
                "${label(stream)} ${percentOf(from, max)}% -> ${percentOf(to, max)}% " +
                    "(requested=${if (absolute) "$requestedPercent%" else "$requestedPercent% delta"})",
            )
            VolumeResult(true, receipt(stream, to, max))
        } catch (e: SecurityException) {
            permissionDenied()
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "setStreamVolume failed: ${t.message}")
            VolumeResult(false, "音量调节失败：${t.message ?: t.javaClass.simpleName}")
        }
    }

    /**
     * 统一回执：只报调整后的音量。
     *
     * WHY: 用户要的是「现在是多少」，不是档位账。改前的值、档位、增量、
     * 是否到顶全是噪音——屏幕上一行字要能一眼看完，语音上也只念一个数字。
     * 例：`音乐音量73%`。
     */
    private fun receipt(stream: Int, value: Int, max: Int): String =
        "${label(stream)}${percentOf(value, max)}%"

    private fun streamOf(name: String?): Int = when (name?.lowercase(Locale.ROOT)) {
        "ring", "铃声" -> AudioManager.STREAM_RING
        else -> AudioManager.STREAM_MUSIC
    }

    private fun label(stream: Int): String = when (stream) {
        AudioManager.STREAM_RING -> "铃声音量"
        else -> "音乐音量"
    }

    private fun percentOf(raw: Int, max: Int): Int =
        if (max <= 0) 0 else raw * 100 / max

    private fun permissionDenied() = VolumeResult(
        false,
        "没有修改音量的权限（MODIFY_AUDIO_SETTINGS）。请重新安装构建流程产出的 MiniPilot 后再试。",
    )

    companion object {
        private const val TAG = "Volume"

        @Volatile
        private var instance: VolumeController? = null

        fun get(context: Context): VolumeController =
            instance ?: synchronized(this) {
                instance ?: VolumeController(context.applicationContext).also { instance = it }
            }

        /**
         * 测试用：返回一个绑定当前 context 的新实例。
         *
         * WHY: 单例会把第一次拿到的 [AudioManager] 一直留着，而 Robolectric
         * 每个用例都重建 Application —— 单例里的 AudioManager 会变成上一用例
         * 的旧对象，断言读的是新对象，两边对不上。
         */
        @VisibleForTesting
        internal fun newInstanceForTest(context: Context): VolumeController =
            VolumeController(context.applicationContext)
    }
}

package com.openminis.app.assistant.execution

import android.content.Context
import android.media.AudioManager
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
 * 设到目标档位，并把「改前 → 改后」的百分比和档位写成回执。
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
            VolumeResult(
                true,
                "${label(stream)} ${percentOf(current, max)}%（第${current}档，共${max}档）。",
            )
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
            VolumeResult(true, message(stream, from, to, max))
        } catch (e: SecurityException) {
            permissionDenied()
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "setStreamVolume failed: ${t.message}")
            VolumeResult(false, "音量调节失败：${t.message ?: t.javaClass.simpleName}")
        }
    }

    /**
     * 统一回执：说清改前改后、实际变化百分比与档位；到顶/到底时明确说明。
     * 例：`音乐音量 60%（第9档）→ 73%（第11档）（+13%）。`
     */
    private fun message(stream: Int, from: Int, to: Int, max: Int): String {
        val name = label(stream)
        val fromPct = percentOf(from, max)
        val toPct = percentOf(to, max)
        val delta = toPct - fromPct
        if (delta == 0) {
            return when {
                to >= max -> "${name}已经是最大（${toPct}%，第${to}档）。"
                to <= 0 -> "${name}已经是最小（0%，第0档）。"
                else -> "${name}保持在 $toPct%（第${to}档）。"
            }
        }
        val sign = if (delta > 0) "+" else ""
        val limit = when {
            to >= max -> "，已到最大"
            to <= 0 -> "，已到最小"
            else -> ""
        }
        return "${name} ${fromPct}%（第${from}档）→ ${toPct}%（第${to}档）（${sign}${delta}%${limit}）。"
    }

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
    }
}

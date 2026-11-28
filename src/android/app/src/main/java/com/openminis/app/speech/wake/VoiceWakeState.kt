package com.openminis.app.speech.wake

import android.content.Context
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the wake pipeline is doing right now. */
enum class VoiceWakePhase {
    /** Feature disabled, or service stopped. */
    OFF,

    /** Microphone open, KWS waiting for the wake word. */
    WAITING,

    /** Wake word matched; the pipeline is waking up. */
    WAKING,

    /**
     * Wake word matched and the voiceprint passed: the microphone is now
     * recording the user's request and nothing else is listening.
     */
    LISTENING,

    /** The request is being transcribed and the agent is working on a reply. */
    THINKING,

    /** The agent's reply is being spoken. */
    REPLYING,

    /** Intentionally not listening (TTS, another recognizer, in-app capture). */
    PAUSED,

    /** The pipeline could not start or lost the microphone. */
    ERROR,
}

data class VoiceWakeSnapshot(
    val phase: VoiceWakePhase = VoiceWakePhase.OFF,
    val detail: String = "",
    /**
     * [T-android-voice-live-preview] 实时识别预览。
     *
     * 唤醒对话聆听期间，端侧识别对最近几秒音频的最新结果。只做界面
     * 展示，不参与提交；相位一变就清空。
     */
    val preview: String = "",
)

/**
 * Process-wide state for the wake service, the floating status ball and the
 * diagnostics file. Updates are deduplicated so a 400 ms gate loop does not
 * rewrite the same line forever.
 */
object VoiceWakeState {

    private const val TAG = "VoiceWake"
    private const val TRACE_FILE = "voice_wake.log"

    private val _state = MutableStateFlow(VoiceWakeSnapshot())
    val state: StateFlow<VoiceWakeSnapshot> = _state.asStateFlow()

    fun update(context: Context, phase: VoiceWakePhase, detail: String = "") {
        val previous = _state.value
        // 同一相位内的 detail 刷新必须保留预览文本；相位切换（聆听 → 思考 /
        // 待命）则清空，旧预览不能挂在新阶段上。
        val preview = if (previous.phase == phase) previous.preview else ""
        val next = VoiceWakeSnapshot(phase, detail, preview)
        if (previous == next) return
        _state.value = next
        AppLogger.info(TAG, "${phase.name} $detail")
        trace(context, "${phase.name} $detail")
    }

    /**
     * [T-android-voice-live-preview] 更新实时预览文本。
     *
     * 只改 [VoiceWakeSnapshot.preview]，不动相位与 detail，也不写日志——
     * 预览每 ~700 ms 刷新一次，写日志会把诊断文件刷爆。
     */
    fun updatePreview(text: String) {
        val previous = _state.value
        if (previous.preview == text) return
        _state.value = previous.copy(preview = text)
    }

    /** Diagnostics at `getExternalFilesDir(null)/voice_wake.log` (adb-readable). */
    fun trace(context: Context, line: String) {
        runCatching {
            val dir = context.getExternalFilesDir(null) ?: return@runCatching
            val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            File(dir, TRACE_FILE).appendText("[$stamp] $line\n")
        }
    }
}

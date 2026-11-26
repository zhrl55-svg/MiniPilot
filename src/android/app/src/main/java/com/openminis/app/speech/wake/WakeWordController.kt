package com.openminis.app.speech.wake

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.openminis.app.MinisApp
import com.openminis.app.logging.AppLogger
import com.openminis.app.speech.MicInUse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single entry point for turning the wake service on/off.
 *
 * It is deliberately separate from the settings UI so the same rules apply to
 * the UI toggle, `minis-config`, and app startup: enabled + RECORD_AUDIO
 * granted starts the service; anything else stops it and publishes an
 * actionable state for the overlay / notification.
 */
object WakeWordController {

    private const val TAG = "VoiceWake"

    /**
     * Minimum gap between degraded-service rebuilds.
     *
     * The preference listener and the foreground callback can both land in
     * the same transition; without a throttle two restarts would race.
     */
    private const val RESTART_THROTTLE_MS = 10_000L

    /** How long to wait for the old service to finish onDestroy. */
    private const val RESTART_WAIT_STEP_MS = 100L
    private const val RESTART_WAIT_MAX_STEPS = 20

    @Volatile
    private var initialized = false
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    /**
     * The last foreground-service start was refused by the system.
     *
     * Android 12+ throws `ForegroundServiceStartNotAllowedException` when a
     * microphone foreground service is started from the background, and
     * Android 14+ refuses the microphone type outright unless the app is
     * eligible (the user switched to the app, or a boot/package event put
     * it in the allowlist). Either way the service is not running and the
     * wake word would silently never work — so the refusal is remembered
     * and retried the moment the app comes back to the foreground.
     */
    @Volatile
    private var startRefused = false

    /** Last degraded rebuild, in elapsedRealtime ms. See [RESTART_THROTTLE_MS]. */
    @Volatile
    private var lastRestartAt = 0L

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * The live service is consuming microphone frames that the platform is
     * feeding silence — the user's preference is still on, but listening is
     * not actually working. The settings toggle reads this to show the truth
     * without rewriting the preference itself.
     */
    private val _degraded = MutableStateFlow(false)
    val degraded: StateFlow<Boolean> = _degraded.asStateFlow()

    fun init(context: Context) {
        if (initialized) {
            sync(context)
            return
        }
        synchronized(this) {
            if (initialized) {
                sync(context)
                return
            }
            val app = context.applicationContext
            WakeWordSettings.init(app)
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                when (key) {
                    WakeWordSettings.KEY_ENABLED,
                    WakeWordSettings.KEY_WAKE_WORD,
                    WakeWordSettings.KEY_OVERLAY_ENABLED,
                    -> sync(app)
                }
            }
            app.getSharedPreferences(WakeWordSettings.PREFS_NAME, Context.MODE_PRIVATE)
                .registerOnSharedPreferenceChangeListener(listener)
            prefsListener = listener
            initialized = true
        }
        sync(context)
    }

    fun sync(context: Context) {
        WakeWordSettings.init(context)
        if (!WakeWordSettings.enabled.value) {
            startRefused = false
            WakeWordSettings.setPendingStart(context, false)
            setDegraded(false)
            WakeWordService.stop(context)
            VoiceWakeState.update(context, VoiceWakePhase.OFF, "语音唤醒已关闭")
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            startRefused = false
            WakeWordSettings.setPendingStart(context, false)
            setDegraded(false)
            WakeWordService.stop(context)
            VoiceWakeState.update(context, VoiceWakePhase.ERROR, "需要麦克风权限")
            AppLogger.info(TAG, "唤醒已开启但缺少 RECORD_AUDIO，服务未启动")
            return
        }

        val app = context.applicationContext
        // Starting a microphone foreground service from the background is the
        // root cause of the deaf-after-install bug: Android 12+ grants the
        // start (boot/package events are exempt) but sets
        // allowWhileInUsePermissionInFgs=false, so the service runs and the
        // platform feeds its recording silence. There is no in-place repair —
        // the ServiceRecord flag belongs to that start — so defer the real
        // start to the first foreground transition instead.
        if (!isAppForeground(app)) {
            startRefused = false
            WakeWordSettings.setPendingStart(app, true)
            AppLogger.info(TAG, "应用在后台，唤醒服务推迟到前台启动")
            return
        }

        if (WakeWordSettings.pendingStart(app) || isDegraded(app)) {
            restartInForeground(app)
            return
        }

        startRefused = !WakeWordService.start(app)
        if (startRefused) {
            AppLogger.info(TAG, "唤醒服务启动被系统拒绝，应用回到前台后会自动重试")
        }
    }

    /**
     * Tear down a service whose microphone access is unusable, then start a
     * fresh instance while the app is in the foreground. `stopService` is
     * asynchronous, so the new start waits for [WakeWordService.isAlive] to
     * flip instead of racing the old instance's onStartCommand.
     */
    private fun restartInForeground(context: Context) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRestartAt < RESTART_THROTTLE_MS) return
        lastRestartAt = now
        WakeWordSettings.setPendingStart(context, false)
        AppLogger.info(TAG, "应用在前台，重建唤醒服务")
        WakeWordService.requestStop(context)
        restartWhenStopped(context, 0)
    }

    private fun restartWhenStopped(context: Context, attempt: Int) {
        mainHandler.postDelayed({
            if (WakeWordService.isAlive) {
                if (attempt < RESTART_WAIT_MAX_STEPS) {
                    restartWhenStopped(context, attempt + 1)
                } else {
                    // Do not lose the intent to repair: try again on the next
                    // foreground transition rather than spinning here.
                    WakeWordSettings.setPendingStart(context, true)
                    AppLogger.info(TAG, "旧唤醒服务未按时退出，下次前台再重建")
                }
                return@postDelayed
            }
            startRefused = !WakeWordService.start(context)
        }, RESTART_WAIT_STEP_MS)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        WakeWordSettings.setEnabled(context, enabled)
        sync(context)
    }

    /**
     * The app came back to the foreground, where a foreground-service start
     * is allowed again. Revalidate unconditionally: a start that returned
     * true can still be degraded (the service runs but hears silence), so
     * `startRefused` alone is not a health check.
     *
     * The wake word stays available in the foreground too: the service (not
     * the Activity lifecycle) decides when the microphone must be yielded,
     * and it keeps listening while the app is simply open.
     */
    fun onAppForegrounded(context: Context) {
        if (!WakeWordSettings.enabled.value) return
        sync(context)
    }

    /**
     * The service was torn down while the feature is still enabled — either
     * the system refused the microphone foreground service or it was killed.
     * Remember it so the next foreground transition restarts it.
     */
    fun onServiceStartRefused(context: Context) {
        if (!WakeWordSettings.enabled.value) return
        startRefused = true
        setDegraded(true)
        VoiceWakeState.update(
            context,
            VoiceWakePhase.ERROR,
            "后台录音服务未运行，打开 MiniPilot 后会自动恢复",
        )
    }

    /**
     * Update the observable health flag from the live capture.
     *
     * [MicInUse.isSilenced] is the platform's own answer, not a guess: the
     * same call the recognizer already uses when a call preempts the mic.
     * Only called while the engine is actually recording.
     */
    fun observeCapture(context: Context, audioSessionId: Int) {
        if (audioSessionId == 0) return
        val silenced = runCatching {
            MicInUse.isSilenced(context, audioSessionId)
        }.getOrDefault(false)
        setDegraded(silenced)
    }

    fun setDegraded(value: Boolean) {
        if (_degraded.value != value) {
            _degraded.value = value
            AppLogger.info(TAG, "唤醒录音降级状态=$value")
        }
    }

    /** Boot/package events record the intent; the start waits for the foreground. */
    fun markPendingRestore(context: Context) {
        if (WakeWordSettings.enabled.value) {
            WakeWordSettings.setPendingStart(context, true)
        }
    }

    private fun isDegraded(context: Context): Boolean {
        val sessionId = WakeWordService.currentAudioSessionId
        // Unknown (service down or engine not recording yet): do not claim
        // degraded, the foreground restart path is driven by pendingStart.
        if (sessionId == 0) return false
        return runCatching {
            MicInUse.isSilenced(context, sessionId)
        }.getOrDefault(false)
    }

    private fun isAppForeground(context: Context): Boolean =
        (context as? MinisApp)?.isAppForeground() == true
}
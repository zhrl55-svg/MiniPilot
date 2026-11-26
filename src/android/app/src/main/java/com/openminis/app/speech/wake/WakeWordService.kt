package com.openminis.app.speech.wake

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.logging.AppLogger
import com.openminis.app.speech.RecognitionState
import com.openminis.app.speech.SpeechRecognitionManager
import com.openminis.app.speech.VoiceOutputState
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Background wake-word listener.
 *
 * Shape:
 *  - a microphone foreground service (Android 10+ requires the type, Android
 *    14+ the `FOREGROUND_SERVICE_MICROPHONE` permission)
 *  - one AudioRecord owned by [WakeWordEngine]
 *  - a 400 ms gate loop that yields the microphone whenever an in-app voice
 *    capture is active, the in-app recognizer is running, TTS is playing, or
 *    the user has just been woken
 *  - on a hit: keep the microphone and run the turn headlessly; tapping the
 *    overlay/notification opens the persistent voice-conversation session
 *
 * The gate is the important part. Holding a second AudioRecord while the
 * in-app recognizer opens the mic produces silence on one side or the other,
 * and holding it while TTS is speaking lets the assistant wake itself.
 *
 * The wake word stays available in the foreground — the app is a voice
 * assistant first, and the user should be able to say the wake word without
 * leaving the chat. The microphone is only handed over while an in-app
 * capture (composer dictation or speech recognition) actually needs it.
 */
class WakeWordService : Service() {

    companion object {
        private const val TAG = "VoiceWake"
        const val CHANNEL_ID = "minis_voice_wake"
        const val ALERT_CHANNEL_ID = "minis_voice_wake_alert"
        const val NOTIFICATION_ID = 9004
        const val ALERT_NOTIFICATION_ID = 9005
        private const val ACTION_STOP = "com.openminis.app.voice.wake.STOP"
        private const val GATE_INTERVAL_MS = 400L

        /** Guard against a second hit right after the conversation ended. */
        private const val WAKE_SUPPRESS_MS = 2_000L

        /**
         * How long keyword spotting stays off after a rejected wake.
         *
         * The engine and microphone stay open — only the spotter pauses.
         * Without the pause the tail of the same ambient phrase can match
         * again immediately, which burns a voiceprint extraction per repeat.
         */
        private const val WAKE_REJECT_COOLDOWN_MS = 700L

        /** Segments kept while the previous one is being transcribed. */
        private const val MAX_PENDING_UTTERANCES = 4

        /**
         * [T-voice-request-silence] Quiet time that ends a spoken request.
         *
         * 1.0 s: the user asked for exactly this ("说话人静音超过1秒就开始
         * 识别并回复"), and it is also the value that makes the request feel
         * answered instead of hanging. It used to be 1.2 s, which was long
         * enough that the sentence sat in the buffer for another fifth of a
         * second after the user had clearly stopped talking.
         *
         * This is now the ONLY rule that ends a request — the conversation
         * deadline above is a silence window and is renewed while the user
         * keeps talking.
         */
        private const val REQUEST_SILENCE_MS = 1_000

        /** Safety valve only: a request that never pauses is cut here. */
        private const val REQUEST_MAX_SEGMENT_MS = 60_000

        /**
         * The offline recogniser may keep emitting partials forever and
         * never settle; without this timeout the coroutine waiting for the
         * final result would park for good and the retry loop would never
         * run again.
         */
        private const val TRANSCRIBE_TIMEOUT_MS = 30_000L

        /**
         * Extra time after the listening window expires for an utterance
         * that was already cut and is being transcribed. Without it the
         * sleep branch wins the race and the finished transcript is thrown
         * away as "state changed during recognition".
         */
        private const val WINDOW_TRANSCRIBE_GRACE_MS = 3_000L

        /**
         * Minimum speech before a first utterance may enrol the owner
         * template — a cough is not a voiceprint.
         *
         * 1.0 s, not 1.5 s: a short request like "几点了" is 0.8–1.2 s and
         * would otherwise never enrol anything, leaving the gate inert.
         */
        private const val ENROLL_MIN_SECONDS = 1.0f

        /**
         * How much of the recording around the wake word is used to enrol
         * or verify the owner.
         *
         * The keyword spotter fires at the *end* of the keyword, so the last
         * two and a half seconds contain the whole keyword plus a little of
         * what came before it — all of it the owner's own voice, and none of
         * it the assistant's echo.
         */
        private const val WAKE_ENROLL_WINDOW_MS = 2_500

        /** Below this the wake-word sample is too short to describe a voice. */
        private const val WAKE_ENROLL_MIN_SECONDS = 0.8

        /**
         * Quiet period after the assistant stops talking.
         *
         * The microphone stays open during the reply, so the tail of the
         * assistant's own voice is still in the buffer when the VAD comes
         * back. Without this gap the VAD would cut the echo as a new
         * utterance and the assistant would answer itself.
         */
        private const val ECHO_GUARD_MS = 1_000L

        /**
         * [T-voice-single-speak] 等端侧 announce 真正起播的最长时间。
         *
         * announce 走的是延迟起播（1.2 秒），从「本轮被标记为已播报」到
         * 「扬声器真的响了」之间还有这段空窗。等不到就当它没播出来。
         */
        private const val ANNOUNCE_START_WAIT_MS = 3_000L

        /**
         * [T-voice-single-speak] 等端侧 announce 播完的最长时间。
         *
         * 一篇新闻摘要能念近一分钟，和 [LocalActionVoiceConfirmer] 里的
         * 播报超时保持一致。
         */
        private const val ANNOUNCE_FINISH_WAIT_MS = 60_000L

        /**
         * How long the conversation survives after the window closed on an
         * unfinished utterance when the VOICEPRINT GATE IS OFF.
         *
         * Only the pre-e2c0667 flush/sleep path uses this now. With the gate
         * on, a failed verdict sleeps immediately — a 20 s grace there is what
         * flushed the room's music into the chat for 28–48 s (owner, 2026-11-22).
         */
        private const val FLUSH_GRACE_MS = 20_000L

        /** Pause that cuts a barge-in utterance while the assistant talks. */
        private const val BARGE_IN_SILENCE_MS = 300

        /**
         * Longest barge-in segment.
         *
         * TTS is continuous, so waiting for a natural sentence pause can
         * delay "别说了" by many seconds. Cutting every 1.5 s keeps the
         * reaction time short without sending frame-sized clips to the ASR.
         */
        private const val BARGE_IN_MAX_SEGMENT_MS = 1_500

        /** Fallback when the string resource cannot be resolved. */
        private const val DEFAULT_ACKNOWLEDGEMENT = "我在"

        /**
         * How long the room keeps ringing after the engine stopped.
         *
         * The microphone hears the tail of the assistant's own voice for a
         * few hundred milliseconds after the TTS engine reports "done".
         */
        private const val ECHO_TAIL_MS = 700L

        /**
         * Grace period before the microphone is opened for the first time.
         *
         * The service is started from `Application.onCreate` (and again on
         * every launch), which runs BEFORE the first Activity is started —
         * so for a moment the app looks "in the background" and the gate
         * would open the microphone only to release it again a few hundred
         * milliseconds later, racing the in-app recognizer for the device.
         */
        private const val STARTUP_GRACE_MS = 5_000L

        /**
         * Backoff before the KWS model is loaded again after a failure.
         *
         * The gate loop runs every 400 ms; without this the service would
         * reload the 4.6 MB zipformer on every tick while the microphone is
         * unavailable (a call in progress, a revoked permission, a HAL
         * error) and burn CPU instead of waiting.
         */
        private const val ENGINE_RETRY_DELAY_MS = 30_000L

        /** True while this process holds a live [WakeWordService]. */
        @Volatile
        var isAlive: Boolean = false
            private set

        /**
         * True while the composer owns the microphone for press-and-hold
         * dictation.
         *
         * The composer records through its own [WakeAudioCapture], and
         * [SpeechRecognitionManager] says nothing about that until the
         * transcription starts — so without this flag the wake engine and
         * the composer would open two AudioRecords at once. Set before the
         * composer opens its recorder and cleared on every terminal path.
         */
        @Volatile
        private var inAppCapturePaused = false

        /** The live instance, so the app can hand the microphone back fast. */
        @Volatile
        private var instance: WakeWordService? = null

        /**
         * Give the microphone up for an in-app recording.
         *
         * Called *before* the composer opens its own AudioRecord: the engine
         * is stopped synchronously, so the hand-off is deterministic instead
         * of a race between two capture streams. The gate loop keeps the
         * engine down until [resumeAfterInAppCapture].
         */
        fun pauseForInAppCapture() {
            inAppCapturePaused = true
            instance?.suspendForInAppCapture()
        }

        /** The in-app recording ended: the wake engine may open the mic again. */
        fun resumeAfterInAppCapture() {
            inAppCapturePaused = false
        }

        /**
         * Stop the reply the wake conversation is speaking.
         *
         * The wake conversation owns its own TTS engine, so the composer's
         * read-aloud player cannot silence it. The UI calls this from the
         * speaker button next to Send.
         */
        fun stopSpeaking() {
            instance?.stopSpeakingNow()
        }

        /**
         * Start the service. Returns false when the system refused the
         * start (Android 12+ throws when a foreground service is started
         * from the background without an exemption) so the caller can
         * retry when the app comes back to the foreground.
         */
        fun start(context: Context): Boolean {
            val intent = Intent(context, WakeWordService::class.java)
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (t: Throwable) {
                AppLogger.info(TAG, "无法启动语音唤醒服务：${t.javaClass.simpleName}: ${t.message}")
                VoiceWakeState.update(context, VoiceWakePhase.ERROR, "系统拒绝了后台录音服务的启动")
                false
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, WakeWordService::class.java)) }
        }
    }

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, e ->
                AppLogger.error(TAG, "语音唤醒协程未捕获异常：${e.message ?: e.javaClass.simpleName}")
            },
    )

    @Volatile
    private var engine: WakeWordEngine? = null
    private var overlay: VoiceStatusOverlay? = null
    private var gateJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var foregroundStarted = false
    private var destroying = false
    private var engineStarting = false
    @Volatile
    private var desiredListening = false
    @Volatile
    private var engineError: String? = null
    @Volatile
    private var nextEngineRetryAt = 0L
    private var wakingUntil = 0L
    private var lastNotificationText = ""
    private val serviceStartedAt = SystemClock.elapsedRealtime()

    // ------------------------------------------------------- conversation

    /** Where the background conversation currently is. */
    private enum class ConversationState {
        /** Only the wake word is being watched for. */
        IDLE,

        /**
         * The wake word matched, but its voiceprint is still being checked.
         *
         * Not a wake yet: no acknowledgement, no VAD, no listening window.
         * The microphone stays open only to keep the engine from being torn
         * down between the keyword and the verdict.
         */
        VERIFYING,

        /** The microphone is open and the user's request is being recorded. */
        LISTENING,

        /** The request is being transcribed / answered / spoken. */
        REPLYING,
    }

    @Volatile
    private var conversationState = ConversationState.IDLE

    /** When the current listening window runs out. */
    @Volatile
    private var conversationDeadline = 0L

    /** When the current LISTENING phase began (elapsedRealtime), for the hard cap. */
    @Volatile
    private var conversationListeningSince = 0L

    /** Bounded voiceprint graces used by the current listening phase. */
    @Volatile
    private var voiceprintGraceUsed = 0

    /**
     * The owner's voiceprint has already passed once inside this listening
     * phase.
     *
     * The energy VAD cannot end a sentence in a noisy car: road noise keeps
     * the level above the end threshold, so the request stays in one segment
     * for 10–30 s and the window only ever closes on the deadline. That
     * deadline used to judge the last 2 s of the segment — by then the room,
     * not the speaker — and throw the whole request away (device log
     * 2026-11-26 13:01: 续期 0.81 at 13:01:41, 到点 0.18 at 13:01:46,
     * 丢弃 10340ms; five times in a row, while the very same sentences went
     * through with the voiceprint switch off).
     *
     * A renewal that already passed is proof that the owner is the one
     * talking in this phase. When the deadline arrives with that proof and
     * the segment's tail is the room instead of the voice, the buffered
     * request is handed to the recogniser instead of being dropped.
     */
    @Volatile
    private var ownerVerifiedInWindow = false

    /**
     * One segment that may skip the per-utterance voiceprint.
     *
     * Set when [ownerVerifiedInWindow] lets an over-long noisy segment out at
     * the deadline; consumed by the next [handleUtterance] so that a later
     * stranger's sentence in the same phase still goes through the gate.
     */
    @Volatile
    private var voiceprintExemptSegment = false

    /**
     * Serialises [WakeVoiceprintGate.verify].
     *
     * sherpa's `SpeakerEmbeddingExtractor` is not safe for concurrent calls:
     * the window-renewal check runs on the IO dispatcher while a request
     * utterance may be verifying on its own coroutine. Two overlapping
     * extractions corrupt the native stream (crash or garbage score), so
     * every `verify` goes through this lock.
     */
    private val voiceprintLock = Any()

    /**
     * How long the assistant keeps listening for the request — user-tunable
     * (3–30 s, default 8 s), read live so a settings change applies to the
     * next window without restarting the service.
     *
     * A SILENCE window, not a speech cap: it starts at the wake hit / after
     * the reply and is renewed only when the buffered speech tail passes the
     * OWNER's voiceprint, so a long uninterrupted request is never cut off
     * mid-sentence ("长语音只进去前半段"). Once this long has passed with no
     * owner speech, the assistant goes back to standby immediately — noise
     * (music, a fan, a stranger) does not renew and is not delivered
     * ([WakeListenWindowPolicy]).
     */
    private val conversationWindowMs: Long
        get() = WakeWordSettings.voiceSleepWindowMs.value

    /** Cuts the user's request out of the live microphone stream. */
    private var vad: WakeEnergyVad? = null

    /** True while the wake acknowledgement is being spoken. */
    @Volatile
    private var ackPlaying = false

    /** True while one utterance is being transcribed / answered. */
    @Volatile
    private var utteranceBusy = false

    /**
     * Segments cut while [utteranceBusy].
     *
     * The acknowledgement segment and the request can arrive back-to-back.
     * Dropping the second one made the assistant answer "我在" and then go
     * silent even though the user had already asked the question.
     */
    private val pendingUtterances = ArrayDeque<ShortArray>()

    /** When the assistant's own voice is over (elapsedRealtime). */
    @Volatile
    private var echoDeadline = 0L

    /** When the acknowledgement stopped being spoken (elapsedRealtime). */
    @Volatile
    private var ackEndedAt = 0L

    /** When the acknowledgement started (elapsedRealtime), 0 = none. */
    @Volatile
    private var ackStartedAt = 0L

    /** The word the assistant used as the acknowledgement. */
    @Volatile
    private var ackText: String = ""

    /** The reply the assistant just spoke — its echo must not be answered. */
    @Volatile
    private var replyText: String = ""

    /** True when the user asked to cut the current spoken reply. */
    @Volatile
    private var speechStopRequested = false

    /** Cuts stop commands out of the audio while the reply is speaking. */
    private var bargeInVad: WakeEnergyVad? = null

    /** One barge-in transcription at a time. */
    @Volatile
    private var bargeInBusy = false

    /**
     * [T-music-pause-on-voice] True while the conversation parked the music
     * player. The player itself remembers whether it was actually playing,
     * so a resume without a matching pause is a no-op; this flag only makes
     * the service's own suspend/resume pairs symmetric.
     */
    @Volatile
    private var playbackSuspendedForConversation = false

    private val voiceprint by lazy { WakeVoiceprintGate(this) }

    /**
     * Speaks the replies.
     *
     * The read-aloud capsule is UI-only, so the service keeps its own TTS
     * engine — the whole point of the background conversation is that no
     * Activity is opened.
     */
    private var tts: com.openminis.app.speech.TextToSpeechManager? = null

    /**
     * [T-android-voice-synthesis-settings] Speaks the REPLY through the same
     * routing as chat read-aloud: the selected cloud TTS model when one is
     * chosen, otherwise the device engine with the picked system voice.
     *
     * The service used to speak replies with its own [TextToSpeechManager]
     * directly, so changing the voice in Settings › Speech Synthesis had no
     * effect on the background voice conversation — cloud stayed the device
     * voice. Created lazily on the first reply; [tts] remains for the quick
     * "I'm here" acknowledgement, which should stay local/low-latency.
     */
    private var readAloud: com.openminis.app.speech.ReadAloudPlayer? = null

    private fun ensureReadAloud(): com.openminis.app.speech.ReadAloudPlayer? =
        runCatching {
            readAloud ?: com.openminis.app.speech.ReadAloudPlayer(
                applicationContext,
                registerWithCapsule = false,
            ).also { readAloud = it }
        }.onFailure {
            AppLogger.info(TAG, "无法创建朗读播放器：${it.message}")
        }.getOrNull()

    /** When the last conversation diagnostic line was written. */
    private var lastDiagAt = 0L

    override fun onCreate() {
        super.onCreate()
        isAlive = true
        instance = this
        WakeWordSettings.init(this)
        createChannels()
        overlay = VoiceStatusOverlay(this)

        if (!startForegroundSafely(buildNotification())) {
            stopSelf()
            return
        }

        scope.launch {
            VoiceWakeState.state.collect { snapshot ->
                overlay?.update(snapshot)
                updateNotification(snapshot)
            }
        }
        // The TTS engine is bound **now**, not when the first wake word
        // arrives: a cold TTS engine takes seconds to initialise, and every
        // second of that is a second in which the user is already talking
        // into a microphone that answers with silence.
        prewarmTts()
        prewarmVoiceprint()
        gateJob = scope.launch { gateLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            WakeWordSettings.setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!WakeWordSettings.enabled.value) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!foregroundStarted && !startForegroundSafely(buildNotification())) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.onConfigurationChanged()
    }

    override fun onDestroy() {
        destroying = true
        isAlive = false
        if (instance === this) instance = null
        gateJob?.cancel()
        gateJob = null
        runCatching { tts?.stop() }
        tts = null
        runCatching { readAloud?.shutdown() }
        readAloud = null
        // engine.stop() 会 join 采集线程，主线程的 onDestroy 等不起。先把
        // 字段清掉（后续路径不再拿到旧引擎），stop 丢给独立线程完成。
        val engineToStop = engine
        engine = null
        if (engineToStop != null) {
            Thread {
                runCatching { engineToStop.stop() }
            }.start()
        }
        overlay?.hide()
        overlay = null
        releaseWakeLock()
        // A destroyed service must not leave the music parked forever.
        resumePlaybackAfterConversation()
        if (WakeWordSettings.enabled.value) {
            // The service died while the feature is still on. Android 14+
            // refuses a microphone foreground service that is started from
            // the background, so the next attempt has to wait for the app
            // to be in the foreground again.
            WakeWordController.onServiceStartRefused(this)
        } else {
            VoiceWakeState.update(this, VoiceWakePhase.OFF, "语音唤醒已停止")
        }
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- gate

    private suspend fun gateLoop() {
        while (scope.isActive && !destroying) {
            val now = SystemClock.elapsedRealtime()
            val app = applicationContext as? MinisApp
            val foreground = app?.isAppForeground() == true
            val enabled = WakeWordSettings.enabled.value
            val overlayEnabled = WakeWordSettings.showOverlay.value
            val permission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            val sttActive = SpeechRecognitionManager.state.value != RecognitionState.IDLE
            // [T-tts-barge-in] Read-aloud audio no longer stops the engine: it
            // keeps listening so "停一下 / 别说了" can cut the playback, and
            // onWakeHit ignores wake-word hits while the assistant itself is
            // audible. Only synthesis with nothing playing yet leaves the gate
            // untouched (starting the engine early is harmless).
            val speaking = VoiceOutputState.isSpeaking.value
            val inAppCapture = inAppCapturePaused
            val waking = now < wakingUntil
            val inGrace = now - serviceStartedAt < STARTUP_GRACE_MS

            // The conversation owns the microphone while it runs: the
            // assistant is recording the request, so the normal gate does
            // not apply.
            if (conversationState != ConversationState.IDLE) {
                handleConversationTick(now, enabled, permission)
                val showBall = enabled && overlayEnabled && !foreground
                overlay?.setVisible(showBall)
                delay(GATE_INTERVAL_MS)
                continue
            }

            val reason = when {
                !enabled -> "语音唤醒已关闭"
                !permission -> "需要麦克风权限"
                waking -> "正在唤醒"
                inGrace -> "正在初始化，稍后开始聆听"
                inAppCapture -> "正在使用语音输入"
                // Only the recognizer's own state says whether the app holds
                // the microphone. A composer-mode flag would stay stale after
                // backgrounding and could silence the wake word.
                sttActive -> "语音识别进行中"
                else -> null
            }
            val shouldListen = reason == null
            desiredListening = shouldListen

            if (shouldListen) {
                val words = WakeWordSettings.wakeWordList()
                val failed = engineError
                if (failed != null && now < nextEngineRetryAt) {
                    // Backoff: keep the error visible, do not reload the
                    // model on every 400 ms tick.
                    if (engine != null) stopEngine()
                    publish(VoiceWakePhase.ERROR, failed)
                } else {
                    if (engine == null && !engineStarting) {
                        startEngine(words)
                    } else if (engine != null && !engine!!.isListening && !engineStarting) {
                        startEngine(words)
                    } else if (engine != null && engine!!.words != words) {
                        startEngine(words)
                    }
                }
                if (engine?.isListening == true) {
                    // The engine is up: any earlier failure is over.
                    if (engineError != null) {
                        engineError = null
                        nextEngineRetryAt = 0L
                    }
                    // [T-tts-barge-in] While read-aloud audio is playing,
                    // watch the same frames for the spoken stop phrase. The
                    // engine stays up (the old gate stopped it to avoid
                    // hearing itself); onWakeHit drops wake-word hits during
                    // playback, so only an explicit stop command acts.
                    if (speaking) {
                        if (bargeInVad == null) {
                            installBargeInVad(VoiceOutputState.currentSpeechText.value)
                        }
                    } else if (bargeInVad != null) {
                        removeBargeInVad()
                    }
                    publish(VoiceWakePhase.WAITING, "等待唤醒词「${WakeWordSettings.wakeWord.value}」")
                } else if (bargeInVad != null) {
                    // The engine is (re)starting; the old onFrame hook went
                    // with it. Reinstall on the next tick once it listens.
                    removeBargeInVad()
                }
            } else {
                if (bargeInVad != null) removeBargeInVad()
                if (engine != null || engineStarting) stopEngine()
                val phase = when {
                    !enabled -> VoiceWakePhase.OFF
                    !permission -> VoiceWakePhase.ERROR
                    // The wake hit opens the voice chat; keep saying
                    // "waking" for the suppression window instead of
                    // falling back to "paused".
                    waking -> VoiceWakePhase.WAKING
                    else -> VoiceWakePhase.PAUSED
                }
                publish(phase, reason.orEmpty())
            }

            val showBall = enabled && overlayEnabled && !foreground
            overlay?.setVisible(showBall)

            delay(GATE_INTERVAL_MS)
        }
    }

    private fun startEngine(words: List<String>) {
        if (engineStarting || destroying) return
        if (words.isEmpty()) {
            failEngine("唤醒词为空")
            return
        }
        engineStarting = true
        // Never hold two AudioRecords. The service gate calls this only after
        // deciding the old engine must be replaced (wake-word edit) or was
        // stopped, so dropping it first is safe.
        engine?.stop()
        engine = null
        scope.launch {
            var candidate: WakeWordEngine? = null
            val started = withContext(Dispatchers.IO) {
                try {
                    val next = WakeWordEngine(
                        context = this@WakeWordService,
                        onHit = { word -> scope.launch { onWakeHit(word) } },
                        onError = { message -> onEngineError(message) },
                    )
                    candidate = next
                    if (next.start(words)) {
                        true
                    } else {
                        // Never leave a half-started engine behind: it owns
                        // the only AudioRecord.
                        next.stop()
                        false
                    }
                } catch (t: Throwable) {
                    AppLogger.info(TAG, "唤醒引擎异常：${t.javaClass.simpleName}: ${t.message}")
                    // The candidate may already own an AudioRecord.
                    candidate?.stop()
                    false
                }
            }
            engineStarting = false
            val ready = candidate
            if (started && ready != null) {
                if (desiredListening && !destroying && !inAppCapturePaused) {
                    engine = ready
                    engineError = null
                    nextEngineRetryAt = 0L
                    // Only hold the CPU awake while the microphone is
                    // actually open: in the foreground the screen already
                    // keeps the device awake, and a permanent wake lock
                    // would drain the battery for nothing.
                    acquireWakeLock()
                    AppLogger.info(TAG, "唤醒引擎已就绪")
                    publish(VoiceWakePhase.WAITING, "等待唤醒词「${WakeWordSettings.wakeWord.value}」")
                } else {
                    ready.stop()
                }
            } else if (inAppCapturePaused) {
                // The start raced the composer's recorder and lost the mic.
                // Expected, not an engine failure: the gate retries as soon
                // as the hold ends.
                engineError = null
                nextEngineRetryAt = 0L
            } else {
                failEngine(engineError ?: "唤醒引擎启动失败")
            }
        }
    }

    /**
     * The engine failed (model load, microphone, decoder). The state is
     * published immediately and the model is retried after
     * [ENGINE_RETRY_DELAY_MS].
     */
    private fun onEngineError(message: String) {
        failEngine(message)
    }

    private fun failEngine(message: String) {
        engineError = message
        nextEngineRetryAt = SystemClock.elapsedRealtime() + ENGINE_RETRY_DELAY_MS
        scope.launch {
            publish(VoiceWakePhase.ERROR, message)
        }
    }

    private fun stopEngine() {
        engine?.stop()
        engine = null
        releaseWakeLock()
    }

    /**
     * Stop the engine for a composer recording.
     *
     * Runs on the caller's thread (the composer's main-thread gesture
     * coroutine) because the composer opens its AudioRecord immediately
     * after this returns; posting the stop to [scope] would race it.
     */
    private fun suspendForInAppCapture() {
        // A background conversation may be mid-turn. The user has picked up
        // the composer, so that turn yields to the app's own voice input.
        abortConversation("应用内语音输入")
        stopEngine()
        publish(VoiceWakePhase.PAUSED, "语音输入中")
    }

    // ---------------------------------------------------------------- wake

    private fun onWakeHit(word: String) {
        val now = SystemClock.elapsedRealtime()
        if (now < wakingUntil || destroying || conversationState != ConversationState.IDLE) return
        // [T-tts-barge-in] The engine now stays up while read-aloud audio is
        // playing, so the stop phrase can be heard. A wake-word hit in that
        // window is the assistant's own voice leaking into the microphone,
        // not the user — drop it before it arms the suppression window.
        if (VoiceOutputState.isSpeaking.value) {
            AppLogger.info(TAG, "忽略播报期间的唤醒命中「$word」")
            VoiceWakeState.trace(this, "忽略播报期间的唤醒命中 $word")
            return
        }
        wakingUntil = now + WAKE_SUPPRESS_MS
        // Log what the gate believed at the moment of the hit.
        val ttsSpeaking = VoiceOutputState.isSpeaking.value
        val ttsSynthesizing = VoiceOutputState.isSynthesizing.value
        val stt = SpeechRecognitionManager.state.value
        val foreground = (applicationContext as? MinisApp)?.isAppForeground() == true
        val peak = engine?.peakLevel ?: 0f
        AppLogger.info(
            TAG,
            "唤醒词命中「$word」 peak=${"%.3f".format(peak)} tts=$ttsSpeaking/$ttsSynthesizing " +
                "stt=$stt fg=$foreground",
        )
        VoiceWakeState.trace(this, "WAKE_HIT $word")

        // The microphone STAYS OPEN: the request is the next thing the user
        // says, and the voiceprint needs the exact samples. Only the keyword
        // spotter goes quiet, so it cannot fire again inside the utterance.
        engine?.setDetecting(false)

        if (!WakeWordSettings.voiceprintEnabled.value) {
            publish(VoiceWakePhase.WAKING, "唤醒词「$word」")
            startConversation()
            return
        }

        // Take the snapshot on the caller's thread, before the model has
        // had time to run: the ring buffer keeps moving and must not pick
        // up the acknowledgement or the request.
        val snapshot = engine?.recentAudio(WAKE_ENROLL_WINDOW_MS) ?: ShortArray(0)

        // Nothing to compare against yet. Enrol in the background and let
        // the first wake through immediately: waiting for a 28 MB model to
        // load before saying "我在" would make the very first use feel
        // broken, and there is no verdict to wait for anyway.
        if (!voiceprint.hasTemplate()) {
            publish(VoiceWakePhase.WAKING, "唤醒词「$word」")
            enrollWakeVoiceprint(snapshot)
            startConversation()
            return
        }

        // A hit is not a wake until the owner's voiceprint passes. Park in
        // VERIFYING: the gate loop keeps the microphone open, but no
        // acknowledgement is spoken, no VAD is armed and no listening
        // window starts. An ambient "小派" therefore never becomes a wake.
        conversationState = ConversationState.VERIFYING
        conversationDeadline = now + conversationWindowMs
        // Do not publish WAKING here: on a false hit the overlay and
        // notification must stay on "waiting", exactly as if the phrase
        // had never matched. The score line is the diagnostic signal.

        scope.launch {
            val passed = withContext(Dispatchers.IO) {
                passesWakeVoiceprint(snapshot)
            }
            if (destroying || conversationState != ConversationState.VERIFYING) return@launch

            if (passed) {
                // Verdict passed: this is a real wake. Begin the
                // conversation exactly as before.
                conversationState = ConversationState.IDLE
                startConversation()
                return@launch
            }

            // Rejected: go straight back to standby. No acknowledgement,
            // no VAD, no conversation window — the false hit is discarded.
            conversationState = ConversationState.IDLE
            wakeRejected(snapshot)
        }
    }

    /**
     * A failed wake: discard it without saying or recording anything.
     *
     * The spotter pauses briefly so the tail of the same ambient phrase
     * cannot immediately trigger another extraction.
     */
    private suspend fun wakeRejected(snapshot: ShortArray) {
        pendingUtterances.clear()
        // The gate loop stops the engine while [wakingUntil] is in the
        // future and the conversation is idle. Clearing it keeps the
        // microphone (and the KWS model) alive; only [WakeWordEngine.detecting]
        // is paused below.
        wakingUntil = 0L
        publish(VoiceWakePhase.WAITING, "等待唤醒词「${WakeWordSettings.wakeWord.value}」")
        AppLogger.info(TAG, "唤醒声纹未通过，保持待命（${snapshot.size * 1000 / WakeAudioCapture.SAMPLE_RATE}ms）")
        delay(WAKE_REJECT_COOLDOWN_MS)
        if (!destroying && conversationState == ConversationState.IDLE) {
            engine?.setDetecting(true)
        }
    }

    // ------------------------------------------------------- conversation

    /** Enrol the first wake-word sample without delaying the first reply. */
    private fun enrollWakeVoiceprint(pcm: ShortArray) {
        val seconds = pcm.size.toDouble() / WakeAudioCapture.SAMPLE_RATE
        if (seconds < WAKE_ENROLL_MIN_SECONDS) {
            AppLogger.info(TAG, "唤醒词音频 ${"%.1f".format(seconds)}s 太短，稍后用第一句注册")
            return
        }
        scope.launch(Dispatchers.IO) {
            val ok = voiceprint.enroll(pcm)
            AppLogger.info(TAG, "用唤醒词注册声纹 ${"%.1f".format(seconds)}s → saved=$ok")
            VoiceWakeState.trace(this@WakeWordService, "声纹注册 ${if (ok) "成功" else "失败"}")
        }
    }

    /**
     * The wake-word voiceprint gate.
     *
     * The wake word is public: the KWS model fires on the sound of the
     * phrase, not on who said it. Without this check an ambient "小派" from
     * a TV or another person opens a conversation, plays "我在", and holds
     * the microphone for eight seconds.
     *
     * Soft-gate rules are the same as [passesVoiceprint]: no template, no
     * model, or an unusably short sample lets the hit through rather than
     * locking the owner out. With a template and a usable sample, the score
     * must reach the configured threshold.
     *
     * On the very first wake there is no template; that wake-word sample is
     * enrolled, exactly as before — it is still the one sample guaranteed
     * not to contain the assistant's own voice.
     */
    private fun passesWakeVoiceprint(pcm: ShortArray): Boolean {
        if (!WakeWordSettings.voiceprintEnabled.value) return true
        val gate = voiceprint
        val seconds = pcm.size.toDouble() / WakeAudioCapture.SAMPLE_RATE

        if (!gate.hasTemplate()) return true

        if (seconds < WAKE_ENROLL_MIN_SECONDS) {
            AppLogger.info(TAG, "唤醒声纹跳过：音频仅 ${"%.1f".format(seconds)}s，放行")
            return true
        }

        val score = synchronized(voiceprintLock) { gate.verify(pcm) }
        if (score == null) {
            AppLogger.info(TAG, "唤醒声纹无法校验，放行")
            return true
        }
        val threshold = WakeWordSettings.voiceprintThreshold.value
        val ok = score >= threshold
        AppLogger.info(
            TAG,
            "唤醒声纹 ${"%.3f".format(score)} 阈值 ${"%.2f".format(threshold)} → " +
                (if (ok) "通过" else "拒绝"),
        )
        VoiceWakeState.trace(
            this,
            "唤醒声纹 ${"%.3f".format(score)} 阈值 ${"%.2f".format(threshold)} " +
                (if (ok) "通过" else "拒绝"),
        )
        return ok
    }

    /**
     * Enter the listening phase: the microphone is already open, the VAD
     * starts cutting the user's request out of it, and the window starts
     * running. No Activity is opened — the whole point is that the user
     * stays wherever they were.
     */
    private fun startConversation() {
        if (conversationState != ConversationState.IDLE) return
        conversationState = ConversationState.LISTENING
        conversationDeadline = SystemClock.elapsedRealtime() + conversationWindowMs
        conversationListeningSince = SystemClock.elapsedRealtime()
        voiceprintGraceUsed = 0
        // A fresh wake starts a fresh listening phase: no earlier renewal or
        // deadline exemption carries over.
        ownerVerifiedInWindow = false
        voiceprintExemptSegment = false
        // [T-music-pause-on-voice] 音乐会被麦克风录进去，也会盖住「我在」和
        // 用户的请求：整段语音对话期间暂停，回到待命时再恢复。
        suspendPlaybackForConversation()
        // Every cycle starts with a clean echo window — the previous cycle's
        // acknowledgement must not make this cycle's first sentence look
        // like the assistant talking to itself.
        ackStartedAt = 0L
        ackEndedAt = 0L
        echoDeadline = 0L
        ackText = ""
        replyText = ""
        // The VAD is armed **immediately**. Arming it after the
        // acknowledgement would swallow the request: the user answers the
        // wake word while "我在" is still playing, and that audio is gone.
        // The assistant's own voice is filtered out by the echo window
        // instead (see [acknowledge] / [onUtterance]).
        installVad()
        // Set the acknowledgement markers BEFORE arming the VAD and before
        // launching the coroutine: on a busy scheduler acknowledge() can be
        // delayed, and any TTS frame arriving in that gap would otherwise
        // be cut into the user's request and drag its voiceprint down.
        ackStartedAt = SystemClock.elapsedRealtime()
        ackPlaying = WakeWordSettings.toneEnabled.value
        publish(VoiceWakePhase.LISTENING, "正在聆听")
        AppLogger.info(TAG, "进入聆听状态 窗口=${conversationWindowMs / 1000}s")
        scope.launch { acknowledge() }
    }

    /**
     * Bind the TTS engine while the service waits for the wake word.
     *
     * Android's TTS engine is a separate process and a cold start costs
     * one to four seconds. Paying that at wake time means the
     * acknowledgement arrives *after* the user's request has already been
     * spoken — and the request is then thrown away with the echo guard.
     * Paying it here costs nothing: the engine is idle until it is needed.
     */
    private fun prewarmTts() {
        scope.launch {
            val speaker = com.openminis.app.speech.TextToSpeechManager()
            speaker.init(applicationContext)
            tts = speaker
            // [T-android-voice-synthesis-settings] Wake replies now speak
            // through ReadAloudPlayer; build its engine here too so the first
            // reply does not pay the 1-4 s cold bind.
            ensureReadAloud()
            val ready = withContext(Dispatchers.IO) { speaker.awaitReady() }
            AppLogger.info(TAG, "TTS 预热完成 ready=$ready")
        }
    }

    /**
     * Load the 28 MB speaker model while waiting for a wake word.
     *
     * Wake-time verification sits between the keyword and "我在"; loading
     * the model on that path would add seconds to the first wake after
     * every service start. Only prewarm when a template exists — otherwise
     * the first wake is enrolling, not verifying.
     */
    private fun prewarmVoiceprint() {
        if (!WakeWordSettings.voiceprintEnabled.value) return
        if (!voiceprint.hasTemplate()) return
        scope.launch(Dispatchers.IO) {
            val ready = voiceprint.ensureReady()
            AppLogger.info(TAG, "声纹模型预热完成 ready=$ready")
        }
    }

    /**
     * Say "我在" so the user knows the assistant is listening.
     *
     * The old behaviour was a 120 ms beep from `ToneGenerator`. A word is
     * unmistakable — you can tell from the next room whether the wake
     * word was heard — and it is spoken by the same engine that will
     * answer, so it also proves the TTS works before the request starts.
     */
    private suspend fun acknowledge(): Boolean {
        if (!WakeWordSettings.toneEnabled.value) {
            ackPlaying = false
            return false
        }
        val speaker = tts ?: com.openminis.app.speech.TextToSpeechManager().also {
            it.init(applicationContext)
            tts = it
        }
        val text = runCatching { getString(R.string.voice_wake_acknowledgement) }
            .getOrDefault(DEFAULT_ACKNOWLEDGEMENT)
        ackText = text
        AppLogger.info(TAG, "开始播报「$text」")
        val spoken = runCatching { WakeConversation.speakQuietly(speaker, text) }
            .getOrElse {
                AppLogger.info(TAG, "唤醒回应失败：${it.message}")
                false
            }
        ackPlaying = false
        ackEndedAt = SystemClock.elapsedRealtime()
        // Discard any segment the VAD opened from the very first TTS frame
        // before the gate took effect. Gating continues through echoDeadline
        // so the room's tail ring cannot start a request either.
        vad?.pause()
        // The room keeps ringing for a moment after the engine stops;
        // anything the microphone picked up until now is the assistant's
        // own voice.
        echoDeadline = ackEndedAt + ECHO_TAIL_MS
        AppLogger.info(TAG, "播报结束 spoken=$spoken 用时=${ackEndedAt - ackStartedAt}ms")
        // The listening window starts when the assistant can actually hear
        // the user: the acknowledgement itself is not listening time.
        if (conversationState == ConversationState.LISTENING) {
            conversationDeadline = ackEndedAt + conversationWindowMs
        }
        return spoken
    }

    private fun installVad() {
        val detector = WakeEnergyVad(
            onSpeechStart = {
                AppLogger.info(TAG, "检测到说话")
                VoiceWakeState.trace(this, "VAD 开始说话")
            },
            onSegment = { pcm ->
                AppLogger.info(TAG, "VAD 切出一句 ${pcm.size * 1000 / WakeAudioCapture.SAMPLE_RATE}ms")
                VoiceWakeState.trace(this, "VAD 切句 ${pcm.size * 1000 / WakeAudioCapture.SAMPLE_RATE}ms")
                scope.launch { onUtterance(pcm) }
            },
            onEvent = { line ->
                AppLogger.info(TAG, line)
                VoiceWakeState.trace(this, line)
            },
            // [T-voice-request-silence] A request is ended by the speaker
            // going quiet, not by a wall clock. Natural mid-sentence pauses
            // of half a second are normal in dictation; the old 600 ms cut
            // sent only the first half of a long request to the model. The
            // 8 s conversation deadline behaves the same way: it only expires
            // during silence (see handleConversationTick).
            minSilenceMs = { REQUEST_SILENCE_MS },
            maxSegmentMs = { REQUEST_MAX_SEGMENT_MS },
        )
        vad = detector
        engine?.onFrame = { frame, len ->
            // The microphone stays open for the whole conversation, but the
            // frames carrying the assistant's own "我在" are not the user's
            // request: feeding them made the request segment start with TTS
            // and fail the voiceprint (device log 2026-11-26: wake 0.55,
            // request 0.10). Feeding resumes the moment the acknowledgement
            // stops — NOT at echoDeadline, whose extra 700 ms silence ate
            // the first syllables of a user who answers immediately
            // (device log 11:15:52 wake → 11:15:54 first VAD frame).
            if (!ackPlaying) detector.feed(frame, len)
        }
    }

    private fun removeVad() {
        engine?.onFrame = null
        vad?.reset()
        vad = null
    }

    /**
     * Listen for "停一下 / 别说了" while a reply is playing.
     *
     * The microphone stays open during the reply (the engine is not stopped),
     * so the only missing piece is a VAD on the same frames. Barge-in cuts
     * shorter than a normal request: the audio is dominated by the
     * assistant's own voice, and the user expects the interruption to land
     * within a second or two.
     *
     * A wake-word hit in this window is deliberately dropped ([onWakeHit]):
     * that is the assistant's own voice leaking back into the microphone,
     * not the user — only the explicit stop phrase acts.
     */
    private fun installBargeInVad(reply: String) {
        val detector = WakeEnergyVad(
            onSpeechStart = {},
            onSegment = { pcm ->
                scope.launch { onBargeInSegment(pcm, reply) }
            },
            minSilenceMs = { BARGE_IN_SILENCE_MS },
            maxSegmentMs = { BARGE_IN_MAX_SEGMENT_MS },
        )
        bargeInVad = detector
        engine?.onFrame = { frame, len -> detector.feed(frame, len) }
    }

    private fun removeBargeInVad() {
        if (bargeInVad != null) engine?.onFrame = null
        bargeInVad?.reset()
        bargeInVad = null
    }

    /**
     * [T-voice-single-speak] 等端侧 announce 播完。
     *
     * 麦克风在这段时间里**没有关**：[installBargeInVad] 一直在跑，用户
     * 随时可以打断。这里等的是「播报结束」这个时机，播报一停（正常播完
     * 或被关键词打断）就立刻返回，回到普通聆听。
     *
     * 不用拍脑袋的固定延时 —— announce 是延迟起播（1.2 秒），一篇新闻
     * 摘要能念近一分钟。
     *
     * @return true 表示播报确实起播了；false 表示压根没播起来（TTS
     *   引擎失败，或它在我们赶到之前就已经播完了）。
     */
    private suspend fun awaitAnnouncePlaybackEnd(): Boolean {
        val started = withTimeoutOrNull(ANNOUNCE_START_WAIT_MS) {
            VoiceOutputState.isSpeaking.first { it }
        } ?: return false
        withTimeoutOrNull(ANNOUNCE_FINISH_WAIT_MS) {
            VoiceOutputState.isSpeaking.first { !it }
        }
        return started
    }

    /**
     * A segment captured while the assistant was speaking.
     *
     * The assistant's own voice is in the same microphone stream, so a
     * plain transcript is not enough: the match only acts on a short
     * command, and a transcript that is merely a fragment of the reply is
     * ignored as echo.
     */
    private suspend fun onBargeInSegment(pcm: ShortArray, reply: String) {
        if (destroying || bargeInBusy) return
        // [T-tts-barge-in] Two contexts install this detector: the wake
        // conversation while its own reply plays (REPLYING), and the idle
        // service while any read-aloud channel is audible. Both must accept
        // a stop command; every other state ignores the segment.
        val replying = conversationState == ConversationState.REPLYING
        val idleReadAloud = conversationState == ConversationState.IDLE
        if (!replying && !idleReadAloud) return
        if (!VoiceOutputState.isSpeaking.value) return
        bargeInBusy = true
        try {
            // No voiceprint gate here: the segment is a mixture of the
            // assistant's own voice and the user talking over it, and a
            // stop command is a local control, not a request that needs the
            // owner's identity. Echo is rejected by the reply-substring
            // check below instead.
            val raw = transcribeOffline(pcm) ?: return
            val text = WakeEcho.strip(
                raw,
                WakeWordSettings.wakeWordList(),
                ack = ackText,
            ) ?: return
            if (text.isBlank()) return
            // In the wake conversation the reference is the whole reply; for
            // an idle read-aloud channel it is the sentence currently on the
            // speaker, published by the player that owns the capsule.
            val echoSource = if (replying) reply else VoiceOutputState.currentSpeechText.value
            val normalized = WakeStopPhrase.normalize(text)
            if (normalized.length >= 2 &&
                WakeStopPhrase.normalize(echoSource).contains(normalized)
            ) {
                AppLogger.info(TAG, "忽略播报回声「$text」")
                return
            }
            if (!WakeStopPhrase.matches(text)) {
                AppLogger.info(TAG, "播报中听到「$text」，非停止指令")
                return
            }
            AppLogger.info(TAG, "检测到语音停止指令「$text」，中止播报")
            VoiceWakeState.trace(this, "停止播报：$text")
            stopSpeakingNow()
        } finally {
            bargeInBusy = false
        }
    }

    /**
     * Silence the reply. [handleUtterance] notices [speechStopRequested] when
     * its blocking speak returns and goes straight back to listening without
     * the usual echo guard.
     */
    private fun stopSpeakingNow() {
        speechStopRequested = true
        runCatching { tts?.stop() }
        // [T-android-voice-synthesis-settings] Wake replies may now be playing
        // through ReadAloudPlayer (cloud or system voice).
        runCatching { readAloud?.stop() }
        // [T-tts-barge-in] The cloud reply channel and local assistant
        // announcements play through ReadAloudPlayer, which this service's
        // own TTS cannot reach. Stop them all so the spoken stop command
        // silences whichever channel is audible.
        runCatching { VoiceOutputState.stopAllPlayers() }
        VoiceOutputState.isSpeaking.value = false
        VoiceOutputState.currentSpeechText.value = ""
        AppLogger.info(TAG, "停止播报")
    }

    /**
     * One tick of the conversation state machine.
     *
     * The window that closes the conversation only runs while we are waiting
     * for the user: while the assistant is transcribing, thinking or talking,
     * the clock does not matter.
     */
    private suspend fun handleConversationTick(
        now: Long,
        enabled: Boolean,
        permission: Boolean,
    ) {
        // One diagnostic line per second: without it there is no way to tell
        // "the microphone was deaf" from "the user did not speak" from
        // "the voiceprint rejected the sentence" once the log has rotated.
        if (now - lastDiagAt >= 1_000L) {
            lastDiagAt = now
            val detector = vad
            val line = buildString {
                append("状态=")
                append(conversationState.name)
                append(" 麦克风=")
                append("%.0f".format(detector?.lastRms ?: 0.0))
                append(" VAD峰值=")
                append("%.0f".format(detector?.peakRms ?: 0.0))
                append(" 阈值=")
                append("%.0f".format(detector?.threshold ?: 0.0))
                append(" 底噪=")
                append("%.0f".format(detector?.floor ?: 0.0))
                append(if (detector?.isInSpeech == true) " 正在说话" else " 静音")
                append(" 帧=")
                append(detector?.frameCount ?: 0)
                append(" 剩余=")
                append(((conversationDeadline - now) / 1000).coerceAtLeast(0))
                append("s")
                if (conversationState == ConversationState.LISTENING) {
                    // Only meaningful inside a listening phase; VERIFYING、
                    // TRANSCRIBING 期间这个起点已经过期。
                    append(" 聆听=")
                    append((now - conversationListeningSince) / 1000)
                    append("s")
                }
            }
            AppLogger.info(TAG, line)
            VoiceWakeState.trace(this, line)
        }
        when {
            !enabled -> {
                abortConversation("功能已关闭")
                stopEngine()
                publish(VoiceWakePhase.OFF, "语音唤醒已关闭")
            }
            !permission -> {
                abortConversation("麦克风权限被收回")
                publish(VoiceWakePhase.ERROR, "需要麦克风权限")
            }
            conversationState == ConversationState.LISTENING &&
                WakeListenWindowPolicy.listeningExpired(conversationListeningSince, now) -> {
                AppLogger.info(TAG, "聆听已达 ${WakeListenWindowPolicy.MAX_LISTENING_MS / 1000}s 上限，回到待命")
                VoiceWakeState.trace(this, "聆听上限到点，回到待命")
                sleepConversation()
            }
            conversationState == ConversationState.LISTENING && now > conversationDeadline -> {
                // A segment may have been cut and handed to the recogniser
                // just before the clock ran out. Let that transcription
                // finish before deciding the window failed: otherwise the
                // sleep below flips the state and handleUtterance() throws
                // away a sentence that was already being understood
                // (device log 2026-11-26 10:29: 「识别期间状态已变，丢弃 4580ms」).
                // Bounded by the transcriber's own timeout/retry budget —
                // the extra deadline here only covers a stuck callback.
                if (utteranceBusy || pendingUtterances.isNotEmpty()) {
                    if (now < conversationDeadline + WINDOW_TRANSCRIBE_GRACE_MS) return
                    AppLogger.info(TAG, "识别超时未返回，窗口到点按失败处理")
                }
                // [T-voice-window-voiceprint] The window is a SILENCE window,
                // but only the OWNER's voice renews it. e2c0667 renewed on
                // `isInSpeech` alone, and energy VAD calls a fan or a TV
                // "speech" — the window then renewed itself forever (device
                // log 2026-11-22: 138 次「正在说话」, 30 s → 5 min). The
                // expiry runs the voiceprint once on the buffered tail:
                // pass renews, "could not judge" gets a bounded grace.
                //
                // [T-voice-wake-sleep-on-fail] Owner, 2026-11-22: a failed
                // verdict sleeps right away. 702e429 used to hand the buffered
                // segment over once and add FLUSH_GRACE_MS on top, so the
                // music playing in the room was flushed into the chat and the
                // microphone stayed open for 28–48 s (log 16:58:21 /
                // 16:59:39 「唤醒命令进入当前会话」). A long sentence is kept
                // alive by the Renew branch, so nothing is delivered here.
                val detector = vad
                if (!WakeWordSettings.voiceprintEnabled.value) {
                    // A user who turned the voiceprint off keeps the
                    // pre-e2c0667 flush/sleep behaviour, with the hard cap
                    // as the backstop.
                    if (detector?.flush() == true) {
                        conversationDeadline = now + FLUSH_GRACE_MS
                        AppLogger.info(TAG, "窗口到点，但 VAD 里还有半句，先处理")
                    } else {
                        sleepConversation()
                    }
                    return
                }
                val tail = detector?.currentSegmentTailPcm(WakeListenWindowPolicy.RENEW_TAIL_MS)
                val score = if (tail != null) {
                    withContext(Dispatchers.IO) {
                        synchronized(voiceprintLock) { voiceprint.verify(tail) }
                    }
                } else {
                    null
                }
                if (conversationState != ConversationState.LISTENING) return // 判定期间状态已变
                val threshold = WakeWordSettings.voiceprintThreshold.value
                when (val d = WakeListenWindowPolicy.decide(
                    hasSpeech = tail != null,
                    score = score,
                    threshold = threshold,
                    graceUsed = voiceprintGraceUsed,
                )) {
                    is WakeListenWindowPolicy.Decision.Renew -> {
                        conversationDeadline = now + conversationWindowMs
                        voiceprintGraceUsed = 0
                        // The owner is talking in this phase. Remember it: a
                        // noisy room can keep the sentence from ever being
                        // cut, and the next deadline may only see the room
                        // in its tail (see [ownerVerifiedInWindow]).
                        ownerVerifiedInWindow = true
                        AppLogger.info(
                            TAG,
                            "声纹续期 score=%.2f 阈值=%.2f，再听 ${conversationWindowMs / 1000}s".format(
                                d.score,
                                threshold,
                            ),
                        )
                        VoiceWakeState.trace(this, "声纹续期 ${"%.2f".format(d.score)}")
                    }
                    is WakeListenWindowPolicy.Decision.NullGrace -> {
                        voiceprintGraceUsed = d.attempt
                        conversationDeadline = now + conversationWindowMs
                        AppLogger.info(
                            TAG,
                            "声纹无法校验（第 ${d.attempt}/${WakeListenWindowPolicy.MAX_VOICEPRINT_GRACE} 次），" +
                                "再给一个 ${conversationWindowMs / 1000}s 聆听窗口",
                        )
                        VoiceWakeState.trace(this, "声纹无法校验 ${d.attempt}")
                    }
                    WakeListenWindowPolicy.Decision.Sleep -> {
                        val bufferedMs = detector?.bufferedSegmentMs ?: 0
                        when {
                            tail == null -> {
                                AppLogger.info(TAG, "窗口到点，没有本人语音，回到待命")
                                VoiceWakeState.trace(this, "窗口到点，无本人语音，休眠")
                            }
                            score == null -> {
                                AppLogger.info(
                                    TAG,
                                    "窗口到点，声纹仍无法校验（宽限已用尽 " +
                                        "${WakeListenWindowPolicy.MAX_VOICEPRINT_GRACE} 次），" +
                                        "丢弃缓冲 ${bufferedMs}ms，回到待命",
                                )
                                VoiceWakeState.trace(this, "窗口到点，声纹无法校验，丢弃 ${bufferedMs}ms")
                            }
                            else -> {
                                // The tail failed, but this phase already
                                // verified the owner earlier. In a noisy car
                                // the VAD never ends the sentence, so the
                                // tail at the deadline is road noise while
                                // the real request sits earlier in the same
                                // buffer (device log 2026-11-26 13:01–13:16:
                                // renew 0.81 → deadline 0.18, five times).
                                // Hand the buffered request over instead of
                                // discarding it; the same sentences transcribe
                                // fine with the voiceprint switch off.
                                if (ownerVerifiedInWindow && detector?.flush() == true) {
                                    voiceprintExemptSegment = true
                                    conversationDeadline = now + FLUSH_GRACE_MS
                                    AppLogger.info(
                                        TAG,
                                        "窗口到点，尾部声纹 %.2f 未通过但本窗口已确认本人，交付缓冲 %dms".format(
                                            score,
                                            bufferedMs,
                                        ),
                                    )
                                    VoiceWakeState.trace(
                                        this,
                                        "窗口到点，本人已确认，交付 ${bufferedMs}ms",
                                    )
                                    return
                                }
                                AppLogger.info(
                                    TAG,
                                    "窗口到点，声纹未通过 score=%.2f/阈值=%.2f，丢弃缓冲 %dms，回到待命".format(
                                        score,
                                        threshold,
                                        bufferedMs,
                                    ),
                                )
                                VoiceWakeState.trace(
                                    this,
                                    "窗口到点，声纹 ${"%.2f".format(score)} 未通过，丢弃 ${bufferedMs}ms",
                                )
                            }
                        }
                        sleepConversation()
                    }
                }
            }
            conversationState == ConversationState.VERIFYING && now > conversationDeadline -> {
                // The voiceprint verdict never came back — its coroutine
                // was lost or stuck. Fall back to standby instead of
                // parking in VERIFYING forever with no way to wake again.
                conversationState = ConversationState.IDLE
                AppLogger.info(TAG, "声纹校验超时，回到待命")
                VoiceWakeState.trace(this, "声纹校验超时，回到待命")
                publish(VoiceWakePhase.WAITING, "等待唤醒词「${WakeWordSettings.wakeWord.value}」")
                engine?.setDetecting(true)
            }
        }
    }

    /**
     * A finished utterance.
     *
     * The voiceprint decides whether this was the owner. A stranger's
     * sentence is dropped and the assistant keeps waiting — if nothing
     * passes the gate before the window closes, it goes back to sleep.
     */
    private suspend fun onUtterance(pcm: ShortArray) {
        if (conversationState != ConversationState.LISTENING || destroying) return
        // One turn at a time: the transcription takes a second or two, and a
        // second segment cut in the meantime would race it for the recogniser.
        // Queue it instead of dropping it: the first segment is usually the
        // assistant's own "我在", and the queued one is the actual request.
        if (utteranceBusy) {
            if (pendingUtterances.size >= MAX_PENDING_UTTERANCES) {
                // [T-voice-wake-queue-log] The queue used to drop the oldest
                // segment silently, so a lost sentence left no trace at all
                // and the transcript simply skipped a request. Say it out
                // loud — a dropped utterance is a bug the user has to see.
                val dropped = pendingUtterances.removeFirst()
                val droppedMs = dropped.size * 1000 / WakeAudioCapture.SAMPLE_RATE
                AppLogger.info(TAG, "语音队列已满，丢弃最早一句 ${droppedMs}ms")
                VoiceWakeState.trace(this, "语音队列已满，丢弃最早一句 ${droppedMs}ms")
            }
            pendingUtterances.addLast(pcm)
            AppLogger.info(TAG, "上一句还在处理，先排队（${pendingUtterances.size}）")
            VoiceWakeState.trace(this, "语音排队 ${pendingUtterances.size}")
            return
        }
        utteranceBusy = true
        try {
            var segment: ShortArray? = pcm
            while (segment != null &&
                conversationState == ConversationState.LISTENING &&
                !destroying
            ) {
                handleUtterance(segment)
                segment = if (conversationState == ConversationState.LISTENING) {
                    pendingUtterances.removeFirstOrNull()
                } else {
                    null
                }
            }
        } finally {
            utteranceBusy = false
            // Anything left was captured for a state that no longer listens.
            pendingUtterances.clear()
        }
    }

    private suspend fun handleUtterance(pcm: ShortArray) {
        if (conversationState != ConversationState.LISTENING || destroying) {
            // [T-voice-wake-queue-log] The audio was captured but the state
            // moved on before it could be transcribed (the window closed, or
            // the assistant started replying). This used to return silently,
            // so the transcript simply skipped a request with no trace of it
            // anywhere — the device log showed a captured segment with no
            // matching recognition line at all.
            val ms = pcm.size * 1000 / WakeAudioCapture.SAMPLE_RATE
            AppLogger.info(TAG, "收到片段但当前不处于聆听状态，丢弃 ${ms}ms")
            VoiceWakeState.trace(this, "收到片段但状态已变，丢弃 ${ms}ms")
            return
        }
        val now = SystemClock.elapsedRealtime()
        // The segment was cut while the assistant was still saying "我在" —
        // the audio carries the assistant's own voice.
        //
        // It used to be thrown away here, and that is exactly why "唤醒后说
        // 话没有反应": the user starts talking while the acknowledgement is
        // still in the air, so the *request* was the segment that got
        // dropped. Now the segment is transcribed, the echo is stripped from
        // the transcript, and only a segment that contains nothing but the
        // echo is ignored.
        // `echoDeadline` is only known once the acknowledgement has ended;
        // a segment cut *while* it is still playing (the user starts talking
        // over "我在") must be in the echo window too. Otherwise the
        // assistant's own words are sent back to it as a request.
        val overlapsAck = ackStartedAt > 0L && (ackPlaying || now < echoDeadline)
        val raw = transcribeOffline(pcm)
        // [T-voice-wake-sleep-on-fail] The listening window may have closed
        // while this segment was being transcribed (voiceprint failed →
        // standby), or the user may already have woken a fresh conversation.
        // Audio from an older listening phase must not resurrect a
        // conversation or hijack the new one.
        if (conversationState != ConversationState.LISTENING || destroying) {
            val ms = pcm.size * 1000 / WakeAudioCapture.SAMPLE_RATE
            AppLogger.info(TAG, "识别期间状态已变，丢弃 ${ms}ms")
            VoiceWakeState.trace(this, "识别期间状态已变，丢弃 ${ms}ms")
            return
        }
        if (raw.isNullOrBlank()) {
            AppLogger.info(TAG, "识别结果为空，继续聆听")
            VoiceWakeState.trace(this, "识别结果为空，继续聆听")
            // The VAD is still armed and the state is still LISTENING, so
            // the next sentence is picked up right away. The window is NOT
            // renewed: an empty transcript is not a voice, so the original
            // deadline stands ("8 秒内没有通过声纹的语音就休眠").
            return
        }
        val withoutWake = WakeEcho.strip(raw, WakeWordSettings.wakeWordList())
        if (overlapsAck &&
            (WakeEcho.isOnly(raw, ackText) || WakeEcho.isOnly(withoutWake, ackText))
        ) {
            AppLogger.info(TAG, "只有助手自己的回应「$raw」，继续聆听")
            VoiceWakeState.trace(this, "只有助手自己的回应")
            return
        }
        // A segment released by the deadline after this phase already
        // verified the owner (see [voiceprintExemptSegment]): its edges are
        // room noise, so a whole-segment re-check here would drop exactly the
        // request the deadline path just saved.
        val voiceprintExempt = voiceprintExemptSegment
        voiceprintExemptSegment = false
        if (!voiceprintExempt && !passesVoiceprint(pcm)) {
            AppLogger.info(TAG, "声纹不通过，忽略这句话")
            VoiceWakeState.trace(this, "声纹不通过，忽略")
            publish(VoiceWakePhase.LISTENING, "等待本人说话")
            return
        }
        // The voiceprint check can take a moment too; do not clobber a state
        // that moved on in the meantime (standby or a new wake).
        if (conversationState != ConversationState.LISTENING || destroying) {
            val ms = pcm.size * 1000 / WakeAudioCapture.SAMPLE_RATE
            AppLogger.info(TAG, "声纹判定期间状态已变，丢弃 ${ms}ms")
            VoiceWakeState.trace(this, "声纹判定期间状态已变，丢弃 ${ms}ms")
            return
        }

        conversationState = ConversationState.REPLYING
        // A request was accepted; queued segments were captured for the
        // listening phase and must not be answered after this turn.
        pendingUtterances.clear()
        removeVad()
        publish(VoiceWakePhase.REPLYING, "识别中")

        val text = WakeEcho.strip(raw, WakeWordSettings.wakeWordList(), ack = ackText)
        // The app may have come to the foreground (or the feature was
        // switched off) while the transcription was running.
        if (conversationState != ConversationState.REPLYING) return
        if (text.isNullOrBlank()) {
            AppLogger.info(TAG, "识别结果为空，继续聆听")
            resumeListening(renewWindow = false)
            return
        }
        AppLogger.info(TAG, "识别结果：$text")
        VoiceWakeState.trace(this, "识别：$text")

        // 如果当前有一张本地写操作确认卡，这句话可能是「确认/取消」。
        // 必须先问桥：否则唤醒链路会把它当成新消息发到语音会话，卡片仍挂着。
        val bridgeResolution = com.openminis.app.assistant.execution.LocalConfirmationBridge
            .resolve(text)
        if (bridgeResolution != null) {
            // 结果播报由卡片所在会话负责（「已创建日程…」「已取消」）。
            // 这里只给一声很短的应答，避免两个 TTS 实例同时说话。
            val phrase = when (bridgeResolution) {
                com.openminis.app.assistant.execution.LocalConfirmationBridge.Resolution.CONFIRM ->
                    "好"
                com.openminis.app.assistant.execution.LocalConfirmationBridge.Resolution.CANCEL ->
                    null
            }
            AppLogger.info(TAG, "确认卡由唤醒语音处理：$bridgeResolution")
            VoiceWakeState.trace(this, "确认卡：$bridgeResolution")
            publish(VoiceWakePhase.REPLYING, if (phrase != null) "已确认" else "已取消")
            if (phrase != null) {
                val speaker = tts ?: com.openminis.app.speech.TextToSpeechManager().also {
                    it.init(applicationContext)
                    tts = it
                }
                WakeConversation.speakQuietly(speaker, phrase)
            }
            if (conversationState == ConversationState.REPLYING) {
                delay(ECHO_GUARD_MS)
                resumeListening()
            }
            return
        }

        val sessionId = runCatching { WakeConversation.ensureSession(this) }.getOrNull()
        if (sessionId == null) {
            AppLogger.info(TAG, "无法创建语音会话，放弃本轮")
            sleepConversation()
            return
        }

        publish(VoiceWakePhase.REPLYING, "思考中")
        // [T-voice-single-speak] 一轮语音只播一次。快路径命中时端侧 announce
        // 会播真实结果，这时模型返回的文本（快路径写进聊天的气泡标题）就
        // 不该再念一遍。标记的粒度是「这一轮 ask()」。
        VoiceTurnSpeech.beginTurn()
        // [T-voice-single-speak] endTurn() 会把「本轮已播报」的标记清掉，
        // 所以让它把标记交回来 —— 事后再去读就永远是 false，抑制分支形同
        // 虚设。
        var announcedThisTurn = false
        val reply = try {
            WakeConversation.ask(this, sessionId, text)
        } finally {
            announcedThisTurn = VoiceTurnSpeech.endTurn()
        }
        if (conversationState != ConversationState.REPLYING) return

        // [T-voice-single-speak] 端侧已经播过真实结果 → 抑制模型文本的
        // 朗读，只播一次。
        //
        // 这个判断必须在「回复为空」之前：快路径命中时 ask() 经常返回空
        // （结果已经在端侧播了，模型没有正文可回）。先按空回复处理的话，
        // 这里会直接 sleepConversation() 退出对话轮次，而 announce 还要
        // 1.2 秒才起播 —— 播报期间没有 barge-in，停止词最长 60 秒失效。
        if (announcedThisTurn) {
            AppLogger.info(TAG, "端侧已播报结果，抑制模型文本朗读")
            VoiceWakeState.trace(this, "voice: speak suppressed reason=announce-already-spoke")
            replyText = reply.orEmpty()
            // 上一轮的停止标记不能带到这一轮（否则日志会误报「被中止」）。
            speechStopRequested = false
            // [T-voice-single-speak] barge-in 照旧装上：播报期间麦克风一直
            // 开着，用户随时可以说「停一下 / 别说了」把这句打断 —— 这条
            // 保护不能因为抑制了模型朗读就丢掉。
            installBargeInVad(replyText)
            // 等播报**结束**再回到普通聆听：播完，或者被停止词打断，都会
            // 立刻返回。等的不只是延迟起播（1.2 秒），新闻摘要能念近一分
            // 钟 —— 这期间断开的是普通 VAD，不是麦克风。
            val spoke = awaitAnnouncePlaybackEnd()
            removeBargeInVad()
            if (spoke) {
                if (speechStopRequested) {
                    AppLogger.info(TAG, "端侧播报被用户中止，直接回到聆听")
                }
                if (conversationState != ConversationState.REPLYING) return
                resumeListening()
                return
            }
            // announce 根本没播出来（TTS 引擎失败、被静音丢弃）：没有重复
            // 风险，回退到下面的语音通道念正文。
            AppLogger.info(TAG, "端侧播报未开始，回退朗读正文")
            VoiceWakeState.trace(this, "voice: announce never started")
            delay(ECHO_GUARD_MS)
        }

        if (reply.isNullOrBlank()) {
            AppLogger.info(TAG, "本轮没有拿到可朗读的回复")
            VoiceWakeState.trace(this, "本轮没有回复")
            sleepConversation()
            return
        }

        publish(VoiceWakePhase.REPLYING, "回复中")
        VoiceWakeState.trace(this, "回复：${reply.take(80)}")
        replyText = reply
        speechStopRequested = false
        // [T-voice-single-speak] 只念最终答案：快路径的气泡标题（「✅
        // Weather」）是给眼睛看的，念出来既没信息量又和端侧播报重复。
        // 剥完不剩什么就不念，保持安静。
        val spoken = VoiceSpeechText.speakable(reply)
        if (spoken.isBlank()) {
            AppLogger.info(TAG, "回复只有气泡标题，没有可朗读的正文")
            VoiceWakeState.trace(this, "voice: speak suppressed reason=title-only")
            if (conversationState != ConversationState.REPLYING) return
            resumeListening()
            return
        }
        installBargeInVad(spoken)
        // [T-android-voice-synthesis-settings] Provider-first, system fallback,
        // blocking until the audio ends — the same routing chat read-aloud
        // uses, so the Settings choice governs wake replies too.
        val player = ensureReadAloud()
        val spoke = if (player != null) {
            // Same 600-character trim as the old wake narration: a background
            // conversation should not monologue for minutes.
            runCatching { player.speakBlocking(spoken.take(WakeConversation.MAX_SPEECH_CHARS)) }
                .onFailure { AppLogger.info(TAG, "朗读播放失败：${it.message}") }
                .getOrDefault(false)
        } else {
            // Last resort: the service's own engine, exactly as before.
            val speaker = tts ?: com.openminis.app.speech.TextToSpeechManager().also {
                it.init(applicationContext)
                tts = it
            }
            WakeConversation.speak(speaker, spoken)
        }
        removeBargeInVad()
        if (!spoke) {
            AppLogger.info(TAG, "回复未能播报，继续聆听")
            VoiceWakeState.trace(this, "回复未能播报")
        }
        if (conversationState != ConversationState.REPLYING) return

        // The window is renewed after the reply: the user may well have
        // more to say.
        if (speechStopRequested) {
            AppLogger.info(TAG, "播报被用户中止，直接回到聆听")
        } else {
            delay(ECHO_GUARD_MS)
        }
        resumeListening()
    }

    private fun resumeListening() {
        resumeListening(renewWindow = true)
    }

    /**
     * Back to listening.
     *
     * @param renewWindow when false the original sleep-window deadline
     *   stands — the turn produced nothing, so the conversation must still
     *   end there.
     */
    private fun resumeListening(renewWindow: Boolean) {
        if (conversationState != ConversationState.REPLYING) return
        conversationState = ConversationState.LISTENING
        // A reply ends the previous listening phase: the hard cap and the
        // voiceprint grace budget start over for the next one.
        conversationListeningSince = SystemClock.elapsedRealtime()
        voiceprintGraceUsed = 0
        // The reply closed the previous listening phase; its voiceprint
        // evidence and any unused exemption do not apply to the next one.
        ownerVerifiedInWindow = false
        voiceprintExemptSegment = false
        if (renewWindow) {
            conversationDeadline = SystemClock.elapsedRealtime() + conversationWindowMs
        }
        installVad()
        publish(VoiceWakePhase.LISTENING, "正在聆听")
        AppLogger.info(TAG, "回到聆听 窗口=${conversationWindowMs / 1000}s 续期=$renewWindow")
    }

    /**
     * The voiceprint gate.
     *
     * @return true when the utterance may be acted on. A gate that cannot
     *   run (no model, no template, audio too short) lets the utterance
     *   through — a soft gate must never lock the owner out.
     */
    private fun passesVoiceprint(pcm: ShortArray): Boolean {
        if (!WakeWordSettings.voiceprintEnabled.value) return true
        val gate = voiceprint
        if (!gate.hasTemplate()) {
            // First utterance: it becomes the owner template.
            gate.enrollIfAbsent(pcm, ENROLL_MIN_SECONDS)
            return true
        }
        val score = synchronized(voiceprintLock) { gate.verify(pcm) } ?: return true
        val threshold = WakeWordSettings.voiceprintThreshold.value
        val ok = score >= threshold
        AppLogger.info(
            TAG,
            "声纹分数 ${"%.3f".format(score)} 阈值 ${"%.2f".format(threshold)} → " +
                (if (ok) "通过" else "拒绝"),
        )
        VoiceWakeState.trace(
            this,
            "声纹 ${"%.3f".format(score)} 阈值 ${"%.2f".format(threshold)} " +
                (if (ok) "通过" else "拒绝"),
        )
        return ok
    }

    /** Recognise the recorded request offline — no microphone needed. */
    private suspend fun transcribeOffline(pcm: ShortArray): String? {
        val wav = WavEncoder.encode(pcm)
        var attempt = 0
        while (attempt < 3) {
            val result = withTimeoutOrNull(TRANSCRIBE_TIMEOUT_MS) {
                kotlinx.coroutines.suspendCancellableCoroutine<String?> { cont ->
                    val started = SpeechRecognitionManager.transcribeAudio(
                        wav,
                        { text, isFinal ->
                            if (isFinal && cont.isActive) cont.resume(text) {}
                        },
                        { error, message ->
                            AppLogger.info(TAG, "离线识别失败：${error.name} ${message ?: ""}")
                            // Empty string, not null: null is reserved for
                            // the timeout below, so a genuine failure is
                            // never mistaken for one.
                            if (cont.isActive) cont.resume("") {}
                        },
                    )
                    if (!started && cont.isActive) {
                        AppLogger.info(
                            TAG,
                            "离线识别未启动：识别引擎状态=${SpeechRecognitionManager.state.value}",
                        )
                        cont.resume("") {}
                    }
                }
            }
            if (result == null) {
                // Only partials so far and the timeout ran out: cancel the
                // stuck recognition, then let the retry loop below decide
                // whether to try again or give up.
                AppLogger.info(TAG, "离线识别超时，取消识别")
                SpeechRecognitionManager.cancelRecording()
            }
            if (!result.isNullOrBlank()) return result
            // The recogniser refuses a second transcription while the
            // previous one has not settled yet. Wait for it and try again —
            // otherwise the request is silently lost and the assistant looks
            // unresponsive.
            if (SpeechRecognitionManager.state.value == RecognitionState.IDLE) return null
            AppLogger.info(TAG, "识别引擎忙，稍后重试")
            delay(400)
            attempt++
        }
        return null
    }

    /** The listening window closed without an accepted utterance: sleep. */
    private fun sleepConversation() {
        if (conversationState == ConversationState.IDLE) return
        conversationState = ConversationState.IDLE
        removeVad()
        removeBargeInVad()
        resumePlaybackAfterConversation()
        engine?.setDetecting(true)
        publish(VoiceWakePhase.WAITING, "等待唤醒词「${WakeWordSettings.wakeWord.value}」")
        AppLogger.info(TAG, "回到待命（聆听窗口 ${conversationWindowMs / 1000}s 内没有本人语音）")
    }

    /** Something interrupted the conversation (in-app voice input, disabled). */
    private fun abortConversation(reason: String) {
        if (conversationState == ConversationState.IDLE) return
        val wasReplying = conversationState == ConversationState.REPLYING
        conversationState = ConversationState.IDLE
        removeVad()
        removeBargeInVad()
        resumePlaybackAfterConversation()
        if (wasReplying) stopSpeakingNow()
        engine?.setDetecting(true)
        AppLogger.info(TAG, "结束语音对话：$reason")
    }

    /** Park the music player for the duration of the voice conversation. */
    private fun suspendPlaybackForConversation() {
        if (playbackSuspendedForConversation) return
        playbackSuspendedForConversation = true
        runCatching { VoiceOutputState.suspendAllForCapture() }
        AppLogger.info(TAG, "语音对话开始，暂停音乐")
    }

    /** The conversation ended however it ended: give the music back. */
    private fun resumePlaybackAfterConversation() {
        if (!playbackSuspendedForConversation) return
        playbackSuspendedForConversation = false
        runCatching { VoiceOutputState.resumeAllAfterCapture() }
        AppLogger.info(TAG, "语音对话结束，恢复音乐")
    }


    // ---------------------------------------------------------------- notification

    private fun publish(phase: VoiceWakePhase, detail: String) {
        VoiceWakeState.update(this, phase, detail)
    }

    private fun startForegroundSafely(notification: Notification): Boolean {
        return try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
            foregroundStarted = true
            true
        } catch (t: Throwable) {
            AppLogger.info(TAG, "startForeground 失败：${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    private fun buildNotification(): Notification {
        val snapshot = VoiceWakeState.state.value
        val text = when (snapshot.phase) {
            VoiceWakePhase.WAITING -> getString(R.string.voice_wake_notification_waiting, WakeWordSettings.wakeWord.value)
            VoiceWakePhase.WAKING -> getString(R.string.voice_wake_notification_waking)
            VoiceWakePhase.LISTENING -> getString(R.string.voice_wake_notification_listening)
            VoiceWakePhase.REPLYING -> getString(R.string.voice_wake_notification_replying)
            VoiceWakePhase.PAUSED -> snapshot.detail.ifBlank { getString(R.string.voice_wake_notification_paused) }
            VoiceWakePhase.ERROR -> snapshot.detail.ifBlank { getString(R.string.voice_wake_state_error) }
            VoiceWakePhase.OFF -> getString(R.string.voice_wake_state_off)
        }
        lastNotificationText = text
        val sessionId = WakeWordSettings.voiceSessionId.value
        val openUri = if (sessionId.isNullOrBlank()) {
            "minis://action/new_chat"
        } else {
            "minis://session/$sessionId"
        }
        val open = Intent(Intent.ACTION_VIEW, Uri.parse(openUri)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val openPending = PendingIntent.getActivity(
            this,
            0,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopPending = PendingIntent.getService(
            this,
            1,
            Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.voice_wake_notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openPending)
            .addAction(0, getString(R.string.voice_wake_notification_stop), stopPending)
            .build()
    }

    private fun updateNotification(snapshot: VoiceWakeSnapshot) {
        if (destroying || !foregroundStarted) return
        val text = when (snapshot.phase) {
            VoiceWakePhase.WAITING -> getString(R.string.voice_wake_notification_waiting, WakeWordSettings.wakeWord.value)
            VoiceWakePhase.WAKING -> getString(R.string.voice_wake_notification_waking)
            VoiceWakePhase.LISTENING -> getString(R.string.voice_wake_notification_listening)
            VoiceWakePhase.REPLYING -> getString(R.string.voice_wake_notification_replying)
            VoiceWakePhase.PAUSED -> snapshot.detail.ifBlank { getString(R.string.voice_wake_notification_paused) }
            VoiceWakePhase.ERROR -> snapshot.detail.ifBlank { getString(R.string.voice_wake_state_error) }
            VoiceWakePhase.OFF -> getString(R.string.voice_wake_state_off)
        }
        if (text == lastNotificationText) return
        lastNotificationText = text
        runCatching {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.voice_wake_notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = getString(R.string.voice_wake_notification_channel_desc)
                    setShowBadge(false)
                },
            )
        }
        if (nm.getNotificationChannel(ALERT_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    ALERT_CHANNEL_ID,
                    getString(R.string.voice_wake_alert_channel),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = getString(R.string.voice_wake_alert_channel)
                    setShowBadge(false)
                    setSound(null, null)
                },
            )
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "minis:voice-wake").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { AppLogger.info(TAG, "WakeLock 获取失败：${it.message}") }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
        wakeLock = null
    }
}

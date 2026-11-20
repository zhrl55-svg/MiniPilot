package com.openminis.app.speech

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Manages Android TextToSpeech engine for reading assistant responses aloud.
 *
 * [T-android-tts-locale GH#324] Language is resolved per utterance by
 * [TtsLocaleResolver]: an identifying script wins, else the user's in-app
 * language, else the system locale. This class used to support only en-US and
 * zh-CN, which meant every other shipped language was read aloud in American
 * English.
 */
class TextToSpeechManager : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "TextToSpeech"

        /**
         * [T-android-tts-silent-blackhole] Upper bound on waiting for the
         * engine to bind. Generous because low-end devices cold-start their
         * TTS service; a device with NO engine may never call onInit, and this
         * is what turns that into a detectable failure instead of a hang.
         */
        const val INIT_TIMEOUT_MS = 4_000L
        // [T-android-tts-intranumber-guard] The sentence-boundary set moved to
        // SpeechSentenceSplitter.SENTENCE_ENDERS — a single source of truth
        // shared with ReadAloudPlayer, so the two TTS paths can't drift.
    }

    // @Volatile: written on the binder thread in onInit / nulled on main in
    // shutdown(), and read from both — the posted pre-init replay relies on
    // seeing a torn-down engine rather than a stale non-null.
    @Volatile
    private var tts: TextToSpeech? = null

    /**
     * [T-android-tts-locale GH#324] Application context, kept for reading the
     * saved UI language. Application-scoped, so no Activity is leaked.
     */
    @Volatile
    private var appContext: Context? = null

    /**
     * 本次实例显式绑定的引擎包名（null = 交给系统默认）。
     *
     * 只用于 init 失败时判断「是不是我指定的那个起不来」，起不来就退回系统默认
     * 再试一次 —— 见 [onInit]。之后置 null，避免反复重试。
     */
    @Volatile
    private var preferredEngine: String? = null

    /**
     * [T-android-tts-locale GH#324] Set when a caller picked the language
     * explicitly via [setLanguage] — today `android-speak --voice <tag>`.
     *
     * Without this the explicit choice was silently discarded: SpeakOffloadHandler
     * calls `setLanguage(...)` and then `speak(text)`, and `speak` re-ran the
     * detector, overwriting the caller's locale microseconds later. So
     * `--voice es-ES` has never actually worked. Cleared by [stop]/[shutdown]
     * so one explicit utterance cannot pin every later one.
     */
    @Volatile
    private var explicitLocale: Locale? = null
    @Volatile
    var isInitialized = false
        private set

    /**
     * [T-android-tts-silent-blackhole] True when the engine reported ERROR at
     * init — typically a device with no functional TTS engine installed
     * (common on Chinese ROMs without Google TTS, e.g. ColorOS). Callers can
     * use this + [awaitReady] to distinguish "not ready YET" from "will never
     * speak", instead of silently dropping text.
     */
    var initFailed = false
        private set

    /**
     * Resolves once [onInit] runs: true on SUCCESS, false on ERROR. Engine
     * binding is asynchronous, so the first utterances can easily arrive
     * before this settles — see [preInitQueue].
     */
    private val initResult = kotlinx.coroutines.CompletableDeferred<Boolean>()

    /**
     * [T-android-tts-silent-blackhole] Utterances that arrived before init
     * settled. `speak()` used to DROP text whenever `!isInitialized` — since
     * init is async, the very first utterance after construction was silently
     * lost (LazyReadAloudPlayer constructs the engine on first tap, so the
     * first Read Aloud tap spoke nothing on every device). Buffered here and
     * replayed in order from [onInit] instead. Guarded by [queueLock];
     * `onInit` may arrive on a binder thread.
     */
    private val preInitQueue = mutableListOf<String>()
    private val queueLock = Any()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private var isPaused = false
    private var pendingTexts = mutableListOf<String>()
    private var pausedAtIndex = 0

    /**
     * Rolling buffer of partial-streaming text that hasn't hit a sentence
     * boundary yet. Cleared by [flush] or [stop]. See [appendText].
     */
    private val sentenceBuffer = StringBuilder()

    var speechRate: Float = 1.0f
        set(value) {
            field = value.coerceIn(0.1f, 3.0f)
            tts?.setSpeechRate(field)
        }

    var speechPitch: Float = 1.0f
        set(value) {
            field = value.coerceIn(0.5f, 2.0f)
            tts?.setPitch(field)
        }

    var speechVolume: Float = 1.0f
        set(value) {
            field = value.coerceIn(0f, 1f)
        }

    /**
     * [T-readaloud-volume-boost] Audio session the engine plays each utterance
     * on (KEY_PARAM_SESSION_ID), so the caller can attach effects to it; 0 =
     * the engine's own session.
     */
    var audioSessionId: Int = 0

    /**
     * Initializes the TTS engine. Must be called before speaking.
     */
    fun init(context: Context) {
        // [T-android-tts-locale GH#324] Retained so the per-utterance locale
        // resolution can read the user's in-app language choice. The manager
        // previously kept no Context at all, which is part of why the language
        // decision could only ever look at the text's script.
        appContext = context.applicationContext
        // [T-android-tts-engine-explicit] 不再把引擎交给系统猜。
        // 华为机上的 tts_default_synth 指向没安装的 com.svox.pico，
        // 详情与取舍见 TtsEngineResolver 的注释。
        val engine = TtsEngineResolver.resolve(context.applicationContext)
        preferredEngine = engine
        tts = if (engine != null) {
            TextToSpeech(context.applicationContext, this, engine)
        } else {
            TextToSpeech(context.applicationContext, this)
        }
    }

    /**
     * [T-android-tts-locale GH#324] The user's saved in-app language tag, or
     * null when they follow the system. Read fresh each utterance rather than
     * cached: the Appearance screen writes this preference and the user can
     * change it without the TTS engine being re-created.
     */
    private fun savedAppLanguageTag(): String? = runCatching {
        appContext
            ?.getSharedPreferences(
                com.openminis.app.ui.settings.PREF_APPEARANCE,
                Context.MODE_PRIVATE,
            )
            ?.getString(com.openminis.app.ui.settings.KEY_LANGUAGE, "")
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isInitialized = true
            tts?.apply {
                setSpeechRate(speechRate)
                setPitch(speechPitch)
                // [T-android-tts-locale GH#324] Was `setLanguage(Locale.US)`.
                // Every utterance re-resolves the language anyway, so this only
                // ever decided the engine's resting state — but on a device
                // whose first utterance raced init, that resting state WAS the
                // voice. Seed from the user's own language instead of pinning
                // American English for everyone.
                applyResolvedLocale(TtsLocaleResolver.resolve("", savedAppLanguageTag()))
                setOnUtteranceProgressListener(createProgressListener())
            // [T-music-player-top] Let the music player know when the app is
            // speaking, so it can duck instead of burying the answer.
            VoiceOutputState.registerSpeechSource(this@TextToSpeechManager)
            }
            Log.d(TAG, "TTS initialized successfully")
            // Replay whatever arrived while the engine was binding, in order.
            val queued = synchronized(queueLock) {
                val copy = preInitQueue.toList()
                preInitQueue.clear()
                copy
            }
            // [T-android-tts-init-thread] Post the replay to the MAIN thread.
            //
            // onInit arrives on a binder thread. speakQueued touches
            // pendingTexts (a plain MutableList that speak()/stop()/the
            // progress listener also mutate) and `tts` (which shutdown()
            // nulls), with no happens-before against any of them. Disposing the
            // ChatScreen inside the ~4 s init window — rotate the device, or
            // navigate away right after enabling read-replies — therefore
            // raced a ConcurrentModificationException on pendingTexts, or a
            // speak() against an already shut-down engine. Same lifecycle shape
            // as the VAD SIGSEGV fixed in 91c7b2944.
            //
            // Re-check liveness inside the post: shutdown() can land between
            // the binder callback and the main-thread turn.
            if (queued.isNotEmpty()) {
                Handler(Looper.getMainLooper()).post {
                    if (tts != null && isInitialized) {
                        for (text in queued) speakQueued(text)
                    } else {
                        Log.d(TAG, "pre-init replay dropped — engine shut down during init")
                    }
                }
            }
        } else if (preferredEngine != null && appContext != null) {
            // [T-android-tts-engine-explicit] 显式指定的引擎起不来（被冻结、
            // 用户停用、或 OEM 改了服务名），退回系统默认再试一次。
            // 只退一次：重试时 preferredEngine 已置 null，再失败就走下面的
            // initFailed 分支，不会无限重建。
            val failed = preferredEngine
            preferredEngine = null
            Log.w(TAG, "TTS engine $failed failed to init (status=$status); retrying with system default")
            runCatching { tts?.shutdown() }
            tts = null
            isInitialized = false
            tts = TextToSpeech(appContext!!, this)
            return
        } else {
            initFailed = true
            synchronized(queueLock) { preInitQueue.clear() }
            _isSpeaking.value = false
            Log.e(TAG, "TTS initialization failed with status: $status")
        }
        initResult.complete(status == TextToSpeech.SUCCESS)
    }

    /**
     * [T-android-tts-silent-blackhole] Suspend until init settles (bounded by
     * [timeoutMs] — a device with no engine may never call [onInit] at all).
     * @return true when the engine is usable.
     */
    suspend fun awaitReady(timeoutMs: Long = INIT_TIMEOUT_MS): Boolean {
        if (isInitialized) return true
        if (initFailed) return false
        return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { initResult.await() } ?: false
    }

    /**
     * Speaks text immediately, interrupting any ongoing speech (QUEUE_FLUSH).
     * Automatically detects language from text content.
     */
    fun speak(text: String) {
        speakInternal(text)
    }

    /**
     * [T-android-speak-wait] Speak and wait until the engine reports the
     * utterance as finished (or failed).
     *
     * The CLI used to answer `status: "speaking"` the moment the utterance
     * was queued, so a caller had no way to tell "the phone is talking" from
     * "the phone will talk in a moment" from "the engine swallowed it". The
     * 2026-11-20 self-diagnosis caught the agent reporting a voice broadcast
     * as done and the user hearing nothing.
     */
    fun speakAndAwait(text: String, timeoutMs: Long): SpeechOutcome {
        val started = System.currentTimeMillis()
        val id = speakInternal(text)
            ?: return SpeechOutcome(
                completed = false,
                error = "引擎拒绝了这次朗读请求（speak() 返回失败）",
                elapsedMs = 0,
            )
        val latch = CountDownLatch(1)
        completionLatches[id] = latch
        // The engine may have finished before we registered the waiter.
        // NOTE: Set.remove returns Boolean, so `remove(id) != null` is ALWAYS
        // true (false != null) — that version released the latch instantly and
        // made --wait report "completed" in ~8 ms while the phone was still
        // speaking.
        if (finishedUtterances.remove(id)) latch.countDown()
        Log.i(TAG, "wait register id=$id")
        val deadline = started + timeoutMs
        var finished = false
        var via = "timeout"
        while (System.currentTimeMillis() < deadline) {
            if (latch.await(100, TimeUnit.MILLISECONDS)) {
                finished = true
                via = "latch"
                break
            }
            // [T-android-speak-wait] Belt and braces: once this utterance is
            // known to have started, the engine going quiet is also a valid
            // completion even if no second onDone arrives (the Huawei engine
            // emits one bogus onDone before onStart, which we drop).
            if (startedUtterances.contains(id) && !isSpeaking.value) {
                finished = true
                via = "quiet"
                break
            }
        }
        Log.i(TAG, "wait id=$id finished=$finished via=$via elapsed=${System.currentTimeMillis() - started}")
        val error = completionErrors.remove(id)
        completionLatches.remove(id)
        finishedUtterances.remove(id)
        startedUtterances.remove(id)
        return SpeechOutcome(
            completed = finished && error == null,
            error = when {
                error != null -> error
                !finished -> "等待朗读结束超时（${timeoutMs}ms）：引擎仍未回报完成，不能确认是否念完"
                else -> null
            },
            elapsedMs = System.currentTimeMillis() - started,
        )
    }

    /** Result of [speakAndAwait]. */
    data class SpeechOutcome(
        val completed: Boolean,
        val error: String?,
        val elapsedMs: Long,
    )

    /**
     * The engine-facing call. Returns the utterance id, or null when the
     * engine rejected the request (which is how a missing language pack
     * shows up).
     */
    private fun speakInternal(text: String): String? {
        if (text.isBlank()) return null
        if (!isInitialized) {
            // [T-android-tts-silent-blackhole] Engine still binding (or dead).
            // Buffer instead of dropping; onInit replays in order. On a failed
            // init the queue is discarded there — nothing can ever speak it.
            if (!initFailed) {
                synchronized(queueLock) {
                    preInitQueue.clear() // speak() semantics: flush what came before
                    preInitQueue.add(text)
                }
                _isSpeaking.value = true
            }
            return null
        }

        isPaused = false
        pendingTexts.clear()
        pendingTexts.add(text)
        pausedAtIndex = 0
        sentenceBuffer.setLength(0)

        autoDetectAndSetLanguage(text)

        val params = buildSpeechParams()
        // [T-android-tts-rom-compat] speak() returns ERROR synchronously when
        // the engine rejects the request (missing language data, engine died —
        // both real on OEM/AOSP engines without Google TTS). The result used
        // to be discarded, so isSpeaking stayed true with NO utterance in
        // flight and no progress callback ever coming — ReadAloudPlayer's
        // completion poll then spun forever and the whole read-aloud queue
        // wedged for the rest of the process.
        val utteranceId = generateUtteranceId()
        val rc = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        if (rc != TextToSpeech.SUCCESS) {
            Log.w(TAG, "speak rejected by engine (rc=$rc len=${text.length})")
            _isSpeaking.value = false
            return null
        }
        _isSpeaking.value = true
        return utteranceId
    }

    /**
     * Adds text to the speech queue without interrupting (QUEUE_ADD).
     */
    fun speakQueued(text: String) {
        if (text.isBlank()) return
        if (!isInitialized) {
            // [T-android-tts-diag] A field report of "read replies is on but
            // nothing is spoken" could not be diagnosed from the log: this
            // path emitted nothing at all, so whether text ever reached the
            // engine was unknowable. Queued-before-init is the one silent
            // outcome that looks identical to success from the caller's side.
            Log.i(TAG, "speakQueued deferred (init pending) len=${text.length} initFailed=$initFailed")
            if (!initFailed) {
                synchronized(queueLock) { preInitQueue.add(text) }
                _isSpeaking.value = true
            }
            return
        }
        Log.i(TAG, "speakQueued len=${text.length}")

        pendingTexts.add(text)
        autoDetectAndSetLanguage(text)

        val params = buildSpeechParams()
        // [T-android-tts-rom-compat] Same rejected-enqueue guard as speak().
        val rc = tts?.speak(text, TextToSpeech.QUEUE_ADD, params, generateUtteranceId())
        if (rc != TextToSpeech.SUCCESS) {
            Log.w(TAG, "speakQueued rejected by engine (rc=$rc len=${text.length})")
            // Only clear the flag when nothing else is in flight — a QUEUE_ADD
            // rejection must not mark an ongoing earlier utterance as done.
            if (pendingTexts.isEmpty()) _isSpeaking.value = false
            return
        }
        _isSpeaking.value = true
    }

    /**
     * Feed a chunk of streaming AI output. Any complete sentences the chunk
     * produces (boundary characters: `。！？.!?\n` — same set as iOS
     * `extractNewSentences` in AIChatViewModel) are immediately enqueued for
     * TTS via [speakQueued], so the first sentence starts speaking the moment
     * its terminator arrives instead of waiting for the full response.
     *
     * Incomplete tail text stays in [sentenceBuffer] until the next call, or
     * until [flush] runs at stream end.
     *
     * Safe to call before [init]/onInit — chunks are still split; the TTS
     * engine simply drops them if not yet ready (speakQueued guards).
     */
    fun appendText(chunk: String) {
        if (chunk.isEmpty()) return
        sentenceBuffer.append(chunk)
        val sentences = extractCompleteSentences(sentenceBuffer)
        if (sentences.isEmpty()) return
        for (sentence in sentences) {
            speakQueued(sentence)
        }
    }

    /**
     * Emit the remaining buffered tail (a sentence fragment with no trailing
     * punctuation) as a final utterance. Call at the end of a streaming
     * response. Mirrors iOS's post-stream "flush remaining speech" logic.
     */
    fun flush() {
        if (sentenceBuffer.isEmpty()) return
        val tail = sentenceBuffer.toString().trim()
        sentenceBuffer.setLength(0)
        if (tail.isNotEmpty()) {
            speakQueued(tail)
        }
    }

    /**
     * [T-android-tts-intranumber-guard] Delegates to the shared
     * [SpeechSentenceSplitter] so this path and [ReadAloudPlayer] apply the same
     * terminator set AND the same intra-number guards ("3.14" is never cut into
     * "3." + "14").
     */
    private fun extractCompleteSentences(buffer: StringBuilder): List<String> =
        SpeechSentenceSplitter.extractCompleteSentences(buffer)

    /**
     * Stops all speech immediately.
     */
    fun stop() {
        tts?.stop()
        // [T-android-tts-locale GH#324] One explicit `--voice` utterance must
        // not pin the language for every later one.
        explicitLocale = null
        isPaused = false
        pendingTexts.clear()
        pausedAtIndex = 0
        sentenceBuffer.setLength(0)
        // A stop while the engine is still binding must also drop the buffered
        // utterances, or they'd blurt out the moment onInit lands.
        synchronized(queueLock) { preInitQueue.clear() }
        _isSpeaking.value = false
    }

    /**
     * Toggles pause/resume. Since Android TTS doesn't natively support pause,
     * this stops playback and tracks position for manual resume.
     */
    fun togglePause() {
        if (isPaused) {
            // Resume: replay remaining texts from where we paused
            isPaused = false
            if (pausedAtIndex < pendingTexts.size) {
                val remaining = pendingTexts.subList(pausedAtIndex, pendingTexts.size)
                remaining.forEachIndexed { index, text ->
                    autoDetectAndSetLanguage(text)
                    val params = buildSpeechParams()
                    val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                    tts?.speak(text, queueMode, params, generateUtteranceId())
                }
                _isSpeaking.value = true
            }
        } else {
            // Pause: stop and mark position
            isPaused = true
            tts?.stop()
            _isSpeaking.value = false
        }
    }

    /**
     * Sets the TTS language explicitly.
     */
    fun setLanguage(locale: Locale) {
        if (!isInitialized) return
        // [T-android-tts-locale GH#324] Remember it, or the very next speak()
        // would re-run detection and throw this away.
        explicitLocale = locale
        val result = tts?.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "Language not supported: ${locale.toLanguageTag()}")
        } else {
            Log.d(TAG, "Language set to: ${locale.toLanguageTag()}")
        }
    }

    /**
     * Cleans up the TTS engine. Must be called when no longer needed.
     */
    fun shutdown() {
        stop()
        tts?.shutdown()
        explicitLocale = null
        appContext = null
        tts = null
        isInitialized = false
        Log.d(TAG, "TTS shut down")
    }

    /**
     * Detects if text contains Han characters and sets language accordingly.
     */
    /**
     * [T-android-system-voice-catalog] Engine voice name to speak with; null =
     * auto (per-utterance language detection below). Set per utterance by
     * ReadAloudPlayer from the capsule's picked system voice. When the named
     * voice exists it wins over language auto-detection — same as iOS, where a
     * picked voice is used regardless of the reply's language.
     */
    @Volatile
    var preferredVoiceName: String? = null

    private fun autoDetectAndSetLanguage(text: String) {
        preferredVoiceName?.let { wanted ->
            // [T-android-system-voice-catalog] Composite id (name|locale) when
            // the engine reports duplicate names across locales; a bare name
            // from an older build still matches by name.
            val v = runCatching {
                tts?.voices?.firstOrNull { SystemTtsVoiceCatalog.matches(it, wanted) }
            }.getOrNull()
            if (v != null) {
                val rc = tts?.setVoice(v)
                if (rc == TextToSpeech.SUCCESS) return
                Log.w(TAG, "setVoice($wanted) rejected (rc=$rc) — falling back to auto language")
            } else {
                Log.w(TAG, "preferred voice '$wanted' not found on this engine — falling back to auto language")
            }
        }
        // [T-android-tts-locale GH#324] An explicit caller choice outranks
        // detection — see [explicitLocale] for why this guard has to exist.
        explicitLocale?.let { applyResolvedLocale(it); return }

        applyResolvedLocale(TtsLocaleResolver.resolve(text, savedAppLanguageTag()))
    }

    /**
     * [T-android-tts-locale GH#324] Hand [wanted] to the engine, degrading to
     * the bare language when the region-qualified form has no voice data.
     *
     * The degradation is the point: a device may ship `pt` but not `pt-BR`, and
     * `setLanguage` with an unsupported locale is a no-op that leaves whatever
     * was set last in place. Before this change that leftover was English, so
     * an unsupported locale produced exactly the reported bug rather than an
     * approximation. When nothing matches we log and leave the engine alone —
     * a wrong-but-present voice beats silence, and the log says which language
     * the device is missing.
     */
    private fun applyResolvedLocale(wanted: Locale) {
        val engine = tts ?: return
        for (candidate in TtsLocaleResolver.candidates(wanted)) {
            val availability = runCatching { engine.isLanguageAvailable(candidate) }
                .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            if (availability == TextToSpeech.LANG_MISSING_DATA ||
                availability == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                continue
            }
            val rc = runCatching { engine.setLanguage(candidate) }
                .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            if (rc != TextToSpeech.LANG_MISSING_DATA && rc != TextToSpeech.LANG_NOT_SUPPORTED) {
                return
            }
        }
        Log.w(
            TAG,
            "no voice data for ${wanted.toLanguageTag()} — keeping the engine's " +
                "current language (install the language's TTS data to fix)",
        )
    }

    private fun buildSpeechParams(): android.os.Bundle {
        return android.os.Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, speechVolume)
            if (audioSessionId > 0) putInt(TextToSpeech.Engine.KEY_PARAM_SESSION_ID, audioSessionId)
        }
    }

    private fun generateUtteranceId(): String = UUID.randomUUID().toString()

    /**
     * [T-android-speak-wait] Waiters parked on a single utterance. The CLI's
     * `--wait` flag blocks here until the engine reports done/error.
     */
    private val completionLatches = ConcurrentHashMap<String, CountDownLatch>()

    /** Utterances that finished before their waiter registered. */
    private val finishedUtterances = ConcurrentHashMap.newKeySet<String>()

    /**
     * [T-android-speak-wait] Utterances whose onStart we have seen.
     *
     * Observed on the Huawei engine (2026-11-20): `speak()` returns and the
     * engine fires `onDone` for the NEW utterance id synchronously, before
     * `onStart`, while the audio only starts later. Accepting that onDone
     * made `--wait` answer "completed" in ~10 ms while `--status` still
     * reported `speaking: true`. A completion is therefore only trusted after
     * the matching onStart; an early onDone is dropped and the caller times
     * out into `unconfirmed` instead of being told a lie.
     */
    private val startedUtterances = ConcurrentHashMap.newKeySet<String>()

    /** Engine-reported failure per utterance, if any. */
    private val completionErrors = ConcurrentHashMap<String, String>()

    private fun createProgressListener(): UtteranceProgressListener =
        object : UtteranceProgressListener() {

            override fun onStart(utteranceId: String?) {
                utteranceId?.let {
                    startedUtterances.add(it)
                    Log.i(TAG, "evt start id=$it inst=${System.identityHashCode(this@TextToSpeechManager)} thread=${Thread.currentThread().name}")
                }
                _isSpeaking.value = true
            }

            override fun onDone(utteranceId: String?) {
                // [T-android-speak-wait] Release a `--wait` caller — but only
                // for an utterance we have actually seen start. The Huawei
                // engine emits a bogus onDone synchronously inside speak()
                // before onStart; trusting it reported "completed" in ~10 ms
                // while the phone was still speaking.
                utteranceId?.let { id ->
                    val started = startedUtterances.remove(id)
                    Log.i(TAG, "evt done id=$id started=$started inst=${System.identityHashCode(this@TextToSpeechManager)} thread=${Thread.currentThread().name}")
                    if (started) {
                        finishedUtterances.add(id)
                        completionLatches.remove(id)?.countDown()
                    }
                }
                // [T-android-tts-init-thread] Progress callbacks arrive on a
                // binder thread. This used to bump pausedAtIndex and read
                // pendingTexts.size straight from there, while togglePause()
                // takes a subList VIEW of that same list on main — a
                // concurrent stop() clearing it makes the view throw. Confine
                // both to the main thread so pendingTexts/pausedAtIndex have a
                // single owner.
                Handler(Looper.getMainLooper()).post {
                    pausedAtIndex++
                    // Only mark as not speaking if nothing else is queued
                    if (pausedAtIndex >= pendingTexts.size) {
                        _isSpeaking.value = false
                    }
                }
            }

            @Deprecated("Deprecated in API level 21")
            override fun onError(utteranceId: String?) {
                Log.e(TAG, "evt error id=$utteranceId code=none")
                utteranceId?.let { id ->
                    completionErrors[id] = "朗读出错（引擎未给出错误码）"
                    finishedUtterances.add(id)
                    completionLatches.remove(id)?.countDown()
                }
                _isSpeaking.value = false
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.e(TAG, "evt error id=$utteranceId code=$errorCode")
                utteranceId?.let { id ->
                    completionErrors[id] = "朗读出错（引擎错误码 $errorCode）"
                    finishedUtterances.add(id)
                    completionLatches.remove(id)?.countDown()
                }
                _isSpeaking.value = false
            }
        }
}

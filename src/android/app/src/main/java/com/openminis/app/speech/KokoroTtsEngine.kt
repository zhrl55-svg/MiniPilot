package com.openminis.app.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.google.mediapipe.tasks.audio.texttospeech.SynthesisOptions
import com.google.mediapipe.tasks.audio.texttospeech.TextToSpeech
import com.google.mediapipe.tasks.audio.texttospeech.TextToSpeechOptions
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * [T-kokoro-voice] Optional on-device TTS: MediaPipe Text-to-Speech Task with
 * the Kokoro-82M `.litertlm` bundle (Apache-2.0).
 *
 * ## Why
 * `TtsEngineResolver` exists because Chinese OEM ROMs routinely point
 * `tts_default_synth` at an engine that is not installed (Huawei → `com.svox.pico`),
 * so the same system voice is unavailable or sounds different per device.
 * Kokoro is model-backed and OEM-independent: once the 295 MB bundle is on
 * disk, Chinese and English sound the same everywhere and work offline.
 *
 * ## Routing
 * This is a THIRD tier, opt-in from Settings › Speech synthesis:
 * provider TTS (if configured) → Kokoro (if enabled and downloaded) → system
 * TextToSpeech (terminal fallback). [ReadAloudPlayer.speakOne] owns that order;
 * this object never decides policy, only reports readiness and speaks.
 *
 * ## Long text
 * Kokoro's unary API has no partial output, and its streaming API is slower
 * without Gallery's static-shape clause coalescing. [speak] therefore splits
 * the sentence at clause punctuation, synthesizes chunk by chunk, and writes
 * each chunk into ONE persistent AudioTrack: synthesis of chunk N+1 overlaps
 * playback of chunk N, and time-to-first-audio is one short clause instead of
 * the whole paragraph.
 *
 * ## Chinese G2P workaround (important)
 * Kokoro's Chinese front-end (cppjieba) unpacks its HMM dictionary to
 * `cache_dir`, and falls back to `std::filesystem::temp_directory_path()` when
 * that write fails. On Android the fallback resolves to `/data/local/tmp`,
 * which the `untrusted_app` SELinux domain cannot write, producing
 * `kokoro.cc:240 Failed to write cppjieba HMM model…`. Setting `TMPDIR` to an
 * app-private directory before the engine is created makes the fallback land
 * somewhere writable. Do not remove this; without it every Chinese utterance
 * fails while English keeps working, which is a confusing field report.
 */
object KokoroTtsEngine {
    private const val TAG = "KokoroTtsEngine"

    /** Adjacent-sample jump that counts as a click at 24 kHz. */
    private const val DECLICK_THRESHOLD = 0.3f

    const val MODEL_FILE = "kokoro_int8_fp16.litertlm"
    private const val MODEL_URL =
        "https://huggingface.co/litert-community/Kokoro-82M/resolve/main/kokoro_int8_fp16.litertlm"
    const val MODEL_BYTES = 294_783_298L
    private const val MODEL_SHA256 =
        "e7b931ee5d4b7104012a838c9eee240d19ea69cb3ca269041a3274f83dbb7fa1"
    /**
     * GPU-packaged bundle (acoustic model CPU + vocoder GPU). Same filename
     * slot; accepted by size and verified against its own hash. Used only in
     * DEV_TOOLS A/B runs.
     */
    const val GPU_MODEL_BYTES = 294_750_530L
    private const val GPU_MODEL_SHA256 =
        "1ef178b127ec48c737daf17eabb4cea5f6b3ffbc510aced270fe5c267bbeac52"

    const val DEFAULT_ZH_VOICE = "zf_xiaoxiao"
    const val DEFAULT_EN_VOICE = "af_heart"

    /**
     * The eight Mandarin voices shipped in the bundle. Labels are the names
     * Kokoro's model card uses, not pinyin transliterations, so a field report
     * that names a voice can be matched back to upstream.
     */
    val ZH_VOICES: List<Voice> = listOf(
        Voice("zf_xiaoxiao", "小晓（女）"),
        Voice("zf_xiaobei", "小北（女）"),
        Voice("zf_xiaoni", "小妮（女）"),
        Voice("zf_xiaoyi", "小艺（女）"),
        Voice("zm_yunxi", "云希（男）"),
        Voice("zm_yunjian", "云健（男）"),
        Voice("zm_yunxia", "云夏（男）"),
        Voice("zm_yunyang", "云扬（男）"),
    )

    /** A small English subset; the bundle's full 54-voice list is overkill here. */
    val EN_VOICES: List<Voice> = listOf(
        Voice("af_heart", "Heart（女）"),
        Voice("af_bella", "Bella（女）"),
        Voice("am_adam", "Adam（男）"),
        Voice("bm_george", "George（男）"),
    )

    data class Voice(val id: String, val label: String)

    sealed interface Status {
        data object NotDownloaded : Status
        data class Downloading(val bytes: Long, val total: Long) : Status
        data object Ready : Status
        data class Failed(val message: String) : Status
    }

    data class SpeakResult(
        val ok: Boolean,
        val voice: String,
        val audioDurationMs: Double,
        val synthesisMs: Double,
        val error: String? = null,
        /** Wall-clock from the first chunk's synthesis start to its first PCM write. */
        val timeToFirstAudioMs: Double = 0.0,
        /** True when [stop] aborted this utterance; callers must not fall back. */
        val cancelled: Boolean = false,
        /** Peak absolute sample before PCM16 conversion (1.0 = full scale). */
        val peak: Double = 0.0,
        /** Samples outside [-1, 1] that PCM16 conversion had to clamp. */
        val clippedSamples: Int = 0,
    )

    private const val PREFS = "kokoro_tts"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_ZH_VOICE = "voice.zh"
    private const val KEY_EN_VOICE = "voice.en"
    /** One-time cleanup marker after the GPU bundle was removed. */
    private const val KEY_MIGRATED = "migrated.cpu.v1"

    private val _status = MutableStateFlow<Status>(Status.NotDownloaded)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val speakMutex = Mutex()
    private var engine: TextToSpeech? = null
    private var engineModelPath: String? = null

    /** True when the warm engine was created with the GPU delegate. */
    @Volatile
    private var engineUseGpu = false

    @Volatile
    private var currentTrack: AudioTrack? = null

    @Volatile
    private var stopRequested = false

    // --- settings (per-device prefs, mirroring provider voice settings) ---

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (!enabled) stop()
    }

    fun zhVoice(context: Context): String =
        prefs(context).getString(KEY_ZH_VOICE, DEFAULT_ZH_VOICE) ?: DEFAULT_ZH_VOICE

    fun setZhVoice(context: Context, voiceId: String) {
        prefs(context).edit().putString(KEY_ZH_VOICE, voiceId).apply()
    }

    fun enVoice(context: Context): String =
        prefs(context).getString(KEY_EN_VOICE, DEFAULT_EN_VOICE) ?: DEFAULT_EN_VOICE

    fun setEnVoice(context: Context, voiceId: String) {
        prefs(context).edit().putString(KEY_EN_VOICE, voiceId).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // --- model files ---

    fun modelDir(context: Context): File =
        File(context.applicationContext.filesDir, "kokoro").apply { mkdirs() }

    fun modelFile(context: Context): File = File(modelDir(context), MODEL_FILE)

    private fun markerFile(context: Context, sha: String): File =
        File(modelDir(context), ".verified-$sha")

    private fun tmpDir(context: Context): File =
        File(context.applicationContext.filesDir, "kokoro-tmp").apply { mkdirs() }

    /** Cheap check used by the settings screen and the routing gate. */
    private fun isUsable(file: File, context: Context): Boolean =
        file.exists() &&
            (file.length() == MODEL_BYTES || file.length() == GPU_MODEL_BYTES)

    private fun expectedSha(file: File): String? = when (file.length()) {
        MODEL_BYTES -> MODEL_SHA256
        GPU_MODEL_BYTES -> GPU_MODEL_SHA256
        else -> null
    }

    /**
     * One-time cleanup after dropping the GPU bundle: keep a previously
     * imported CPU bundle (the only accepted size) by renaming it to the
     * canonical name, then delete every other file — the GPU backup, stale
     * verification markers and the old PoC download.
     */
    private fun migrateOnce(context: Context) {
        val prefs = prefs(context)
        if (prefs.getBoolean(KEY_MIGRATED, false)) return
        val dir = modelDir(context)
        val target = modelFile(context)
        if (!target.exists()) {
            dir.listFiles()?.firstOrNull {
                it.isFile && it.length() == MODEL_BYTES && it.name != MODEL_FILE
            }?.renameTo(target)
        }
        dir.listFiles()?.forEach { file ->
            if (file.name != MODEL_FILE && file.name != "$MODEL_FILE.part") {
                file.delete()
            }
        }
        File(context.applicationContext.filesDir, "kokoro-poc").deleteRecursively()
        prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
    }

    fun refreshStatus(context: Context) {
        migrateOnce(context)
        _status.value = when {
            !isUsable(modelFile(context), context) -> Status.NotDownloaded
            else -> Status.Ready
        }
    }

    fun isReady(context: Context): Boolean {
        migrateOnce(context)
        return isUsable(modelFile(context), context)
    }

    /**
     * Download or resume the bundle. Progress is published on [status]; the
     * final hash is verified before the file is renamed into place, so a
     * truncated Xet/CDN response can never be handed to the native graph.
     */
    suspend fun download(context: Context): Boolean = withContext(Dispatchers.IO) {
        val target = modelFile(context)
        val part = File(modelDir(context), "$MODEL_FILE.part")
        if (isUsable(target, context)) {
            _status.value = Status.Ready
            return@withContext true
        }
        val start = if (part.exists()) part.length() else 0L
        _status.value = Status.Downloading(start, MODEL_BYTES)
        try {
            val connection = URL(MODEL_URL).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            if (start > 0) connection.setRequestProperty("Range", "bytes=$start-")
            connection.connect()
            try {
                val code = connection.responseCode
                if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                    throw IllegalStateException("HTTP $code")
                }
                val append = code == HttpURLConnection.HTTP_PARTIAL && start > 0
                var written = if (append) start else 0L
                connection.inputStream.use { input ->
                    FileOutputStream(part, append).use { output ->
                        val buffer = ByteArray(256 * 1024)
                        var lastReport = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            written += n
                            if (written - lastReport >= 4L * 1024 * 1024) {
                                lastReport = written
                                _status.value = Status.Downloading(
                                    written.coerceAtMost(MODEL_BYTES),
                                    MODEL_BYTES,
                                )
                            }
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
            if (part.length() != MODEL_BYTES) {
                throw IllegalStateException("incomplete download: ${part.length()}/$MODEL_BYTES")
            }
            if (!verifySha256(part, MODEL_SHA256)) {
                part.delete()
                throw IllegalStateException("SHA-256 mismatch")
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            markerFile(context, MODEL_SHA256).writeText(MODEL_SHA256)
            _status.value = Status.Ready
            Log.i(TAG, "model downloaded: ${target.length()} bytes")
            true
        } catch (t: Throwable) {
            _status.value = Status.Failed(t.message ?: t.javaClass.simpleName)
            Log.e(TAG, "download failed: $t")
            false
        }
    }

    /**
     * [T-kokoro-voice] DEV_TOOLS-only import of an already-downloaded bundle
     * (adb-staged under /data/local/tmp), so the production path can be tested
     * without spending 295 MB of phone bandwidth. Never called from the app.
     */
    suspend fun importModel(
        context: Context,
        source: File,
        verify: Boolean = true,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!source.exists()) return@withContext false
            val sha = if (verify) expectedSha(source) ?: return@withContext false else null
            if (verify) {
                if (!verifySha256(source, sha!!)) return@withContext false
            } else if (source.length() < 100L * 1024 * 1024) {
                return@withContext false
            }
            source.copyTo(modelFile(context), overwrite = true)
            if (sha != null) {
                markerFile(context, sha).writeText(sha)
            }
            _status.value = Status.Ready
            true
        } catch (t: Throwable) {
            _status.value = Status.Failed(t.message ?: t.javaClass.simpleName)
            false
        }
    }

    fun deleteModel(context: Context) {
        releaseEngine()
        modelFile(context).delete()
        File(modelDir(context), "$MODEL_FILE.part").delete()
        modelDir(context).listFiles()?.forEach {
            if (it.name.startsWith(".verified-")) it.delete()
        }
        _status.value = Status.NotDownloaded
    }

    private fun verifySha256(file: File, expected: String): Boolean {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == expected
    }

    /**
     * Hash once per process (and once per download): the marker persists the
     * result so a cold start does not read 295 MB before the first sentence.
     */
    private fun ensureVerified(context: Context): Boolean {
        migrateOnce(context)
        val file = modelFile(context)
        if (!isUsable(file, context)) return false
        val sha = expectedSha(file) ?: return false
        val marker = markerFile(context, sha)
        if (marker.exists() && marker.readText().trim() == sha) return true
        if (!verifySha256(file, sha)) {
            Log.e(TAG, "model SHA-256 mismatch; refusing to load")
            return false
        }
        marker.writeText(sha)
        return true
    }

    // --- synthesis ---

    /**
     * Speak [text] to completion. Returns a failed result (never throws) so the
     * read-aloud worker can fall back to the system engine for this sentence.
     */
    suspend fun speak(
        context: Context,
        text: String,
        voiceOverride: String? = null,
        /** DEV_TOOLS A/B: GPU delegate (GPU bundle only). Default CPU. */
        preferGpu: Boolean = false,
        /** DEV_TOOLS A/B: interpolate sample-to-sample jump artifacts. */
        declick: Boolean = false,
        /**
         * Gallery's 110-char merge + join trim. On by default: measured on a
         * 193-character Chinese text (ELS-AN00, CPU delegate, thermal 3),
         * coalescing cut synthesis from 129.8/150.3 s to 94.5/117.6 s
         * (RTF 3.31/3.83 → 2.38/2.96) with the same time-to-first-audio.
         * Short text coalesces back into a single clause, so there is no cost.
         */
        coalesce: Boolean = true,
    ): SpeakResult {
        if (text.isBlank()) return SpeakResult(false, "", 0.0, 0.0, "empty text")
        return speakMutex.withLock {
            val voice = voiceOverride ?: voiceFor(context, text)
            try {
                withContext(Dispatchers.IO) {
                    if (!ensureVerified(context)) {
                        return@withContext SpeakResult(
                            false, voice, 0.0, 0.0, "model missing or corrupt",
                        )
                    }
                    prepareTempDir(context)
                    // A GPU-packaged bundle is useless on the CPU delegate:
                    // select GPU automatically, with engineFor's CPU fallback.
                    var tts = engineFor(
                        context,
                        preferGpu || modelFile(context).length() == GPU_MODEL_BYTES,
                    )
                    // The GPU bundle's int8 vocoder emits sample-to-sample
                    // pops; de-click by default when that bundle is installed.
                    val useDeclick =
                        declick || modelFile(context).length() == GPU_MODEL_BYTES
                    stopRequested = false
                    // Gallery's 110-char static-shape budget. Measured on a
                    // 193-char Chinese text: one big pass was 20-27% slower
                    // than two 110-char passes, so the split pays for its
                    // extra stream startup. ReadAloudPlayer also batches the
                    // queued sentences, so a whole reply is one call here.
                    val clauses = if (coalesce) {
                        coalesceForKokoro(text, maxChars = 110)
                    } else if (text.length <= 300) {
                        listOf(text.trim())
                    } else {
                        coalesceForKokoro(text, maxChars = 200)
                    }
                    if (clauses.isEmpty() || clauses.first().isEmpty()) {
                        return@withContext SpeakResult(false, voice, 0.0, 0.0, "empty text")
                    }
                    var track: AudioTrack? = null
                    var rate = 24_000
                    var totalSamples = 0
                    var firstAudioAt = -1L
                    var synthNanos = 0L
                    var peak = 0.0
                    var clipped = 0
                    val start = System.nanoTime()
                    val channel = Channel<Pair<FloatArray, Int>>(capacity = 4)
                    try {
                        coroutineScope {
                            // Producer: pure synthesis. Its time is the RTF
                            // number — it must not include AudioTrack's
                            // blocking writes, which used to double-count
                            // playback and made a 0.8-RTF engine look like 1.6.
                            val producer = launch(Dispatchers.IO) {
                                try {
                                    var chunkIndex = 0
                                    for ((clauseIndex, clause) in clauses.withIndex()) {
                                        if (stopRequested) break
                                        val t0 = System.nanoTime()
                                        var firstChunkOfClause = true
                                        val stream = try {
                                            tts.synthesizeStream(
                                                clause,
                                                SynthesisOptions(voice, null),
                                            )
                                        } catch (t: Throwable) {
                                            if (engineUseGpu) {
                                                Log.w(
                                                    TAG,
                                                    "GPU stream failed, falling back to CPU: $t",
                                                )
                                                releaseEngine()
                                                tts = engineFor(context, preferGpu = false)
                                                tts.synthesizeStream(
                                                    clause,
                                                    SynthesisOptions(voice, null),
                                                )
                                            } else {
                                                throw t
                                            }
                                        }
                                        stream.collect { chunk ->
                                            if (stopRequested) return@collect
                                            val chunkRate =
                                                chunk.sampleRateHz.takeIf { it > 0 } ?: rate
                                            var samples = chunk.samples
                                            if (samples.isEmpty()) return@collect
                                            if (firstChunkOfClause && clauseIndex > 0) {
                                                samples = trimEdge(
                                                    samples,
                                                    trimHead = true,
                                                    trimTail = false,
                                                )
                                            }
                                            if (chunk.isFinal && clauseIndex < clauses.lastIndex) {
                                                samples = trimEdge(
                                                    samples,
                                                    trimHead = false,
                                                    trimTail = true,
                                                )
                                            }
                                            firstChunkOfClause = false
                                            Log.i(
                                                TAG,
                                                "chunk#$chunkIndex clause=$clauseIndex " +
                                                    "samples=${samples.size} " +
                                                    "at=${(System.nanoTime() - start) / 1_000_000}ms " +
                                                    "final=${chunk.isFinal}",
                                            )
                                            chunkIndex++
                                            if (samples.isNotEmpty()) {
                                                channel.send(samples to chunkRate)
                                            }
                                        }
                                        synthNanos += System.nanoTime() - t0
                                    }
                                } finally {
                                    channel.close()
                                }
                            }
                            // Consumer: the only place that touches AudioTrack.
                            // Channel capacity 4 lets synthesis run ahead, so
                            // playback is continuous whenever the engine is
                            // faster than real time.
                            var prevSample = 0f
                            var jumps = 0
                            var declickFixed = 0
                            for ((rawSamples, chunkRate) in channel) {
                                if (stopRequested) break
                                rate = chunkRate
                                val t = track ?: newTrack(rate).also {
                                    track = it
                                    it.play()
                                }
                                if (firstAudioAt < 0) firstAudioAt = System.nanoTime()
                                var samples = rawSamples
                                if (useDeclick) {
                                    // int8 vocoder artifacts show up as
                                    // sample-to-sample jumps; replace each
                                    // offending sample with the midpoint of
                                    // its neighbours, carrying state across
                                    // chunks so a click on a chunk boundary
                                    // is caught too.
                                    samples = rawSamples.copyOf()
                                    for (i in samples.indices) {
                                        if (kotlin.math.abs(samples[i] - prevSample) >
                                            DECLICK_THRESHOLD
                                        ) {
                                            jumps++
                                            val next =
                                                if (i + 1 < samples.size) samples[i + 1]
                                                else samples[i]
                                            samples[i] = (prevSample + next) * 0.5f
                                            declickFixed++
                                        }
                                        prevSample = samples[i]
                                    }
                                }
                                for (v in samples) {
                                    val a = kotlin.math.abs(v.toDouble())
                                    if (a > peak) peak = a
                                    if (a > 1.0) clipped++
                                }
                                writePcm(t, samples)
                                totalSamples += samples.size
                            }
                            if (useDeclick) {
                                Log.i(TAG, "declick jumps=$jumps fixed=$declickFixed")
                            }
                            producer.join()
                        }
                        val synthesisMs = synthNanos / 1_000_000.0
                        if (stopRequested) {
                            return@withContext SpeakResult(
                                false, voice, 0.0, synthesisMs, "cancelled", cancelled = true,
                            )
                        }
                        if (totalSamples == 0) {
                            return@withContext SpeakResult(
                                false, voice, 0.0, synthesisMs, "empty audio",
                            )
                        }
                        val audioDurationMs = totalSamples * 1000.0 / rate
                        val ttfaMs = if (firstAudioAt < 0) 0.0 else (firstAudioAt - start) / 1_000_000.0
                        Log.i(
                            TAG,
                            "spoken audio=${audioDurationMs.toInt()}ms " +
                                "synth=${synthesisMs.toInt()}ms ttfa=${ttfaMs.toInt()}ms " +
                                "underruns=${track?.underrunCount ?: -1}",
                        )
                        awaitPlayback(track, totalSamples, rate)
                        SpeakResult(
                            true, voice, audioDurationMs, synthesisMs,
                            timeToFirstAudioMs = ttfaMs,
                            peak = peak,
                            clippedSamples = clipped,
                        )
                    } finally {
                        track?.let {
                            runCatching {
                                it.stop()
                                it.release()
                            }
                        }
                        currentTrack = null
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "synthesis failed voice=$voice: $t")
                SpeakResult(false, voice, 0.0, 0.0, t.message ?: t.javaClass.simpleName)
            }
        }
    }

    /**
     * Load the model and Chinese G2P ahead of the first reply.
     * The first Chinese utterance otherwise pays ~4 s of one-time setup —
     * the difference between "starts talking" and "hangs" from the user's side.
     */
    suspend fun warmUp(context: Context) {
        if (!isReady(context)) return
        speakMutex.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    if (!ensureVerified(context)) return@runCatching
                    prepareTempDir(context)
                    val tts = engineFor(context)
                    tts.synthesize(
                        "你好",
                        SynthesisOptions(zhVoice(context), null),
                    )
                    Log.i(TAG, "warm-up done")
                }.onFailure { Log.w(TAG, "warm-up failed: $it") }
            }
        }
    }

    /**
     * [T-kokoro-voice] Render one sentence to a WAV file without playing it,
     * so a metallic-noise report can be split into "model output" vs "device
     * playback chain". DEBUG diagnostics only.
     */
    suspend fun renderWav(
        context: Context,
        text: String,
        voiceOverride: String?,
        outFile: File,
        useStream: Boolean = true,
    ): JSONObject = withContext(Dispatchers.IO) {
        speakMutex.withLock {
            if (!ensureVerified(context)) {
                return@withLock JSONObject().put("ok", false).put("error", "model missing")
            }
            prepareTempDir(context)
            val tts = engineFor(context)
            val voice = voiceOverride ?: voiceFor(context, text)
            var rate = 24_000
            val pcm = java.io.ByteArrayOutputStream()
            var peak = 0.0
            var clipped = 0
            fun append(samples: FloatArray, chunkRate: Int) {
                if (chunkRate > 0) rate = chunkRate
                for (v in samples) {
                    val a = kotlin.math.abs(v.toDouble())
                    if (a > peak) peak = a
                    if (a > 1.0) clipped++
                    val i = (v * 32767).toInt().coerceIn(-32768, 32767).toShort()
                    pcm.write(i.toInt() and 0xFF)
                    pcm.write((i.toInt() ushr 8) and 0xFF)
                }
            }
            if (useStream) {
                tts.synthesizeStream(text, SynthesisOptions(voice, null)).collect { chunk ->
                    append(chunk.samples, chunk.sampleRateHz)
                }
            } else {
                val result = tts.synthesize(text, SynthesisOptions(voice, null))
                append(result.samples, result.sampleRateHz)
            }
            val bytes = pcm.toByteArray()
            outFile.parentFile?.mkdirs()
            outFile.outputStream().use { out ->
                fun le16(v: Int) { out.write(v and 0xFF); out.write((v ushr 8) and 0xFF) }
                fun le32(v: Int) {
                    out.write(v and 0xFF); out.write((v ushr 8) and 0xFF)
                    out.write((v ushr 16) and 0xFF); out.write((v ushr 24) and 0xFF)
                }
                out.write("RIFF".toByteArray()); le32(36 + bytes.size)
                out.write("WAVEfmt ".toByteArray()); le32(16); le16(1); le16(1)
                le32(rate); le32(rate * 2); le16(2); le16(16)
                out.write("data".toByteArray()); le32(bytes.size); out.write(bytes)
            }
            JSONObject()
                .put("ok", true)
                .put("path", outFile.absolutePath)
                .put("sampleRateHz", rate)
                .put("audioDurationMs", bytes.size / 2.0 * 1000.0 / rate)
                .put("peak", peak)
                .put("clippedSamples", clipped)
        }
    }

    /** Voice used for [text] when the caller did not pick one explicitly. */
    fun voiceFor(context: Context, text: String): String =
        if (text.any { it in '一'..'鿿' }) zhVoice(context) else enVoice(context)

    /**
     * `TMPDIR` is process-wide: set it before ANY engine creation, and keep it
     * pointed at a directory that survives `cacheDir` eviction — the HMM file
     * is ~1 MB and its absence forces every Chinese sentence through the
     * failing unpack path again.
     */
    private fun prepareTempDir(context: Context) {
        runCatching {
            android.system.Os.setenv("TMPDIR", tmpDir(context).absolutePath, true)
        }.onFailure { Log.w(TAG, "setenv TMPDIR failed: ${it.javaClass.simpleName}") }
    }

    private fun engineFor(context: Context, preferGpu: Boolean = false): TextToSpeech {
        val path = modelFile(context).absolutePath
        engine?.let {
            if (engineModelPath == path && engineUseGpu == preferGpu) return it
        }
        releaseEngine()
        prepareTempDir(context)

        fun create(delegate: Delegate): TextToSpeech {
            val options = TextToSpeechOptions(
                BaseOptions.builder()
                    .setModelAssetPath(path)
                    .setDelegate(delegate)
                    .build(),
                // A non-null defaultVoiceId and a filesDir cache keep the
                // Chinese G2P dictionary in app-private storage.
                kotlin.runCatching { zhVoice(context) }.getOrNull(),
                null,      // defaultLocale — voice prefix picks the G2P
                1.0f,      // speakingRate
                0,         // targetBucket
                File(context.filesDir, "kokoro-cache").apply { mkdirs() }.absolutePath,
            )
            return TextToSpeech.create(options)
        }

        if (preferGpu) {
            try {
                return create(Delegate.GPU).also {
                    engine = it
                    engineModelPath = path
                    engineUseGpu = true
                    Log.i(TAG, "engine created with GPU delegate")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "GPU engine create failed, using CPU: $t")
            }
        }
        return create(Delegate.CPU).also {
            engine = it
            engineModelPath = path
            engineUseGpu = false
            Log.i(TAG, "engine created with CPU delegate")
        }
    }

    /** Stop the current utterance and discard the warm engine. */
    fun stop() {
        stopRequested = true
        runCatching { engine?.cancel() }
        currentTrack?.let { track ->
            runCatching {
                track.pause()
                track.flush()
                track.stop()
            }
        }
        currentTrack = null
    }

    fun releaseEngine() {
        stop()
        runCatching { engine?.close() }
        engine = null
        engineModelPath = null
    }

    /**
     * Split text that exceeds the per-stream static-shape budget into
     * comma-joined pieces. Only used for >300-character input; normal calls
     * are one stream.
     */
    internal fun coalesceForKokoro(text: String, maxChars: Int = 110): List<String> {
        val raw = ArrayList<String>()
        val current = StringBuilder()
        fun flush() {
            val piece = current.toString().trim()
            if (piece.isNotEmpty()) raw.add(piece)
            current.setLength(0)
        }
        for (ch in text) {
            current.append(ch)
            if (ch in "，,、；;：:。！？!?\n") flush()
        }
        flush()
        if (raw.isEmpty()) return emptyList()

        val merged = ArrayList<String>()
        val buffer = StringBuilder()
        for (clause in raw) {
            // Measured: a 20-char first clause did NOT lower time-to-first-
            // audio (3.3 s vs 3.5 s uniform) — that latency is synthesizeStream
            // startup, not text length — and it made RTF worse (0.92 vs 0.86).
            // Keep Gallery's uniform 110-char budget.
            when {
                buffer.isEmpty() -> buffer.append(clause)
                buffer.length + 1 + clause.length <= maxChars -> {
                    val cjk = buffer.any { it in '一'..'鿿' } ||
                        clause.any { it in '一'..'鿿' }
                    buffer.append(if (cjk) '，' else ' ').append(clause)
                }
                else -> {
                    merged.add(replaceInnerSentenceEnders(buffer.toString()))
                    buffer.setLength(0)
                    buffer.append(clause)
                }
            }
        }
        if (buffer.isNotEmpty()) merged.add(replaceInnerSentenceEnders(buffer.toString()))
        return merged
    }

    private val innerSentenceEnders = Regex("[。！？!?]+(?=\\S)")

    private fun replaceInnerSentenceEnders(clause: String): String =
        clause.replace(innerSentenceEnders, "，")

    /**
     * Trim excess model-edge silence at an inner clause join: keep 5 ms of
     * head margin and 200 ms of punctuation pause (24 kHz, like Gallery).
     */
    private fun trimEdge(samples: FloatArray, trimHead: Boolean, trimTail: Boolean): FloatArray {
        if (samples.isEmpty()) return samples
        var peak = 0f
        for (v in samples) {
            val m = kotlin.math.abs(v)
            if (m > peak) peak = m
        }
        if (peak <= 0f) return samples
        val threshold = peak * 0.01f
        var begin = 0
        var end = samples.size
        if (trimHead) {
            while (begin < end && kotlin.math.abs(samples[begin]) < threshold) begin++
            begin = (begin - 120).coerceAtLeast(0)
        }
        if (trimTail) {
            while (end > begin && kotlin.math.abs(samples[end - 1]) < threshold) end--
            end = (end + 4_800).coerceAtMost(samples.size)
        }
        if (begin == 0 && end == samples.size) return samples
        if (begin >= end) return FloatArray(0)
        return samples.copyOfRange(begin, end)
    }

    private fun newTrack(sampleRateHz: Int): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setSampleRate(sampleRateHz)
                    .build(),
            )
            .setBufferSizeInBytes(minBuffer.coerceAtLeast(48_000))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also {
                Log.i(TAG, "track rate=$sampleRateHz buffer=${minBuffer.coerceAtLeast(48_000)}B")
                // [T-readaloud-volume] Same 0–100 % gain the other paths use;
                // the >100 % LoudnessEnhancer boost stays a system/provider
                // feature because AudioTrack's own volume covers this fallback.
                it.setVolume(ReadAloudBoost.linearGain(VoiceOutputState.volume.value))
                currentTrack = it
            }
    }

    private fun writePcm(track: AudioTrack, samples: FloatArray) {
        val bytes = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val v = (samples[i] * 32767).toInt().coerceIn(-32768, 32767).toShort()
            bytes[2 * i] = v.toByte()
            bytes[2 * i + 1] = (v.toInt() ushr 8).toByte()
        }
        var offset = 0
        while (offset < bytes.size && !stopRequested) {
            val n = minOf(8192, bytes.size - offset)
            val written = track.write(bytes, offset, n)
            if (written <= 0) break
            offset += written
        }
    }

    private fun awaitPlayback(track: AudioTrack?, samples: Int, sampleRateHz: Int) {
        if (track == null) return
        val deadline = System.currentTimeMillis() + samples * 1000L / sampleRateHz + 5_000L
        while (!stopRequested &&
            track.playbackHeadPosition < samples &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(40)
        }
    }

    /** Diagnostics for the DEV_TOOLS `debug.tts.kokoro.engine` RPC. */
    fun diagnostics(context: Context): JSONObject = JSONObject()
        .put("enabled", isEnabled(context))
        .put("ready", isReady(context))
        .put("status", _status.value.toString())
        .put("modelPath", modelFile(context).absolutePath)
        .put("zhVoice", zhVoice(context))
        .put("enVoice", enVoice(context))
        .put("tmpdir", tmpDir(context).absolutePath)
        .put("delegate", if (engineUseGpu) "gpu" else "cpu")
        .put("modelBytes", modelFile(context).length())
}

package com.openminis.app.speech

import android.content.Context
import android.media.MediaPlayer
import android.widget.Toast
import com.openminis.app.R
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.voice.VoiceOutputRequest
import com.openminis.app.provider.voice.VoiceProviderFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * [T-android-voice-synthesis-settings] Audition a voice from Settings without
 * committing to it.
 *
 * System voices are spoken by a throwaway [TextToSpeechManager] with
 * `preferredVoiceName` set to the row's composite id; cloud voices are
 * synthesized through the same vendor adapters read-aloud uses and played on a
 * plain MediaPlayer. Starting a new preview (or leaving the screen) stops the
 * previous one, and nothing here touches the persisted selection.
 */
class VoicePreviewPlayer(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var system: TextToSpeechManager? = null
    private var job: Job? = null
    private var player: MediaPlayer? = null
    private var kokoroPreview = false

    /** Bumped by every stop/preview so a late synthesis cannot start audio. */
    private var generation = 0

    fun previewSystem(voiceId: String?, text: String) {
        stopPlayback()
        val gen = generation
        job = scope.launch {
            val manager = system ?: TextToSpeechManager().also {
                it.init(appContext)
                system = it
            }
            if (!manager.awaitReady()) {
                notifyFailure()
                return@launch
            }
            if (gen != generation) return@launch
            manager.preferredVoiceName = voiceId
            manager.speechRate = 1.0f
            manager.speak(text)
        }
    }

    fun previewCloud(
        repository: ProviderRepository,
        instance: ProviderInstance,
        entry: ModelEntry,
        voiceId: String?,
        text: String,
    ) {
        stopPlayback()
        val gen = generation
        job = scope.launch {
            val apiKey = repository.requestCredential(instance)
            if (apiKey == null) {
                notifyFailure()
                return@launch
            }
            val provider = VoiceProviderFactory.make(instance, apiKey)
            if (provider == null) {
                notifyFailure()
                return@launch
            }
            val data = try {
                withContext(Dispatchers.IO) {
                    provider.synthesize(
                        VoiceOutputRequest(
                            input = text,
                            model = entry.model.id,
                            // null keeps the vendor's own voice (for MiniMax
                            // this means its default); MiniMax previews pass
                            // the row's voice id explicitly.
                            voice = voiceId ?: entry.model.id,
                            resolvedModel = entry.model,
                        ),
                    )
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                // A new preview / leaving the screen cancels this job; never
                // turn that into a "preview failed" toast.
                throw ce
            } catch (e: Exception) {
                AppLogger.error(TAG, "preview synthesis failed: $e")
                null
            }
            if (data == null || data.isEmpty()) {
                notifyFailure()
                return@launch
            }
            if (gen != generation) return@launch
            playBytes(data)
        }
    }

    /**
     * [T-kokoro-voice] Audition a bundled Kokoro voice. Reuses the production
     * engine (so a preview proves the same path read-aloud will take) and
     * speaks the row's voice explicitly.
     */
    fun previewKokoro(voiceId: String, text: String) {
        stopPlayback()
        val gen = generation
        kokoroPreview = true
        job = scope.launch {
            val result = KokoroTtsEngine.speak(appContext, text, voiceId)
            if (gen == generation && !result.ok) notifyFailure()
        }
    }

    /** Stop whatever is previewing. Safe to call from any preview entry point. */
    fun stop() {
        stopPlayback()
    }

    fun shutdown() {
        stopPlayback()
        system?.shutdown()
        system = null
        scope.cancel()
    }

    private fun stopPlayback() {
        generation++
        job?.cancel()
        job = null
        releasePlayer()
        system?.stop()
        if (kokoroPreview) {
            kokoroPreview = false
            // The production engine is process-wide; leaving this screen (or
            // starting another preview) must silence the Kokoro row too.
            KokoroTtsEngine.stop()
        }
    }

    private suspend fun playBytes(data: ByteArray) {
        val file = withContext(Dispatchers.IO) {
            File(appContext.cacheDir, "voice_preview.audio").apply { writeBytes(data) }
        }
        suspendCancellableCoroutine { cont ->
            val mp = MediaPlayer()
            player = mp
            var resumed = false
            fun finish() {
                if (resumed) return
                resumed = true
                releasePlayer()
                if (cont.isActive) cont.resume(Unit)
            }
            runCatching {
                mp.setAudioAttributes(ReadAloudAudioFocus.ATTRIBUTES)
                mp.setDataSource(file.absolutePath)
                mp.setOnCompletionListener { finish() }
                mp.setOnErrorListener { _, what, extra ->
                    AppLogger.error(TAG, "preview MediaPlayer error what=$what extra=$extra")
                    finish()
                    true
                }
                mp.prepare()
                mp.start()
            }.onFailure {
                AppLogger.error(TAG, "preview MediaPlayer setup failed: $it")
                finish()
            }
            cont.invokeOnCancellation { releasePlayer() }
        }
    }

    private fun releasePlayer() {
        player?.runCatching {
            if (isPlaying) stop()
            release()
        }
        player = null
    }

    private fun notifyFailure() {
        Toast.makeText(
            appContext,
            appContext.getString(R.string.voice_preview_failed),
            Toast.LENGTH_SHORT,
        ).show()
    }

    companion object {
        private const val TAG = "VoicePreview"
    }
}

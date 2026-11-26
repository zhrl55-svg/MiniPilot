package com.openminis.app.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.model.MiniMaxVoices
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.SystemVoiceIds
import com.openminis.app.data.model.isVoiceOutputCandidate
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.speech.SystemTtsVoiceCatalog
import com.openminis.app.speech.VoiceOutputState
import com.openminis.app.speech.VoicePreviewPlayer
import com.openminis.app.ui.components.SectionDesign
import com.openminis.app.ui.components.SectionFooter

/**
 * [T-android-voice-synthesis-settings] Settings › Speech synthesis, in two
 * levels — the hierarchy mirrors the actual decision the user makes:
 *
 *   1. How should replies be spoken — the on-device engine, or a cloud vendor?
 *   2. Only then the details of that choice:
 *      • System → Automatic only: the engine picks a voice matching the text
 *        language. No manual language/voice list — that selection was removed
 *        because it fought the per-reply language switch.
 *      • Cloud  → the provider, then its voice (MiniMax voice ids, or the
 *        provider's model entries for vendors whose entries ARE voices).
 *
 * The old flat screen mixed a "follow model group" row, every system voice and
 * every provider model in one list, which made the order of decisions
 * invisible. Model-group bindings still work at runtime; this screen simply
 * writes the explicit override once the user picks something.
 *
 * Selection writes are identical to the chat capsule's
 * [com.openminis.app.ui.chat.voice.VoiceOutputPickerSheet]:
 *  - [ProviderRepository.voiceOutputOverrideEntryId] chooses system vs a cloud
 *    model entry;
 *  - [VoiceOutputState.setSystemVoice] stores the device voice;
 *  - [ProviderRepository.setVoiceOutputCloudVoice] stores the MiniMax voice id,
 *    which [com.openminis.app.speech.ReadAloudPlayer] forwards per utterance.
 */
@Composable
fun VoiceSynthesisScreen(
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
    onAddProvider: () -> Unit = {},
) {
    val context = LocalContext.current
    val config by providerRepository.config.collectAsState()
    val systemVoiceName by VoiceOutputState.systemVoiceName.collectAsState()

    // Device TTS voices are enumerated off the main thread and can take a few
    // seconds to bind on a cold engine — see SystemTtsVoiceCatalog.
    var systemVoices by remember {
        mutableStateOf(emptyList<SystemTtsVoiceCatalog.SystemVoice>())
    }
    var voicesLoaded by remember { mutableStateOf(false) }
    // [T-android-system-voice-tuning] Pitch is the framework's only prosody
    // control (no style/SSML); the row cycles 自然 / 偏低 / 偏高.
    val pitch by VoiceOutputState.pitch.collectAsState()
    val speed by VoiceOutputState.speed.collectAsState()

    LaunchedEffect(Unit) {
        VoiceOutputState.init(context)
        systemVoices = SystemTtsVoiceCatalog.voices(context)
        voicesLoaded = true
    }

    // [T-android-voice-synthesis-settings] 试听 — audition a row without
    // committing it. Shut down with the screen so neither the throwaway engine
    // nor a cloud MediaPlayer outlives the page.
    val preview = remember { VoicePreviewPlayer(context) }
    val previewSample = stringResource(R.string.voice_preview_sample)
    DisposableEffect(Unit) {
        onDispose { preview.shutdown() }
    }

    val overrideId = providerRepository.voiceOutputOverrideEntryId
    // Recomputed when the config revision changes so the screen follows a
    // disabled instance / removed entry, not just its own writes.
    val effective = remember(config, overrideId) {
        providerRepository.resolveVoiceOutputChoice()
    }
    val cloudEntry = effective.entry
    val isSystemMode = cloudEntry == null

    val systemEntryId = "${SystemVoiceIds.BUILTIN_PROVIDER_ID}/${SystemVoiceIds.SYSTEM_TTS}"

    // TTS-capable models from enabled providers. A model that only ACCEPTS
    // audio never shows here — isVoiceOutputCandidate is the runtime's gate.
    val cloudProviders = remember(config) {
        config.instances
            .filter { it.isEnabled }
            .mapNotNull { inst ->
                val entries = config.modelEntries.filter { entry ->
                    entry.providerInstanceId == inst.id &&
                        !entry.isHidden &&
                        entry.model.isVoiceOutputCandidate
                }
                if (entries.isEmpty()) null else inst to entries
            }
    }

    // ── Level 2, system branch: Automatic only ──
    // A saved manual voice would keep overriding the engine behind this
    // screen's back, so clear it once. From here on the engine always picks
    // the voice whose language matches each reply's text.
    LaunchedEffect(systemVoiceName) {
        if (systemVoiceName != null) VoiceOutputState.setSystemVoice(null, null)
    }

    fun useSystem() {
        VoiceOutputState.setSystemVoice(null, null)
        providerRepository.voiceOutputOverrideEntryId = systemEntryId
    }

    fun pickAutoSystemVoice() = useSystem()

    // ── Level 2, cloud branch: providers → voices ──
    val selectedCloudInstance = cloudEntry?.first
    val selectedProviderEntries = cloudProviders
        .firstOrNull { it.first.id == selectedCloudInstance?.id }
        ?.second
        .orEmpty()

    fun pickCloudMode() {
        if (cloudProviders.isEmpty()) {
            onAddProvider()
            return
        }
        if (cloudEntry == null) {
            cloudProviders.firstOrNull()?.second?.firstOrNull()?.let { entry ->
                providerRepository.voiceOutputOverrideEntryId = entry.id
            }
        }
    }

    fun pickCloudProvider(
        instance: ProviderInstance,
        entries: List<ModelEntry>,
    ) {
        val existing = cloudEntry?.takeIf { it.first.id == instance.id }?.second
        providerRepository.voiceOutputOverrideEntryId = (existing ?: entries.first()).id
    }

    // First-level cloud subtitle names the actual vendors: with only MiniMax
    // configured, "Cloud synthesis" alone hides which service will be used.
    val cloudModeSubtitle = if (cloudProviders.isEmpty()) {
        stringResource(R.string.voice_synthesis_mode_cloud_unavailable)
    } else {
        cloudProviders.joinToString(" · ") { (instance, _) ->
            instance.label.ifBlank { instance.providerType.displayName }
        }
    }

    fun pickMiniMaxVoice(
        instance: ProviderInstance,
        entries: List<ModelEntry>,
        voiceId: String,
    ) {
        providerRepository.setVoiceOutputCloudVoice(instance.id, voiceId)
        val entry = cloudEntry?.takeIf { it.first.id == instance.id }?.second
            ?: entries.firstOrNull()
        // entries can be empty if every TTS entry of the instance is hidden
        // while a stale override still resolves to it; the voice preference
        // is still worth storing, the override write is not.
        entry?.let { providerRepository.voiceOutputOverrideEntryId = it.id }
    }

    SettingsScaffold(
        title = stringResource(R.string.voice_synthesis_title),
        onBack = onBack,
    ) {
        Spacer(Modifier.height(SectionDesign.FirstSectionTopGap))
        SectionFooter(text = stringResource(R.string.voice_synthesis_intro))

        // ── Level 1: system vs cloud ──
        SettingsSection(header = stringResource(R.string.voice_synthesis_mode_header)) {
            SettingsChoiceRow(
                title = stringResource(R.string.voice_synthesis_mode_system),
                subtitle = stringResource(R.string.voice_synthesis_mode_system_desc),
                selected = isSystemMode,
                onSelect = { useSystem() },
                showDivider = true,
            )
            SettingsChoiceRow(
                title = stringResource(R.string.voice_synthesis_mode_cloud),
                subtitle = cloudModeSubtitle,
                selected = !isSystemMode,
                onSelect = { pickCloudMode() },
                showDivider = false,
            )
        }

        if (isSystemMode) {
            // ── Level 2: automatic voice only ──
            SettingsSection(
                header = stringResource(R.string.voice_synthesis_language_header),
                footer = stringResource(R.string.voice_synthesis_system_footer),
            ) {
                SettingsChoiceRow(
                    title = stringResource(R.string.voice_synthesis_auto),
                    selected = true,
                    onSelect = { pickAutoSystemVoice() },
                    onPreview = { preview.previewSystem(null, previewSample) },
                    showDivider = !voicesLoaded || systemVoices.isEmpty(),
                )
                if (!voicesLoaded) {
                    SettingsRow(
                        title = stringResource(R.string.voice_synthesis_loading),
                        showDivider = false,
                    )
                } else if (systemVoices.isEmpty()) {
                    SettingsRow(
                        title = stringResource(R.string.voice_synthesis_system_empty),
                        subtitle = stringResource(R.string.voice_synthesis_system_empty_desc),
                        showDivider = true,
                    )
                    SettingsRow(
                        title = stringResource(R.string.voice_synthesis_open_system_settings),
                        icon = Icons.Outlined.Settings,
                        onClick = {
                            // Not a documented SDK constant — OEMs occasionally
                            // rename it, so fall back to general settings rather
                            // than crashing.
                            runCatching {
                                context.startActivity(
                                    Intent("com.android.settings.TTS_SETTINGS"),
                                )
                            }.onFailure {
                                runCatching {
                                    context.startActivity(Intent(Settings.ACTION_SETTINGS))
                                }
                            }
                        },
                        showDivider = false,
                    )
                }
            }
            SectionFooter(text = stringResource(R.string.voice_synthesis_auto_voice_note))

            // ── Level 3: prosody ──
            SettingsSection(
                header = stringResource(R.string.voice_synthesis_pitch_header),
                footer = stringResource(R.string.voice_synthesis_pitch_footer),
            ) {
                VoiceOutputState.PITCH_STEPS.forEachIndexed { index, step ->
                    SettingsChoiceRow(
                        title = when {
                            step < 0.95f -> stringResource(R.string.voice_synthesis_pitch_low)
                            step > 1.05f -> stringResource(R.string.voice_synthesis_pitch_high)
                            else -> stringResource(R.string.voice_synthesis_pitch_natural)
                        },
                        selected = kotlin.math.abs(pitch - step) < 0.001f,
                        onSelect = { VoiceOutputState.setPitch(step) },
                        showDivider = index < VoiceOutputState.PITCH_STEPS.lastIndex,
                    )
                }
            }
        }

        // 语速同时作用于系统引擎与云端合成的朗读，因此放在两种模式之外。
        // 连续滑动，0.05× 一档；系统语音与云端朗读共用 VoiceOutputState.speed。
        SettingsSection(
            header = stringResource(R.string.voice_synthesis_speed_header),
            footer = stringResource(R.string.voice_synthesis_speed_footer),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = VoiceOutputState.speedLabel(speed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Slider(
                    value = speed,
                    onValueChange = { VoiceOutputState.setSpeed(it) },
                    valueRange = 0.5f..3f,
                    steps = 50,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (!isSystemMode) {
            // ── Level 2: cloud provider ──
            SettingsSection(
                header = stringResource(R.string.voice_synthesis_cloud_service_header),
                footer = stringResource(R.string.voice_synthesis_cloud_footer),
            ) {
                cloudProviders.forEachIndexed { index, (instance, entries) ->
                    SettingsChoiceRow(
                        title = instance.label.ifBlank {
                            instance.providerType.displayName
                        },
                        // Show the entry actually in effect on the selected
                        // provider (may be HD or Turbo), not always the first.
                        subtitle = (if (selectedCloudInstance?.id == instance.id) {
                            cloudEntry?.second ?: entries.firstOrNull()
                        } else {
                            entries.firstOrNull()
                        })?.let { shown ->
                            shown.model.displayName.ifBlank { shown.model.id }
                        },
                        selected = selectedCloudInstance?.id == instance.id,
                        onSelect = { pickCloudProvider(instance, entries) },
                        showDivider = index < cloudProviders.lastIndex,
                    )
                }
            }

            // ── Level 3: voices for the selected cloud provider ──
            if (selectedCloudInstance != null && selectedProviderEntries.isNotEmpty()) {
                if (MiniMaxVoices.isMiniMax(selectedCloudInstance)) {
                    // MiniMax: model and voice are separate namespaces. When
                    // nothing is saved the adapter's own default is in effect,
                    // so mark it selected instead of showing an empty state.
                    val selectedVoiceId = providerRepository
                        .voiceOutputCloudVoice(selectedCloudInstance.id)
                        ?: MiniMaxVoices.DEFAULT_VOICE_ID
                    // Preview through the model entry actually in effect on this
                    // provider (HD/Turbo), not merely the first one.
                    val previewEntry = cloudEntry
                        ?.takeIf { it.first.id == selectedCloudInstance.id }
                        ?.second
                        ?: selectedProviderEntries.first()
                    SettingsSection(
                        header = stringResource(R.string.voice_synthesis_voice_header),
                    ) {
                        MiniMaxVoices.all.forEachIndexed { index, voice ->
                            SettingsChoiceRow(
                                title = voice.label,
                                subtitle = voice.id,
                                selected = selectedVoiceId == voice.id,
                                onSelect = {
                                    pickMiniMaxVoice(
                                        selectedCloudInstance,
                                        selectedProviderEntries,
                                        voice.id,
                                    )
                                },
                                showDivider = index < MiniMaxVoices.all.lastIndex,
                                onPreview = {
                                    preview.previewCloud(
                                        providerRepository,
                                        selectedCloudInstance,
                                        previewEntry,
                                        voice.id,
                                        previewSample,
                                    )
                                },
                            )
                        }
                    }
                } else {
                    // Vendors whose catalog entries ARE voices (Doubao speaker,
                    // ElevenLabs voice_id …): the entry list is the voice list.
                    SettingsSection(
                        header = stringResource(R.string.voice_synthesis_voice_header),
                    ) {
                        selectedProviderEntries.forEachIndexed { index, entry ->
                            SettingsChoiceRow(
                                title = entry.model.displayName.ifBlank { entry.model.id },
                                subtitle = entry.baseModel.id,
                                selected = cloudEntry?.second?.id == entry.id,
                                onSelect = {
                                    providerRepository.voiceOutputOverrideEntryId =
                                        entry.id
                                },
                                showDivider = index < selectedProviderEntries.lastIndex,
                                onPreview = {
                                    preview.previewCloud(
                                        providerRepository,
                                        selectedCloudInstance,
                                        entry,
                                        // Entry id IS the voice for this vendor.
                                        null,
                                        previewSample,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(SectionDesign.SectionTopGap))
    }
}

/** Provider + model summary for the Settings row, mirroring the capsule chip. */
internal fun voiceSynthesisSummary(
    entry: Pair<ProviderInstance, ModelEntry>?,
): String? {
    val (instance, modelEntry) = entry ?: return null
    val provider = instance.label.ifBlank { instance.providerType.displayName }
    val model = modelEntry.model.displayName.ifBlank { modelEntry.model.id }
    // MiniMax's model names already start with the vendor name
    // ("MiniMax Speech 2.8 HD") — repeating the provider reads as a stutter.
    return if (model.contains(provider, ignoreCase = true)) model else "$provider · $model"
}

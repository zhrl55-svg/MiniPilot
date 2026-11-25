package com.openminis.app.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import java.util.Locale

/**
 * [T-android-voice-synthesis-settings] Settings › Speech synthesis, in two
 * levels — the hierarchy mirrors the actual decision the user makes:
 *
 *   1. How should replies be spoken — the on-device engine, or a cloud vendor?
 *   2. Only then the details of that choice:
 *      • System → language, then a voice inside that language.
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

    // ── Level 2, system branch: languages → voices in that language ──
    val languageGroups = remember(systemVoices) {
        val uiLocale = Locale.getDefault()
        systemVoices
            .groupBy { it.locale.language }
            .map { (code, voices) ->
                val name = runCatching {
                    Locale.forLanguageTag(code).getDisplayName(uiLocale)
                }.getOrDefault(code).ifBlank { code }
                SystemLanguageGroup(code = code, label = name, voices = voices)
            }
        // groupBy keeps the catalog's own order: the UI language's voices
        // first, then Chinese/English, then the rest.
    }

    // Composite id for new picks; a legacy bare engine name still resolves to
    // the first voice carrying it, which is what the old lookup used.
    val currentSystemLanguage = systemVoices
        .firstOrNull { it.id == systemVoiceName || it.name == systemVoiceName }
        ?.locale?.language

    // Local browse state for the two-step system flow. Null = "follow the
    // saved voice" (and, when no voice is saved, the Automatic row).
    var browsingLanguage by remember { mutableStateOf<String?>(null) }
    val displayedLanguage = browsingLanguage
        ?.takeIf { code -> languageGroups.any { it.code == code } }
        ?: currentSystemLanguage

    fun useSystem() {
        providerRepository.voiceOutputOverrideEntryId = systemEntryId
    }

    fun pickLanguage(group: SystemLanguageGroup) {
        browsingLanguage = group.code
        // Commit a concrete voice so the radio state always matches what the
        // engine will actually use: the first voice of the picked language
        // when the saved voice belongs to another one.
        if (currentSystemLanguage != group.code) {
            group.voices.firstOrNull()?.let { voice ->
                VoiceOutputState.setSystemVoice(voice.id, voice.label)
                useSystem()
            }
        }
    }

    fun pickSystemVoice(voice: SystemTtsVoiceCatalog.SystemVoice) {
        browsingLanguage = voice.locale.language
        // Composite id: the locale is part of the identity because OEM engines
        // can report one name for several locale variants.
        VoiceOutputState.setSystemVoice(voice.id, voice.label)
        useSystem()
    }

    fun pickAutoSystemVoice() {
        browsingLanguage = null
        VoiceOutputState.setSystemVoice(null, null)
        useSystem()
    }

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
            // ── Level 2: language ──
            SettingsSection(
                header = stringResource(R.string.voice_synthesis_language_header),
                footer = stringResource(R.string.voice_synthesis_system_footer),
            ) {
                SettingsChoiceRow(
                    title = stringResource(R.string.voice_synthesis_auto),
                    selected = displayedLanguage == null,
                    onSelect = { pickAutoSystemVoice() },
                    // Something always follows: language rows, the loading
                    // row, or the empty-engine rows.
                    showDivider = true,
                )
                languageGroups.forEachIndexed { index, group ->
                    SettingsChoiceRow(
                        title = group.label,
                        selected = displayedLanguage == group.code,
                        onSelect = { pickLanguage(group) },
                        showDivider = index < languageGroups.lastIndex ||
                            !voicesLoaded || systemVoices.isEmpty(),
                    )
                }
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

            // ── Level 3: voice in the picked language ──
            if (displayedLanguage == null) {
                if (systemVoices.isNotEmpty()) {
                    SectionFooter(text = stringResource(R.string.voice_synthesis_auto_voice_note))
                }
            } else {
                val voices = languageGroups
                    .firstOrNull { it.code == displayedLanguage }
                    ?.voices
                    .orEmpty()
                if (voices.isNotEmpty()) {
                    SettingsSection(
                        header = stringResource(R.string.voice_synthesis_voice_header),
                    ) {
                        voices.forEachIndexed { index, voice ->
                            SettingsChoiceRow(
                                title = voice.label,
                                // Locale tag + engine-reported quality: Huawei
                                // reports "zh" for every Chinese variant, so the
                                // quality word is what distinguishes rows.
                                subtitle = listOfNotNull(
                                    voice.locale.toLanguageTag().ifBlank { null },
                                    when {
                                        voice.quality >= 500 -> stringResource(R.string.voice_quality_very_high)
                                        voice.quality >= 400 -> stringResource(R.string.voice_quality_high)
                                        else -> null
                                    },
                                ).joinToString(" · "),
                                selected = SystemTtsVoiceCatalog.isSelected(
                                    voice,
                                    systemVoiceName,
                                    voices,
                                ),
                                onSelect = { pickSystemVoice(voice) },
                                showDivider = index < voices.lastIndex,
                                onPreview = {
                                    preview.previewSystem(voice.id, previewSample)
                                },
                            )
                        }
                    }
                }
            }

            // ── Level 4: prosody ──
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
        } else {
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

/** One device language and the voices the TTS engine exposes for it. */
private data class SystemLanguageGroup(
    val code: String,
    val label: String,
    val voices: List<SystemTtsVoiceCatalog.SystemVoice>,
)

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

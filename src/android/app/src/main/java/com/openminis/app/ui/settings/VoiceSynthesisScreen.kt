package com.openminis.app.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
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
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.SystemVoiceIds
import com.openminis.app.data.model.isVoiceOutputCandidate
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.speech.SystemTtsVoiceCatalog
import com.openminis.app.speech.VoiceOutputState
import com.openminis.app.ui.components.SectionDesign
import com.openminis.app.ui.components.SectionFooter

/**
 * [T-android-voice-synthesis-settings] Settings › Speech synthesis — choose
 * what reads replies and selected text aloud: the device TTS engine (with a
 * specific voice) or a cloud TTS model from a configured provider (MiniMax,
 * Doubao, Azure, ElevenLabs, …).
 *
 * The selection writes exactly what the chat capsule's
 * [com.openminis.app.ui.chat.voice.VoiceOutputPickerSheet] writes:
 *  - [ProviderRepository.voiceOutputOverrideEntryId] — the per-device override
 *    slot [ProviderRepository.resolveVoiceOutputChoice] consults first;
 *  - [VoiceOutputState.setSystemVoice] — the system engine's voice id, which
 *    [com.openminis.app.speech.ReadAloudPlayer] applies per utterance.
 *
 * Both selections are visible in one place instead of being reachable only
 * from the chat capsule or by binding a "Voice Output" model group under
 * Default Models. The group binding is still honoured through the "follow
 * default group" row (override = null), so this screen never silently
 * abandons a group the user configured.
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

    LaunchedEffect(Unit) {
        VoiceOutputState.init(context)
        systemVoices = SystemTtsVoiceCatalog.voices(context)
        voicesLoaded = true
    }

    val overrideId = providerRepository.voiceOutputOverrideEntryId
    // Recomputed when the config revision changes so the checkmarks follow a
    // disabled instance / removed entry, not just this screen's own writes.
    val effective = remember(config, overrideId) {
        providerRepository.resolveVoiceOutputChoice()
    }
    val effectiveEntryId = effective.entry?.second?.id
    val systemEffective = effective.entry == null

    val systemEntryId = "${SystemVoiceIds.BUILTIN_PROVIDER_ID}/${SystemVoiceIds.SYSTEM_TTS}"
    val boundGroup = config.voiceOutputGroupId?.let { gid ->
        config.modelGroups.find { it.id == gid }
    }

    // TTS-capable models from enabled providers, grouped by provider so the
    // list reads like the rest of the picker surfaces. A model that only
    // ACCEPTS audio never shows here — isVoiceOutputCandidate is the same
    // capability gate the runtime resolver uses.
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

    fun selectSystem() {
        providerRepository.voiceOutputOverrideEntryId = systemEntryId
    }

    fun selectSystemVoice(voice: SystemTtsVoiceCatalog.SystemVoice) {
        VoiceOutputState.setSystemVoice(voice.name, voice.label)
        selectSystem()
    }

    fun cloudSelected(entry: ModelEntry): Boolean =
        overrideId == entry.id || (overrideId == null && effectiveEntryId == entry.id)

    SettingsScaffold(
        title = stringResource(R.string.voice_synthesis_title),
        onBack = onBack,
    ) {
        Spacer(Modifier.height(SectionDesign.FirstSectionTopGap))
        SectionFooter(text = stringResource(R.string.voice_synthesis_intro))

        // ── Follow the Default Models › Voice Output group, when one is bound.
        // Override = null is the runtime's "follow the group" state; without
        // this row, visiting the screen and picking nothing would still leave
        // the group in charge, but the user could never knowingly go BACK to it.
        if (boundGroup != null) {
            SettingsSection(
                header = stringResource(R.string.voice_synthesis_follow_group_header),
                footer = stringResource(R.string.voice_synthesis_follow_group_desc),
            ) {
                SettingsChoiceRow(
                    title = stringResource(
                        R.string.voice_synthesis_follow_group,
                        boundGroup.name,
                    ),
                    selected = overrideId == null,
                    onSelect = {
                        providerRepository.voiceOutputOverrideEntryId = null
                    },
                    showDivider = false,
                )
            }
        }

        // ── System voice ──
        SettingsSection(
            header = stringResource(R.string.voice_synthesis_system_header),
            footer = stringResource(R.string.voice_synthesis_system_footer),
        ) {
            SettingsChoiceRow(
                title = stringResource(R.string.voice_synthesis_auto),
                selected = systemEffective && systemVoiceName == null,
                onSelect = {
                    VoiceOutputState.setSystemVoice(null, null)
                    selectSystem()
                },
                showDivider = systemVoices.isNotEmpty(),
            )
            systemVoices.forEachIndexed { index, voice ->
                SettingsChoiceRow(
                    title = voice.label,
                    subtitle = voice.name,
                    selected = systemEffective && systemVoiceName == voice.name,
                    onSelect = { selectSystemVoice(voice) },
                    showDivider = index < systemVoices.lastIndex,
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
                        // rename it, so fall back to the general settings
                        // page rather than crashing.
                        runCatching {
                            context.startActivity(
                                Intent("com.android.settings.TTS_SETTINGS"),
                            )
                        }.onFailure {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_SETTINGS),
                                )
                            }
                        }
                    },
                    showDivider = false,
                )
            }
        }

        // ── Cloud models ──
        if (cloudProviders.isEmpty()) {
            SettingsSection(
                header = stringResource(R.string.voice_synthesis_cloud_header),
                footer = stringResource(R.string.voice_synthesis_cloud_footer),
            ) {
                SettingsRow(
                    title = stringResource(R.string.voice_synthesis_cloud_empty),
                    subtitle = stringResource(R.string.voice_synthesis_cloud_empty_desc),
                    showDivider = true,
                )
                SettingsRow(
                    title = stringResource(R.string.voice_synthesis_add_provider),
                    onClick = onAddProvider,
                    showDivider = false,
                )
            }
        } else {
            cloudProviders.forEach { (instance, entries) ->
                SettingsSection(
                    header = instance.label.ifBlank { instance.providerType.displayName },
                ) {
                    entries.forEachIndexed { index, entry ->
                        SettingsChoiceRow(
                            title = entry.model.displayName.ifBlank { entry.model.id },
                            subtitle = entry.baseModel.id,
                            selected = cloudSelected(entry),
                            onSelect = {
                                providerRepository.voiceOutputOverrideEntryId = entry.id
                            },
                            showDivider = index < entries.lastIndex,
                        )
                    }
                }
            }
            // One shared footer after the provider cards, not one per provider.
            SectionFooter(text = stringResource(R.string.voice_synthesis_cloud_footer))
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

package com.openminis.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import com.openminis.app.icons.outlined.Apps
import com.openminis.app.icons.outlined.AutoAwesome
import com.openminis.app.icons.outlined.Bolt
import com.openminis.app.icons.outlined.Cloud
import com.openminis.app.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Place
import com.openminis.app.icons.outlined.Schedule
import com.openminis.app.icons.outlined.Lan
import com.openminis.app.icons.outlined.Dns
import com.openminis.app.icons.outlined.Router
import com.openminis.app.icons.outlined.VpnLock
import com.openminis.app.icons.outlined.BarChart
import com.openminis.app.icons.outlined.BatteryFull
import com.openminis.app.icons.outlined.BugReport
import com.openminis.app.icons.outlined.Dashboard
import com.openminis.app.icons.outlined.Description
import androidx.compose.material.icons.outlined.Email
import com.openminis.app.icons.outlined.Extension
import com.openminis.app.icons.outlined.Feedback
import com.openminis.app.icons.outlined.Backup
import com.openminis.app.icons.outlined.Folder
import com.openminis.app.icons.outlined.FolderShared
import com.openminis.app.icons.outlined.FrontHand
import androidx.compose.material.icons.outlined.Info
import com.openminis.app.icons.outlined.Inventory2
import com.openminis.app.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Lock
import com.openminis.app.icons.outlined.Mic
import com.openminis.app.icons.outlined.Palette
import com.openminis.app.icons.outlined.Groups
import com.openminis.app.icons.outlined.Handyman
import com.openminis.app.icons.outlined.Psychology
import com.openminis.app.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Settings
import com.openminis.app.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Star
import com.openminis.app.icons.outlined.Terminal
import com.openminis.app.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.openminis.app.BuildConfig
import com.openminis.app.R
import com.openminis.app.data.model.MiniMaxVoices
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.speech.VoiceOutputState
import com.openminis.app.ui.components.openExternalUrl
import com.openminis.app.i18n.uppercaseForDisplay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    /** [T-android-voice-synthesis-settings] Summary source for the voice row. */
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
    onProvidersClick: () -> Unit,
    onModelGroupsClick: () -> Unit,
    /** Settings › Default Models: bind a group to each role. */
    onDefaultModelsClick: () -> Unit = {},
    /** [T-android-voice-synthesis-settings] System voice or cloud TTS model. */
    onVoiceSynthesisClick: () -> Unit = {},
    /** [T-android-ondevice-models] 手动下载语音识别与快路径端侧模型。 */
    onOnDeviceModelsClick: () -> Unit = {},
    onUsageClick: () -> Unit = {},
    /** Settings › Agent Runtime: tools, skills, memory, MCP, runtime models. */
    onAgentRuntimeClick: () -> Unit = {},
    /** Settings › General › Message context. */
    onContextClick: () -> Unit = {},
    onAppearanceClick: () -> Unit = {},
    onRootfsClick: () -> Unit = {},
    onBackupClick: () -> Unit = {},
    onSharedFoldersClick: () -> Unit = {},
    onMountedFoldersClick: () -> Unit = {},
    /** [T-net-android-phase2] Settings › Network › Network Routing. */
    onNetworkClick: () -> Unit = {},
    /** [FEAT-261021-04] The other rows of the Network section. */
    onTailscaleClick: () -> Unit = {},
    onProxiesClick: () -> Unit = {},
    onContainerDnsClick: () -> Unit = {},
    onPermissionsClick: () -> Unit = {},
    onBackgroundClick: () -> Unit = {},
    onVoiceWakeClick: () -> Unit = {},
    onLogsClick: () -> Unit = {},
    onAboutClick: () -> Unit = {},
) {
    val context = LocalContext.current
    var showFeedbackSheet by remember { mutableStateOf(false) }
    // [T-music-player-top] The music settings sheet (NetEase login + proxy
    // address) is hosted here so it does not need a navigation route.
    var showMusicSheet by remember { mutableStateOf(false) }
    // [T-android-brave-web-search] Brave Search API key entry.
    var showWebSearchSheet by remember { mutableStateOf(false) }

    // [T-android-voice-synthesis-settings] The row states what will actually
    // speak next, so "currently MiniMax" is visible without opening the page.
    // config is collected (not read once) so the summary follows an override
    // picked on the child screen as soon as the user comes back.
    val providerConfig by providerRepository.config.collectAsState()
    val systemVoiceLabel by VoiceOutputState.systemVoiceLabel.collectAsState()
    LaunchedEffect(Unit) { VoiceOutputState.init(context) }
    val voiceChoice = remember(providerConfig) {
        providerRepository.resolveVoiceOutputChoice()
    }
    val cloudModelLabel = voiceSynthesisSummary(voiceChoice.entry)
    // MiniMax's voice is a separate axis from its model, so name it here too;
    // otherwise the row would say "MiniMax Speech 2.8 HD" while twenty
    // different voices could be in effect.
    val cloudVoiceLabel = voiceChoice.entry?.let { (instance, _) ->
        if (MiniMaxVoices.isMiniMax(instance)) {
            providerRepository.voiceOutputCloudVoice(instance.id)
                ?.let { MiniMaxVoices.label(it) }
        } else {
            null
        }
    }
    val systemVoiceText = stringResource(R.string.voice_synthesis_system_label)
    val voiceSubtitle = if (cloudModelLabel != null) {
        val label = listOfNotNull(cloudModelLabel, cloudVoiceLabel).joinToString(" · ")
        stringResource(R.string.settings_voice_synthesis_current, label)
    } else {
        val label = listOfNotNull(systemVoiceText, systemVoiceLabel).joinToString(" · ")
        stringResource(R.string.settings_voice_synthesis_current, label)
    }

    // [T-android-ondevice-models] 端侧模型的状态行：模型不随 APK 安装，
    // 新装用户从这里进入手动下载。
    val onDeviceStates by com.openminis.app.ondevice.OnDeviceModelManager.states.collectAsState()
    LaunchedEffect(Unit) { com.openminis.app.ondevice.OnDeviceModelManager.refreshAsync(context) }
    val onDeviceSubtitle = when {
        onDeviceStates.values.any { it.isBusy } ->
            stringResource(R.string.settings_ondevice_models_downloading)
        else -> {
            val installed = com.openminis.app.ondevice.OnDeviceModelManager.specs.count { spec ->
                onDeviceStates[spec.key]?.installed == true
            }
            if (installed == 0) {
                stringResource(R.string.settings_ondevice_models_none)
            } else {
                stringResource(R.string.settings_ondevice_models_some, installed)
            }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // -- AI & Assistant --
            //
            // The model rows read in dependency order: a provider supplies
            // models, a group is an ordered route over models from any
            // provider, Default Models binds a group to each role, and Speech
            // Synthesis picks the voice that actually speaks replies. The
            // Agent Runtime hub holds the nine capability rows that used to be
            // top-level; they configure one subsystem, not nine separate
            // choices.
            SettingsSection(
                title = stringResource(R.string.settings_section_ai_assistant),
                footer = stringResource(R.string.settings_section_llm_providers_footer),
            ) {
                SettingsItem(
                    icon = Icons.Outlined.Lock,
                    iconColor = Color(0xFF007AFF),
                    title = stringResource(R.string.settings_manage_providers),
                    subtitle = stringResource(R.string.settings_manage_providers_subtitle),
                    onClick = onProvidersClick,
                )
                SettingsItem(
                    icon = Icons.Outlined.Settings,
                    iconColor = Color(0xFF007AFF),
                    title = stringResource(R.string.settings_model_groups),
                    subtitle = stringResource(R.string.settings_model_groups_subtitle),
                    onClick = onModelGroupsClick,
                )
                SettingsItem(
                    icon = Icons.Outlined.Star,
                    iconColor = Color(0xFFFF9500),
                    title = stringResource(R.string.settings_default_models),
                    subtitle = stringResource(R.string.settings_default_models_subtitle),
                    onClick = onDefaultModelsClick,
                )
                // [T-android-voice-synthesis-settings] Which voice speaks
                // replies: the on-device engine (with a specific voice) or a
                // cloud TTS model such as MiniMax. Sits right after Default
                // Models because it is the direct form of that role binding.
                SettingsItem(
                    icon = Icons.Outlined.RecordVoiceOver,
                    iconColor = Color(0xFFAF52DE),
                    title = stringResource(R.string.settings_voice_synthesis),
                    subtitle = voiceSubtitle,
                    onClick = onVoiceSynthesisClick,
                )
                // [T-android-ondevice-models] 语音识别（SenseVoice）与快路径小模型
                // （Pocket 0.3B）都不进 APK；新装用户在这里下载，已装的显示进度/
                // 容量。放在语音合成下面，因为它们同属「端侧运行」这一组选择。
                SettingsItem(
                    icon = Icons.Outlined.CloudDownload,
                    iconColor = Color(0xFF34C759),
                    title = stringResource(R.string.settings_ondevice_models),
                    subtitle = onDeviceSubtitle,
                    onClick = onOnDeviceModelsClick,
                )
                SettingsItem(
                    icon = Icons.Outlined.AutoAwesome,
                    iconColor = Color(0xFF5E5CE6),
                    title = stringResource(R.string.settings_section_agent_runtime),
                    subtitle = stringResource(R.string.settings_agent_runtime_subtitle),
                    onClick = onAgentRuntimeClick,
                )
                SettingsItem(
                    icon = Icons.Outlined.BarChart,
                    iconColor = Color(0xFF007AFF),
                    title = stringResource(R.string.settings_token_usage),
                    subtitle = stringResource(R.string.settings_token_usage_subtitle),
                    onClick = onUsageClick,
                    showDivider = false,
                )
            }

            // -- General --
            SettingsSection(title = stringResource(R.string.settings_section_general)) {
                SettingsItem(
                    icon = Icons.Outlined.Palette,
                    iconColor = Color(0xFF5856D6),
                    title = stringResource(R.string.settings_section_appearance),
                    subtitle = stringResource(R.string.settings_appearance_subtitle),
                    onClick = onAppearanceClick,
                )
                SettingsItem(
                    icon = Icons.Outlined.Schedule,
                    iconColor = Color(0xFF5AC8FA),
                    title = stringResource(R.string.settings_section_message_context),
                    subtitle = stringResource(R.string.settings_context_entry_subtitle),
                    onClick = onContextClick,
                )
                SettingsItem(
                    icon = Icons.Outlined.MusicNote,
                    iconColor = Color(0xFFE0342E),
                    title = stringResource(R.string.settings_music),
                    subtitle = stringResource(R.string.settings_music_subtitle),
                    onClick = { showMusicSheet = true },
                    showDivider = false,
                )
            }

            // -- Storage --
            SettingsSection(title = stringResource(R.string.settings_section_storage)) {
                SettingsItem(
                    icon = Icons.Outlined.Inventory2,
                    iconColor = Color(0xFF007AFF),
                    title = stringResource(R.string.settings_section_storage),
                    subtitle = stringResource(R.string.settings_storage_subtitle),
                    onClick = onRootfsClick,
                )
                SettingsItem(
                    icon = Icons.Outlined.Folder,
                    iconColor = Color(0xFF34C759),
                    title = stringResource(R.string.settings_shared_folders),
                    subtitle = stringResource(R.string.settings_shared_folders_subtitle),
                    onClick = onSharedFoldersClick,
                )
                SettingsItem(
                    icon = Icons.Outlined.FolderShared,
                    iconColor = Color(0xFFFF9500),
                    title = stringResource(R.string.settings_mount_external_folders),
                    subtitle = stringResource(R.string.settings_mount_external_folders_subtitle),
                    onClick = onMountedFoldersClick,
                )
                SettingsItem(
                    icon = Icons.Outlined.Backup,
                    iconColor = Color(0xFF34C759),
                    title = stringResource(R.string.settings_backup_restore),
                    subtitle = stringResource(R.string.settings_backup_restore_subtitle),
                    onClick = onBackupClick,
                    showDivider = false,
                )
            }

            // -- Network -- [FEAT-261021-04, iOS c77be4fb6] Its own section
            // below Storage: Network Routing, Tailscale, Proxies, the Shell
            // Container DNS and the Brave Search key.
            SettingsSection(title = stringResource(R.string.network_title)) {
                SettingsItem(
                    icon = Icons.Outlined.Lan,
                    iconColor = Color(0xFF007AFF),
                    title = stringResource(R.string.network_routing_title),
                    subtitle = null,
                    onClick = onNetworkClick,
                )
                SettingsItem(
                    icon = Icons.Outlined.VpnLock,
                    iconColor = Color(0xFF5856D6),
                    title = stringResource(R.string.network_tailscale),
                    subtitle = null,
                    onClick = onTailscaleClick,
                )
                SettingsItem(
                    icon = Icons.Outlined.Router,
                    iconColor = Color(0xFFFF9500),
                    title = stringResource(R.string.network_proxies),
                    subtitle = null,
                    onClick = onProxiesClick,
                )
                SettingsItem(
                    icon = Icons.Outlined.Dns,
                    iconColor = Color(0xFF8E8E93),
                    title = stringResource(R.string.network_container_dns),
                    subtitle = null,
                    onClick = onContainerDnsClick,
                    showDivider = true,
                )
                // [T-android-brave-web-search] Brave Search API key.
                SettingsItem(
                    icon = Icons.Outlined.Search,
                    iconColor = Color(0xFF5856D6),
                    title = stringResource(R.string.settings_web_search),
                    subtitle = stringResource(R.string.settings_web_search_subtitle),
                    onClick = { showWebSearchSheet = true },
                    showDivider = false,
                )
            }

            // -- Device & Permissions --
            // Three one-row sections (Permissions, Background, Voice wake)
            // collapse into one scannable group.
            SettingsSection(title = stringResource(R.string.settings_section_device)) {
                SettingsItem(
                    icon = Icons.Outlined.Shield,
                    iconColor = Color(0xFF007AFF),
                    title = stringResource(R.string.settings_section_permissions),
                    subtitle = stringResource(R.string.settings_permissions_subtitle),
                    onClick = onPermissionsClick,
                )
                SettingsItem(
                    icon = Icons.Outlined.BatteryFull,
                    iconColor = Color(0xFFFF9500),
                    title = stringResource(R.string.bg_section_header),
                    subtitle = stringResource(R.string.bg_section_subtitle),
                    onClick = onBackgroundClick,
                )
                // [T-android-voice-wake] Voice wake used to be buried inside
                // the background screen; it stays a direct row here.
                SettingsItem(
                    icon = Icons.Outlined.Mic,
                    iconColor = Color(0xFF34C759),
                    title = stringResource(R.string.settings_voice_wake_master),
                    subtitle = stringResource(R.string.settings_voice_wake_entry_subtitle),
                    onClick = onVoiceWakeClick,
                    showDivider = false,
                )
            }

            // -- About & Support --
            SettingsSection(title = stringResource(R.string.settings_section_support)) {
                // [T-update-channels] The update badge's Settings layer.
                val updateBadge by com.openminis.app.data.UpdateStateStore.hasUpdateBadge.collectAsState()
                SettingsItem(
                    icon = Icons.Outlined.Description,
                    iconColor = Color(0xFF007AFF),
                    title = stringResource(R.string.settings_section_logs),
                    subtitle = stringResource(R.string.settings_logs_subtitle),
                    onClick = onLogsClick,
                )
                SettingsItem(
                    icon = Icons.Outlined.Info,
                    iconColor = Color(0xFF007AFF),
                    title = stringResource(R.string.settings_about_minis),
                    subtitle = stringResource(R.string.settings_about_subtitle),
                    onClick = onAboutClick,
                    badge = updateBadge,
                )
                SettingsItem(
                    icon = Icons.Outlined.FrontHand,
                    iconColor = Color(0xFF007AFF),
                    title = stringResource(R.string.settings_privacy_policy),
                    subtitle = null,
                    // iOS canonical URL — ContentView.swift / AddProviderView.swift
                    onClick = { openExternalUrl(context, "https://openminis.github.io/privacy-policy.html") },
                )
                SettingsItem(
                    icon = Icons.Outlined.Feedback,
                    iconColor = Color(0xFF007AFF),
                    title = stringResource(R.string.settings_feedback),
                    subtitle = null,
                    onClick = { showFeedbackSheet = true },
                    showDivider = false,
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    // [T-music-player-top] NetEase login + proxy address.
    if (showMusicSheet) {
        com.openminis.app.ui.media.music.MusicSettingsSheet(
            onDismiss = { showMusicSheet = false },
        )
    }

    // [T-android-brave-web-search] Brave Search API key.
    if (showWebSearchSheet) {
        WebSearchSettingsSheet(onDismiss = { showWebSearchSheet = false })
    }

    if (showFeedbackSheet) {
        ModalBottomSheet(onDismissRequest = { showFeedbackSheet = false }) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                FeedbackSheetItem(
                    icon = Icons.Outlined.BugReport,
                    title = stringResource(R.string.settings_submit_github_issues),
                    onClick = {
                        showFeedbackSheet = false
                        openExternalUrl(context, buildBugReportUrl())
                    },
                )
                FeedbackSheetItem(
                    icon = Icons.AutoMirrored.Outlined.Send,
                    title = stringResource(R.string.settings_feedback_telegram),
                    onClick = {
                        showFeedbackSheet = false
                        openExternalUrl(context, "https://t.me/+2NzhOJuzRyI1YmM1")
                    },
                )
                FeedbackSheetItem(
                    icon = Icons.Outlined.Email,
                    title = stringResource(R.string.settings_feedback_email),
                    onClick = {
                        showFeedbackSheet = false
                        openExternalUrl(context, buildFeedbackMailto())
                    },
                )
            }
        }
    }
}

@Composable
private fun FeedbackSheetItem(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Build the GitHub Issues "new bug report" URL with the body pre-filled
 * from the existing bug-report template. Platform / OS version / app
 * version / device model are injected so the report arrives ready to
 * triage instead of asking the user to fill in environment details.
 *
 * URL shape:
 *   https://github.com/OpenMinis/OpenMinis/issues/new
 *     ?template=bug_report.md
 *     &title=[Bug]
 *     &body=<percent-encoded markdown>
 *
 * The body is a Markdown template with sections for Problem Summary,
 * Basic Information (table — auto-filled), Steps to Reproduce, Error
 * Details (fenced code block), Expected Behavior, and Additional
 * Information.
 */
private fun buildBugReportUrl(): String {
    val osVersion = android.os.Build.VERSION.RELEASE
    val sdkInt = android.os.Build.VERSION.SDK_INT
    val versionName = BuildConfig.VERSION_NAME
    val versionCode = BuildConfig.VERSION_CODE
    val manufacturer = android.os.Build.MANUFACTURER
    val model = android.os.Build.MODEL

    // Body matches the spec template. Triple-backtick fences are written
    // as "```" — they survive percent-encoding cleanly. Indentation here
    // is significant: trimIndent() removes the common Kotlin indentation
    // but preserves the Markdown structure as-is.
    val body = """
        ## 📝 Problem Summary

        <!-- Briefly describe the issue you encountered -->


        ## 📱 Basic Information

        | Field | Value |
        |-------|-------|
        | Platform | Android |
        | OS Version | Android $osVersion (API $sdkInt) |
        | Minis Version | $versionName (build $versionCode) |
        | Device Model | $manufacturer $model |

        ## 🔁 Steps to Reproduce

        1.
        2.
        3.

        ## ❌ Error Details

        ```
        paste error here
        ```

        ## ✅ Expected Behavior



        ## 🗂️ Additional Information

    """.trimIndent()

    val encodedBody = java.net.URLEncoder.encode(body, "UTF-8")
    // Title carries a "[Bug] " prefix with a trailing space so the cursor
    // lands after it on GitHub's page; encode the space as %20 explicitly
    // since URLEncoder turns spaces into '+' which GitHub also accepts but
    // the spec calls for the literal "[Bug] " form.
    val title = java.net.URLEncoder.encode("[Bug] ", "UTF-8")
    return "https://github.com/zhrl55-svg/MiniPilot/issues/new" +
        "?template=bug_report.md" +
        "&title=$title" +
        "&body=$encodedBody"
}

/**
 * Compose a `mailto:` URL with a prefilled subject and body that include
 * app version, Android version, and device model. Mirrors iOS
 * `ContentView.makeFeedbackEmailURL()`.
 */
private fun buildFeedbackMailto(): String {
    val body = """
        Please describe your feedback:


        ---
        App Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})
        Android Version: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})
        Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}

        Screenshot (optional): Please attach a screenshot if relevant.
    """.trimIndent()
    val subject = java.net.URLEncoder.encode("MiniPilot Feedback", "UTF-8")
    val encodedBody = java.net.URLEncoder.encode(body, "UTF-8")
    return "mailto:dev@openminis.app?subject=$subject&body=$encodedBody"
}

/**
 * A grouped settings section with header and optional footer, matching iOS grouped List sections.
 */
@Composable
internal fun SettingsSection(
    title: String,
    footer: String? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp),
    ) {
        // Section header
        Text(
            text = title.uppercaseForDisplay(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.5.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )

        // Section card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(color = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            content()
        }

        // Section footer
        if (footer != null) {
            Text(
                text = footer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                lineHeight = 16.sp,
            )
        }
    }
}

/**
 * A single settings row item with colored icon, title, optional subtitle, and chevron.
 * Styled to match iOS settings rows with SF Symbol-like colored circle icons.
 */
@Composable
internal fun SettingsItem(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    showDivider: Boolean = true,
    // [T-update-channels] A small dot before the chevron.
    badge: Boolean = false,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Colored circle icon (matching iOS settings style)
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(
                        color = iconColor,
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
            }

            Spacer(Modifier.width(14.dp))

            // Title + subtitle
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (badge) {
                UpdateBadgeDot()
                Spacer(Modifier.width(8.dp))
            }

            // Chevron
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp),
            )
        }

        // Divider between items (inset to match icon alignment)
        if (showDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 58.dp, end = 14.dp)
                    .height(0.5.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            )
        }
    }
}


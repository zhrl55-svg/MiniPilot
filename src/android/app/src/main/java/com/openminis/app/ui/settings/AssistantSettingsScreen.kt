package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import com.openminis.app.icons.outlined.AutoAwesome
import com.openminis.app.icons.outlined.Bolt
import com.openminis.app.icons.outlined.Dashboard
import com.openminis.app.icons.outlined.Extension
import com.openminis.app.icons.outlined.Groups
import com.openminis.app.icons.outlined.Handyman
import com.openminis.app.icons.outlined.Psychology
import com.openminis.app.icons.outlined.Terminal
import com.openminis.app.icons.outlined.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.ui.chat.HelperAccentStatic
import com.openminis.app.ui.components.SectionDesign

/**
 * The nine Agent Runtime rows that used to live directly in the main settings
 * list. They are configuration for one subsystem, not nine top-level choices,
 * so they now share one page; the main list keeps a single row.
 */
@Composable
fun AssistantSettingsScreen(
    onBack: () -> Unit,
    onAgentToolsClick: () -> Unit,
    onAgentsClick: () -> Unit,
    onSkillsClick: () -> Unit,
    onSoulClick: () -> Unit,
    onMemoryClick: () -> Unit,
    onMcpClick: () -> Unit,
    onEnvVarsClick: () -> Unit,
    onAssistantRoutesClick: () -> Unit,
    onRuntimeModelsClick: () -> Unit,
) {
    val context = LocalContext.current
    // [T-local-assistant] 助理模式（端侧直连）。默认关 —— 端侧是加速器，
    // 不是主链路；打开后只有规则命中且端侧抽全参数的那批指令走端侧。
    var localAssistantMode by remember {
        mutableStateOf(com.openminis.app.llm.mnn.LocalAssistantPrefs.isEnabled(context))
    }

    SettingsScaffold(
        title = stringResource(R.string.settings_section_agent_runtime),
        onBack = onBack,
    ) {
        Spacer(Modifier.height(SectionDesign.FirstSectionTopGap))

        SettingsSection(title = stringResource(R.string.assistant_section_capabilities)) {
            SettingsItem(
                icon = Icons.Outlined.Handyman,
                iconColor = Color(0xFF5E5CE6),
                title = stringResource(R.string.settings_agent_tools),
                subtitle = stringResource(R.string.settings_agent_tools_subtitle),
                onClick = onAgentToolsClick,
            )
            SettingsItem(
                icon = Icons.Outlined.Groups,
                iconColor = HelperAccentStatic,
                title = stringResource(R.string.settings_agents),
                subtitle = stringResource(R.string.settings_agents_subtitle),
                onClick = onAgentsClick,
            )
            SettingsItem(
                icon = Icons.Outlined.Extension,
                iconColor = Color(0xFF007AFF),
                title = stringResource(R.string.settings_skills),
                subtitle = stringResource(R.string.settings_skills_subtitle),
                onClick = onSkillsClick,
                showDivider = false,
            )
        }

        SettingsSection(title = stringResource(R.string.assistant_section_identity)) {
            SettingsItem(
                icon = Icons.Outlined.AutoAwesome,
                iconColor = Color(0xFFFF9500),
                title = stringResource(R.string.settings_soul),
                subtitle = stringResource(R.string.settings_soul_subtitle),
                onClick = onSoulClick,
            )
            SettingsItem(
                icon = Icons.Outlined.Psychology,
                iconColor = Color(0xFF5856D6),
                title = stringResource(R.string.settings_memory),
                subtitle = stringResource(R.string.settings_memory_subtitle),
                onClick = onMemoryClick,
                showDivider = false,
            )
        }

        SettingsSection(title = stringResource(R.string.assistant_section_connections)) {
            // [T-mcp-integration-android] Dashboard (2x2 block grid) instead of
            // Extension so MCP does not share the Skills row's icon.
            SettingsItem(
                icon = Icons.Outlined.Dashboard,
                iconColor = Color(0xFF30B0C7),
                title = stringResource(R.string.settings_mcp),
                subtitle = stringResource(R.string.settings_mcp_subtitle),
                onClick = onMcpClick,
            )
            SettingsItem(
                icon = Icons.Outlined.Terminal,
                iconColor = Color(0xFF34C759),
                title = stringResource(R.string.settings_env_vars),
                subtitle = stringResource(R.string.settings_env_vars_subtitle),
                onClick = onEnvVarsClick,
                showDivider = false,
            )
        }

        SettingsSection(title = stringResource(R.string.assistant_section_on_device)) {
            SettingsSwitchRow(
                icon = Icons.Outlined.Bolt,
                iconColor = Color(0xFF5856D6),
                title = stringResource(R.string.settings_assistant_mode),
                subtitle = stringResource(R.string.settings_assistant_mode_subtitle),
                checked = localAssistantMode,
                onCheckedChange = { on ->
                    localAssistantMode = on
                    com.openminis.app.llm.mnn.LocalAssistantPrefs.setEnabled(context, on)
                },
            )
            SettingsItem(
                icon = Icons.Outlined.Tune,
                iconColor = Color(0xFF5856D6),
                title = stringResource(R.string.settings_assistant_routes),
                subtitle = stringResource(R.string.settings_assistant_routes_subtitle),
                onClick = onAssistantRoutesClick,
                showDivider = false,
            )
        }

        SettingsSection(title = stringResource(R.string.settings_runtime_models)) {
            SettingsItem(
                icon = Icons.Outlined.Psychology,
                iconColor = Color(0xFFAF52DE),
                title = stringResource(R.string.settings_runtime_models),
                subtitle = stringResource(R.string.settings_runtime_models_subtitle),
                onClick = onRuntimeModelsClick,
                showDivider = false,
            )
        }

        Spacer(Modifier.height(SectionDesign.SectionTopGap))
    }
}

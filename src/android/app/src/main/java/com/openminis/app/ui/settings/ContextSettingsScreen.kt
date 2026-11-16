package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Wifi
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
import com.openminis.app.ui.components.SectionDesign

/**
 * [T-android-context-injection] The nine per-source switches for the
 * request-only device context block. They are one area, not nine separate
 * destinations. Notification titles is a second-level switch: it only has an
 * effect while Recent notifications is on.
 */
@Composable
fun ContextSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var contextConfig by remember {
        mutableStateOf(
            com.openminis.app.assistant.context.ContextInjectionSettings.load(context),
        )
    }
    val setContextConfig: (com.openminis.app.assistant.context.ContextInjectionSettings.Config) -> Unit =
        { updated ->
            contextConfig = updated
            com.openminis.app.assistant.context.ContextInjectionSettings.save(context, updated)
        }

    SettingsScaffold(
        title = stringResource(R.string.settings_section_message_context),
        onBack = onBack,
    ) {
        Spacer(Modifier.height(SectionDesign.FirstSectionTopGap))
        SettingsSection(
            footer = stringResource(R.string.settings_context_footer),
        ) {
            SettingsSwitchRow(
                icon = Icons.Outlined.Schedule,
                iconColor = Color(0xFF5856D6),
                title = stringResource(R.string.settings_context_time),
                subtitle = stringResource(R.string.settings_context_time_subtitle),
                checked = contextConfig.time,
                onCheckedChange = { on -> setContextConfig(contextConfig.copy(time = on)) },
            )
            SettingsSwitchRow(
                icon = Icons.Outlined.BatteryFull,
                iconColor = Color(0xFF34C759),
                title = stringResource(R.string.settings_context_battery),
                subtitle = stringResource(R.string.settings_context_battery_subtitle),
                checked = contextConfig.battery,
                onCheckedChange = { on -> setContextConfig(contextConfig.copy(battery = on)) },
            )
            SettingsSwitchRow(
                icon = Icons.Outlined.Wifi,
                iconColor = Color(0xFF00C7BE),
                title = stringResource(R.string.settings_context_network),
                subtitle = stringResource(R.string.settings_context_network_subtitle),
                checked = contextConfig.network,
                onCheckedChange = { on -> setContextConfig(contextConfig.copy(network = on)) },
            )
            SettingsSwitchRow(
                icon = Icons.Outlined.Cloud,
                iconColor = Color(0xFF5AC8FA),
                title = stringResource(R.string.settings_context_weather),
                subtitle = stringResource(R.string.settings_context_weather_subtitle),
                checked = contextConfig.weather,
                onCheckedChange = { on -> setContextConfig(contextConfig.copy(weather = on)) },
            )
            SettingsSwitchRow(
                icon = Icons.Outlined.Place,
                iconColor = Color(0xFFFF9500),
                title = stringResource(R.string.settings_context_location),
                subtitle = stringResource(R.string.settings_context_location_subtitle),
                checked = contextConfig.location,
                onCheckedChange = { on ->
                    setContextConfig(contextConfig.copy(location = on))
                    // Warm the place name so the readable location (instead of
                    // raw coordinates) is ready for the next request.
                    if (on) {
                        com.openminis.app.assistant.context.WeatherContextCache
                            .refreshAsync(context)
                    }
                },
            )
            SettingsSwitchRow(
                icon = Icons.Outlined.Notifications,
                iconColor = Color(0xFFFF3B30),
                title = stringResource(R.string.settings_context_notifications),
                subtitle = stringResource(R.string.settings_context_notifications_subtitle),
                checked = contextConfig.notifications,
                onCheckedChange = { on -> setContextConfig(contextConfig.copy(notifications = on)) },
            )
            SettingsSwitchRow(
                icon = Icons.Outlined.TextFields,
                iconColor = Color(0xFFAF52DE),
                title = stringResource(R.string.settings_context_notification_titles),
                subtitle = stringResource(R.string.settings_context_notification_titles_subtitle),
                checked = contextConfig.notificationTitles,
                onCheckedChange = { on ->
                    setContextConfig(contextConfig.copy(notificationTitles = on))
                },
                enabled = contextConfig.notifications,
            )
            SettingsSwitchRow(
                icon = Icons.Outlined.Apps,
                iconColor = Color(0xFF007AFF),
                title = stringResource(R.string.settings_context_current_app),
                subtitle = stringResource(R.string.settings_context_current_app_subtitle),
                checked = contextConfig.currentApp,
                onCheckedChange = { on -> setContextConfig(contextConfig.copy(currentApp = on)) },
            )
            SettingsSwitchRow(
                icon = Icons.Outlined.Description,
                iconColor = Color(0xFF8E8E93),
                title = stringResource(R.string.settings_context_screen_text),
                subtitle = stringResource(R.string.settings_context_screen_text_subtitle),
                checked = contextConfig.screenText,
                onCheckedChange = { on -> setContextConfig(contextConfig.copy(screenText = on)) },
                showDivider = false,
            )
        }
        Spacer(Modifier.height(SectionDesign.SectionTopGap))
    }
}

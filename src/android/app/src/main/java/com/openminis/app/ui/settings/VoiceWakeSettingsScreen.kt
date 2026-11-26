package com.openminis.app.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import com.openminis.app.icons.outlined.Fingerprint
import com.openminis.app.icons.outlined.Layers
import com.openminis.app.icons.outlined.Mic
import com.openminis.app.icons.outlined.RecordVoiceOver
import com.openminis.app.icons.outlined.Timer
import com.openminis.app.icons.outlined.Tune
import com.openminis.app.icons.outlined.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.openminis.app.R
import com.openminis.app.speech.wake.WakeWordController
import com.openminis.app.speech.wake.WakeWordSettings

/**
 * Voice-wake settings. Split out of [BackgroundSettingsScreen] so the
 * "Background & notifications" and "Background voice wake" rows on the main
 * settings screen open two distinct pages instead of the same screen twice.
 *
 * Owns the whole wake-word stack: enable switch, wake word, floating ball,
 * wake tone, voiceprint (+ threshold) and the voice-dialogue sleep window.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceWakeSettingsScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    WakeWordSettings.init(context)
    val voiceWakeEnabled by WakeWordSettings.enabled.collectAsState()
    // The preference stays on; this only reflects whether the live service
    // is actually able to hear. A degraded service is rebuilt on foreground.
    val voiceWakeDegraded by WakeWordController.degraded.collectAsState()
    val voiceWakeWord by WakeWordSettings.wakeWord.collectAsState()
    val voiceWakeOverlay by WakeWordSettings.showOverlay.collectAsState()
    val voiceWakeTone by WakeWordSettings.toneEnabled.collectAsState()
    val voiceWakeVoiceprint by WakeWordSettings.voiceprintEnabled.collectAsState()
    val voiceWakeThreshold by WakeWordSettings.voiceprintThreshold.collectAsState()
    val voiceSleepWindowMs by WakeWordSettings.voiceSleepWindowMs.collectAsState()

    var canDrawOverlays by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                Settings.canDrawOverlays(context),
        )
    }
    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var showWakeWordDialog by remember { mutableStateOf(false) }
    var wakeWordDraft by remember { mutableStateOf(voiceWakeWord) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        // The service is allowed to run without notification permission; it
        // simply cannot show its ongoing row. Enable either way.
        WakeWordController.setEnabled(context, true)
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        micGranted = granted
        if (granted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                WakeWordController.setEnabled(context, true)
            }
        }
    }

    fun enableVoiceWake() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        micGranted = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            WakeWordController.setEnabled(context, true)
        }
    }

    // Re-probe on ON_RESUME: the user may have just granted the overlay or
    // microphone permission in system settings, so the rows must reflect it
    // without an app restart.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canDrawOverlays =
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                        Settings.canDrawOverlays(context)
                micGranted =
                    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_voice_wake)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.size(8.dp))

            // Wake-word master switch.
            BgToggleRow(
                icon = Icons.Outlined.Mic,
                iconColor = Color(0xFFFF9500),
                title = stringResource(R.string.settings_voice_wake),
                checked = voiceWakeEnabled && !voiceWakeDegraded,
                onCheckedChange = { wanted ->
                    if (wanted) {
                        enableVoiceWake()
                    } else {
                        WakeWordController.setEnabled(context, false)
                    }
                },
            )
            BgFooter(
                when {
                    voiceWakeDegraded ->
                        stringResource(R.string.settings_voice_wake_degraded)
                    voiceWakeEnabled && !micGranted ->
                        stringResource(R.string.settings_voice_wake_permission_needed)
                    else ->
                        stringResource(R.string.settings_voice_wake_footer)
                },
            )

            Spacer(Modifier.size(8.dp))
            BgRow(
                icon = Icons.Outlined.RecordVoiceOver,
                iconColor = Color(0xFF34C759),
                title = stringResource(R.string.settings_voice_wake_word),
                subtitle = voiceWakeWord,
                onClick = {
                    wakeWordDraft = voiceWakeWord
                    showWakeWordDialog = true
                },
            )
            BgFooter(stringResource(R.string.settings_voice_wake_word_footer))

            Spacer(Modifier.size(8.dp))
            BgToggleRow(
                icon = Icons.Outlined.Layers,
                iconColor = Color(0xFF5856D6),
                title = stringResource(R.string.settings_voice_wake_overlay),
                checked = voiceWakeOverlay,
                onCheckedChange = { wanted ->
                    if (wanted && !canDrawOverlays) {
                        try {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + context.packageName),
                            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                            context.startActivity(intent)
                        } catch (_: Throwable) {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        }
                        WakeWordSettings.setShowOverlay(context, true)
                    } else {
                        WakeWordSettings.setShowOverlay(context, wanted)
                    }
                },
            )
            BgFooter(
                if (!canDrawOverlays && voiceWakeOverlay) {
                    stringResource(R.string.settings_voice_wake_overlay_permission_needed)
                } else {
                    stringResource(R.string.settings_voice_wake_overlay_footer)
                },
            )

            Spacer(Modifier.size(8.dp))
            BgToggleRow(
                icon = Icons.Outlined.VolumeUp,
                iconColor = Color(0xFFAF52DE),
                title = stringResource(R.string.settings_voice_wake_tone),
                checked = voiceWakeTone,
                onCheckedChange = { WakeWordSettings.setToneEnabled(context, it) },
            )
            BgFooter(stringResource(R.string.settings_voice_wake_tone_footer))

            Spacer(Modifier.size(8.dp))
            BgToggleRow(
                icon = Icons.Outlined.Fingerprint,
                iconColor = Color(0xFF30B0C7),
                title = stringResource(R.string.settings_voice_wake_voiceprint),
                checked = voiceWakeVoiceprint,
                onCheckedChange = { wanted ->
                    WakeWordSettings.setVoiceprintEnabled(context, wanted)
                    if (!wanted) {
                        // Forgetting the template is what "off" has to mean:
                        // a stale template would still be compared against
                        // the next time the gate is switched back on.
                        WakeWordSettings.clearVoiceprintTemplate(context)
                    }
                },
            )
            BgFooter(stringResource(R.string.settings_voice_wake_voiceprint_footer))

            if (voiceWakeVoiceprint) {
                Spacer(Modifier.size(8.dp))
                BgRow(
                    icon = Icons.Outlined.Tune,
                    iconColor = Color(0xFF30B0C7),
                    title = stringResource(R.string.settings_voice_wake_threshold),
                    subtitle = "%.2f".format(voiceWakeThreshold),
                    onClick = null,
                )
                Slider(
                    value = voiceWakeThreshold,
                    onValueChange = { WakeWordSettings.setVoiceprintThreshold(context, it) },
                    valueRange = WakeWordSettings.MIN_VOICEPRINT_THRESHOLD..
                        WakeWordSettings.MAX_VOICEPRINT_THRESHOLD,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                BgFooter(stringResource(R.string.settings_voice_wake_threshold_footer))
            }

            // How long a voice dialogue waits for speech before going back
            // to standby. One setting drives both background wake listening
            // and hold-to-talk; applied live via the StateFlow.
            Spacer(Modifier.size(8.dp))
            BgRow(
                icon = Icons.Outlined.Timer,
                iconColor = Color(0xFF30B0C7),
                title = stringResource(R.string.settings_voice_sleep_window),
                subtitle = stringResource(
                    R.string.settings_voice_sleep_window_value,
                    (voiceSleepWindowMs / 1000).toInt(),
                ),
                onClick = null,
            )
            Slider(
                value = voiceSleepWindowMs / 1000f,
                onValueChange = {
                    WakeWordSettings.setVoiceSleepWindowMs(context, (it * 1000).toLong())
                },
                valueRange = (WakeWordSettings.MIN_VOICE_SLEEP_WINDOW_MS / 1000f)..
                    (WakeWordSettings.MAX_VOICE_SLEEP_WINDOW_MS / 1000f),
                steps = ((WakeWordSettings.MAX_VOICE_SLEEP_WINDOW_MS -
                    WakeWordSettings.MIN_VOICE_SLEEP_WINDOW_MS) / 1000).toInt() - 1,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            BgFooter(stringResource(R.string.settings_voice_sleep_window_footer))

            Spacer(Modifier.size(16.dp))
        }

        if (showWakeWordDialog) {
            val valid = WakeWordSettings.parseWords(wakeWordDraft).isNotEmpty()
            AlertDialog(
                onDismissRequest = { showWakeWordDialog = false },
                title = { Text(stringResource(R.string.settings_voice_wake_word_dialog_title)) },
                text = {
                    Column {
                        OutlinedTextField(
                            value = wakeWordDraft,
                            onValueChange = { wakeWordDraft = it },
                            singleLine = true,
                            isError = !valid,
                            label = { Text(stringResource(R.string.settings_voice_wake_word)) },
                            placeholder = { Text(stringResource(R.string.settings_voice_wake_word_hint)) },
                        )
                        if (!valid) {
                            Text(
                                text = stringResource(R.string.settings_voice_wake_word_invalid),
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = valid,
                        onClick = {
                            if (WakeWordSettings.setWakeWord(context, wakeWordDraft)) {
                                showWakeWordDialog = false
                            }
                        },
                    ) {
                        Text(stringResource(R.string.settings_voice_wake_word_save))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showWakeWordDialog = false }) {
                        Text(stringResource(R.string.settings_voice_wake_word_cancel))
                    }
                },
            )
        }
    }
}

package com.openminis.app.ui.settings

import android.os.Build
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.openminis.app.ondevice.OnDeviceModelManager
import com.openminis.app.ui.components.MinisAlertDialog
import com.openminis.app.ui.components.SectionDesign
import com.openminis.app.ui.components.SectionFooter

/**
 * 设置 › 端侧模型 —— 手动下载不随安装包分发的两个本机模型：
 *
 *  - 语音识别：SenseVoice Small（int8，sherpa-onnx），语音输入 / 唤醒转写用；
 *  - 快路径小模型：Pocket 0.3B（int4，MNN），助理快速路径的本地兜底。
 *
 * 下载逻辑与状态在 [OnDeviceModelManager]（object 级协程），所以离开本页
 * 不会中断，回来时进度条接着走。
 */
@Composable
fun OnDeviceModelsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val states by OnDeviceModelManager.states.collectAsState()

    var downloadTarget by remember { mutableStateOf<OnDeviceModelManager.ModelSpec?>(null) }
    var deleteTarget by remember { mutableStateOf<OnDeviceModelManager.ModelSpec?>(null) }

    LaunchedEffect(Unit) { OnDeviceModelManager.refreshAsync(context) }

    SettingsScaffold(
        title = stringResource(R.string.ondevice_models_title),
        onBack = onBack,
    ) {
        Spacer(Modifier.height(SectionDesign.FirstSectionTopGap))
        SectionFooter(text = stringResource(R.string.ondevice_models_intro))

        for (spec in OnDeviceModelManager.specs) {
            val state = states[spec.key]
                ?: OnDeviceModelManager.ModelState(totalBytes = spec.totalBytes)
            val title = stringResource(
                when (spec.key) {
                    OnDeviceModelManager.ASR_SENSEVOICE -> R.string.ondevice_asr_name
                    else -> R.string.ondevice_fast_llm_name
                },
            )
            val description = stringResource(
                when (spec.key) {
                    OnDeviceModelManager.ASR_SENSEVOICE -> R.string.ondevice_asr_desc
                    else -> R.string.ondevice_fast_llm_desc
                },
            )
            val abiUnsupported = spec.key == OnDeviceModelManager.FAST_LLM &&
                !Build.SUPPORTED_ABIS.any { it.startsWith("arm64") }

            SettingsSection(
                header = stringResource(
                    when (spec.key) {
                        OnDeviceModelManager.ASR_SENSEVOICE -> R.string.ondevice_section_asr
                        else -> R.string.ondevice_section_fast_llm
                    },
                ),
                footer = description,
            ) {
                ModelDownloadRow(
                    title = title,
                    state = state,
                    abiUnsupported = abiUnsupported,
                    onDownload = { downloadTarget = spec },
                    onCancel = { OnDeviceModelManager.cancel(spec.key) },
                    onDelete = { deleteTarget = spec },
                )
            }
        }

        SectionFooter(text = stringResource(R.string.ondevice_models_source_note))
    }

    val downloadSpec = downloadTarget
    if (downloadSpec != null) {
        val state = states[downloadSpec.key]
        val size = Formatter.formatShortFileSize(
            context,
            state?.totalBytes ?: downloadSpec.totalBytes,
        )
        val name = stringResource(
            when (downloadSpec.key) {
                OnDeviceModelManager.ASR_SENSEVOICE -> R.string.ondevice_asr_name
                else -> R.string.ondevice_fast_llm_name
            },
        )
        MinisAlertDialog(
            onDismissRequest = { downloadTarget = null },
            title = stringResource(R.string.ondevice_download_title, name),
            text = stringResource(R.string.ondevice_download_body, size),
            confirmText = stringResource(R.string.ondevice_action_download),
            onConfirm = {
                downloadTarget = null
                OnDeviceModelManager.install(context, downloadSpec.key)
            },
        )
    }

    val removeSpec = deleteTarget
    if (removeSpec != null) {
        val state = states[removeSpec.key]
        val size = Formatter.formatShortFileSize(
            context,
            state?.totalBytes ?: removeSpec.totalBytes,
        )
        val name = stringResource(
            when (removeSpec.key) {
                OnDeviceModelManager.ASR_SENSEVOICE -> R.string.ondevice_asr_name
                else -> R.string.ondevice_fast_llm_name
            },
        )
        MinisAlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = stringResource(R.string.ondevice_delete_title, name),
            text = stringResource(R.string.ondevice_delete_body, size),
            confirmText = stringResource(R.string.ondevice_action_delete),
            isDestructive = true,
            onConfirm = {
                deleteTarget = null
                OnDeviceModelManager.delete(context, removeSpec.key)
            },
        )
    }
}

@Composable
private fun ModelDownloadRow(
    title: String,
    state: OnDeviceModelManager.ModelState,
    abiUnsupported: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val total = if (state.totalBytes > 0) state.totalBytes else 1L

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = statusText(context, state),
            style = MaterialTheme.typography.bodySmall,
            color = if (state.phase == OnDeviceModelManager.Phase.FAILED) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )

        if (state.isBusy) {
            Spacer(Modifier.height(10.dp))
            val percent = (state.bytesDone * 100.0 / total).toInt().coerceIn(0, 100)
            LinearProgressIndicator(
                progress = { (state.bytesDone.toFloat() / total).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(
                    R.string.ondevice_status_downloading,
                    percent,
                    Formatter.formatShortFileSize(context, state.bytesDone),
                    Formatter.formatShortFileSize(context, state.totalBytes),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state.phase == OnDeviceModelManager.Phase.FAILED) {
            state.error?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        if (abiUnsupported) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.ondevice_abi_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(2.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                state.isBusy -> TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.ondevice_action_cancel))
                }
                !state.installed -> TextButton(
                    onClick = onDownload,
                    enabled = !abiUnsupported,
                ) {
                    Text(
                        stringResource(
                            if (state.partialBytes > 0) R.string.ondevice_action_resume
                            else R.string.ondevice_action_download,
                        ),
                    )
                }
            }
            if (state.installed && !state.isBusy) {
                TextButton(onClick = onDelete) {
                    Text(
                        stringResource(R.string.ondevice_action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun statusText(
    context: android.content.Context,
    state: OnDeviceModelManager.ModelState,
): String = when {
    state.phase == OnDeviceModelManager.Phase.VERIFYING ->
        stringResource(R.string.ondevice_status_verifying)
    state.phase == OnDeviceModelManager.Phase.DOWNLOADING ->
        stringResource(R.string.ondevice_status_downloading_short)
    state.installed -> stringResource(
        R.string.ondevice_status_installed,
        Formatter.formatShortFileSize(context, state.totalBytes),
    )
    state.phase == OnDeviceModelManager.Phase.FAILED ->
        stringResource(R.string.ondevice_status_failed)
    state.partialBytes > 0 -> stringResource(
        R.string.ondevice_status_partial,
        Formatter.formatShortFileSize(context, state.partialBytes),
    )
    else -> stringResource(
        R.string.ondevice_status_missing,
        Formatter.formatShortFileSize(context, state.totalBytes),
    )
}

package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.tools.WebSearchSettings

/**
 * [T-android-brave-web-search] Settings › Network › Web Search.
 *
 * One field, one key. The value is stored encrypted and only read by
 * [com.openminis.app.tools.WebSearchTool]; an empty field disables web search.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebSearchSettingsSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var key by remember { mutableStateOf(WebSearchSettings.apiKey(context)) }
    var savedKey by remember { mutableStateOf(WebSearchSettings.apiKey(context)) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.web_search_key_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.web_search_key_subtitle),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                shape = RoundedCornerShape(12.dp),
                label = { Text(stringResource(R.string.web_search_key_label)) },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = {
                        key = ""
                        WebSearchSettings.setApiKey(context, "")
                        savedKey = ""
                    },
                ) {
                    Text(stringResource(R.string.web_search_key_clear))
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        WebSearchSettings.setApiKey(context, key)
                        savedKey = WebSearchSettings.apiKey(context)
                    },
                    enabled = key.trim().isNotEmpty(),
                ) {
                    Text(stringResource(R.string.web_search_key_save))
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(
                    if (savedKey.isNotBlank()) {
                        R.string.web_search_key_configured
                    } else {
                        R.string.web_search_key_missing
                    },
                ),
                fontSize = 12.sp,
                color = if (savedKey.isNotBlank()) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

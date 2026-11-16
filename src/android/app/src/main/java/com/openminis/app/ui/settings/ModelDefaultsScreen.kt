package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.components.SectionDesign
import com.openminis.app.ui.components.SectionFooter

/**
 * Bind a model group to each role. Split out of ModelGroupsScreen: a group is
 * a reusable route, a role is where that route is used. Keeping both on one
 * page made "new group → pick members → bind it" read as a single opaque
 * screen and hid the fact that one group can serve several roles.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelDefaultsScreen(
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
) {
    val config by providerRepository.config.collectAsState()
    val groups = config.modelGroups

    SettingsScaffold(
        title = stringResource(R.string.model_defaults_title),
        onBack = onBack,
    ) {
        Spacer(Modifier.height(SectionDesign.FirstSectionTopGap))
        SectionFooter(text = stringResource(R.string.model_defaults_intro))

        RoleSection(
            title = stringResource(R.string.model_defaults_primary),
            description = stringResource(R.string.model_defaults_primary_desc),
            groups = groups,
            selectedId = config.defaultPrimaryGroupId,
            onSelect = { providerRepository.defaultPrimaryGroupId = it },
        )
        RoleSection(
            title = stringResource(R.string.model_defaults_sub),
            description = stringResource(R.string.model_defaults_sub_desc),
            groups = groups,
            selectedId = config.defaultSubGroupId,
            onSelect = { providerRepository.defaultSubGroupId = it },
        )
        RoleSection(
            title = stringResource(R.string.model_groups_voice_input),
            description = stringResource(R.string.model_defaults_voice_input_desc),
            groups = groups,
            selectedId = config.voiceInputGroupId,
            onSelect = { providerRepository.voiceInputGroupId = it },
        )
        RoleSection(
            title = stringResource(R.string.model_groups_voice_output),
            description = stringResource(R.string.model_defaults_voice_output_desc),
            groups = groups,
            selectedId = config.voiceOutputGroupId,
            onSelect = { providerRepository.voiceOutputGroupId = it },
        )
        // [T-android-vision-group / GH#182] The group whose vision-capable
        // members read images for a main model that cannot natively see them.
        RoleSection(
            title = stringResource(R.string.model_groups_vision),
            description = stringResource(R.string.model_defaults_vision_desc),
            groups = groups,
            selectedId = config.visionGroupId,
            onSelect = { providerRepository.visionGroupId = it },
        )

        Spacer(Modifier.height(SectionDesign.SectionTopGap))
    }
}

@Composable
private fun RoleSection(
    title: String,
    description: String,
    groups: List<ModelGroup>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
) {
    SettingsSection(header = title, footer = description) {
        SettingsCardBlock {
            RoleGroupDropdown(
                groups = groups,
                selectedId = selectedId,
                onSelect = onSelect,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoleGroupDropdown(
    groups: List<ModelGroup>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = groups.find { it.id == selectedId }?.name
        ?: stringResource(R.string.model_groups_none)

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.model_defaults_group_label)) },
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.model_groups_none)) },
                onClick = {
                    onSelect(null)
                    expanded = false
                },
            )
            groups.forEach { group ->
                DropdownMenuItem(
                    text = { Text(group.name) },
                    onClick = {
                        onSelect(group.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

package com.openminis.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import com.openminis.app.icons.filled.DragHandle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.components.MinisOutlinedButton
import com.openminis.app.ui.components.SectionDesign
import com.openminis.app.ui.components.SectionFooter
import com.openminis.app.ui.components.SectionHeader
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.ReorderableLazyListState
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * Models the assistant may call at runtime (`minis-model-use`), plus the
 * groups it may expand into its own model set.
 *
 * This used to be the bottom half of ModelGroupsScreen, below role bindings
 * and the group list. Three different concepts shared one page, and the one
 * users came for (model groups) was buried under one they rarely touch.
 * The set now lives on its own page under Settings › Agent Runtime, where
 * "what the agent may do" already lives; model groups stay purely about
 * reusable fallback routes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuntimeModelsScreen(
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
    onAddModels: () -> Unit,
    onAddGroups: () -> Unit,
) {
    val config by providerRepository.config.collectAsState()
    val lazyListState = rememberLazyListState()

    // Two reorder zones share one lazyListState — entries ("runtime_entry:")
    // and groups ("runtime_group:"). Cross-zone drags are refused so a row
    // cannot be dropped into the other data shape (a no-op beats a snap-back).
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val fromKey = from.key as? String ?: return@rememberReorderableLazyListState
        val toKey = to.key as? String ?: return@rememberReorderableLazyListState
        when {
            fromKey.startsWith("runtime_entry:") && toKey.startsWith("runtime_entry:") -> {
                val fromId = fromKey.removePrefix("runtime_entry:")
                val toId = toKey.removePrefix("runtime_entry:")
                val cur = providerRepository.config.value.agentLoopModelEntryIds.toList()
                val fromIdx = cur.indexOf(fromId)
                val toIdx = cur.indexOf(toId)
                if (fromIdx < 0 || toIdx < 0) return@rememberReorderableLazyListState
                providerRepository.reorderAgentLoopEntries(
                    cur.toMutableList().apply { add(toIdx, removeAt(fromIdx)) },
                )
            }
            fromKey.startsWith("runtime_group:") && toKey.startsWith("runtime_group:") -> {
                val fromId = fromKey.removePrefix("runtime_group:")
                val toId = toKey.removePrefix("runtime_group:")
                val cur = providerRepository.config.value.agentLoopGroupIds.toList()
                val fromIdx = cur.indexOf(fromId)
                val toIdx = cur.indexOf(toId)
                if (fromIdx < 0 || toIdx < 0) return@rememberReorderableLazyListState
                providerRepository.reorderAgentLoopGroups(
                    cur.toMutableList().apply { add(toIdx, removeAt(fromIdx)) },
                )
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.runtime_models_title)) },
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
        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(SectionDesign.screenBackgroundColor()),
        ) {
            item("top_gap") { Spacer(modifier = Modifier.height(SectionDesign.FirstSectionTopGap)) }
            item("runtime_intro") {
                SectionFooter(text = stringResource(R.string.runtime_models_intro))
            }
            runtimeModelRows(
                providerRepository = providerRepository,
                config = config,
                reorderState = reorderState,
            )
            item("runtime_add_buttons") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MinisOutlinedButton(
                        onClick = onAddModels,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.agent_loop_section_add_models))
                    }
                    MinisOutlinedButton(
                        onClick = onAddGroups,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.agent_loop_section_add_groups))
                    }
                }
            }
            item("bottom_gap") { Spacer(modifier = Modifier.height(SectionDesign.SectionTopGap)) }
        }
    }
}

/**
 * Pinned groups first (picking a group implicitly pins all its members), then
 * pinned individual entries. Rows must stay top-level LazyColumn items so
 * ReorderableLazyListState can reorder them.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.runtimeModelRows(
    providerRepository: ProviderRepository,
    config: ProviderConfig,
    reorderState: ReorderableLazyListState,
) {
    // [T-android-agentloop-dup-key-crash] distinctBy is belt-and-suspenders
    // alongside the data-layer sink dedup: a config persisted with duplicate
    // ids would otherwise yield duplicate LazyColumn/Reorderable keys.
    val pinnedGroups = config.agentLoopGroupIds
        .mapNotNull { gid -> config.modelGroups.find { it.id == gid } }
        .distinctBy { it.id }
    val pinnedEntries = config.agentLoopModelEntryIds
        .mapNotNull { eid -> config.modelEntries.find { it.id == eid } }
        .distinctBy { it.id }
    val totalRows = pinnedGroups.size + pinnedEntries.size

    if (totalRows == 0) {
        item("runtime_empty") {
            Box(modifier = Modifier.cardRow(isFirst = true, isLast = true)) {
                Text(
                    text = stringResource(R.string.agent_loop_section_empty_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                )
            }
        }
        return
    }

    itemsIndexed(pinnedGroups, key = { _, g -> "runtime_group:${g.id}" }) { index, group ->
        ReorderableItem(state = reorderState, key = "runtime_group:${group.id}") { _ ->
            val resolvedCount = group.memberEntryIds.count { mid ->
                config.modelEntries.any { it.id == mid }
            }
            val subtitle = if (resolvedCount == 1) {
                stringResource(R.string.agent_loop_models_model_count_singular)
            } else {
                stringResource(R.string.agent_loop_models_model_count_plural, resolvedCount)
            }
            Column {
                if (index != 0) SectionDividerInsetCard()
                Box(
                    modifier = Modifier.cardRow(
                        isFirst = index == 0,
                        isLast = index == totalRows - 1,
                    ),
                ) {
                    RuntimeModelRow(
                        title = group.name,
                        subtitle = subtitle,
                        badge = stringResource(R.string.agent_loop_section_group_badge),
                        onRemove = { providerRepository.removeAgentLoopGroup(group.id) },
                        dragHandleModifier = with(this@ReorderableItem) { Modifier.draggableHandle() },
                    )
                }
            }
        }
    }

    itemsIndexed(pinnedEntries, key = { _, e -> "runtime_entry:${e.id}" }) { index, entry ->
        ReorderableItem(state = reorderState, key = "runtime_entry:${entry.id}") { _ ->
            val instanceLabel = config.instances.find { it.id == entry.providerInstanceId }?.label
            val absIndex = pinnedGroups.size + index
            Column {
                if (absIndex != 0) SectionDividerInsetCard()
                Box(
                    modifier = Modifier.cardRow(
                        isFirst = absIndex == 0,
                        isLast = absIndex == totalRows - 1,
                    ),
                ) {
                    RuntimeModelRow(
                        title = entry.model.displayName,
                        subtitle = instanceLabel,
                        badge = null,
                        onRemove = { providerRepository.removeAgentLoopEntry(entry.id) },
                        dragHandleModifier = with(this@ReorderableItem) { Modifier.draggableHandle() },
                    )
                }
            }
        }
    }
}

/** One pinned row: drag handle, title + optional subtitle + optional badge, remove. */
@Composable
private fun RuntimeModelRow(
    title: String,
    subtitle: String?,
    badge: String?,
    onRemove: () -> Unit,
    dragHandleModifier: Modifier = Modifier,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The handle must be an IconButton — a bare Icon has no pointer
        // consumer, so draggableHandle() never sees ACTION_DOWN/MOVE.
        IconButton(onClick = {}, modifier = dragHandleModifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Default.DragHandle,
                contentDescription = stringResource(R.string.model_group_detail_drag_to_reorder),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp),
            )
        }
        Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                )
                if (badge != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    BadgeLabel(badge, MaterialTheme.colorScheme.primary)
                }
            }
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.agent_loop_section_remove),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

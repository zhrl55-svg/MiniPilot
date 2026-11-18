package com.openminis.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.openminis.app.R

/**
 * [T-android-settings-field-selection-menu] A text-selection toolbar that
 * draws its menu **inside the app window** instead of in a system popup.
 *
 * Why not the platform toolbar: Compose's Android implementation shows the
 * Cut/Copy/Paste/Select-all bar in a separate `PopupWindow`. That window is
 * outside the app's own window, so when the phone is driven from a PC
 * (screen projection / mirroring with pointer forwarding) the projected
 * pointer never reaches it: the touch lands on the app window underneath,
 * the popup is dismissed as a click-outside, and the menu vanishes before
 * the user can reach it. On the phone itself the native bar works fine
 * (verified on ELS-AN00, build 57: long-pressing 自定义 API 地址 shows
 * 剪切/复制/粘贴/搜索/翻译), which is exactly why the bug only shows up
 * under projection.
 *
 * A menu drawn inside the app window is part of the same input surface as
 * the field, so it survives projection and its buttons can be clicked with
 * the PC pointer.
 *
 * The toolbar still delegates every action back to Compose: Compose decides
 * which actions exist (copy only with a selection, paste only with
 * clipboard content, …) and hands us the callbacks in [showMenu]. We only
 * render them.
 *
 * Scope: the settings text fields ([SectionTextField], [DialogTextField]).
 * The chat keeps its own markdown selection toolbar — that one is already
 * drawn inside the app window.
 */
internal class InWindowTextToolbar : TextToolbar {

    internal data class State(
        val visible: Boolean = false,
        /** Selection rect Compose reported, in window coordinates. */
        val rect: Rect? = null,
        val onCutRequested: (() -> Unit)? = null,
        val onCopyRequested: (() -> Unit)? = null,
        val onPasteRequested: (() -> Unit)? = null,
        val onSelectAllRequested: (() -> Unit)? = null,
    )

    internal var state by mutableStateOf(State())
        private set

    override val status: TextToolbarStatus
        get() = if (state.visible) TextToolbarStatus.Shown else TextToolbarStatus.Hidden

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        state = State(
            visible = true,
            rect = rect,
            onCutRequested = onCutRequested,
            onCopyRequested = onCopyRequested,
            onPasteRequested = onPasteRequested,
            onSelectAllRequested = onSelectAllRequested,
        )
    }

    override fun hide() {
        state = state.copy(visible = false)
    }

    /**
     * The menu entries, in the platform menu's order. Only the actions
     * Compose offered (i.e. the callbacks it handed us) are listed — a field
     * with no clipboard content has no 粘贴 entry, and so on.
     */
    internal fun entries(): List<FieldMenuEntry> {
        if (!state.visible) return emptyList()
        return buildList {
            state.onCutRequested?.let { add(FieldMenuEntry(FieldMenuAction.CUT, it)) }
            state.onCopyRequested?.let { add(FieldMenuEntry(FieldMenuAction.COPY, it)) }
            state.onPasteRequested?.let { add(FieldMenuEntry(FieldMenuAction.PASTE, it)) }
            state.onSelectAllRequested?.let { add(FieldMenuEntry(FieldMenuAction.SELECT_ALL, it)) }
        }
    }
}

/** One button of the selection menu. */
internal data class FieldMenuEntry(val action: FieldMenuAction, val onClick: () -> Unit)

/** The actions the selection menu can offer. */
internal enum class FieldMenuAction { CUT, COPY, PASTE, SELECT_ALL }

/** Height of the floating bar — fixed so its placement needs no measuring. */
private val MENU_HEIGHT = 44.dp

/** Gap between the field and the bar. */
private val MENU_GAP = 6.dp

/**
 * Renders the selection bar for the field this host sits in. Must be a child
 * of the same [BoxScope] as the field, so the bar can be placed relative to
 * it.
 *
 * @param fieldHeight height of the text field (its bottom edge anchors the bar).
 * @param fieldTopInWindow the field's top edge in window coordinates — used to
 *   flip the bar above the field when there is no room below it.
 */
@Composable
internal fun BoxScope.InWindowTextToolbarHost(
    toolbar: InWindowTextToolbar,
    fieldHeight: Dp,
    fieldTopInWindow: Dp,
) {
    val state = toolbar.state
    if (!state.visible) return
    val entries = toolbar.entries()
    if (entries.isEmpty()) return

    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val fitsBelow = fieldTopInWindow + fieldHeight + MENU_HEIGHT + MENU_GAP <= screenHeight

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        modifier = Modifier
            .align(Alignment.TopStart)
            .offset(y = if (fitsBelow) fieldHeight + MENU_GAP else -(MENU_HEIGHT + MENU_GAP))
            .height(MENU_HEIGHT),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            entries.forEach { entry ->
                Text(
                    text = stringResource(entry.action.labelRes()),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .clickable {
                            entry.onClick()
                            toolbar.hide()
                        }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

private fun FieldMenuAction.labelRes(): Int = when (this) {
    FieldMenuAction.CUT -> R.string.settings_field_menu_cut
    FieldMenuAction.COPY -> R.string.selection_copy
    FieldMenuAction.PASTE -> R.string.settings_field_menu_paste
    FieldMenuAction.SELECT_ALL -> R.string.settings_field_menu_select_all
}

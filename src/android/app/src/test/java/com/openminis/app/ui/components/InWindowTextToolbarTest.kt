package com.openminis.app.ui.components

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.TextToolbarStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-settings-field-selection-menu] The in-window selection toolbar is
 * a plain state holder: Compose decides which actions exist and hands us the
 * callbacks, we only decide what the menu shows and in which order.
 */
class InWindowTextToolbarTest {

    @Test
    fun `starts hidden with no entries`() {
        val toolbar = InWindowTextToolbar()
        assertEquals(TextToolbarStatus.Hidden, toolbar.status)
        assertTrue(toolbar.entries().isEmpty())
    }

    @Test
    fun `shows only the actions compose offered`() {
        val toolbar = InWindowTextToolbar()
        var copied = false

        // Copy only — no clipboard content, so Compose offered no paste.
        toolbar.showMenu(
            rect = Rect(0f, 0f, 10f, 10f),
            onCopyRequested = { copied = true },
            onPasteRequested = null,
            onCutRequested = null,
            onSelectAllRequested = null,
        )

        assertEquals(TextToolbarStatus.Shown, toolbar.status)
        val entries = toolbar.entries()
        assertEquals(1, entries.size)
        assertEquals(FieldMenuAction.COPY, entries[0].action)

        entries[0].onClick()
        assertTrue(copied)
    }

    @Test
    fun `keeps the platform menu order`() {
        val toolbar = InWindowTextToolbar()
        toolbar.showMenu(
            rect = Rect(0f, 0f, 10f, 10f),
            onCopyRequested = {},
            onPasteRequested = {},
            onCutRequested = {},
            onSelectAllRequested = {},
        )

        assertEquals(
            listOf(
                FieldMenuAction.CUT,
                FieldMenuAction.COPY,
                FieldMenuAction.PASTE,
                FieldMenuAction.SELECT_ALL,
            ),
            toolbar.entries().map { it.action },
        )
    }

    @Test
    fun `hide drops the menu`() {
        val toolbar = InWindowTextToolbar()
        toolbar.showMenu(
            rect = Rect(0f, 0f, 10f, 10f),
            onCopyRequested = {},
            onPasteRequested = {},
            onCutRequested = {},
            onSelectAllRequested = {},
        )
        assertEquals(TextToolbarStatus.Shown, toolbar.status)

        toolbar.hide()

        assertEquals(TextToolbarStatus.Hidden, toolbar.status)
        assertTrue(toolbar.entries().isEmpty())
    }
}

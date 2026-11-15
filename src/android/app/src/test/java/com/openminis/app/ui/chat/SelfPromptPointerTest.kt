package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-self-manual] The prompt carries a pointer to `minis-self`, not the
 * reference manuals themselves: every character in the fixed prompt is paid
 * for on every request, and the manuals are read on demand.
 */
class SelfPromptPointerTest {

    private val promptSource: String
        get() = ProductionSources.read("ui/chat/ChatViewModel.kt")

    @Test
    fun promptPointsAtMinisSelf() {
        val text = promptSource
        assertTrue(text.contains("minis-self tools"))
        assertTrue(text.contains("minis-self cli"))
        assertTrue(text.contains("minis-self settings"))
        assertTrue(text.contains("minis-self links"))
    }

    @Test
    fun longCliManualsNoLongerLiveInThePrompt() {
        val text = promptSource
        // These paragraphs now live in SelfManual and are served by
        // `minis-self cli`.
        assertFalse(text.contains("android-shizuku-cli — invoke privileged Android system APIs"))
        assertFalse(text.contains("minis-sessions-cli: Read chat history."))
        assertFalse(text.contains("Every write triggers an in-app confirmation sheet and is logged"))
    }
}

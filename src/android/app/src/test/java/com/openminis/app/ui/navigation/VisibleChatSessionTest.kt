package com.openminis.app.ui.navigation

import com.openminis.app.service.SessionActivityTracker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * [T-wake-current-chat] Pins the contract between the chat pane and the wake
 * pipeline: whatever session the detail pane is rendering is the one a spoken
 * command must land in.
 *
 * The bug this guards against (2026-11-22, device log): the user was reading
 * 6443d25e, switched to another chat inside the split scaffold — a switch the
 * NavHost route never sees — and the wake turn was appended to 5ae83322, the
 * chat the route had been created for.
 *
 * A Compose UI test would need `ui-test-junit4`, which this app's unit-test
 * source set does not carry, so the bridge itself is tested here: the effect
 * in [ChatSplitScaffold] always hands it the pane's `contentKey` (resolved,
 * null when the list is showing), and the tracker is what WakeConversation
 * reads.
 */
class VisibleChatSessionTest {

    @Before
    fun clearTracker() {
        SessionActivityTracker.setVisibleChatSession(null)
    }

    @After
    fun resetTracker() {
        SessionActivityTracker.setVisibleChatSession(null)
    }

    @Test
    fun `the list showing publishes null`() {
        publishVisibleChatSession("6443d25e-e1b3-4a45-b9fd-65baf37b0b47")
        assertEquals(
            "6443d25e-e1b3-4a45-b9fd-65baf37b0b47",
            SessionActivityTracker.visibleChatSession.value,
        )

        assertNull(publishVisibleChatSession(null))
        assertNull(SessionActivityTracker.visibleChatSession.value)
    }

    @Test
    fun `an in-pane switch republishes the newly shown chat`() {
        publishVisibleChatSession("6443d25e-e1b3-4a45-b9fd-65baf37b0b47") { it }
        // The user taps another row: only the pane navigator changes.
        publishVisibleChatSession("5ae83322-0000-0000-0000-000000000000") { it }
        assertEquals(
            "5ae83322-0000-0000-0000-000000000000",
            SessionActivityTracker.visibleChatSession.value,
        )
    }

    @Test
    fun `a draft is published under its canonical id once promoted`() {
        val draft = "__new__11111111-2222-3333-4444-555555555555"
        val canonical = "aaaa1111-2222-3333-4444-555555555555"
        val aliases = mutableMapOf<String, String>()

        // Un-promoted: the draft key itself is the target (the send that
        // follows will promote it).
        assertEquals(draft, publishVisibleChatSession(draft) { aliases[it] ?: it })
        assertEquals(draft, SessionActivityTracker.visibleChatSession.value)

        // Promoted on first send: the alias map now answers with the row id,
        // and the next publish follows it.
        aliases[draft] = canonical
        assertEquals(canonical, publishVisibleChatSession(draft) { aliases[it] ?: it })
        assertEquals(canonical, SessionActivityTracker.visibleChatSession.value)
    }
}

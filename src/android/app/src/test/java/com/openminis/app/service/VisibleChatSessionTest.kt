package com.openminis.app.service

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * [T-wake-current-chat] Pins the tracker contract the wake pipeline depends on:
 * the session published by the chat screen on screen is the one a spoken command
 * must land in.
 *
 * The device bug this guards against (2026-11-22 12:14): the user was reading
 * 6443d25e but the tracker still named 5ae83322 — a chat an earlier route
 * navigation had claimed and an in-pane switch never updated — so the wake turn
 * was appended to the wrong conversation.
 *
 * The publisher is now the ChatScreen rendering the pane's contentKey, and a
 * screen withdraws its claim only while it still owns the slot. That "only while
 * it still owns it" is the subtle half: during an in-pane switch the outgoing
 * screen can dispose AFTER the incoming one has claimed the slot, and an
 * unconditional clear would blank the correct claim and drop the next wake turn
 * back into the background 语音对话 session.
 */
class VisibleChatSessionTest {

    private val viewed = "6443d25e-e1b3-4a45-b9fd-65baf37b0b47"
    private val switchedTo = "5ae83322-0000-0000-0000-000000000000"

    @Before
    fun reset() {
        SessionActivityTracker.setVisibleChatSession(null)
    }

    @After
    fun cleanup() {
        SessionActivityTracker.setVisibleChatSession(null)
    }

    @Test
    fun `publishing the on-screen chat is what the wake pipeline reads`() {
        SessionActivityTracker.setVisibleChatSession(viewed)
        assertEquals(viewed, SessionActivityTracker.visibleChatSession.value)
    }

    @Test
    fun `an in-pane switch republishes the newly shown chat`() {
        SessionActivityTracker.setVisibleChatSession(viewed)
        // The user taps another row: only the pane navigator changes.
        SessionActivityTracker.setVisibleChatSession(switchedTo)
        assertEquals(switchedTo, SessionActivityTracker.visibleChatSession.value)
    }

    @Test
    fun `a stale dispose does not withdraw the newer claim`() {
        SessionActivityTracker.setVisibleChatSession(viewed)
        // The incoming chat screen claims the slot first...
        SessionActivityTracker.setVisibleChatSession(switchedTo)
        // ...then the outgoing one disposes and tries to withdraw.
        SessionActivityTracker.clearVisibleChatSessionIfCurrent(viewed)
        assertEquals(switchedTo, SessionActivityTracker.visibleChatSession.value)
    }

    @Test
    fun `the on-screen screen withdrawing clears the claim`() {
        SessionActivityTracker.setVisibleChatSession(viewed)
        SessionActivityTracker.clearVisibleChatSessionIfCurrent(viewed)
        assertNull(SessionActivityTracker.visibleChatSession.value)
    }

    @Test
    fun `null never clears somebody else's claim`() {
        SessionActivityTracker.setVisibleChatSession(viewed)
        SessionActivityTracker.clearVisibleChatSessionIfCurrent(null)
        assertEquals(viewed, SessionActivityTracker.visibleChatSession.value)
    }
}

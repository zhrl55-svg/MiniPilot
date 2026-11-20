package com.openminis.app.ui.chat

import com.openminis.app.agent.userask.PendingAsk
import com.openminis.app.agent.userask.UserAskAnswer
import com.openminis.app.agent.userask.UserAskOption
import com.openminis.app.agent.userask.UserAskQuestion
import com.openminis.app.agent.userask.UserAskQuestionType
import com.openminis.app.agent.userask.UserAskResolver
import com.openminis.app.agent.userask.UserAskState
import com.openminis.app.hub.HubApprovals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-hub-approval-banner] The global approval banner.
 *
 * Two things have to hold: the banner shows the hub's approval and nothing
 * else, and the answer it sends is one the hub can read. The second one is
 * the dangerous half — the hub's parser only understands an ANSWERED entry
 * whose text says 允许 or 拒绝, so a banner with its own wording would turn
 * every approval into a refusal.
 */
class HubApprovalBannerTest {

    private fun hubQuestion(): UserAskQuestion = UserAskQuestion(
        type = UserAskQuestionType.OPTIONS,
        question = "shell：cd /d/minis-src && git status",
        header = "AUSA 的远程请求",
        options = listOf(
            UserAskOption("允许一次", "只执行这一次"),
            UserAskOption("拒绝", "不执行"),
        ),
    )

    private fun ask(
        id: String = "hub-ap-1",
        state: UserAskState = UserAskState.PENDING,
        question: UserAskQuestion = hubQuestion(),
    ): PendingAsk = PendingAsk.create(
        requestId = id,
        sessionId = "session-1",
        toolCallId = "tool-1",
        questions = listOf(question),
        nowMs = 0L,
        timeoutMinutes = 10,
    ).copy(state = state)

    @Test
    fun `a pending hub approval is the one the banner shows`() {
        val pending = ask()
        val target = HubApprovalBannerLogic.target(mapOf(pending.requestId to pending))
        assertNotNull(target)
        assertEquals(0, target!!.questionIndex)
        assertEquals("AUSA 的远程请求", HubApprovalBannerLogic.question(target)?.header)
    }

    @Test
    fun `an ask of another kind is not this banner's business`() {
        val other = ask(id = "ask-1")
        assertNull(HubApprovalBannerLogic.target(mapOf(other.requestId to other)))
    }

    @Test
    fun `an approval that was already answered is not shown again`() {
        val done = ask(state = UserAskState.ANSWERED)
        assertNull(HubApprovalBannerLogic.target(mapOf(done.requestId to done)))
    }

    @Test
    fun `an ask without an options question cannot be answered from here`() {
        val confirm = ask(question = UserAskQuestion(type = UserAskQuestionType.CONFIRM, question = "继续？"))
        assertNull(HubApprovalBannerLogic.target(mapOf(confirm.requestId to confirm)))
    }

    /**
     * The answer the banner sends is built the way the card builds it: pick
     * the option, and the RESULT carries that option's label. This is the
     * text the hub's parser reads.
     */
    @Test
    fun `the allow button answers the way the hub reads it`() {
        val pending = ask()
        val target = HubApprovalBannerLogic.target(mapOf(pending.requestId to pending))!!
        val question = HubApprovalBannerLogic.question(target)!!
        val picked = (pending.answers.getOrNull(0) ?: UserAskAnswer()).withOption(0, question)
        val result = UserAskResolver.encode(listOf(UserAskResolver.answered(question, 0, picked)))
        assertTrue(HubApprovals.parseAllow(result))
    }

    @Test
    fun `the deny button answers the way the hub reads it`() {
        val pending = ask()
        val target = HubApprovalBannerLogic.target(mapOf(pending.requestId to pending))!!
        val question = HubApprovalBannerLogic.question(target)!!
        val picked = (pending.answers.getOrNull(0) ?: UserAskAnswer()).withOption(1, question)
        val result = UserAskResolver.encode(listOf(UserAskResolver.answered(question, 0, picked)))
        assertFalse(HubApprovals.parseAllow(result))
    }
}

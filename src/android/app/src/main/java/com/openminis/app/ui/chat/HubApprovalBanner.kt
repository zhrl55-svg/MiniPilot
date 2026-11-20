package com.openminis.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.agent.userask.PendingAsk
import com.openminis.app.agent.userask.UserAskAnswer
import com.openminis.app.agent.userask.UserAskCenter
import com.openminis.app.agent.userask.UserAskQuestion
import com.openminis.app.agent.userask.UserAskQuestionType

/**
 * [T-hub-approval-banner] A remote approval, visible from any screen.
 *
 * WHY: the hub's approval card is rendered INSIDE the tool block of the
 * session that asked for it ([com.openminis.app.hub.HubApprovals] → the
 * user-ask centre). A person looking at another screen never sees it, and
 * the request waits — for ten minutes, until the approval times out.
 *
 * This banner is a SECOND entrance to the same ask, never a second approval
 * mechanism: the buttons answer through [UserAskCenter] exactly the way the
 * card does, so the wait, the timeout and the result JSON stay the ones the
 * hub is already reading.
 *
 * The buttons carry the ask's OWN option labels. That is what ends up in the
 * result JSON, and the hub's parser looks for 允许 / 拒绝 in it — inventing a
 * different wording here would silently turn every approval into a refusal.
 */
@Composable
fun HubApprovalBanner(modifier: Modifier = Modifier) {
    val asks by UserAskCenter.asks.collectAsState()
    val target = remember(asks) { HubApprovalBannerLogic.target(asks) }
    AnimatedVisibility(
        visible = target != null,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = modifier,
    ) {
        val current = target ?: return@AnimatedVisibility
        val question = current.ask.questions.getOrNull(current.questionIndex)
        HubApprovalBannerCard(
            header = question?.header.orEmpty(),
            body = question?.question.orEmpty(),
            options = question?.options.orEmpty().map { it.label },
            onOption = { index -> HubApprovalBannerLogic.answer(current, index) },
        )
    }
}

@Composable
private fun HubApprovalBannerCard(
    header: String,
    body: String,
    options: List<String>,
    onOption: (Int) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xF2E53935),
        shadowElevation = 6.dp,
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .widthIn(max = 480.dp)
            .fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    if (header.isNotEmpty()) {
                        Text(
                            header,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        body,
                        color = Color.White.copy(alpha = 0.92f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth(),
            ) {
                options.forEachIndexed { index, label ->
                    TextButton(onClick = { onOption(index) }) {
                        Text(label, color = Color.White)
                    }
                }
            }
        }
    }
}

/**
 * The banner's brain, kept apart from the drawing so it can be tested.
 */
object HubApprovalBannerLogic {

    /** Hub approvals are the asks whose request id starts with this. */
    const val REQUEST_PREFIX = "hub-"

    /** An ask the banner can answer: a hub ask with an OPTIONS question. */
    data class Target(val ask: PendingAsk, val questionIndex: Int)

    /**
     * The approval the banner shows, or null when there is none.
     *
     * A resolved ask is not shown (the user already answered it), and an ask
     * of another kind is not this banner's business.
     */
    fun target(asks: Map<String, PendingAsk>): Target? =
        asks.values.firstNotNullOfOrNull { ask ->
            if (!ask.requestId.startsWith(REQUEST_PREFIX)) return@firstNotNullOfOrNull null
            if (ask.state.isTerminal) return@firstNotNullOfOrNull null
            val index = ask.questions.indexOfFirst { it.type == UserAskQuestionType.OPTIONS }
            if (index < 0) return@firstNotNullOfOrNull null
            Target(ask, index)
        }

    /**
     * Answer it the way the card does: pick the option, submit the question.
     *
     * The card's own path — [UserAskCenter.updateAnswer] then
     * [UserAskCenter.submitQuestion] — is what resolves the ask, hands
     * `wait()` its result JSON and keeps the first-wins race against the
     * timeout intact. The banner adds a door, it does not add a rule.
     */
    fun answer(target: Target, optionIndex: Int) {
        val question = target.ask.questions.getOrNull(target.questionIndex) ?: return
        val current = target.ask.answers.getOrNull(target.questionIndex) ?: UserAskAnswer()
        UserAskCenter.updateAnswer(
            target.ask.requestId,
            target.questionIndex,
            current.withOption(optionIndex, question),
        )
        UserAskCenter.submitQuestion(target.ask.requestId, target.questionIndex)
    }

    /** The question the banner shows for [target], or null. */
    fun question(target: Target): UserAskQuestion? =
        target.ask.questions.getOrNull(target.questionIndex)
}

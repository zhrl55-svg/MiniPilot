package com.openminis.app.agent.guard

import android.content.Context
import com.openminis.app.accessibility.A11yLabels
import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.agent.userask.PendingAsk
import com.openminis.app.agent.userask.UserAskCenter
import com.openminis.app.agent.userask.UserAskOption
import com.openminis.app.agent.userask.UserAskQuestion
import com.openminis.app.agent.userask.UserAskQuestionType
import com.openminis.app.agent.userask.UserAskResolver
import com.openminis.app.agent.userask.UserAskResultStatus
import com.openminis.app.agent.userask.UserAskRisk
import com.openminis.app.agent.userask.UserAskSettings
import com.openminis.app.logging.AppLogger
import org.json.JSONObject
import java.util.UUID

/**
 * Risk classification, audit and optional confirmation for tool calls.
 *
 * The audit line is written for every classified call. Confirmation is
 * OPT-IN: [enforceEnabled] reads `guard_enforce` from the `agent_settings`
 * preferences and defaults to **false**, so by default this object only
 * observes — every existing workflow (scheduled tasks, sub agents, headless
 * runs) behaves exactly as before.
 *
 * When enforcement is on, a call at or above [RiskLevel.EXTERNAL] asks through
 * [UserAskCenter] — the same card stack `user_ask` and hub approvals use. A
 * denial or a timeout turns the call into a failure result instead of running
 * it. Runs with no UI behind them (helper / scheduled / headless) are never
 * asked: they get `decision=denied` in the audit and the call proceeds, so a
 * scheduled task can never block on a card nobody is there to answer.
 */
object Guard {
    private const val TAG = "Guard"

    /** Same SharedPreferences file as the other agent tool switches. */
    const val PREFS = "agent_settings"

    /** Read by [enforceEnabled]; absent means false. */
    const val ENFORCE_KEY = "guard_enforce"

    private const val MAX_EVIDENCE = 200

    /** Literal secret; plain contains() so regex metacharacters cannot break the match. */
    private const val SECRET_LITERAL = "password"

    private data class Rule(val level: RiskLevel, val label: String, val regex: Regex)

    // ── Rule tables (severity order is handled by RiskLevel.ordinal) ────────

    private val IRREVERSIBLE_RULES = listOf(
        Rule(RiskLevel.IRREVERSIBLE, "rm -rf", Regex("""rm\s+-[rf]{1,2}\s""", RegexOption.IGNORE_CASE)),
        Rule(RiskLevel.IRREVERSIBLE, "mkfs", Regex("""mkfs""", RegexOption.IGNORE_CASE)),
        Rule(RiskLevel.IRREVERSIBLE, "dd of=/dev/", Regex("""dd\s+of=/dev/""", RegexOption.IGNORE_CASE)),
        Rule(RiskLevel.IRREVERSIBLE, "pm uninstall", Regex("""pm\s+uninstall""", RegexOption.IGNORE_CASE)),
        Rule(RiskLevel.IRREVERSIBLE, "adb uninstall", Regex("""adb\s+uninstall""", RegexOption.IGNORE_CASE)),
        Rule(RiskLevel.IRREVERSIBLE, "pm clear", Regex("""pm\s+clear""", RegexOption.IGNORE_CASE)),
        Rule(RiskLevel.IRREVERSIBLE, "git reset --hard", Regex("""git\s+reset\s+--hard""", RegexOption.IGNORE_CASE)),
        Rule(RiskLevel.IRREVERSIBLE, "drop table", Regex("""drop\s+table""", RegexOption.IGNORE_CASE)),
        Rule(RiskLevel.IRREVERSIBLE, "> /dev/sd", Regex(""">\s*/dev/sd""", RegexOption.IGNORE_CASE)),
    )

    private val MONEY_WORDS = Regex(
        """支付|付款|转账|下单|收款|余额|pay\b|transfer|checkout|¥|￥""",
        RegexOption.IGNORE_CASE,
    )

    /** A money word only matters when the same command presses a control. */
    private val MONEY_ACTIONS = Regex(
        """tap|click|android-a11y-cli|input""",
        RegexOption.IGNORE_CASE,
    )

    private val EXTERNAL_RULES = listOf(
        Rule(RiskLevel.EXTERNAL, "curl -X POST", Regex("""curl.*-X\s*(POST|PUT|PATCH)""", RegexOption.IGNORE_CASE)),
        Rule(RiskLevel.EXTERNAL, "wget --post", Regex("""wget.*--post""", RegexOption.IGNORE_CASE)),
        Rule(RiskLevel.EXTERNAL, "ssh", Regex("""\bssh\b""", RegexOption.IGNORE_CASE)),
        Rule(RiskLevel.EXTERNAL, "minis-mcp-cli call", Regex("""minis-mcp-cli\s+call""", RegexOption.IGNORE_CASE)),
        Rule(RiskLevel.EXTERNAL, "hub_call", Regex("""hub_call""", RegexOption.IGNORE_CASE)),
        Rule(RiskLevel.EXTERNAL, "android-open mailto/tel", Regex("""android-open\s+(mailto|tel):""", RegexOption.IGNORE_CASE)),
        Rule(RiskLevel.EXTERNAL, "git push", Regex("""git\s+push""", RegexOption.IGNORE_CASE)),
        Rule(RiskLevel.EXTERNAL, "nc -", Regex("""nc\s+-""", RegexOption.IGNORE_CASE)),
    )

    /** Checked last; any hit here also triggers evidence redaction. */
    private val SENSITIVE_RULES = listOf(
        Rule(RiskLevel.SENSITIVE, "id_rsa", Regex("""id_rsa""", RegexOption.IGNORE_CASE)),
        Rule(RiskLevel.SENSITIVE, ".pem", Regex("""\.pem\b""", RegexOption.IGNORE_CASE)),
        Rule(RiskLevel.SENSITIVE, ".env", Regex("""\.env\b""", RegexOption.IGNORE_CASE)),
        Rule(RiskLevel.SENSITIVE, "token", Regex("""token""", RegexOption.IGNORE_CASE)),
        Rule(RiskLevel.SENSITIVE, "api key", Regex("""api[_-]?key""", RegexOption.IGNORE_CASE)),
        Rule(RiskLevel.SENSITIVE, "credential", Regex("""credential""", RegexOption.IGNORE_CASE)),
        Rule(RiskLevel.SENSITIVE, "secret", Regex("""secret""", RegexOption.IGNORE_CASE)),
        Rule(RiskLevel.SENSITIVE, ".keystore", Regex("""\.keystore""", RegexOption.IGNORE_CASE)),
    )

    /**
     * Accessibility taps whose command text carries no semantics (`tap node
     * a3f2`, `tap xy 600 1200`, `tap id com.x:id/pay`). Guard resolves the
     * target node and appends its label chain, so the money/irreversible
     * rules can see what is actually being pressed without scanning the whole
     * screen (Pilot's own false-positive lesson).
     */
    private val A11Y_TAP_NODE = Regex(
        """android-a11y-cli\s+tap\s+(?:node)\s+(\S+)""", RegexOption.IGNORE_CASE,
    )
    private val A11Y_TAP_ID = Regex(
        """android-a11y-cli\s+tap\s+id\s+(\S+)""", RegexOption.IGNORE_CASE,
    )
    private val A11Y_TAP_XY = Regex(
        """android-a11y-cli\s+tap\s+xy\s+(-?\d+)\s+(-?\d+)""", RegexOption.IGNORE_CASE,
    )

    /**
     * Chinese irreversible intents that only make sense for a UI target
     * ("卸载", "清除数据", "退出登录"). Applied to the resolved target line
     * only, never to the whole command, so a file named 删除 does not trip it.
     */
    private val IRREVERSIBLE_TARGET_WORDS = Regex(
        """卸载|清除数据|恢复出厂|注销账号|删除账号|退出登录|解绑银行卡|格式化""",
    )

    /** `input text <secret>` must never survive in the audit evidence. */
    private val TYPED_TEXT = Regex("""(\binput\s+text\s+)([^\n]+)""", RegexOption.IGNORE_CASE)

    /** Long opaque values in an evidence string; only applied at SENSITIVE. */
    private val SECRET_VALUE = Regex("""[A-Za-z0-9_\-]{16,}""")

    // ── Classification ──────────────────────────────────────────────────────

    /**
     * Classify one call. Only the tool's risky field is scanned: `command`
     * for shell_execute, `path` for file_write/file_edit, `url` for
     * browser_use. Every other tool is [RiskLevel.NONE].
     *
     * A command matching several rules gets the highest level; within one
     * level the first rule in table order wins.
     */
    fun classify(toolName: String, argsJson: String): RiskVerdict {
        val commandText = scannedText(toolName, argsJson)
        if (commandText.isBlank()) return RiskVerdict.NONE

        // For opaque accessibility taps the command alone says nothing about
        // what is being pressed. Resolve the target node and give the rule
        // tables its label chain. A coordinate tap resolves through the
        // smallest actionable node at that point — no whole-screen scan.
        val targetText = if (toolName == "shell_execute") a11yTargetText(commandText) else null
        val text = if (targetText.isNullOrBlank()) commandText else "$commandText\n[target] $targetText"

        val candidates = mutableListOf<RiskVerdict>()

        IRREVERSIBLE_RULES.firstOrNull { it.regex.containsMatchIn(text) }?.let {
            candidates += RiskVerdict(it.level, it.label, text)
        }

        if (!targetText.isNullOrBlank()) {
            IRREVERSIBLE_TARGET_WORDS.find(targetText)?.let {
                candidates += RiskVerdict(RiskLevel.IRREVERSIBLE, "a11y target: ${it.value}", text)
            }
        }

        if (MONEY_WORDS.containsMatchIn(text) && MONEY_ACTIONS.containsMatchIn(text)) {
            val hit = MONEY_WORDS.find(text)?.value.orEmpty()
            candidates += RiskVerdict(RiskLevel.MONEY, "money action: $hit", text)
        }

        EXTERNAL_RULES.firstOrNull { it.regex.containsMatchIn(text) }?.let {
            candidates += RiskVerdict(it.level, it.label, text)
        }

        val sensitive = SENSITIVE_RULES.firstOrNull { it.regex.containsMatchIn(text) }
        val sensitiveHit = sensitive != null || text.contains(SECRET_LITERAL, ignoreCase = true)
        if (sensitiveHit) {
            candidates += RiskVerdict(RiskLevel.SENSITIVE, sensitive?.label ?: "secret literal", text)
        }

        val top = candidates.maxByOrNull { it.level.ordinal } ?: return RiskVerdict.NONE
        // Lead with the resolved target so the confirmation card says
        // "target: 微信 支付" even when the command itself is long.
        val evidenceBase = if (targetText.isNullOrBlank()) top.evidence
                           else "target: $targetText\n$commandText"
        // Redacted whenever the sensitive rule hit, even if a higher level won:
        // an API key must not survive in the audit just because the same
        // command also deletes a directory.
        val evidence = if (sensitiveHit) redact(evidenceBase) else evidenceBase
        return top.copy(evidence = redactTypedText(evidence).take(MAX_EVIDENCE))
    }

    private fun scannedText(toolName: String, argsJson: String): String {
        val obj = runCatching { JSONObject(argsJson) }.getOrNull() ?: return ""
        return when (toolName) {
            "shell_execute" -> obj.optString("command", "")
            "file_write", "file_edit" -> obj.optString("path", "")
            "browser_use" -> obj.optString("url", "")
            else -> ""
        }
    }

    /**
     * Resolve an opaque `android-a11y-cli tap ...` to a target description.
     *
     * Only tap forms are handled; `tap text 支付` already carries its label in
     * the command, and non-tap a11y commands cannot spend money or delete an
     * account. Any failure (service down, stale node, coordinates off-screen)
     * returns null and leaves the command as the only evidence — the audit
     * must never break a tool call.
     */
    private fun a11yTargetText(command: String): String? = runCatching {
        if (!command.contains("android-a11y-cli", ignoreCase = true)) return@runCatching null
        val svc = MinisAccessibilityService.getInstance() ?: return@runCatching null

        A11Y_TAP_NODE.find(command)?.let { m ->
            svc.nodeRegistry.get(m.groupValues[1])?.let {
                return@runCatching A11yLabels.describeTarget(it)
            }
        }
        A11Y_TAP_XY.find(command)?.let { m ->
            val x = m.groupValues[1].toIntOrNull() ?: return@runCatching null
            val y = m.groupValues[2].toIntOrNull() ?: return@runCatching null
            A11yLabels.nodeAt(svc.rootNodes(), x, y)?.let {
                return@runCatching A11yLabels.describeTarget(it)
            }
        }
        A11Y_TAP_ID.find(command)?.let { m ->
            A11yLabels.nodeByResourceId(svc.rootNodes(), m.groupValues[1])?.let {
                return@runCatching A11yLabels.describeTarget(it)
            }
        }
        null
    }.getOrNull()

    /** Replace the value after `input text` so a password never reaches the audit. */
    internal fun redactTypedText(text: String): String =
        TYPED_TEXT.replace(text) { m -> m.groupValues[1] + "***" }

    /** Replace any long opaque value with `***`. */
    internal fun redact(text: String): String = SECRET_VALUE.replace(text, "***")

    // ── Audit ───────────────────────────────────────────────────────────────

    /**
     * Write one audit line. Every dispatched call is recorded, including
     * [RiskLevel.NONE] — the acceptance harness checks that a plain
     * `shell_execute` run leaves a line, and seeing the harmless calls next to
     * the risky ones is what makes the trail readable. Never throws.
     *
     * [decision] is one of `allowed` / `denied` / `asked_approved` /
     * `asked_denied` / `asked_timeout`.
     */
    fun record(
        context: Context,
        verdict: RiskVerdict,
        sessionId: String?,
        toolName: String,
        decision: String = "allowed",
    ) {
        runCatching {
            AuditLog.append(
                context = context,
                sessionId = sessionId,
                tool = toolName,
                risk = verdict.level,
                rule = verdict.rule,
                evidence = verdict.evidence,
                decision = decision,
            )
        }
    }

    // ── Enforcement switch ──────────────────────────────────────────────────

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Default false: audit only, nothing is ever blocked. */
    fun enforceEnabled(context: Context): Boolean =
        runCatching { prefs(context).getBoolean(ENFORCE_KEY, false) }.getOrDefault(false)

    fun setEnforce(context: Context, value: Boolean): Boolean {
        runCatching { prefs(context).edit().putBoolean(ENFORCE_KEY, value).apply() }
        return value
    }

    // ── Confirmation card ───────────────────────────────────────────────────

    /**
     * Show a confirmation card for [verdict] and wait. Returns the audit
     * decision string; the caller records it and, unless it is
     * `asked_approved`, turns the call into a failure.
     *
     * The card uses the user_ask machinery with `ownedByToolCall = false`
     * (like [com.openminis.app.hub.HubApprovals]): the approval is not the
     * call's tool result, its output stays the result.
     */
    suspend fun requestApproval(
        context: Context,
        verdict: RiskVerdict,
        sessionId: String,
        toolCallId: String,
    ): String {
        val question = UserAskQuestion(
            type = UserAskQuestionType.OPTIONS,
            question = "即将执行高风险操作：${verdict.rule}",
            header = "安全确认 · ${levelLabel(verdict.level)}",
            options = listOf(
                UserAskOption("允许一次", "只执行这一次"),
                UserAskOption("拒绝", "不执行"),
            ),
            impact = verdict.evidence.take(MAX_EVIDENCE),
            risk = if (verdict.level.atLeast(RiskLevel.IRREVERSIBLE)) UserAskRisk.HIGH else UserAskRisk.MEDIUM,
        )
        val ask = PendingAsk.create(
            requestId = "guard-${UUID.randomUUID()}",
            sessionId = sessionId,
            toolCallId = toolCallId,
            questions = listOf(question),
            nowMs = System.currentTimeMillis(),
            // 0 means "wait forever" for user_ask; a blocked tool must never
            // hang the run, so one minute is the floor.
            timeoutMinutes = UserAskSettings.timeoutMinutes(context).coerceAtLeast(1),
        )
        UserAskCenter.begin(ask, ownedByToolCall = false)
        AppLogger.info(
            TAG,
            "[guard] waiting_approval sid=${sessionId.take(8)} tool=${toolCallId.take(16)} " +
                "risk=${verdict.level.name} rule=${verdict.rule}",
        )
        val result = UserAskCenter.wait(ask.requestId)
        return decisionFrom(result)
    }

    /** Map the card's result JSON to an audit decision. Anything unreadable is a denial. */
    internal fun decisionFrom(resultJson: String?): String {
        val entry = UserAskResolver.parseResults(resultJson).firstOrNull() ?: return "asked_denied"
        return when (entry.status) {
            UserAskResultStatus.TIMEOUT -> "asked_timeout"
            // The card is an options question today; APPROVED covers a future
            // confirm-style rendering of the same ask.
            UserAskResultStatus.APPROVED -> "asked_approved"
            UserAskResultStatus.ANSWERED, UserAskResultStatus.OTHER, UserAskResultStatus.DELEGATED -> {
                val answer = entry.answer.joinToString(" ")
                if (answer.contains("允许")) "asked_approved" else "asked_denied"
            }
            else -> "asked_denied"
        }
    }

    private fun levelLabel(level: RiskLevel): String = when (level) {
        RiskLevel.MONEY -> "资金操作"
        RiskLevel.IRREVERSIBLE -> "不可恢复"
        RiskLevel.EXTERNAL -> "对外发送"
        RiskLevel.SENSITIVE -> "敏感数据"
        RiskLevel.NONE -> "无"
    }
}

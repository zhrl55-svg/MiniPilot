package com.openminis.app.ui.chat

/**
 * [T-port-fold-tools] Decides which runs of consecutive activity in one
 * assistant message the chat list folds into a single summary row. Pure: it
 * sees only a compact description of each block, so the rule is unit-tested
 * without the list. Ported from iOS `ToolFoldPlanner`; see
 * [T-android-fold-activity] for where Android deliberately diverges.
 *
 * A run starts at the first thinking block or finished tool block and
 * continues through
 * - more finished tool blocks (counted),
 * - thinking blocks (counted as members),
 * - empty text blocks (a text block that has not received content yet),
 * - one-line remarks between tool calls ([Input.NOTE], see [markingNotes]):
 *   folded along, not counted.
 *
 * It ends at a non-empty text block, an info block, a sub agent / user_ask /
 * hub block, a tool that has not finished, or the end of the message. A run
 * containing thinking folds at any size; a run of tools alone needs at least
 * [MINIMUM_FOLD_MEMBERS]. A lone tool capsule is left as it is.
 *
 * [T-android-fold-activity] iOS only folds a run that ends at a NON-EMPTY text
 * block — a tool-heavy turn with no prose between its calls stayed fully
 * unfolded, which on Android filled whole screens with "Deep Thinking" cards
 * and tool capsules (field report, 2026-11-18: 48 calls + a dozen thinking
 * blocks, no reply text yet / scrolled up). Android folds a run at ANY of its
 * ends listed above: a finished stretch of work is folded away while the
 * unfinished tool and the streaming thinking block stay visible below the
 * summary row, and a run left without a reply at the end of the message folds
 * too. Thinking blocks also count as members here instead of merely riding
 * along, so "thinking + one tool" and thinking-only stretches fold as well.
 */
object ToolFoldPlanner {
    /**
     * The fewest block rows a message needs before planning at all — a cheap
     * skip in [ToolFoldController], not the fold rule itself (that is
     * [MINIMUM_FOLD_MEMBERS]). Counting every block row, thinking included.
     */
    const val MINIMUM_TOOL_COUNT = 2

    /**
     * The fewest tool members a run WITHOUT thinking needs before it folds.
     * A run that contains any thinking member folds regardless; a lone tool
     * capsule stays as it is.
     */
    const val MINIMUM_FOLD_MEMBERS = 2

    enum class ToolOutcome { UNFINISHED, SUCCEEDED, FAILED, CANCELLED }

    /** What the planner needs to know about one block. */
    sealed class Input {
        data class Tool(val outcome: ToolOutcome) : Input()
        object Thinking : Input() { override fun toString() = "Thinking" }
        data class Text(val isEmpty: Boolean) : Input()
        /** A one-line text with a tool call after it - a remark, not the reply. */
        object Note : Input() { override fun toString() = "Note" }
        /** Always visible and ends a run: info rows, sub agent and user_ask blocks. */
        object Barrier : Input() { override fun toString() = "Barrier" }
    }

    data class Group(
        /** Indices of the folded blocks, first member through last member. */
        val range: IntRange,
        val toolCount: Int,
        /** Thinking blocks in the run; drawn in the row's title, never counted as tools. */
        val thinkingCount: Int,
        val failedCount: Int,
        val cancelledCount: Int,
    )

    fun plan(blocks: List<Input>, enabled: Boolean): List<Group> {
        if (!enabled) return emptyList()
        val groups = mutableListOf<Group>()
        var start: Int? = null
        var lastMember = 0
        var tools = 0
        var thinking = 0
        var failed = 0
        var cancelled = 0

        fun reset() {
            start = null
            tools = 0; thinking = 0; failed = 0; cancelled = 0
        }
        fun close() {
            val s = start
            // [T-android-fold-activity] Any thinking folds, even a lone card
            // between two barriers — the thinking cards were the largest part
            // of the reported wall. A run of tools alone still needs
            // [MINIMUM_FOLD_MEMBERS], so a single tool capsule keeps its pill.
            if (s != null && (thinking >= 1 || tools >= MINIMUM_FOLD_MEMBERS)) {
                groups.add(Group(s..lastMember, tools, thinking, failed, cancelled))
            }
            reset()
        }

        for ((i, block) in blocks.withIndex()) {
            when (block) {
                is Input.Barrier -> close()
                is Input.Tool -> {
                    if (block.outcome == ToolOutcome.UNFINISHED) {
                        // The running call is never folded: it stays visible as
                        // its own capsule below the row, and the finished work
                        // before it closes here.
                        close()
                        continue
                    }
                    if (start == null) start = i
                    lastMember = i
                    tools += 1
                    if (block.outcome == ToolOutcome.FAILED) failed += 1
                    if (block.outcome == ToolOutcome.CANCELLED) cancelled += 1
                }
                is Input.Note -> {
                    // Only after a member: a remark before the first one is not
                    // part of any run.
                    if (start == null) continue
                    lastMember = i
                }
                is Input.Thinking -> {
                    if (start == null) start = i
                    lastMember = i
                    thinking += 1
                }
                is Input.Text -> if (!block.isEmpty) close()
            }
        }
        close()
        return groups
    }
    /**
     * Marks the one-line remarks a model writes between tool calls as
     * [Input.NOTE]: a non-empty text is a note when [isSingleLine] holds AND a
     * tool call comes after it. Everything after the last tool call is the
     * reply and is never a note.
     */
    fun markingNotes(inputs: List<Input>, isSingleLine: (Int) -> Boolean): List<Input> {
        val lastTool = inputs.indexOfLast { it is Input.Tool }
        if (lastTool < 0) return inputs
        val out = inputs.toMutableList()
        for (i in 0 until lastTool) {
            if (out[i] == Input.Text(isEmpty = false) && isSingleLine(i)) out[i] = Input.Note
        }
        return out
    }

    /** One line by line BREAKS: no newline between the first and last visible character. */
    fun isSingleLine(text: String): Boolean {
        val first = text.indexOfFirst { !it.isWhitespace() }
        if (first < 0) return true
        val last = text.indexOfLast { !it.isWhitespace() }
        for (i in first..last) if (text[i] == '\n' || text[i] == '\r') return false
        return true
    }

    private fun lines(text: String): Sequence<String> = text.splitToSequence("\r\n", "\n", "\r")

    /** Lines with at least one visible character. */
    fun nonEmptyLineCount(text: String): Int = lines(text).count { line -> line.any { !it.isWhitespace() } }

    /**
     * Whether a run that is due to fold is held back for now because folding
     * it would move what the user is reading: only while they are scrolled up,
     * never for a run already folded once, and only when it belongs to the
     * running message or its blocks are on the list as separate rows right
     * now. [shownAsRows] is only called when the cheaper conditions leave the
     * answer open.
     */
    fun holdsBack(
        browsing: Boolean,
        alreadyFolded: Boolean,
        inRunningMessage: Boolean,
        shownAsRows: () -> Boolean,
    ): Boolean {
        if (!browsing || alreadyFolded) return false
        return inRunningMessage || shownAsRows()
    }

    // --- AssistantBlock adapters ---------------------------------------------

    /** The planner's view of one block. */
    fun input(block: AssistantBlock): Input = when (block.kind) {
        "text" -> Input.Text(isEmpty = block.content.none { !it.isWhitespace() })
        "thinking" -> Input.Thinking
        "info" -> Input.Barrier
        else -> when {
            // iOS: delegate and user_ask blocks are never folded.
            com.openminis.app.agent.jobs.HelperRunner.isSubAgentToolName(block.toolName) -> Input.Barrier
            block.toolName == com.openminis.app.agent.userask.UserAskPromptText.TOOL_NAME -> Input.Barrier
            // [T-minis-hub] A cross-device call is never folded either: the
            // approval card lives inside this block, and a card folded into
            // "Ran 3 tools" is a request the person cannot see, let alone
            // answer. Folding it away would also hide the remote output that
            // replaced the card once it was answered.
            block.toolName == com.openminis.app.hub.HubCallTool.NAME -> Input.Barrier
            else -> when (block.toolStatus) {
                ToolBlockStatus.SUCCESS -> Input.Tool(ToolOutcome.SUCCEEDED)
                // A timed-out call ended in failure; the capsule shows it as such.
                ToolBlockStatus.FAILED, ToolBlockStatus.TIMEOUT -> Input.Tool(ToolOutcome.FAILED)
                ToolBlockStatus.CANCELLED -> Input.Tool(ToolOutcome.CANCELLED)
                ToolBlockStatus.STREAMING, ToolBlockStatus.PENDING, ToolBlockStatus.RUNNING, null ->
                    Input.Tool(ToolOutcome.UNFINISHED)
            }
        }
    }

    /** The planner's view of a message's blocks, remarks marked as notes. */
    fun inputs(blocks: List<AssistantBlock>): List<Input> =
        markingNotes(blocks.map(::input)) { isSingleLine(blocks[it].content) }

    /** One folded run, as the list draws it. */
    data class FoldedRun(
        /**
         * Expanded-state key: the first TOOL's id (the provider's tool_use
         * id, which survives a reload), or the first thinking block's id when
         * the run holds no tool at all.
         */
        val key: String,
        val firstBlockId: String,
        val memberIds: List<String>,
        val group: Group,
        /** Summed execution time of the folded calls; null when none recorded one. */
        val durationMs: Long?,
    )

    fun foldedRuns(blocks: List<AssistantBlock>): List<FoldedRun> =
        plan(inputs(blocks), enabled = true).map { g ->
            val members = blocks.subList(g.range.first, g.range.last + 1)
            val firstTool = members.firstOrNull { it.kind == "tool_use" } ?: members.first()
            val durations = members.filter { it.kind == "tool_use" && it.durationMs > 0 }.map { it.durationMs }
            FoldedRun(
                key = firstTool.id,
                firstBlockId = members.first().id,
                memberIds = members.map { it.id },
                group = g,
                durationMs = if (durations.isEmpty()) null else durations.sum(),
            )
        }

    /** Runs to fold in [message]; while it streams, the live tail stays out. */
    fun foldedRuns(message: ChatMessage): List<FoldedRun> {
        val blocks = message.toolBlocks
        if (blocks.isEmpty()) return emptyList()
        // [T-android-fold-activity] The trailing thinking block of a running
        // turn is still streaming its content, and ThinkingBlock only ever
        // auto-expands the last block overall — folding it away would hide
        // the reasoning as it arrives. Everything before it folds.
        val end = if (message.isStreaming && blocks.last().kind == "thinking") blocks.size - 1 else blocks.size
        if (end <= 0) return emptyList()
        return foldedRuns(blocks.subList(0, end))
    }
}

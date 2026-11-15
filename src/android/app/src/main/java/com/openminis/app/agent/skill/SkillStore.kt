package com.openminis.app.agent.skill

import android.content.Context
import com.openminis.app.logging.AppLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Persistent store of successful task trajectories ("skills").
 *
 * File: `getExternalFilesDir(null)/agent/skills.json` — deliberately on
 * external storage so the debug acceptance harness can read it over adb.
 * Format: `{ "version": 3, "records": [...] }`, at most [MAX_RECORDS] records;
 * the oldest `lastUsedAt` is evicted first. Any read or write failure is
 * treated as "no data" — a damaged file must never break the agent loop.
 *
 * Schema 3 (this version) keeps the original fields and adds:
 *   - per-step `input` (the command / locator that actually ran) and `output`
 *     (a bounded excerpt of what it printed), so a hint can say "run this, it
 *     printed that";
 *   - a record-level `conclusion` (the successful run's final answer).
 * Schema-2 files load unchanged and are upgraded in place on the next save;
 * see [decodeRoot].
 *
 * Recording rules (all must hold, otherwise nothing is written):
 *  1. the run made at least 1 tool call,
 *  2. every one of them succeeded,
 *  3. the final assistant text carries no failure marker,
 *  4. no step input/output/summary touches a sensitive field (password /
 *     verification code / OTP / PIN / API token) — a single hit drops the
 *     whole record (anti-replay guard),
 *  5. after waste removal at least one actionable step remains.
 *
 * Reading is a hint only: [promptHintFor] returns a prompt section, and the
 * caller injects it verbatim. No step is ever executed from the store.
 */
object SkillStore {
    /** Public so the chat loop's goal-extraction diagnostics land under the
     *  same tag as the record/skip lines (the "[Skill]" grep). */
    const val TAG = "SkillStore"

    const val VERSION = 3

    /** Read compatibility: v2 files load and migrate losslessly on next save. */
    internal val ACCEPTED_VERSIONS = setOf(2, 3)

    const val MAX_RECORDS = 200
    private const val MAX_GOAL_CHARS = 200
    private const val MAX_SUMMARY_CHARS = 80
    private const val MAX_TARGET_CHARS = 500
    private const val MAX_INPUT_CHARS = 800
    private const val MAX_OUTPUT_CHARS = 600
    private const val MAX_CONCLUSION_CHARS = 500
    private const val MAX_STEPS = 12
    private const val MAX_HINT_STEPS = 6

    /**
     * A recorded goal must be a task, not an ASR fragment. Two cleaned
     * characters is enough for a real command ("截屏", "静音"); the intent
     * marker below is what rejects a bare noun like "手电筒".
     */
    private const val MIN_GOAL_CLEAN_CHARS = 2

    /** The literal value the runtime substitutes for the placeholder; plain contains() so regex metacharacters cannot break the check. */
    private const val SECRET_LITERAL = "password"

    /**
     * Anti-replay guard. `pin` / `otp` only match as standalone ASCII words so
     * a `ping` command or a "shopping" summary does not silently disable
     * recording, while "输入PIN码" / "OTP 123456" still do. Long `sk-…` /
     * `Bearer …` runs are credential shapes: keeping one in a hint would leak
     * it into a later prompt, so such a run is not recorded at all.
     */
    private val SENSITIVE_STEP = Regex(
        "密码|验证码|passcode|(?<![a-z])otp(?![a-z])|(?<![a-z])pin(?![a-z])|" +
            "sk-[A-Za-z0-9_-]{20,}|Bearer\\s+[A-Za-z0-9._-]{20,}",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Only unambiguous "the task was not completed" phrasings. Do NOT widen
     * this to bare 失败 / 错误 / 无法 / 不能 / error / failed / cannot / unable:
     * the marker is matched against ordinary prose in the final answer, where
     * those words are everyday Chinese ("这样就不能保证…", "无法确认",
     * "避免失败", "没有错误"), so a wide list discards successful trajectories
     * even though [maybeRecord]'s allSucceeded already covers real failures.
     */
    private val FAILURE_MARKER = Regex(
        "未能完成|没有完成|无法完成|未完成|未成功|任务失败|做不到|放弃了",
    )

    // ── Goal quality ────────────────────────────────────────────────────────

    /**
     * A goal is only stored / matched when it names an action. The field data
     * this gate was written against is full of ASR fragments ("就这个",
     * "手电筒", "我们可以加个手电筒功能"): a bare noun has no direction (on or
     * off?), and a feature-discussion sentence is not a task the user asked
     * the device to perform. Both would match an unrelated live request.
     *
     * Keep this concrete: a verb / request marker, or an interrogative that
     * asks the agent to find something out ("今天会下雨吗").
     */
    private val TASK_MARKER = Regex(
        "打开|关闭|关掉|关上|开启|启动|停用|设置|调整|调到|调高|调低|调亮|调暗|调大|调小|" +
            "清理|清除|删除|删掉|清空|释放|腾出|截屏|截图|截个图|拍照|拍张照|保存|发送|分享|" +
            "下载|上传|安装|卸载|播放|暂停|提醒|记一下|记住|添加|新建|创建|写入|读取|查看|" +
            "查询|查一下|查查|看看|看一下|帮我|帮忙|导航|规划|连接|断开|搜索|搜一下|找一下|" +
            "拨号|拨打|打电话|打个电话|呼叫|静音|震动|取消|预约|总结|整理|检查|测试|统计|" +
            "列出|显示|告诉我|还剩|剩多少|多少|哪个|哪些|什么|哪里|是否|有没有|吗|呢|怎么|" +
            "如何|大一点|小一点|小声|大声|放首歌|放歌|听歌|来首歌|发条|发个|发给|问一下|问问|" +
            "读一下|写个|写一下|删了|删一下|清一下|截个|截一下|截长图|录屏|录个屏|开一下|关一下|" +
            "开个|连一下|连上|设个|定个|定一个|设一个|查下|看下|试下|重启|关机|开机|锁屏|解锁|" +
            "免打扰|把|" +
            "\\b(open|close|turn|set|check|show|list|clean|clear|delete|take|screenshot|save|" +
            "send|share|download|upload|install|uninstall|play|pause|record|remind|add|create|" +
            "write|read|search|find|navigate|connect|disconnect|enable|disable|toggle|mute|unmute|" +
            "increase|decrease|raise|lower|put|call|message|text|lock|unlock|restart|reboot|" +
            "query|what|how|which|where|please)\\b",
        RegexOption.IGNORE_CASE,
    )

    /**
     * "我们可以加个手电筒功能" is a design discussion, not "turn the flashlight
     * on". The first alternative needs a first-person plural plus a feature /
     * discussion word; the second catches "加个…功能" without the pronoun. A
     * real request ("帮我加个闹钟", "我们能不能打开手电筒") does not match
     * either — the polite modal alone is not a discussion.
     */
    private val DISCUSSION_MARKER = Regex(
        "(我们|咱们)[^。！？!?]{0,12}(加个|增加|实现|做个|需求|想法|讨论|觉得)" +
            "|(加个|增加|实现|做个|支持)[^。！？!?]{0,12}功能" +
            "|\\b(we|let'?s)\\b[^.!?]{0,20}\\b(add|implement|support|feature)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Whole goal is a filler / acknowledgement. */
    private val FILLER_ONLY = Regex(
        "^(嗯+|哦+|呃+|啊+|那个|这个|就这个|就是|然后|好的?|行|可以|ok|okay|对对)[，,。.、\\s]*$",
        RegexOption.IGNORE_CASE,
    )

    /** Leading discourse fillers to strip before the goal is stored. */
    private val LEADING_FILLER = Regex(
        "^(嗯+|哦+|呃+|啊+|那个|这个|就是|然后|好的|好|行|ok|okay)[，,。.、\\s]+",
        RegexOption.IGNORE_CASE,
    )

    /** ASR often omits the comma: "嗯帮我打开手电筒". Only pure interjections. */
    private val LEADING_INTERJECTION = Regex("^(嗯+|哦+|呃+|啊+)", RegexOption.IGNORE_CASE)

    /** A bare search / request verb with no object: "我看看", "帮我看看这个". */
    private val VAGUE_GOAL = Regex(
        "^(我|你)?(看看|看一下|看下|查一下|查下|查查|试试|试下|弄一下|搞一下|来一下|" +
            "帮我看看|帮我看一下|帮我查一下)(这个|那个|一下)?[吧啊呀呢。.!！?？\\s]*$",
        RegexOption.IGNORE_CASE,
    )

    private val lock = Any()

    /** Stable names for the three hint elements; also returned by `agent.skills.hint`. */
    private const val PART_INPUTS = "inputs"
    private const val PART_KEY_OUTPUT = "keyOutput"
    private const val PART_CONCLUSION = "conclusion"

    /**
     * The chat loop and `agent.skills.hint` share this one decision: what was
     * matched, whether it is safe to inject, and — when it is not — exactly
     * which of the three contract elements is missing.
     */
    data class HintDecision(
        val hit: Boolean,
        val hint: String?,
        val score: Double,
        val threshold: Double,
        val algorithm: SkillMatcher.Algorithm,
        val recordCount: Int,
        val matchingPoolCount: Int,
        val eligibleCount: Int,
        val record: SkillRecord?,
        val missing: List<String>,
        val reason: String,
    )

    // ── Paths ───────────────────────────────────────────────────────────────

    fun skillsFile(context: Context): File? = agentDir(context)?.let { File(it, "skills.json") }

    private fun agentDir(context: Context): File? =
        context.getExternalFilesDir(null)?.let { File(it, "agent") }

    // ── Read / write ────────────────────────────────────────────────────────

    /**
     * Decode the on-disk document. Returns null for a foreign version or a
     * missing `records` array; individual malformed records are skipped.
     * Accepts both schema 2 and schema 3 so an upgrade never loses the
     * existing store (P1-4).
     */
    internal fun decodeRoot(text: String): List<SkillRecord>? {
        val root = JSONObject(text)
        val version = root.optInt("version", -1)
        if (version !in ACCEPTED_VERSIONS) return null
        val arr = root.optJSONArray("records") ?: return null
        val out = mutableListOf<SkillRecord>()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { o -> SkillRecord.fromJson(o)?.let(out::add) }
        }
        return out
    }

    /** Serialize the whole store as schema 3. */
    internal fun encodeRoot(records: List<SkillRecord>): String =
        JSONObject().apply {
            put("version", VERSION)
            put("records", JSONArray().also { arr -> records.forEach { arr.put(it.toJson()) } })
        }.toString()

    /** All records; a missing, damaged or foreign-version file reads as empty. */
    fun load(context: Context): List<SkillRecord> = synchronized(lock) {
        runCatching<List<SkillRecord>> {
            val f = skillsFile(context) ?: return@runCatching emptyList()
            if (!f.exists() || f.length() == 0L) return@runCatching emptyList()
            decodeRoot(f.readText(Charsets.UTF_8)) ?: emptyList()
        }.getOrElse { t ->
            AppLogger.warning(TAG, "skills.json unreadable (${t.javaClass.simpleName}) — treating as empty")
            emptyList()
        }
    }

    private fun save(context: Context, records: List<SkillRecord>) {
        synchronized(lock) {
            runCatching {
                val f = skillsFile(context) ?: return@runCatching
                f.parentFile?.mkdirs()
                // Temp file + rename: a kill mid-write must not leave a
                // truncated JSON document behind (which the next load would
                // silently discard, losing every earlier record).
                val tmp = File(f.parentFile, "skills.json.tmp")
                tmp.writeText(encodeRoot(records), Charsets.UTF_8)
                if (!tmp.renameTo(f)) {
                    f.delete()
                    if (!tmp.renameTo(f)) tmp.delete()
                }
            }.onFailure { t ->
                AppLogger.warning(TAG, "skills.json not written: ${t.javaClass.simpleName}")
            }
        }
    }

    /** Delete every record. Returns how many existed. */
    fun clear(context: Context): Int = synchronized(lock) {
        val n = load(context).size
        runCatching { skillsFile(context)?.delete() }
        n
    }

    // ── Goal quality / eligibility ──────────────────────────────────────────

    /**
     * Canonical task text: trimmed, whitespace-collapsed, with leading
     * discourse fillers ("嗯，", "那个，") removed. This is what a NEW record
     * stores, so its goal reads as a task instead of a transcription artifact.
     * Existing records are never rewritten by this function.
     */
    fun normalizeGoal(raw: String): String {
        var s = raw.trim().replace(Regex("\\s+"), " ")
        while (true) {
            var next = s.replaceFirst(LEADING_FILLER, "").trim()
            if (next == s) next = s.replaceFirst(LEADING_INTERJECTION, "").trim()
            if (next == s) break
            s = next
        }
        return s.trim('"', '\'', '“', '”')
    }

    /**
     * Why [rawGoal] is not a usable task description, or null when it is.
     * Codes are stable and surface in `agent.skills.list` / `matchBench`:
     * `empty`, `filler`, `vague`, `too-short`, `discussion`, `no-task-verb`.
     */
    fun goalProblem(rawGoal: String): String? {
        val goal = normalizeGoal(rawGoal)
        val cleaned = SkillMatcher.clean(goal)
        if (cleaned.isEmpty()) return "empty"
        if (FILLER_ONLY.matches(goal) || FILLER_ONLY.matches(cleaned)) return "filler"
        if (VAGUE_GOAL.matches(goal) || VAGUE_GOAL.matches(cleaned)) return "vague"
        if (cleaned.length < MIN_GOAL_CLEAN_CHARS) return "too-short"
        if (DISCUSSION_MARKER.containsMatchIn(goal)) return "discussion"
        if (!TASK_MARKER.containsMatchIn(goal)) return "no-task-verb"
        return null
    }

    /**
     * The three elements a hint must carry: a command/input, the key output it
     * produced, and the conclusion. Missing elements are returned in contract
     * order (`inputs`, `keyOutput`, `conclusion`) so a caller can say which
     * item failed. Low-value steps are excluded exactly as they are when the
     * hint is rendered, so the check and the text cannot disagree.
     */
    internal fun missingHintParts(steps: List<SkillStep>, conclusion: String?): List<String> {
        val actionable = distillSteps(steps)
        val hasInput = actionable.any {
            !it.input.isNullOrBlank() || !it.target.isNullOrBlank()
        }
        val hasOutput = actionable.any { !it.output.isNullOrBlank() }
        val hasConclusion = !conclusion.isNullOrBlank()
        return buildList {
            if (!hasInput) add(PART_INPUTS)
            if (!hasOutput) add(PART_KEY_OUTPUT)
            if (!hasConclusion) add(PART_CONCLUSION)
        }
    }

    fun missingHintParts(record: SkillRecord): List<String> =
        missingHintParts(record.steps, record.conclusion)

    /**
     * Non-null when this record must not be injected. Format is a compact
     * reason (`goal-discussion`, `missing-keyOutput,conclusion`), or both
     * joined by `+`.
     */
    fun ineligibleReason(record: SkillRecord): String? {
        val goal = goalProblem(record.goal)
        val missing = missingHintParts(record)
        if (goal == null && missing.isEmpty()) return null
        return buildList {
            if (goal != null) add("goal-$goal")
            if (missing.isNotEmpty()) add("missing-" + missing.joinToString(","))
        }.joinToString("+")
    }

    fun isEligible(record: SkillRecord): Boolean = ineligibleReason(record) == null

    /**
     * The gate [maybeRecord] applies before writing: a new record must have a
     * readable goal AND all three hint elements. This is what keeps the store
     * from growing a new generation of unusable entries.
     */
    internal fun recordRejection(
        goal: String,
        steps: List<SkillStep>,
        conclusion: String?,
    ): String? {
        goalProblem(goal)?.let { return "goal-$it" }
        val missing = missingHintParts(steps, conclusion)
        if (missing.isNotEmpty()) return "missing-" + missing.joinToString(",")
        return null
    }

    // ── Waste removal ───────────────────────────────────────────────────────

    /**
     * Turn the raw trace into the steps worth remembering.
     *
     * A clean run can still be noisy: the live 2026-11-15 case recorded 16
     * calls for a task whose decisive action was one command. Two rules keep
     * the sediment free of that noise:
     *  - an identical (tool, target, input, summary) step is kept once, at its
     *    LAST occurrence (the final successful state, not the first attempt);
     *  - generic "called <tool>" lines with no target carry no method and are
     *    dropped when a more concrete step exists.
     *
     * The result keeps chronological order and is capped at [max].
     */
    internal fun distillSteps(raw: List<SkillStep>, max: Int = MAX_STEPS): List<SkillStep> {
        if (raw.isEmpty()) return emptyList()
        val seen = HashSet<String>()
        val reversedKept = ArrayList<SkillStep>(raw.size)
        for (step in raw.asReversed()) {
            val key = step.tool + "\u0000" + (step.target ?: "") + "\u0000" +
                (step.input ?: "") + "\u0000" + step.summary
            if (!seen.add(key)) continue
            reversedKept.add(step)
            if (reversedKept.size >= max) break
        }
        reversedKept.reverse()
        // A run whose only steps are questions / memory writes has no method
        // worth sedimenting; returning empty makes [maybeRecord] skip it
        // rather than freezing the chatter as a "skill".
        return reversedKept.filterNot { isLowValueStep(it) }
    }

    /**
     * A step that cannot teach a method: a question the model asked the user,
     * a memory write, or a bare "called some_tool" line. Kept only when the
     * whole run has nothing else (see [distillSteps]).
     */
    private fun isLowValueStep(step: SkillStep): Boolean = when {
        step.tool == "user_ask" -> true
        step.tool == "memory_write" || step.tool == "memory_get" -> true
        step.target == null && BARE_TOOL_LINE.matches(step.summary) -> true
        else -> false
    }

    private val BARE_TOOL_LINE = Regex("^调用 [A-Za-z0-9_.-]+$")

    // ── Recording ───────────────────────────────────────────────────────────

    /**
     * Record this run when it is a clean success (see the class comment).
     * Returns the stored record, or null when it was skipped.
     *
     * [goal] is the run's last user message, [appPackage] the foreground
     * package at run start, [steps] one entry per executed call, [toolChain]
     * the call names in order, [allSucceeded] whether every call succeeded.
     */
    fun maybeRecord(
        context: Context,
        goal: String?,
        appPackage: String?,
        steps: List<SkillStep>,
        toolChain: List<String>,
        allSucceeded: Boolean,
        finalAssistantText: String,
    ): SkillRecord? {
        val goalText = normalizeGoal(goal.orEmpty())
        if (goalText.isEmpty()) {
            AppLogger.info(TAG, "[Skill] skip: empty goal")
            return null
        }
        if (!allSucceeded) {
            AppLogger.info(TAG, "[Skill] skip: not clean (steps=${steps.size} chain=${toolChain.size})")
            return null
        }
        if (toolChain.isEmpty() || steps.size != toolChain.size) {
            AppLogger.info(TAG, "[Skill] skip: no call or size mismatch (steps=${steps.size} chain=${toolChain.size})")
            return null
        }
        if (FAILURE_MARKER.containsMatchIn(finalAssistantText)) {
            AppLogger.info(TAG, "[Skill] skip: failure marker in answer")
            return null
        }
        if (steps.any { isSensitiveStep(it) }) {
            AppLogger.info(TAG, "[Skill] not recorded: a step touches a sensitive field (anti-replay)")
            return null
        }

        val storedSteps = distillSteps(steps)
        if (storedSteps.isEmpty()) {
            AppLogger.info(TAG, "[Skill] skip: no actionable step after waste removal (raw=${steps.size})")
            return null
        }
        val conclusion = finalAssistantText.trim()
            .replace(Regex("\\s+"), " ")
            .take(MAX_CONCLUSION_CHARS)
            .ifBlank { null }

        val storedGoal = goalText.take(MAX_GOAL_CHARS)
        // [T-skill-store-quality] New records must be usable the moment they
        // land. A fragment goal or a trace missing one of the three hint
        // elements would be matched later and then refused at injection time,
        // so it is not written at all; the skip reason is logged for the
        // harness.
        recordRejection(storedGoal, storedSteps, conclusion)?.let { reason ->
            AppLogger.info(
                TAG,
                "[Skill] skip: $reason (goal=\"${storedGoal.take(60)}\" " +
                    "steps=${storedSteps.size} conclusion=${!conclusion.isNullOrBlank()})",
            )
            return null
        }

        // A package the caller could not identify still has an honest answer:
        // this chat runs in our app, and the accessibility helper falls back
        // to context.packageName. Never write null when it is knowable.
        val providedApp = appPackage?.trim()?.takeIf { it.isNotEmpty() }
        val fallbackApp = runCatching { foregroundPackage(context) }.getOrNull()

        val now = System.currentTimeMillis()
        val id = idForGoal(storedGoal)
        val record = synchronized(lock) {
            val records = load(context).toMutableList()
            val existingIndex = records.indexOfFirst { it.id == id }
            val merged = if (existingIndex >= 0) {
                val old = records[existingIndex]
                old.copy(
                    goal = storedGoal,
                    appPackage = providedApp ?: old.appPackage ?: fallbackApp,
                    keywords = SkillMatcher.keywords(storedGoal),
                    steps = storedSteps,
                    toolChain = toolChain,
                    successCount = old.successCount + 1,
                    lastUsedAt = now,
                    conclusion = conclusion ?: old.conclusion,
                )
            } else {
                SkillRecord(
                    id = id,
                    goal = storedGoal,
                    appPackage = providedApp ?: fallbackApp,
                    keywords = SkillMatcher.keywords(storedGoal),
                    steps = storedSteps,
                    toolChain = toolChain,
                    successCount = 1,
                    createdAt = now,
                    lastUsedAt = now,
                    conclusion = conclusion,
                )
            }
            if (existingIndex >= 0) records[existingIndex] = merged else records.add(merged)
            val pruned = records.sortedByDescending { it.lastUsedAt }.take(MAX_RECORDS)
            save(context, pruned)
            merged
        }
        AppLogger.info(
            TAG,
            "[Skill] recorded id=$id calls=${toolChain.size} steps=${storedSteps.size}/${steps.size} " +
                "goal=\"${storedGoal.take(60)}\" total=${load(context).size}",
        )
        return record
    }

    private fun isSensitiveStep(step: SkillStep): Boolean {
        val haystack = buildString {
            append(step.viewId.orEmpty()); append('\n')
            append(step.target.orEmpty()); append('\n')
            append(step.summary); append('\n')
            append(step.input.orEmpty()); append('\n')
            append(step.output.orEmpty())
        }
        return haystack.contains(SECRET_LITERAL, ignoreCase = true) ||
            SENSITIVE_STEP.containsMatchIn(haystack)
    }

    // ── Reading a hint ──────────────────────────────────────────────────────

    /**
     * The system-prompt section for the best matching past run, or null when
     * nothing reaches the configured threshold OR the match cannot produce a
     * complete hint. The caller must append it verbatim and must leave the
     * prompt untouched on null (a stray character would invalidate the
     * provider's prompt cache).
     *
     * This is the only injection path; [hintFor] is the diagnostic view of the
     * same decision used by `agent.skills.hint`, so the RPC can never report a
     * hit the chat loop would reject.
     */
    fun promptHintFor(context: Context, goal: String?, appPackage: String?): String? {
        val decision = hintFor(context, goal, appPackage)
        if (decision.hit && decision.hint != null) {
            AppLogger.info(
                TAG,
                "[Skill] hint id=${decision.record?.id} score=${"%.2f".format(decision.score)} " +
                    "algo=${decision.algorithm.wire} steps=${decision.record?.steps?.size ?: 0} " +
                    "chars=${decision.hint.length} pool=${decision.matchingPoolCount} " +
                    "eligible=${decision.eligibleCount}",
            )
            return decision.hint
        }
        AppLogger.info(
            TAG,
            "[Skill] hint skip: ${decision.reason} (score=${"%.2f".format(decision.score)} " +
                "threshold=${"%.2f".format(decision.threshold)} algo=${decision.algorithm.wire} " +
                "records=${decision.recordCount} pool=${decision.matchingPoolCount} " +
                "eligible=${decision.eligibleCount} " +
                "matched=${decision.record?.id ?: "-"} " +
                "missing=${decision.missing.joinToString(",").ifEmpty { "-" }})",
        )
        return null
    }

    /** Shared retrieval decision for the chat loop and the debug RPC. */
    fun hintFor(
        context: Context,
        goal: String?,
        appPackage: String?,
        config: SkillMatcher.MatchConfig = runCatching { SkillSettings.config(context) }
            .getOrDefault(SkillMatcher.MatchConfig()),
    ): HintDecision = decideHint(goal.orEmpty(), appPackage, load(context), config)

    /**
     * Pure decision core. Dirty goals (ASR fragments, feature discussions) are
     * removed from the matching pool entirely, so they can neither win a match
     * nor be injected. Among the remaining candidates the top match must carry
     * all three contract elements; an incomplete winner returns `hit=false`
     * with its missing parts instead of injecting a partial hint. This is the
     * "宁可不给" direction: a near-miss must not steer the agent.
     */
    internal fun decideHint(
        query: String,
        appPackage: String?,
        records: List<SkillRecord>,
        config: SkillMatcher.MatchConfig,
    ): HintDecision {
        val q = query.trim()
        val readable = records.filter { goalProblem(it.goal) == null }
        val eligible = readable.filter { missingHintParts(it).isEmpty() }

        fun decision(
            hit: Boolean,
            hint: String?,
            score: Double,
            record: SkillRecord?,
            missing: List<String>,
            reason: String,
        ) = HintDecision(
            hit = hit,
            hint = hint,
            score = score,
            threshold = config.threshold,
            algorithm = config.algorithm,
            recordCount = records.size,
            matchingPoolCount = readable.size,
            eligibleCount = eligible.size,
            record = record,
            missing = missing,
            reason = reason,
        )

        if (q.isEmpty()) return decision(false, null, 0.0, null, emptyList(), "empty-query")
        if (records.isEmpty()) return decision(false, null, 0.0, null, emptyList(), "no-records")
        if (readable.isEmpty()) {
            return decision(false, null, 0.0, null, emptyList(), "no-usable-records")
        }
        val match = SkillMatcher.best(q, readable, appPackage, config)
        val top = readable.maxOfOrNull {
            SkillMatcher.score(q, it, appPackage, config.algorithm)
        } ?: 0.0
        if (match == null) {
            return decision(false, null, top, null, emptyList(), "below-threshold")
        }
        val missing = missingHintParts(match.record)
        if (missing.isNotEmpty()) {
            return decision(false, null, match.score, match.record, missing, "candidate-incomplete")
        }
        return decision(true, renderHint(match.record, q), match.score, match.record, emptyList(), "hit")
    }

    /**
     * Render one record as instructions a later run can follow directly:
     * what to run/read, what it printed last time, and the conclusion.
     * Deliberately no "tool name × count" list — that was the old failure
     * mode (P1-2).
     */
    internal fun renderHint(record: SkillRecord, query: String? = null): String {
        val steps = selectHintSteps(distillSteps(record.steps))
        return buildString {
            append("【已知做法】历史上同类任务的成功做法与结论，可直接参考；")
            append("执行前必须核对当前设备、路径与界面，不符就放弃：\n")
            append("同类任务：").append(record.goal).append('\n')
            if (polarityWarning(record.goal, query)) {
                append("注意：本次要求的方向与上次记录相反，只参考路径与方法，不要照抄开关方向。\n")
            }
            append("做法：\n")
            if (steps.isEmpty()) {
                append("1. ").append(record.goal).append("（无可复用的具体步骤）\n")
            } else {
                steps.forEachIndexed { index, step ->
                    append(index + 1).append(". ").append(actionLine(step)).append('\n')
                    step.input?.takeIf { it.isNotBlank() }?.let { input ->
                        append("   ").append(if (step.tool == "shell_execute") "命令：" else "参数：")
                        append(input).append('\n')
                    }
                    step.output?.takeIf { it.isNotBlank() }?.let { output ->
                        append("   关键输出：").append(output).append('\n')
                    }
                }
            }
            record.conclusion?.takeIf { it.isNotBlank() }?.let {
                append("结论：").append(it).append('\n')
            }
            append("注意：命令、路径与界面均来自上次运行，可能已变化；先核对再执行，不要运行来源不明的命令。")
        }.trimEnd()
    }

    /**
     * Choose at most [max] steps without losing the method or the key output.
     * A plain `take(6)` can drop the only output-bearing step of a long trace;
     * eligibility would still say "complete" while the rendered hint silently
     * omitted it. Required indices are kept and the remaining slots are filled
     * chronologically.
     */
    internal fun selectHintSteps(
        steps: List<SkillStep>,
        max: Int = MAX_HINT_STEPS,
    ): List<SkillStep> {
        if (max <= 0) return emptyList()
        if (steps.size <= max) return steps
        val chosen = LinkedHashSet<Int>()
        steps.indexOfFirst { !it.input.isNullOrBlank() || !it.target.isNullOrBlank() }
            .takeIf { it >= 0 }?.let(chosen::add)
        steps.indexOfFirst { !it.output.isNullOrBlank() }
            .takeIf { it >= 0 }?.let(chosen::add)
        var i = 0
        while (chosen.size < max && i < steps.size) {
            chosen.add(i)
            i++
        }
        return chosen.sorted().map { steps[it] }
    }

    private fun actionLine(step: SkillStep): String = when (step.tool) {
        "shell_execute" ->
            if (step.input.isNullOrBlank() && !step.target.isNullOrBlank()) {
                step.summary
            } else {
                "在终端执行 shell 命令"
            }
        "file_read" -> "读取文件 ${step.target.orEmpty()}".trim()
        "file_write" -> "写入文件 ${step.target.orEmpty()}".trim()
        "file_edit" -> "修改文件 ${step.target.orEmpty()}".trim()
        "read_image" -> "查看图片 ${step.target.orEmpty()}".trim()
        "browser_use" -> step.summary
        "amap_search" -> "地图搜索 ${step.target.orEmpty()}".trim()
        "news_search" -> "搜索新闻 ${step.target.orEmpty()}".trim()
        "stock_quote" -> "查询行情 ${step.target.orEmpty()}".trim()
        else -> step.summary
    }

    /** True only when the two goals clearly ask for opposite directions. */
    private fun polarityWarning(recordGoal: String, query: String?): Boolean {
        if (query.isNullOrBlank()) return false
        val a = polarity(recordGoal)
        val b = polarity(query)
        return a != 0 && b != 0 && a != b
    }

    /** 1 = turn on / raise, -1 = turn off / lower, 0 = neither or both. */
    private fun polarity(text: String): Int {
        val on = Regex("打开|开启|启动|点亮|亮起|开一下|开灯|调高|调亮").containsMatchIn(text)
        val off = Regex("关闭|关掉|关上|熄灭|停用|禁用|关一下|关灯|调低|调暗").containsMatchIn(text)
        return when {
            on && !off -> 1
            off && !on -> -1
            else -> 0
        }
    }

    // ── Step extraction ─────────────────────────────────────────────────────

    /**
     * Describe one executed call: the tool, its semantic locator, the view id
     * when present, a one-line summary, and — for schema 3 — the actual input
     * and a bounded excerpt of the output. Tolerates any args shape; an
     * unparseable call still yields a usable generic step.
     */
    fun stepFor(tool: String, args: JSONObject, output: String? = null): SkillStep {
        val viewId = (args.optText("view_id") ?: args.optText("viewId"))
            ?.takeIf { it.isNotBlank() }
        fun clip(s: String): String =
            s.replace('\n', ' ').replace('\r', ' ').trim().take(MAX_SUMMARY_CHARS)
        fun target(s: String): String? = s.trim().take(MAX_TARGET_CHARS).takeIf { it.isNotEmpty() }
        fun input(s: String): String? =
            s.trim().take(MAX_INPUT_CHARS).takeIf { it.isNotEmpty() }
        val out = outputExcerpt(output)

        return when (tool) {
            "shell_execute" -> {
                val command = args.optString("command", "").trim()
                val first = command.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
                SkillStep(tool, target(command), viewId, clip("执行命令：$first"), input(command), out)
            }
            "browser_use" -> {
                val action = args.optString("action", "").trim().ifEmpty { "操作" }
                val locator = args.optString("url", "")
                    .ifBlank { args.optString("selector", "") }
                    .ifBlank { args.optString("text", "") }
                SkillStep(
                    tool, target(locator), viewId,
                    clip("浏览器 $action${if (locator.isBlank()) "" else " $locator"}"),
                    input(locator), out,
                )
            }
            "file_read" -> {
                val path = args.optString("path", "")
                SkillStep(tool, target(path), viewId, clip("读取文件 $path"), null, out)
            }
            "file_write" -> {
                val path = args.optString("path", "")
                SkillStep(tool, target(path), viewId, clip("写入文件 $path"), null, out)
            }
            "file_edit" -> {
                val path = args.optString("path", "")
                SkillStep(tool, target(path), viewId, clip("修改文件 $path"), null, out)
            }
            "read_image" -> {
                val path = args.optString("path", "")
                SkillStep(tool, target(path), viewId, clip("查看图片 $path"), null, out)
            }
            "amap_search" -> {
                val query = args.optString("query", "")
                SkillStep(tool, target(query), viewId, clip("地图搜索 $query"), null, out)
            }
            "news_search" -> {
                val query = args.optString("query", "")
                SkillStep(tool, target(query), viewId, clip("新闻搜索 $query"), null, out)
            }
            "stock_quote" -> {
                val query = args.optString("query", "")
                SkillStep(tool, target(query), viewId, clip("查询行情 $query"), null, out)
            }
            "memory_write" -> SkillStep(tool, null, viewId, "写入记忆", null, out)
            "memory_get" -> {
                val keywords = args.optString("keywords", "")
                SkillStep(tool, target(keywords), viewId, clip("查询记忆 $keywords"), null, out)
            }
            "user_ask" -> {
                val question = args.optJSONArray("questions")
                    ?.optJSONObject(0)
                    ?.optString("question", "")
                    .orEmpty()
                SkillStep(tool, target(question), viewId, clip("向用户确认：$question"), null, null)
            }
            "hub_call" -> {
                val task = args.optString("task", "").ifBlank { args.optString("prompt", "") }
                SkillStep(tool, target(task), viewId, clip("调用其他设备：$task"), null, out)
            }
            "delegate_task", "subagent_task" -> {
                val task = args.optString("task", "")
                SkillStep(tool, target(task), viewId, clip("委派子任务：$task"), null, out)
            }
            else -> {
                val title = args.optString("tool_title", "")
                SkillStep(
                    tool, null, viewId,
                    clip(if (title.isBlank()) "调用 $tool" else "$tool：$title"),
                    null, out,
                )
            }
        }
    }

    /**
     * Keep the useful part of a tool result. Command output often ends with the
     * summary line (e.g. `du | sort -h`), so short outputs are kept whole and
     * long ones keep both ends. Returns null for blank output.
     */
    internal fun outputExcerpt(raw: String?, maxChars: Int = MAX_OUTPUT_CHARS): String? {
        if (raw.isNullOrBlank()) return null
        val text = raw.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (text.isEmpty()) return null
        val lines = text.lineSequence().map { it.trimEnd() }.filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return null
        val picked = if (lines.size <= 10) {
            lines
        } else {
            lines.take(4) + listOf("…") + lines.takeLast(3)
        }
        return picked.joinToString("\n").take(maxChars).ifBlank { null }
    }

    // ── Environment ─────────────────────────────────────────────────────────

    /**
     * Foreground package at task start. The chat's own package is reported as
     * ITSELF, not null: the user typed the goal here, and treating that as
     * "unknown" is exactly what left every record's appPackage empty (the
     * same-app weight could never apply). When the accessibility service is
     * unavailable the goal still originated in this app, so the app package
     * is the honest fallback.
     */
    fun foregroundPackage(context: Context): String? = runCatching {
        val foreground = com.openminis.app.accessibility.MinisAccessibilityService.getInstance()
            ?.foregroundPackage()
            ?.first
            ?.takeIf { it.isNotBlank() }
        foreground ?: context.packageName
    }.getOrElse { context.packageName }

    /**
     * The deterministic id a record for [goal] has (or would have). Used by
     * `agent.skills.matchBench mode=store` to derive the expected record from
     * a pair's goal when no explicit `expectedId` was supplied.
     */
    internal fun idForGoal(goal: String): String =
        sha1Hex(normalizeGoal(goal).take(MAX_GOAL_CHARS)).take(16)

    private fun sha1Hex(text: String): String = runCatching {
        val digest = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append("%02x".format(b))
        sb.toString()
    }.getOrDefault(Integer.toHexString(text.hashCode()))
}

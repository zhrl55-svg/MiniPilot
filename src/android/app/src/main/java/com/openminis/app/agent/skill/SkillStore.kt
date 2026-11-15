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

    private val lock = Any()

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
        val goalText = goal?.trim().orEmpty()
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

        val now = System.currentTimeMillis()
        val id = sha1Hex(goalText).take(16)
        val storedGoal = goalText.take(MAX_GOAL_CHARS)
        val record = synchronized(lock) {
            val records = load(context).toMutableList()
            val existingIndex = records.indexOfFirst { it.id == id }
            val merged = if (existingIndex >= 0) {
                val old = records[existingIndex]
                old.copy(
                    goal = storedGoal,
                    appPackage = appPackage ?: old.appPackage,
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
                    appPackage = appPackage,
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
     * nothing reaches the configured threshold. The caller must append it
     * verbatim and must leave the prompt untouched on null (a stray character
     * would invalidate the provider's prompt cache).
     */
    fun promptHintFor(context: Context, goal: String?, appPackage: String?): String? {
        val query = goal?.trim().orEmpty()
        if (query.isEmpty()) {
            AppLogger.info(TAG, "[Skill] hint skip: empty query")
            return null
        }
        val records = load(context)
        if (records.isEmpty()) {
            AppLogger.info(TAG, "[Skill] hint skip: no records")
            return null
        }
        val config = runCatching { SkillSettings.config(context) }
            .getOrDefault(SkillMatcher.MatchConfig())
        val match = SkillMatcher.best(query, records, appPackage, config)
        if (match == null) {
            val top = records.maxOfOrNull {
                SkillMatcher.score(query, it, appPackage, config.algorithm)
            } ?: 0.0
            AppLogger.info(
                TAG,
                "[Skill] hint skip: below threshold (top=${"%.2f".format(top)} " +
                    "threshold=${"%.2f".format(config.threshold)} algo=${config.algorithm.wire} " +
                    "records=${records.size})",
            )
            return null
        }
        val hint = renderHint(match.record, query)
        AppLogger.info(
            TAG,
            "[Skill] hint id=${match.record.id} score=${"%.2f".format(match.score)} " +
                "algo=${config.algorithm.wire} steps=${match.record.steps.size} chars=${hint.length}",
        )
        return hint
    }

    /**
     * Render one record as instructions a later run can follow directly:
     * what to run/read, what it printed last time, and the conclusion.
     * Deliberately no "tool name × count" list — that was the old failure
     * mode (P1-2).
     */
    internal fun renderHint(record: SkillRecord, query: String? = null): String {
        val steps = distillSteps(record.steps).take(MAX_HINT_STEPS)
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

    private fun sha1Hex(text: String): String = runCatching {
        val digest = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append("%02x".format(b))
        sb.toString()
    }.getOrDefault(Integer.toHexString(text.hashCode()))
}

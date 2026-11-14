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
 * Format: `{ "version": 2, "records": [...] }`, at most [MAX_RECORDS] records;
 * the oldest `lastUsedAt` is evicted first. Any read or write failure is
 * treated as "no data" — a damaged file must never break the agent loop.
 *
 * Recording rules (all must hold, otherwise nothing is written):
 *  1. the run made at least 2 tool calls,
 *  2. every one of them succeeded,
 *  3. the final assistant text carries no failure marker,
 *  4. no step touches a sensitive field (password / verification code / OTP /
 *     PIN) — this is the anti-replay guard, a single hit drops the whole record.
 *
 * Reading is a hint only: [promptHintFor] returns a prompt section, and the
 * caller injects it verbatim. No step is ever executed from the store.
 */
object SkillStore {
    /** Public so the chat loop's goal-extraction diagnostics land under the
     *  same tag as the record/skip lines (the "[Skill]" grep). */
    const val TAG = "SkillStore"

    private const val VERSION = 2
    const val MAX_RECORDS = 200
    private const val MAX_GOAL_CHARS = 200
    private const val MAX_SUMMARY_CHARS = 80
    private const val MAX_TARGET_CHARS = 500
    private const val MAX_STEPS = 40
    private const val MAX_HINT_STEPS = 20

    /** The literal value the runtime substitutes for the placeholder; plain contains() so regex metacharacters cannot break the check. */
    private const val SECRET_LITERAL = "password"

    /**
     * Anti-replay guard. `pin` / `otp` only match as standalone ASCII words so
     * a `ping` command or a "shopping" summary does not silently disable
     * recording, while "输入PIN码" / "OTP 123456" still do.
     */
    private val SENSITIVE_STEP = Regex(
        "密码|验证码|passcode|(?<![a-z])otp(?![a-z])|(?<![a-z])pin(?![a-z])",
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

    /** All records; a missing, damaged or foreign-version file reads as empty. */
    fun load(context: Context): List<SkillRecord> = synchronized(lock) {
        runCatching<List<SkillRecord>> {
            val f = skillsFile(context) ?: return@runCatching emptyList()
            if (!f.exists() || f.length() == 0L) return@runCatching emptyList()
            val root = JSONObject(f.readText(Charsets.UTF_8))
            if (root.optInt("version", -1) != VERSION) return@runCatching emptyList()
            val arr = root.optJSONArray("records") ?: return@runCatching emptyList()
            val out = mutableListOf<SkillRecord>()
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { o -> SkillRecord.fromJson(o)?.let(out::add) }
            }
            out
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
                val root = JSONObject().apply {
                    put("version", VERSION)
                    put("records", JSONArray().also { arr -> records.forEach { arr.put(it.toJson()) } })
                }
                // Temp file + rename: a kill mid-write must not leave a
                // truncated JSON document behind (which the next load would
                // silently discard, losing every earlier record).
                val tmp = File(f.parentFile, "skills.json.tmp")
                tmp.writeText(root.toString(), Charsets.UTF_8)
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
        if (toolChain.size < 2 || steps.size != toolChain.size) {
            AppLogger.info(TAG, "[Skill] skip: chain too short or size mismatch (steps=${steps.size} chain=${toolChain.size})")
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
                    steps = steps.take(MAX_STEPS),
                    toolChain = toolChain,
                    successCount = old.successCount + 1,
                    lastUsedAt = now,
                )
            } else {
                SkillRecord(
                    id = id,
                    goal = storedGoal,
                    appPackage = appPackage,
                    keywords = SkillMatcher.keywords(storedGoal),
                    steps = steps.take(MAX_STEPS),
                    toolChain = toolChain,
                    successCount = 1,
                    createdAt = now,
                    lastUsedAt = now,
                )
            }
            if (existingIndex >= 0) records[existingIndex] = merged else records.add(merged)
            val pruned = records.sortedByDescending { it.lastUsedAt }.take(MAX_RECORDS)
            save(context, pruned)
            merged
        }
        AppLogger.info(
            TAG,
            "[Skill] recorded id=$id calls=${toolChain.size} steps=${steps.size} " +
                "goal=\"${storedGoal.take(60)}\" total=${load(context).size}",
        )
        return record
    }

    private fun isSensitiveStep(step: SkillStep): Boolean {
        val haystack = buildString {
            append(step.viewId.orEmpty()); append('\n')
            append(step.target.orEmpty()); append('\n')
            append(step.summary)
        }
        return haystack.contains(SECRET_LITERAL, ignoreCase = true) ||
            SENSITIVE_STEP.containsMatchIn(haystack)
    }

    // ── Reading a hint ──────────────────────────────────────────────────────

    /**
     * The system-prompt section for the best matching past run, or null when
     * nothing reaches [SkillMatcher.THRESHOLD]. The caller must append it
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
        val match = SkillMatcher.best(query, records, appPackage)
        if (match == null) {
            val top = records.maxOfOrNull { SkillMatcher.score(query, it, appPackage) } ?: 0.0
            AppLogger.info(
                TAG,
                "[Skill] hint skip: below threshold (top=${"%.2f".format(top)} records=${records.size})",
            )
            return null
        }
        val steps = match.record.steps
        if (steps.isEmpty()) {
            AppLogger.info(TAG, "[Skill] hint skip: empty steps")
            return null
        }
        AppLogger.info(
            TAG,
            "[Skill] hint id=${match.record.id} score=${"%.2f".format(match.score)} steps=${steps.size}",
        )
        return buildString {
            append("【已知做法】历史上同类任务的成功轨迹，仅供参考，必须逐屏核对当前界面后再执行，界面不符就放弃它：\n")
            steps.take(MAX_HINT_STEPS).forEachIndexed { i, s ->
                append(i + 1).append(". ").append(s.summary).append('\n')
            }
        }.trimEnd()
    }

    // ── Step extraction ─────────────────────────────────────────────────────

    /**
     * Describe one executed call: the tool, its semantic locator, the view id
     * when present, and a one-line summary for the hint. Tolerates any args
     * shape — an unparseable call still yields a usable generic step.
     */
    fun stepFor(tool: String, args: JSONObject): SkillStep {
        val viewId = (args.optText("view_id") ?: args.optText("viewId"))
            ?.takeIf { it.isNotBlank() }
        fun clip(s: String): String =
            s.replace('\n', ' ').replace('\r', ' ').trim().take(MAX_SUMMARY_CHARS)
        fun target(s: String): String? = s.trim().take(MAX_TARGET_CHARS).takeIf { it.isNotEmpty() }

        return when (tool) {
            "shell_execute" -> {
                val command = args.optString("command", "").trim()
                val first = command.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
                SkillStep(tool, target(command), viewId, clip("执行命令：$first"))
            }
            "browser_use" -> {
                val action = args.optString("action", "").trim().ifEmpty { "操作" }
                val locator = args.optString("url", "")
                    .ifBlank { args.optString("selector", "") }
                    .ifBlank { args.optString("text", "") }
                SkillStep(tool, target(locator), viewId, clip("浏览器 $action${if (locator.isBlank()) "" else " $locator"}"))
            }
            "file_read" -> {
                val path = args.optString("path", "")
                SkillStep(tool, target(path), viewId, clip("读取文件 $path"))
            }
            "file_write" -> {
                val path = args.optString("path", "")
                SkillStep(tool, target(path), viewId, clip("写入文件 $path"))
            }
            "file_edit" -> {
                val path = args.optString("path", "")
                SkillStep(tool, target(path), viewId, clip("修改文件 $path"))
            }
            "read_image" -> {
                val path = args.optString("path", "")
                SkillStep(tool, target(path), viewId, clip("查看图片 $path"))
            }
            "amap_search" -> {
                val query = args.optString("query", "")
                SkillStep(tool, target(query), viewId, clip("地图搜索 $query"))
            }
            "news_search" -> {
                val query = args.optString("query", "")
                SkillStep(tool, target(query), viewId, clip("新闻搜索 $query"))
            }
            "stock_quote" -> {
                val query = args.optString("query", "")
                SkillStep(tool, target(query), viewId, clip("查询行情 $query"))
            }
            "memory_write" -> SkillStep(tool, null, viewId, "写入记忆")
            "memory_get" -> {
                val keywords = args.optString("keywords", "")
                SkillStep(tool, target(keywords), viewId, clip("查询记忆 $keywords"))
            }
            "user_ask" -> {
                val question = args.optJSONArray("questions")
                    ?.optJSONObject(0)
                    ?.optString("question", "")
                    .orEmpty()
                SkillStep(tool, target(question), viewId, clip("向用户确认：$question"))
            }
            "hub_call" -> {
                val task = args.optString("task", "").ifBlank { args.optString("prompt", "") }
                SkillStep(tool, target(task), viewId, clip("调用其他设备：$task"))
            }
            "delegate_task", "subagent_task" -> {
                val task = args.optString("task", "")
                SkillStep(tool, target(task), viewId, clip("委派子任务：$task"))
            }
            else -> {
                val title = args.optString("tool_title", "")
                SkillStep(tool, null, viewId, clip(if (title.isBlank()) "调用 $tool" else "$tool：$title"))
            }
        }
    }

    // ── Environment ─────────────────────────────────────────────────────────

    /**
     * Foreground package at task start, or null when it cannot be read. The
     * chat's own package is reported as null: the user typed the goal HERE, so
     * the app in front is the assistant, not the target of the task.
     */
    fun foregroundPackage(context: Context): String? = runCatching {
        com.openminis.app.accessibility.MinisAccessibilityService.getInstance()
            ?.foregroundPackage()
            ?.first
            ?.takeIf { it.isNotBlank() && it != context.packageName }
    }.getOrNull()

    private fun sha1Hex(text: String): String = runCatching {
        val digest = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append("%02x".format(b))
        sb.toString()
    }.getOrDefault(Integer.toHexString(text.hashCode()))
}

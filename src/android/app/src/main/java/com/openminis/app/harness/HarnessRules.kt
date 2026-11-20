package com.openminis.app.harness

import android.content.Context
import java.io.File

/**
 * [T-harness-rules-file] Runtime-editable harness rules.
 *
 * The behavioural rules the agent must follow (execution discipline, etc.)
 * used to be compiled into [com.openminis.app.ui.chat.ChatViewModel]'s base
 * system prompt. They now live in a Markdown file the user (or a probe, or a
 * future self-improvement loop) can edit WITHOUT rebuilding the app:
 *
 *   runtime path (what the agent sees): /var/minis/harness/rules.md
 *   host path    (what this class reads): <filesDir>/minis-global/harness/rules.md
 *
 * Precedence: file content wins; when the file is missing or blank, the
 * compiled [DEFAULT_RULES] is used, so a first launch behaves exactly like
 * the old compiled prompt. [ensureSeeded] writes the default once; it never
 * overwrites user edits.
 *
 * iOS mirrors this class (Agent/Harness/HarnessRules.swift) and reads the
 * same /var/minis path, so one file is the single source of truth for both
 * platforms. Keep [DEFAULT_RULES] text identical on both sides.
 */
object HarnessRules {

    const val RUNTIME_PATH = "/var/minis/harness/rules.md"
    private const val HOST_RELATIVE_PATH = "minis-global/harness/rules.md"

    fun hostFile(context: Context): File = File(context.filesDir, HOST_RELATIVE_PATH)

    /** Read the active rules: the runtime file when usable, else the default. */
    fun read(context: Context): String {
        val file = hostFile(context)
        val fromFile = runCatching {
            if (file.exists()) file.readText(Charsets.UTF_8) else null
        }.getOrNull()
        return fromFile?.takeIf { it.isNotBlank() } ?: DEFAULT_RULES
    }

    /** Seed the default rules file on first launch. Never overwrites edits. */
    fun ensureSeeded(context: Context) {
        val file = hostFile(context)
        val existed = file.exists()
        android.util.Log.i("HarnessRules",
            "ensureSeeded path=${file.absolutePath} exists=$existed")
        if (existed) return
        val ok = runCatching {
            file.parentFile?.mkdirs()
            file.writeText(DEFAULT_RULES, Charsets.UTF_8)
        }.isSuccess
        android.util.Log.i("HarnessRules",
            "ensureSeeded wrote=$ok bytes=${if (file.exists()) file.length() else -1}")
    }

    /**
     * Factory default. Mirrors iOS HarnessRules.defaultText byte-for-byte.
     * Keep platform-specific phrasing OUT: the concrete scheduling mechanics
     * are already described by each platform's "Scheduled tasks" section.
     */
    val DEFAULT_RULES: String = """
Execution discipline for long-running or dispatched work: make tool calls immediately instead of describing intentions, and keep working until the task is complete. Within a turn, `delay` is your wait mechanism — to follow up on something still running, chain delay-then-check calls at a task-appropriate interval until you have the result or hit a sensible retry cap. For a check that should happen AFTER this turn ends, register it with `minis-scheduled create …` via shell_execute (see the CLI list and 'Scheduled tasks' later in this prompt). NEVER end a turn with a promise of future action: 'I'll keep monitoring', 'will sync the result later', and ending right after a single still-running status check with 'let's keep waiting' are all the same violation — once your turn ends, NOTHING runs until the user's next message. If polling to completion is genuinely not worth blocking the turn, close honestly instead: either register a `minis-scheduled` follow-up (say so, with its task id) or state that you will only learn the outcome when the user next messages; for anything that must fire even if the app is killed, point the user at a platform-level automation that survives the app process.

Tone and style:
- Reply in the language that best matches the user's input. Only switch languages when the user explicitly asks.
- Be concise. Prefer action over explanation — when the user asks for something that can be done via shell, do it directly.
    """.trimIndent()
}

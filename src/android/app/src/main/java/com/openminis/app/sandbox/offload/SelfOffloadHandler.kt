package com.openminis.app.sandbox.offload

import com.openminis.app.config.ConfigBridge
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import com.openminis.app.tools.AgentTools

/**
 * [T-self-manual] `minis-self` offload handler.
 *
 * Reference material on demand: tools (live from AgentTools), settings (live
 * from ConfigBridge), and the hand-written CLI/feature/architecture manuals in
 * [SelfManual]. Registered like the other minis-* CLIs; the system prompt
 * carries one pointer line instead of the full manuals.
 */
class SelfOffloadHandler : NativeOffloadHandler {

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        // argv[0] is the program name; subcommand and options follow.
        val args = OffloadArgs(request.argv.drop(1), booleanFlags = setOf("json"))
        if (args.hasFlag("h", "help")) return ok(SelfManual.HELP_TEXT)
        val sub = args.positional.firstOrNull() ?: return ok(SelfManual.HELP_TEXT)
        return try {
            when (sub) {
                "tools" -> cmdTools(args)
                "cli" -> cmdCli(args)
                "settings" -> cmdSettings(args)
                "links" -> ok(SelfManual.SETTINGS_LINKS.joinToString("\n"))
                "features" -> filterLines(args, SelfManual.FEATURES, "Features")
                "arch" -> filterLines(args, SelfManual.ARCHITECTURE.lines(), "Architecture")
                else -> err("Unknown subcommand '$sub'. Use --help.")
            }
        } catch (e: Throwable) {
            err(e.message ?: "internal error")
        }
    }

    // -- tools -----------------------------------------------------------------

    private fun cmdTools(args: OffloadArgs): NativeOffloadResult {
        val terms = filterTerms(args)
        val full = args.hasFlag("full")
        val matched = AgentTools.makeAgentTools().filter { tool ->
            val hay = (tool.name + " " + tool.description).lowercase()
            terms.isEmpty() || terms.all { hay.contains(it) }
        }
        if (matched.isEmpty()) {
            return err("No tool matches ${terms.joinToString(" ")}")
        }

        // [T-self-manual] Compact by default: one line per tool. The full
        // manual was 11 KB, so the agent truncated it with `head` and then
        // re-ran a grep just to list names (audit 2026-11-15). `--full`
        // restores parameter details.
        if (!full) {
            val sb = StringBuilder(
                "Agent tools (${matched.size}; default surface; per-run gating can hide " +
                    "memory / browser / sub-agent tools):\n",
            )
            for (tool in matched) {
                val firstLine = tool.description.trim().lineSequence().firstOrNull().orEmpty()
                val purpose = firstLine.take(120).trimEnd()
                val ellipsis = if (firstLine.length > 120) "…" else ""
                sb.append("- ").append(tool.name).append(" — ").append(purpose).append(ellipsis)
                    .append(" (").append(tool.parameters.size).append(" params)\n")
            }
            sb.append("Run `minis-self tools --full [--filter k]` for parameter details.\n")
            return ok(sb.toString())
        }

        val sb = StringBuilder(
            "Agent tools (${matched.size}; default surface; per-run gating can hide " +
                "memory / browser / sub-agent tools):\n",
        )
        for (tool in matched) {
            sb.append("\n- ").append(tool.name).append(": ")
                .append(tool.description.trim().take(600)).append('\n')
            if (tool.parameters.isNotEmpty()) {
                sb.append("    params: ")
                sb.append(
                    tool.parameters.entries.joinToString(", ") { (name, param) ->
                        val required = if (name in tool.required) " required" else ""
                        val enum = param.enumValues?.takeIf { it.isNotEmpty() }
                            ?.joinToString("|", prefix = "=", limit = 8)
                            .orEmpty()
                        "$name(${param.type}$required$enum)"
                    },
                )
                sb.append('\n')
                for ((name, param) in tool.parameters) {
                    val desc = param.description.trim()
                    if (desc.isNotEmpty()) {
                        sb.append("      ").append(name).append(": ")
                            .append(desc.take(180)).append('\n')
                    }
                }
            }
        }
        return ok(sb.toString())
    }

    // -- cli -------------------------------------------------------------------

    private fun cmdCli(args: OffloadArgs): NativeOffloadResult {
        val name = args.positional.getOrNull(1)
        if (name == null) return ok(SelfManual.CLI_MANUAL)
        val hits = SelfManual.CLI_MANUAL.lines()
            .filter { it.contains(name, ignoreCase = true) }
        if (hits.isEmpty()) return err("No CLI named '$name' (run `minis-self cli` for the index)")
        return ok(hits.joinToString("\n"))
    }

    // -- settings --------------------------------------------------------------

    private fun cmdSettings(args: OffloadArgs): NativeOffloadResult {
        if (!ConfigBridge.isEnabled()) {
            return ok(
                "minis-config is disabled in Settings → Permissions, so settings cannot be read " +
                    "or written. Ask the user to re-enable it there.",
            )
        }
        val topic = args.positional.getOrNull(1)
        if (topic == null) {
            val topics = ConfigBridge.allTopics()
            return ok(
                buildString {
                    append("Config topics (use `minis-self settings <topic>`; write with minis-config):\n")
                    for (i in 0 until topics.length()) {
                        append("- ").append(topics.getString(i)).append('\n')
                    }
                },
            )
        }
        val terms = filterTerms(args)
        val fields = ConfigBridge.fieldsForTopic(topic)
        val sb = StringBuilder(
            "Settings in '$topic' (write with minis-config set <path> <value-json>):\n",
        )
        var shown = 0
        for (i in 0 until fields.length()) {
            val field = fields.optJSONObject(i) ?: continue
            val hay = (
                field.optString("path") + " " + field.optString("display_name") + " " +
                    field.optString("description")
                ).lowercase()
            if (terms.isNotEmpty() && !terms.all { hay.contains(it) }) continue
            shown++
            sb.append("\n- ").append(field.optString("path"))
            val display = field.optString("display_name")
            if (display.isNotEmpty()) sb.append(" — ").append(display)
            sb.append('\n')
            val desc = field.optString("description")
            if (desc.isNotEmpty()) sb.append("    ").append(desc).append('\n')
            val schema = field.optString("schema")
            if (schema.isNotEmpty()) sb.append("    type: ").append(schema).append('\n')
            sb.append("    access: ").append(field.optString("access"))
                .append("  risk: ").append(field.optString("risk"))
                .append("  revertable: ").append(field.optBoolean("revertable"))
                .append('\n')
        }
        if (shown == 0) {
            val known = ConfigBridge.allTopics()
            val names = (0 until known.length()).joinToString(", ") { known.getString(it) }
            return err("No setting in '$topic' matches ${terms.joinToString(" ")}. Topics: $names")
        }
        return ok(sb.toString())
    }

    // -- shared ----------------------------------------------------------------

    private fun filterLines(
        args: OffloadArgs,
        lines: List<String>,
        title: String,
    ): NativeOffloadResult {
        // `features 语音` (positional) works like `features --filter 语音`.
        val terms = filterTerms(args).ifEmpty {
            args.positional.getOrNull(1)?.lowercase()?.let { listOf(it) }.orEmpty()
        }
        val hits = if (terms.isEmpty()) {
            lines
        } else {
            lines.filter { line -> terms.all { line.lowercase().contains(it) } }
        }
        if (hits.isEmpty()) return err("No $title entry matches ${terms.joinToString(" ")}")
        return ok(hits.joinToString("\n"))
    }

    private fun filterTerms(args: OffloadArgs): List<String> =
        args.get("filter", "f")
            ?.lowercase()
            ?.split(Regex("\\s+"))
            ?.filter { it.isNotEmpty() }
            .orEmpty()

    private fun ok(body: String): NativeOffloadResult =
        NativeOffloadResult(0, body.trimEnd() + "\n")

    private fun err(message: String): NativeOffloadResult =
        NativeOffloadResult(1, message.trimEnd() + "\n")
}

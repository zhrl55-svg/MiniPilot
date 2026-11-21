package com.openminis.app.assistant.routing

/**
 * [T-cloud-fastpath-catalog] Machine-readable view of the on-device fast path,
 * rendered from [LocalCapabilityRegistry] at call time.
 *
 * The cloud model used to know nothing about the app's local life capabilities:
 * when the deterministic router could not match a sentence it handed the turn
 * to the agent loop, which only had shell / file / browser tools and therefore
 * answered "cannot do that" for things the app can do natively (calendar,
 * alarm, torch, volume, player, ...).
 *
 * There is deliberately NO second hand-written list here. Everything is derived
 * from the registry — ids, actions, slots, enum values and risk — so a new
 * capability or slot appears in the cloud prompt automatically and cannot drift.
 * The only hand-written input is the [LocalCapabilityRegistry.inProcessCapabilities]
 * split, which lives next to the registry for the same reason.
 */
object LocalCapabilityCatalog {

    /**
     * Capabilities the in-process executor can run, so the cloud tool
     * `local_capability` accepts exactly these. Order follows the registry.
     */
    val invokableCapabilityIds: List<String>
        get() = LocalCapabilityRegistry.all
            .map { it.id }
            .filter { it in LocalCapabilityRegistry.inProcessCapabilities }

    /**
     * Capabilities that already have a first-class agent tool with the same
     * name (shell_execute, file_read, calendar-adjacent network tools, ...).
     * The cloud should call those tools directly; a second dispatcher path for
     * them would duplicate schema, guard and confirmation behaviour.
     */
    val directToolCapabilityIds: List<String>
        get() = LocalCapabilityRegistry.all
            .map { it.id }
            .filter { it !in LocalCapabilityRegistry.inProcessCapabilities }

    val totalCapabilityCount: Int get() = LocalCapabilityRegistry.all.size

    /**
     * The system-prompt section. Kept byte-stable for a given build (no dates,
     * no runtime values) so prefix caching still works.
     */
    fun renderPromptSection(): String = buildString {
        appendLine("Local on-device capabilities (fast path):")
        appendLine(
            "The actions below run inside the app, without a shell. When the user's " +
                "request maps to one, call the `local_capability` tool with " +
                "{capability, action, arguments} directly — do not reproduce it with " +
                "shell commands, and never answer that it is unavailable before trying it. " +
                "HIGH-risk actions still show the user the in-app confirmation card.",
        )
        for (capability in LocalCapabilityRegistry.all) {
            if (capability.id !in LocalCapabilityRegistry.inProcessCapabilities) continue
            val actions = capability.actions.values.joinToString(" | ") { renderAction(it) }
            appendLine("- ${capability.id} — ${capability.label}: $actions")
            // [T-alarm-truthful-result] A caveat belongs next to the
            // capability it qualifies, so the model reads it while deciding
            // whether to call the tool — not in a footnote further down.
            capability.caveat?.let { appendLine("  Note: $it") }
        }
        appendLine(
            "Slot notation: name:TYPE required, name?:TYPE optional; " +
                "TIME = ISO-8601 local `YYYY-MM-DDTHH:mm:ss`; " +
                "DURATION/NUMBER = integer (durations in seconds); " +
                "ENUM = one of the values in parentheses; " +
                "QUERY = search phrase; TEXT = free text (copy the user's wording verbatim). " +
                "[read-only] = no side effect; [HIGH: asks first] = destructive, confirmation required.",
        )
        appendLine(
            "These local capabilities already have a dedicated agent tool — use that " +
                "tool directly instead of `local_capability`: " +
                directToolCapabilityIds.joinToString(", ") + ".",
        )
        append(
            "When no local capability and no dedicated agent tool matches, use the " +
                "normal agent tools (shell_execute etc.). Never answer \"cannot do it\" " +
                "or \"nothing found\" before trying the matching tool.",
        )
    }

    /** One capability action in compact slot notation, e.g. `create(title:TEXT, start:TIME)`. */
    fun renderAction(action: CapabilityAction): String {
        val slots = action.slots.joinToString(", ") { slot ->
            val type = when (slot.type) {
                SlotType.TIME -> "TIME"
                SlotType.DURATION -> "DURATION"
                SlotType.NUMBER -> "NUMBER"
                SlotType.ENUM -> if (slot.enumValues.isEmpty()) {
                    "ENUM"
                } else {
                    "ENUM(${slot.enumValues.joinToString("|")})"
                }
                SlotType.QUERY -> "QUERY"
                SlotType.VERBATIM_TEXT, SlotType.TEXT -> "TEXT"
                SlotType.PATH -> "PATH"
                SlotType.URL -> "URL"
            }
            "${slot.name}${if (slot.required) "" else "?"}:$type"
        }
        val suffix = when {
            action.risk == CapabilityRisk.HIGH -> " [HIGH: asks first]"
            action.readOnly -> " [read-only]"
            else -> ""
        }
        return if (slots.isEmpty()) "${action.name}$suffix" else "${action.name}($slots)$suffix"
    }
}

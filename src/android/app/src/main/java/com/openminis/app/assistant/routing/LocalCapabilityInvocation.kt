package com.openminis.app.assistant.routing

import org.json.JSONObject

/**
 * [T-cloud-fastpath-catalog] The cloud → fast-path invocation contract.
 *
 * The cloud model calls ONE dispatcher tool, `local_capability`, rather than
 * 13 separate tools: the capability/action/slot surface is large and already
 * described by the registry-driven prompt catalog, and one tool keeps the
 * schema (and the prompt's size) compact. Parsing lives here, free of Android
 * dependencies, so dispatch, validation and confirmation policy are unit
 * testable on the JVM.
 *
 * Safety is not re-implemented here: [RouteDecision.Local.needsConfirmation]
 * is derived from the registry's [CapabilityRisk.HIGH], which is the same
 * destructive-only rule the deterministic router uses, and the caller still
 * runs the executor's preflight + the in-app confirmation card.
 */
sealed interface LocalCapabilityPlan {
    data class Invoke(val decision: RouteDecision.Local) : LocalCapabilityPlan
    data class Reject(val reason: String) : LocalCapabilityPlan
}

object LocalCapabilityInvocation {

    /** Name of the single cloud-callable dispatcher tool. */
    const val TOOL_NAME = "local_capability"

    /**
     * Validate a `local_capability` call and turn it into an executor-ready
     * [RouteDecision.Local].
     *
     * @param args the raw tool arguments: `capability`, `action`, optional
     *   `arguments` (JSON object or JSON-in-a-string) and optional
     *   `tool_title`/`summary`.
     */
    fun plan(args: JSONObject): LocalCapabilityPlan {
        val capabilityId = args.optString("capability").trim()
        if (capabilityId.isEmpty()) {
            return LocalCapabilityPlan.Reject(
                "Missing `capability`. Valid values: " + LocalCapabilityCatalog.invokableCapabilityIds.joinToString(", "),
            )
        }
        val capability = LocalCapabilityRegistry.get(capabilityId)
            ?: return LocalCapabilityPlan.Reject(
                "Unknown capability `$capabilityId`. Valid values: " +
                    LocalCapabilityCatalog.invokableCapabilityIds.joinToString(", "),
            )
        if (capabilityId !in LocalCapabilityRegistry.inProcessCapabilities) {
            return LocalCapabilityPlan.Reject(
                "`$capabilityId` has a dedicated agent tool — call `$capabilityId` directly " +
                    "instead of `$TOOL_NAME`.",
            )
        }

        val actionName = args.optString("action").trim()
        if (actionName.isEmpty()) {
            return LocalCapabilityPlan.Reject(
                "Missing `action` for `$capabilityId`. Valid actions: " +
                    capability.actions.keys.joinToString(", "),
            )
        }
        val action = capability.action(actionName)
            ?: return LocalCapabilityPlan.Reject(
                "Unknown action `$actionName` for `$capabilityId`. Valid actions: " +
                    capability.actions.keys.joinToString(", "),
            )

        val slotArgs = when (val raw = args.opt("arguments")) {
            null, JSONObject.NULL -> JSONObject()
            is JSONObject -> raw
            is String -> if (raw.isBlank()) {
                JSONObject()
            } else {
                runCatching { JSONObject(raw) }.getOrElse {
                    return LocalCapabilityPlan.Reject(
                        "`arguments` must be a JSON object, e.g. {\"title\":\"Dentist\"}.",
                    )
                }
            }
            else -> return LocalCapabilityPlan.Reject(
                "`arguments` must be a JSON object, not ${raw.javaClass.simpleName}.",
            )
        }

        val missing = action.requiredSlots.filter { slotArgs.optString(it).isBlank() }
        if (missing.isNotEmpty()) {
            return LocalCapabilityPlan.Reject(
                "Missing required slot(s) for `$capabilityId/$actionName`: " +
                    missing.joinToString(", ") + ". Required: " +
                    action.requiredSlots.joinToString(", ") + ".",
            )
        }

        // Enum slots carry their contract in the registry; reject a value the
        // executor would have to guess at, so the model corrects itself now.
        for (slot in action.slots) {
            if (slot.type != SlotType.ENUM || slot.enumValues.isEmpty()) continue
            val value = slotArgs.optString(slot.name).trim()
            if (value.isBlank()) continue
            if (value !in slot.enumValues) {
                return LocalCapabilityPlan.Reject(
                    "Slot `${slot.name}` must be one of: ${slot.enumValues.joinToString(", ")} " +
                        "(got `$value`).",
                )
            }
        }

        val summary = args.optString("summary").trim()
            .ifBlank { args.optString("tool_title").trim() }
            .ifBlank { "${capability.label}: ${action.name}" }

        val decision = RouteDecision.Local(
            route = cloudRoute(capability, action),
            capability = capability.id,
            action = action.name,
            args = slotArgs,
            // Destructive-only policy, same as AssistantRouter: HIGH asks, the
            // rest (create/schedule/play/volume/...) runs directly.
            needsConfirmation = action.risk == CapabilityRisk.HIGH,
            needsModelExtraction = false,
            missingSlots = emptySet(),
            score = CLOUD_INVOCATION_SCORE,
            summary = summary,
        )
        return LocalCapabilityPlan.Invoke(decision)
    }

    /**
     * A synthetic route label for the confirmation card / tool block. It is
     * not registered in the route store: the cloud call names the capability
     * and action explicitly, so nothing needs keyword matching.
     */
    private fun cloudRoute(capability: LocalCapability, action: CapabilityAction): AssistantRoute =
        AssistantRoute(
            id = "cloud:${capability.id}:${action.name}",
            label = "${capability.label} · ${action.name}",
            category = "cloud",
            capability = capability.id,
            action = action.name,
            confirm = ConfirmMode.WRITE,
        )

    /** Ranked above every keyword route: the cloud already made the decision. */
    private const val CLOUD_INVOCATION_SCORE = 100
}

package com.openminis.app.agent.guard

/**
 * Risk of one tool call, ordered by severity:
 * `MONEY > IRREVERSIBLE > EXTERNAL > SENSITIVE > NONE`.
 *
 * The declaration order IS the comparison order — [atLeast] and any
 * `level >= other` comparison rely on [ordinal], so do not reorder the
 * constants without updating every reader.
 */
enum class RiskLevel {
    NONE,
    SENSITIVE,
    EXTERNAL,
    IRREVERSIBLE,
    MONEY,
    ;

    fun atLeast(other: RiskLevel): Boolean = ordinal >= other.ordinal
}

/**
 * The outcome of [Guard.classify].
 *
 * [rule] names the rule that fired ("rm -rf", "git push", …). [evidence] is
 * the matched text fragment, already redacted and cut to 200 chars — it is
 * what the audit line stores, never the user's task text.
 */
data class RiskVerdict(
    val level: RiskLevel,
    val rule: String,
    val evidence: String,
) {
    companion object {
        val NONE = RiskVerdict(RiskLevel.NONE, "", "")
    }
}

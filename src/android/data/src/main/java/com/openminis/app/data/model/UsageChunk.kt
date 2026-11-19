package com.openminis.app.data.model

import com.openminis.app.data.model.LLMUsage

/**
 * [T-usage-step1] One provider usage block normalized to the five internal
 * fields of src/shared/usage/USAGE_METRICS_SPEC.md (spec 1.1). A null field
 * means the block did not carry it, which is what lets UsageChunkMerger (in the app module)
 * leave it alone instead of zeroing it.
 */
data class UsageChunk(
    /** Fresh input, cache excluded. */
    val input: Long? = null,
    val cacheRead: Long? = null,
    val cacheWrite: Long? = null,
    /** Output, reasoning included. */
    val output: Long? = null,
    /** Subset of [output]; never added to a total. */
    val reasoning: Long? = null,
    /** Any cache key was present in this block, even with the value 0. */
    val cacheKeyPresent: Boolean = false,
    /** Context size of the request as the provider reports it (not summed). */
    val contextTokens: Long? = null,
) {
    /**
     * The legacy LLMUsage shape. Cache counts of 0 stay null there, as every
     * existing consumer expects; [LLMUsage.cacheReported] carries the
     * difference between "reported 0" and "not reported".
     */
    fun toLLMUsage(): LLMUsage = LLMUsage(
        inputTokens = (input ?: 0).toIntSaturated(),
        outputTokens = (output ?: 0).toIntSaturated(),
        cacheCreationInputTokens = cacheWrite?.takeIf { it > 0 }?.toIntSaturated(),
        cacheReadInputTokens = cacheRead?.takeIf { it > 0 }?.toIntSaturated(),
        latestContextTokens = (contextTokens ?: 0).toIntSaturated(),
        reasoningTokens = reasoning?.toIntSaturated(),
        cacheReported = cacheKeyPresent,
        chunk = this,
    )
}

private fun Long.toIntSaturated(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

package com.openminis.app.agent.skill

import android.content.Context

/**
 * Runtime knobs for skill retrieval, persisted in SharedPreferences so they
 * survive across process starts without touching skills.json.
 *
 * Both the threshold and the algorithm are adjustable (requirement P1-5):
 *  - `algorithm=hybrid` (default) uses [SkillMatcher.Algorithm.HYBRID] with a
 *    default threshold of [SkillMatcher.DEFAULT_HYBRID_THRESHOLD];
 *  - `algorithm=legacy` restores the original character-bigram + Jaccard
 *    scoring, default threshold [SkillMatcher.THRESHOLD] (0.55).
 *
 * An explicitly stored threshold wins over either default, so a harness can
 * sweep values without changing the algorithm.
 */
object SkillSettings {
    private const val PREFS = "skill_match"
    private const val KEY_THRESHOLD = "threshold"
    private const val KEY_ALGORITHM = "algorithm"

    const val MIN_THRESHOLD = 0.05
    const val MAX_THRESHOLD = 0.95

    fun config(context: Context): SkillMatcher.MatchConfig {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val algorithm = SkillMatcher.Algorithm.fromWire(
            prefs.getString(KEY_ALGORITHM, SkillMatcher.Algorithm.HYBRID.wire),
        )
        val stored = prefs.getFloat(KEY_THRESHOLD, Float.NaN)
        val threshold = if (stored.isNaN()) {
            SkillMatcher.Algorithm.defaultThreshold(algorithm)
        } else {
            stored.toDouble().coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)
        }
        return SkillMatcher.MatchConfig(threshold = threshold, algorithm = algorithm)
    }

    /** Returns the effective config (clamped), so a caller can echo it back. */
    fun setThreshold(context: Context, value: Double): SkillMatcher.MatchConfig {
        val clamped = value.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_THRESHOLD, clamped.toFloat()).apply()
        return config(context)
    }

    /** Returns the effective config, including the algorithm's default threshold. */
    fun setAlgorithm(context: Context, value: String): SkillMatcher.MatchConfig {
        val algorithm = SkillMatcher.Algorithm.fromWire(value)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ALGORITHM, algorithm.wire).apply()
        return config(context)
    }

    /** Clear both keys; the defaults of the current algorithm come back. */
    fun reset(context: Context): SkillMatcher.MatchConfig {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_THRESHOLD).remove(KEY_ALGORITHM).apply()
        return config(context)
    }
}

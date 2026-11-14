package com.openminis.app.agent.skill

/**
 * Goal similarity for the skill store: character bigrams + Jaccard, no
 * tokenizer and no dependency beyond the stdlib.
 *
 * A record from the same app is trusted at face value; one from another app is
 * down-weighted (0.82), because the same wording in a different UI does not
 * imply the same taps. Only the top record above [THRESHOLD] is used.
 */
object SkillMatcher {
    const val THRESHOLD = 0.55

    private const val SAME_APP_WEIGHT = 1.0
    private const val CROSS_APP_WEIGHT = 0.82

    data class Match(val record: SkillRecord, val score: Double)

    /**
     * Keep only CJK characters, letters and digits — strips punctuation,
     * whitespace and full-width symbols. Latin letters are lowercased so
     * "Open APP" and "open app" produce the same bigrams.
     */
    fun clean(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            if (isCjk(ch) || ch.isLetterOrDigit()) sb.append(ch.lowercaseChar())
        }
        return sb.toString()
    }

    /** Every adjacent character pair; a 0/1-character string has none. */
    fun bigrams(cleaned: String): Set<String> {
        if (cleaned.length < 2) return emptySet()
        val out = LinkedHashSet<String>(cleaned.length)
        for (i in 0 until cleaned.length - 1) out.add(cleaned.substring(i, i + 2))
        return out
    }

    /** Intersection over union; 0 when either side has no bigrams. */
    fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var intersection = 0
        for (s in a) if (s in b) intersection++
        if (intersection == 0) return 0.0
        val union = a.size + b.size - intersection
        return intersection.toDouble() / union
    }

    /** The goal's first [limit] unique bigrams, stored with the record. */
    fun keywords(goal: String, limit: Int = 12): List<String> =
        bigrams(clean(goal)).take(limit)

    /** Jaccard on cleaned goal bigrams, weighted by app identity. */
    fun score(query: String, record: SkillRecord, appPackage: String?): Double {
        val q = bigrams(clean(query))
        val r = bigrams(clean(record.goal))
        val similarity = jaccard(q, r)
        if (similarity <= 0.0) return 0.0
        val weight = if (appPackage != null && appPackage == record.appPackage) {
            SAME_APP_WEIGHT
        } else {
            CROSS_APP_WEIGHT
        }
        return similarity * weight
    }

    /** Top-1 above [THRESHOLD], or null. */
    fun best(query: String, records: List<SkillRecord>, appPackage: String?): Match? {
        var best: Match? = null
        for (record in records) {
            val s = score(query, record, appPackage)
            if (s > (best?.score ?: 0.0)) best = Match(record, s)
        }
        return best?.takeIf { it.score >= THRESHOLD }
    }

    private fun isCjk(ch: Char): Boolean {
        val c = ch.code
        return (c in 0x4E00..0x9FFF) ||   // CJK Unified Ideographs
            (c in 0x3400..0x4DBF) ||      // CJK Extension A
            (c in 0xF900..0xFAFF) ||      // CJK Compatibility Ideographs
            (c in 0x3040..0x309F) ||      // Hiragana
            (c in 0x30A0..0x30FF) ||      // Katakana
            (c in 0xAC00..0xD7AF)         // Hangul syllables
    }
}

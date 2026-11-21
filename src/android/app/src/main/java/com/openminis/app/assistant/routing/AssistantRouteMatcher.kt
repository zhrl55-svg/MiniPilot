package com.openminis.app.assistant.routing

import java.text.Normalizer
import java.util.Locale

data class RouteMatch(
    val route: AssistantRoute,
    val score: Int,
    val matchedKeywords: List<String>,
    val matchedPatterns: List<String>,
    /**
     * [T-fastpath-topic-guard] 每个命中 pattern / 关键词在归一化文本里的
     * 区间。长度和位置决定这次命中是「整句都在请求」还是「长文里顺带
     * 提了一句」。
     */
    val matchedRanges: List<IntRange> = emptyList(),
) {
    val longestKeywordLength: Int
        get() = matchedKeywords.maxOfOrNull { it.length } ?: 0

    val isStrong: Boolean
        get() = matchedPatterns.isNotEmpty() || matchedKeywords.any {
            val ascii = it.all { ch -> ch.code < 128 }
            if (ascii) it.length >= 3 else it.length >= 2
        }
}

/**
 * Pure best-match router. It never decides that "the first matching rule wins":
 * every enabled route is scored, and ties are resolved by priority, longest
 * matched keyword, then id.
 */
object AssistantRouteMatcher {

    const val MIN_SCORE = 50

    fun rank(
        userText: String,
        routes: List<AssistantRoute>,
        limit: Int = 3,
    ): List<RouteMatch> {
        val text = normalizeText(userText)
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<RouteMatch>()
        for (route in routes) {
            if (!route.enabled) continue
            if (route.capability.isBlank() || route.action.isBlank()) continue
            if (!LocalCapabilityRegistry.supports(route.capability, route.action)) continue

            val excluded = route.exclude.any { term ->
                term.isNotBlank() && containsTerm(text, normalizeText(term))
            }
            if (excluded) continue

            if (route.allKeywords.any { !containsTerm(text, normalizeText(it)) }) continue

            val matchedKeywords = route.keywords
                .filter { it.isNotBlank() }
                .map { it.trim() }
                .filter { containsTerm(text, normalizeText(it)) }
            val matchedPatterns = ArrayList<String>()
            val matchedRanges = ArrayList<IntRange>()
            for (pattern in route.patterns) {
                val range = runCatching {
                    Regex(pattern, RegexOption.IGNORE_CASE).find(text)?.range
                }.getOrNull() ?: continue
                matchedPatterns += pattern
                matchedRanges += range
            }
            for (keyword in matchedKeywords) {
                val needle = normalizeText(keyword)
                val at = text.indexOf(needle)
                if (at >= 0) matchedRanges += at..(at + needle.length - 1)
            }
            if (matchedKeywords.isEmpty() && matchedPatterns.isEmpty()) continue

            var score = 40
            matchedKeywords.forEach { keyword ->
                score += 8 + minOf(normalizeText(keyword).length, 12)
            }
            score += matchedPatterns.size * 18
            score += route.allKeywords.count { containsTerm(text, normalizeText(it)) } * 6
            out += RouteMatch(route, score, matchedKeywords, matchedPatterns, matchedRanges)
        }
        return out.sortedWith(
            compareByDescending<RouteMatch> { it.score }
                .thenByDescending { it.route.priority }
                .thenByDescending { it.longestKeywordLength }
                .thenBy { it.route.id },
        ).take(limit.coerceAtLeast(0))
    }

    fun normalizeText(input: String): String {
        return normalizeTextPreservingCase(input).lowercase(Locale.ROOT)
    }

    /**
     * Matching is case-insensitive, but extracted arguments must keep their
     * original case (shell commands, paths, URLs, note bodies and names are
     * values, not intent). This variant performs the same compatibility and
     * whitespace normalization without folding case.
     */
    fun normalizeTextPreservingCase(input: String): String {
        val nfkc = Normalizer.normalize(input, Normalizer.Form.NFKC)
        return nfkc
            .replace('\u3000', ' ')
            // Normalize the punctuation that commonly differs between the
            // keyboard and the model, without deleting semantic characters.
            .replace('，', ',')
            .replace('。', '.')
            .replace('；', ';')
            .replace('：', ':')
            .replace('！', '!')
            .replace('？', '?')
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun containsTerm(normalizedText: String, normalizedTerm: String): Boolean {
        if (normalizedTerm.isEmpty()) return false
        val ascii = normalizedTerm.all { it.code < 128 }
        if (!ascii) return normalizedText.contains(normalizedTerm)
        val escaped = Regex.escape(normalizedTerm)
        // `_` 也算词内字符：工具名 `hub_call` 里的 "call" 不是「打电话」，
        // `run_command` 里的 "command" 也不是「运行命令」。
        return Regex("""(?<![a-z0-9_])$escaped(?![a-z0-9_])""").containsMatchIn(normalizedText)
    }
}

package com.openminis.app.data.repository.memory

import java.util.Locale

/**
 * Formats ranked entries for a tool result.
 *
 * Every block keeps its source file and section title so the model can cite
 * the fact rather than treating retrieved text as anonymous context. The
 * output is bounded by [MemoryIndexConfig.MAX_RECALL_CHARS] in total and
 * [MemoryIndexConfig.MAX_ENTRY_CHARS] per entry; unlike the old line-window
 * dump, the cap trims the tail rather than dropping the ranking silently.
 */
object MemorySearchFormatter {
    private const val HEADER_RESERVE = 300

    fun format(query: String, result: MemorySearchResult): String {
        val shownQuery = query.trim().take(120)
        if (result.hits.isEmpty()) {
            return "No matches found for: $shownQuery"
        }

        val body = StringBuilder()
        val bodyBudget = MemoryIndexConfig.MAX_RECALL_CHARS - HEADER_RESERVE
        var truncated = false

        for (hit in result.hits) {
            val entry = hit.entry
            val tags = if (entry.tags.isEmpty()) {
                ""
            } else {
                " | tags: ${entry.tags.sorted().joinToString(", ")}"
            }
            val meta = "[${entry.file} › ${entry.title} | score ${score(hit.score)}$tags]"
            var text = entry.body.trim().ifEmpty { "（无正文）" }
            if (text.length > MemoryIndexConfig.MAX_ENTRY_CHARS) {
                text = text.take(MemoryIndexConfig.MAX_ENTRY_CHARS).trimEnd() + "…"
            }
            val block = "$meta\n$text"
            val separator = if (body.isEmpty()) "" else "\n---\n"

            if (body.length + separator.length + block.length > bodyBudget) {
                val remaining = bodyBudget - body.length - separator.length
                if (remaining > 200) {
                    body.append(separator)
                        .append(block.take(remaining).trimEnd())
                        .append('…')
                }
                truncated = true
                break
            }
            body.append(separator).append(block)
        }

        val prefix = "[Recalled ${result.hits.size} entries (of ${result.totalEntries}) " +
            "for \"$shownQuery\" — ~"
        val kiloChars = (prefix.length + body.length) / 1000.0
        val header = prefix + String.format(Locale.US, "%.1fk chars]", kiloChars)
        val footer = if (truncated) {
            "\n\n[Truncated to fit ${MemoryIndexConfig.MAX_RECALL_CHARS} chars; " +
                "narrow the query or lower limit for more.]"
        } else {
            ""
        }
        return "$header\n$body$footer"
    }

    private fun score(value: Double): String = String.format(Locale.US, "%.3f", value)
}

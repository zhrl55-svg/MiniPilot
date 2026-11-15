package com.openminis.app.data.repository.memory

enum class MemoryKind {
    GLOBAL,
    USER,
    DAILY,
}

enum class MemoryScope {
    DAILY,
    ALL;

    companion object {
        fun from(raw: String?): MemoryScope =
            if (raw != null && raw.trim().equals("daily", ignoreCase = true)) DAILY else ALL
    }
}

/**
 * One searchable section of a memory file.
 *
 * GLOBAL.md and USER.md are split on `## ` headings; daily logs are split on
 * `<!-- YYYY-MM-DD HH:MM:SS -->` markers. [tokens] is the CJK-bigram/Latin
 * token stream of `title + body`, and [tf] is its term frequency map. The
 * index is derived data only; the Markdown file remains the source of truth.
 */
data class MemoryEntry(
    val id: String,
    val file: String,
    val kind: MemoryKind,
    val title: String,
    val body: String,
    val date: String?,
    val headings: List<String>,
    val tokens: List<String>,
    val tokenSet: Set<String>,
    val tf: Map<String, Int>,
    val tags: Set<String>,
    val recency: Double,
    val importance: Double,
) {
    val fullText: String
        get() = if (body.isBlank()) title else "$title\n$body"
}

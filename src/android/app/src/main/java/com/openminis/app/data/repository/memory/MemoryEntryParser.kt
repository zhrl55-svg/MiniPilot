package com.openminis.app.data.repository.memory

import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.exp

/**
 * Pure-JVM parser for the Markdown memory directory.
 *
 * Only GLOBAL.md, USER.md, and `YYYY-MM-DD.md` files are indexed. SOUL.md is
 * the always-on identity file, dotfiles are private side-channel files (for
 * example `.workbuddy_phone`), and `*.bak.*` files are history — none of them
 * may ever enter the index. That exclusion is a hard invariant, covered by
 * unit tests, not a best-effort filter.
 */
object MemoryEntryParser {
    private val DATE_FILE = Regex("^(\\d{4}-\\d{2}-\\d{2})\\.md$")
    private val ENTRY_MARKER = Regex("^<!--\\s*(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})\\s*-->\\s*$")
    private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    /**
     * Returns true for a file name the index may read.
     *
     * Kept public so the repository's fingerprint uses exactly the same file
     * set as the parser; a mismatch would make the index look stale (or hide
     * a real change).
     */
    fun isMemoryFile(name: String): Boolean {
        if (name.startsWith(".")) return false
        if (name.contains(".bak.")) return false
        if (name == "SOUL.md") return false
        return name == "GLOBAL.md" || name == "USER.md" || DATE_FILE.matches(name)
    }

    fun parse(memoryDir: File, warn: (String) -> Unit = {}): List<MemoryEntry> {
        val entries = ArrayList<MemoryEntry>()

        val global = File(memoryDir, "GLOBAL.md")
        if (global.isFile) {
            entries.addAll(parseSections(global, MemoryKind.GLOBAL, warn))
        }

        val user = File(memoryDir, "USER.md")
        if (user.isFile) {
            entries.addAll(parseSections(user, MemoryKind.USER, warn))
        }

        val dailyFiles = memoryDir.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && DATE_FILE.matches(it.name) }
            ?.sortedByDescending { it.name }
            ?.toList()
            .orEmpty()
        for (file in dailyFiles) {
            entries.addAll(parseDaily(file, warn))
        }
        return entries
    }

    private fun parseSections(file: File, kind: MemoryKind, warn: (String) -> Unit): List<MemoryEntry> {
        val raw = readCapped(file, warn) ?: return emptyList()
        val entries = ArrayList<MemoryEntry>()
        var currentTitle: String? = null
        var currentBody = ArrayList<String>()

        fun flush() {
            val title = currentTitle ?: "（前言）"
            val body = currentBody.joinToString("\n").trim()
            if (currentTitle != null || body.isNotEmpty()) {
                entries.add(makeEntry(file.name, title, body, kind, entries.size, null))
            }
            currentTitle = null
            currentBody = ArrayList()
        }

        for (line in raw.lines()) {
            if (line.startsWith("## ")) {
                flush()
                currentTitle = line.substring(3).trim()
            } else {
                currentBody.add(line)
            }
        }
        flush()
        return entries
    }

    private fun parseDaily(file: File, warn: (String) -> Unit): List<MemoryEntry> {
        val raw = readCapped(file, warn) ?: return emptyList()
        val date = DATE_FILE.find(file.name)?.groupValues?.getOrNull(1)
        val blocks = ArrayList<Pair<String?, List<String>>>()
        var current = ArrayList<String>()
        var timestamp: String? = null

        fun flushBlock() {
            if (current.any { it.isNotBlank() }) {
                blocks.add(timestamp to current)
            }
            current = ArrayList()
        }

        for (line in raw.lines()) {
            val marker = ENTRY_MARKER.matchEntire(line)
            if (marker != null) {
                flushBlock()
                timestamp = marker.groupValues[1]
            } else {
                current.add(line)
            }
        }
        flushBlock()

        val entries = ArrayList<MemoryEntry>()
        for ((_, block) in blocks) {
            // Empty, not the placeholder: makeEntry falls back to the
            // structured-write Summary before finally using "（无标题）".
            var title = ""
            val body = ArrayList<String>()
            var titleTaken = false
            for (line in block) {
                if (!titleTaken && line.startsWith("## ")) {
                    title = line.substring(3).trim()
                    titleTaken = true
                } else {
                    body.add(line)
                }
            }
            entries.add(
                makeEntry(
                    file = file.name,
                    title = title,
                    body = body.joinToString("\n"),
                    kind = MemoryKind.DAILY,
                    index = entries.size,
                    date = date,
                ),
            )
        }
        return entries
    }

    private fun makeEntry(
        file: String,
        title: String,
        body: String,
        kind: MemoryKind,
        index: Int,
        date: String?,
    ): MemoryEntry {
        // [T-memory-structured-write] Optional prefixes written by
        // memory_write. They stay in the body (so the raw daily log remains
        // self-describing) but also feed the index's title/tag signals.
        val summary = body.lines()
            .firstOrNull { it.startsWith("Summary:") }
            ?.removePrefix("Summary:")
            ?.trim()
            .orEmpty()
        val explicitTags = body.lines()
            .filter { it.startsWith("Tags:") }
            .flatMap { MemoryTagger.canonicalize(it.removePrefix("Tags:")) }
            .toSet()
        val safeTitle = title.trim().ifEmpty { summary.take(80) }.ifEmpty { "（无标题）" }
        val safeBody = body.trim().take(MemoryIndexConfig.MAX_ENTRY_TEXT_CHARS)
        val full = "$safeTitle\n$safeBody"
        val tokens = MemoryTokenizer.tokenize(full)
        val tf = HashMap<String, Int>()
        for (token in tokens) {
            tf[token] = (tf[token] ?: 0) + 1
        }
        val headings = safeBody.lines()
            .filter { it.startsWith("### ") }
            .map { it.substring(4).trim() }
            .filter { it.isNotEmpty() }

        return MemoryEntry(
            id = "$file#$index",
            file = file,
            kind = kind,
            title = safeTitle,
            body = safeBody,
            date = date,
            headings = headings,
            tokens = tokens,
            tokenSet = tokens.toHashSet(),
            tf = tf,
            tags = MemoryTagger.tagsFor(full) + explicitTags,
            recency = recencyFor(kind, date),
            importance = importanceFor(kind),
        )
    }

    private fun recencyFor(kind: MemoryKind, date: String?): Double {
        if (kind != MemoryKind.DAILY || date == null) return MemoryIndexConfig.STATIC_RECENCY
        return try {
            val day = LocalDate.parse(date, DATE_FORMATTER)
            val age = ChronoUnit.DAYS.between(day, LocalDate.now()).coerceAtLeast(0L)
            exp(-age.toDouble() / MemoryIndexConfig.RECENCY_HALF_LIFE_DAYS)
        } catch (_: Exception) {
            0.5
        }
    }

    private fun importanceFor(kind: MemoryKind): Double = when (kind) {
        MemoryKind.GLOBAL -> MemoryIndexConfig.IMPORTANCE_GLOBAL
        MemoryKind.USER -> MemoryIndexConfig.IMPORTANCE_USER
        MemoryKind.DAILY -> MemoryIndexConfig.IMPORTANCE_DAILY
    }

    private fun readCapped(file: File, warn: (String) -> Unit): String? {
        return try {
            if (file.length() <= MemoryIndexConfig.MAX_FILE_BYTES) {
                file.readText()
            } else {
                warn("memory file exceeds ${MemoryIndexConfig.MAX_FILE_BYTES} bytes, truncating: ${file.name}")
                file.inputStream().bufferedReader(Charsets.UTF_8).use { reader ->
                    val buffer = CharArray(MemoryIndexConfig.MAX_FILE_BYTES)
                    var total = 0
                    while (total < buffer.size) {
                        val read = reader.read(buffer, total, buffer.size - total)
                        if (read < 0) break
                        total += read
                    }
                    String(buffer, 0, total)
                }
            }
        } catch (e: Exception) {
            warn("failed to read ${file.name}: ${e.message}")
            null
        }
    }
}

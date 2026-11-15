package com.openminis.app.data.repository.memory

import java.io.File

/**
 * Owns the mutable index state for one memory directory.
 *
 * The index is derived data: it is never written to disk and never touches
 * the Markdown files or backups. Writes go through explicit [invalidate], and
 * a cheap `(name, size, lastModified)` fingerprint catches external writes
 * (Settings, SAF import, restore) before the next search.
 */
class MemoryIndexRepository(
    private val memoryDir: File,
    private val warn: (String) -> Unit = {},
) {
    private data class FileStamp(
        val name: String,
        val size: Long,
        val modified: Long,
    )

    private val lock = Any()

    @Volatile
    private var cachedIndex: MemoryIndex? = null

    @Volatile
    private var cachedFingerprint: List<FileStamp>? = null

    @Volatile
    private var cachedBuildMs: Long = 0L

    @Volatile
    private var lastQueryMs: Long = 0L

    @Volatile
    private var dirty: Boolean = true

    fun search(
        query: String,
        scope: MemoryScope,
        limit: Int = MemoryIndexConfig.TOP_K,
    ): MemorySearchResult {
        val index = ensureIndex()
        val started = System.nanoTime()
        val hits = index.search(query, scope, limit)
        val queryMs = (System.nanoTime() - started) / 1_000_000L
        lastQueryMs = queryMs
        return MemorySearchResult(
            hits = hits,
            totalEntries = index.entries.size,
            buildMs = cachedBuildMs,
            queryMs = queryMs,
        )
    }

    /** Build the index ahead of the first tool call. Call from Dispatchers.IO. */
    fun prewarm() {
        try {
            ensureIndex()
        } catch (_: Exception) {
            // The facade falls back to the legacy scan; startup must not fail.
        }
    }

    fun invalidate() {
        dirty = true
    }

    fun rebuild() {
        synchronized(lock) {
            dirty = true
            cachedIndex = null
            cachedFingerprint = null
        }
        ensureIndex()
    }

    fun status(): MemoryIndexStatus {
        val index = try {
            ensureIndex()
        } catch (_: Exception) {
            null
        }
        val entries = index?.entries.orEmpty()
        val fingerprint = cachedFingerprint
            ?.joinToString(",") { "${it.name}:${it.size}" }
            ?.take(500)
            .orEmpty()
        return MemoryIndexStatus(
            entryCount = entries.size,
            globalCount = entries.count { it.kind == MemoryKind.GLOBAL },
            userCount = entries.count { it.kind == MemoryKind.USER },
            dailyCount = entries.count { it.kind == MemoryKind.DAILY },
            chars = entries.fold(0) { total, entry -> total + entry.fullText.length },
            buildMs = cachedBuildMs,
            lastQueryMs = lastQueryMs,
            fingerprint = fingerprint,
        )
    }

    private fun ensureIndex(): MemoryIndex {
        val stamp = currentFingerprint()
        val current = cachedIndex
        if (current != null && !dirty && stamp == cachedFingerprint) return current

        synchronized(lock) {
            val rechecked = cachedIndex
            if (rechecked != null && !dirty && stamp == cachedFingerprint) return rechecked

            val started = System.nanoTime()
            val entries = MemoryEntryParser.parse(memoryDir, warn)
            val built = MemoryIndex(entries)
            cachedIndex = built
            cachedFingerprint = stamp
            cachedBuildMs = (System.nanoTime() - started) / 1_000_000L
            dirty = false
            return built
        }
    }

    private fun currentFingerprint(): List<FileStamp> {
        val files = memoryDir.listFiles() ?: return emptyList()
        return files.asSequence()
            .filter { it.isFile && MemoryEntryParser.isMemoryFile(it.name) }
            .map { FileStamp(it.name, it.length(), it.lastModified()) }
            .sortedBy { it.name }
            .toList()
    }
}

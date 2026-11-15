package com.openminis.app.data.repository.memory

data class MemorySearchResult(
    val hits: List<MemoryHit>,
    val totalEntries: Int,
    val buildMs: Long,
    val queryMs: Long,
)

data class MemoryIndexStatus(
    val entryCount: Int,
    val globalCount: Int,
    val userCount: Int,
    val dailyCount: Int,
    val chars: Int,
    val buildMs: Long,
    val lastQueryMs: Long,
    val fingerprint: String,
)

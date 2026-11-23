package com.openminis.app.provider

import org.json.JSONObject

/**
 * [T-toolcall-args-integrity] Parsed outcome of one tool call's accumulated
 * argument text.
 *
 * Both providers used to collapse a non-empty-but-unparseable argument string
 * into `JSONObject()` (an empty object) with no signal. Downstream that became
 * a real tool call with `{}` — `hub_call` then failed on the remote with
 * "shell needs args.command", the model could not tell that ITS arguments had
 * been lost, and retried the same empty call up to 14 times (self-improve card
 * 2026-11-22-91f2a642-1). Keeping [invalidReason] lets the execution layer
 * refuse the call with an explicit message instead of executing on `{}`.
 */
internal data class ToolArgsParse(
    val args: JSONObject,
    /** Non-null when the text was non-blank but not valid JSON. */
    val invalidReason: String?,
)

internal fun parseToolArgs(raw: CharSequence): ToolArgsParse {
    val text = raw.toString()
    if (text.isBlank()) return ToolArgsParse(JSONObject(), null)
    return try {
        ToolArgsParse(JSONObject(text), null)
    } catch (e: Exception) {
        ToolArgsParse(
            JSONObject(),
            "工具参数 JSON 不完整或损坏（收到 ${text.length} 字符）：" +
                (e.message ?: e.javaClass.simpleName),
        )
    }
}

/**
 * [T-toolcall-args-integrity] One argument buffer per Anthropic content block.
 *
 * The provider previously kept a SINGLE `currentToolId/currentToolName/buffer`
 * and ignored the `index` carried by every SSE frame. Any out-of-order start,
 * a missing `content_block_stop`, or an interleaved block overwrote or cleared
 * the buffer, so later calls arrived with empty arguments. Keying by index
 * keeps parallel blocks independent and lets the stream-end flush report the
 * blocks whose arguments never finished.
 */
internal class ToolCallBuffers {
    internal class Entry(val id: String, val name: String) {
        val buffer = StringBuilder()
    }

    private val byIndex = HashMap<Int, Entry>()
    private var last: Entry? = null

    fun start(index: Int, id: String, name: String): Entry {
        val entry = Entry(id, name)
        byIndex[index] = entry
        last = entry
        return entry
    }

    /** The block for [index]; falls back to the most recently started one. */
    fun entry(index: Int): Entry? = byIndex[index] ?: last

    fun append(index: Int, partial: String): Entry? {
        val entry = entry(index) ?: return null
        entry.buffer.append(partial)
        return entry
    }

    /** Remove and return the block for [index] (or the most recent one). */
    fun finish(index: Int): Entry? {
        val entry = byIndex.remove(index) ?: last ?: return null
        byIndex.entries.removeAll { it.value === entry }
        if (last === entry) last = byIndex.values.lastOrNull()
        return entry
    }

    /** Blocks still open when the stream ended — their arguments were cut short. */
    fun drainUnfinished(): List<Entry> {
        val open = byIndex.values.toList()
        clear()
        return open
    }

    fun clear() {
        byIndex.clear()
        last = null
    }

    fun size(): Int = byIndex.size
}

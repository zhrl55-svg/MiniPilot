package com.openminis.app.offload

/**
 * [T-android-notification-summary] In-process inbox of notifications the
 * listener has seen since this process started.
 *
 * Deliberately memory-only: notification bodies can contain verification
 * codes and private messages, so nothing here is ever written to disk. A
 * process restart (or the user revoking notification access) leaves it empty.
 */
object NotificationInbox {

    data class Entry(
        val packageName: String,
        val appName: String,
        val title: String,
        val text: String,
        val postedAt: Long,
    )

    /** The tool exposes at most 20; the cache keeps a little more history. */
    const val MAX_ENTRIES = 50

    private val entries = ArrayDeque<Entry>()

    @Synchronized
    fun add(entry: Entry) {
        if (entry.title.isBlank() && entry.text.isBlank()) return
        // A notification that updates in place would otherwise fill the box
        // with copies of the same message.
        entries.removeAll {
            it.packageName == entry.packageName &&
                it.title == entry.title &&
                it.text == entry.text
        }
        entries.addFirst(entry)
        while (entries.size > MAX_ENTRIES) entries.removeLast()
    }

    @Synchronized
    fun recent(limit: Int = 5): List<Entry> =
        entries.take(limit.coerceIn(1, MAX_ENTRIES))

    @Synchronized
    fun size(): Int = entries.size

    @Synchronized
    fun clear() = entries.clear()
}

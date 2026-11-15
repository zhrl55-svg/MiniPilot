package com.openminis.app.data.repository.memory

/**
 * Dependency-free tokenizer for Chinese/English mixed memory text.
 *
 * CJK runs become adjacent bigrams (single-character runs stay single);
 * Latin/digit runs become lowercase words, and snake_case identifiers also
 * contribute their individual parts. This is deliberately lexical: it needs
 * no dictionary, no model, and no Android API, so it is cheap to build on
 * device and easy to unit-test on the JVM.
 */
object MemoryTokenizer {
    private val CJK_RUN = Regex(
        "[\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF\\u3040-\\u30FF\\uAC00-\\uD7AF]+",
    )
    private val LATIN_RUN = Regex("[A-Za-z0-9_]+")

    fun tokenize(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val lower = text.lowercase()
        val out = ArrayList<String>()

        for (match in CJK_RUN.findAll(lower)) {
            val run = match.value
            if (run.length == 1) {
                out.add(run)
            } else {
                for (i in 0 until run.length - 1) {
                    out.add(run.substring(i, i + 2))
                }
            }
        }

        for (match in LATIN_RUN.findAll(lower)) {
            val token = match.value
            out.add(token)
            if (token.indexOf('_') >= 0) {
                for (part in token.split('_')) {
                    if (part.isNotEmpty()) out.add(part)
                }
            }
        }
        return out
    }

    fun tokenSet(text: String): Set<String> = tokenize(text).toHashSet()
}

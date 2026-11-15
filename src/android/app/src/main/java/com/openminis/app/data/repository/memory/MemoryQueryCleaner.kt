package com.openminis.app.data.repository.memory

/**
 * Normalizes a natural-language memory query before tokenization.
 *
 * The agent often passes a whole short question ("buntu 上有哪些服务") rather
 * than keywords. The legacy `memory_get` required every whitespace token to
 * match, so question words were fatal. This cleaner removes pure
 * question/function words and glued question suffixes, then expands a small,
 * deliberately manual set of domain synonyms. Content words (服务/构建/端口/
 * 版本/失败/记录…) are kept; there is no global single-character deletion, so
 * terms such as "上证" survive.
 */
object MemoryQueryCleaner {
    private val STOPWORDS = setOf(
        "怎么", "怎么修", "怎么用", "怎么填", "怎么走", "怎么连", "怎么看",
        "怎么取", "怎么发", "怎么开", "什么", "是什么", "为什么", "在哪",
        "哪些", "哪个", "能不能", "多少", "吗", "呢", "的", "了", "要",
        "有", "我的", "我", "上", "下", "里", "中", "和", "与", "及", "或",
        "是", "不", "会", "就", "把", "被", "在", "到", "从", "对", "为",
        "这个", "那个", "一下", "现在", "目前", "需要", "可以", "应该",
        "什么时候", "时候", "谁", "放在哪", "弹出", "弹", "用什么", "用什么查",
        "找谁", "怎么进",
    )

    private val QUERY_SUFFIXES = listOf(
        "上有哪些", "有哪些", "有什么", "是什么", "为什么", "怎么进", "怎么修",
        "怎么用", "怎么填", "怎么走", "怎么连", "怎么看", "怎么取", "怎么发",
        "怎么开", "在哪里", "放在哪", "什么时候", "了多少", "了什么", "多少行",
        "用什么查", "用什么", "找谁", "了吗",
    )

    private val SUFFIXES_BY_LENGTH = QUERY_SUFFIXES.sortedByDescending { it.length }

    private val EXPANSIONS = mapOf(
        "歌源" to listOf("音乐源", "网易云"),
        "小窗" to listOf("freeform", "浮窗"),
        "401" to listOf("magpie", "控制台"),
        "手机号" to listOf("身份", "workbuddy"),
        "验证码" to listOf("短信", "登录"),
        "唤醒词" to listOf("唤醒"),
    )

    private val WHITESPACE = Regex("\\s+")

    fun clean(query: String): String {
        val words = ArrayList<String>()
        for (raw in query.split(WHITESPACE)) {
            if (raw.isBlank() || raw in STOPWORDS) continue
            var word = raw
            for (suffix in SUFFIXES_BY_LENGTH) {
                if (word.contains(suffix)) word = word.replace(suffix, " ")
            }
            for (part in word.split(WHITESPACE)) {
                if (part.isNotBlank() && part !in STOPWORDS) words.add(part)
            }
        }
        if (words.isEmpty()) return query

        val expanded = ArrayList<String>(words)
        for (word in words) {
            expanded.addAll(EXPANSIONS[word].orEmpty())
        }
        return expanded.joinToString(" ")
    }
}

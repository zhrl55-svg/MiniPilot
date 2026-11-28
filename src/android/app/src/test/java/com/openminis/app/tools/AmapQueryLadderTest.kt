package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-amap-query-ladder] 「查询药店」这一类 bug 的**类型级**回归。
 *
 * 2026-11-28 真机：「查询附近的药店」「查询周边加油站」被当成关键词发出去，
 * 高德回 0 条，用户看到「附近 3000m 内没有找到『查询药店』」。
 *
 * 修法不是再补一个词，而是两层：
 *  1. 词表/归一化尽量剥干净（个案）；
 *  2. 工具在「0 结果」时按**结构**再剥一次重试，仍不行就返回失败让云端接手
 *     （类型）——词表漏词最多多一次请求，不会变成一句假结论。
 *
 * 因此这里的断言是「管线终态」：归一化剥不干净时，重试候选必须能捞回干净的
 * 品类词。新说法先在语料里红，而不是等用户踩到。
 */
class AmapQueryLadderTest {

    private val nouns = listOf("药店", "加油站", "咖啡店", "超市", "银行")

    /** 常见的口语外壳。%s 处放品类词。 */
    private val frames = listOf(
        "查询附近的%s",
        "查询周边%s",
        "查一下附近的%s",
        "帮我查询%s",
        "搜一下%s",
        "帮我搜%s",
        "搜搜%s",
        "找一家%s",
        "帮我找%s",
        "看看附近的%s",
        "我想找%s",
        "麻烦查一下%s",
        "附近有%s吗",
        "周边有什么%s",
        "离我最近的%s",
        "这附近哪有%s",
    )

    /** 管线终态：直接归一化，或重试候选之一，能落到干净品类词上。 */
    private fun resolvesTo(text: String, noun: String): Boolean {
        val plan = AmapPlaceQuery.plan(text)
        val first = plan.keywords.firstOrNull() ?: return false
        if (first == noun) return true
        return AmapPlaceQuery.retryQueries(first).contains(noun)
    }

    @Test
    fun `every everyday frame resolves to a bare noun`() {
        for (frame in frames) {
            for (noun in nouns) {
                val text = frame.format(noun)
                assertTrue(
                    "$text → plan=${AmapPlaceQuery.plan(text).keywords} " +
                        "retry=${AmapPlaceQuery.plan(text).keywords.firstOrNull()?.let { AmapPlaceQuery.retryQueries(it) }}",
                    resolvesTo(text, noun),
                )
            }
        }
    }

    @Test
    fun `retry candidates never carry a command word`() {
        for (frame in frames) {
            for (noun in nouns) {
                val text = frame.format(noun)
                val first = AmapPlaceQuery.plan(text).keywords.firstOrNull().orEmpty()
                AmapPlaceQuery.retryQueries(first).forEach { candidate ->
                    assertFalse(
                        "$text → $candidate",
                        AmapPlaceQuery.startsWithCommandWord(candidate),
                    )
                }
            }
        }
    }

    /**
     * 词表覆盖不到的动词（结构规则兜底）：归一化会留下指令残渣，
     * 但重试候选里必须能捞回干净的品类词。
     */
    @Test
    fun `retry ladder recovers unknown command verbs`() {
        val cases = mapOf(
            "瞅瞅药店" to "药店",
            "瞄一眼加油站" to "加油站",
            "扫描附近药店" to "药店",
            "查询药店" to "药店",
            "瞧瞧超市" to "超市",
        )
        for ((text, noun) in cases) {
            assertTrue("$text should resolve to $noun", resolvesTo(text, noun))
        }
    }

    @Test
    fun `retry candidates are bounded and never the original`() {
        val candidates = AmapPlaceQuery.retryQueries("查询附近的药店")
        assertTrue("bounded", candidates.size <= 2)
        assertFalse("excludes original", candidates.contains("查询附近的药店"))
        assertEquals(emptyList<String>(), AmapPlaceQuery.retryQueries("药店"))
        assertEquals(emptyList<String>(), AmapPlaceQuery.retryQueries(""))
    }

    @Test
    fun `command word detection only looks at the head`() {
        assertTrue(AmapPlaceQuery.startsWithCommandWord("查询药店"))
        assertTrue(AmapPlaceQuery.startsWithCommandWord("哪有药店"))
        assertFalse(AmapPlaceQuery.startsWithCommandWord("药店"))
        assertFalse(AmapPlaceQuery.startsWithCommandWord("张掖西关药店"))
    }
}

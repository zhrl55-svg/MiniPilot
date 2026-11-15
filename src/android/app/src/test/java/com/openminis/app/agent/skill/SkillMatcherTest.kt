package com.openminis.app.agent.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1 acceptance at the matcher level: differently-worded Chinese phrasings of
 * the same intent must be retrieved, unrelated tasks must not be.
 *
 * The 2026-11-15 field data is the baseline: character-bigram Jaccard scored
 * the six related tasks at <= 0.036, so a 0.55 threshold matched nothing. The
 * hybrid concept score is what turns that 0% into a usable hit rate.
 */
class SkillMatcherTest {

    private fun record(goal: String, app: String? = "com.openminis.app.dev", id: String = goal) =
        SkillRecord(
            id = id,
            goal = goal,
            appPackage = app,
            keywords = SkillMatcher.keywords(goal),
            steps = emptyList(),
            toolChain = emptyList(),
            createdAt = 0L,
            lastUsedAt = 0L,
        )

    private val pairs = listOf(
        "看看手机存储都被什么占满了" to "查询一下 App 私有目录的占用构成",
        "打开手电筒" to "把闪光灯打开",
        "帮我把屏幕截个图" to "截屏保存一下",
        "手机还剩多少电" to "查一下电池电量",
        "清理一下没用的缓存" to "把垃圾文件删掉释放空间",
        "帮我记一下明天要开会" to "添加一个明天开会的提醒",
        "现在连着哪个WiFi" to "查一下无线网络状态",
        "把音量调到一半" to "声音小一点",
        "看一下今天的天气" to "今天会下雨吗",
        "打开地图导航去公司" to "帮我规划到公司的路线",
    )

    private fun bestFor(query: String, records: List<SkillRecord>): SkillMatcher.Match? =
        SkillMatcher.best(
            query, records, "com.openminis.app.dev",
            SkillMatcher.MatchConfig(threshold = SkillMatcher.DEFAULT_HYBRID_THRESHOLD),
        )

    @Test
    fun `ten differently-worded Chinese pairs are retrieved above 50 percent`() {
        val records = pairs.mapIndexed { i, (stored, _) -> record(stored, id = "r$i") }
        var hits = 0
        val misses = mutableListOf<String>()
        pairs.forEachIndexed { i, (_, query) ->
            val match = bestFor(query, records)
            if (match?.record?.id == "r$i") hits++ else misses.add("$query -> ${match?.record?.goal}")
        }
        val rate = hits.toDouble() / pairs.size
        assertTrue("hit rate $rate below 0.5; misses=$misses", rate >= 0.5)
        // The tuned hybrid is expected to hit every pair; keep the stricter
        // assertion so a lexicon regression is visible immediately.
        assertEquals("misses=$misses", pairs.size, hits)
    }

    @Test
    fun `unrelated tasks stay below the hybrid threshold`() {
        val negatives = listOf(
            "打开手电筒" to "查一下电池电量",
            "清理一下没用的缓存" to "把屏幕亮度调低",
            "帮我把屏幕截个图" to "现在连着哪个WiFi",
            "手机还剩多少电" to "打开地图导航去公司",
            "看一下今天的天气" to "把音量调到一半",
            "查询一下 App 私有目录的占用构成" to "帮我把屏幕截个图",
            // Near-neighbour traps: a shared "清理" verb is not the same task.
            "清理缓存" to "清理聊天记录",
        )
        for ((a, b) in negatives) {
            val score = SkillMatcher.score(a, record(b), "com.openminis.app.dev")
            assertTrue("unrelated pair scored $score: $a / $b", score < SkillMatcher.DEFAULT_HYBRID_THRESHOLD)
        }
    }

    @Test
    fun `a shared generic verb alone is not a task match`() {
        // The 2026-11-15 field false positive: the only shared concept is
        // `search` ("看看"), which the containment formula used to score 1.0.
        val noise = record("你看看这此快捷工具使用有什么问题")
        val score = SkillMatcher.score("看看私有目录都被什么占满了", noise, "com.openminis.app.dev")
        assertTrue(
            "generic overlap scored $score (concepts=${SkillMatcher.concepts("看看私有目录都被什么占满了")} / " +
                "${SkillMatcher.concepts(noise.goal)})",
            score < SkillMatcher.DEFAULT_HYBRID_THRESHOLD,
        )
    }

    @Test
    fun `legacy algorithm remains available and behaves as before`() {
        val original = record("打开手电筒")
        assertEquals(1.0, SkillMatcher.legacyScore("打开手电筒", original, "com.openminis.app.dev"), 1e-9)
        // The paraphrase that hybrid matches shares no bigram with the goal.
        val legacy = SkillMatcher.legacyScore("把闪光灯打开", original, "com.openminis.app.dev")
        assertTrue("legacy unexpectedly matched: $legacy", legacy < SkillMatcher.THRESHOLD)
        val legacyBest = SkillMatcher.best(
            "把闪光灯打开", listOf(original), "com.openminis.app.dev",
            SkillMatcher.MatchConfig(SkillMatcher.THRESHOLD, SkillMatcher.Algorithm.LEGACY),
        )
        assertNull(legacyBest)
    }

    @Test
    fun `threshold is adjustable`() {
        val records = listOf(record("看看手机存储都被什么占满了"))
        val query = "查询一下 App 私有目录的占用构成"
        assertNotNull(
            SkillMatcher.best(
                query, records, "com.openminis.app.dev",
                SkillMatcher.MatchConfig(0.30, SkillMatcher.Algorithm.HYBRID),
            ),
        )
        assertNull(
            SkillMatcher.best(
                query, records, "com.openminis.app.dev",
                SkillMatcher.MatchConfig(0.95, SkillMatcher.Algorithm.HYBRID),
            ),
        )
    }

    @Test
    fun `same app is weighted above a different app`() {
        val rec = record("打开手电筒", app = "com.example.other")
        val same = SkillMatcher.score("把闪光灯打开", rec, "com.example.other")
        val cross = SkillMatcher.score("把闪光灯打开", rec, "com.openminis.app.dev")
        assertTrue("same=$same cross=$cross", same > cross)
        val unknown = SkillMatcher.score("把闪光灯打开", record("打开手电筒", app = null), "com.openminis.app.dev")
        assertTrue("unknown app id should stay usable: $unknown", unknown > 0.4)
    }
}

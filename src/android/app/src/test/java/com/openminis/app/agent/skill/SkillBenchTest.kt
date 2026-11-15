package com.openminis.app.agent.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Acceptance A at the unit level: store-mode ground truth must distinguish
 * "the matcher returned the expected record" from "some other record crossed
 * the threshold". The old bench counted the latter as a hit.
 */
class SkillBenchTest {

    private fun record(
        id: String,
        goal: String,
        steps: List<SkillStep> = completeSteps(),
        conclusion: String? = "已确认。",
    ): SkillRecord = SkillRecord(
        id = id,
        goal = goal,
        appPackage = "com.openminis.app.dev",
        keywords = SkillMatcher.keywords(goal),
        steps = steps,
        toolChain = steps.map { it.tool },
        createdAt = 0L,
        lastUsedAt = 0L,
        conclusion = conclusion,
    )

    private fun completeSteps(): List<SkillStep> = listOf(
        SkillStep(
            tool = "shell_execute",
            target = "cmd",
            summary = "执行命令：cmd",
            input = "cmd",
            output = "ok",
        ),
    )

    @Test
    fun `store bench counts only the expected record as a hit`() {
        val correct = record("correct", "打开手电筒")
        val other = record("other", "查询电池电量")
        val dirty = record("dirty", "手电筒")
        val incomplete = record(
            id = "incomplete",
            goal = "截屏保存一下",
            steps = completeSteps().map { it.copy(output = null) },
            conclusion = null,
        )
        val records = listOf(correct, other, dirty, incomplete)
        val pairs = listOf(
            SkillBench.Pair(correct.goal, "把闪光灯打开", "correct"),
            // Expected another record: the match is real but wrong. The old
            // bench would have called this a hit.
            SkillBench.Pair(correct.goal, "把闪光灯打开", "other"),
            // Expected record is dirty and excluded from the matching pool.
            SkillBench.Pair(dirty.goal, "手电筒", "dirty"),
            // Expected record does not exist at all.
            SkillBench.Pair("不存在的任务", "查一下电池电量", "deadbeef"),
            // Expected record is readable but cannot be injected.
            SkillBench.Pair(incomplete.goal, "截屏保存一下", "incomplete"),
        )

        val result = SkillBench.evaluateStore(
            records = records,
            pairs = pairs,
            appPackage = "com.openminis.app.dev",
            config = SkillMatcher.MatchConfig(),
        )

        assertEquals(5, result.rows.size)
        assertEquals(4, result.recordCount)
        assertEquals(3, result.matchingPoolCount) // dirty goal excluded
        assertEquals(2, result.eligibleCount)
        assertEquals(1, result.expectedMissing)
        assertEquals(1, result.expectedDirty)
        assertEquals(1, result.expectedIncomplete)
        assertEquals(3, result.evaluatedPairs) // eligible + incomplete
        assertEquals(2, result.injectablePairs)
        assertEquals(1, result.injectableHits)
        assertEquals(2, result.correctHits)
        assertEquals(3, result.wrongMatches)
        assertEquals(0, result.noMatches)
        assertEquals(2.0 / 3.0, result.hitRate, 1e-9)
        assertEquals(0.5, result.injectableHitRate, 1e-9)
        assertEquals(2.0 / 5.0, result.hitRateAll(5), 1e-9)

        assertEquals("correct", result.rows[0].outcome)
        assertTrue(result.rows[0].sameRecord)
        assertEquals("correct", result.rows[0].matchedId)
        assertEquals("wrong-record", result.rows[1].outcome)
        assertFalse(result.rows[1].sameRecord)
        assertTrue(result.rows[1].falseHit)
        assertEquals("expected-dirty", result.rows[2].outcome)
        assertEquals("expected-missing", result.rows[3].outcome)
        assertFalse(result.rows[3].sameRecord)
        assertTrue(result.rows[3].falseHit)
        assertEquals("correct", result.rows[4].outcome)
        assertEquals("incomplete", result.rows[4].expectedStatus)
        assertEquals(listOf("keyOutput", "conclusion"), result.rows[4].matchedMissing)
    }

    @Test
    fun `expected record is resolved by id then by normalized goal`() {
        val goal = "打开手电筒"
        val byId = record(id = SkillStore.idForGoal(goal), goal = goal)
        val explicit = SkillBench.Pair(goal, "把闪光灯打开", byId.id)
        val derived = SkillBench.Pair(goal, "把闪光灯打开", null)
        assertEquals(byId.id, SkillBench.findExpected(listOf(byId), explicit)?.id)
        assertEquals(byId.id, SkillBench.findExpected(listOf(byId), derived)?.id)

        // A record written before goal normalization has a different id; the
        // normalized goal equality fallback must still find it.
        val legacy = byId.copy(id = "legacy-id", goal = "嗯，打开手电筒")
        val resolved = SkillBench.findExpected(listOf(legacy), SkillBench.Pair("打开手电筒", "开灯", null))
        assertNotNull(resolved)
        assertEquals("legacy-id", resolved!!.id)
    }
}

package com.openminis.app.assistant.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-local-volume] 音量是确定性端侧能力：明确指令命中本地路由并直接执行，
 * 不弹确认卡；单位不对、只是讨论、或打开设置页的句子必须留在原来的路径。
 */
class VolumeRouteTest {

    private val router = AssistantRouter()

    private fun route(text: String): RouteDecision =
        router.route(text, DefaultAssistantRoutes.all)

    private fun local(text: String): RouteDecision.Local =
        route(text) as? RouteDecision.Local
            ?: error("expected a local route for: $text")

    @Test
    fun absoluteChinesePercentSetsExactLevel() {
        val decision = local("音量调到百分之六十")
        assertEquals("volume", decision.capability)
        assertEquals("set", decision.action)
        assertEquals(60, decision.args.optInt("percent"))
        assertFalse(decision.needsConfirmation)
    }

    @Test
    fun absoluteDigitPercentSetsExactLevel() {
        assertEquals(60, local("把音量调到 60%").args.optInt("percent"))
        assertEquals(60, local("音量 60%").args.optInt("percent"))
        assertEquals(60, local("音量调到 60").args.optInt("percent"))
    }

    @Test
    fun ringStreamIsAnOptionalSlot() {
        val decision = local("把铃声调到百分之五十")
        assertEquals("volume", decision.capability)
        assertEquals("set", decision.action)
        assertEquals(50, decision.args.optInt("percent"))
        assertEquals("ring", decision.args.optString("stream"))
    }

    @Test
    fun relativeStepAddsTenPercent() {
        val decision = local("音量调大一点")
        assertEquals("volume", decision.capability)
        assertEquals("adjust", decision.action)
        assertEquals(10, decision.args.optInt("percent"))
        assertFalse(decision.needsConfirmation)
    }

    @Test
    fun relativeExplicitPercentUsesTheNumber() {
        assertEquals(20, local("音量调大 20%").args.optInt("percent"))
        assertEquals(-20, local("音量调小 20%").args.optInt("percent"))
        assertEquals(40, local("音量调大很多").args.optInt("percent"))
    }

    @Test
    fun maximumAndMinimumAreAbsolute() {
        val max = local("音量调到最大")
        assertEquals("set", max.action)
        assertEquals(100, max.args.optInt("percent"))
        val min = local("音量调到最小")
        assertEquals("set", min.action)
        assertEquals(0, min.args.optInt("percent"))
    }

    @Test
    fun statusDoesNotChangeTheVolume() {
        val decision = local("现在音量多少")
        assertEquals("status", decision.action)
        assertFalse(decision.needsConfirmation)
    }

    @Test
    fun wrongUnitDoesNotHitTheVolumeRoute() {
        assertTrue(route("把音量调到 60 分") is RouteDecision.Cloud)
    }

    @Test
    fun discussionStaysInTheCloud() {
        assertTrue(route("我们在讨论音量接口的方案") is RouteDecision.Cloud)
    }

    @Test
    fun settingsPageStillOpensSettings() {
        val decision = route("打开声音设置") as RouteDecision.Local
        assertEquals("settings", decision.capability)
        assertEquals("open", decision.action)
    }

    @Test
    fun registrySupportsVolumeActions() {
        assertTrue(LocalCapabilityRegistry.supports("volume", "set"))
        assertTrue(LocalCapabilityRegistry.supports("volume", "adjust"))
        assertTrue(LocalCapabilityRegistry.supports("volume", "status"))
        assertTrue(LocalCapabilityRegistry.isReadOnly("volume", "status"))
        assertFalse(LocalCapabilityRegistry.isHighRisk("volume", "set"))
    }
}

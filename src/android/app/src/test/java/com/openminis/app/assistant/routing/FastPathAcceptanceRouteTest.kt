package com.openminis.app.assistant.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-fastpath-weather-ms] 验收句的端侧路由回归。
 *
 * 对应任务 m-20261128-107 的验收标准 1~3：日常指令必须端侧命中并带着
 * 完整参数直接执行；闲聊不能命中任何设备动作。
 */
class FastPathAcceptanceRouteTest {

    private val router = AssistantRouter()

    private fun route(text: String): RouteDecision = router.route(text, DefaultAssistantRoutes.all)

    private fun local(text: String): RouteDecision.Local =
        route(text) as? RouteDecision.Local ?: error("expected a local route for: $text")

    @Test
    fun weatherSentenceRoutesLocally() {
        val decision = local("今天天气怎么样")
        assertEquals("weather", decision.capability)
        assertEquals("current", decision.action)
        assertFalse(decision.needsModelExtraction)
    }

    @Test
    fun volumeSetWithDigitsRoutesLocally() {
        val decision = local("把音量调到 30")
        assertEquals("volume", decision.capability)
        assertEquals("set", decision.action)
        assertEquals(30, decision.args.optInt("percent", -1))
        assertFalse(decision.needsConfirmation)
        assertFalse(decision.needsModelExtraction)
    }

    @Test
    fun batteryQuestionRoutesLocally() {
        val decision = local("还有多少电")
        assertEquals("device", decision.capability)
        assertEquals("battery", decision.action)
        assertFalse(decision.needsModelExtraction)
    }

    @Test
    fun openAppRoutesLocallyWithTheAppName() {
        val decision = local("打开微信")
        assertEquals("app_launch", decision.capability)
        assertEquals("open", decision.action)
        assertEquals("微信", decision.args.optString("app"))
        assertFalse(decision.needsModelExtraction)
    }

    @Test
    fun casualStatementIsNotADeviceAction() {
        listOf("还可以总体效果不错", "好了", "咱们聊聊天呗").forEach { text ->
            assertTrue("must stay on the cloud path: $text", route(text) is RouteDecision.Cloud)
        }
    }
}

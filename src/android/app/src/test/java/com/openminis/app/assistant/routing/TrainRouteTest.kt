package com.openminis.app.assistant.routing

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-train-quote] 12306 余票是一条确定性只读能力：一句「明天北京南
 * 到上海虹桥的车票」应当直接抽槽走端侧，而讨论「加个车票功能」必须留在云端。
 */
class TrainRouteTest {

    private val now = LocalDateTime.of(2026, 11, 15, 14, 0)
    private val router = AssistantRouter(SlotExtractor({ now }, ZoneId.of("Asia/Shanghai")))

    private fun route(text: String): RouteDecision =
        router.route(text, DefaultAssistantRoutes.all)

    @Test
    fun routesATicketQueryLocally() {
        val decision = route("明天北京南到上海虹桥的车票") as RouteDecision.Local
        assertEquals("train_12306", decision.capability)
        assertEquals("query", decision.action)
        assertEquals("北京南", decision.args.optString("from"))
        assertEquals("上海虹桥", decision.args.optString("to"))
        assertEquals("2026-11-16", decision.args.optString("date"))
        assertFalse(decision.needsConfirmation)
    }

    @Test
    fun extractsDepartureWindowAndAvailabilityFilter() {
        val decision = route("查一下明天08:00-10:00从张掖西到兰州西的高铁票，只看有票") as RouteDecision.Local
        assertEquals("train_12306", decision.capability)
        assertEquals("张掖西", decision.args.optString("from"))
        assertEquals("兰州西", decision.args.optString("to"))
        assertEquals("08:00-10:00", decision.args.optString("time_range"))
        assertTrue(decision.args.optBoolean("only_available"))
    }

    @Test
    fun discussingTheTicketFeatureStaysInTheCloud() {
        assertTrue(route("我们可以加个车票功能") is RouteDecision.Cloud)
    }

    @Test
    fun purchaseProcessQuestionsStayInTheCloud() {
        assertTrue(route("火车票退票改签的流程是什么") is RouteDecision.Cloud)
        assertTrue(route("12306 怎么买票") is RouteDecision.Cloud)
    }
}

package com.openminis.app.assistant.routing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-fastpath-topic-guard] 长对话里提到「天气 / 新闻 / 搜索 / 股票」不等于
 * 要端侧执行。只有命中落在句首附近、且句子在目标词之后就结束（或紧跟
 * 疑问/请求尾巴）的句子才是指令。
 */
class AssistantRouteTopicMentionTest {

    private val router = AssistantRouter()
    private val routes = DefaultAssistantRoutes.all

    private fun assertCloud(text: String) {
        val decision = router.route(text, routes)
        assertTrue("$text should stay in the cloud, got $decision", decision is RouteDecision.Cloud)
    }

    private fun assertLocal(text: String, capability: String) {
        val decision = router.route(text, routes)
        assertTrue(
            "$text should run locally as $capability, got $decision",
            decision is RouteDecision.Local && decision.capability == capability,
        )
    }

    @Test
    fun weatherMentionsStayInTheCloud() {
        assertCloud("今天天气不错，我们下午去公园走走吧")
        assertCloud("明天可能会下雨，我打算去露营，你帮我看看要准备什么装备")
        assertCloud("今天外面天气真好，适合晒被子，你觉得呢")
        assertCloud("上周天气冷得要命，我新买的羽绒服倒是挺暖和")
    }

    @Test
    fun newsMentionsStayInTheCloud() {
        assertCloud("帮我看看这段新闻文案怎么写")
        assertCloud("说说新闻推送的产品设计")
        assertCloud("新闻推送的点击率下降了，需要排查一下原因")
        assertCloud("我看了下新闻详情页的设计，觉得标题字号偏大了一些")
    }

    @Test
    fun webSearchMentionsStayInTheCloud() {
        assertCloud("刚才你帮我搜一下的那个网页搜索 API 文档我看了")
        assertCloud("我昨天用网页搜索查了资料，感觉搜索结果质量一般")
        assertCloud("我在写一段关于网页搜索体验的文档，需要你帮我梳理结构")
    }

    @Test
    fun stockMentionsStayInTheCloud() {
        assertCloud("A股市场最近波动大，我在想仓位要不要调整")
        assertCloud("我们讨论一下股票行情页的设计")
    }

    @Test
    fun realReadOnlyCommandsStillRunLocally() {
        assertLocal("今天天气怎么样", "weather")
        assertLocal("明天北京天气怎么样，需要带伞吗", "weather")
        assertLocal("明天会下雨吗", "weather")
        assertLocal("今天有什么科技新闻", "news_search")
        assertLocal("帮我看看财经新闻", "news_search")
        assertLocal("最近有什么国际新闻", "news_search")
        assertLocal("帮我搜一下 Kotlin 协程教程", "web_search")
        assertLocal("今天大盘怎么样", "stock_quote")
        assertLocal("深证成指涨了还是跌了", "stock_quote")
        assertLocal("battery level", "device")
        assertLocal("free time tomorrow", "calendar")
        assertLocal("待触发提醒", "reminder")
    }

    @Test
    fun multiLineMessagesStayInTheCloud() {
        assertCloud("帮我看看新闻\n另外天气怎么样")
    }

    @Test
    fun version15ReplacesRetiredBuiltinPatterns() {
        val retiredWeather = """(?:今天|明天|后天|现在|这几天|周末).{0,6}(?:天气|下雨|温度|气温)"""
        val retiredNews =
            """(?:看看|看一下|查一下|搜一下|来点|说说|讲讲|播报).{0,6}(?:新闻|资讯|热点|头条)"""
        val userPattern = """^自定义模式$"""

        val loaded = routes.map { route ->
            when (route.id) {
                "life.weather" -> route.copy(patterns = route.patterns + retiredWeather + userPattern)
                "info.news" -> route.copy(patterns = route.patterns + retiredNews)
                else -> route
            }
        }
        val migrated = DefaultAssistantRoutes.migrate(loaded, 14)
        val weather = migrated.first { it.id == "life.weather" }
        assertFalse(weather.patterns.contains(retiredWeather))
        assertTrue(weather.patterns.contains(userPattern))
        assertTrue(weather.patterns.size <= AssistantRouteStore.MAX_PATTERNS)
        assertFalse(migrated.first { it.id == "info.news" }.patterns.contains(retiredNews))
        assertTrue(
            migrated.first { it.id == "info.websearch" }.patterns.none {
                it == """(?:搜|搜索)\s*(?:网页|网页结果|网上)"""
            },
        )
    }
}
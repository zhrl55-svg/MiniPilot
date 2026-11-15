package com.openminis.app.assistant.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-brave-web-search] Web search only answers "search the web"; it
 * must not steal commands that belong to the browser or the news route.
 */
class WebSearchRouteTest {

    private val router = AssistantRouter()

    private fun route(text: String): RouteDecision =
        router.route(text, DefaultAssistantRoutes.all)

    @Test
    fun routesAWebSearchLocally() {
        val decision = route("帮我搜一下 Kotlin 协程教程") as RouteDecision.Local
        assertEquals("info.websearch", decision.route.id)
        assertEquals("web_search", decision.capability)
        assertEquals("search", decision.action)
    }

    /**
     * 「运行 grep 搜索文件」是终端命令：句子里有「搜索」两个字也不能被
     * 网页搜索抢走。
     */
    @Test
    fun shellCommandsAreNotWebSearches() {
        val decision = route("运行 grep 搜索文件")
        assertFalse(
            "shell command must not route to web search",
            decision is RouteDecision.Local && decision.capability == "web_search",
        )
    }

    @Test
    fun openingAUrlStaysWithTheBrowser() {
        val decision = route("打开网页 https://example.com") as RouteDecision.Local
        assertEquals("browser_use", decision.capability)
        assertEquals("navigate", decision.action)
    }

    @Test
    fun newsQuestionsStayWithTheNewsRoute() {
        val decision = route("今天有什么科技新闻") as RouteDecision.Local
        assertEquals("news_search", decision.capability)
    }

    @Test
    fun discussingTheFeatureStaysInTheCloud() {
        assertTrue(route("我们可以加个网页搜索功能") is RouteDecision.Cloud)
    }
}

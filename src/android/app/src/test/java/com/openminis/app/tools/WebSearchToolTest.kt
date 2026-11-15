package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-brave-web-search] The missing-key path is a plain result (no
 * network), and Brave's response shape is parsed by pure functions.
 */
class WebSearchToolTest {

    private val fixture = """
        {
          "web": {
            "results": [
              {
                "title": "Kotlin <strong>协程</strong> 教程",
                "url": "https://example.com/kotlin",
                "description": "介绍 <strong>协程</strong> 的用法 &amp; 示例"
              },
              {
                "title": "Brave Search API",
                "url": "https://api.search.brave.com/",
                "description": "Official docs"
              },
              {
                "title": "",
                "url": "https://example.com/empty-title"
              }
            ]
          }
        }
    """.trimIndent()

    @Test
    fun `blank key returns the configuration error before any network call`() {
        val result = WebSearchTool.executeWithKey("", """{"query":"Kotlin"}""")
        assertFalse(result.success)
        assertEquals(WebSearchTool.NO_KEY_MESSAGE, result.output)
        assertTrue(result.output.contains("Brave Search API Key"))
    }

    @Test
    fun `parser strips highlight tags and entities`() {
        val results = WebSearchTool.parseResults(fixture)
        assertEquals(2, results.size)
        assertEquals("Kotlin 协程 教程", results[0].title)
        assertEquals("https://example.com/kotlin", results[0].url)
        assertEquals("介绍 协程 的用法 & 示例", results[0].description)
    }

    @Test
    fun `formatter renders a Chinese result list`() {
        val text = WebSearchTool.formatResults("Kotlin 协程", WebSearchTool.parseResults(fixture))
        assertTrue(text.startsWith("网页搜索：Kotlin 协程（2 条）"))
        assertTrue(text.contains("1. Kotlin 协程 教程"))
        assertTrue(text.contains("   https://example.com/kotlin"))
        assertTrue(text.contains("   介绍 协程 的用法 & 示例"))
        assertTrue(text.contains("2. Brave Search API"))
        assertTrue(text.endsWith("结果由 Brave Search 提供。"))
    }

    @Test
    fun `parser keeps at most five results`() {
        val rows = (1..8).joinToString(",") { index ->
            """{"title":"结果 $index","url":"https://example.com/$index","description":""}"""
        }
        val body = """{"web":{"results":[$rows]}}"""
        assertEquals(5, WebSearchTool.parseResults(body).size)
    }

    @Test
    fun `query normalization removes command words`() {
        assertEquals("Kotlin 协程", WebSearchTool.normalizeQuery("帮我上网搜一下 Kotlin 协程"))
        assertEquals("Brave Search API", WebSearchTool.normalizeQuery("查资料 Brave Search API"))
    }
}

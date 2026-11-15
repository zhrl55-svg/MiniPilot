package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.logging.AppLogger
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * [T-android-brave-web-search] General-purpose web search through the Brave
 * Search API. Unlike `news_search` (key-free RSS, headlines only) this returns
 * ordinary web results, so it needs a user-supplied API key; without one the
 * tool tells the user where to configure it instead of failing silently.
 *
 * Read-only and bounded: at most 5 results, description text only — opening a
 * page is still `browser_use`'s job.
 */
object WebSearchTool {

    const val NAME = "web_search"

    private const val TAG = "WebSearchTool"
    private const val BASE = "https://api.search.brave.com/res/v1/web/search"
    private const val USER_AGENT = "Minis/1.0 (Android; web search)"
    private const val MAX_RESULTS = 5

    const val NO_KEY_MESSAGE =
        "网页搜索未配置 API Key，请在设置里填写 Brave Search API Key。"

    private val client = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(9, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Search the open web with Brave Search and return up to 5 results " +
            "(title, URL, snippet). Use for \"上网查一下…\", \"帮我搜一下…\", documentation, " +
            "tutorials and official sites. Requires a Brave Search API key configured in " +
            "Settings. For latest news prefer news_search; to open or read a specific URL " +
            "use browser_use.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user. Use the same language as the user."),
            "query" to AgentToolParam("string", "What to search for, e.g. \"Kotlin 协程 教程\", \"Brave Search API 文档\"."),
        ),
        required = listOf("tool_title", "query"),
        propertyOrdering = listOf("tool_title", "query"),
    )

    // ---------------------------------------------------------------- exec

    /** Blocking call — the caller runs it on an IO dispatcher. */
    fun execute(context: Context, argsJson: String): ToolExecutionResult =
        executeWithKey(WebSearchSettings.apiKey(context), argsJson)

    /**
     * Key-injected entry point: a blank key is the configuration-error path
     * the unit test drives without touching the network.
     */
    internal fun executeWithKey(key: String, argsJson: String): ToolExecutionResult {
        if (key.isBlank()) {
            return ToolExecutionResult(NO_KEY_MESSAGE, false)
        }
        val args = runCatching { JSONObject(argsJson) }.getOrNull()
            ?: return ToolExecutionResult("参数不是合法 JSON", false)
        val query = normalizeQuery(args.optString("query"))
        if (query.isBlank()) return ToolExecutionResult("请补充要搜索的内容。", false)

        val url = "$BASE?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8.name()) +
            "&count=$MAX_RESULTS&country=CN&search_lang=zh-hans&extra_snippets=false"
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .header("X-Subscription-Token", key)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    AppLogger.warning(TAG, "HTTP ${response.code}")
                    ToolExecutionResult(httpErrorMessage(response.code), false)
                } else {
                    val results = parseResults(response.body?.string().orEmpty())
                    if (results.isEmpty()) {
                        val message = "没有找到“$query”的网页结果。可以换个关键词再搜。"
                        ToolExecutionResult(message, true, spokenSummary = message)
                    } else {
                        ToolExecutionResult(
                            formatResults(query, results),
                            true,
                            spokenSummary = spokenSummary(results),
                        )
                    }
                }
            }
        }.getOrElse {
            AppLogger.warning(TAG, "search failed: ${it.javaClass.simpleName}")
            ToolExecutionResult("网页搜索失败（网络或超时），请检查网络后重试。", false)
        }
    }

    private fun httpErrorMessage(code: Int): String = when (code) {
        401, 403 -> "网页搜索密钥无效或没有权限（HTTP $code），请在设置里检查 Brave Search API Key。"
        429 -> "网页搜索额度已用完或请求过于频繁（HTTP 429），请稍后再试。"
        else -> "网页搜索暂时不可用（HTTP $code），请稍后重试。"
    }

    // ------------------------------------------------------------- query

    /** Strip the command words so "帮我上网搜一下 Kotlin 教程" searches "Kotlin 教程". */
    internal fun normalizeQuery(raw: String): String {
        var q = raw.trim()
        var changed = true
        while (changed) {
            changed = false
            for (prefix in QUERY_PREFIXES) {
                if (q.startsWith(prefix, ignoreCase = true)) {
                    q = q.substring(prefix.length).trim()
                    changed = true
                    break
                }
            }
        }
        q = q.trim(' ', '，', ',', '。', '.', '？', '?', '！', '!', '：', ':', '；', ';')
        q = q.removeSuffix("吧").removeSuffix("吗").removeSuffix("呢").trim()
        return q
    }

    // -------------------------------------------------------------- parse

    internal data class WebResult(
        val title: String,
        val url: String,
        val description: String,
    )

    /** Parse `web.results[]`; malformed items are skipped, never fatal. */
    internal fun parseResults(json: String): List<WebResult> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val array = root.optJSONObject("web")?.optJSONArray("results") ?: return emptyList()
        val out = ArrayList<WebResult>(MAX_RESULTS)
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val title = plainText(item.optString("title")).take(160)
            val url = item.optString("url").trim()
            if (title.isBlank() || url.isBlank()) continue
            out += WebResult(
                title = title,
                url = url,
                description = plainText(item.optString("description")).take(300),
            )
            if (out.size >= MAX_RESULTS) break
        }
        return out
    }

    private val ENTITIES = mapOf(
        "&amp;" to "&",
        "&lt;" to "<",
        "&gt;" to ">",
        "&quot;" to "\"",
        "&#39;" to "'",
        "&#x27;" to "'",
        "&nbsp;" to " ",
    )

    /** Brave highlights matches with `<strong>`; snippets are shown as text. */
    private fun plainText(raw: String): String {
        if (raw.isBlank()) return ""
        var text = raw.replace(Regex("(?is)<[^>]+>"), " ")
        for ((entity, replacement) in ENTITIES) {
            text = text.replace(entity, replacement, ignoreCase = true)
        }
        return text.replace(Regex("\\s+"), " ").trim()
    }

    // ------------------------------------------------------------- output

    internal fun formatResults(query: String, results: List<WebResult>): String = buildString {
        append("网页搜索：").append(query).append("（").append(results.size).append(" 条）")
        results.forEachIndexed { index, result ->
            append("\n\n").append(index + 1).append(". ").append(result.title)
            append("\n   ").append(result.url)
            if (result.description.isNotBlank()) {
                append("\n   ").append(result.description)
            }
        }
        append("\n\n结果由 Brave Search 提供。")
    }

    private fun spokenSummary(results: List<WebResult>): String {
        if (results.isEmpty()) return ""
        return "找到 ${results.size} 条网页结果。第一条，${results.first().title.take(40)}。"
    }

    private val QUERY_PREFIXES = listOf(
        "请帮我", "帮我搜索", "帮我搜一下", "帮我查一下", "帮我看看", "帮我搜", "帮我查", "帮我",
        "给我搜", "给我查", "给我",
        "请", "麻烦",
        "在网上", "上网", "网上", "网页",
        "搜索网页", "网页搜索", "搜索一下", "搜一下", "搜一搜", "搜索", "搜",
        "查资料", "查一下", "查查",
        "找一下", "找找",
    ).sortedByDescending { it.length }
}

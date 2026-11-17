package com.openminis.app.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * 高德地点查询（Web API 通道）。
 *
 * ## 为什么是 Web API 而不是驱动高德 App
 * 实测（2026-11-12/13）：
 *  - 高德搜索页是**自绘**的（`ajx3.widget`），a11y 读不到任何文字（74 节点全空），
 *    只能盲点坐标。
 *  - URI 通道有**反滥用限流**：连续调会弹「访问被拒绝 / 你的操作过于频繁」。
 *  - uiautomator 报的 bounds 与实际点击位置**差 57px**（状态栏偏移），点击常落空。
 * 走 API 一个坑都没有，而且返回结构化数据。
 *
 * ## 两个必须记住的坑（pilot 已在生产验证）
 * 1. **city 参数在 SDK 通道不生效**，Web 通道才生效（这里用 `citylimit=false` 当偏置）。
 * 2. **距离安全阀**：高德会返回 1500 公里外的同名 POI。超过 [MAX_SANE_DISTANCE_M]
 *    的候选直接丢；全被丢掉时报「附近没有这个地点」，而不是把用户指到天边。
 *    （pilot 的真实教训：搜「水勘院超市」命中石家庄，一路 1517 公里。）
 *
 * ## key
 * 高德 Web Key 从两个位置找（先到先得）：
 *  1. `getExternalFilesDir(null)/amap_key.txt` —— App 私有外，adb 可推（部署脚本写这里）
 *  2. `filesDir/shared/amap-nav/amap_key.txt` —— 沙箱里的那份（`/var/minis` 的直译）
 *
 * key 是敏感值：本工具只读、不打印、不外传。
 */
object AmapSearchTool {

    const val NAME = "amap_search"

    /** 超过这个距离的候选直接丢（防止 city 偏置失效导致指到天边）。 */
    private const val MAX_SANE_DISTANCE_M = 50_000

    private const val BASE = "https://restapi.amap.com/v3/place"
    private const val KEY_FILE_NAME = "amap_key.txt"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Search places near the device with AMap (Gaode). " +
            "Use mode=\"near\" (default) for \"around me\" queries and mode=\"poi\" for " +
            "city-wide search by name. Returns names, addresses and distance in meters.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user. Use the same language as the user."),
            "query" to AgentToolParam("string", "What to search for, e.g. \"超市\", \"咖啡\", \"加油站\". Use the word the user used. Colloquial food questions such as \"附近有什么好吃的\" are expanded into restaurant keywords automatically."),
            "mode" to AgentToolParam(
                "string",
                "\"near\" (default) — search around the device's current location; " +
                    "\"poi\" — search the whole city by name.",
                enumValues = listOf("near", "poi"),
            ),
            "radius" to AgentToolParam("integer", "Search radius in meters for mode=\"near\" (default 3000, max 50000)."),
            "city" to AgentToolParam("string", "City name for mode=\"poi\" (e.g. \"张掖\"). Only used as a bias, never a hard filter."),
        ),
        required = listOf("tool_title", "query"),
        propertyOrdering = listOf("tool_title", "query", "mode", "radius", "city"),
    )

    // ------------------------------------------------------------------ key

    /** 找 Web Key。找不到返回空串。 */
    fun readKey(context: Context): String {
        val candidates = listOfNotNull(
            context.getExternalFilesDir(null)?.let { File(it, KEY_FILE_NAME) },
            File(context.filesDir, "shared/amap-nav/$KEY_FILE_NAME"),
        )
        for (f in candidates) {
            runCatching {
                if (f.isFile) {
                    val t = f.readText().trim()
                    if (t.isNotEmpty()) return t
                }
            }
        }
        return ""
    }

    // ------------------------------------------------------------- location

    /**
     * 设备当前定位。优先 GPS、网络次之。
     *
     * 拿不到就返回 null —— 上层会退化成「按城市搜」而不是编一个坐标。
     */
    fun currentLocation(context: Context): Location? {
        val granted = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = try {
            lm.getProviders(true)
        } catch (_: SecurityException) {
            emptyList<String>()
        }
        var best: Location? = null
        for (p in providers) {
            val loc = try {
                @Suppress("MissingPermission")
                lm.getLastKnownLocation(p)
            } catch (_: SecurityException) {
                null
            } ?: continue
            if (best == null || loc.time > best.time) best = loc
        }
        return best
    }

    // ----------------------------------------------------------------- http

    private fun httpJson(url: String, timeoutMs: Int = 12_000): JSONObject? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", "Minis/amap-web/1.0")
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (text.isBlank()) null else JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun haversineM(lon1: Double, lat1: Double, lon2: Double, lat2: Double): Double {
        val r = 6371000.0
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = Math.toRadians(lat2 - lat1)
        val dl = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dp / 2) * Math.sin(dp / 2) +
            Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2)
        return 2 * r * Math.asin(Math.sqrt(a))
    }

    // ---------------------------------------------------------------- exec

    /**
     * 单个关键词的高德查询结果。
     *
     * @param pois 命中列表；null 表示这一次请求失败了。
     * @param error 失败原因（网络超时 / 接口 status 不为 1）。
     */
    private data class SearchOutcome(val pois: JSONArray?, val error: String?)

    /**
     * 打一个关键词。
     *
     * around 还是 text 由有没有定位决定，和之前一致。
     */
    private fun searchPois(
        key: String,
        keyword: String,
        loc: Location?,
        radius: Int,
        city: String,
    ): SearchOutcome {
        val url = if (loc != null) {
            "$BASE/around?key=$key&keywords=${enc(keyword)}" +
                "&location=${loc.longitude},${loc.latitude}&radius=$radius" +
                "&sortrule=distance&offset=10"
        } else {
            val cityPart = if (city.isEmpty()) "" else "&city=${enc(city)}"
            "$BASE/text?key=$key&keywords=${enc(keyword)}$cityPart&citylimit=false&offset=10"
        }
        val body = httpJson(url) ?: return SearchOutcome(null, "网络或超时")
        if (body.optString("status") != "1") {
            return SearchOutcome(
                null,
                "status=${body.optString("status")} info=${body.optString("info")}",
            )
        }
        return SearchOutcome(body.optJSONArray("pois") ?: JSONArray(), null)
    }

    /**
     * 执行查询。
     *
     * 返回给人看的文本（不是 JSON）—— 这是给用户看的工具结果，
     * 不是给模型再解析的中间产物。
     */
    fun execute(context: Context, argsJson: String): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrNull()
            ?: return ToolExecutionResult("参数不是合法 JSON", false)
        val query = args.optString("query").trim()
        if (query.isEmpty()) return ToolExecutionResult("缺少 query", false)

        val key = readKey(context)
        if (key.isEmpty()) {
            return ToolExecutionResult(
                "高德 Web Key 未配置（缺少 amap_key.txt），地点查询不可用。",
                false,
            )
        }

        val mode = args.optString("mode", "near").trim().lowercase().ifEmpty { "near" }
        val radius = args.optInt("radius", 3000).coerceIn(100, 50_000)
        val city = args.optString("city").trim()

        val loc = if (mode == "near") currentLocation(context) else null
        if (mode == "near" && loc == null) {
            return ToolExecutionResult(
                "拿不到设备定位，无法做周边搜索。可以改用「在${city.ifBlank { "当前城市" }}找…」这种城市级查询。",
                false,
            )
        }

        // [T-amap-colloquial] 口语归一化 + 泛指展开。
        //
        // 「附近有什么好吃的」原样丢给高德是 0 结果：它只认 POI 关键词，
        // 「有什么」「好吃」都不是地点。这里把它们剥掉，并把「好吃」展开成
        // 餐厅/饭店/小吃并行去搜；修饰词（面馆、火锅）留在最前面。
        //
        // 端侧（本地路由抽槽）和云端（模型调用工具）走的是同一个工具，
        // 所以同一句话在两端的结果一致。
        val plan = AmapPlaceQuery.plan(query)
        if (plan.keywords.isEmpty()) {
            // 剥完填充词剩下的是一整句话（或者什么都不剩）：这不是地点关键词。
            // 明确说清楚，别拿它去搜出一句「附近没有找到」—— 那会让用户以为
            // 附近真的没有，而其实是整句话被当成了关键词。
            return ToolExecutionResult(
                "「${query.take(40)}」不像一个能搜的地点关键词。" +
                    "换个说法试试：直接说地点或品类，例如「附近的加油站」「附近有什么好吃的」。",
                false,
            )
        }
        val keywords = plan.keywords

        // 多关键词并行搜索：三个品类词一起打，墙钟时间 ≈ 单次请求。
        val responses: List<Pair<String, SearchOutcome>> = if (keywords.size == 1) {
            keywords.map { kw -> kw to searchPois(key, kw, loc, radius, city) }
        } else {
            val pool = Executors.newFixedThreadPool(keywords.size)
            try {
                keywords
                    .map { kw -> pool.submit(Callable { kw to searchPois(key, kw, loc, radius, city) }) }
                    .map { future ->
                        runCatching { future.get() }.getOrNull()
                            ?: ("?" to SearchOutcome(null, "网络或超时"))
                    }
            } finally {
                pool.shutdown()
            }
        }
        if (responses.all { it.second.pois == null }) {
            val reason = responses.firstNotNullOfOrNull { it.second.error } ?: "网络或超时"
            return ToolExecutionResult("请求高德接口失败（$reason）", false)
        }
        val ranked = responses.mapNotNull { (kw, outcome) -> outcome.pois?.let { kw to it } }
        val merged = AmapPlaceQuery.merge(ranked, plan.foodMode, MAX_SANE_DISTANCE_M)

        // 显示真正搜过的关键词：泛指问句会显示成「餐厅·饭店·小吃」，
        // 普通查询显示归一化后的地点名。
        val label = keywords.joinToString("·")
        if (merged.pois.isEmpty()) {
            return if (merged.droppedTooFar > 0) {
                ToolExecutionResult(
                    "找到的「$label」都在 ${MAX_SANE_DISTANCE_M / 1000}km 之外，已全部丢弃。",
                    true,
                    spokenSummary = "找到的「$label」都太远了，没有可用的结果。",
                )
            } else {
                ToolExecutionResult(
                    if (loc != null) "附近 ${radius}m 内没有找到「$label」" else "没有找到「$label」",
                    true,
                    spokenSummary = if (loc != null) "附近没有找到「$label」" else "没有找到「$label」",
                )
            }
        }

        val sb = StringBuilder()
        // 前三个结果用于语音播报：名字 + 距离，「周边搜索」在语音场景下
        // 用户最关心的就是「最近的是什么、多远」。完整列表仍在气泡里。
        val spoken = mutableListOf<String>()
        var shown = 0
        // 每个关键词分到一份名额、总共最多 8 条：不分配额的话，
        // 「好吃的面馆」光面馆就能占满，后面的餐厅/饭店/小吃一条都露不出来。
        for (poi in AmapPlaceQuery.select(merged.pois, limit = 8)) {
            val distM = poi.distanceM
            shown++
            sb.append("• ").append(poi.name)
            if (distM != null) sb.append("（").append(distM.toInt()).append("m）")
            if (poi.address.isNotBlank()) sb.append(" — ").append(poi.address)
            sb.append('\n')
            if (spoken.size < 3) {
                spoken += if (distM != null) "${poi.name}，${distM.toInt()}米" else poi.name
            }
        }
        val header = if (loc != null) {
            "附近 ${radius}m 内的「$label」：\n"
        } else {
            "「$label」的结果：\n"
        }
        val tail = buildString {
            if (merged.droppedTooFar > 0) {
                append("\n（已丢弃 ${merged.droppedTooFar} 个距离过远的结果）")
            }
            if (merged.droppedWrongType > 0) {
                append("\n（已过滤 ${merged.droppedWrongType} 个非餐饮结果）")
            }
        }
        val spokenSummary = buildString {
            append("找到 ").append(shown).append(" 个「").append(label).append("」")
            if (spoken.isNotEmpty()) append("：").append(spoken.joinToString("；"))
            append("。")
        }
        return ToolExecutionResult(
            header + sb.toString().trimEnd() + tail,
            true,
            spokenSummary = spokenSummary,
        )
    }
}

package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.logging.AppLogger
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * [T-android-train-quote] China Railway 12306 seat availability.
 *
 * Read-only: resolve station names, ask the left-ticket endpoint, render the
 * seat columns. It never logs in, reserves or submits an order.
 *
 * Two details decide whether 12306 answers with data or with a captcha page:
 *  - the station table (name → telecode, city → stations) is fetched once per
 *    process and cached;
 *  - the left-ticket request reuses the cookies from `/otn/leftTicket/init`,
 *    and the query path (`query` / `queryG`) is discovered from that page
 *    instead of being hard-coded.
 *
 * The request is issued by the phone itself (no sandbox / proxy), which is why
 * the endpoint is reachable at all.
 */
object TrainQuoteTool {

    const val NAME = "train_12306"

    private const val TAG = "TrainQuoteTool"
    private const val API_BASE = "https://kyfw.12306.cn"
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0 Mobile Safari/537.36 Minis/1.0"

    private const val DEFAULT_MAX_RESULTS = 8
    private const val MAX_RESULTS = 20
    private const val MAX_STATIONS_PER_SIDE = 3
    private const val MAX_SALE_DAYS = 15L

    /** `result` rows address fields up to index 32 (business seat). */
    private const val REQUIRED_FIELD_COUNT = 33

    private val SHANGHAI: ZoneId = ZoneId.of("Asia/Shanghai")
    private val QUERY_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    private val cookies = ConcurrentHashMap<String, List<Cookie>>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(16, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                val host = url.host
                val merged = this@TrainQuoteTool.cookies[host].orEmpty()
                    .associateBy { it.name }
                    .toMutableMap()
                for (cookie in cookies) merged[cookie.name] = cookie
                this@TrainQuoteTool.cookies[host] = merged.values.toList()
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> =
                this@TrainQuoteTool.cookies[url.host].orEmpty()
        })
        .build()

    @Volatile
    private var stationCatalog: StationCatalog? = null

    @Volatile
    private var queryPath: String? = null

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Query China Railway 12306 seat availability for one journey and date. " +
            "Stations must be Chinese station or city names (e.g. 北京南, 上海虹桥, 张掖西). " +
            "Returns departure/arrival times, duration and remaining seats per class. " +
            "Read-only: it does not book or hold tickets.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user. Use the same language as the user."),
            "from" to AgentToolParam("string", "Departure station or city, e.g. \"张掖西\" or \"北京\"."),
            "to" to AgentToolParam("string", "Arrival station or city, e.g. \"兰州西\" or \"上海\"."),
            "date" to AgentToolParam("string", "Travel date in YYYY-MM-DD. Defaults to today (Asia/Shanghai)."),
            "time_range" to AgentToolParam("string", "Departure window, e.g. \"08:00-10:00\". Omit for the whole day."),
            "only_available" to AgentToolParam("boolean", "When true, keep only trains that still have at least one seat class on sale."),
            "max_results" to AgentToolParam("integer", "Maximum trains to list (default 8, max 20)."),
        ),
        required = listOf("tool_title", "from", "to"),
        propertyOrdering = listOf(
            "tool_title", "from", "to", "date", "time_range", "only_available", "max_results",
        ),
    )

    // ---------------------------------------------------------------- exec

    /** Blocking call — the caller runs it on an IO dispatcher. */
    fun execute(argsJson: String): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrNull()
            ?: return ToolExecutionResult("参数不是合法 JSON", false)
        val fromInput = args.optString("from").trim()
        val toInput = args.optString("to").trim()
        if (fromInput.isBlank() || toInput.isBlank()) {
            return ToolExecutionResult("请提供出发站和到达站，例如「北京南到上海虹桥」。", false)
        }

        val today = LocalDate.now(SHANGHAI)
        val rawDate = args.optString("date").trim()
        val date = if (rawDate.isBlank()) {
            today
        } else {
            runCatching { LocalDate.parse(rawDate) }.getOrNull()
                ?: return ToolExecutionResult("日期格式不对，请用 YYYY-MM-DD，例如 2026-11-16。", false)
        }
        if (date.isBefore(today)) {
            return ToolExecutionResult("不能查询过去的日期，请换一个出行日期。", false)
        }
        if (date.isAfter(today.plusDays(MAX_SALE_DAYS))) {
            return ToolExecutionResult("12306 目前只预售 $MAX_SALE_DAYS 天内的车票，请换一个更近的日期。", false)
        }

        val maxResults = args.optInt("max_results", DEFAULT_MAX_RESULTS).coerceIn(1, MAX_RESULTS)
        val onlyAvailable = args.optBoolean("only_available", false)
        val window = parseTimeRange(args.optString("time_range"))

        return try {
            val catalog = loadStations()
            val origins = catalog.resolve(fromInput)
            val destinations = catalog.resolve(toInput)
            if (origins.isEmpty()) {
                return ToolExecutionResult(
                    "没识别到出发站「$fromInput」。请回复准确站名或城市，例如「北京南」或「北京」。",
                    false,
                )
            }
            if (destinations.isEmpty()) {
                return ToolExecutionResult(
                    "没识别到到达站「$toInput」。请回复准确站名或城市，例如「上海虹桥」或「上海」。",
                    false,
                )
            }

            val endpoint = prepareSession()
            val trains = queryAll(endpoint, date.toString(), origins, destinations)
                .filter { window?.contains(it.departTime) ?: true }
                .filter { !onlyAvailable || it.hasAvailability }
                .distinctBy { "${it.code}|${it.fromStation}|${it.toStation}|${it.departTime}" }
                .sortedBy { it.departTime }

            if (trains.isEmpty()) {
                val message = buildString {
                    append("12306 余票：").append(fromInput).append(" → ").append(toInput)
                        .append("，").append(date).append('\n')
                    append("查询时间：").append(queryTime())
                    append("；没有查到符合条件的车次")
                    window?.let { append("（出发时间 ").append(it.label).append("）") }
                    if (onlyAvailable) append("（只看有票）")
                    append("。可以换个日期或筛选条件再查。\n")
                    append("来源：中国铁路 12306 网站；余票会实时变化，请以购票页为准。")
                }
                return ToolExecutionResult(message, true, spokenSummary = "没有查到符合条件的车次。")
            }

            ToolExecutionResult(
                formatTrains(
                    fromDisplay = fromInput,
                    toDisplay = toInput,
                    date = date.toString(),
                    queryTime = queryTime(),
                    timeLabel = window?.label,
                    onlyAvailable = onlyAvailable,
                    trains = trains,
                    maxResults = maxResults,
                ),
                true,
                spokenSummary = spokenSummary(trains),
            )
        } catch (failure: TrainQueryFailure) {
            AppLogger.warning(TAG, "12306 query failed: ${failure.message}")
            ToolExecutionResult("12306 暂时没查成：${failure.message}", false)
        } catch (failure: Throwable) {
            AppLogger.warning(TAG, "12306 query error: ${failure.javaClass.simpleName}: ${failure.message}")
            ToolExecutionResult("12306 暂时不可用（网络或服务异常），请稍后重试。", false)
        }
    }

    private fun queryAll(
        endpoint: String,
        date: String,
        origins: List<Station>,
        destinations: List<Station>,
    ): List<Train> {
        val pairs = origins.take(MAX_STATIONS_PER_SIDE).flatMap { from ->
            destinations.take(MAX_STATIONS_PER_SIDE).map { to -> from to to }
        }
        val responses = runBlocking {
            coroutineScope {
                pairs.map { (from, to) ->
                    async(Dispatchers.IO) {
                        runCatching {
                            parseTrains(
                                body = queryPair(endpoint, date, from, to),
                                fallbackFrom = from.name,
                                fallbackTo = to.name,
                            )
                        }
                    }
                }.awaitAll()
            }
        }
        val trains = responses.mapNotNull { it.getOrNull() }.flatten()
        if (trains.isEmpty() && responses.all { it.isFailure }) {
            (responses.firstNotNullOfOrNull { it.exceptionOrNull() as? TrainQueryFailure })
                ?.let { throw it }
            throw TrainQueryFailure("12306 余票接口返回异常")
        }
        return trains
    }

    // --------------------------------------------------------------- http

    private fun loadStations(): StationCatalog {
        stationCatalog?.let { return it }
        synchronized(this) {
            stationCatalog?.let { return it }
            val request = Request.Builder()
                .url("$API_BASE/otn/resources/js/framework/station_name.js")
                .header("User-Agent", USER_AGENT)
                .header("Referer", "$API_BASE/index/")
                .get()
                .build()
            val body = executeText(request, "获取 12306 车站表失败")
            val raw = STATION_NAME_REGEX.find(body)?.groupValues?.getOrNull(1)
                ?: throw TrainQueryFailure("12306 车站表格式已变化")
            val stations = raw.split('@').mapNotNull { record ->
                if (record.isBlank()) return@mapNotNull null
                val fields = record.split('|')
                if (fields.size < 8) return@mapNotNull null
                val name = fields[1].replace(Regex("\\s+"), "")
                val code = fields[2]
                val city = fields[7].replace(Regex("\\s+"), "")
                if (name.isBlank() || code.isBlank()) null else Station(name, code, city)
            }
            if (stations.size < 100) throw TrainQueryFailure("12306 车站表不完整")
            val catalog = StationCatalog(stations)
            stationCatalog = catalog
            AppLogger.info(TAG, "station table loaded: ${stations.size} stations")
            return catalog
        }
    }

    private fun prepareSession(): String {
        queryPath?.let { return it }
        val request = Request.Builder()
            .url("$API_BASE/otn/leftTicket/init")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .get()
            .build()
        val html = executeText(request, "获取 12306 会话失败")
        val endpoint = LEFT_TICKET_PATH_REGEX.find(html)?.groupValues?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?: "leftTicket/query"
        queryPath = endpoint
        AppLogger.info(TAG, "leftTicket endpoint=$endpoint")
        return endpoint
    }

    private fun queryPair(
        endpoint: String,
        date: String,
        from: Station,
        to: Station,
    ): String {
        val path = endpoint.removePrefix("/")
        val url = when {
            path.startsWith("http://") || path.startsWith("https://") -> path
            path.startsWith("otn/") -> "$API_BASE/$path"
            else -> "$API_BASE/otn/$path"
        }
        val separator = if (url.contains("?")) "&" else "?"
        val fullUrl = url + separator +
            "leftTicketDTO.train_date=${URLEncoder.encode(date, StandardCharsets.UTF_8.name())}" +
            "&leftTicketDTO.from_station=${URLEncoder.encode(from.telecode, StandardCharsets.UTF_8.name())}" +
            "&leftTicketDTO.to_station=${URLEncoder.encode(to.telecode, StandardCharsets.UTF_8.name())}" +
            "&purpose_codes=ADULT"
        val request = Request.Builder()
            .url(fullUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("Referer", "$API_BASE/otn/leftTicket/init")
            .header("X-Requested-With", "XMLHttpRequest")
            .get()
            .build()
        val body = executeText(request, "查询 ${from.name} 到 ${to.name} 的余票失败")
        // 12306 answers a throttled session with an HTML captcha page.
        if (body.trimStart().startsWith("<")) {
            throw TrainQueryFailure("12306 返回了验证页面，请稍后再试")
        }
        return body
    }

    private fun executeText(request: Request, failureMessage: String): String {
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw TrainQueryFailure("$failureMessage（HTTP ${response.code}）")
                }
                response.body?.string().orEmpty()
            }
        } catch (failure: TrainQueryFailure) {
            throw failure
        } catch (failure: Throwable) {
            throw TrainQueryFailure("$failureMessage（网络异常）")
        }
    }

    // ------------------------------------------------------------- parser

    internal data class Seat(val name: String, val status: String)

    internal data class Train(
        val code: String,
        val fromStation: String,
        val toStation: String,
        val departTime: String,
        val arriveTime: String,
        val duration: String,
        val seats: List<Seat>,
    ) {
        val hasAvailability: Boolean
            get() = seats.any { it.status != "无票" }
    }

    /**
     * Parse the `data.result` rows. Pure JVM code — the unit test feeds it a
     * fixture instead of calling 12306.
     */
    internal fun parseTrains(
        body: String,
        fallbackFrom: String,
        fallbackTo: String,
    ): List<Train> {
        val json = runCatching { JSONObject(body) }
            .getOrElse { throw TrainQueryFailure("12306 返回格式已变化") }
        val data = json.optJSONObject("data") ?: return emptyList()
        val rows = data.optJSONArray("result") ?: return emptyList()
        val stationMap = data.optJSONObject("map")
        val result = ArrayList<Train>(rows.length())
        for (index in 0 until rows.length()) {
            val fields = rows.optString(index).split('|')
            if (fields.size < REQUIRED_FIELD_COUNT) continue
            val code = fields[3].trim()
            if (code.isBlank()) continue
            val fromCode = fields[6]
            val toCode = fields[7]
            result += Train(
                code = code,
                fromStation = stationMap?.optString(fromCode).orEmpty().ifBlank { fallbackFrom },
                toStation = stationMap?.optString(toCode).orEmpty().ifBlank { fallbackTo },
                departTime = fields[8].trim(),
                arriveTime = fields[9].trim(),
                duration = fields[10].trim(),
                seats = parseSeats(fields),
            )
        }
        return result
    }

    private fun parseSeats(fields: List<String>): List<Seat> =
        SEAT_FIELDS.mapNotNull { (name, index) ->
            val raw = fields.getOrNull(index).orEmpty().trim()
            if (raw.isBlank() || raw == "--" || raw == "*") return@mapNotNull null
            Seat(name, availability(raw))
        }

    private fun availability(raw: String): String {
        val value = raw.trim()
        return when {
            value == "有" || value == "充足" -> "有票"
            value == "候补" -> "候补"
            value == "无" -> "无票"
            value.toIntOrNull() != null -> "${value.toInt()}张"
            else -> value
        }
    }

    // ------------------------------------------------------------- output

    internal fun formatTrains(
        fromDisplay: String,
        toDisplay: String,
        date: String,
        queryTime: String,
        timeLabel: String?,
        onlyAvailable: Boolean,
        trains: List<Train>,
        maxResults: Int,
    ): String = buildString {
        append("12306 余票：").append(fromDisplay).append(" → ").append(toDisplay)
            .append("，").append(date).append('\n')
        append("查询时间：").append(queryTime).append("；查到 ").append(trains.size).append(" 趟")
        timeLabel?.let { append("；出发时间：").append(it) }
        if (onlyAvailable) append("；只看有票")
        append('\n')
        val limit = maxResults.coerceIn(1, MAX_RESULTS)
        trains.take(limit).forEachIndexed { index, train ->
            append('\n').append(index + 1).append(". ").append(train.code)
            append(' ').append(train.fromStation).append(" → ").append(train.toStation)
            append("  ").append(train.departTime).append('-').append(train.arriveTime)
            append("  历时").append(train.duration)
            append('\n')
            if (train.seats.isEmpty()) {
                append("   余票：暂未显示可售席别")
            } else {
                append("   余票：").append(
                    train.seats.joinToString("；") { "${it.name} ${it.status}" },
                )
            }
        }
        if (trains.size > limit) {
            append("\n\n其余 ").append(trains.size - limit).append(" 趟未展开。")
        }
        append("\n来源：中国铁路 12306 网站；余票会实时变化，请以购票页为准。")
    }

    private fun spokenSummary(trains: List<Train>): String {
        if (trains.isEmpty()) return ""
        val first = trains.first()
        return "查到 ${trains.size} 趟车。第一趟 ${first.code}，${first.departTime} 发车，" +
            "${first.arriveTime} 到达。"
    }

    private fun queryTime(): String =
        LocalDateTime.now(SHANGHAI).format(QUERY_TIME_FORMAT)

    // -------------------------------------------------------------- time

    private class DepartureWindow(
        private val startMinutes: Int,
        private val endMinutes: Int,
    ) {
        fun contains(raw: String): Boolean {
            val parts = raw.split(':')
            val hour = parts.getOrNull(0)?.toIntOrNull() ?: return false
            val minute = parts.getOrNull(1)?.toIntOrNull() ?: return false
            val value = hour * 60 + minute
            return if (startMinutes <= endMinutes) {
                value in startMinutes..endMinutes
            } else {
                value >= startMinutes || value <= endMinutes
            }
        }

        val label: String
            get() = String.format(
                Locale.US,
                "%02d:%02d-%02d:%02d",
                startMinutes / 60,
                startMinutes % 60,
                endMinutes / 60,
                endMinutes % 60,
            )
    }

    private fun parseTimeRange(raw: String): DepartureWindow? {
        val match = TIME_RANGE_REGEX.find(raw.trim()) ?: return null
        val startHour = match.groupValues[1].toIntOrNull() ?: return null
        val startMinute = match.groupValues[2].toIntOrNull() ?: return null
        val endHour = match.groupValues[3].toIntOrNull() ?: return null
        val endMinute = match.groupValues[4].toIntOrNull() ?: return null
        if (startHour !in 0..23 || endHour !in 0..23) return null
        if (startMinute !in 0..59 || endMinute !in 0..59) return null
        return DepartureWindow(startHour * 60 + startMinute, endHour * 60 + endMinute)
    }

    // ----------------------------------------------------------- stations

    private data class Station(val name: String, val telecode: String, val city: String)

    private class StationCatalog(private val stations: List<Station>) {
        private val byName = stations.groupBy { normalize(it.name) }
        private val byCity = stations.groupBy { normalize(it.city) }

        fun resolve(raw: String): List<Station> {
            val value = normalize(raw)
            if (value.isBlank()) return emptyList()
            if (raw.trim().endsWith("站")) {
                return byName[value]?.take(1).orEmpty()
            }
            val cityStations = byCity[value].orEmpty()
            if (cityStations.isNotEmpty()) {
                return cityStations.sortedWith(
                    compareByDescending<Station> { normalize(it.name) == value }
                        .thenByDescending { stationRank(it.name) }
                        .thenBy { it.name },
                ).take(MAX_STATIONS_PER_SIDE)
            }
            return byName[value]?.take(1).orEmpty()
        }

        private fun stationRank(name: String): Int = when {
            name.endsWith("南") || name.endsWith("虹桥") -> 6
            name.endsWith("北") || name.endsWith("朝阳") -> 5
            name.endsWith("东") || name.endsWith("丰台") -> 4
            name.endsWith("西") || name.endsWith("大兴") || name.endsWith("浦东") -> 3
            else -> 1
        }

        private fun normalize(raw: String): String =
            raw.replace(Regex("\\s+"), "").removeSuffix("站")
    }

    private class TrainQueryFailure(message: String) : Exception(message)

    private val SEAT_FIELDS = listOf(
        "商务座" to 32,
        "一等座" to 31,
        "二等座" to 30,
        "软卧" to 23,
        "硬卧" to 28,
        "硬座" to 29,
        "无座" to 26,
    )
    private val STATION_NAME_REGEX = Regex("""var\s+station_names\s*=\s*'([^']+)'""")
    private val LEFT_TICKET_PATH_REGEX = Regex("""CLeftTicketUrl\s*=\s*['"]([^'"]+)['"]""")
    private val TIME_RANGE_REGEX = Regex("""(\d{1,2}):(\d{2})\s*[-~—–至到]\s*(\d{1,2}):(\d{2})""")
}

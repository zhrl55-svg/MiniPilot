package com.openminis.app.assistant.execution

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.openminis.app.logging.AppLogger
import com.openminis.app.tools.AmapSearchTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [T-fastpath-weather-ms] 端侧天气的亚秒路径。
 *
 * ## 为什么需要它
 * 旧实现每次「查天气」都先发一次**全新 GPS** 请求（`--timeout 8`），再用
 * Open-Meteo 拉 48 小时 + 10 天的完整报文。实测：定位失败时 8 s 超时 +
 * 3.4 s 网络 = 11.5 s；定位已有缓存时也要 3.4 s。用户的主观感受是
 * 「Pilot 端侧特别快，MiniPilot 明显慢」。
 *
 * ## 怎么做（与 Pilot 对齐）
 * 1. **位置**：只读最近一次已知位置（`getLastKnownLocation`，binder 调用，
 *    毫秒级），绝不等待新的 GPS 定位。没有位置就不猜城市，直接回退云端。
 * 2. **数据**：优先高德 Web 天气（`restapi.amap.com`，国内 2 次 RTT），
 *    key 沿用 `amap_key.txt`；不可用时退回 Open-Meteo 的 `current` 精简报文
 *    （不再拉 48h/10d）。
 * 3. **缓存**：15 分钟内同一位置直接返回上次结果（内存 + 磁盘，进程重启
 *    也能命中）；App 启动时后台预热一次。
 *
 * ## 边界
 * 这里只负责「当前天气」一句话。任何一步失败（无定位权限、无最近位置、
 * 两个数据源都失败）都返回错误，由调用方按原有逻辑回退云端 —— 端侧只是
 * 加速器，不能让本来能完成的查询变慢或失败。
 */
object WeatherFastAnswer {

    private const val TAG = "WeatherFast"
    private const val DISK_FILE = "weather_fast.json"

    /** 缓存有效期：15 分钟内的实况可以直接复用。 */
    const val TTL_MS = 15 * 60 * 1000L

    /** adcode 缓存：行政区划很少变，6 小时内不重复反查。 */
    private const val ADCODE_TTL_MS = 6 * 60 * 60 * 1000L

    /** 位置移动超过这个纬度/经度差（约 30 km）就认为换地方了，缓存作废。 */
    private const val MOVE_DEG = 0.3

    private const val AMAP_BASE = "https://restapi.amap.com/v3"

    /** 一次快速天气查询的结果：要么是一句话，要么是给调用方的失败原因。 */
    data class Outcome(
        val line: String? = null,
        val error: String? = null,
        val source: String = "",
    )

    @Volatile private var text: String? = null
    @Volatile private var atMs: Long = 0L
    @Volatile private var lat: Double = Double.NaN
    @Volatile private var lon: Double = Double.NaN

    @Volatile private var adcode: String? = null
    @Volatile private var adcodeAtMs: Long = 0L

    @Volatile private var diskLoaded = false

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── 缓存 ────────────────────────────────────────────────────────────

    fun isFresh(at: Long, now: Long): Boolean = at > 0L && now - at <= TTL_MS

    /** 同一位置未移动（或没有位置可比）时返回缓存正文。 */
    fun cachedLine(now: Long = System.currentTimeMillis()): String? {
        val line = text ?: return null
        if (!isFresh(atMs, now)) return null
        return line
    }

    fun cachedCoordinates(): Pair<Double, Double>? =
        if (lat.isNaN() || lon.isNaN()) null else lat to lon

    fun put(context: Context, line: String, latitude: Double, longitude: Double) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            text = line
            atMs = now
            lat = latitude
            lon = longitude
        }
        runCatching {
            val dir = context.filesDir ?: return@runCatching
            File(dir, DISK_FILE).writeText(
                JSONObject()
                    .put("text", line)
                    .put("at", now)
                    .put("lat", latitude)
                    .put("lon", longitude)
                    .toString(),
            )
        }
    }

    fun clear() {
        synchronized(lock) {
            text = null
            atMs = 0L
            lat = Double.NaN
            lon = Double.NaN
            adcode = null
            adcodeAtMs = 0L
            diskLoaded = false
        }
    }

    /** 进程重启后把磁盘缓存读回内存（只做一次）。 */
    fun loadDisk(context: Context) {
        if (diskLoaded) return
        synchronized(lock) {
            if (diskLoaded) return
            diskLoaded = true
            runCatching {
                val file = File(context.filesDir, DISK_FILE)
                if (!file.isFile) return@runCatching
                val json = JSONObject(file.readText())
                val cachedText = json.optString("text").takeIf { it.isNotBlank() } ?: return@runCatching
                val at = json.optLong("at", 0L)
                if (!isFresh(at, System.currentTimeMillis())) return@runCatching
                text = cachedText
                atMs = at
                lat = json.optDouble("lat", Double.NaN)
                lon = json.optDouble("lon", Double.NaN)
            }
        }
    }

    // ── 位置 ────────────────────────────────────────────────────────────

    /**
     * 最近一次已知位置。**不发起新定位**，没有就返回 null。
     * 与 Pilot 的 WeatherTool 同一策略：多个 provider 取时间最新的那个。
     */
    fun lastKnownLocation(context: Context): Location? {
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!coarse && !fine) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        val providers = try {
            manager.getProviders(true)
        } catch (_: Throwable) {
            emptyList()
        }
        var best: Location? = null
        for (provider in providers) {
            val location = try {
                manager.getLastKnownLocation(provider)
            } catch (_: Throwable) {
                null
            } ?: continue
            if (location.latitude == 0.0 && location.longitude == 0.0) continue
            if (best == null || location.time > best.time) best = location
        }
        return best
    }

    // ── 主入口 ──────────────────────────────────────────────────────────

    /**
     * 执行一次快速天气查询（在工作线程调用；会阻塞到网络返回）。
     *
     * 顺序：缓存 → 最近位置 → 高德 → Open-Meteo。全部失败时返回 [Outcome.error]，
     * 调用方据此回退云端。
     */
    fun refreshBlocking(context: Context, now: Long = System.currentTimeMillis()): Outcome {
        loadDisk(context)
        val location = lastKnownLocation(context)
        cachedLine(now)?.let { line ->
            if (location == null || !movedFar(location)) {
                return Outcome(line = line, source = "cache")
            }
        }
        if (location == null) {
            return Outcome(error = "没有拿到当前位置（定位未开启，或系统没有最近定位记录）。")
        }

        fetchAmap(context, location.latitude, location.longitude)?.let { line ->
            put(context, line, location.latitude, location.longitude)
            return Outcome(line = line, source = "amap")
        }
        fetchOpenMeteo(location.latitude, location.longitude)?.let { line ->
            put(context, line, location.latitude, location.longitude)
            return Outcome(line = line, source = "open-meteo")
        }
        return Outcome(error = "天气查询失败：高德与 Open-Meteo 均未返回数据。")
    }

    private fun movedFar(location: Location): Boolean {
        val cachedLat = lat
        val cachedLon = lon
        if (cachedLat.isNaN() || cachedLon.isNaN()) return false
        return kotlin.math.abs(location.latitude - cachedLat) > MOVE_DEG ||
            kotlin.math.abs(location.longitude - cachedLon) > MOVE_DEG
    }

    /**
     * App 启动时后台预热：缓存还在有效期内就什么都不做。失败静默 ——
     * 预热只是为了「第一句话就快」，不能影响启动。
     */
    fun warmAsync(context: Context) {
        val app = context.applicationContext
        scope.launch {
            runCatching {
                loadDisk(app)
                if (cachedLine() != null) return@runCatching
                val location = lastKnownLocation(app) ?: return@runCatching
                fetchAmap(app, location.latitude, location.longitude)?.let { line ->
                    put(app, line, location.latitude, location.longitude)
                    return@runCatching
                }
                fetchOpenMeteo(location.latitude, location.longitude)?.let { line ->
                    put(app, line, location.latitude, location.longitude)
                }
            }.onFailure {
                AppLogger.warning(TAG, "warm failed: ${it.message}")
            }
        }
    }

    // ── 数据源：高德（与 Pilot 一致） ──────────────────────────────────

    private fun fetchAmap(context: Context, latitude: Double, longitude: Double): String? {
        val key = runCatching { AmapSearchTool.readKey(context) }.getOrDefault("")
        if (key.isBlank()) return null
        val code = resolveAdcode(key, longitude, latitude) ?: return null
        val json = getJson(
            "$AMAP_BASE/weather/weatherInfo?key=$key&city=$code&extensions=base&output=json",
        ) ?: return null
        if (json.optString("status") != "1") {
            AppLogger.warning(TAG, "amap weather status=${json.optString("status")} ${json.optString("info")}")
            return null
        }
        return lineFromAmap(json)
    }

    private fun resolveAdcode(key: String, longitude: Double, latitude: Double): String? {
        val cached = adcode
        if (cached != null && System.currentTimeMillis() - adcodeAtMs <= ADCODE_TTL_MS) {
            return cached
        }
        val json = getJson(
            "$AMAP_BASE/geocode/regeo?key=$key&location=$longitude,$latitude&extensions=base&batch=false",
        ) ?: return null
        if (json.optString("status") != "1") return null
        val code = json.optJSONObject("regeocode")
            ?.optJSONObject("addressComponent")
            ?.optString("adcode")
            ?.takeIf { it.length == 6 && it.all(Char::isDigit) }
            ?: return null
        adcode = code
        adcodeAtMs = System.currentTimeMillis()
        return code
    }

    /**
     * 高德 `lives[0]` → 一句中文。字段都是字符串，空值直接跳过，
     * 一个字段都没有就返回 null（当作失败，让调用方回退）。
     */
    fun lineFromAmap(json: JSONObject): String? {
        val lives = json.optJSONArray("lives")?.optJSONObject(0) ?: return null
        val city = lives.optString("city").ifBlank { lives.optString("province") }.trim()
        val weather = lives.optString("weather").trim()
        val temp = lives.optString("temperature").trim()
        val humidity = lives.optString("humidity").trim()
        val windDir = lives.optString("winddirection").trim()
        val windPower = lives.optString("windpower").trim()
        val report = lives.optString("reporttime").trim().substringAfter(' ', "").take(5)

        val clauses = mutableListOf<String>()
        if (weather.isNotBlank()) clauses += weather
        if (temp.isNotBlank()) clauses += "气温 ${temp}℃"
        if (windDir.isNotBlank()) {
            clauses += if (windPower.isNotBlank()) "${windDir}风${windPower}级" else "${windDir}风"
        }
        if (humidity.isNotBlank()) clauses += "湿度 ${humidity}%"
        if (clauses.isEmpty()) return null

        val head = if (city.isNotBlank()) "${city}天气：" else "当前天气："
        val tail = if (report.isNotBlank()) "（数据更新 $report）" else ""
        return head + clauses.joinToString("，") + "。" + tail
    }

    // ── 数据源：Open-Meteo（只取 current，精简报文） ──────────────────

    private fun fetchOpenMeteo(latitude: Double, longitude: Double): String? {
        val url = "https://api.open-meteo.com/v1/forecast?" +
            "latitude=$latitude&longitude=$longitude" +
            "&current=temperature_2m,relative_humidity_2m,apparent_temperature," +
            "precipitation,weather_code,wind_speed_10m,wind_direction_10m" +
            "&timezone=auto"
        val json = getJson(url) ?: return null
        return lineFromOpenMeteo(json)
    }

    /**
     * Open-Meteo `current` → 一句中文。先把字段归一成 android-weather
     * `current` 块的形状，再交给 [LocalResultFormatter] —— 手动天气查询
     * 和上下文注入读的是同一套翻译，不会出现两种说法。
     */
    fun lineFromOpenMeteo(json: JSONObject): String? {
        val current = json.optJSONObject("current") ?: return null
        val units = json.optJSONObject("current_units")
        val normalized = JSONObject()
            .put("time", current.optString("time", ""))
            .put("temperature", current.optDouble("temperature_2m"))
            .put("temperature_unit", units?.optString("temperature_2m", "°C") ?: "°C")
            .put("feels_like", current.optDouble("apparent_temperature"))
            .put("humidity", current.optInt("relative_humidity_2m", -1))
            .put("precipitation_mm", current.optDouble("precipitation"))
            .put("wind_speed", current.optDouble("wind_speed_10m"))
            .put("wind_speed_unit", units?.optString("wind_speed_10m", "km/h") ?: "km/h")
            .put("weather_code", current.optInt("weather_code", -1))
        val line = LocalResultFormatter.answer(
            capability = "weather",
            action = "current",
            output = normalized.toString(),
            success = true,
        )
        return line.takeIf { it.isNotBlank() }
    }

    // ── HTTP ───────────────────────────────────────────────────────────

    private fun getJson(url: String): JSONObject? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 2500
            readTimeout = 2500
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }
        conn.inputStream.use { JSONObject(it.readBytes().decodeToString()) }
    } catch (t: Throwable) {
        AppLogger.warning(TAG, "request failed: ${t.javaClass.simpleName}: ${t.message}")
        null
    }

    // ── 供诊断使用 ─────────────────────────────────────────────────────

    fun clockLabel(now: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(now))
}

package com.openminis.app.assistant.execution

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-fastpath-weather-ms] 端侧天气快速路径的纯函数部分。
 *
 * 不碰 Android 框架：只验证「报文 → 一句中文」和缓存有效期判断。
 * 位置、网络、缓存落盘需要真机，交给端到端验证（assistant_fastpath.log）。
 */
class WeatherFastAnswerTest {

    @Test
    fun amapLivesBecomeOneChineseSentence() {
        val json = JSONObject(
            """
            {"status":"1","count":"1","lives":[{
              "province":"甘肃","city":"张掖市","adcode":"620700",
              "weather":"晴","temperature":"-0.2","winddirection":"西北",
              "windpower":"2","humidity":"42","reporttime":"2026-11-28 21:15:00"
            }]}
            """.trimIndent(),
        )
        val line = WeatherFastAnswer.lineFromAmap(json)
            ?: error("expected a line")
        assertTrue(line, line.startsWith("张掖市天气："))
        assertTrue(line, line.contains("晴"))
        assertTrue(line, line.contains("气温 -0.2℃"))
        assertTrue(line, line.contains("西北风2级"))
        assertTrue(line, line.contains("湿度 42%"))
        assertTrue(line, line.contains("数据更新 21:15"))
    }

    @Test
    fun amapLineFallsBackToProvinceWhenCityIsBlank() {
        val json = JSONObject(
            """{"status":"1","lives":[{"province":"甘肃","city":"","weather":"多云","temperature":"3"}]}""",
        )
        val line = WeatherFastAnswer.lineFromAmap(json) ?: error("expected a line")
        assertTrue(line, line.startsWith("甘肃天气："))
        assertTrue(line, line.contains("多云"))
    }

    @Test
    fun amapEmptyLivesIsAFailure() {
        assertNull(WeatherFastAnswer.lineFromAmap(JSONObject("""{"status":"1","lives":[]}""")))
        assertNull(WeatherFastAnswer.lineFromAmap(JSONObject("""{"status":"0"}""")))
        assertNull(WeatherFastAnswer.lineFromAmap(JSONObject("""{"status":"1","lives":[{}]}""")))
    }

    @Test
    fun openMeteoCurrentBecomesTheSameChineseSentenceAsTheTool() {
        val json = JSONObject(
            """
            {"current_units":{"temperature_2m":"°C","wind_speed_10m":"km/h"},
             "current":{"time":"2026-11-28T21:15","temperature_2m":-0.1,
                        "relative_humidity_2m":41,"apparent_temperature":-3.6,
                        "precipitation":0.0,"weather_code":0,"wind_speed_10m":1.3}}
            """.trimIndent(),
        )
        val line = WeatherFastAnswer.lineFromOpenMeteo(json) ?: error("expected a line")
        assertTrue(line, line.startsWith("现在（21:15）："))
        assertTrue(line, line.contains("晴"))
        assertTrue(line, line.contains("-0.1°C"))
        assertTrue(line, line.contains("体感 -3.6°C"))
        assertTrue(line, line.contains("湿度 41%"))
    }

    @Test
    fun openMeteoWithoutCurrentIsAFailure() {
        assertNull(WeatherFastAnswer.lineFromOpenMeteo(JSONObject("""{"latitude":38.9}""")))
    }

    @Test
    fun freshnessWindowIsFifteenMinutes() {
        val now = 1_000_000L
        assertTrue(WeatherFastAnswer.isFresh(now - 60_000L, now))
        assertTrue(WeatherFastAnswer.isFresh(now - WeatherFastAnswer.TTL_MS, now))
        assertFalse(WeatherFastAnswer.isFresh(now - WeatherFastAnswer.TTL_MS - 1L, now))
        assertFalse(WeatherFastAnswer.isFresh(0L, now))
    }
}

package com.openminis.app.tools

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-train-quote] Field mapping and rendering are pure functions, so
 * a fixture result string is enough to exercise them without touching 12306.
 */
class TrainQuoteToolTest {

    private fun row(
        code: String,
        fromCode: String,
        toCode: String,
        depart: String,
        arrive: String,
        duration: String,
        softSleeper: String = "",
        hardSleeper: String = "",
        standing: String = "",
    ): String {
        val fields = MutableList(40) { "" }
        fields[3] = code
        fields[6] = fromCode
        fields[7] = toCode
        fields[8] = depart
        fields[9] = arrive
        fields[10] = duration
        fields[23] = softSleeper
        fields[28] = hardSleeper
        fields[26] = standing
        return fields.joinToString("|")
    }

    private val body: String = JSONObject().apply {
        put(
            "data",
            JSONObject().apply {
                put(
                    "map",
                    JSONObject()
                        .put("ZYJ", "张掖西")
                        .put("LZX", "兰州西")
                        .put("LZ", "兰州"),
                )
                put(
                    "result",
                    JSONArray()
                        .put(row("Z306", "ZYJ", "LZX", "08:12", "12:02", "03:50", "7", "2", "有"))
                        // Same train, later stop: both rows must survive.
                        .put(row("Z306", "ZYJ", "LZ", "08:12", "12:20", "04:08", "7")),
                )
            },
        )
    }.toString()

    @Test
    fun `parser reads the fixed field indexes`() {
        val trains = TrainQuoteTool.parseTrains(body, "张掖西", "兰州西")
        assertEquals(2, trains.size)
        val first = trains[0]
        assertEquals("Z306", first.code)
        assertEquals("张掖西", first.fromStation)
        assertEquals("兰州西", first.toStation)
        assertEquals("08:12", first.departTime)
        assertEquals("12:02", first.arriveTime)
        assertEquals("03:50", first.duration)
        assertEquals(
            listOf(
                TrainQuoteTool.Seat("软卧", "7张"),
                TrainQuoteTool.Seat("硬卧", "2张"),
                TrainQuoteTool.Seat("无座", "有票"),
            ),
            first.seats,
        )
        assertTrue(first.hasAvailability)
    }

    @Test
    fun `formatter keeps each arrival stop on its own line`() {
        val trains = TrainQuoteTool.parseTrains(body, "张掖西", "兰州西")
        val text = TrainQuoteTool.formatTrains(
            fromDisplay = "张掖西",
            toDisplay = "兰州西",
            date = "2026-11-16",
            queryTime = "2026-11-15 15:30",
            timeLabel = "08:00-10:00",
            onlyAvailable = false,
            trains = trains,
            maxResults = 8,
        )
        assertTrue(text.startsWith("12306 余票：张掖西 → 兰州西，2026-11-16\n"))
        assertTrue(text.contains("查询时间：2026-11-15 15:30；查到 2 趟；出发时间：08:00-10:00"))
        assertTrue(text.contains("1. Z306 张掖西 → 兰州西  08:12-12:02  历时03:50"))
        assertTrue(text.contains("   余票：软卧 7张；硬卧 2张；无座 有票"))
        assertTrue(text.contains("2. Z306 张掖西 → 兰州  08:12-12:20  历时04:08"))
        assertTrue(text.contains("来源：中国铁路 12306 网站"))
    }

    @Test
    fun `formatter truncates past the result limit`() {
        val trains = TrainQuoteTool.parseTrains(body, "张掖西", "兰州西")
        val text = TrainQuoteTool.formatTrains(
            fromDisplay = "张掖西",
            toDisplay = "兰州西",
            date = "2026-11-16",
            queryTime = "2026-11-15 15:30",
            timeLabel = null,
            onlyAvailable = false,
            trains = trains,
            maxResults = 1,
        )
        assertFalse(text.contains("2. Z306"))
        assertTrue(text.contains("其余 1 趟未展开。"))
    }
}

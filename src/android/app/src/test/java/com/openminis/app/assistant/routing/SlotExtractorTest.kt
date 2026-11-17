package com.openminis.app.assistant.routing

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotExtractorTest {

    private val zone = ZoneId.of("Asia/Singapore")
    private val now = LocalDateTime.of(2026, 11, 13, 10, 0)
    private val extractor = SlotExtractor({ now }, zone)
    private val routes = DefaultAssistantRoutes.all.associateBy { it.id }

    private fun extract(text: String, routeId: String): SlotExtraction =
        extractor.extract(text, routes.getValue(routeId))

    @Test
    fun `shell command keeps case`() {
        val out = extract("运行命令 Echo \"Hello World\" > /Tmp/Result.TXT", "tool.shell")
        assertEquals("Echo \"Hello World\" > /Tmp/Result.TXT", out.args.getString("command"))
    }

    @Test
    fun `file path and url keep case`() {
        val file = extract("读取 /Tmp/Config.JSON", "tool.file-read")
        assertEquals("/Tmp/Config.JSON", file.args.getString("path"))

        val browser = extract("打开网页 https://Example.COM/Path?Q=One", "tool.browser")
        assertEquals("https://Example.COM/Path?Q=One", browser.args.getString("url"))
    }

    @Test
    fun `note body is verbatim and preserves line breaks`() {
        val out = extract("记笔记：Buy Milk\nSecond Line", "life.note-create")
        assertEquals("Buy Milk\nSecond Line", out.args.getString("content"))
        assertEquals("Buy Milk", out.args.getString("title"))
    }

    @Test
    fun `note search keeps the query case`() {
        val out = extract("搜索笔记 Project Alpha", "life.note-search")
        assertEquals("Project Alpha", out.args.getString("query"))
    }

    @Test
    fun `reminder title and body preserve names`() {
        val out = extract("30分钟后提醒我 Call Mom", "life.reminder")
        assertEquals("Call Mom", out.args.getString("title"))
        assertTrue(out.args.getString("body").contains("Call Mom"))
        assertEquals(1800, out.args.getLong("after"))
    }

    @Test
    fun `memory content preserves case`() {
        val out = extract("记住 Project X uses Port 8080", "tool.memory-write")
        assertEquals("Project X uses Port 8080", out.args.getString("content"))
    }

    @Test
    fun `calendar title keeps place and removes time phrases`() {
        val out = extract("明天下午3点安排会议 Product Launch", "life.calendar-create")
        assertEquals("Product Launch", out.args.getString("title"))
        assertEquals("2026-11-14T15:00:00", out.args.getString("start"))
        assertFalse(out.missing.contains("title"))
    }

    @Test
    fun `calendar location and alarm are deterministic`() {
        val out = extract(
            "明天下午3点安排会议 Product Launch 地点：Room A 提前15分钟",
            "life.calendar-create",
        )
        assertEquals("Room A", out.args.getString("location"))
        assertEquals(15, out.args.getInt("alarmMinutes"))
    }

    @Test
    fun `calendar update separates the lookup title from a new title`() {
        val out = extract(
            "修改日程 项目评审 改名为 产品评审",
            "life.calendar-update",
        )
        assertEquals("项目评审", out.args.getString("id_or_title"))
        assertEquals("产品评审", out.args.getString("title"))
    }

    @Test
    fun `calendar update does not mistake a new time for a new title`() {
        val out = extract(
            "修改日程 项目评审 改成明天下午3点",
            "life.calendar-update",
        )
        assertEquals("项目评审", out.args.getString("id_or_title"))
        assertFalse(out.args.has("title"))
        assertEquals("2026-11-14T15:00:00", out.args.getString("start"))
    }

    @Test
    fun `alarm uses ISO date and preserves label`() {
        val out = extract("明天早上7点设个闹钟 Wake UP", "life.alarm")
        assertEquals("2026-11-14T07:00:00", out.args.getString("time"))
        assertEquals("2026-11-14", out.args.getString("targetDate"))
        assertTrue(out.args.getString("label").contains("Wake UP"))
    }

    @Test
    fun `timer extracts seconds and label`() {
        val out = extract("开始一个25分钟计时器 Tea", "life.timer")
        assertEquals(25 * 60L, out.args.getLong("duration"))
        assertTrue(out.args.getString("label").contains("Tea"))
    }

    @Test
    fun `settings target and value`() {
        val open = extract("打开WiFi设置", "life.settings-open")
        assertEquals("wifi", open.args.getString("target"))

        val set = extract("把亮度调到 50%", "life.settings-set")
        assertEquals("brightness", set.args.getString("target"))
        assertEquals("50", set.args.getString("value"))
    }

    @Test
    fun `scheduled task prompt strips the cadence`() {
        val out = extract("每天帮我总结新闻", "life.scheduled-create")
        assertEquals("总结新闻", out.args.getString("prompt"))
        assertEquals("DAILY", out.args.getString("repeat"))
        assertTrue(out.parsedTime?.ambiguous == true)
    }

    @Test
    fun `relative scheduled task uses after instead of demanding a clock time`() {
        val out = extract("10分钟后定时帮我备份照片", "life.scheduled-create")
        assertEquals(600L, out.args.getLong("after"))
        assertFalse(out.args.has("at"))
    }

    @Test
    fun `contacts and app names are not folded`() {
        val contact = extract("打电话给 Alice Wang", "life.contacts")
        assertEquals("Alice Wang", contact.args.getString("name"))

        val app = extract("打开 WeChat", "life.app-open")
        assertEquals("WeChat", app.args.getString("app"))
    }

    @Test
    fun `explicit direct call is marked for the confirmed CALL_PHONE path`() {
        val out = extract("直接拨打张三", "life.contacts")
        assertEquals("张三", out.args.getString("name"))
        assertTrue(out.args.getBoolean("directCall"))
        assertFalse(extract("打电话给张三", "life.contacts").args.has("directCall"))
    }

    @Test
    fun `ambiguous date without time is flagged`() {
        val out = extract("提醒我明天交报告", "life.reminder")
        assertTrue(out.ambiguous)
        assertEquals("2026-11-14T00:00:00", out.args.getString("at"))
    }

    @Test
    fun `news and stock queries are optional and model-free`() {
        val news = extract("今天有什么科技新闻", "info.news")
        assertEquals("今天有什么科技新闻", news.args.getString("query"))
        assertTrue(news.missing.isEmpty())

        val stock = extract("上证指数现在多少", "info.stock")
        assertEquals("上证指数现在多少", stock.args.getString("query"))
        assertTrue(stock.missing.isEmpty())
    }

    // ── [T-music-slot-semantics] 端侧点歌语义 ──────────────────────────
    // 「轻音乐」是流派，不是可丢弃的尾缀；「music」是泛指，不是歌名。

    @Test
    fun `genre word is kept whole instead of becoming a one character song`() {
        val out = extract("播放轻音乐", "life.music-play")
        assertEquals("genre", out.args.getString("kind"))
        assertEquals("轻音乐", out.args.getString("query"))
        assertFalse(out.missing.contains("kind"))
    }

    @Test
    fun `english generic music request goes to the chart`() {
        val out = extract("play music", "life.music-play")
        assertEquals("none", out.args.getString("kind"))
        assertFalse(out.args.has("query"))
    }

    @Test
    fun `artist and song are split in the extraction layer`() {
        val out = extract("放一首周杰伦的稻香", "life.music-play")
        assertEquals("song", out.args.getString("kind"))
        assertEquals("稻香", out.args.getString("query"))
        assertEquals("周杰伦", out.args.getString("artist"))
    }

    @Test
    fun `bare song request has no specific target`() {
        for (text in listOf("放首歌", "来首歌", "播放音乐", "play a song")) {
            val out = extract(text, "life.music-play")
            assertEquals("none", out.args.getString("kind"))
            assertFalse("unexpected query for $text", out.args.has("query"))
        }
    }

    @Test
    fun `artist request is not searched as a song title`() {
        val out = extract("播放周杰伦的歌", "life.music-play")
        assertEquals("artist", out.args.getString("kind"))
        assertEquals("周杰伦", out.args.getString("query"))
    }

    @Test
    fun `scene variant keeps the genre word intact`() {
        val out = extract("放轻音乐给我听", "life.music-play")
        assertEquals("genre", out.args.getString("kind"))
        assertEquals("轻音乐", out.args.getString("query"))
    }

    @Test
    fun `chart words map to a chart parameter instead of a song search`() {
        val out = extract("播放新歌", "life.music-play")
        assertEquals("chart", out.args.getString("kind"))
        assertEquals("new", out.args.getString("chart"))
    }

    // ── [T-amap-colloquial] 口语地点查询 ────────────────────────────────
    // 槽位只负责剥掉疑问句的壳；泛指的展开是 AmapSearchTool 的活。

    @Test
    fun `amap query drops the question shell`() {
        val out = extract("附近有什么好吃的", "tool.amap")
        assertEquals("好吃", out.args.getString("query"))
        assertFalse(out.missing.contains("query"))
    }

    @Test
    fun `amap query keeps the generic and the modifier for the tool to expand`() {
        // 「好吃的面馆」端侧只剥掉「的」；工具再把「好吃」展开成餐厅/饭店/小吃。
        assertEquals("好吃面馆", extract("好吃的面馆", "tool.amap").args.getString("query"))
        assertEquals("咖啡店", extract("找一家附近的咖啡店", "tool.amap").args.getString("query"))
        assertEquals("超市", extract("附近有超市吗", "tool.amap").args.getString("query"))
    }

    @Test
    fun `amap query drops the search verb`() {
        // 「搜一下 / 搜下」是搜索动词：剥掉之后剩下的才是地点关键词。
        // 端侧实测：「搜一下加油站」曾原样当成关键词，高德搜不到。
        assertEquals("加油站", extract("搜一下加油站", "tool.amap").args.getString("query"))
        assertEquals("加油站", extract("搜下周边加油站", "tool.amap").args.getString("query"))
        assertEquals("加油站", extract("搜一下周边的加油站", "tool.amap").args.getString("query"))
        assertEquals("加油站", extract("周边加油站", "tool.amap").args.getString("query"))
    }

    @Test
    fun `a whole sentence leaves the query slot empty`() {
        // 端侧实测：这一整段说明文字被当成了地点关键词，搜出 0 条。
        // 槽位留空 → 必需槽位缺失 → 路由交回云端。
        val out = extract(
            "我说搜下周边加油站和搜一下加油站都没有成功，说周边加油站.才成功，" +
                "然后这一段长文字也走了快路径",
            "tool.amap",
        )
        assertTrue(out.args.optString("query").isBlank())
        assertTrue(out.missing.contains("query"))
    }

    @Test
    fun `a sentence mentioning a nearby place is not a place query`() {
        val out = extract("我昨天在附近那家店吃了个饭，你觉得怎么样", "tool.amap")
        assertTrue(out.args.optString("query").isBlank())
        assertTrue(out.missing.contains("query"))
    }

    @Test
    fun `an asr written search verb still yields the place`() {
        // 语音识别把「搜一下」写成「搜一上」：端侧照样要抽出「药店」。
        assertEquals("药店", extract("周边搜一上药店", "tool.amap").args.getString("query"))
        assertEquals("药店", extract("搜一下药店", "tool.amap").args.getString("query"))
        assertEquals("加油站", extract("找加油站", "tool.amap").args.getString("query"))
    }
}

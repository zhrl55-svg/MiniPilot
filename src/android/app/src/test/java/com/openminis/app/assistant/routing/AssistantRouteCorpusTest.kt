package com.openminis.app.assistant.routing

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 90+ sentence corpus for the local assistant. Every local life route must
 * resolve without waiting for the 0.3B model; unrelated chat must not be
 * captured by a keyword.
 */
class AssistantRouteCorpusTest {

    private val zone = ZoneId.of("Asia/Singapore")
    private val now = LocalDateTime.of(2026, 11, 13, 10, 0) // Friday
    private val router = AssistantRouter(SlotExtractor({ now }, zone))
    private val routes = DefaultAssistantRoutes.all

    private data class Case(
        val text: String,
        val capability: String? = null,
        val action: String? = null,
    )

    private fun local(text: String, capability: String, action: String) =
        Case(text, capability, action)

    private fun cloud(text: String) = Case(text)

    private val cases = listOf(
        // Alarm / timer
        local("明天早上7点设个闹钟", "alarm", "set"),
        local("设一个8点半的闹钟", "alarm", "set"),
        local("每天早上6点闹钟", "alarm", "set"),
        local("关闭闹钟", "alarm", "open"),
        local("查看闹钟", "alarm", "open"),
        local("取消闹钟", "alarm", "open"),
        local("开始一个25分钟计时器", "alarm", "timer"),
        local("倒计时10分钟", "alarm", "timer"),
        local("start a 10 minute timer", "alarm", "timer"),
        local("set an alarm for 7am", "alarm", "set"),
        local("wake me up at 7am", "alarm", "set"),

        // Reminder
        local("30分钟后提醒我开会", "reminder", "schedule"),
        local("提醒我明天下午3点给妈妈打电话", "reminder", "schedule"),
        local("明天上午9点提醒我交报告", "reminder", "schedule"),
        local("remind me in 10 minutes to call mom", "reminder", "schedule"),
        local("remind me at 9am to drink water", "reminder", "schedule"),
        local("查看提醒", "reminder", "list"),
        local("我的提醒", "reminder", "list"),
        local("待触发提醒", "reminder", "pending"),
        local("取消提醒 会议", "reminder", "cancel"),

        // Calendar
        local("明天下午3点安排会议", "calendar", "create"),
        local("下午3点创建日程 项目评审", "calendar", "create"),
        local("schedule a meeting tomorrow at 3pm", "calendar", "create"),
        local("今天有什么安排", "calendar", "list"),
        local("查看日历", "calendar", "list"),
        local("打开日历", "calendar", "list"),
        local("明天日程", "calendar", "list"),
        local("我有空吗", "calendar", "freebusy"),
        local("free time tomorrow", "calendar", "freebusy"),
        local("删除日程 项目评审", "calendar", "delete"),
        local("取消会议", "calendar", "delete"),
        local("修改日程 项目评审", "calendar", "update"),
        local("reschedule meeting to Friday at 2pm", "calendar", "update"),

        // Notes
        local("记笔记：明天买牛奶", "note", "create"),
        local("新建笔记 购物清单", "note", "create"),
        local("备忘 周末计划", "note", "create"),
        local("take a note: buy milk", "note", "create"),
        local("搜索笔记 会议", "note", "search"),
        local("找笔记 Project Alpha", "note", "search"),
        local("我的笔记", "note", "list"),
        local("打开笔记", "note", "list"),
        local("删除笔记 3", "note", "delete"),
        local("delete note 2", "note", "delete"),

        // Memory
        local("记一下我喜欢喝茶", "memory_write", "write"),
        local("记住 Project X uses Port 8080", "memory_write", "write"),
        local("我的记忆", "memory_get", "search"),
        local("我说过什么", "memory_get", "search"),

        // Settings
        local("打开WiFi设置", "settings", "open"),
        local("打开蓝牙设置", "settings", "open"),
        local("打开设置", "settings", "open"),
        local("打开电池设置", "settings", "open"),
        local("打开无障碍设置", "settings", "open"),
        local("打开应用详情", "settings", "open"),
        local("打开勿扰模式设置", "settings", "open"),
        local("自动旋转设置", "settings", "open"),
        local("open bluetooth settings", "settings", "open"),
        local("把亮度调到50%", "settings", "set"),
        local("打开勿扰模式", "settings", "set"),
        local("关闭WiFi", "settings", "set"),
        local("打开飞行模式", "settings", "set"),
        local("turn off wifi", "settings", "set"),
        local("息屏时间设为5分钟", "settings", "set"),

        // Scheduled tasks
        local("每天帮我总结新闻", "scheduled_task", "create"),
        local("每周一帮我发周报", "scheduled_task", "create"),
        local("取消定时任务 每日总结", "scheduled_task", "cancel"),
        local("我的定时任务", "scheduled_task", "list"),
        local("明天上午10点定时帮我备份", "scheduled_task", "create"),
        local("10分钟后定时帮我备份照片", "scheduled_task", "create"),
        local("schedule task every day at 8am", "scheduled_task", "create"),
        local("every day help me summarize news", "scheduled_task", "create"),
        local("remind me every day to drink water", "scheduled_task", "create"),

        // Weather, device, media, contacts, app
        local("今天天气怎么样", "weather", "current"),
        local("what's the weather", "weather", "current"),
        local("明天会下雨吗", "weather", "current"),
        local("weather", "weather", "current"),
        local("电量还剩多少", "device", "battery"),
        local("battery level", "device", "battery"),
        local("剩余空间多少", "device", "storage"),
        local("disk space", "device", "storage"),
        local("手机型号是什么", "device", "info"),
        local("暂停播放", "media", "pause"),
        local("pause music", "media", "pause"),
        local("继续播放", "media", "resume"),
        local("resume music", "media", "resume"),
        local("停止播放", "media", "stop"),
        local("播放状态", "media", "status"),
        local("播放列表", "media", "list"),
        // Music player (T-music-player-top)
        local("播放音乐", "media", "play"),
        local("放一首周杰伦的稻香", "media", "play"),
        local("播放周杰伦的歌", "media", "play"),
        local("来首歌", "media", "play"),
        local("下一首", "media", "next"),
        local("上一首", "media", "previous"),
        local("打电话给张三", "contacts", "dial"),
        local("直接拨打张三", "contacts", "dial"),
        local("call Alice Wang", "contacts", "dial"),
        local("拨打李四", "contacts", "dial"),
        local("搜索联系人 Alice", "contacts", "search"),
        local("打开微信", "app_launch", "open"),

        // News and A-share quotes (key-free read-only network tools)
        local("今天有什么科技新闻", "news_search", "search"),
        local("帮我看看财经新闻", "news_search", "search"),
        local("今天有什么财经消息", "news_search", "search"),
        local("最近有什么国际新闻", "news_search", "search"),
        local("上证指数现在多少", "stock_quote", "quote"),
        local("今天大盘怎么样", "stock_quote", "quote"),
        local("深证成指涨了还是跌了", "stock_quote", "quote"),

        // [T-amap-colloquial] 口语地点查询走本地 amap 路由（不用等云端）。
        local("附近有什么好吃的", "amap_search", "search"),
        local("附近有超市吗", "amap_search", "search"),
        local("找一家附近的咖啡店", "amap_search", "search"),
        // 「搜一下 / 搜下」是搜索动词，剥掉之后剩下的才是地点关键词。
        local("周边加油站", "amap_search", "search"),
        local("搜下周边加油站", "amap_search", "search"),
        local("搜一下周边的加油站", "amap_search", "search"),
        // 语音识别把「搜一下」写成「搜一上」：照样抽出地点。
        local("周边搜一上药店", "amap_search", "search"),
        // 地点闸门不能误伤火车票路由。
        local("张掖西到兰州西的高铁票", "train_12306", "query"),

        // [F-004] `hub_call` 里的 "call" 是「调用工具」，不是「打电话」。
        cloud("hub_call 张三"),

        // [F-001] 普通句子里出现「写入」不是写文件指令；带路径的真命令仍走本地。
        cloud("这句话里写入了很多细节"),
        cloud("我们讨论一下写入经 ConfigBridge 确认卡的流程"),
        local("把 hello 写入 /tmp/a.txt", "file_write", "write"),
        // 同一条规则管 file-edit：「你一并整理成一个文档放桌面」不是改文件
        // 指令，端侧模型曾据此把路径猜成 /etc/hosts。
        cloud("前面另一个会话记了要修改的东西，你一并整理成一个文档放桌面"),
        local("修改 /tmp/a.txt 里的 a 改成 b", "file_edit", "edit"),
        // [F-002] 桥接信封（[FROM: X] … [END X]）是内部消息，整段跳过端侧路由。
        cloud("[FROM: CLAUDE] 写入测试"),
        cloud("[FROM: CLAUDE]\n轮次：2026-11-17 批次\n[END CLAUDE]"),

        // Unrelated chat / deliberate low-information commands
        cloud("你好呀"),
        cloud("你是谁"),
        cloud("写一首关于秋天的诗"),
        cloud("讲个笑话"),
        cloud("今天心情不好"),
        cloud("帮我分析一下这份代码的架构"),
        cloud("张掖西站怎么走"),
        cloud("我想学英语"),
        cloud("明天会更好"),
        cloud("这个项目怎么样"),
        cloud("explain quantum physics"),
        cloud("tell me a story"),
        cloud("谢谢"),
        cloud("晚安"),
        // 提到「天气」/「电池」不等于要查询：长句或讨论性语境留在云端。
        cloud("可以直接对接高德地图的天气接口"),
        cloud("天气完成了过程，没有把结果显示出来"),
        cloud("我们在讨论天气接口的方案"),
        // 「播放」出现在技术讨论里时是话题，不是点歌。
        cloud("音乐播放逻辑"),
        cloud("播放器的设计"),
        cloud("帮我分析一下播放器的架构"),
        // [T-media-route-long-text] 长文本/多行消息里的媒体关键词是讨论或
        // 汇报，不能被执行成一次点歌或暂停。
        cloud("【端侧改动回报】\n1. adf750e 点歌槽位语义：流派/场景走 type=1000 歌单；语音期间暂停音乐。"),
        cloud("帮我总结一下昨天讨论的播放音乐和暂停音乐的产品设计思路"),
        cloud("这是一段超过二十个字的说明文字，里面顺便提到播放轻音乐和暂停音乐，但并不是指令"),
        cloud("暂停音乐\n谢谢"),
        cloud("打开"),
        cloud("提醒我"),
        cloud("明天"),
        cloud("schedule"),
        cloud("会议"),
        cloud("删除"),
        // Mentioning finance/news vocabulary is not the same as asking for data.
        cloud("指数基金怎么买"),
        cloud("新闻学专业怎么样"),
        cloud("怎么对接新闻接口"),
        cloud("我们在讨论股票接口的方案"),
        cloud("看看未读消息"),
        // 一整句话不是地点查询：句子里带「周边」也不行。槽位抽不出来就
        // 交回云端，而不是本地拿 39 个字去搜出 0 条。
        cloud("我说搜下周边加油站和搜一下加油站都没有成功，说周边加油站.才成功，然后这一段长文字也走了快路径"),
        cloud("我昨天在附近那家店吃了个饭，你觉得怎么样"),
    )

    @Test
    fun `corpus has enough coverage`() {
        assertTrue("corpus must cover 90+ sentences", cases.size >= 90)
        assertTrue(cases.count { it.capability != null } >= 70)
        assertTrue(cases.count { it.capability == null } >= 15)
    }

    @Test
    fun `every corpus sentence routes as expected`() {
        val failures = mutableListOf<String>()
        for (case in cases) {
            val decision = router.route(case.text, routes)
            if (case.capability == null) {
                if (decision is RouteDecision.Local) {
                    failures += "CLOUD EXPECTED: ${case.text} -> " +
                        "${decision.capability}/${decision.action} score=${decision.score}"
                }
            } else {
                if (decision !is RouteDecision.Local) {
                    failures += "LOCAL EXPECTED: ${case.text} -> " +
                        "cloud/${(decision as? RouteDecision.Cloud)?.reason}"
                } else if (decision.capability != case.capability || decision.action != case.action) {
                    failures += "WRONG: ${case.text} -> " +
                        "${decision.capability}/${decision.action}, want ${case.capability}/${case.action}"
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    /** [F-004] `hub_call` 是工具调用，不是打电话——曾经的真误触发。 */
    @Test
    fun `hub_call is never a phone call`() {
        for (text in listOf(
            "用 hub_call 工具在电脑上执行 shell，command 是 echo \"a b\"",
            "hub_call 张三",
            "hub_call 一下",
        )) {
            val decision = router.route(text, routes)
            val isDial = decision is RouteDecision.Local &&
                decision.capability == "contacts" && decision.action == "dial"
            assertTrue("$text -> $decision", !isDial)
        }
    }

    /** [F-001] 没有 path 的「写入」句子必须交云端，而不是本地弹卡/猜测路径。 */
    @Test
    fun `prose mentions of write fall back to the cloud with no-args`() {
        for (text in listOf(
            "这句话里写入了很多细节",
            "把结果写入",
        )) {
            val decision = router.route(text, routes)
            assertTrue(
                text,
                decision is RouteDecision.Cloud && decision.reason == "no-args",
            )
        }
    }

    /** [F-002] 桥接信封整段跳过端侧路由，reason 固定为 internal-message。 */
    @Test
    fun `bridge envelopes skip local routing`() {
        for (text in listOf(
            "[FROM: CLAUDE] 写入测试",
            "[FROM: X]\n任意内容\n[END X]",
            "  [FROM：CLAUDE] 写入测试",
        )) {
            val decision = router.route(text, routes)
            assertTrue(
                text,
                decision is RouteDecision.Cloud && decision.reason == "internal-message",
            )
        }
    }

    /**
     * [T-assistant-confirm-destructive-only] Only destructive actions ask for
     * a confirmation card. A create or a playback command runs right away -
     * asking twice for "设个闹钟" costs more than the alarm does.
     */
    @Test
    fun `destructive actions require confirmation`() {
        val destructive = listOf(
            "删除日程 项目评审",
            "取消提醒 会议",
            "删除笔记 3",
            "把亮度调到50%",
            "打电话给张三",
            "运行命令 ls -la /var/minis",
        )
        for (text in destructive) {
            val decision = router.route(text, routes)
            assertTrue(text, decision is RouteDecision.Local && decision.needsConfirmation)
        }
    }

    @Test
    fun `everyday commands run without confirmation`() {
        val everyday = listOf(
            "明天下午3点安排会议",
            "明天早上7点设个闹钟",
            "30分钟后提醒我开会",
            "记笔记：明天买牛奶",
            "每天帮我总结新闻",
            "播放音乐",
            "暂停播放",
            "下一首",
        )
        for (text in everyday) {
            val decision = router.route(text, routes)
            assertTrue(text, decision is RouteDecision.Local && !decision.needsConfirmation)
        }
    }

    @Test
    fun `read life actions do not require confirmation`() {
        val reads = listOf(
            "今天有什么安排",
            "我的笔记",
            "今天天气怎么样",
            "电量还剩多少",
            "查看闹钟",
            "播放状态",
        )
        for (text in reads) {
            val decision = router.route(text, routes)
            assertTrue(text, decision is RouteDecision.Local && !decision.needsConfirmation)
        }
    }
}

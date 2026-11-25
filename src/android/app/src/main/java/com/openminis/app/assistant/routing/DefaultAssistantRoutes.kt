package com.openminis.app.assistant.routing

object DefaultAssistantRoutes {
    const val SEED_VERSION = 15

    private val RETIRED_AT_V3 = setOf("life.media")

    /** [F-004] The contacts pattern that matched `hub_call` as a phone call. */
    private val RETIRED_CONTACT_PATTERNS = setOf(
        """(?:打电话给|拨打|call)\s*[\p{L}0-9_·]{1,30}""",
    )

    /**
     * [T-fastpath-topic-guard] v15 之前的内置模式：只命中关键词、不看句子
     * 上下文，长对话里出现「今天天气不错」「帮我看看这段新闻文案」
     * 「我昨天用网页搜索查了资料」都会直接走端侧。迁移时替换掉它们，
     * 用户自建的 pattern 不动。
     */
    private val V15_REPLACED_PATTERNS: Map<String, Set<String>> = mapOf(
        "life.weather" to setOf(
            """(?:今天|明天|后天|现在|这几天|周末).{0,6}(?:天气|下雨|温度|气温)""",
        ),
        "info.news" to setOf(
            """(?:看看|看一下|查一下|搜一下|来点|说说|讲讲|播报).{0,6}(?:新闻|资讯|热点|头条)""",
        ),
        "info.stock" to setOf(
            """(?:上证|深证|创业板|沪深|沪指|大盘|A股|a股|股市|股票|股价).{0,8}(?:指数|行情|多少点|多少|涨|跌|怎么样|如何)""",
            """(?:今天|现在|目前).{0,6}(?:大盘|股市|A股).{0,8}(?:怎么样|如何|多少)""",
        ),
        "info.websearch" to setOf(
            """(?:帮我|给我|请|麻烦)?\s*(?:上网|在网上|网上|网页)\s*(?:搜|搜索|查)(?:索|一下)?\s*.{1,40}""",
            """(?:搜|搜索)\s*(?:网页|网页结果|网上)""",
            """(?:查|找)\s*(?:资料|教程|官网|文档|攻略)""",
        ),
    )

    /**
     * [T-local-flashlight] A sentence can mention the torch while discussing
     * the feature ("我们可以加个手电筒功能"). Those verbs/nouns keep the
     * flashlight routes from treating the mention as a command.
     */
    /**
     * [F-001] 讨论「写文件」这件事的句子不是写文件指令。用带语境的短语
     * 而不是裸词：真命令（「把日志写入文件」「修改 /tmp/a.txt」）照样命中
     * 本地。最多 20 项（AssistantRouteStore 的校验上限）。
     */
    private val FILE_WRITE_TOPIC_EXCLUDES = listOf(
        "写入经", "写入配置", "写进配置", "配置文件",
        "代码里", "代码中", "日志里", "日志中", "文档里", "文档中",
        "确认卡",
    )

    private val FLASHLIGHT_TOPIC_EXCLUDES = listOf(
        "加个", "添加", "增加", "支持", "功能", "开发", "实现",
        "讨论", "方案", "接口", "代码", "原理", "设计",
    )

    /**
     * [T-local-volume] 「声音设置」是打开设置页，不是调音量；「接口 / 方案 /
     * 实现」说明用户在聊这个能力。这些都留给云端或其他路由。
     * 最多 20 项（AssistantRouteStore 的校验上限）。
     */
    private val VOLUME_TOPIC_EXCLUDES = listOf(
        "音量设置", "声音设置", "打开设置", "设置页", "设置里", "设置界面", "系统设置",
        "声音模式", "铃声设置", "设置铃声", "更换铃声", "换铃声",
        "讨论", "方案", "接口", "代码", "实现", "开发", "功能", "原理",
    )

    private fun r(
        id: String,
        label: String,
        category: String,
        capability: String,
        action: String,
        priority: Int = 100,
        keywords: List<String> = emptyList(),
        allKeywords: List<String> = emptyList(),
        patterns: List<String> = emptyList(),
        exclude: List<String> = emptyList(),
        examples: List<String> = emptyList(),
        confirm: ConfirmMode = ConfirmMode.WRITE,
    ) = AssistantRoute(
        id = id,
        label = label,
        category = category,
        capability = capability,
        action = action,
        priority = priority,
        keywords = keywords,
        allKeywords = allKeywords,
        patterns = patterns,
        exclude = exclude,
        examples = examples,
        confirm = confirm,
    )

    val all: List<AssistantRoute> = listOf(
        // ── Existing local tools ───────────────────────────────────────
        r(
            "tool.shell", "Run shell command", "files_terminal", "shell_execute", "run", 120,
            listOf("运行", "执行", "跑一下", "跑个", "命令", "终端", "shell", "脚本", "run command", "execute"),
            examples = listOf("运行 ls -la", "执行 echo hello", "run ls -la"),
        ),
        r(
            "tool.file-read", "Read file", "files_terminal", "file_read", "read", 120,
            listOf("读一下", "读取", "打开文件", "看看文件", "文件内容", "读文件", "read file", "show file"),
            examples = listOf("读一下 /tmp/a.txt", "read file /tmp/a.txt"),
        ),
        r(
            "tool.file-write", "Write file", "files_terminal", "file_write", "write", 120,
            listOf("写入", "写文件", "保存到", "存到", "新建文件", "write file", "save to"),
            // [F-001] 讨论写文件这件事的句子（「写入经…」「写进配置文件」）
            // 不是写文件指令。
            exclude = FILE_WRITE_TOPIC_EXCLUDES,
            examples = listOf("把 hello 写入 /tmp/a.txt", "write hello to /tmp/a.txt"),
        ),
        r(
            "tool.file-edit", "Edit file", "files_terminal", "file_edit", "edit", 120,
            listOf("修改", "编辑文件", "改一下", "改成", "替换掉", "编辑", "edit file", "replace"),
            // [F-001] 同 tool.file-write：讨论「改文件」不是改文件指令。
            exclude = FILE_WRITE_TOPIC_EXCLUDES,
            examples = listOf("修改 /tmp/a.txt 里的 a 改成 b"),
        ),
        r(
            "tool.browser", "Open web page", "network", "browser_use", "navigate", 115,
            listOf("网页", "浏览器", "打开网址", "上网查", "搜网页", "open website", "browser"),
            examples = listOf("打开网页 https://example.com"),
        ),
        r(
            "tool.memory-write", "Save memory", "memory", "memory_write", "write", 130,
            listOf("记住", "记一下", "存进记忆", "别忘了", "remember that", "remember"),
            examples = listOf("记住我喜欢简短回答", "remember that I like tea"),
        ),
        r(
            "tool.memory-get", "Recall memory", "memory", "memory_get", "search", 125,
            listOf("我说过", "我的记忆", "之前说过", "查记忆", "what did I say", "my memory"),
        ),
        r(
            "tool.hub", "Remote device", "network", "hub_call", "call", 110,
            listOf("另一台电脑", "远程电脑", "那台机器", "远程执行", "remote computer", "other device"),
        ),
        r(
            "tool.read-image", "Read image", "files_terminal", "read_image", "read", 110,
            listOf("这张图", "看图", "图片内容", "识别图", "look at image", "read image"),
        ),
        r(
            "tool.amap", "Search nearby places", "network", "amap_search", "search", 90,
            listOf("附近", "周边", "周围", "旁边", "最近的", "找一家", "找个", "地址是", "怎么去", "nearby"),
        ),

        // ── Information: news and A-share quotes ───────────────────────
        // Read-only, key-free network tools. Both are also registered as agent
        // tools, so the cloud Agent and scheduled tasks can call them; these
        // routes make them reachable in assistant mode without a cloud round.
        r(
            "info.news", "Search news", "life", "news_search", "search", 168,
            listOf(
                "新闻", "资讯", "热点", "头条", "最新消息", "最新动态",
                "科技新闻", "财经新闻", "股票新闻", "财经消息", "股市消息", "娱乐新闻",
                "国际新闻", "国内新闻", "AI 动态", "最近发生", "最新情况",
                "news", "headlines", "latest news",
            ),
            patterns = listOf(
                """(?:今天|最近|现在|刚刚|这几天).{0,6}(?:有什么|有啥|发生|出).{0,4}(?:新闻|资讯|热点|头条)""",
                // [T-fastpath-topic-guard] 「看看 / 说说」只有在句末落点是
                // 新闻/资讯时才是指令：允许请求前缀，但目标词之后不能再有
                // 「文案怎么写 / 推送的产品设计」这类叙述。
                """^(?:帮我|给我|请|麻烦|帮忙|能不能|可以)?\s*(?:看看|看一下|查一下|搜一下|来点|说说|讲讲|播报).{0,6}(?:新闻|资讯|热点|头条)\s*$""",
                """(?:新闻|资讯|热点|头条).{0,6}(?:是什么|有哪些|怎么样)""",
                """\b(?:latest|today'?s|breaking)\s+news\b""",
                """\bnews\b.{0,12}\b(?:today|latest|headlines)\b""",
            ),
            exclude = listOf(
                "每天", "每周", "定时", "提醒我", "闹钟", "日程", "新闻学", "新闻专业",
                "新闻稿", "发布会", "微信", "短信", "邮件", "接口", "代码", "实现", "讨论", "方案",
            ),
            examples = listOf("今天有什么科技新闻", "帮我看看财经新闻"),
            confirm = ConfirmMode.NEVER,
        ),
        r(
            "info.stock", "Stock quotes", "life", "stock_quote", "quote", 158,
            listOf(
                "上证", "深证", "沪指", "大盘", "创业板", "沪深", "A股", "a股",
                "股市", "股价", "股票", "行情", "涨了", "跌了", "涨停", "跌停",
            ),
            patterns = listOf(
                """^(?:帮我|给我|请|麻烦|今天|现在|目前)?\s*(?:上证|深证|创业板|沪深|沪指|大盘|A股|a股|股市|股票|股价).{0,8}(?:指数|行情|多少点|多少|涨|跌|怎么样|如何)""",
                """^(?:帮我|给我|请|麻烦)?\s*(?:今天|现在|目前).{0,6}(?:大盘|股市|A股).{0,8}(?:怎么样|如何|多少)""",
                """\b(?:stock|index|market)\b.{0,20}\b(?:today|now|how|quote)\b""",
            ),
            exclude = listOf(
                "新闻", "资讯", "消息", "热点", "头条", "基金", "ETF", "etf",
                "开户", "交易", "定投", "怎么买", "讨论", "接口", "代码", "实现", "方案", "指数基金",
            ),
            examples = listOf("上证指数现在多少", "今天大盘怎么样"),
            confirm = ConfirmMode.NEVER,
        ),
        // [T-android-train-quote] 12306 余票：只读查询，永不确认。讨论
        // 「加个车票功能」或「怎么买/退票/改签」留在云端，不抢本地路由。
        r(
            "info.train", "Train tickets", "life", "train_12306", "query", 165,
            listOf(
                "车票", "车票余票", "高铁票", "动车票", "火车票", "余票", "买票", "抢票",
                "12306", "车次",
            ),
            patterns = listOf(
                """.*(?:车票|高铁票|动车票|火车票|余票).*""",
                """(?:查|看|搜|问).{0,8}(?:到|至|去).{0,12}(?:车票|高铁票|动车票|火车票|余票)""",
                """(?:车票|高铁票|动车票|火车票|余票).{0,8}(?:查询|查一下|有没有|还有吗|多少)""",
            ),
            exclude = listOf(
                "怎么买", "购票流程", "退票", "改签", "报销", "车票价格", "抢票软件",
                "讨论", "接口", "代码", "实现", "方案", "加个", "添加", "增加", "支持",
                "功能", "开发", "多少钱", "怎么去",
            ),
            examples = listOf("明天北京南到上海虹桥的车票", "查一下后天从张掖西到兰州西的余票"),
            confirm = ConfirmMode.NEVER,
        ),
        // [T-android-notification-summary] 最近通知摘要：只读、不确认。
        // 「通知设置」仍归 life.settings-open，讨论句留在云端。
        r(
            "info.notifications", "Recent notifications", "life", "notifications", "summary", 155,
            listOf(
                "读通知", "读一下通知", "念通知", "最新通知", "通知摘要", "谁发消息",
                "刚才谁发消息", "有什么通知", "未读通知", "通知消息",
            ),
            patterns = listOf(
                """(?:读|念|看|查|播报|说说).{0,4}通知""",
                """(?:最近|刚才|刚刚|今天).{0,6}(?:谁发消息|谁发来消息)""",
            ),
            exclude = listOf(
                "通知设置", "打开通知", "关闭通知", "通知栏", "通知权限", "通知样式",
                "讨论", "接口", "代码", "实现", "方案", "功能", "开发", "测试",
            ),
            examples = listOf("读一下通知", "刚才谁发消息"),
            confirm = ConfirmMode.NEVER,
        ),
        // [T-android-brave-web-search] 通用网页搜索：只返回搜索结果，不打开
        // 页面。优先级高于 tool.browser(115)，低于 info.news(168)；打开网址、
        // 新闻/股票/天气/导航等各有专线，这里全部排除。
        r(
            "info.websearch", "Web search", "network", "web_search", "search", 160,
            listOf(
                "搜网页", "搜索网页", "网页搜索", "网上搜", "上网搜", "网上查", "上网查",
                "帮我搜一下", "查资料",
            ),
            patterns = listOf(
                // [T-fastpath-topic-guard] 指令从句首开始、目标在句子里：
                // 长文里中部出现的「网页搜索」是话题提及。
                """^(?:帮我|给我|请|麻烦)?\s*(?:上网|在网上|网上|网页)\s*(?:搜|搜索|查)(?:索|一下)?\s*.{1,40}$""",
                """^(?:帮我|给我|请|麻烦)?\s*(?:搜|搜索)\s*(?:网页|网页结果|网上)\s*.{0,40}$""",
                """^(?:帮我|给我|请|麻烦)?\s*(?:查|找)\s*(?:资料|教程|官网|文档|攻略)\s*.{0,40}$""",
            ),
            // Route validation caps exclude terms at 20. The dropped shell /
            // file tokens cannot reach this route anyway: its patterns require
            // "上网/网上/网页 + 搜/查" or a "资料/教程/官网/文档/攻略" target,
            // and none of those shell words are web-search keywords.
            exclude = listOf(
                "打开", "浏览", "网址", "链接", "新闻", "资讯", "热点", "股票", "行情",
                "天气", "附近", "导航", "怎么去", "车票", "余票", "通知", "笔记", "联系人",
                "讨论", "功能",
            ),
            examples = listOf("帮我搜一下 Kotlin 协程教程", "上网查一下 Brave Search API 文档"),
            confirm = ConfirmMode.NEVER,
        ),

        // ── Life: alarm, reminder, calendar ────────────────────────────
        r(
            "life.alarm", "Set alarm", "life", "alarm", "set", 220,
            listOf("闹钟", "叫我起床", "叫我起来", "叫醒我", "alarm", "wake me up"),
            patterns = listOf("""(?:设|定|开|加).{0,6}(?:闹钟|alarm)"""),
            exclude = listOf(
                "取消闹钟", "关掉闹钟", "关闭闹钟", "打开闹钟", "查看闹钟", "我的闹钟",
                "cancel alarm", "open clock", "show alarms",
            ),
            examples = listOf("明天早上7点设个闹钟", "wake me up at 7am"),
        ),
        r(
            "life.timer", "Start timer", "life", "alarm", "timer", 215,
            listOf("计时器", "倒计时", "计时", "timer", "countdown"),
            examples = listOf("开始一个25分钟计时器", "start a 10 minute timer"),
        ),
        r(
            "life.alarm-open", "Open Clock", "life", "alarm", "open", 200,
            listOf(
                "打开闹钟", "查看闹钟", "我的闹钟", "关闭闹钟", "取消闹钟", "关掉闹钟",
                "open clock", "show alarms", "cancel alarm",
            ),
        ),
        r(
            "life.reminder", "Schedule reminder", "life", "reminder", "schedule", 230,
            listOf("提醒我", "提醒一下", "到点提醒", "记得提醒", "remind me", "reminder"),
            exclude = listOf("闹钟", "alarm"),
            examples = listOf("30分钟后提醒我开会", "remind me at 9 to call mom"),
        ),
        r(
            "life.reminder-pending", "Pending reminders", "life", "reminder", "pending", 210,
            listOf("待触发提醒", "未触发提醒", "待办提醒", "pending reminders"),
        ),
        r(
            "life.reminder-list", "List reminders", "life", "reminder", "list", 195,
            listOf(
                "提醒列表", "我的提醒", "查看提醒", "打开提醒",
                "list reminders", "show reminders",
            ),
        ),
        r(
            "life.reminder-cancel", "Cancel reminder", "life", "reminder", "cancel", 205,
            listOf("取消提醒", "删掉提醒", "cancel reminder"),
        ),
        r(
            "life.calendar-create", "Create calendar event", "life", "calendar", "create", 210,
            listOf(
                "安排日程", "创建日程", "新建日程", "加个会议", "安排会议",
                "calendar add", "schedule event", "schedule a meeting", "schedule meeting",
                "create event", "add event",
            ),
            patterns = listOf(
                """(?:安排|新建|创建|添加|加).{0,12}(?:日程|会议|事件)""",
                """(?:日程|会议|calendar).{0,8}(?:安排|新建|创建)""",
                """(?:schedule|create|add).{0,16}(?:meeting|event)""",
            ),
            examples = listOf("明天下午3点安排会议", "schedule a meeting tomorrow at 3"),
        ),
        r(
            "life.calendar-list", "List calendar", "life", "calendar", "list", 180,
            listOf(
                "查看日历", "打开日历", "我的日程", "今天日程", "明天安排", "有什么安排",
                "查看日程", "我的日历", "calendar", "my schedule",
            ),
            patterns = listOf("""(?:今天|明天|后天|本周|下周).{0,6}(?:日程|安排|会议)"""),
        ),
        r(
            "life.calendar-freebusy", "Calendar availability", "life", "calendar", "freebusy", 185,
            listOf("有空吗", "空闲时间", "什么时候有空", "空闲查询", "free time", "availability"),
        ),
        r(
            "life.calendar-delete", "Delete calendar event", "life", "calendar", "delete", 215,
            listOf("删除日程", "取消日程", "删掉会议", "取消会议", "delete event", "cancel meeting"),
        ),
        r(
            "life.calendar-update", "Update calendar event", "life", "calendar", "update", 205,
            listOf("修改日程", "改会议时间", "推迟会议", "更新日程", "update event", "reschedule meeting"),
        ),

        // ── Life: notes ───────────────────────────────────────────────
        r(
            "life.note-create", "Create note", "life", "note", "create", 225,
            listOf("记笔记", "做个笔记", "记个笔记", "备忘", "记事本", "新建笔记", "take a note", "note this"),
            exclude = listOf("记一下", "记住", "remember"),
            patterns = listOf("""(?:记|写|新建|创建).{0,4}(?:笔记|备忘)"""),
            examples = listOf("记笔记：明天买牛奶", "take a note: buy milk"),
        ),
        r(
            "life.note-search", "Search notes", "life", "note", "search", 205,
            listOf("搜笔记", "找笔记", "查找笔记", "搜索笔记", "search notes", "find note"),
        ),
        r(
            "life.note-list", "List notes", "life", "note", "list", 195,
            listOf("我的笔记", "笔记列表", "查看笔记", "打开笔记", "list notes", "show notes"),
        ),
        r(
            "life.note-delete", "Delete note", "life", "note", "delete", 205,
            listOf("删除笔记", "删掉笔记", "delete note"),
        ),

        // ── Life: settings, scheduled tasks, curated handlers ──────────
        // [T-settings-open-intent-guard] 关键词一律带意图动词。
        //
        // 这里曾经收录裸名词「系统设置」「电池设置」等，而匹配器是
        // 「文本包含关键词即命中」——只要一句话里出现「系统设置」四个字
        // （「你测试一下系统设置执行生效了没有」），快速路径就真的去打开
        // 设置页，用户说的明明是另一件事。设置页是给别人看的界面，
        // 不是一句话里顺带提到软件功能就该弹出的东西。
        //
        // 现在只有「打开/进入/去 + 设置」这类明确命令才命中；不确定的
        // 一律留给我们后面的云端模型（它能读上下文）。patterns 兜住中间
        // 多几个字的说法（「打开一下系统设置」）。
        r(
            "life.settings-open", "Open system settings", "life", "settings", "open", 220,
            listOf(
                // 通用设置页
                "打开设置", "进入设置", "去设置", "设置页面", "设置界面",
                "打开系统设置", "进入系统设置", "去系统设置",
                "系统设置页", "系统设置页面", "系统设置界面",
                // 具体子页
                "打开wifi设置", "进入wifi设置", "打开wi-fi设置", "打开wlan设置",
                "打开蓝牙设置", "进入蓝牙设置",
                "打开飞行模式设置", "进入飞行模式设置",
                "打开电池设置", "进入电池设置", "电池设置页",
                "打开显示设置", "进入显示设置",
                "打开声音设置", "进入声音设置",
                "打开位置设置", "进入位置设置",
                "打开通知设置", "进入通知设置",
                "打开无障碍设置", "进入无障碍设置",
                "打开应用详情", "进入应用详情", "应用详情页",
                "打开勿扰模式设置", "进入勿扰模式设置",
                "打开自动旋转设置", "进入自动旋转设置",
                "打开亮度设置", "进入亮度设置",
                "打开息屏设置", "进入息屏设置",
                // English
                "open settings", "open system settings", "go to system settings",
                "system settings page", "open wifi settings", "open bluetooth settings",
            ),
            patterns = listOf(
                """(?:打开|进入|前往|跳到|跳转|去|查看|看看)\s*.{0,4}(?:系统|应用)?设置(?:页|页面|界面)?""",
                """(?:open|launch|go\s+to|show\s+me)\s+(?:the\s+)?(?:system\s+|app\s+)?settings""",
            ),
            examples = listOf("打开WiFi设置", "open bluetooth settings"),
        ),
        r(
            "life.settings-open-english", "Open system settings (English)", "life",
            "settings", "open", 220,
            listOf(
                "open brightness settings", "open rotation settings",
                "open screen timeout settings", "open dnd settings",
                "open do not disturb settings",
            ),
        ),
        r(
            "life.settings-set", "Change system setting", "life", "settings", "set", 235,
            listOf(
                "亮度调到", "调高亮度", "调低亮度", "息屏时间", "屏幕超时", "自动旋转",
                "勿扰模式", "飞行模式", "打开wifi", "关闭wifi", "打开蓝牙", "关闭蓝牙",
                "set brightness", "screen timeout", "do not disturb", "turn on wifi", "turn off wifi",
            ),
            exclude = listOf(
                "打开设置", "open settings", "wifi设置", "wi-fi设置",
                "蓝牙设置", "wifi settings", "wi-fi settings", "bluetooth settings",
                "勿扰模式设置", "自动旋转设置", "亮度设置", "息屏设置",
                "brightness settings", "rotation settings", "screen timeout settings",
                "dnd settings", "do not disturb settings",
            ),
            examples = listOf("亮度调到 50%", "打开勿扰模式", "turn off wifi"),
        ),
        r(
            "life.scheduled-create", "Create scheduled task", "life", "scheduled_task", "create", 190,
            listOf("定时任务", "定时帮我", "每天帮我", "每周帮我", "schedule task", "every day help me"),
            patterns = listOf(
                """(?:每天|每日|每周|每个工作日|工作日).{0,10}(?:帮我|替我|提醒我|定时)""",
                """(?:every\s+day|daily|every\s+week|weekdays|every\s+(?:mon|tue|wed|thu|fri|sat|sun)[a-z]*).{0,30}(?:help\s+me|remind\s+me|run|send|summarize)""",
                """(?:remind\s+me|help\s+me).{0,24}(?:every\s+day|daily|every\s+week|weekdays|every\s+(?:mon|tue|wed|thu|fri|sat|sun)[a-z]*)""",
            ),
        ),
        r(
            "life.scheduled-list", "List scheduled tasks", "life", "scheduled_task", "list", 180,
            listOf("定时任务列表", "我的定时任务", "scheduled tasks", "list scheduled tasks"),
        ),
        r(
            "life.scheduled-cancel", "Cancel scheduled task", "life", "scheduled_task", "cancel", 200,
            listOf("取消定时任务", "删掉定时任务", "cancel scheduled task"),
        ),
        r(
            "life.weather", "Weather", "life", "weather", "current", 160,
            listOf("天气", "下雨", "温度", "气温", "weather", "forecast"),
            // 只出现「天气」两个字不等于是问天气。真正的问句/指令会命中
            // 下面这些模式；单纯提到天气的长句子由 AssistantRouter 的
            // 话题提及保护挡回云端（例如「可以直接对接高德地图的天气接口」）。
            //
            // [T-fastpath-topic-guard] 时间词模式必须带疑问/请求尾巴：
            // 「今天天气不错，我们下午去公园走走吧」是在聊天气，不是在问。
            patterns = listOf(
                """^(?:帮我|给我|请|麻烦|能不能|可以)?\s*(?:今天|明天|后天|现在|这几天|周末).{0,6}(?:天气|下雨|气温|温度).{0,8}(?:怎么样|如何|多少|几度|吗|呢|需要|带伞|穿什么|要不要|有哪些)""",
                """(?:天气|下雨|温度|气温).{0,8}(?:怎么样|如何|多少度|多少|几度|带伞|穿什么)""",
                """\b(?:what'?s|whats|how'?s|hows)\b.{0,16}\bweather\b""",
                """\bweather\b.{0,16}\b(?:today|tomorrow|now|this week)\b""",
            ),
            examples = listOf("今天天气怎么样", "what's the weather"),
        ),
        r(
            "life.device-battery", "Battery status", "life", "device", "battery", 150,
            listOf("电量", "电池", "还有多少电", "battery", "battery level"),
        ),
        r(
            "life.device-storage", "Storage status", "life", "device", "storage", 150,
            listOf("存储空间", "剩余空间", "磁盘空间", "storage", "disk space"),
        ),
        r(
            "life.device-info", "Device info", "life", "device", "info", 140,
            listOf("设备信息", "手机型号", "系统版本", "device info", "android version"),
        ),
        // [T-local-flashlight] Torch on/off/status — NEVER confirms: the LED is
        // instantly reversible and the user asked for it directly.
        r(
            "life.flashlight-on", "Turn on flashlight", "life", "flashlight", "on", 160,
            listOf(
                "打开手电筒", "开启手电筒", "开手电筒", "点亮手电筒",
                "打开闪光灯", "开启闪光灯", "开闪光灯",
                "turn on flashlight", "turn on torch", "turn on the flashlight",
                "turn on the torch", "switch on the flashlight", "flashlight on", "torch on",
            ),
            patterns = listOf(
                """(手电筒|闪光灯).{0,6}(打开|开启|点亮|开)""",
                """(打开|开启|点亮).{0,6}(手电筒|闪光灯)""",
            ),
            exclude = FLASHLIGHT_TOPIC_EXCLUDES,
            examples = listOf("打开手电筒", "turn on the flashlight"),
            confirm = ConfirmMode.NEVER,
        ),
        r(
            "life.flashlight-off", "Turn off flashlight", "life", "flashlight", "off", 160,
            listOf(
                "关闭手电筒", "关掉手电筒", "关手电筒", "熄灭手电筒",
                "关闭闪光灯", "关掉闪光灯", "关闪光灯", "熄灭闪光灯",
                "turn off flashlight", "turn off torch", "turn off the flashlight",
                "turn off the torch", "switch off the flashlight", "flashlight off", "torch off",
            ),
            patterns = listOf(
                """(手电筒|闪光灯).{0,6}(关闭|关掉|关上|熄灭|关)""",
                """(关闭|关掉|熄灭).{0,6}(手电筒|闪光灯)""",
            ),
            exclude = FLASHLIGHT_TOPIC_EXCLUDES,
            examples = listOf("关掉手电筒", "turn off the flashlight"),
            confirm = ConfirmMode.NEVER,
        ),
        r(
            "life.flashlight-status", "Flashlight status", "life", "flashlight", "status", 140,
            listOf("手电筒", "闪光灯", "torch", "flashlight"),
            patterns = listOf(
                """(手电筒|闪光灯).*(状态|开着|关了|亮着|开没开)""",
            ),
            exclude = FLASHLIGHT_TOPIC_EXCLUDES,
            examples = listOf("手电筒", "手电筒现在开着吗"),
            confirm = ConfirmMode.NEVER,
        ),
        // [T-local-volume] 音量快路径：端侧规则命中后进程内直调 AudioManager，
        // 不上云、不弹确认卡（音量随时可以改回，和手电筒同级）。
        // 只认「音量 / 声音 / 铃声 + 明确动作」；「声音设置」归 life.settings-open，
        // 讨论句和「调到 60 分」这类错误单位留给云端。
        r(
            "life.volume-set", "Set volume", "life", "volume", "set", 160,
            listOf(
                "音量调到", "音量调至", "音量调成", "音量设为", "音量设成", "音量改成",
                "音量最大", "音量最小", "音量最响", "音量最轻", "媒体音量", "音乐音量",
                "把音量", "铃声音量", "铃声调到", "铃声最大", "铃声最小",
                "声音调到", "声音最大", "声音最小",
            ),
            patterns = listOf(
                """(?:音量|声音|响度|铃声).{0,8}(?:调到|调至|调成|设成|设为|设置为|改成|改为|变成|变到)\s*(?:百分之[零一二两三四五六七八九十百]{1,4}|\d{1,3}\s*(?:%|$|[,.!?]))""",
                """(?:音量|声音|响度|铃声)\s*(?:百分之[零一二两三四五六七八九十百]{1,4}|\d{1,3}\s*%)""",
                """(?:音量|声音|响度|铃声).{0,6}(?:最大|最响|最轻|最小)""",
            ),
            exclude = VOLUME_TOPIC_EXCLUDES,
            examples = listOf("音量调到百分之六十", "把铃声调到 50%", "音量调到最大"),
            confirm = ConfirmMode.NEVER,
        ),
        r(
            "life.volume-adjust", "Adjust volume", "life", "volume", "adjust", 160,
            listOf(
                "调大音量", "调小音量", "音量调大", "音量调小", "音量加大", "音量减小",
                "音量增大", "音量降低", "音量调高", "音量调低", "声音调大", "声音调小",
                "声音大一点", "声音小一点", "声音大点", "声音小点", "大声点", "小声点",
                "大点声", "小点声",
            ),
            patterns = listOf(
                """(?:音量|声音|响度|铃声).{0,6}(?:调大|调小|增大|减小|加大|提高|升高|上调|降低|调低|下调|大一点|小一点|大一些|小一些|大点|小点|大很多|小很多|高一点|低一点)""",
                """(?:调大|调小|增大|减小|加大|提高|升高|上调|降低|调低|下调|大点声|小点声|大声点|小声点).{0,6}(?:音量|声音|响度)""",
                """^(?:帮我|给我|请|麻烦)?\s*(?:把)?\s*(?:音量|声音|铃声)?\s*(?:调大|调小|增大|减小|加大|提高|升高|上调|降低|调低|下调|大点声|小点声|大声点|小声点)\s*(?:一点|一些|点儿|点|很多|大幅|大量)?\s*$""",
            ),
            exclude = VOLUME_TOPIC_EXCLUDES,
            examples = listOf("音量调大一点", "音量调小 20%", "大点声"),
            confirm = ConfirmMode.NEVER,
        ),
        r(
            "life.volume-status", "Volume status", "life", "volume", "status", 165,
            listOf(
                "音量多少", "当前音量", "现在音量", "音量是", "音量多大", "声音多大",
                "音量大小", "音量状态", "铃声音量", "媒体音量", "音乐音量",
            ),
            patterns = listOf(
                """(?:音量|声音|铃声).{0,4}(?:多少|多大|几档|是几|当前|现在|状态)""",
            ),
            exclude = VOLUME_TOPIC_EXCLUDES,
            examples = listOf("现在音量多少", "媒体音量是几档"),
            confirm = ConfirmMode.NEVER,
        ),
        r(
            "life.media-pause", "Pause media", "life", "media", "pause", 145,
            listOf("暂停播放", "暂停音乐", "pause music", "pause playback"),
            // [T-music-player-top] Pausing is a transient state change, not
            // a data write: it must not raise a confirmation card.
            confirm = ConfirmMode.NEVER,
        ),
        r(
            "life.media-resume", "Resume media", "life", "media", "resume", 145,
            listOf("继续播放", "恢复播放", "resume music", "resume playback"),
            confirm = ConfirmMode.NEVER,
        ),
        r(
            "life.media-stop", "Stop media", "life", "media", "stop", 145,
            listOf("停止播放", "关闭音乐", "stop music", "stop playback"),
            confirm = ConfirmMode.NEVER,
        ),
        r(
            "life.media-status", "Media status", "life", "media", "status", 140,
            listOf("播放状态", "音乐状态", "media status", "playback status"),
        ),
        r(
            "life.media-list", "List media sessions", "life", "media", "list", 135,
            listOf("播放列表", "媒体会话", "media sessions", "list playback"),
        ),
        // [T-music-player-top] The in-app music player: 「放一首周杰伦的稻香」
        // searches the NetEase catalogue on the buntu host and starts
        // playing; the mini player slides in at the top of the chat.
        r(
            "life.music-play", "Play music", "life", "media", "play", 150,
            listOf(
                "播放音乐", "放首歌", "放一首", "放首歌儿", "来首歌", "来一首",
                "听首歌", "听一首", "唱一首", "放点歌", "play music", "play a song",
            ),
            // The patterns are anchored: a command is a short sentence that
            // STARTS with the verb. Without the anchor 「音乐播放逻辑」 would
            // look like a request to play a song called 逻辑.
            patterns = listOf(
                """^(?:帮我|给我|请|帮忙|麻烦|能不能|可以|我想|我要|想|要)?\s*(?:播放|放|来|想听|要听|听|唱)\s*一?[首下]\s*[\p{L}0-9_·\s]{0,20}$""",
                """^(?:帮我|给我|请|帮忙|麻烦|能不能|可以)?\s*(?:播放|放)\s*[\p{L}0-9_·\s]{1,16}(?:的歌|歌曲|曲子|音乐)$""",
                """^(?:帮我|给我|请|帮忙|麻烦|能不能|可以)?\s*(?:播放|放)\s*[\p{L}0-9_·\s]{1,10}$""",
                """^(?:please\s+)?play\s+[\p{L}0-9_' -]{1,40}$""",
            ),
            // 「播放器的设计」这类句子里有「播放」，但那是话题不是指令。
            // 最多 20 个排除词（AssistantRouteStore 的校验上限）。
            exclude = listOf(
                "设计", "方案", "接口", "代码", "实现", "优化", "排查", "日志",
                "讨论", "开发", "架构", "重构", "原理", "为什么", "是什么",
                "逻辑", "播放器", "播放列表", "播放状态", "参数",
            ),
            confirm = ConfirmMode.NEVER,
        ),
        r(
            "life.music-next", "Next track", "life", "media", "next", 146,
            listOf("下一首", "下一首歌", "换一首", "next track", "next song"),
            confirm = ConfirmMode.NEVER,
        ),
        r(
            "life.music-prev", "Previous track", "life", "media", "previous", 146,
            listOf("上一首", "上一首歌", "previous track", "previous song"),
            confirm = ConfirmMode.NEVER,
        ),
        r(
            "life.contacts", "Call contact", "life", "contacts", "dial", 210,
            listOf("打电话给", "拨打", "给...打电话", "call "),
            // [F-004] `call` 必须带词边界：工具名 `hub_call` 里的 "call"
            // 是「调用工具」，不是「打电话」。
            patterns = listOf("""(?:打电话给|拨打|(?<![A-Za-z0-9_])call\b)\s*[\p{L}0-9_·]{1,30}"""),
            exclude = listOf("打开电话", "话费", "提醒", "remind", "hub_call", "shell", "command", "工具"),
        ),
        r(
            "life.contacts-search", "Search contacts", "life", "contacts", "search", 170,
            listOf("搜索联系人", "查找联系人", "找联系人", "search contacts", "find contact"),
        ),
        r(
            "life.app-open", "Open app", "life", "app_launch", "open", 70,
            listOf("打开应用", "启动应用", "启动app", "打开app", "打开微信", "打开支付宝", "launch app"),
            exclude = listOf("打开设置", "打开网页", "打开文件", "打开日历"),
        ),
    )

    /**
     * Add newly introduced defaults without replacing user edits, and remove
     * only the retired media route that was split into pause/resume/stop/
     * status/list. A custom route with a different id is never touched.
     */
    fun migrate(
        loaded: List<AssistantRoute>,
        fromVersion: Int,
    ): List<AssistantRoute> {
        var routes = loaded
        if (fromVersion < 3) {
            routes = routes.filterNot {
                it.id in RETIRED_AT_V3 && it.capability == "media"
            }
        }
        if (fromVersion < 4) {
            // v4 adds question/command patterns to the built-in weather route.
            // Merge rather than replace: a user who added keywords to this
            // route keeps them, but the new patterns start deciding whether a
            // bare mention of 天气 is a command or just a topic.
            val weather = all.firstOrNull { it.id == "life.weather" }
            if (weather != null) {
                routes = routes.map { route ->
                    if (route.id != "life.weather") {
                        route
                    } else {
                        route.copy(
                            keywords = (route.keywords + weather.keywords).distinct(),
                            patterns = (route.patterns + weather.patterns).distinct(),
                        )
                    }
                }
            }
        }
        if (fromVersion < 13) {
            // v13 adds prose-context exclude terms to the built-in file-write
            // and file-edit routes (F-001). Merge rather than replace, like v4
            // did for weather: a user who edited these routes keeps their own
            // terms, but the new excludes start deciding whether 「写入」 is an
            // instruction or just a topic mention.
            for (id in listOf("tool.file-write", "tool.file-edit")) {
                val builtin = all.firstOrNull { it.id == id } ?: continue
                routes = routes.map { route ->
                    if (route.id != id) {
                        route
                    } else {
                        route.copy(exclude = (route.exclude + builtin.exclude).distinct())
                    }
                }
            }
        }
        if (fromVersion < 14) {
            // v14 puts a word boundary on "call" in the built-in contacts
            // route (F-004): `hub_call` is a tool call, not a phone call.
            // The old built-in pattern is retired and replaced — merging it
            // would keep matching `hub_call ...` — while any pattern the
            // user added survives.
            val contacts = all.firstOrNull { it.id == "life.contacts" }
            if (contacts != null) {
                routes = routes.map { route ->
                    if (route.id != "life.contacts") {
                        route
                    } else {
                        route.copy(
                            patterns = (route.patterns - RETIRED_CONTACT_PATTERNS) + contacts.patterns,
                            exclude = (route.exclude + contacts.exclude).distinct(),
                        )
                    }
                }
            }
        }
        if (fromVersion < 15) {
            // [T-fastpath-topic-guard] v15 收紧只读检索/状态类路由的模式：
            // 句首锚定 + 句尾落点，把「今天天气不错」「帮我看看这段新闻
            // 文案」「我昨天用网页搜索查了资料」还给云端。旧内置模式被
            // 替换（合并会让它们继续误命中），用户自建的模式保留。
            for ((id, retired) in V15_REPLACED_PATTERNS) {
                val builtin = all.firstOrNull { it.id == id } ?: continue
                routes = routes.map { route ->
                    if (route.id != id) {
                        route
                    } else {
                        route.copy(
                            patterns = ((route.patterns - retired) + builtin.patterns)
                                .distinct()
                                .take(AssistantRouteStore.MAX_PATTERNS),
                        )
                    }
                }
            }
        }
        if (fromVersion < SEED_VERSION) {
            val existing = routes.mapTo(hashSetOf()) { it.id }
            routes = routes + all.filter { it.id !in existing }
        }
        return routes
    }
}

package com.openminis.app.data.repository.memory

/**
 * Maps entity aliases in an entry (or query) to canonical tags.
 *
 * Tags are a light-weight signal that bridges wording differences the
 * bigram tokenizer cannot: "小窗" and "freeform" both tag `freeform`, "玛格派"
 * and "magpie" both tag `magpie`. The alias list is intentionally small and
 * hand-written; it is not a knowledge base and should not be expanded with
 * speculative synonyms.
 */
object MemoryTagger {
    private val ENTITY_ALIASES: Map<String, List<String>> = linkedMapOf(
        "magpie" to listOf("magpie", "玛格派"),
        "buntu" to listOf("buntu"),
        "asus" to listOf("asus", "华硕"),
        "tailscale" to listOf("tailscale", "tailnet"),
        "freeform" to listOf("freeform", "小窗", "浮窗"),
        "crlf" to listOf("crlf", "回车换行"),
        "mcp" to listOf("mcp", "minis-mcp-cli"),
        "wechat" to listOf("微信", "wechat"),
        "morning" to listOf("早安", "问候"),
        "stock" to listOf("a股", "股票", "行情"),
        "music" to listOf("音乐", "网易云", "netease"),
        "amap" to listOf("高德", "百度地图", "amap", "导航"),
        "weather" to listOf("天气", "weather"),
        "voice" to listOf("语音", "唤醒", "asr", "sensevoice", "tts", "播报"),
        "guard" to listOf("guard", "审计", "audit", "风险"),
        "skillstore" to listOf("skillstore", "技能轨迹", "轨迹"),
        "virtualscreen" to listOf("虚拟屏", "virtual display", "vdctl"),
        "debug" to listOf("调试", "debugserver", "minis-debug", "debug."),
        "schedule" to listOf("定时任务", "scheduled", "签到", "cron"),
        "ssh" to listOf("ssh", "密钥"),
        "wol" to listOf("wol", "远程唤醒", "开机"),
        "network" to listOf("ping", "icmp", "nc ", "端口", "网络"),
        "input" to listOf("input", "按键", "tab", "dpad", "keyevent"),
        "compose" to listOf("compose", "表单", "输入框"),
        "webview" to listOf("webview", "浏览器", "chrome", "chromium"),
        "google" to listOf("google", "谷歌"),
        "hub" to listOf("hub_call", "hub", "relay"),
        "build" to listOf("构建", "编译", "assemble", "gradle"),
        "memory" to listOf("记忆", "global", "注入", "检索"),
        "phone" to listOf("手机号", "号码", "workbuddy"),
        "candor" to listOf("直言", "附和", "讨好"),
        "confirm" to listOf("确认卡", "确认", "破坏性"),
    )

    private val LOWERED_ALIASES: Map<String, List<String>> =
        ENTITY_ALIASES.mapValues { (_, aliases) -> aliases.map { it.lowercase() } }

    fun tagsFor(text: String): Set<String> {
        if (text.isEmpty()) return emptySet()
        val lower = text.lowercase()
        val out = LinkedHashSet<String>()
        for ((canonical, aliases) in LOWERED_ALIASES) {
            if (aliases.any { lower.contains(it) }) out.add(canonical)
        }
        return out
    }

    /**
     * Normalise a model-supplied `Tags:` line to the closed vocabulary above.
     *
     * Unknown words are dropped instead of being stored: free-form tags are
     * just query-independent noise in BM25, and a tag that no future query can
     * produce is worse than no tag. Alias matching is deliberately conservative
     * (exact token, or a token that contains an alias of at least 3 chars).
     */
    fun canonicalize(raw: String): Set<String> {
        val tokens = raw.lowercase()
            .split(Regex("[,，、\\s]+"))
            .map { it.trim('#', ':', '-') }
            .filter { it.length >= 2 }
        if (tokens.isEmpty()) return emptySet()
        val out = LinkedHashSet<String>()
        for ((canonical, aliases) in LOWERED_ALIASES) {
            if (tokens.any { token ->
                token == canonical || aliases.any { alias ->
                    token == alias || (alias.length >= 3 && token.contains(alias))
                }
            }) out.add(canonical)
        }
        return out
    }

    /** Lines the structured-write format uses; hidden from body comparison. */
    fun isMetaLine(line: String): Boolean =
        line.startsWith("Summary:") || line.startsWith("Tags:")
}

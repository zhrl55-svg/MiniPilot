package com.openminis.app.tools

import org.json.JSONArray
import org.json.JSONObject

/**
 * 口语地点查询的归一化与同义展开。
 *
 * 「附近有什么好吃的」这类句子对地图 API 是没有意义的：高德只认 POI 关键词，
 * `有什么` 和 `好吃` 都不是地点。原样丢给 around 接口只会回来 0 条，
 * 用户看到的是一句「附近没有找到」。
 *
 * 这里做三件事：
 *
 *  1. [normalize] —— 剥掉疑问句的壳，只留下用户真正要找的东西；
 *  2. [plan] —— 把「好吃」这类泛指展开成高德认识的品类词（餐厅/饭店/小吃），
 *     同时把修饰词（面馆、火锅…）留在最前面；
 *  3. [isPlaceKeyword] —— 判断剥完之后剩下的是不是一个地点关键词。一整句话
 *     （「我说搜下加油站…然后这一段长文字也走快路径」）剥完还有 39 个字，
 *     拿去搜只会得到 0 条 —— 这种一律交回云端，绝不截断后硬搜。
 *
 * 词表对齐沙箱里那份实测过的 amap.py（`normalize_query()` / `expand_query()`），
 * 端侧（本地路由）与云端（模型调用）用同一套口径，同一句话在两端结果才一致。
 *
 * 纯逻辑，不碰网络 —— 单元测试可以整段覆盖。
 */
object AmapPlaceQuery {

    /**
     * 疑问/方位/客套等填充词：出现在查询里不代表用户要找这类东西。
     *
     * 剥离时按长度从长到短替换 ——「有什么」必须先于「什么」被剥掉，
     * 否则会留下一个孤零零的「什么」。
     *
     * 同长度的词按**表里的先后**剥（排序是稳定的）：「搜一下」必须先于
     * 「帮我搜」，否则「帮我搜一下加油站」会先被剥成「帮我 一下加油站」，
     * 剩下一个孤零零的「一下」。
     */
    val FILLER_WORDS: List<String> = listOf(
        // 方位
        "附近", "周边", "周围", "旁边", "最近", "离我最近",
        // 疑问
        "有什么", "有哪些", "有啥", "有来", "有没有", "有几家", "在哪有",
        "哪里", "哪儿", "哪家", "哪个", "什么",
        // 动作与客套
        "找一家", "找个", "找一下", "找找", "找点", "看看", "查一下", "推荐",
        "请问", "帮我", "帮忙", "麻烦", "我想", "我要", "想要", "给我", "能不能",
        // 搜索动作
        "搜一下", "搜下", "搜搜", "帮我搜", "查查", "看一看",
        // 其它
        "地址是", "怎么去", "nearby", "near me",
    )

    /**
     * 泛指 → 高德认识的品类词。
     *
     * 「馆子」只映射餐厅/饭店（不加小吃）：小吃摊跟「馆子」不是一回事。
     *
     * 注意不要加裸的「吃」：那会命中「小吃」「吃面」「吃货」里的「吃」，
     * 把「附近有小吃」当成泛指，剩下一个没意义的「小」。
     */
    val FOOD_ALIASES: Map<String, List<String>> = mapOf(
        "好吃的" to listOf("餐厅", "饭店", "小吃"),
        "好吃" to listOf("餐厅", "饭店", "小吃"),
        "美食" to listOf("餐厅", "饭店", "小吃"),
        "美味" to listOf("餐厅", "饭店", "小吃"),
        "吃的" to listOf("餐厅", "饭店", "小吃"),
        "馆子" to listOf("餐厅", "饭店"),
    )

    /**
     * 明确的餐饮品类词。
     *
     * 用户直接说「附近的餐厅」时也要按餐饮过滤 —— 「XX 餐饮管理有限公司」
     * 这种公司类结果不该出现在餐厅列表里。
     */
    val FOOD_CATEGORY_TERMS: Set<String> = setOf(
        "餐厅", "饭店", "小吃", "面馆", "餐馆", "菜馆", "食府", "美食城", "夜市",
        "食堂", "快餐", "火锅", "烧烤", "粥铺", "饺子馆", "咖啡厅", "咖啡馆",
        "茶馆", "茶餐厅", "酒吧",
    )

    /** 剥掉之后仍然不像 POI 关键词的残留（宁可少搜一次，也不要搜出个「地方」）。 */
    private val JUNK_REMAINDERS = setOf("地方", "东西", "店", "些", "点", "我", "你", "它")

    /** 修饰词至少这么长才算关键词 —— 单个字（「吃」「玩」）搜不出东西。 */
    private const val MIN_SPECIFIC_LENGTH = 2

    /**
     * POI 关键词最长到这个长度；更长就当句子处理，不再当关键词用。
     *
     * 端侧抽槽（[com.openminis.app.assistant.routing.SlotExtractor]）和
     * 这里的 [plan] 共用这一个阈值 —— 两端口径必须一致，同一句话在两端
     * 才会有同样的结果。
     */
    const val MAX_QUERY_LENGTH = 16

    /**
     * 分句标点：出现任何一个，说明这是一句话，不是一个地点关键词。
     *
     * 全角和半角都算 —— 端侧抽槽前文本已经被归一化过（「，」→「,」），
     * 所以这里两套都得认。
     */
    private val CLAUSE_PUNCTUATION = setOf(
        '，', ',', '。', '.', '！', '!', '？', '?', '；', ';', '、',
    )

    private val CJK_REGEX = Regex("""[一-鿿]""")

    /** 汉字之间的空白：中文里没有词间空格，留着只会让泛指匹配不上。 */
    private val CJK_SPACE_REGEX = Regex("""(?<=[一-鿿])\s+(?=[一-鿿])""")

    /** 语气词/连接词：留下任何一个，高德都搜不出来。 */
    private val PARTICLE_REGEX = Regex("""(?:吗|呢|啊|吧|的|了|哦|呀|哟)""")

    /** 句首的「有」是存在动词（「附近有超市」），不是地点的一部分。 */
    private val LEADING_HAVE_REGEX = Regex("""(^|\s)有""")

    /**
     * 查询词**开头**的搜索动词短语。
     *
     * 词表剥的是标准说法（「搜一下」「查一下」），但语音识别会写成「搜一上」
     * 「查一查」这类变体 —— 词表追不上。所以这里按**结构**再剥一次：
     * 动词（可带「一」）+ 尾巴（下/上/个/同类动词/索/见）。
     *
     * 只剥开头：动词出现在开头才说明是命令。地名里带这些字是常事 ——
     * 「鑫看看便利店」中间的「看看」不能动（词表已单独处理标准的「看看」）。
     * 尾巴是**必需**的，所以「看守所」「查尔斯酒店」这种以动词字开头的
     * 地名不会被啃掉开头。
     */
    private val LEADING_VERB_REGEX = Regex(
        """^\s*(?:搜|查|找|看)(?:一)?(?:下|上|个|搜|查|找|看|索|见)""",
    )

    /**
     * 光秃秃一个动词：「找加油站」「搜药店」。
     *
     * 只认「搜」「找」—— 「看」会啃掉「看守所」，「查」会啃掉「查尔斯酒店」。
     */
    private val LEADING_BARE_VERB_REGEX = Regex("""^\s*(?:搜|找)""")

    /**
     * 一条高德 POI。
     *
     * @param rank 命中的是第几个关键词 —— 0 是最具体（最先搜）的那个。
     */
    data class Poi(
        val id: String,
        val name: String,
        val address: String,
        val distanceM: Double?,
        val type: String,
        val typecode: String,
        val rank: Int = 0,
    )

    /** 一次查询该怎么打：关键词列表 + 是否是餐饮泛指。 */
    data class Plan(
        val keywords: List<String>,
        val foodMode: Boolean,
    )

    /** 合并后的结果与被丢弃的数量。 */
    data class Merged(
        val pois: List<Poi>,
        val droppedTooFar: Int = 0,
        val droppedWrongType: Int = 0,
    )

    // ------------------------------------------------------------ 归一化

    /**
     * 剥掉疑问句的壳。
     *
     * 「附近有什么好吃的」→「好吃」，「附近有超市吗」→「超市」。
     * 泛指（好吃/美食/馆子）**不**在这里展开 —— 那是 [plan] 的事，
     * 端侧路由只负责把壳剥干净。
     *
     * 词表之后还会按**结构**剥一次开头的搜索动词：语音识别把「搜一下」写成
     * 「搜一上」是常事，穷举词表追不上这种变体。
     */
    fun normalize(raw: String): String {
        var text = lightNormalize(raw)
        for (filler in FILLER_WORDS.sortedByDescending { it.length }) {
            text = text.replace(filler, " ", ignoreCase = true)
        }
        text = text.replace(PARTICLE_REGEX, " ")
        text = text.replace(LEADING_HAVE_REGEX) { it.groupValues[1] }
        text = text.replace(LEADING_VERB_REGEX, "")
        text = text.replace(LEADING_BARE_VERB_REGEX, "")
        text = text.replace(Regex("""\s+"""), " ").trim()
        // 剥掉填充词时留下的空格在中文串里没有意义（「好吃 面馆」→「好吃面馆」），
        // 英文与中文之间的空格要留着（"Starbucks 咖啡"）。
        text = text.replace(CJK_SPACE_REGEX, "")
        return text
    }

    /**
     * 归一化之后的字符串是不是一个可以拿去搜的地点关键词。
     *
     * 三条闸门，命中任一就不是关键词：
     *  1. 空 —— 剥完填充词什么都不剩（「附近呢」）；
     *  2. 超过 [MAX_QUERY_LENGTH] —— 这么长的是一句话，不是 POI 名；
     *  3. 含分句标点 ——「我说搜下加油站…然后这一段长文字也走快路径」剥完
     *     填充词还有 39 个字，拿去搜只能得到 0 条。
     *
     * 不是关键词时**不要截断后硬搜**：截出来的关键词只会得到错误结果，
     * 应该交回云端。
     */
    fun isPlaceKeyword(normalized: String): Boolean =
        normalized.isNotBlank() &&
            normalized.length <= MAX_QUERY_LENGTH &&
            normalized.none { it in CLAUSE_PUNCTUATION }

    /**
     * 一次查询的展开方案。
     *
     * - 「附近有什么好吃的」→ 餐厅 / 饭店 / 小吃（三个词并行搜）
     * - 「好吃的面馆」→ 面馆 / 餐厅 / 饭店 / 小吃（修饰词排最前）
     * - 「找一家附近的咖啡店」→ 咖啡店（原样，不展开）
     */
    fun plan(raw: String): Plan {
        val light = lightNormalize(raw)
        if (light.isBlank()) return Plan(emptyList(), false)
        val plain = normalize(light)

        // 一整句话不是地点关键词：「我说搜下加油站…然后这一段长文字也走快路径」
        // 剥完填充词还有 39 个字。宁可空手回去让工具明确报错，也不要拿它去
        // 搜出「附近没有找到」。
        if (!isPlaceKeyword(plain)) return Plan(emptyList(), false)

        // 用户直接说了品类（「附近的餐厅」「美食城」）：照搜，别再展开。
        if (plain in FOOD_CATEGORY_TERMS) return Plan(listOf(plain), true)

        // 泛指可以叠着出现（「我想吃好吃的」）：反复剥，剥到没有为止。
        var rest = light
        val categories = mutableListOf<String>()
        while (true) {
            val hit = findAlias(rest) ?: break
            categories += FOOD_ALIASES[hit.alias].orEmpty()
            rest = rest.removeRange(hit.index, hit.index + hit.alias.length)
        }
        if (categories.isEmpty()) {
            return Plan(listOf(plain), false)
        }

        val specific = normalize(rest)
        val keywords = buildList {
            if (specific.length >= MIN_SPECIFIC_LENGTH &&
                specific !in JUNK_REMAINDERS &&
                isPlaceKeyword(specific)
            ) {
                add(specific)
            }
            addAll(categories)
        }.distinct()
        return Plan(keywords, true)
    }

    private data class AliasHit(val alias: String, val index: Int)

    /** 最早出现的泛指优先；同一位置则长的优先（「好吃的」胜过「吃」）。 */
    private fun findAlias(text: String): AliasHit? {
        var best: AliasHit? = null
        for (alias in FOOD_ALIASES.keys) {
            val index = text.indexOf(alias)
            if (index < 0) continue
            val hit = AliasHit(alias, index)
            val current = best
            best = when {
                current == null -> hit
                hit.index < current.index -> hit
                hit.index == current.index && hit.alias.length > current.alias.length -> hit
                else -> current
            }
        }
        return best
    }

    /**
     * 轻量归一化：全角空格、连续空白。
     *
     * 中文查询里汉字之间的空格没有意义 ——「好 吃」这种被拆开的泛指就再也
     * 匹配不上，所以汉字之间的空白直接去掉；英文单词之间的空格要留着。
     */
    private fun lightNormalize(raw: String): String {
        val text = raw.replace('　', ' ').replace(Regex("""\s+"""), " ").trim()
        return text.replace(CJK_SPACE_REGEX, "")
    }

    // -------------------------------------------------------------- 合并

    /** 高德 POI → 内部结构。名字为空的结果直接丢掉。 */
    fun parsePoi(json: JSONObject): Poi? {
        val name = json.optString("name").trim()
        if (name.isEmpty()) return null
        return Poi(
            id = json.optString("id").trim(),
            name = name,
            address = json.optString("address").trim(),
            distanceM = json.optString("distance").trim().toDoubleOrNull(),
            type = json.optString("type").trim(),
            typecode = json.optString("typecode").trim(),
        )
    }

    /**
     * 合并多个关键词的结果。
     *
     *  - 按 poi_id 去重（同一家的店会同时命中「餐厅」和「饭店」）；
     *  - 餐饮模式下过滤掉非餐饮服务的 POI；
     *  - 排序：先按关键词命中次序，再按距离 ——「好吃的面馆」面馆排最前。
     */
    fun merge(
        ranked: List<Pair<String, JSONArray>>,
        foodMode: Boolean,
        maxDistanceM: Int = 50_000,
    ): Merged {
        val out = mutableListOf<Poi>()
        val seen = mutableSetOf<String>()
        var tooFar = 0
        var wrongType = 0
        ranked.forEachIndexed { rank, (_, array) ->
            for (i in 0 until array.length()) {
                val poi = parsePoi(array.optJSONObject(i) ?: continue) ?: continue
                val distance = poi.distanceM
                if (distance != null && distance > maxDistanceM) {
                    tooFar++
                    continue
                }
                if (foodMode && !isFoodPoi(poi)) {
                    wrongType++
                    continue
                }
                val key = poi.id.ifBlank { poi.name + "|" + poi.address }
                if (!seen.add(key)) continue
                out += poi.copy(rank = rank)
            }
        }
        val sorted = out.sortedWith(
            compareBy<Poi> { it.rank }.thenBy { it.distanceM ?: Double.MAX_VALUE },
        )
        return Merged(sorted, tooFar, wrongType)
    }

    /**
     * 展示配额：按关键词次序取，每个关键词分到一份，总共最多 [limit] 个。
     *
     * 不分配额的话，「好吃的面馆」光面馆一个关键词就能占满 8 条，
     * 后面展开出来的餐厅/饭店/小吃一条都露不出来。配额在关键词之间均分，
     * 一个关键词结果少的时候，剩下的名额让给后面的关键词。
     *
     * @param perKeyword 每个关键词的固定上限；<= 0 表示按 [limit] 自动均分。
     */
    fun select(pois: List<Poi>, perKeyword: Int = 0, limit: Int = 8): List<Poi> {
        if (pois.isEmpty() || limit <= 0) return emptyList()
        val groups = pois.groupBy { it.rank }
        val quotas = LinkedHashMap<Int, Int>()
        var left = limit
        var ranksLeft = groups.size
        for ((rank, group) in groups) {
            val quota = if (perKeyword > 0) {
                perKeyword
            } else {
                // 剩下的名额在剩下的关键词之间均分（向上取整，至少 1 个）。
                maxOf(1, (left + ranksLeft - 1) / ranksLeft)
            }
            val taken = minOf(quota, group.size)
            quotas[rank] = taken
            left -= taken
            ranksLeft--
        }
        // 还有名额就按关键词次序补齐（某个关键词结果太少时）。
        if (left > 0) {
            for ((rank, group) in groups) {
                val extra = minOf(left, group.size - (quotas[rank] ?: 0))
                if (extra > 0) {
                    quotas[rank] = (quotas[rank] ?: 0) + extra
                    left -= extra
                }
                if (left <= 0) break
            }
        }
        val out = mutableListOf<Poi>()
        for ((rank, group) in groups) {
            out += group.take(quotas[rank] ?: 0)
            if (out.size >= limit) break
        }
        return out
    }

    /**
     * 是不是餐饮服务类 POI。
     *
     * 高德 typecode 的前两位就是大类，05 = 餐饮服务；type 文本里也会写
     * 「餐饮服务;中餐馆」这样的细分。「XX 餐饮管理有限公司」落在 17（公司
     * 企业）里 —— 它不该出现在「附近有什么好吃的」的结果中。
     *
     * 名字先判一次：公司名里就带着「有限公司」「餐饮管理」，高德照样会
     * 当关键词命中返回。
     */
    fun isFoodPoi(poi: Poi): Boolean {
        if (COMPANY_NAME_WORDS.any { poi.name.contains(it) }) return false
        val type = poi.type.trim()
        if (type.contains("餐饮服务")) return true
        val code = poi.typecode.trim()
        if (code.isNotBlank()) return code.startsWith("05")
        if (type.isBlank()) return true // 类型未知：宁可留下
        return DINING_TYPE_WORDS.any { type.contains(it) }
    }

    /** 名字里带这些词，是公司/机构而不是吃饭的地方。 */
    private val COMPANY_NAME_WORDS = listOf("有限公司", "有限责任公司", "餐饮管理", "餐饮公司")

    /** type 文本里带这些词，说明是餐饮子类。 */
    private val DINING_TYPE_WORDS = listOf(
        "中餐馆", "外国餐馆", "小吃", "快餐", "面馆", "咖啡", "茶艺", "酒吧",
        "清真", "美食", "火锅", "烧烤", "餐厅", "饭店", "食堂", "粥", "点心",
        "茶餐厅", "餐吧", "美食城",
    )
}

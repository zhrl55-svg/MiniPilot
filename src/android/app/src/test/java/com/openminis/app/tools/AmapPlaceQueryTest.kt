package com.openminis.app.tools

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-amap-colloquial] 「附近有什么好吃的」这类口语问法的归一化与展开。
 *
 * 词表对齐沙箱里那份实测过的 amap.py：同样的问法在端侧和云端必须得到
 * 同样的关键词，否则用户会看到「端侧说没有、云端说有」。
 */
class AmapPlaceQueryTest {

    private fun pois(vararg rows: String): JSONArray {
        val array = JSONArray()
        rows.forEach { array.put(JSONObject(it)) }
        return array
    }

    private fun poi(
        id: String,
        name: String,
        distance: String = "100",
        typecode: String = "050100",
        type: String = "餐饮服务;中餐馆",
    ) = """{"id":"$id","name":"$name","address":"测试路1号","distance":"$distance","typecode":"$typecode","type":"$type"}"""

    // ------------------------------------------------------------ 归一化

    @Test
    fun `question shell is stripped`() {
        assertEquals("超市", AmapPlaceQuery.normalize("附近有超市吗"))
        assertEquals("咖啡店", AmapPlaceQuery.normalize("找一家附近的咖啡店"))
        // 泛指和修饰词都留着，展开是 plan() 的事。
        assertEquals("好吃面馆", AmapPlaceQuery.normalize("好吃的面馆"))
    }

    /**
     * [T-amap-colloquial] 2026-11-28 真机回归：「查询」不在填充词表里时，
     * 归一化会留下「查询药店 / 查询加油站」，高德按这个词搜只能回 0 条，
     * 用户看到「附近 3000m 内没有找到『查询药店』」。
     */
    @Test
    fun `leading 查询 is stripped like 查一下`() {
        assertEquals("药店", AmapPlaceQuery.normalize("查询附近的药店"))
        assertEquals("加油站", AmapPlaceQuery.normalize("查询周边加油站"))
        assertEquals("药店", AmapPlaceQuery.plan("查询附近的药店").keywords.single())
        assertEquals("加油站", AmapPlaceQuery.plan("查询周边加油站").keywords.single())
    }

    @Test
    fun `有什么 is stripped as a whole and leaves the generic behind`() {
        assertEquals("好吃", AmapPlaceQuery.normalize("附近有什么好吃的"))
        assertEquals("好吃", AmapPlaceQuery.normalize("附近有什么好吃"))
        // 「有什么」整体被剥掉，不会剩下一个孤零零的「什么」。
        assertEquals("", AmapPlaceQuery.normalize("附近有什么"))
    }

    @Test
    fun `generic food words survive normalization`() {
        // 泛指不在这里展开 —— 展开是 AmapSearchTool 的活。
        assertEquals("好吃", AmapPlaceQuery.normalize("好吃"))
        assertEquals("馆子", AmapPlaceQuery.normalize("附近有馆子吗"))
    }

    // -------------------------------------------------------------- 展开

    @Test
    fun `colloquial food question expands into restaurant keywords`() {
        val plan = AmapPlaceQuery.plan("附近有什么好吃的")
        assertEquals(listOf("餐厅", "饭店", "小吃"), plan.keywords)
        assertTrue(plan.foodMode)
    }

    @Test
    fun `every food alias expands to the same categories`() {
        assertEquals(
            listOf("餐厅", "饭店", "小吃"),
            AmapPlaceQuery.plan("附近有美食").keywords,
        )
        assertEquals(
            listOf("餐厅", "饭店", "小吃"),
            AmapPlaceQuery.plan("附近有美味").keywords,
        )
        assertEquals(
            listOf("餐厅", "饭店", "小吃"),
            AmapPlaceQuery.plan("附近有什么好吃的").keywords,
        )
        assertEquals(
            listOf("餐厅", "饭店", "小吃"),
            AmapPlaceQuery.plan("附近有什么吃的").keywords,
        )
    }

    @Test
    fun `馆子 does not pull in 小吃`() {
        val plan = AmapPlaceQuery.plan("附近有馆子吗")
        assertEquals(listOf("餐厅", "饭店"), plan.keywords)
        assertTrue(plan.foodMode)
    }

    @Test
    fun `modifier keyword ranks before the generic categories`() {
        val plan = AmapPlaceQuery.plan("好吃的面馆")
        assertEquals(listOf("面馆", "餐厅", "饭店", "小吃"), plan.keywords)
        assertTrue(plan.foodMode)
    }

    @Test
    fun `plain place queries are not expanded`() {
        val plan = AmapPlaceQuery.plan("找一家附近的咖啡店")
        assertEquals(listOf("咖啡店"), plan.keywords)
        assertFalse(plan.foodMode)
    }

    @Test
    fun `an explicit category term is searched as is`() {
        val plan = AmapPlaceQuery.plan("附近的餐厅")
        assertEquals(listOf("餐厅"), plan.keywords)
        assertTrue(plan.foodMode)
    }

    @Test
    fun `a query that is only filler produces no keyword`() {
        assertTrue(AmapPlaceQuery.plan("附近呢").keywords.isEmpty())
    }

    // -------------------------------------------------- 搜索动词 / 整句闸门

    @Test
    fun `search verbs are stripped from the query`() {
        // 「搜一下 / 搜下」是搜索动词，不是地点的一部分。剥掉之后剩下的
        // 才是高德认识的关键词。
        assertEquals("加油站", AmapPlaceQuery.normalize("搜一下加油站"))
        assertEquals("加油站", AmapPlaceQuery.normalize("搜下周边加油站"))
        assertEquals("加油站", AmapPlaceQuery.normalize("搜一下周边的加油站"))
        assertEquals("加油站", AmapPlaceQuery.normalize("帮我搜一下加油站"))
        assertEquals("加油站", AmapPlaceQuery.normalize("帮我搜加油站"))
    }

    @Test
    fun `the search verb never reaches the keyword list`() {
        assertEquals(listOf("加油站"), AmapPlaceQuery.plan("搜一下加油站").keywords)
        assertEquals(listOf("加油站"), AmapPlaceQuery.plan("搜下周边加油站").keywords)
        assertEquals(listOf("加油站"), AmapPlaceQuery.plan("周边加油站").keywords)
    }

    @Test
    fun `the search verb is not a keyword of a food question`() {
        // 端侧实测：「搜一下周边好吃的」曾把「搜一下」当成一个关键词去搜，
        // 结果标签变成「搜一下·餐厅·饭店·小吃」。
        val plan = AmapPlaceQuery.plan("搜一下周边好吃的")
        assertEquals(listOf("餐厅", "饭店", "小吃"), plan.keywords)
        assertTrue(plan.foodMode)
    }

    @Test
    fun `asr written search verbs are stripped by structure`() {
        // 语音识别把「搜一下」写成「搜一上」：穷举词表追不上，按结构剥。
        assertEquals("药店", AmapPlaceQuery.normalize("周边搜一上药店"))
        assertEquals("药店", AmapPlaceQuery.normalize("搜一上药店"))
        assertEquals("药店", AmapPlaceQuery.normalize("搜一下药店"))
        assertEquals("加油站", AmapPlaceQuery.normalize("找加油站"))
        assertEquals("加油站", AmapPlaceQuery.normalize("搜索加油站"))
        assertEquals("咖啡店", AmapPlaceQuery.normalize("看见咖啡店"))
        // 方言/口语的「一哈」和别的单字写法同样按「一 + 任意单字」剥。
        assertEquals("加油站", AmapPlaceQuery.normalize("搜一哈加油站"))
        assertEquals("药店", AmapPlaceQuery.normalize("找一哈药店"))
        assertEquals("药店", AmapPlaceQuery.normalize("查一哈药店"))
    }

    @Test
    fun `place names that start with a verb character survive`() {
        // 只剥完整的动词短语：以动词字开头的地名不能被啃掉开头。
        assertEquals("看守所", AmapPlaceQuery.normalize("看守所"))
        assertEquals("查尔斯酒店", AmapPlaceQuery.normalize("查尔斯酒店"))
        assertEquals("看牙", AmapPlaceQuery.normalize("看牙"))
    }

    @Test
    fun `the asr variant reaches the keyword list`() {
        assertEquals(listOf("药店"), AmapPlaceQuery.plan("周边搜一上药店").keywords)
        assertEquals(listOf("加油站"), AmapPlaceQuery.plan("搜一哈加油站").keywords)
    }

    @Test
    fun `a whole sentence produces no keyword`() {
        // 用户原话：一整段说明文字被当成了地点关键词，搜出 0 条。
        val sentence = "我说搜下周边加油站和搜一下加油站都没有成功,说周边加油站.才成功," +
            "然后这一段长文字也走了快路径"
        assertTrue(AmapPlaceQuery.plan(sentence).keywords.isEmpty())
        assertTrue(
            AmapPlaceQuery.plan("我昨天在附近那家店吃了个饭,你觉得怎么样").keywords.isEmpty(),
        )
    }

    @Test
    fun `a place keyword is short and free of clause punctuation`() {
        assertTrue(AmapPlaceQuery.isPlaceKeyword("加油站"))
        assertFalse(AmapPlaceQuery.isPlaceKeyword(""))
        assertFalse(AmapPlaceQuery.isPlaceKeyword("加油站,超市"))
        assertFalse(AmapPlaceQuery.isPlaceKeyword("加油站,超市".repeat(4)))
    }

    // -------------------------------------------------------------- 合并

    @Test
    fun `merge keeps the keyword order and dedupes by poi id`() {
        val noodles = pois(
            poi("A", "老王面馆", "120", "050900", "餐饮服务;面馆"),
            poi("B", "小李面馆", "300", "050900", "餐饮服务;面馆"),
        )
        val restaurants = pois(
            poi("A", "老王面馆", "120", "050900", "餐饮服务;面馆"),
            poi("C", "某某餐厅", "500"),
        )
        val merged = AmapPlaceQuery.merge(
            listOf("面馆" to noodles, "餐厅" to restaurants),
            foodMode = true,
        )
        assertEquals(listOf("老王面馆", "小李面馆", "某某餐厅"), merged.pois.map { it.name })
        assertEquals(listOf(0, 0, 1), merged.pois.map { it.rank })
    }

    @Test
    fun `company results are filtered out of food queries`() {
        val restaurants = pois(
            poi(
                "D",
                "张掖市餐饮管理有限公司",
                "80",
                "170000",
                "公司企业;餐饮管理有限公司",
            ),
            poi("E", "夜市小吃城", "200", "050800", "餐饮服务;美食城"),
        )
        val filtered = AmapPlaceQuery.merge(listOf("餐厅" to restaurants), foodMode = true)
        assertEquals(listOf("夜市小吃城"), filtered.pois.map { it.name })
        assertEquals(1, filtered.droppedWrongType)

        // 非餐饮泛指的普通查询不过滤：公司名也是用户可能要找的东西。
        val plain = AmapPlaceQuery.merge(listOf("餐厅" to restaurants), foodMode = false)
        assertEquals(2, plain.pois.size)
    }

    @Test
    fun `distance safety valve still applies`() {
        val far = pois(poi("F", "天边的餐厅", "900000"))
        val merged = AmapPlaceQuery.merge(listOf("餐厅" to far), foodMode = true)
        assertTrue(merged.pois.isEmpty())
        assertEquals(1, merged.droppedTooFar)
    }

    @Test
    fun `results of the same keyword are sorted by distance`() {
        val restaurants = pois(
            poi("G", "远的店", "900"),
            poi("H", "近的店", "100"),
            poi("I", "中的店", "400"),
        )
        val merged = AmapPlaceQuery.merge(listOf("餐厅" to restaurants), foodMode = true)
        assertEquals(listOf("近的店", "中的店", "远的店"), merged.pois.map { it.name })
    }

    // -------------------------------------------------------------- 配额

    @Test
    fun `every keyword gets a share so the later categories still show`() {
        val noodles = pois(
            poi("N1", "面馆1", "100"),
            poi("N2", "面馆2", "200"),
            poi("N3", "面馆3", "300"),
            poi("N4", "面馆4", "400"),
        )
        val restaurants = pois(
            poi("R1", "餐厅1", "500"),
            poi("R2", "餐厅2", "600"),
            poi("R3", "餐厅3", "700"),
            poi("R4", "餐厅4", "800"),
        )
        val merged = AmapPlaceQuery.merge(
            listOf("面馆" to noodles, "餐厅" to restaurants),
            foodMode = true,
        )
        val shown = AmapPlaceQuery.select(merged.pois, limit = 6)
        // 面馆排最前，但餐厅也要露出来。
        assertEquals("面馆1", shown.first().name)
        assertTrue(shown.map { it.name }.contains("餐厅1"))
        assertTrue(shown.size <= 6)
    }

    @Test
    fun `a single keyword is not split into quotas`() {
        val shops = pois(
            poi("S1", "超市1", "100"),
            poi("S2", "超市2", "200"),
            poi("S3", "超市3", "300"),
            poi("S4", "超市4", "400"),
            poi("S5", "超市5", "500"),
        )
        val merged = AmapPlaceQuery.merge(listOf("超市" to shops), foodMode = false)
        val shown = AmapPlaceQuery.select(merged.pois, limit = 8)
        assertEquals(5, shown.size)
        assertEquals("超市1", shown.first().name)
    }

    @Test
    fun `food type code decides whether a poi is a restaurant`() {
        val restaurant = AmapPlaceQuery.parsePoi(JSONObject(poi("A", "某餐厅")))
        val company = AmapPlaceQuery.parsePoi(
            JSONObject(poi("B", "某餐饮管理有限公司", "100", "170000", "公司企业;餐饮管理有限公司")),
        )
        assertTrue(AmapPlaceQuery.isFoodPoi(restaurant!!))
        assertFalse(AmapPlaceQuery.isFoodPoi(company!!))
    }
}

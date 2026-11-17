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

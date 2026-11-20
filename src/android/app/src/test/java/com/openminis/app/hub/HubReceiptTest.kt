package com.openminis.app.hub

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-hub-receipt] The machine-readable receipt schema.
 *
 * A receipt that only says "done, see the file" leaves the other side
 * parsing prose. The schema gives both sides the same fields to read — and
 * the phone must refuse a broken one before it goes on the wire, instead of
 * letting the other side store half-valid data.
 */
class HubReceiptTest {

    private fun valid(): JSONObject = JSONObject()
        .put("re", "m-20261120-003")
        .put("status", "done")
        .put("summary", "wired the working frame")
        .put(
            "artifacts",
            JSONArray().put(
                JSONObject()
                    .put("path", "D:/minis-src/x.md")
                    .put("sha256", "a".repeat(64))
                    .put("lines", 12),
            ),
        )
        .put("needs", JSONArray().put("mirror the schema in the notify"))

    @Test
    fun `a valid receipt has no problems`() {
        assertTrue(HubReceipt.problems(valid()).isEmpty())
    }

    @Test
    fun `an unknown status is refused`() {
        val problems = HubReceipt.problems(JSONObject(valid().toString()).put("status", "finished"))
        assertTrue(problems.any { it.contains("status") })
    }

    @Test
    fun `a missing task id is refused`() {
        val problems = HubReceipt.problems(JSONObject(valid().toString()).put("re", ""))
        assertTrue(problems.any { it.contains("re") })
    }

    @Test
    fun `an artifact without a path is refused`() {
        val broken = JSONObject(valid().toString()).put(
            "artifacts",
            JSONArray().put(JSONObject().put("sha256", "a".repeat(64))),
        )
        assertTrue(HubReceipt.problems(broken).any { it.contains("path") })
    }

    @Test
    fun `a digest that is not a sha256 is refused`() {
        val broken = JSONObject(valid().toString()).put(
            "artifacts",
            JSONArray().put(JSONObject().put("path", "x").put("sha256", "nope")),
        )
        assertTrue(HubReceipt.problems(broken).any { it.contains("sha256") })
    }

    @Test
    fun `an unknown field is tolerated`() {
        val grown = JSONObject(valid().toString()).put("future_field", 1)
        assertTrue(HubReceipt.problems(grown).isEmpty())
    }

    @Test
    fun `a receipt that is not an object has a problem`() {
        assertTrue(HubReceipt.problems(null).isNotEmpty())
    }

    @Test
    fun `a receipt round trips through json`() {
        val receipt = HubReceipt.fromJson(valid())
        assertNotNull(receipt)
        assertEquals("m-20261120-003", receipt!!.re)
        assertEquals("done", receipt.status)
        assertEquals("wired the working frame", receipt.summary)
        assertEquals(1, receipt.artifacts.size)
        assertEquals("D:/minis-src/x.md", receipt.artifacts[0].path)
        assertEquals(12, receipt.artifacts[0].lines)
        assertEquals(listOf("mirror the schema in the notify"), receipt.needs)

        val again = HubReceipt.fromJson(receipt.toJson())
        assertEquals(receipt.re, again?.re)
        assertEquals(receipt.status, again?.status)
        assertEquals(receipt.artifacts.size, again?.artifacts?.size)
        assertEquals(receipt.needs, again?.needs)
    }
}

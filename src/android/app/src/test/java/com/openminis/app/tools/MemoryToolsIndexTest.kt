package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryToolsIndexTest {

    @Test
    fun memoryGetSchemaExposesTheRankedLimit() {
        val properties = MemoryTools.memoryGetToolDefinition()
            .getJSONObject("input_schema")
            .getJSONObject("properties")

        assertTrue(properties.has("limit"))
        val limit = properties.getJSONObject("limit")
        assertEquals("integer", limit.getString("type"))
        assertEquals(1, limit.getInt("minimum"))
        assertEquals(10, limit.getInt("maximum"))
    }
}

package com.openminis.app.assistant.execution

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test


/**
 * [T-alarm-truthful-result] 「需要用户在系统时钟里确认」是一句人话，气泡里
 * 不能倒一段 JSON 给用户，也不能说成「操作失败」。
 */
class AlarmResultPresentationTest {

    @Test
    fun `a pending confirmation shows the message instead of json`() {
        val json = """
            {
              "error": "needs_user_confirmation",
              "message": "已经打开系统时钟并填好闹钟，但需要你在系统时钟里点确认才会生效。"
            }
        """.trimIndent()

        val answer = LocalResultFormatter.answer("alarm", "set", json, false)

        assertEquals("已经打开系统时钟并填好闹钟，但需要你在系统时钟里点确认才会生效。", answer)
    }

    @Test
    fun `a real failure keeps its error code`() {
        val json = """{"error":"alarm_not_created","message":"系统时钟没有接受这个闹钟，没有创建成功。"}"""
        val answer = LocalResultFormatter.answer("alarm", "set", json, false)
        // 失败保留原始错误码（与其它能力的失败口径一致）。
        assertTrue(answer.contains("alarm_not_created"))
    }

    @Test
    fun `the completion speech asks for confirmation instead of reporting failure`() {
        val speech = LocalActionSpeech.completion(
            capability = "alarm",
            action = "set",
            chinese = true,
            fallbackLabel = "Set alarm",
            args = JSONObject(),
            success = false,
            output = "已经打开系统时钟并填好闹钟。",
            needsUserConfirmation = true,
        )
        assertEquals("已经填好了，需要你在系统时钟里点确认才会生效", speech)
    }

    @Test
    fun `an ordinary failure is still announced as a failure`() {
        val speech = LocalActionSpeech.completion(
            capability = "alarm",
            action = "set",
            chinese = true,
            fallbackLabel = "Set alarm",
            args = JSONObject(),
            success = false,
            output = "系统时钟没有接受这个闹钟，没有创建成功。",
        )
        assertTrue(speech.startsWith("操作失败"))
    }
}

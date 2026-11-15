package com.openminis.app.sandbox.offload

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level contract for the a11y action loop. The handler needs a live
 * AccessibilityService, so the behaviours that must not regress are pinned
 * where they can be checked without one (same style as
 * [A11yScreenshotPathTest]).
 */
class A11yActionLoopSourceTest {

    private fun source(path: String): String {
        val f = File(path)
        assertTrue("missing ${f.absolutePath}", f.exists())
        return f.readText()
    }

    private val handler: String by lazy {
        source("src/main/java/com/openminis/app/sandbox/offload/AccessibilityOffloadHandler.kt")
    }

    private val handlerCode: String by lazy {
        handler.lineSequence()
            .filterNot {
                val t = it.trimStart()
                t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
            }
            .joinToString("\n")
    }

    private fun body(funName: String): String =
        handlerCode.substringAfter("private fun $funName(")
            .substringBefore("\n    private fun ")

    @Test
    fun `tap text no longer reports success without performing the click`() {
        val tapText = body("tapText")
        assertTrue("must try node -> ancestor -> coordinate", tapText.contains("performTapOnNode("))
        assertTrue("must branch on the result", tapText.contains("if (!clicked)"))
        assertFalse(
            "the old bare performAction whose return value was ignored must be gone",
            tapText.contains("if (n.isClickable) n.performAction"),
        )
    }

    @Test
    fun `every tap form reports via and change detection`() {
        for (name in listOf("tapNode", "tapText", "tapId", "tapXYRaw")) {
            val b = body(name)
            assertTrue("$name must call finishAction", b.contains("finishAction("))
        }
        assertTrue("tapNode must report the landing path", body("tapNode").contains(".put(\"via\", via)"))
        assertTrue("tapXY must resolve a semantic target", body("tapXYRaw").contains("A11yLabels.nodeAt("))
    }

    @Test
    fun `the landing order is node then ancestors then coordinates`() {
        val b = body("performTapOnNode")
        assertTrue("must walk ancestors", b.contains("TAP_ANCESTOR_DEPTH"))
        assertTrue("must fall back to a gesture", b.contains("dispatchTap("))
        assertTrue("must name the path that worked", b.contains("ancestor"))
    }

    @Test
    fun `input never echoes the typed value`() {
        val inputText = body("inputText")
        assertFalse(
            "the typed text must not be put in the tool result",
            inputText.contains(".put(\"text\""),
        )
        assertTrue("only the length is reported", inputText.contains(".put(\"chars\""))
        assertTrue("input participates in change detection", inputText.contains("finishAction("))
    }

    @Test
    fun `the dump has a semantic mode and redacts passwords`() {
        val dump = body("uiDump")
        assertTrue(dump.contains("interactive"))
        assertTrue(dump.contains("screenshotSuggested"))
        val node = body("nodeToJson")
        assertTrue(node.contains("n.isPassword"))
        assertTrue(node.contains(".put(\"password\", password)"))
        assertTrue(node.contains(".put(\"untrusted\", true)"))
        assertTrue("password text must be blank", node.contains("if (password) \"\" else n.text"))
    }

    @Test
    fun `the action journal is wired into mutating actions and the CLI`() {
        assertTrue(handlerCode.contains("A11yJournal.append("))
        assertTrue(handlerCode.contains("journalSub(args)"))
        assertTrue(handlerCode.contains("private fun journalSub("))
    }

    @Test
    fun `editable values never become labels or injected context`() {
        val labels = source("src/main/java/com/openminis/app/accessibility/A11yLabels.kt")
        assertTrue(
            "an editable node's text is its value, not a label",
            labels.contains("if (node.isEditable) null else node.text"),
        )
        val service = source("src/main/java/com/openminis/app/accessibility/MinisAccessibilityService.kt")
        assertTrue(
            "typed values must not enter the screen-text cache",
            service.contains("!node.isPassword && !node.isEditable"),
        )
    }

    @Test
    fun `the shizuku input path is redacted too`() {
        val shizuku = source("src/main/java/com/openminis/app/sandbox/offload/ShizukuOffloadHandler.kt")
        val inputText = shizuku.substringAfter("private fun inputText(")
            .substringBefore("\n    private fun ")
        assertFalse(inputText.contains(".put(\"text\""))
        assertTrue(inputText.contains(".put(\"chars\""))
    }

    @Test
    fun `guard resolves opaque accessibility targets`() {
        val guard = source("src/main/java/com/openminis/app/agent/guard/Guard.kt")
        assertTrue(guard.contains("a11yTargetText"))
        assertTrue(guard.contains("A11Y_TAP_XY"))
        assertTrue(guard.contains("redactTypedText"))
        assertTrue(guard.contains("IRREVERSIBLE_TARGET_WORDS"))
    }

    @Test
    fun `the model-facing manual prefers the semantic path`() {
        val manual = source("src/main/java/com/openminis/app/sandbox/offload/SelfManual.kt")
        assertTrue(manual.contains("ui dump --interactive"))
        assertTrue(manual.contains("changed"))
        assertTrue(manual.contains("journal tail"))
    }
}

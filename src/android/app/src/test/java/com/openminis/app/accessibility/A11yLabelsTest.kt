package com.openminis.app.accessibility

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure contract tests for the semantic snapshot helpers. The tree-walking
 * overloads need a live AccessibilityService and are covered on-device; these
 * pin the parts that decide what the model sees and what must never leak.
 */
class A11yLabelsTest {

    @Test
    fun `roles map the widgets an agent can act on`() {
        assertEquals("Button", A11yLabels.roleOf("android.widget.Button"))
        assertEquals("Button", A11yLabels.roleOf("android.widget.ImageButton"))
        assertEquals("Edit", A11yLabels.roleOf("android.widget.EditText"))
        assertEquals("Edit", A11yLabels.roleOf("androidx.appcompat.widget.AppCompatEditText"))
        assertEquals("CheckBox", A11yLabels.roleOf("android.widget.CheckBox"))
        assertEquals("Switch", A11yLabels.roleOf("android.widget.Switch"))
        assertEquals("List", A11yLabels.roleOf("androidx.recyclerview.widget.RecyclerView"))
        assertEquals("Text", A11yLabels.roleOf("android.widget.TextView"))
        assertEquals("WebView", A11yLabels.roleOf("android.webkit.WebView"))
        assertEquals("Tab", A11yLabels.roleOf("android.widget.TabWidget"))
        assertEquals("Group", A11yLabels.roleOf("android.widget.TableLayout"))
        assertEquals("Group", A11yLabels.roleOf("android.widget.LinearLayout"))
        assertEquals("View", A11yLabels.roleOf(null))
    }

    @Test
    fun `a password field never yields a label`() {
        assertEquals(
            "",
            A11yLabels.sanitizeLabel(
                text = "hunter2",
                contentDesc = "密码",
                hint = "请输入密码",
                password = true,
            ),
        )
    }

    @Test
    fun `label prefers text then description then hint`() {
        assertEquals("保存", A11yLabels.sanitizeLabel(" 保存 ", "desc", "hint", false))
        assertEquals("desc", A11yLabels.sanitizeLabel("", "desc", "hint", false))
        assertEquals("hint", A11yLabels.sanitizeLabel(null, null, "hint", false))
        assertEquals("", A11yLabels.sanitizeLabel(null, null, null, false))
    }

    @Test
    fun `labels are capped at sixty characters`() {
        val long = "a".repeat(200)
        assertEquals(A11yLabels.MAX_LABEL_CHARS, A11yLabels.sanitizeLabel(long, null, null, false).length)
    }

    @Test
    fun `typed text is redacted in audit evidence`() {
        assertEquals(
            "android-a11y-cli input text ***",
            com.openminis.app.agent.guard.Guard.redactTypedText(
                "android-a11y-cli input text hunter2",
            ),
        )
        assertEquals(
            "android-shizuku-cli input text ***",
            com.openminis.app.agent.guard.Guard.redactTypedText(
                "android-shizuku-cli input text 123456",
            ),
        )
        assertEquals(
            "echo hello",
            com.openminis.app.agent.guard.Guard.redactTypedText("echo hello"),
        )
    }
}

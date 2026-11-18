package com.openminis.app.ui.chat

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-copy-toast-l10n] Tapping inline code in a reply copied it and
 * showed a hard-coded English "Copied: …" toast even in a Chinese UI, while
 * copying a selection showed the localized "已复制：…". Copy feedback - the
 * toast and the copy buttons' TalkBack labels - must come from string
 * resources.
 *
 * Source guard only: the unit tests run without app resources, so the
 * localized toast text itself is not checked here.
 */
class CopyFeedbackLocalizedGuardTest {

    private val ui = File("src/main/java/com/openminis/app/ui")
    private val streaming = File(ui, "chat/StreamingMarkdownText.kt")

    /** Code with comment lines removed, so a quoted example in a comment does not count. */
    private fun code(f: File): String =
        f.readLines().filterNot { it.trimStart().let { t -> t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") } }
            .joinToString("\n")

    @Test
    fun `tapping inline code toasts the localized selection_copied_toast with the same preview`() {
        val src = code(streaming)
        assertTrue("hard-coded \"Copied: \$…\" toast is back", !src.contains("\"Copied: \$"))
        val preview = "val preview = if (snippet.length > 40) snippet.take(37) + \"…\" else snippet"
        val at = src.indexOf(preview)
        assertTrue("inline-code preview computation changed or moved", at >= 0)
        val branch = src.substring(at, minOf(src.length, at + 400))
        assertTrue(
            "the inline-code tap branch must use R.string.selection_copied_toast: $branch",
            branch.contains("context.getString(R.string.selection_copied_toast, preview)"),
        )
    }

    @Test
    fun `the code block copy button has localized TalkBack labels`() {
        val src = code(streaming)
        assertTrue(!src.contains("\"Copy code\"") && !src.contains("\"Copied\""))
        assertTrue(src.contains("stringResource(R.string.image_copied_toast)"))
        assertTrue(src.contains("stringResource(R.string.copy_code_block)"))
    }

    @Test
    fun `the code block copy button has a finger-sized hit area and a green check`() {
        // [T-android-codeblock-copy-target] The 24dp ContentCopy glyph is drawn
        // LARGER than the 16dp layout box a bare Icon(...).clickable leaves
        // behind, so the part of the glyph outside that box was dead to the
        // finger - only the "pressed" overlap was tappable. The glyph stays
        // 16dp; the tappable box is 36dp (iOS: 44x44 hit area, small glyph).
        //
        // The feedback is the green check - a toast was tried and dropped, the
        // check is enough and matches iOS.
        val src = code(streaming)
        val at = src.indexOf("stringResource(R.string.copy_code_block)")
        assertTrue("the code block copy button is gone", at >= 0)
        val around = src.substring(maxOf(0, at - 1500), minOf(src.length, at + 1500))
        assertTrue(
            "the code block copy button needs a finger-sized hit area: $around",
            around.contains(".size(36.dp)"),
        )
        assertTrue(
            "the code block copy button must confirm the copy with the green check: $around",
            around.contains("Icons.Default.Check"),
        )
        assertTrue(
            "the code block copy button must not pop a text toast: $around",
            !around.contains("Toast.makeText"),
        )
        assertTrue(
            "the clickable box must carry the TalkBack label itself",
            around.contains("contentDescription = copyLabel"),
        )
    }

    @Test
    fun `no copy feedback in ui is a hard-coded English literal`() {
        // Exact copy-feedback literals. SessionDuplicateTitle matches the
        // English "Copy" suffix of old titles on purpose (logic, not UI text).
        val literal = Regex(""""(Copy|Copied|Copy code|Copied!)"|"Copied: |"Copied to """)
        val allowed = setOf("sessions/SessionDuplicateTitle.kt")
        val hits = ui.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filterNot { it.relativeTo(ui).path.replace(File.separatorChar, '/') in allowed }
            .flatMap { f ->
                code(f).lines().filter { literal.containsMatchIn(it) }
                    .map { "${f.relativeTo(ui).path.replace(File.separatorChar, '/')}: ${it.trim()}" }
            }.toList()
        assertTrue("hard-coded copy feedback (use a string resource): $hits", hits.isEmpty())
    }
}

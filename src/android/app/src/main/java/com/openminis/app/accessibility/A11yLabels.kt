package com.openminis.app.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Semantic helpers shared by `android-a11y-cli` and [com.openminis.app.agent.guard.Guard].
 *
 * The raw accessibility tree is a poor prompt: list rows expose a clickable
 * `LinearLayout` with no text while the label lives in a filtered child, every
 * node carries a dozen fields, and password fields may report their contents.
 * [labelOf] aggregates child text upward (2 levels, 60 chars), [roleOf] maps
 * Android classes to the portable roles used by the cross-platform CLI spec
 * (`src/shared/cli/a11y-cli.md` §2.1), and [describeTarget] produces the short
 * "role + label + viewId + package" line Guard uses to judge what a tap is
 * actually about.
 *
 * Everything here is best-effort: a stale/recycled node must yield an empty
 * string, never throw into the agent loop.
 */
object A11yLabels {
    const val MAX_LABEL_CHARS = 60
    const val MAX_AGGREGATE_LEVELS = 2
    const val MAX_SIGNATURE_NODES = 300

    /** Map an Android class name to the portable role vocabulary. */
    fun roleOf(className: String?): String {
        if (className.isNullOrBlank()) return "View"
        val c = className.substringAfterLast('.').lowercase()
        return when {
            c.contains("imagebutton") -> "Button"
            c.contains("button") -> "Button"
            c.contains("edittext") || c.contains("autocompletetextview") -> "Edit"
            c.contains("checkbox") -> "CheckBox"
            c.contains("radiobutton") || c.contains("radio") -> "Radio"
            c.contains("switch") || c.contains("toggle") -> "Switch"
            c.contains("seekbar") || c.contains("slider") -> "Slider"
            c.contains("progressbar") -> "Progress"
            c.contains("recyclerview") || c.contains("listview") || c.contains("gridview") -> "List"
            c.contains("scrollview") || c.contains("horizontalscrollview") -> "Scroll"
            c.contains("textview") -> "Text"
            c.contains("imageview") -> "Image"
            // TableLayout is a layout, not a tab; check it before the "tab" rule.
            c.contains("tablelayout") -> "Group"
            c.contains("tabwidget") || c.contains("tabhost") || c.endsWith("tab") -> "Tab"
            c.contains("webview") -> "WebView"
            c.contains("dialog") -> "Dialog"
            c.contains("spinner") || c.contains("dropdown") -> "ComboBox"
            c.contains("menu") -> "Menu"
            c.contains("layout") || c.contains("viewgroup") -> "Group"
            else -> "View"
        }
    }

    /**
     * The label a screen reader would announce, or "" for a password field.
     * Pure so the JVM tests can pin the redaction contract.
     */
    fun sanitizeLabel(
        text: String?,
        contentDesc: String?,
        hint: String?,
        password: Boolean,
    ): String {
        if (password) return ""
        val raw = sequenceOf(text, contentDesc, hint)
            .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            .firstOrNull()
            ?: return ""
        return raw.take(MAX_LABEL_CHARS)
    }

    fun ownLabel(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        return try {
            if (node.isPassword) return ""
            // For an editable node, `text` is its current VALUE (an email, a
            // search query, sometimes a one-time code), not a label. Use the
            // hint/description instead so action results and the journal never
            // echo what the user typed.
            val text = if (node.isEditable) null else node.text?.toString()
            sanitizeLabel(
                text = text,
                contentDesc = node.contentDescription?.toString(),
                hint = node.hintText?.toString(),
                password = false,
            )
        } catch (_: Throwable) {
            ""
        }
    }

    /**
     * A clickable row often has no text of its own; its label is in a child
     * `TextView`. Aggregate up to [maxLevels] levels, joined with spaces.
     * Password descendants never contribute.
     */
    fun labelOf(
        node: AccessibilityNodeInfo?,
        maxLevels: Int = MAX_AGGREGATE_LEVELS,
        maxChars: Int = MAX_LABEL_CHARS,
    ): String {
        if (node == null) return ""
        // A password field's descendants are still part of the secret widget;
        // never aggregate through it.
        if (try { node.isPassword } catch (_: Throwable) { false }) return ""
        val own = ownLabel(node)
        if (own.isNotBlank()) return own.take(maxChars)
        return try {
            val sb = StringBuilder()
            collectLabels(node, 0, maxLevels, maxChars, sb)
            sb.toString().trim().take(maxChars)
        } catch (_: Throwable) {
            ""
        }
    }

    private fun collectLabels(
        node: AccessibilityNodeInfo,
        depth: Int,
        maxLevels: Int,
        maxChars: Int,
        out: StringBuilder,
    ) {
        if (depth > maxLevels || out.length >= maxChars) return
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (try { child.isPassword } catch (_: Throwable) { false }) continue
            val label = ownLabel(child)
            if (label.isNotBlank()) {
                if (out.isNotEmpty()) out.append(' ')
                out.append(label)
                if (out.length >= maxChars) return
            }
            if (depth + 1 < maxLevels) collectLabels(child, depth + 1, maxLevels, maxChars, out)
        }
    }

    fun isActionable(node: AccessibilityNodeInfo): Boolean = try {
        node.isClickable || node.isLongClickable || node.isEditable ||
            node.isScrollable || node.isCheckable
    } catch (_: Throwable) {
        false
    }

    /**
     * One line describing what an action targets: `"支付" Button
     * com.tencent.mm:id/pay in com.tencent.mm ancestor "微信支付" [..]`.
     * Password fields are named, never read.
     */
    fun describeTarget(node: AccessibilityNodeInfo?, maxAncestors: Int = 3): String {
        if (node == null) return ""
        return try {
            val parts = mutableListOf<String>()
            if (node.isPassword) {
                parts += "password field"
            } else {
                val label = labelOf(node)
                if (label.isNotBlank()) parts += "\"$label\""
            }
            val role = roleOf(node.className?.toString())
            if (role != "View") parts += role
            node.viewIdResourceName?.takeIf { it.isNotBlank() }?.let { parts += it }
            node.packageName?.toString()?.takeIf { it.isNotBlank() }?.let { parts += "in $it" }
            var cur = try { node.parent } catch (_: Throwable) { null }
            var n = 0
            while (cur != null && n < maxAncestors) {
                val label = ownLabel(cur)
                if (label.isNotBlank() && parts.none { it.contains(label) }) {
                    parts += "ancestor \"$label\""
                }
                cur = try { cur.parent } catch (_: Throwable) { null }
                n++
            }
            val r = Rect()
            node.getBoundsInScreen(r)
            parts += "[${r.left},${r.top},${r.right},${r.bottom}]"
            parts.joinToString(" ")
        } catch (_: Throwable) {
            ""
        }
    }

    /**
     * Smallest actionable node containing the point. This is what gives a
     * coordinate tap a semantic target without scanning the whole screen for
     * risk words (the false-positive trap in Pilot's Guard).
     */
    fun nodeAt(
        roots: List<AccessibilityNodeInfo>,
        x: Int,
        y: Int,
        maxDepth: Int = 30,
        maxNodes: Int = 400,
    ): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = Long.MAX_VALUE
        val counter = intArrayOf(0)
        for (root in roots) {
            findAt(root, x, y, 0, maxDepth, maxNodes, counter) { node, area ->
                if (area < bestArea) {
                    best = node
                    bestArea = area
                }
            }
        }
        return best
    }

    private fun findAt(
        node: AccessibilityNodeInfo?,
        x: Int,
        y: Int,
        depth: Int,
        maxDepth: Int,
        maxNodes: Int,
        counter: IntArray,
        hit: (AccessibilityNodeInfo, Long) -> Unit,
    ) {
        if (node == null || depth > maxDepth || counter[0] >= maxNodes) return
        counter[0]++
        try {
            if (node.isVisibleToUser) {
                val r = Rect()
                node.getBoundsInScreen(r)
                if (r.contains(x, y) && isActionable(node)) {
                    val area = r.width().toLong() * r.height().toLong()
                    hit(node, if (area > 0) area else Long.MAX_VALUE)
                }
            }
            for (i in 0 until node.childCount) {
                findAt(node.getChild(i), x, y, depth + 1, maxDepth, maxNodes, counter, hit)
            }
        } catch (_: Throwable) {
        }
    }

    fun nodeByResourceId(
        roots: List<AccessibilityNodeInfo>,
        resourceId: String,
        maxDepth: Int = 30,
    ): AccessibilityNodeInfo? {
        for (root in roots) {
            findById(root, resourceId, 0, maxDepth)?.let { return it }
        }
        return null
    }

    private fun findById(
        node: AccessibilityNodeInfo?,
        id: String,
        depth: Int,
        maxDepth: Int,
    ): AccessibilityNodeInfo? {
        if (node == null || depth > maxDepth) return null
        try {
            if (node.viewIdResourceName == id) return node
            for (i in 0 until node.childCount) {
                findById(node.getChild(i), id, depth + 1, maxDepth)?.let { return it }
            }
        } catch (_: Throwable) {
        }
        return null
    }

    /** Cheap tree fingerprint used to decide whether an action changed the screen. */
    fun signature(root: AccessibilityNodeInfo?, maxNodes: Int = MAX_SIGNATURE_NODES): Int {
        val counter = intArrayOf(0)
        return signatureOf(root, 0, maxNodes, counter)
    }

    /**
     * Fingerprint across every window with ONE shared node budget. Signing each
     * root separately would allow 300 nodes per window (app + system UI + IME),
     * making the ~1s change-detection poll needlessly expensive.
     */
    fun signature(roots: List<AccessibilityNodeInfo>, maxNodes: Int = MAX_SIGNATURE_NODES): Int {
        val counter = intArrayOf(0)
        var h = 17
        for (root in roots) h = h * 31 + signatureOf(root, 0, maxNodes, counter)
        return h
    }

    private fun signatureOf(
        node: AccessibilityNodeInfo?,
        depth: Int,
        maxNodes: Int,
        counter: IntArray,
    ): Int {
        if (node == null || depth > 30 || counter[0] >= maxNodes) return 0
        counter[0]++
        var h = 7
        try {
            h = h * 31 + (node.className?.toString()?.hashCode() ?: 0)
            // For a password field only the length participates, never the
            // content: enough to notice that typing changed something.
            if (node.isPassword) {
                h = h * 31 + (node.text?.length ?: 0)
            } else {
                h = h * 31 + (node.text?.toString()?.hashCode() ?: 0)
            }
            h = h * 31 + (node.contentDescription?.toString()?.hashCode() ?: 0)
            val r = Rect()
            node.getBoundsInScreen(r)
            h = h * 31 + r.left
            h = h * 31 + r.top
            h = h * 31 + r.right
            h = h * 31 + r.bottom
            h = h * 31 + (if (node.isChecked) 1 else 0)
            h = h * 31 + (if (node.isSelected) 1 else 0)
            h = h * 31 + (if (node.isFocused) 1 else 0)
            for (i in 0 until node.childCount) {
                h = h * 31 + signatureOf(node.getChild(i), depth + 1, maxNodes, counter)
            }
        } catch (_: Throwable) {
        }
        return h
    }
}

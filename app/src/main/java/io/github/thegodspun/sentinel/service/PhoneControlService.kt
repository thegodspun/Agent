package io.github.thegodspun.sentinel.service

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility service that lets the agent operate other apps: read what is on screen,
 * tap buttons by their label, type into fields, scroll and press system buttons. It only
 * acts when a tool call asks it to; it ignores the event stream.
 */
class PhoneControlService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    fun globalAction(name: String): Boolean {
        val action = when (name) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "power_dialog" -> GLOBAL_ACTION_POWER_DIALOG
            "lock_screen" -> GLOBAL_ACTION_LOCK_SCREEN
            "screenshot" -> GLOBAL_ACTION_TAKE_SCREENSHOT
            else -> throw IllegalArgumentException("Unknown action \"$name\"")
        }
        return performGlobalAction(action)
    }

    /** Text of everything on screen, one element per line, marking what can be tapped or typed into. */
    fun readScreen(maxLines: Int = 150): String {
        val root = rootInActiveWindow ?: return "The screen can't be read right now."
        val lines = mutableListOf<String>()
        lines += "App: ${root.packageName}"
        walk(root) { node ->
            val label = label(node) ?: return@walk
            val tags = buildList {
                if (node.isClickable) add("tap")
                if (node.isEditable) add("text field")
                if (node.isScrollable) add("scrolls")
                if (node.isChecked) add("checked")
            }
            lines += if (tags.isEmpty()) label else "$label  [${tags.joinToString()}]"
        }
        return lines.distinct().take(maxLines).joinToString("\n")
    }

    /** Taps the first element whose text or description matches [target] (exact match preferred). */
    fun tap(target: String): Boolean {
        val node = find(target) ?: return false
        var current: AccessibilityNodeInfo? = node
        while (current != null && !current.isClickable) current = current.parent
        return current?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    /** Types into the field labeled [field], or the focused field when [field] is null. */
    fun type(text: String, field: String?): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = if (field != null) {
            find(field)?.let { found -> if (found.isEditable) found else firstEditable(found) }
        } else {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: firstEditable(root)
        } ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun scroll(forward: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        var target: AccessibilityNodeInfo? = null
        walk(root) { if (target == null && it.isScrollable) target = it }
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return target?.performAction(action) ?: false
    }

    private fun find(target: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        var partial: AccessibilityNodeInfo? = null
        var exact: AccessibilityNodeInfo? = null
        walk(root) { node ->
            if (exact != null) return@walk
            val label = label(node) ?: return@walk
            when {
                label.equals(target, ignoreCase = true) -> exact = node
                partial == null && label.contains(target, ignoreCase = true) -> partial = node
            }
        }
        return exact ?: partial
    }

    private fun firstEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        walk(root) { if (found == null && it.isEditable) found = it }
        return found
    }

    private fun label(node: AccessibilityNodeInfo): String? =
        (node.text ?: node.contentDescription ?: node.hintText)?.toString()?.trim()?.takeIf { it.isNotEmpty() }

    private fun walk(node: AccessibilityNodeInfo, visit: (AccessibilityNodeInfo) -> Unit) {
        if (!node.isVisibleToUser) return
        visit(node)
        for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it, visit) }
    }

    companion object {
        @Volatile
        var instance: PhoneControlService? = null
            private set
    }
}

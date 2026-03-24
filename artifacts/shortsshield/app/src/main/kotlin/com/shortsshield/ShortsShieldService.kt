package com.shortsshield

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ShortsShieldService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val rootNode = rootInActiveWindow ?: return

        if (handleBackClicker(rootNode)) {
            rootNode.recycle()
            return
        }

        handleShelfPurger(rootNode)
        rootNode.recycle()
    }

    /**
     * Back-Clicker:
     * Detects reel_player_view_container or any view whose content description / text
     * contains "Shorts", then immediately fires GLOBAL_ACTION_BACK.
     */
    private fun handleBackClicker(root: AccessibilityNodeInfo): Boolean {
        val reelNodes = root.findAccessibilityNodeInfosByViewId(
            "com.google.android.youtube:id/reel_player_view_container"
        )
        if (reelNodes != null && reelNodes.isNotEmpty()) {
            reelNodes.forEach { it.recycle() }
            performGlobalAction(GLOBAL_ACTION_BACK)
            return true
        }

        if (containsShortsTitle(root)) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            return true
        }

        return false
    }

    /**
     * Recursively checks whether any node in the tree has text or
     * content description that equals "Shorts" (case-insensitive).
     */
    private fun containsShortsTitle(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        if (text.equals("Shorts", ignoreCase = true) || desc.equals("Shorts", ignoreCase = true)) {
            return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (containsShortsTitle(child)) {
                child.recycle()
                return true
            }
            child.recycle()
        }
        return false
    }

    /**
     * Shelf-Purger:
     * Looks for a Shorts shelf on the Home feed, finds the nearby
     * 'More options' button, clicks it, then clicks 'Fewer shorts'.
     */
    private fun handleShelfPurger(root: AccessibilityNodeInfo) {
        val shortsShelf = findShortsShelfNode(root) ?: return

        val moreOptionsNode = findMoreOptionsNear(root, shortsShelf)
        shortsShelf.recycle()

        if (moreOptionsNode != null) {
            moreOptionsNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            moreOptionsNode.recycle()

            postDelayed(500) {
                val freshRoot = rootInActiveWindow ?: return@postDelayed
                val fewerShortsNode = findNodeWithText(freshRoot, "Fewer shorts")
                fewerShortsNode?.let {
                    it.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    it.recycle()
                }
                freshRoot.recycle()
            }
        }
    }

    /**
     * Finds the first node whose text/description signals a Shorts shelf
     * (text contains "Shorts" but is NOT the back-clicker target — i.e. it
     * is part of a feed shelf, not the full-screen reel player).
     */
    private fun findShortsShelfNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return findNodeMatching(root) { node ->
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            (text.contains("Shorts", ignoreCase = true) ||
                    desc.contains("Shorts", ignoreCase = true)) &&
                    node.viewIdResourceName?.contains("reel_player") != true
        }
    }

    /**
     * Finds a sibling or nearby node whose content description or text
     * is "More options" (the three-dot button next to the shelf).
     */
    private fun findMoreOptionsNear(
        root: AccessibilityNodeInfo,
        referenceNode: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {
        val candidates = root.findAccessibilityNodeInfosByText("More options")
        return candidates?.firstOrNull()
    }

    /** Finds any node whose text equals [text] (case-insensitive). */
    private fun findNodeWithText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val results = root.findAccessibilityNodeInfosByText(text)
        return results?.firstOrNull { node ->
            node.text?.toString()?.equals(text, ignoreCase = true) == true
        }
    }

    /**
     * Generic depth-first search that returns the first node matching [predicate].
     * The caller is responsible for recycling the returned node.
     */
    private fun findNodeMatching(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeMatching(child, predicate)
            if (found != null) {
                if (found !== child) child.recycle()
                return found
            }
            child.recycle()
        }
        return null
    }

    /** Runs [block] after [delayMs] milliseconds on the main thread. */
    private fun postDelayed(delayMs: Long, block: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(block, delayMs)
    }

    override fun onInterrupt() {}
}

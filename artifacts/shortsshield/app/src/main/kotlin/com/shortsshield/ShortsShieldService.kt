package com.shortsshield

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * ShortsShieldService — high-performance AccessibilityService that:
 *
 * 1. BACK-CLICKER: Immediately navigates back when a Shorts reel is detected.
 * 2. SHELF-PURGER: Dismisses "Shorts" shelves on the Home feed via More Options → Fewer Shorts.
 *
 * Design principles for speed & reliability:
 * - View-ID lookup first (O(1) hash lookup, no tree traversal).
 * - Tree traversal only as fallback, capped at MAX_DEPTH to avoid hanging on huge trees.
 * - Cooldowns to prevent infinite back-action loops.
 * - AtomicBoolean flags to avoid re-entrant processing on rapid scroll events.
 * - All work on the AccessibilityService callback thread; UI posts only for deferred clicks.
 */
class ShortsShieldService : AccessibilityService() {

    companion object {
        private const val TAG = "ShortsShield"
        private const val YT_PACKAGE = "com.google.android.youtube"

        // YouTube view IDs for the Shorts reel player (confirmed across recent YT versions)
        private val SHORTS_PLAYER_IDS = setOf(
            "$YT_PACKAGE:id/reel_player_view_container",
            "$YT_PACKAGE:id/shorts_container",
            "$YT_PACKAGE:id/reel_channel_bar_inner_container",
            "$YT_PACKAGE:id/reel_watch_fragment_container",
            "$YT_PACKAGE:id/shorts_video_cell_root",
        )

        // Max tree depth to scan (prevents slow traversal on huge feed pages)
        private const val MAX_DEPTH = 12

        // Minimum ms between successive BACK actions (prevents double-back)
        private const val BACK_COOLDOWN_MS = 800L

        // Delay after clicking "More options" before searching for "Fewer shorts"
        private const val MENU_SETTLE_MS = 600L

        // Minimum ms between purge attempts
        private const val PURGE_COOLDOWN_MS = 3000L

        // Exact strings YouTube uses (verified via UI Automator)
        private const val MORE_OPTIONS_DESC = "More options"
        private const val FEWER_SHORTS_TEXT = "Fewer shorts"
        private const val NOT_INTERESTED_TEXT = "Not interested"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val isProcessing = AtomicBoolean(false)
    private val lastBackMs = AtomicLong(0L)
    private val lastPurgeMs = AtomicLong(0L)

    // -------------------------------------------------------------------------
    // Service lifecycle
    // -------------------------------------------------------------------------

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            // Restrict to YouTube only — no overhead from other apps
            packageNames = arrayOf(YT_PACKAGE)
            eventTypes = (
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED
            )
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = (
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            )
            notificationTimeout = 50 // ms — respond as fast as possible
        }
        Log.i(TAG, "ShortsShield connected and active")
    }

    override fun onInterrupt() {
        Log.w(TAG, "ShortsShield interrupted")
    }

    // -------------------------------------------------------------------------
    // Event processing
    // -------------------------------------------------------------------------

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.packageName?.toString() != YT_PACKAGE) return

        // Prevent re-entrant processing (rapid scroll events)
        if (!isProcessing.compareAndSet(false, true)) return

        try {
            processEvent(event)
        } finally {
            isProcessing.set(false)
        }
    }

    private fun processEvent(event: AccessibilityEvent) {
        // --- Step 1: Fast path via event source node ---
        val source = event.source
        if (source != null) {
            val handled = tryHandleNode(source)
            source.recycle()
            if (handled) return
        }

        // --- Step 2: Full window scan ---
        val root = rootInActiveWindow ?: return
        try {
            // Back-clicker: check for Shorts player
            if (detectAndBlockShortsPlayer(root)) return

            // Shelf-purger: check for Shorts shelf on home feed
            val now = SystemClock.elapsedRealtime()
            if (now - lastPurgeMs.get() >= PURGE_COOLDOWN_MS) {
                detectAndPurgeShortsShelf(root)
            }
        } finally {
            root.recycle()
        }
    }

    // -------------------------------------------------------------------------
    // Back-Clicker — O(1) ID lookup then fallback tree scan
    // -------------------------------------------------------------------------

    /**
     * Returns true if a Shorts player was found and BACK was fired.
     */
    private fun detectAndBlockShortsPlayer(root: AccessibilityNodeInfo): Boolean {
        // 1. Try each known Shorts player view ID (fastest)
        for (id in SHORTS_PLAYER_IDS) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (!nodes.isNullOrEmpty()) {
                nodes.forEach { it.recycle() }
                return fireBack("player ID: $id")
            }
        }

        // 2. Fallback: depth-limited tree scan for "Shorts" title nodes
        if (treeContainsShortsTitle(root, 0)) {
            return fireBack("title scan")
        }

        return false
    }

    private fun tryHandleNode(node: AccessibilityNodeInfo): Boolean {
        val id = node.viewIdResourceName ?: return false
        if (id in SHORTS_PLAYER_IDS) {
            return fireBack("source node ID: $id")
        }
        return false
    }

    private fun fireBack(reason: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBackMs.get() < BACK_COOLDOWN_MS) return true // already fired recently
        lastBackMs.set(now)
        Log.d(TAG, "Firing BACK — reason: $reason")
        performGlobalAction(GLOBAL_ACTION_BACK)
        return true
    }

    /**
     * Depth-limited DFS. Checks text and contentDescription for the word "Shorts".
     * Returns true as soon as a match is found (short-circuits the entire tree).
     */
    private fun treeContainsShortsTitle(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (depth > MAX_DEPTH) return false

        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()

        // Exact match "Shorts" or it's a title node with "Shorts" in it
        if (isShortsTitleNode(text, desc)) return true

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            val found = treeContainsShortsTitle(child, depth + 1)
            child.recycle()
            if (found) return true
        }
        return false
    }

    private fun isShortsTitleNode(text: String?, desc: String?): Boolean {
        return text == "Shorts" || desc == "Shorts"
    }

    // -------------------------------------------------------------------------
    // Shelf-Purger — find Shorts shelf → More options → Fewer shorts
    // -------------------------------------------------------------------------

    private fun detectAndPurgeShortsShelf(root: AccessibilityNodeInfo) {
        // Look for a Shorts shelf title/header in the home feed
        val shelfNode = findShortsShelfHeader(root, 0) ?: return
        shelfNode.recycle()

        lastPurgeMs.set(SystemClock.elapsedRealtime())
        Log.d(TAG, "Shorts shelf found — looking for More options button")

        // Find the "More options" button (⋮) for this shelf
        val moreOptionsNode = findNodeByContentDesc(root, MORE_OPTIONS_DESC)
            ?: return

        Log.d(TAG, "Clicking More options")
        moreOptionsNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        moreOptionsNode.recycle()

        // After the menu appears, click "Fewer shorts"
        mainHandler.postDelayed({
            clickMenuOption(FEWER_SHORTS_TEXT)
        }, MENU_SETTLE_MS)
    }

    private fun clickMenuOption(text: String) {
        val root = rootInActiveWindow ?: return
        try {
            val node = findNodeByText(root, text)
            if (node != null) {
                Log.d(TAG, "Clicking menu option: $text")
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                node.recycle()
            } else {
                // Fallback: try "Not interested"
                val fallback = findNodeByText(root, NOT_INTERESTED_TEXT)
                if (fallback != null) {
                    Log.d(TAG, "Fallback — clicking: $NOT_INTERESTED_TEXT")
                    fallback.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    fallback.recycle()
                }
            }
        } finally {
            root.recycle()
        }
    }

    /**
     * Finds a Shorts shelf header node — a node whose text is "Shorts" that is
     * NOT inside the Shorts reel player (i.e. it's a feed shelf title).
     */
    private fun findShortsShelfHeader(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (depth > MAX_DEPTH) return null

        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val id = node.viewIdResourceName ?: ""

        // It's a Shorts shelf if it says "Shorts" and is NOT the reel player
        if ((text == "Shorts" || desc == "Shorts") && id !in SHORTS_PLAYER_IDS) {
            return AccessibilityNodeInfo.obtain(node) // caller must recycle
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findShortsShelfHeader(child, depth + 1)
            if (found != null) {
                child.recycle()
                return found
            }
            child.recycle()
        }
        return null
    }

    /**
     * Finds the first node whose contentDescription matches [desc] exactly.
     * Uses system findAccessibilityNodeInfosByText as fast path, then verifies
     * the content description for precision.
     */
    private fun findNodeByContentDesc(
        root: AccessibilityNodeInfo,
        desc: String
    ): AccessibilityNodeInfo? {
        val candidates = root.findAccessibilityNodeInfosByText(desc)
        if (!candidates.isNullOrEmpty()) {
            val exact = candidates.firstOrNull {
                it.contentDescription?.toString() == desc
            }
            // Recycle non-chosen candidates
            candidates.filter { it !== exact }.forEach { it.recycle() }
            return exact
        }
        return null
    }

    /**
     * Finds a node whose visible text matches [text] (case-insensitive).
     */
    private fun findNodeByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val candidates = root.findAccessibilityNodeInfosByText(text)
        if (!candidates.isNullOrEmpty()) {
            val exact = candidates.firstOrNull {
                it.text?.toString()?.equals(text, ignoreCase = true) == true
            }
            candidates.filter { it !== exact }.forEach { it.recycle() }
            return exact
        }
        return null
    }
}

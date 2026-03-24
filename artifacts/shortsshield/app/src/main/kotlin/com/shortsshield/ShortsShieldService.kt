package com.shortsshield

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * ShortsShieldService
 *
 * An AccessibilityService with two independent, configurable shields:
 *
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │ SHIELD A — Back-Clicker                                             │
 * │  Detects the Shorts reel player via view-ID lookup (O(1)) and       │
 * │  fires GLOBAL_ACTION_BACK immediately. Falls back to a depth-capped │
 * │  title scan only when no ID match is found.                         │
 * ├─────────────────────────────────────────────────────────────────────┤
 * │ SHIELD B — Shelf-Purger                                             │
 * │  On the Home feed, finds Shorts shelf headers and clicks            │
 * │  "More options" → "Fewer shorts" to suppress the shelf.            │
 * └─────────────────────────────────────────────────────────────────────┘
 *
 * Performance design:
 *  - Event source node checked first (no root traversal required).
 *  - View-ID lookup before any tree scan.
 *  - Tree scan is depth-capped and short-circuits on first hit.
 *  - AtomicBoolean guard prevents re-entrant event processing.
 *  - Separate cooldowns for BACK actions and shelf purges.
 *  - All work on the AccessibilityService thread; only deferred shelf
 *    clicks are posted to the main Handler.
 */
class ShortsShieldService : AccessibilityService() {

    // ── Constants ─────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "ShortsShield"
        const val YT_PACKAGE = "com.google.android.youtube"

        /**
         * Known view IDs for the Shorts reel player across YouTube releases.
         * IDs are checked in order; first match wins.
         */
        private val SHORTS_PLAYER_IDS = listOf(
            "$YT_PACKAGE:id/reel_player_view_container",
            "$YT_PACKAGE:id/shorts_container",
            "$YT_PACKAGE:id/reel_watch_fragment_container",
            "$YT_PACKAGE:id/reel_channel_bar_inner_container",
            "$YT_PACKAGE:id/shorts_video_cell_root",
            "$YT_PACKAGE:id/shorts_standalone_player_fragment",
            "$YT_PACKAGE:id/reel_player_page_container",
        )

        /** YouTube activity class names that indicate a Shorts session. */
        private val SHORTS_ACTIVITY_NAMES = listOf(
            "com.google.android.apps.youtube.app.watchwhile.WatchWhileActivity",
            "com.google.android.youtube.ShortsActivity",
        )

        private const val MAX_DEPTH          = 10   // tree scan depth cap
        private const val BACK_COOLDOWN_MS   = 700L  // min ms between BACK fires
        private const val PURGE_COOLDOWN_MS  = 4_000L
        private const val MENU_SETTLE_MS     = 550L  // wait for context menu to open

        private const val TEXT_MORE_OPTIONS  = "More options"
        private const val TEXT_FEWER_SHORTS  = "Fewer shorts"
        private const val TEXT_NOT_INTERESTED = "Not interested"
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private val handler       = Handler(Looper.getMainLooper())
    private val processing    = AtomicBoolean(false)
    private val lastBackMs    = AtomicLong(0L)
    private val lastPurgeMs   = AtomicLong(0L)
    private lateinit var prefs: ShieldPrefs

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        prefs = ShieldPrefs.get(this)
        applyServiceConfig()
        NotificationHelper.createChannel(this)
        if (prefs.showNotification) {
            NotificationHelper.update(this, ShieldStats.getToday(this))
        }
        sendBroadcast(Intent(ACTION_STATUS_CHANGED).putExtra(EXTRA_ACTIVE, true))
        Log.i(TAG, "Service connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        NotificationHelper.cancel(this)
        sendBroadcast(Intent(ACTION_STATUS_CHANGED).putExtra(EXTRA_ACTIVE, false))
        return super.onUnbind(intent)
    }

    override fun onInterrupt() = Log.w(TAG, "Service interrupted")

    // ── Event entry-point ─────────────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.packageName?.toString() != YT_PACKAGE) return
        if (!processing.compareAndSet(false, true)) return
        try {
            handleEvent(event)
        } finally {
            processing.set(false)
        }
    }

    private fun handleEvent(event: AccessibilityEvent) {
        // ─── Fast path 1: source node ID check (no root fetch needed) ───
        if (prefs.blockShortsPlayer) {
            val src = event.source
            if (src != null) {
                val id = src.viewIdResourceName
                src.recycle()
                if (id != null && id in SHORTS_PLAYER_IDS) {
                    fireBack("source-id:$id")
                    return
                }
            }
        }

        // ─── Fast path 2: window state change → check class name ────────
        if (prefs.blockDeepLinks &&
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            val cls = event.className?.toString() ?: ""
            if (SHORTS_ACTIVITY_NAMES.any { it == cls }) {
                fireBack("activity:$cls")
                return
            }
        }

        // ─── Full root scan ───────────────────────────────────────────────
        val root = rootInActiveWindow ?: return
        try {
            if (prefs.blockShortsPlayer && detectPlayer(root)) return
            val now = SystemClock.elapsedRealtime()
            if (prefs.hideShortsShelf && now - lastPurgeMs.get() >= PURGE_COOLDOWN_MS) {
                purgeShelf(root)
            }
        } finally {
            root.recycle()
        }
    }

    // ── Shield A: Back-Clicker ────────────────────────────────────────────────

    private fun detectPlayer(root: AccessibilityNodeInfo): Boolean {
        // ID lookup first (hashmap speed)
        for (id in SHORTS_PLAYER_IDS) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (!nodes.isNullOrEmpty()) {
                nodes.forEach { it.recycle() }
                return fireBack("view-id:$id")
            }
        }
        // Fallback: depth-capped title scan
        return if (hasShortsTitle(root, 0)) fireBack("title-scan") else false
    }

    /**
     * Returns true if any node in the subtree has text or contentDescription
     * exactly equal to "Shorts" — indicating this is the Shorts tab/player.
     */
    private fun hasShortsTitle(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (depth > MAX_DEPTH) return false
        if (node.text?.toString() == "Shorts" ||
            node.contentDescription?.toString() == "Shorts"
        ) return true
        repeat(node.childCount) { i ->
            val child = node.getChild(i) ?: return@repeat
            val found = hasShortsTitle(child, depth + 1)
            child.recycle()
            if (found) return true
        }
        return false
    }

    private fun fireBack(reason: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBackMs.get() < BACK_COOLDOWN_MS) return true
        lastBackMs.set(now)
        performGlobalAction(GLOBAL_ACTION_BACK)
        recordBlock()
        Log.d(TAG, "BACK fired — $reason")
        return true
    }

    // ── Shield B: Shelf-Purger ────────────────────────────────────────────────

    private fun purgeShelf(root: AccessibilityNodeInfo) {
        val shelf = findShelfNode(root, 0) ?: return
        shelf.recycle()
        lastPurgeMs.set(SystemClock.elapsedRealtime())

        val moreBtn = findByContentDesc(root, TEXT_MORE_OPTIONS) ?: return
        Log.d(TAG, "Shelf found — clicking More options")
        moreBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        moreBtn.recycle()

        handler.postDelayed({ clickMenuOption() }, MENU_SETTLE_MS)
    }

    private fun clickMenuOption() {
        val root = rootInActiveWindow ?: return
        try {
            val target = findByText(root, TEXT_FEWER_SHORTS)
                ?: findByText(root, TEXT_NOT_INTERESTED)
                ?: return
            Log.d(TAG, "Clicking: ${target.text}")
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            target.recycle()
        } finally {
            root.recycle()
        }
    }

    /**
     * Finds a Shorts shelf header — a node with text "Shorts" that is NOT
     * inside the reel player (so it's a home-feed shelf title, not the player).
     */
    private fun findShelfNode(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (depth > MAX_DEPTH) return null
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val id   = node.viewIdResourceName ?: ""
        if ((text == "Shorts" || desc == "Shorts") && id !in SHORTS_PLAYER_IDS) {
            return AccessibilityNodeInfo.obtain(node)
        }
        repeat(node.childCount) { i ->
            val child = node.getChild(i) ?: return@repeat
            val found = findShelfNode(child, depth + 1)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    // ── Node helpers ──────────────────────────────────────────────────────────

    private fun findByContentDesc(root: AccessibilityNodeInfo, desc: String): AccessibilityNodeInfo? {
        val all = root.findAccessibilityNodeInfosByText(desc) ?: return null
        val match = all.firstOrNull { it.contentDescription?.toString() == desc }
        all.filter { it !== match }.forEach { it.recycle() }
        return match
    }

    private fun findByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val all = root.findAccessibilityNodeInfosByText(text) ?: return null
        val match = all.firstOrNull { it.text?.toString().equals(text, ignoreCase = true) }
        all.filter { it !== match }.forEach { it.recycle() }
        return match
    }

    // ── Stats & notification ──────────────────────────────────────────────────

    private fun recordBlock() {
        ShieldStats.recordBlocked(this)
        NotificationHelper.update(this, ShieldStats.getToday(this))
    }

    // ── Config reload ─────────────────────────────────────────────────────────

    private fun applyServiceConfig() {
        serviceInfo = serviceInfo?.also { info ->
            info.packageNames = arrayOf(YT_PACKAGE)
            info.eventTypes = (
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED
            )
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.flags = (
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            )
            info.notificationTimeout = 50
        }
    }

    companion object {
        const val ACTION_STATUS_CHANGED = "com.shortsshield.STATUS_CHANGED"
        const val EXTRA_ACTIVE          = "active"
    }
}

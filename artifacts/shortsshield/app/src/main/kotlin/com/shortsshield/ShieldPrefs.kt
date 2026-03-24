package com.shortsshield

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Single source of truth for all user-configurable settings.
 * Thread-safe reads via SharedPreferences (already thread-safe for gets).
 */
class ShieldPrefs private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    // ── Feature toggles ───────────────────────────────────────────────────────

    /** Navigate back whenever a Shorts reel player is detected. */
    var blockShortsPlayer: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_PLAYER, true)
        set(v) = prefs.edit { putBoolean(KEY_BLOCK_PLAYER, v) }

    /** Hide Shorts shelves from the YouTube Home feed. */
    var hideShortsShelf: Boolean
        get() = prefs.getBoolean(KEY_HIDE_SHELF, true)
        set(v) = prefs.edit { putBoolean(KEY_HIDE_SHELF, v) }

    /** Show a persistent notification while the shield is active. */
    var showNotification: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATION, true)
        set(v) = prefs.edit { putBoolean(KEY_NOTIFICATION, v) }

    /** Block Shorts opened via external links / deep-links. */
    var blockDeepLinks: Boolean
        get() = prefs.getBoolean(KEY_DEEP_LINKS, true)
        set(v) = prefs.edit { putBoolean(KEY_DEEP_LINKS, v) }

    // ── Statistics ────────────────────────────────────────────────────────────

    var totalBlocked: Long
        get() = prefs.getLong(KEY_TOTAL, 0L)
        set(v) = prefs.edit { putLong(KEY_TOTAL, v) }

    var blockedToday: Long
        get() = prefs.getLong(KEY_TODAY, 0L)
        set(v) = prefs.edit { putLong(KEY_TODAY, v) }

    var statsDate: String
        get() = prefs.getString(KEY_DATE, "") ?: ""
        set(v) = prefs.edit { putString(KEY_DATE, v) }

    fun resetStats() = prefs.edit {
        putLong(KEY_TOTAL, 0L)
        putLong(KEY_TODAY, 0L)
    }

    companion object {
        private const val PREF_FILE       = "shield_prefs"
        private const val KEY_BLOCK_PLAYER = "block_player"
        private const val KEY_HIDE_SHELF   = "hide_shelf"
        private const val KEY_NOTIFICATION = "show_notification"
        private const val KEY_DEEP_LINKS   = "block_deep_links"
        private const val KEY_TOTAL        = "total_blocked"
        private const val KEY_TODAY        = "today_blocked"
        private const val KEY_DATE         = "stats_date"

        @Volatile
        private var instance: ShieldPrefs? = null

        fun get(context: Context): ShieldPrefs =
            instance ?: synchronized(this) {
                instance ?: ShieldPrefs(context.applicationContext).also { instance = it }
            }
    }
}

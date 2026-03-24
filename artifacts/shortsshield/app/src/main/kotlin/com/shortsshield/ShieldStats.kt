package com.shortsshield

import android.content.Context
import java.time.LocalDate

/**
 * Thin wrapper around [ShieldPrefs] that tracks per-day and all-time blocked counts.
 * Resets the today counter automatically when the date changes.
 */
object ShieldStats {

    fun recordBlocked(context: Context) {
        val prefs = ShieldPrefs.get(context)
        val today = LocalDate.now().toString()

        if (prefs.statsDate != today) {
            prefs.blockedToday = 0L
            prefs.statsDate = today
        }

        prefs.blockedToday = prefs.blockedToday + 1
        prefs.totalBlocked = prefs.totalBlocked + 1
    }

    fun getToday(context: Context): Long {
        val prefs = ShieldPrefs.get(context)
        val today = LocalDate.now().toString()
        return if (prefs.statsDate == today) prefs.blockedToday else 0L
    }

    fun getTotal(context: Context): Long = ShieldPrefs.get(context).totalBlocked

    fun reset(context: Context) = ShieldPrefs.get(context).resetStats()
}

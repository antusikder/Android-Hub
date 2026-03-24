package com.shortsshield

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.accessibilityservice.AccessibilityServiceInfo
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.shortsshield.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: ShieldPrefs

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ShortsShieldService.ACTION_STATUS_CHANGED) {
                refreshStatus()
                refreshStats()
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = ShieldPrefs.get(this)

        configureToggleRows()
        syncToggles()
        setupButtons()
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(
            this, statusReceiver,
            IntentFilter(ShortsShieldService.ACTION_STATUS_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        refreshStatus()
        refreshStats()
    }

    override fun onPause() {
        super.onPause()
        runCatching { unregisterReceiver(statusReceiver) }
    }

    // ── Configure toggle rows ─────────────────────────────────────────────────

    private fun configureToggleRows() {
        // Block Shorts player (back-clicker)
        binding.rowBlockPlayer.toggleTitle.text    = "Block Shorts Player"
        binding.rowBlockPlayer.toggleSubtitle.text = "Go back immediately when Shorts opens"

        // Hide Shorts shelf on home feed
        binding.rowHideShelf.toggleTitle.text    = "Hide Shorts Shelf"
        binding.rowHideShelf.toggleSubtitle.text = "Remove Shorts section from Home feed"

        // Block deep-link launches
        binding.rowBlockDeepLinks.toggleTitle.text    = "Block Link Opens"
        binding.rowBlockDeepLinks.toggleSubtitle.text = "Redirect Shorts opened via external links"

        // Persistent notification
        binding.rowNotification.toggleTitle.text    = "Show Notification"
        binding.rowNotification.toggleSubtitle.text = "Persistent status notification while active"
    }

    private fun syncToggles() {
        binding.rowBlockPlayer.toggleSwitch.isChecked    = prefs.blockShortsPlayer
        binding.rowHideShelf.toggleSwitch.isChecked      = prefs.hideShortsShelf
        binding.rowBlockDeepLinks.toggleSwitch.isChecked = prefs.blockDeepLinks
        binding.rowNotification.toggleSwitch.isChecked   = prefs.showNotification

        binding.rowBlockPlayer.toggleSwitch.setOnCheckedChangeListener { _, v ->
            prefs.blockShortsPlayer = v
        }
        binding.rowHideShelf.toggleSwitch.setOnCheckedChangeListener { _, v ->
            prefs.hideShortsShelf = v
        }
        binding.rowBlockDeepLinks.toggleSwitch.setOnCheckedChangeListener { _, v ->
            prefs.blockDeepLinks = v
        }
        binding.rowNotification.toggleSwitch.setOnCheckedChangeListener { _, v ->
            prefs.showNotification = v
            if (v) NotificationHelper.update(this, ShieldStats.getToday(this))
            else   NotificationHelper.cancel(this)
        }
    }

    private fun setupButtons() {
        binding.cardStatus.setOnClickListener { openAccessibilitySettings() }
        binding.btnPrimary.setOnClickListener { openAccessibilitySettings() }

        binding.btnOpenYoutube.setOnClickListener {
            packageManager
                .getLaunchIntentForPackage(ShortsShieldService.YT_PACKAGE)
                ?.let { startActivity(it) }
        }

        binding.btnResetStats.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Reset Statistics?")
                .setMessage("This will clear all blocked counts.")
                .setPositiveButton("Reset") { _, _ ->
                    ShieldStats.reset(this)
                    refreshStats()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.btnGithub.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com")))
        }
    }

    // ── UI refresh ────────────────────────────────────────────────────────────

    private fun refreshStatus() {
        val active = isServiceActive()
        val red    = ContextCompat.getColor(this, R.color.brand_red)
        val dim    = ContextCompat.getColor(this, R.color.on_surface_dim)
        val green  = ContextCompat.getColor(this, R.color.green_active)
        val stroke = ContextCompat.getColor(this, R.color.surface_stroke)

        with(binding) {
            statusIcon.setImageResource(
                if (active) R.drawable.ic_shield_active else R.drawable.ic_shield_inactive
            )
            statusTitle.text = if (active) "Shield Active" else "Shield Inactive"
            statusSubtitle.text = if (active)
                "Blocking YouTube Shorts in real-time"
            else
                "Tap to open Accessibility Settings"

            statusTitle.setTextColor(if (active) green else dim)
            cardStatus.strokeColor = if (active) green else stroke

            btnPrimary.text = if (active) "Manage in Accessibility" else "Enable Shield"
            btnOpenYoutube.isEnabled = active
            instructionCard.visibility = if (active) View.GONE else View.VISIBLE
        }
    }

    private fun refreshStats() {
        binding.statToday.text = ShieldStats.getToday(this).toString()
        binding.statTotal.text = ShieldStats.getTotal(this).toString()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun openAccessibilitySettings() =
        startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))

    private fun isServiceActive(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == packageName }
    }
}

package com.shortsshield

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import com.shortsshield.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnEnableShield.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.btnOpenYoutube.setOnClickListener {
            val intent = packageManager.getLaunchIntentForPackage("com.google.android.youtube")
            if (intent != null) {
                startActivity(intent)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val active = isServiceActive()
        with(binding) {
            statusIndicator.setImageResource(
                if (active) R.drawable.ic_status_active else R.drawable.ic_status_inactive
            )
            statusTitle.text = if (active) "Shield Active" else "Shield Inactive"
            statusSubtitle.text = if (active)
                "ShortsShield is blocking YouTube Shorts"
            else
                "Tap 'Enable Shield' to activate"

            btnEnableShield.text = if (active) "Manage in Accessibility" else "Enable Shield"
            btnOpenYoutube.isEnabled = active
        }
    }

    private fun isServiceActive(): Boolean {
        val am = getSystemService<AccessibilityManager>() ?: return false
        val services = am.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        return services.any { it.resolveInfo.serviceInfo.packageName == packageName }
    }
}

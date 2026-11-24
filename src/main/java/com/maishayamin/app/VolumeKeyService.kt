package com.maishayamin.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * ভলিউম বাটন দুবার চাপলে অ্যাপ চালু/বন্ধ করার সার্ভিস।
 * প্রথম চাপ স্বাভাবিকভাবে ভলিউম বদলায়, দ্বিতীয় চাপটি এই সার্ভিস ধরে নেয়।
 */
class VolumeKeyService : AccessibilityService() {

    private var lastDown = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return false
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP &&
            event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN
        ) return false

        val now = SystemClock.uptimeMillis()
        if (lastDown != 0L && now - lastDown < 700) {
            lastDown = 0L
            val i = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("vol_toggle", true)
            }
            try {
                startActivity(i)
            } catch (e: Exception) {
            }
            return true
        }
        lastDown = now
        return false
    }
}

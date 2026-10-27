package com.macdroid.service

import android.accessibilityservice.AccessibilityService
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.macdroid.MacDroidApplication

/**
 * DexoLinkAccessibilityService provides background clipboard synchronization on Android 10+ (API 29+).
 *
 * Android 10+ restricts background applications from reading ClipboardManager.getPrimaryClip()
 * unless the app has active window focus or is an enabled AccessibilityService.
 * By running this service, DexoLink can reliably detect and sync copied text from any app
 * directly to the MacBook even when DexoLink is minimized or the screen is locked.
 */
class DexoLinkAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "DexoLinkA11yService"

        @Volatile
        var instance: DexoLinkAccessibilityService? = null
            private set

        val isServiceRunning: Boolean
            get() = instance != null
    }

    private var clipboardManager: ClipboardManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastObservedText: String? = null
    private var lastObservedTime: Long = 0L

    private val clipChangedListener = ClipboardManager.OnPrimaryClipChangedListener {
        Log.d(TAG, "OnPrimaryClipChanged detected via AccessibilityService")
        scheduleClipboardCheck(delayMs = 50L)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        try {
            clipboardManager?.addPrimaryClipChangedListener(clipChangedListener)
            Log.i(TAG, "DexoLink Accessibility Service connected and listening for clipboard changes")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register clip changed listener: ${e.message}")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // Intercept events that typically correspond to copying, cutting, or text selection
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // Short debounce to allow the source app's clipboard write to settle
                scheduleClipboardCheck(delayMs = 120L)
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "DexoLink Accessibility Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            clipboardManager?.removePrimaryClipChangedListener(clipChangedListener)
        } catch (e: Exception) {
            // Ignore
        }
        mainHandler.removeCallbacksAndMessages(null)
        if (instance === this) {
            instance = null
        }
        Log.i(TAG, "DexoLink Accessibility Service destroyed")
    }

    private fun scheduleClipboardCheck(delayMs: Long) {
        mainHandler.removeCallbacks(checkClipboardRunnable)
        mainHandler.postDelayed(checkClipboardRunnable, delayMs)
    }

    private val checkClipboardRunnable = Runnable {
        syncClipboardSafely()
    }

    /**
     * Reads current clipboard text safely using Accessibility privileges.
     */
    fun readClipboardText(): String? {
        return try {
            val cm = clipboardManager ?: (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
            val clip = cm?.primaryClip ?: return null
            if (clip.itemCount > 0) {
                clip.getItemAt(0)?.coerceToText(this)?.toString()
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error reading clipboard via accessibility context: ${e.message}")
            null
        }
    }

    /**
     * Checks if new text is present on the clipboard and synchronizes it to paired devices.
     */
    fun syncClipboardSafely() {
        val currentText = readClipboardText()
        if (currentText.isNullOrBlank()) return

        val now = System.currentTimeMillis()
        if (currentText == lastObservedText && (now - lastObservedTime) < 800L) {
            // Debounce identical rapid fire events
            return
        }
        lastObservedText = currentText
        lastObservedTime = now

        try {
            val cm = MacDroidApplication.connectionManager
            val clipManager = cm.clipboardManager

            // Enforce mode (DISABLED / MAC_TO_ANDROID check) & duplicate suppression (SHA-256 hash match)
            if (!clipManager.canSend(currentText)) {
                return
            }

            if (cm.isConnected()) {
                Log.d(TAG, "Background clipboard detected via Accessibility -> Auto-syncing to Mac: ${currentText.take(30)}...")
                cm.sendClipboard(currentText, force = false)
            } else {
                Log.d(TAG, "Background clipboard detected while offline -> Queueing for Mac: ${currentText.take(30)}...")
                clipManager.queueOutgoing(currentText)
                cm.triggerAutoReconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync background clipboard: ${e.message}", e)
        }
    }
}

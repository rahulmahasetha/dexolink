package com.macdroid.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import com.macdroid.MacDroidApplication

/**
 * Handles Android system text selection context menu action:
 * When the user selects text in any app (Chrome, Docs, WhatsApp), "Send to Mac"
 * appears directly in the context menu (alongside Copy/Paste).
 */
class ProcessTextActivity : Activity() {

    companion object {
        private const val TAG = "ProcessTextActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val selectedText = intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            if (!selectedText.isNullOrBlank()) {
                val connectionManager = MacDroidApplication.connectionManager
                val peer = connectionManager.currentPeerName ?: connectionManager.trustStore.getPairedDevice()?.deviceName ?: "Mac"
                val preview = if (selectedText.length > 25) selectedText.take(25) + "..." else selectedText

                if (connectionManager.isConnected()) {
                    connectionManager.sendClipboard(selectedText, force = true)
                    Toast.makeText(this, "Sent to $peer: \"$preview\"", Toast.LENGTH_SHORT).show()
                    Log.d(TAG, "Sent selected text to $peer ($selectedText)")
                } else {
                    val queued = connectionManager.clipboardManager.queueOutgoing(selectedText)
                    connectionManager.triggerAutoReconnect()
                    if (queued) {
                        Toast.makeText(this, "Queued for $peer: \"$preview\" (will sync when connected)", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Already queued for $peer", Toast.LENGTH_SHORT).show()
                    }
                    Log.d(TAG, "Queued selected text for $peer (queued=$queued)")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing selected text: ${e.message}", e)
        } finally {
            finish()
        }
    }
}

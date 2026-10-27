package com.macdroid.ui

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import com.macdroid.MacDroidApplication

/**
 * Transparent trampoline activity used by the DexoLink persistent notification
 * and Quick Settings tile to read the system clipboard on demand on Android 10+.
 *
 * Because this activity gains momentary user focus, Android allows it to read
 * the primary clip immediately and reliably without requiring root or ADB.
 */
class ClipboardSyncActivity : Activity() {

    companion object {
        private const val TAG = "ClipboardSyncActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val connectionManager = MacDroidApplication.connectionManager
            val paired = connectionManager.trustStore.getPairedDevice()
            val peerName = connectionManager.currentPeerName ?: paired?.deviceName ?: "Mac"

            val clipManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = clipManager?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0)?.text?.toString()
                if (!text.isNullOrBlank()) {
                    val preview = if (text.length > 25) text.take(25) + "..." else text
                    if (connectionManager.isConnected()) {
                        val sent = connectionManager.sendClipboard(text, force = true)
                        if (sent) {
                            Toast.makeText(this, "Synced to $peerName: \"$preview\"", Toast.LENGTH_SHORT).show()
                            Log.d(TAG, "Successfully synced clipboard to $peerName ($text)")
                        } else {
                            Toast.makeText(this, "Clipboard already synced with $peerName", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        val queued = connectionManager.clipboardManager.queueOutgoing(text)
                        connectionManager.triggerAutoReconnect()
                        if (queued) {
                            Toast.makeText(this, "Queued for $peerName: \"$preview\" (will sync when connected)", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "Already queued for $peerName", Toast.LENGTH_SHORT).show()
                        }
                        Log.d(TAG, "Queued clipboard for $peerName while disconnected (queued=$queued)")
                    }
                } else {
                    Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in ClipboardSyncActivity: ${e.message}", e)
            Toast.makeText(this, "Error syncing clipboard: ${e.message}", Toast.LENGTH_SHORT).show()
        } finally {
            finish()
        }
    }
}

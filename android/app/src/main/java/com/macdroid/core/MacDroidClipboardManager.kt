package com.macdroid.core

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import java.security.MessageDigest

enum class ClipboardSyncMode(val id: String, val title: String, val description: String) {
    ANDROID_TO_MAC("android_to_mac", "Android → Mac", "Copy on Android updates Mac; Mac changes ignored"),
    MAC_TO_ANDROID("mac_to_android", "Mac → Android", "Copy on Mac updates Android; Android changes ignored"),
    TWO_WAY("two_way", "Two-Way Sync", "Copy on either device updates the other");

    companion object {
        fun fromId(id: String?): ClipboardSyncMode {
            return entries.find { it.id == id } ?: TWO_WAY
        }
    }
}

class MacDroidClipboardManager(private val context: Context) {
    companion object {
        private const val TAG = "MacDroidClipboard"
        private const val PREFS_NAME = "dexolink_clipboard_prefs"
        private const val KEY_SYNC_MODE = "clipboard_sync_mode"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var syncMode: ClipboardSyncMode = ClipboardSyncMode.fromId(prefs.getString(KEY_SYNC_MODE, ClipboardSyncMode.TWO_WAY.id))
        set(value) {
            field = value
            prefs.edit().putString(KEY_SYNC_MODE, value.id).apply()
            Log.d(TAG, "ClipboardSyncMode persisted: ${value.title}")
        }

    private val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    private var lastReceivedHash: String = ""
    private var lastSentHash: String = ""

    var lastSyncedSnippet: String? = null
        private set

    private fun computeHash(text: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(text.toByteArray(Charsets.UTF_8))
            hashBytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            text.hashCode().toString()
        }
    }

    /**
     * Handles incoming clipboard text from the Mac.
     * Returns true if clipboard was updated, false if it was rejected/duplicate/echo.
     */
    fun handleMessage(content: String): Boolean {
        // Enforce mode: In ANDROID_TO_MAC mode, never apply incoming clipboard from Mac
        if (syncMode == ClipboardSyncMode.ANDROID_TO_MAC) {
            Log.d(TAG, "[Mode: Android → Mac] Dropped incoming clipboard push from Mac (reverse sync prohibited)")
            return false
        }

        if (content.isEmpty()) return false
        val hash = computeHash(content)

        // Loop prevention: ignore if already received or if it originated from us
        if (hash == lastReceivedHash || hash == lastSentHash) {
            Log.d(TAG, "Ignored duplicate/echo clipboard push (hash: ${hash.take(8)})")
            return false
        }

        lastReceivedHash = hash
        lastSyncedSnippet = content

        return try {
            val clip = ClipData.newPlainText("DexoLink", content)
            clipboardManager?.setPrimaryClip(clip)
            Log.d(TAG, "[Clipboard Receipt] Set Android clipboard: \"${content.take(30)}...\" (${content.length} chars)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "[Clipboard Error] Failed to set clipboard: ${e.message}", e)
            false
        }
    }

    /**
     * Checks if this text should be sent to Mac.
     * Enforces sync mode and prevents echo loops.
     */
    fun shouldSend(content: String): Boolean {
        // Enforce mode: In MAC_TO_ANDROID mode, never send local Android clipboard to Mac
        if (syncMode == ClipboardSyncMode.MAC_TO_ANDROID) {
            Log.d(TAG, "[Mode: Mac → Android] Blocked sending local clipboard to Mac (reverse sync prohibited)")
            return false
        }

        if (content.isEmpty()) return false
        val hash = computeHash(content)

        // If it matches what we just sent or just received from Mac, do not send
        if (hash == lastSentHash || hash == lastReceivedHash) {
            return false
        }

        lastSentHash = hash
        lastSyncedSnippet = content
        return true
    }

    fun markSent(content: String) {
        lastSentHash = computeHash(content)
        lastSyncedSnippet = content
    }

    fun getCurrentClipText(): String? {
        return try {
            val clip = clipboardManager?.primaryClip ?: return null
            if (clip.itemCount > 0) {
                clip.getItemAt(0)?.text?.toString()
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot read clipboard: ${e.message}")
            null
        }
    }

    fun addClipChangedListener(listener: ClipboardManager.OnPrimaryClipChangedListener) {
        try {
            clipboardManager?.addPrimaryClipChangedListener(listener)
        } catch (e: Exception) {
            Log.e(TAG, "Error adding clip listener: ${e.message}")
        }
    }

    fun removeClipChangedListener(listener: ClipboardManager.OnPrimaryClipChangedListener) {
        try {
            clipboardManager?.removePrimaryClipChangedListener(listener)
        } catch (e: Exception) {
            // Ignore
        }
    }
}

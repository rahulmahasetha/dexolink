package com.macdroid.core

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest

enum class ClipboardSyncMode(val id: String, val title: String, val description: String) {
    DISABLED("disabled", "Disabled", "Clipboard synchronization paused"),
    ANDROID_TO_MAC("android_to_mac", "Android → Mac", "Copy on Android updates Mac; Mac changes ignored"),
    MAC_TO_ANDROID("mac_to_android", "Mac → Android", "Copy on Mac updates Android; Android changes ignored"),
    TWO_WAY("two_way", "Two-Way Sync", "Copy on either device updates the other");

    companion object {
        fun fromId(id: String?): ClipboardSyncMode {
            return entries.find { it.id == id } ?: TWO_WAY
        }
    }
}

data class ClipboardHistoryItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isOutgoing: Boolean
)

class MacDroidClipboardManager(private val context: Context) {
    companion object {
        private const val TAG = "MacDroidClipboard"
        private const val PREFS_NAME = "dexolink_clipboard_prefs"
        private const val KEY_SYNC_MODE = "clipboard_sync_mode"
        private const val KEY_CLIPBOARD_HISTORY = "clipboard_history"
        private const val KEY_ENHANCED_CLIPBOARD = "enhanced_clipboard_enabled"
        private const val KEY_PENDING_QUEUE = "clipboard_pending_queue"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var syncMode: ClipboardSyncMode = ClipboardSyncMode.fromId(prefs.getString(KEY_SYNC_MODE, ClipboardSyncMode.TWO_WAY.id))
        set(value) {
            field = value
            prefs.edit().putString(KEY_SYNC_MODE, value.id).apply()
            Log.d(TAG, "ClipboardSyncMode persisted: ${value.title}")
        }

    /**
     * Enhanced Clipboard setting (OFF by default).
     * When enabled, improves paste compatibility across web editors (Google Docs, Notion, etc.)
     * and provides alternative paste workflows for restricted web inputs.
     */
    var isEnhancedClipboardEnabled: Boolean = prefs.getBoolean(KEY_ENHANCED_CLIPBOARD, false)
        set(value) {
            field = value
            prefs.edit().putBoolean(KEY_ENHANCED_CLIPBOARD, value).apply()
            _isEnhancedClipboardEnabledFlow.value = value
            Log.d(TAG, "Enhanced Clipboard persisted: $value")
        }

    private val _isEnhancedClipboardEnabledFlow = MutableStateFlow(isEnhancedClipboardEnabled)
    val isEnhancedClipboardEnabledFlow: StateFlow<Boolean> = _isEnhancedClipboardEnabledFlow.asStateFlow()

    private val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    private var lastReceivedHash: String = ""
    private var lastSentHash: String = ""
    private val recentReceivedHashes = java.util.Collections.synchronizedList(mutableListOf<Pair<String, Long>>())

    var lastSyncedSnippet: String? = null
        private set

    private val history = java.util.Collections.synchronizedList(mutableListOf<ClipboardHistoryItem>())
    private val pendingQueue = java.util.Collections.synchronizedList(mutableListOf<String>())
    var onHistoryChanged: (() -> Unit)? = null
    var onEnhancedClipReceived: ((String) -> Unit)? = null

    init {
        loadHistory()
        loadPendingQueue()
    }

    private fun loadPendingQueue() {
        try {
            val jsonStr = prefs.getString(KEY_PENDING_QUEUE, null) ?: return
            val arr = org.json.JSONArray(jsonStr)
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val item = arr.getString(i)
                if (item.isNotBlank()) {
                    list.add(item)
                }
            }
            synchronized(pendingQueue) {
                pendingQueue.clear()
                pendingQueue.addAll(list)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading pending clipboard queue: ${e.message}")
        }
    }

    private fun persistPendingQueue() {
        try {
            val arr = org.json.JSONArray()
            synchronized(pendingQueue) {
                for (item in pendingQueue.take(10)) {
                    arr.put(item)
                }
            }
            prefs.edit().putString(KEY_PENDING_QUEUE, arr.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error persisting pending clipboard queue: ${e.message}")
        }
    }

    private fun recordReceivedHash(hash: String) {
        val now = System.currentTimeMillis()
        synchronized(recentReceivedHashes) {
            recentReceivedHashes.removeAll { now - it.second > 60_000L }
            recentReceivedHashes.add(Pair(hash, now))
            while (recentReceivedHashes.size > 20) {
                recentReceivedHashes.removeAt(0)
            }
        }
    }

    private fun isRecentReceivedHash(hash: String): Boolean {
        val now = System.currentTimeMillis()
        synchronized(recentReceivedHashes) {
            recentReceivedHashes.removeAll { now - it.second > 60_000L }
            return recentReceivedHashes.any { it.first == hash }
        }
    }

    fun queueOutgoing(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        if (!canSend(text)) {
            Log.d(TAG, "Not queueing text: blocked by mode or duplicate/echo suppression")
            return false
        }
        synchronized(pendingQueue) {
            if (pendingQueue.contains(text)) {
                return true
            }
            pendingQueue.add(text)
            while (pendingQueue.size > 10) {
                pendingQueue.removeAt(0)
            }
        }
        persistPendingQueue()
        Log.d(TAG, "Queued clipboard update for Mac (pending queue size: ${pendingQueue.size})")
        return true
    }

    fun getPendingQueue(): List<String> {
        synchronized(pendingQueue) {
            return ArrayList(pendingQueue)
        }
    }

    fun removePendingClip(text: String) {
        synchronized(pendingQueue) {
            pendingQueue.remove(text)
        }
        persistPendingQueue()
    }

    fun clearPendingQueue() {
        synchronized(pendingQueue) {
            pendingQueue.clear()
        }
        persistPendingQueue()
    }

    fun hasPendingClips(): Boolean {
        synchronized(pendingQueue) {
            return pendingQueue.isNotEmpty()
        }
    }

    private fun loadHistory() {
        try {
            val jsonStr = prefs.getString(KEY_CLIPBOARD_HISTORY, null) ?: return
            val arr = org.json.JSONArray(jsonStr)
            val list = mutableListOf<ClipboardHistoryItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    ClipboardHistoryItem(
                        id = obj.getString("id"),
                        text = obj.getString("text"),
                        timestamp = obj.getLong("timestamp"),
                        isOutgoing = obj.getBoolean("isOutgoing")
                    )
                )
            }
            synchronized(history) {
                history.clear()
                history.addAll(list)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading clipboard history: ${e.message}")
        }
    }

    private fun persistHistory() {
        try {
            val arr = org.json.JSONArray()
            synchronized(history) {
                val takeList = history.take(50)
                for (item in takeList) {
                    val obj = org.json.JSONObject()
                    obj.put("id", item.id)
                    obj.put("text", item.text)
                    obj.put("timestamp", item.timestamp)
                    obj.put("isOutgoing", item.isOutgoing)
                    arr.put(obj)
                }
            }
            prefs.edit().putString(KEY_CLIPBOARD_HISTORY, arr.toString()).apply()
            onHistoryChanged?.invoke()
        } catch (e: Exception) {
            Log.e(TAG, "Error persisting clipboard history: ${e.message}")
        }
    }

    fun getHistory(): List<ClipboardHistoryItem> {
        synchronized(history) {
            return ArrayList(history)
        }
    }

    fun addToHistory(text: String, isOutgoing: Boolean) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        synchronized(history) {
            if (history.isNotEmpty() && history[0].text == text) {
                return
            }
            history.add(0, ClipboardHistoryItem(text = text, isOutgoing = isOutgoing))
            while (history.size > 50) {
                history.removeAt(history.size - 1)
            }
        }
        persistHistory()
    }

    fun deleteHistoryItem(id: String) {
        synchronized(history) {
            history.removeAll { it.id == id }
        }
        persistHistory()
    }

    fun deleteHistoryItems(ids: Set<String>) {
        synchronized(history) {
            history.removeAll { ids.contains(it.id) }
        }
        persistHistory()
    }

    fun clearHistory() {
        synchronized(history) {
            history.clear()
        }
        persistHistory()
    }

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
        // Enforce mode: In DISABLED or ANDROID_TO_MAC mode, never apply incoming clipboard from Mac
        if (syncMode == ClipboardSyncMode.DISABLED || syncMode == ClipboardSyncMode.ANDROID_TO_MAC) {
            Log.d(TAG, "[Mode: $syncMode] Dropped incoming clipboard push from Mac")
            return false
        }

        if (content.isEmpty()) return false
        val hash = computeHash(content)

        // Loop prevention: ignore if already received or if it originated from us
        if (hash == lastReceivedHash || hash == lastSentHash || isRecentReceivedHash(hash)) {
            Log.d(TAG, "Ignored duplicate/echo clipboard push (hash: ${hash.take(8)})")
            return false
        }

        lastReceivedHash = hash
        recordReceivedHash(hash)
        lastSyncedSnippet = content

        val applied = try {
            val clip = if (isEnhancedClipboardEnabled) {
                val cleanPlain = EnhancedClipboardHelper.sanitizePlainText(content)
                val html = EnhancedClipboardHelper.toHtmlRepresentation(content)
                ClipData.newHtmlText("DexoLink", cleanPlain, html)
            } else {
                ClipData.newPlainText("DexoLink", content)
            }
            clipboardManager?.setPrimaryClip(clip)
            Log.d(TAG, "[Clipboard Receipt] Set Android clipboard (enhanced=$isEnhancedClipboardEnabled): \"${content.take(30)}...\" (${content.length} chars)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "[Clipboard Error] Failed to set clipboard: ${e.message}", e)
            false
        }
        if (applied) {
            addToHistory(content, isOutgoing = false)
            if (isEnhancedClipboardEnabled) {
                onEnhancedClipReceived?.invoke(content)
            }
        }
        return applied
    }

    /**
     * Manually sets clean plain text on clipboard (used by Paste Assistant actions).
     */
    fun setCleanPlainText(text: String): Boolean {
        return try {
            val clean = EnhancedClipboardHelper.sanitizePlainText(text)
            val clip = ClipData.newPlainText("DexoLink", clean)
            clipboardManager?.setPrimaryClip(clip)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set clean plain text: ${e.message}")
            false
        }
    }

    /**
     * Checks if this text is eligible to be sent to Mac.
     * Pure predicate that does NOT modify lastSentHash.
     */
    fun canSend(content: String): Boolean {
        // Enforce mode: In DISABLED or MAC_TO_ANDROID mode, never send local Android clipboard to Mac
        if (syncMode == ClipboardSyncMode.DISABLED || syncMode == ClipboardSyncMode.MAC_TO_ANDROID) {
            Log.d(TAG, "[Mode: $syncMode] Blocked sending local clipboard to Mac")
            return false
        }

        if (content.isEmpty()) return false
        val hash = computeHash(content)

        // If it matches what we just sent or just received from Mac, do not send
        if (hash == lastSentHash || hash == lastReceivedHash || isRecentReceivedHash(hash)) {
            return false
        }

        return true
    }

    /**
     * Checks if this text should be sent to Mac.
     * If [markAsSent] is true, records this text as sent immediately.
     */
    fun shouldSend(content: String, markAsSent: Boolean = false): Boolean {
        if (!canSend(content)) return false
        if (markAsSent) {
            markSent(content)
        }
        return true
    }

    fun markSent(content: String) {
        lastSentHash = computeHash(content)
        lastSyncedSnippet = content
        removePendingClip(content)
        addToHistory(content, isOutgoing = true)
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

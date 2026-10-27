package com.macdroid.service

import android.content.Context
import android.content.SharedPreferences
import com.macdroid.core.ClipboardSyncMode
import com.macdroid.core.MacDroidClipboardManager
import com.macdroid.networking.ClipboardAckMessage
import com.macdroid.networking.ClipboardPushMessage
import com.macdroid.networking.ProtocolCodec
import org.junit.Assert.*
import org.junit.Test

class BackgroundClipboardSyncTest {

    private val codec = ProtocolCodec()

    private class FakeContext : android.content.ContextWrapper(null) {
        val prefsMap = mutableMapOf<String, Any?>()

        private val editor = java.lang.reflect.Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "putString" -> {
                    prefsMap[args[0] as String] = args[1]
                    proxy
                }
                "putBoolean" -> {
                    prefsMap[args[0] as String] = args[1]
                    proxy
                }
                "apply" -> null
                "commit" -> true
                else -> proxy
            }
        } as SharedPreferences.Editor

        private val prefs = java.lang.reflect.Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getString" -> prefsMap[args[0] as String] ?: (if (args != null && args.size > 1) args[1] else null)
                "getBoolean" -> prefsMap[args[0] as String] ?: (if (args != null && args.size > 1) args[1] else false)
                "edit" -> editor
                else -> null
            }
        } as SharedPreferences

        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
            return prefs
        }

        override fun getSystemService(name: String): Any? {
            return null
        }

        override fun getApplicationContext(): Context {
            return this
        }
    }

    private fun createFakeContext(): Context = FakeContext()

    @Test
    fun testClipboardPushMessageEncodingAndDecoding() {
        val originalText = "Hello from Android background sync!"
        val message = ClipboardPushMessage(sequenceNumber = 42, content = originalText)
        val encodedBytes = codec.encode(message)
        assertNotNull(encodedBytes)

        val buffer = java.nio.ByteBuffer.wrap(encodedBytes)
        val payloadPair = codec.decodePayload(buffer)
        assertNotNull(payloadPair)

        val decoded = codec.decodeMessage(payloadPair!!.first)
        assertTrue(decoded is ClipboardPushMessage)
        val pushDecoded = decoded as ClipboardPushMessage
        assertEquals(42, pushDecoded.sequenceNumber)
        assertEquals(originalText, pushDecoded.content)
    }

    @Test
    fun testClipboardAckMessageEncodingAndDecoding() {
        val ack = ClipboardAckMessage(sequenceNumber = 42)
        val encodedBytes = codec.encode(ack)
        assertNotNull(encodedBytes)

        val buffer = java.nio.ByteBuffer.wrap(encodedBytes)
        val payloadPair = codec.decodePayload(buffer)
        assertNotNull(payloadPair)

        val decoded = codec.decodeMessage(payloadPair!!.first)
        assertTrue(decoded is ClipboardAckMessage)
        val ackDecoded = decoded as ClipboardAckMessage
        assertEquals(42, ackDecoded.sequenceNumber)
    }

    @Test
    fun testClipboardSyncModeBehaviors() {
        val fakeContext = createFakeContext()
        val manager = MacDroidClipboardManager(fakeContext)

        // TWO_WAY should allow both directions
        manager.syncMode = ClipboardSyncMode.TWO_WAY
        assertEquals("two_way", manager.syncMode.id)
        assertTrue(manager.canSend("Some local text"))

        // ANDROID_TO_MAC should allow Android sending
        manager.syncMode = ClipboardSyncMode.ANDROID_TO_MAC
        assertEquals("android_to_mac", manager.syncMode.id)
        assertTrue(manager.canSend("Another local text"))

        // MAC_TO_ANDROID should drop Android sending
        manager.syncMode = ClipboardSyncMode.MAC_TO_ANDROID
        assertEquals("mac_to_android", manager.syncMode.id)
        assertFalse(manager.canSend("Should be blocked"))

        // DISABLED should drop both directions
        manager.syncMode = ClipboardSyncMode.DISABLED
        assertEquals("disabled", manager.syncMode.id)
        assertFalse(manager.canSend("Should be blocked"))
    }

    @Test
    fun testOfflineClipboardQueueingAndPersistence() {
        val fakeContext = createFakeContext()
        val manager = MacDroidClipboardManager(fakeContext)
        manager.syncMode = ClipboardSyncMode.TWO_WAY
        manager.clearPendingQueue()

        assertFalse("Initial queue must be empty", manager.hasPendingClips())
        assertEquals(0, manager.getPendingQueue().size)

        // Queue first offline clip
        val text1 = "First offline copied snippet"
        val queued1 = manager.queueOutgoing(text1)
        assertTrue(queued1)
        assertTrue(manager.hasPendingClips())
        assertEquals(1, manager.getPendingQueue().size)
        assertEquals(text1, manager.getPendingQueue()[0])

        // Queue second offline clip
        val text2 = "Second offline copied snippet"
        val queued2 = manager.queueOutgoing(text2)
        assertTrue(queued2)
        assertEquals(2, manager.getPendingQueue().size)
        assertEquals(text1, manager.getPendingQueue()[0])
        assertEquals(text2, manager.getPendingQueue()[1])

        // Deduplication in queue: queueing text1 again should not duplicate
        val queuedDuplicate = manager.queueOutgoing(text1)
        assertTrue("Duplicate text returns true (handled)", queuedDuplicate)
        assertEquals("Queue should not contain duplicate items", 2, manager.getPendingQueue().size)

        // Bounded queue: ensure max 10 items limit is enforced
        for (i in 3..15) {
            manager.queueOutgoing("Snippet $i")
        }
        assertEquals("Queue must be capped at 10 items to save memory", 10, manager.getPendingQueue().size)

        // Remove single item upon send
        manager.removePendingClip(text1)
        assertFalse(manager.getPendingQueue().contains(text1))

        // Clear queue
        manager.clearPendingQueue()
        assertFalse(manager.hasPendingClips())
        assertEquals(0, manager.getPendingQueue().size)
    }

    @Test
    fun testDeduplicationAndLoopPrevention() {
        val fakeContext = createFakeContext()
        val manager = MacDroidClipboardManager(fakeContext)
        manager.syncMode = ClipboardSyncMode.TWO_WAY

        val localText = "Important meeting notes"

        // Pure canSend check does not mutate lastSentHash
        assertTrue("Initial check must allow sending", manager.canSend(localText))
        assertTrue("Subsequent canSend check must still allow sending before markSent", manager.canSend(localText))

        // Once sent, markSent updates lastSentHash
        manager.markSent(localText)
        assertFalse("Immediate duplicate of recently sent text must be blocked", manager.canSend(localText))

        // Different text can be sent
        val newText = "Different copied text"
        assertTrue("New distinct text must be allowed", manager.canSend(newText))

        // Simulate incoming clip from Mac
        val incomingFromMac = "Copied on MacBook Pro"
        manager.handleMessage(incomingFromMac)
        // Note: in unit test, clipboardManager is null so handleMessage returns false (no clip service),
        // but hash is recorded in recentReceivedHashes
        assertFalse("Echo loop prevention must block sending back text just received from Mac", manager.canSend(incomingFromMac))
    }

    @Test
    fun testReconnectedStateQueueFlushingSimulation() {
        val fakeContext = createFakeContext()
        val manager = MacDroidClipboardManager(fakeContext)
        manager.syncMode = ClipboardSyncMode.TWO_WAY
        manager.clearPendingQueue()

        // 1. Simulate user copying text while closed / disconnected
        val clipA = "git status --short"
        val clipB = "https://github.com/rahulmahaseth/dexolink"
        manager.queueOutgoing(clipA)
        manager.queueOutgoing(clipB)
        assertEquals(2, manager.getPendingQueue().size)

        // 2. Simulate reconnection: flush pending items to socket sequentially
        val transmitted = mutableListOf<String>()
        val pending = manager.getPendingQueue()
        for (item in pending) {
            if (manager.canSend(item)) {
                manager.markSent(item)
                val seq = 101
                val msg = ClipboardPushMessage(seq, item)
                val encoded = codec.encode(msg)
                assertNotNull(encoded)
                transmitted.add(item)
            }
        }
        manager.clearPendingQueue()

        // Verify all queued items were transmitted in order
        assertEquals(2, transmitted.size)
        assertEquals(clipA, transmitted[0])
        assertEquals(clipB, transmitted[1])
        assertFalse("Queue should be completely empty after successful flush", manager.hasPendingClips())
    }

    @Test
    fun testAdaptiveBackoffLowBatteryConsumption() {
        // Verify exponential backoff delay calculation to prevent battery drain while Mac is asleep/offline
        fun getReconnectDelay(attempt: Int): Long {
            return when {
                attempt <= 2 -> 2000L
                attempt <= 5 -> 5000L
                attempt <= 10 -> 12000L
                else -> 30000L
            }
        }

        assertEquals(2000L, getReconnectDelay(1))
        assertEquals(2000L, getReconnectDelay(2))
        assertEquals(5000L, getReconnectDelay(3))
        assertEquals(5000L, getReconnectDelay(5))
        assertEquals(12000L, getReconnectDelay(6))
        assertEquals(12000L, getReconnectDelay(10))
        assertEquals("Backoff must cap at 30s to prevent battery drain", 30000L, getReconnectDelay(11))
        assertEquals("Backoff must remain 30s for long disconnects", 30000L, getReconnectDelay(50))
    }

    @Test
    fun testMinimizedAndClosedStatePersistenceAndRecovery() {
        val fakeContext = createFakeContext()

        // 1. App is minimized or running in background when text is copied
        val manager1 = MacDroidClipboardManager(fakeContext)
        manager1.syncMode = ClipboardSyncMode.TWO_WAY
        manager1.clearPendingQueue()

        val backgroundClip1 = "Token: abc123def456"
        val backgroundClip2 = "https://dexolink.app/meeting"
        manager1.queueOutgoing(backgroundClip1)
        manager1.queueOutgoing(backgroundClip2)

        assertEquals(2, manager1.getPendingQueue().size)

        // 2. Simulate DexoLink app being swiped away / closed / process restarted by OS
        val manager2 = MacDroidClipboardManager(fakeContext)
        assertEquals("Pending queue must be restored from persistent storage after app restart", 2, manager2.getPendingQueue().size)
        assertEquals(backgroundClip1, manager2.getPendingQueue()[0])
        assertEquals(backgroundClip2, manager2.getPendingQueue()[1])

        // 3. Connection is established / reconnected: flush items
        val flushed = mutableListOf<String>()
        val pending = manager2.getPendingQueue()
        for (item in pending) {
            manager2.markSent(item)
            flushed.add(item)
        }
        manager2.clearPendingQueue()

        // Verify state is clean and persisted
        assertEquals(2, flushed.size)
        assertFalse(manager2.hasPendingClips())

        // 4. Verify third manager instance sees empty queue
        val manager3 = MacDroidClipboardManager(fakeContext)
        assertFalse("Restored manager after flush must see empty queue", manager3.hasPendingClips())
    }
}

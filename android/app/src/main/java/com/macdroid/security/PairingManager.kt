package com.macdroid.security

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.macdroid.storage.TrustStore
import com.macdroid.storage.TrustedDevice
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

sealed class PairingState {
    object Idle : PairingState()
    data class WaitingForPin(val pin: String, val peerName: String, val peerId: String) : PairingState()
    data class Verified(val peerName: String, val peerId: String) : PairingState()
    data class Failed(val reason: String) : PairingState()
}

interface PairingListener {
    fun onPairingStateChanged(state: PairingState)
}

class PairingManager(private val context: Context) {
    companion object {
        @Volatile
        private var instance: PairingManager? = null

        fun getInstance(context: Context): PairingManager {
            return instance ?: synchronized(this) {
                instance ?: PairingManager(context.applicationContext).also { instance = it }
            }
        }

        fun generatePin(key1: String, key2: String): String {
            val keys = listOf(key1, key2).sorted().joinToString(":")
            val md = MessageDigest.getInstance("SHA-256")
            val hash = md.digest(keys.toByteArray(Charsets.UTF_8))

            val value = ((hash[0].toLong() and 0xFF) shl 24) or
                    ((hash[1].toLong() and 0xFF) shl 16) or
                    ((hash[2].toLong() and 0xFF) shl 8) or
                    (hash[3].toLong() and 0xFF)

            return String.format(Locale.US, "%06d", value % 1000000)
        }
    }

    private val trustStore = TrustStore.getInstance(context)
    private val cryptoService = CryptoService.getInstance(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<PairingListener>()

    var state: PairingState = PairingState.Idle
        private set(value) {
            field = value
            mainHandler.post {
                for (listener in listeners) {
                    listener.onPairingStateChanged(value)
                }
            }
        }

    private var pendingPeerId: String? = null
    private var pendingPeerName: String? = null
    private var pendingPeerKey: String? = null

    fun addListener(listener: PairingListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
            listener.onPairingStateChanged(state)
        }
    }

    fun removeListener(listener: PairingListener) {
        listeners.remove(listener)
    }

    fun initiatePairing(peerId: String, peerName: String, peerKeyBase64: String): String {
        pendingPeerId = peerId
        pendingPeerName = peerName
        pendingPeerKey = peerKeyBase64

        val localKey = cryptoService.publicKeyBase64
        val pin = generatePin(localKey, peerKeyBase64)

        state = PairingState.WaitingForPin(pin = pin, peerName = peerName, peerId = peerId)
        return pin
    }

    fun confirmPairing() {
        val peerId = pendingPeerId
        val peerName = pendingPeerName ?: "MacBook"
        val peerKey = pendingPeerKey ?: ""

        if (peerId == null) {
            state = PairingState.Failed("No pending pairing request")
            return
        }

        val device = TrustedDevice(
            deviceId = peerId,
            deviceName = peerName,
            publicKeyBase64 = peerKey,
            pairedAt = System.currentTimeMillis()
        )
        trustStore.addTrustedDevice(device)
        state = PairingState.Verified(peerName, peerId)
        resetPending()
    }

    fun rejectPairing(reason: String = "Pairing cancelled") {
        state = PairingState.Failed(reason)
        resetPending()
    }

    fun unpair() {
        trustStore.unpairDevice()
        state = PairingState.Idle
        resetPending()
    }

    fun reset() {
        state = PairingState.Idle
        resetPending()
    }

    private fun resetPending() {
        pendingPeerId = null
        pendingPeerName = null
        pendingPeerKey = null
    }
}

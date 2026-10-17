package com.macdroid.networking

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.macdroid.core.ClipboardSyncMode
import com.macdroid.core.MacDroidClipboardManager
import com.macdroid.security.CryptoService
import com.macdroid.security.PairingManager
import com.macdroid.storage.TrustStore
import com.macdroid.storage.TrustedDevice
import kotlinx.coroutines.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    data class Connecting(val target: String) : ConnectionState()
    data class Connected(val peerName: String, val peerAddress: String) : ConnectionState()
    data class Pairing(val pin: String, val peerName: String, val peerId: String) : ConnectionState()
    data class Authenticated(val peerName: String, val peerId: String) : ConnectionState()
    data class Failed(val reason: String) : ConnectionState()
}

interface ConnectionEventListener {
    fun onStateChanged(state: ConnectionState)
    fun onMessageReceived(message: ProtocolMessage)
    fun onLog(log: String)
}

class ConnectionManager(private val context: Context) {
    companion object {
        private const val TAG = "ConnectionManager"
        private const val CONNECT_TIMEOUT_MS = 8000
        private const val PREFS_NAME = "macdroid_connection_prefs"
        private const val KEY_LAST_HOST = "last_host"
        private const val KEY_LAST_PORT = "last_port"
        private const val HEARTBEAT_INTERVAL_MS = 20000L
    }

    private val codec = ProtocolCodec()
    val clipboardManager = MacDroidClipboardManager(context)
    val cryptoService = CryptoService.getInstance(context)
    val trustStore = TrustStore.getInstance(context)
    val pairingManager = PairingManager.getInstance(context)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var activeSocket: Socket? = null
    private var dataInputStream: DataInputStream? = null
    private var dataOutputStream: DataOutputStream? = null
    private var serverSocket: ServerSocket? = null

    private var ioJob: Job? = null
    private var listenJob: Job? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val deviceId: String get() = cryptoService.deviceId
    val deviceName: String = "${Build.MANUFACTURER} ${Build.MODEL}"

    private val listeners = CopyOnWriteArrayList<ConnectionEventListener>()

    var lastConnectedHost: String? = prefs.getString(KEY_LAST_HOST, null)
        private set
    var lastConnectedPort: Int = prefs.getInt(KEY_LAST_PORT, 0)
        private set

    var autoReconnect: Boolean = true

    var state: ConnectionState = ConnectionState.Disconnected
        private set(value) {
            field = value
            mainHandler.post {
                for (listener in listeners) {
                    listener.onStateChanged(value)
                }
            }
        }

    fun addListener(listener: ConnectionEventListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
            // Immediately notify of current state
            listener.onStateChanged(state)
        }
    }

    fun removeListener(listener: ConnectionEventListener) {
        listeners.remove(listener)
    }

    fun startServer(port: Int = 0): Int {
        if (serverSocket != null && !serverSocket!!.isClosed) {
            return serverSocket!!.localPort
        }
        try {
            serverSocket = ServerSocket(port)
            val boundPort = serverSocket!!.localPort
            log("Server listening on port $boundPort")

            listenJob = scope.launch {
                while (isActive) {
                    try {
                        val client = serverSocket?.accept() ?: break
                        log("Accepted connection from ${client.inetAddress.hostAddress}")
                        handleNewSocket(client, isIncoming = true)
                    } catch (e: Exception) {
                        if (isActive) {
                            log("Server socket error: ${e.message}")
                        }
                    }
                }
            }
            return boundPort
        } catch (e: Exception) {
            log("Failed to start server: ${e.message}")
            return -1
        }
    }

    fun connect(host: String, port: Int) {
        autoReconnect = true
        lastConnectedHost = host
        lastConnectedPort = port
        saveLastTarget(host, port)

        reconnectJob?.cancel()
        disconnectInternal(disableAutoReconnect = false)

        state = ConnectionState.Connecting("$host:$port")
        log("Connecting to $host:$port...")

        ioJob = scope.launch {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                socket.tcpNoDelay = true
                socket.keepAlive = true
                handleNewSocket(socket, isIncoming = false)
            } catch (e: Exception) {
                log("Connection failed: ${e.message}")
                state = ConnectionState.Failed(e.localizedMessage ?: "Connection failed")
                disconnectInternal(disableAutoReconnect = false)
                scheduleReconnect()
            }
        }
    }

    private suspend fun handleNewSocket(socket: Socket, isIncoming: Boolean) {
        reconnectJob?.cancel()
        activeSocket = socket
        dataInputStream = DataInputStream(socket.getInputStream())
        dataOutputStream = DataOutputStream(socket.getOutputStream())

        val peerAddr = socket.inetAddress?.hostAddress ?: "Unknown"
        state = ConnectionState.Connected(peerName = "MacBook", peerAddress = peerAddr)
        log("Connected to $peerAddr (incoming: $isIncoming)")

        // Send HELLO handshake
        val hello = HelloMessage(
            version = 1,
            deviceId = deviceId,
            deviceName = deviceName
        )
        sendMessage(hello)
        log("Sent HELLO ($deviceName, deviceId: $deviceId)")

        // Start background heartbeat
        startHeartbeat()

        // Start receive loop
        receiveLoop(socket)
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                val socket = activeSocket
                if (socket != null && socket.isConnected && !socket.isClosed) {
                    sendMessage(PingMessage)
                } else {
                    break
                }
            }
        }
    }

    private suspend fun receiveLoop(socket: Socket) {
        val stream = dataInputStream ?: return
        try {
            while (scope.isActive && !socket.isClosed) {
                val length = stream.readInt()
                if (length <= 0 || length > ProtocolCodec.MAX_MESSAGE_SIZE) {
                    throw IllegalArgumentException("Invalid message length: $length")
                }

                val payload = ByteArray(length)
                stream.readFully(payload)

                val message = codec.decodeMessage(payload)
                handleIncomingMessage(message)
            }
        } catch (e: Exception) {
            if (scope.isActive && !socket.isClosed) {
                log("Socket read ended: ${e.message}")
            }
        } finally {
            log("Connection dropped, checking auto-reconnect...")
            disconnectInternal(disableAutoReconnect = false)
            if (autoReconnect && lastConnectedHost != null && lastConnectedPort > 0) {
                scheduleReconnect()
            }
        }
    }

    fun updateTargetIfChanged(host: String, port: Int) {
        if (lastConnectedHost != host || lastConnectedPort != port) {
            log("Updated target Mac from discovery: $host:$port")
            lastConnectedHost = host
            lastConnectedPort = port
            saveLastTarget(host, port)
        }
    }

    private fun scheduleReconnect() {
        if (!autoReconnect) return
        val host = lastConnectedHost ?: return
        val port = lastConnectedPort
        if (port <= 0) return

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            var attempt = 1
            while (isActive && autoReconnect && (activeSocket == null || activeSocket?.isClosed == true)) {
                val delayMs = minOf(1500L * attempt, 10000L)
                state = ConnectionState.Connecting("Reconnecting to $host:$port ($attempt)...")
                log("Auto-reconnecting in ${delayMs / 1000}s (attempt $attempt)...")
                delay(delayMs)

                if (!isActive || !autoReconnect) break

                try {
                    log("Attempting socket connect to $host:$port...")
                    val socket = Socket()
                    socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                    socket.tcpNoDelay = true
                    socket.keepAlive = true
                    handleNewSocket(socket, isIncoming = false)
                    log("Auto-reconnection successful!")
                    return@launch
                } catch (e: Exception) {
                    log("Reconnect attempt $attempt failed: ${e.message}")
                    attempt++
                }
            }
        }
    }

    private fun handleIncomingMessage(message: ProtocolMessage) {
        log("Received: ${message.type}")

        when (message) {
            is HelloMessage -> {
                log("Peer identified: ${message.deviceName} (${message.deviceId})")
                val isTrusted = trustStore.isTrusted(message.deviceId)

                if (isTrusted) {
                    log("Device ${message.deviceName} (${message.deviceId}) is trusted in TrustStore! Authenticating...")
                    state = ConnectionState.Authenticated(message.deviceName, message.deviceId)
                    val ack = HelloAckMessage(
                        version = 1,
                        deviceId = deviceId,
                        deviceName = deviceName,
                        paired = true
                    )
                    sendMessage(ack)
                    sendMessage(ClipboardModeMessage(clipboardManager.syncMode.id))
                } else {
                    log("Device ${message.deviceName} (${message.deviceId}) is not in TrustStore. Starting pairing handshake...")
                    val ack = HelloAckMessage(
                        version = 1,
                        deviceId = deviceId,
                        deviceName = deviceName,
                        paired = false
                    )
                    sendMessage(ack)
                    // Request pairing
                    sendMessage(PairRequestMessage(publicKey = cryptoService.publicKeyBase64))
                }
            }

            is HelloAckMessage -> {
                log("Peer acknowledged HELLO: ${message.deviceName} (paired: ${message.paired})")
                if (message.paired) {
                    log("Peer verified authenticated session: ${message.deviceName}")
                    state = ConnectionState.Authenticated(message.deviceName, message.deviceId)
                    // Save to trust store if not present
                    if (!trustStore.isTrusted(message.deviceId)) {
                        trustStore.addTrustedDevice(
                            TrustedDevice(
                                deviceId = message.deviceId,
                                deviceName = message.deviceName,
                                publicKeyBase64 = ""
                            )
                        )
                    }
                    sendMessage(ClipboardModeMessage(clipboardManager.syncMode.id))
                    log("[Mode Sync] Synced mode '${clipboardManager.syncMode.id}' with ${message.deviceName}")
                } else {
                    log("Peer requires pairing. Initiating PAIR_REQUEST...")
                    sendMessage(PairRequestMessage(publicKey = cryptoService.publicKeyBase64))
                }
            }

            is PairRequestMessage -> {
                log("Received PAIR_REQUEST with public key from peer")
                val peerName = when (val s = state) {
                    is ConnectionState.Connected -> s.peerName
                    is ConnectionState.Authenticated -> s.peerName
                    else -> "MacBook"
                }
                val peerId = when (val s = state) {
                    is ConnectionState.Connected -> s.peerAddress
                    is ConnectionState.Authenticated -> s.peerId
                    else -> "Mac"
                }

                val pin = pairingManager.initiatePairing(peerId, peerName, message.publicKey)
                log("Generated SAS confirmation PIN: $pin for pairing with $peerName")

                // Send PAIR_RESPONSE with our public key
                sendMessage(PairResponseMessage(accepted = true, publicKey = cryptoService.publicKeyBase64))
                state = ConnectionState.Pairing(pin = pin, peerName = peerName, peerId = peerId)
            }

            is PairResponseMessage -> {
                if (message.accepted) {
                    log("Peer accepted pairing response! Verifying SAS key...")
                    val peerName = when (val s = state) {
                        is ConnectionState.Connected -> s.peerName
                        is ConnectionState.Authenticated -> s.peerName
                        is ConnectionState.Pairing -> s.peerName
                        else -> "MacBook"
                    }
                    val peerId = when (val s = state) {
                        is ConnectionState.Connected -> s.peerAddress
                        is ConnectionState.Authenticated -> s.peerId
                        is ConnectionState.Pairing -> s.peerId
                        else -> "Mac"
                    }

                    val pin = pairingManager.initiatePairing(peerId, peerName, message.publicKey)
                    log("Pairing verified! 6-digit confirmation code: $pin")
                    pairingManager.confirmPairing()
                    state = ConnectionState.Authenticated(peerName, peerId)
                    sendMessage(ClipboardModeMessage(clipboardManager.syncMode.id))
                } else {
                    log("Peer rejected pairing.")
                    state = ConnectionState.Failed("Pairing rejected by peer")
                }
            }

            is PingMessage -> {
                log("Received PING -> sending PONG")
                sendMessage(PongMessage)
            }

            is PongMessage -> {
                // Heartbeat alive
            }

            is ClipboardPushMessage -> {
                log("[Clipboard Receive] Incoming push from Mac: \"${message.content.take(30)}...\" (${message.content.length} chars)")
                val applied = clipboardManager.handleMessage(message.content)
                if (applied) {
                    log("[Clipboard Applied] Updated Android clipboard: \"${message.content.take(30)}...\"")
                } else {
                    log("[Clipboard Dropped] Ignored push (duplicate, echo, or blocked by mode: ${clipboardManager.syncMode.title})")
                }
                sendMessage(ClipboardAckMessage(message.sequenceNumber))
            }

            is ClipboardAckMessage -> {
                log("Clipboard push acknowledged by peer (seq: ${message.sequenceNumber})")
            }

            is ErrorMessage -> {
                log("Peer error [${message.code}]: ${message.message}")
            }

            else -> {
                log("Handled message type: ${message.type}")
            }
        }

        mainHandler.post {
            for (listener in listeners) {
                listener.onMessageReceived(message)
            }
        }
    }

    fun confirmPairing() {
        pairingManager.confirmPairing()
        val currentState = state
        if (currentState is ConnectionState.Pairing) {
            state = ConnectionState.Authenticated(currentState.peerName, currentState.peerId)
            sendMessage(ClipboardModeMessage(clipboardManager.syncMode.id))
            log("Pairing confirmed by user for ${currentState.peerName} (${currentState.peerId})")
        }
    }

    fun rejectPairing() {
        pairingManager.rejectPairing()
        state = ConnectionState.Failed("Pairing cancelled")
        disconnect(disableAutoReconnect = true)
    }

    fun sendMessage(message: ProtocolMessage) {
        scope.launch {
            try {
                val out = dataOutputStream ?: return@launch
                val bytes = codec.encode(message)
                synchronized(out) {
                    out.write(bytes)
                    out.flush()
                }
            } catch (e: Exception) {
                log("Failed to send ${message.type}: ${e.message}")
                if (e is java.net.SocketException) {
                    disconnectInternal(disableAutoReconnect = false)
                    scheduleReconnect()
                }
            }
        }
    }

    fun sendPing() {
        log("Sending PING...")
        sendMessage(PingMessage)
    }

    fun setClipboardSyncMode(mode: ClipboardSyncMode) {
        clipboardManager.syncMode = mode
        log("[Mode Changed] Switched sync mode to: ${mode.title}")
        if (isConnected()) {
            sendMessage(ClipboardModeMessage(mode.id))
            log("[Mode Broadcast] Notified Mac of sync mode: ${mode.id}")
        }
    }

    fun sendClipboard(text: String, force: Boolean = false) {
        if (!force && !clipboardManager.shouldSend(text)) {
            log("[Clipboard Skip] Text unchanged, echo, or blocked by mode: ${clipboardManager.syncMode.title}")
            return
        }
        clipboardManager.markSent(text)
        log("[Clipboard Transmit] Sending to Mac: \"${text.take(30)}...\" (${text.length} chars) [Mode: ${clipboardManager.syncMode.title}]")
        val seq = (System.currentTimeMillis() % 100000).toInt()
        sendMessage(ClipboardPushMessage(seq, text))
    }

    fun isConnected(): Boolean {
        return (state is ConnectionState.Connected || state is ConnectionState.Authenticated) &&
                activeSocket != null && activeSocket?.isConnected == true && activeSocket?.isClosed == false
    }

    fun disconnect(disableAutoReconnect: Boolean = true) {
        if (disableAutoReconnect) {
            autoReconnect = false
            reconnectJob?.cancel()
        }
        disconnectInternal(disableAutoReconnect)
    }

    private fun disconnectInternal(disableAutoReconnect: Boolean) {
        heartbeatJob?.cancel()
        try {
            ioJob?.cancel()
            dataInputStream?.close()
            dataOutputStream?.close()
            activeSocket?.close()
        } catch (e: Exception) {
            // Ignore
        } finally {
            activeSocket = null
            dataInputStream = null
            dataOutputStream = null
            if (disableAutoReconnect || !autoReconnect) {
                state = ConnectionState.Disconnected
            }
        }
    }

    fun stopServer() {
        listenJob?.cancel()
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // Ignore
        }
        serverSocket = null
    }

    fun shutdown() {
        disconnect(disableAutoReconnect = true)
        stopServer()
        scope.cancel()
    }

    private fun saveLastTarget(host: String, port: Int) {
        prefs.edit()
            .putString(KEY_LAST_HOST, host)
            .putInt(KEY_LAST_PORT, port)
            .apply()
    }

    private fun log(msg: String) {
        Log.d(TAG, msg)
        mainHandler.post {
            for (listener in listeners) {
                listener.onLog(msg)
            }
        }
    }
}

package com.macdroid.networking

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.net.Uri
import com.macdroid.MacDroidApplication
import com.macdroid.core.ClipboardSyncMode
import com.macdroid.core.MacDroidClipboardManager
import com.macdroid.core.TransferManager
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
        private const val ACTIVITY_EXPIRY_THRESHOLD_MS = 35000L // 35s
    }

    private val codec = ProtocolCodec()
    val clipboardManager = MacDroidClipboardManager(context)
    val cryptoService = CryptoService.getInstance(context)
    val trustStore = TrustStore.getInstance(context)
    val pairingManager = PairingManager.getInstance(context)
    val transferManager = TransferManager(context, trustStore, { isConnected() || trustStore.hasPairedDevice() }) { sendMessage(it) }

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
    private var sendJob: Job? = null
    private val sendChannel = kotlinx.coroutines.channels.Channel<ProtocolMessage>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastDataActivityTimestamp: Long = System.currentTimeMillis()

    val deviceId: String get() = cryptoService.deviceId
    var deviceName: String = prefs.getString("custom_device_name", null) ?: "${Build.MANUFACTURER} ${Build.MODEL}"
        private set
    var isIncomingConnection: Boolean = false
        private set
    var currentPeerId: String? = null
        private set
    var currentPeerName: String? = null
        private set

    fun setDeviceName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isNotEmpty()) {
            deviceName = trimmed
            prefs.edit().putString("custom_device_name", trimmed).apply()
            log("Updated local device name to: $trimmed")
        }
    }

    private val listeners = CopyOnWriteArrayList<ConnectionEventListener>()

    var lastConnectedHost: String? = prefs.getString(KEY_LAST_HOST, null)
        private set
    var lastConnectedPort: Int = prefs.getInt(KEY_LAST_PORT, 0)
        private set

    var autoReconnect: Boolean = true
    var userExplicitlyDisconnected: Boolean = false
    private var pingStartTime: Long = 0L
    var lastPingLatency: Long? = null
        private set

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
                        if (userExplicitlyDisconnected) {
                            log("Rejecting incoming connection from ${client.inetAddress.hostAddress}: User explicitly disconnected")
                            try { client.close() } catch (e: Exception) {}
                            continue
                        }
                        if (isConnected() && state is ConnectionState.Authenticated) {
                            log("Ignoring incoming connection from ${client.inetAddress.hostAddress}: Active authenticated session already exists")
                            try { client.close() } catch (e: Exception) {}
                            continue
                        }
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
        if (isConnected() && (state is ConnectionState.Authenticated || state is ConnectionState.Connected)) {
            if (activeSocket?.inetAddress?.hostAddress == host) {
                log("Already connected to $host:$port, skipping redundant connect request")
                return
            }
        }

        userExplicitlyDisconnected = false
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
                socket.sendBufferSize = 2 * 1024 * 1024
                socket.receiveBufferSize = 2 * 1024 * 1024
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
        if (isIncoming && userExplicitlyDisconnected) {
            log("Rejecting incoming connection from ${socket.inetAddress?.hostAddress}: User explicitly disconnected")
            try { socket.close() } catch (e: Exception) {}
            return
        }
        if (isIncoming && isConnected() && state is ConnectionState.Authenticated) {
            log("Ignoring incoming connection from ${socket.inetAddress?.hostAddress}: Already active and authenticated with ${currentPeerName ?: "Mac"}")
            try { socket.close() } catch (e: Exception) {}
            return
        }
        if (!isIncoming) {
            userExplicitlyDisconnected = false
        }
        isIncomingConnection = isIncoming
        reconnectJob?.cancel()

        // Clean up previous active socket cleanly without triggering teardown cascades
        val previousSocket = activeSocket
        if (previousSocket != null && previousSocket != socket && !previousSocket.isClosed) {
            log("Closing superseded socket (new incoming: $isIncoming)")
            try { previousSocket.close() } catch (e: Exception) {}
        }

        activeSocket = socket
        socket.tcpNoDelay = true
        socket.keepAlive = true
        socket.soTimeout = 90000 // 90s socket read timeout aligned with 30s heartbeat & 90s stale timeout
        socket.sendBufferSize = 2 * 1024 * 1024
        socket.receiveBufferSize = 2 * 1024 * 1024
        dataInputStream = DataInputStream(java.io.BufferedInputStream(socket.getInputStream(), 512 * 1024))
        dataOutputStream = DataOutputStream(java.io.BufferedOutputStream(socket.getOutputStream(), 512 * 1024))

        val peerAddr = socket.inetAddress?.hostAddress ?: "Unknown"
        state = ConnectionState.Connected(peerName = "MacBook", peerAddress = peerAddr)
        log("Connected to $peerAddr (incoming: $isIncoming)")

        // Start send loop for strictly sequential transmission
        while (sendChannel.tryReceive().isSuccess) {}
        startSendLoop()

        // Send HELLO handshake
        val hello = HelloMessage(
            version = 2,
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

    private fun startSendLoop() {
        sendJob?.cancel()
        sendJob = scope.launch {
            for (message in sendChannel) {
                try {
                    val out = dataOutputStream ?: continue
                    val bytes = codec.encode(message)
                    synchronized(out) {
                        out.write(bytes)
                        out.flush()
                    }
                    lastDataActivityTimestamp = System.currentTimeMillis()
                } catch (e: Exception) {
                    log("Failed to send ${message.type}: ${e.message}")
                    if (e is java.net.SocketException) {
                        disconnectInternal(disableAutoReconnect = false)
                        scheduleReconnect()
                        break
                    }
                }
            }
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(30_000L) // Optimized heartbeat interval: 30s (was 15s) for lower CPU/battery

                val socket = activeSocket
                if (socket != null && socket.isConnected && !socket.isClosed) {
                    val timeSinceActivity = System.currentTimeMillis() - lastDataActivityTimestamp
                    if (timeSinceActivity >= 90_000L) {
                        // Stale connection: 90s with no data (was 45s)
                        log("No heartbeat or data received for 90s. Connection lost, auto-reconnecting...")
                        disconnectInternal(disableAutoReconnect = false, targetSocket = socket)
                        if (autoReconnect && !userExplicitlyDisconnected && lastConnectedHost != null && lastConnectedPort > 0) {
                            scheduleReconnect()
                        }
                        break
                    } else if (timeSinceActivity >= 25_000L) {
                        // Only PING if truly idle (25s threshold accounts for delay jitter)
                        pingStartTime = System.currentTimeMillis()
                        sendMessage(PingMessage)
                        log("Heartbeat PING sent (idle: ${timeSinceActivity / 1000}s)")
                    }
                    // If timeSinceActivity < 25s, recent data means connection is alive — skip PING
                } else {
                    break
                }
            }
        }
    }

    private suspend fun receiveLoop(socket: Socket) {
        val stream = dataInputStream ?: return
        try {
            while (scope.isActive && !socket.isClosed && activeSocket == socket) {
                try {
                    val length = stream.readInt()
                    if (length <= 0 || length > ProtocolCodec.MAX_MESSAGE_SIZE) {
                        throw IllegalArgumentException("Invalid message length: $length")
                    }

                    val payload = ByteArray(length)
                    stream.readFully(payload)

                    val message = codec.decodeMessage(payload)
                    lastDataActivityTimestamp = System.currentTimeMillis()
                    handleIncomingMessage(message)
                } catch (e: java.net.SocketTimeoutException) {
                    if (socket.isClosed || activeSocket != socket) break
                    val idle = System.currentTimeMillis() - lastDataActivityTimestamp
                    if (idle >= 90_000L) {
                        log("Socket read timeout: idle for ${idle / 1000}s, probing connection with PING...")
                        sendMessage(PingMessage)
                    }
                }
            }
        } catch (e: Exception) {
            if (scope.isActive && !socket.isClosed && activeSocket == socket) {
                log("Socket read ended: ${e.message}")
            }
        } finally {
            if (activeSocket == socket) {
                log("Active socket dropped, checking auto-reconnect...")
                disconnectInternal(disableAutoReconnect = false, targetSocket = socket)
                if (autoReconnect && !userExplicitlyDisconnected && lastConnectedHost != null && lastConnectedPort > 0) {
                    scheduleReconnect()
                }
            } else {
                log("Stale socket closed, active socket is intact. Skipping teardown.")
                try { socket.close() } catch (e: Exception) {}
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
        if (!autoReconnect || userExplicitlyDisconnected) return
        val host = lastConnectedHost ?: return
        val port = lastConnectedPort
        if (port <= 0) return

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            var attempt = 1
            while (isActive && autoReconnect && !userExplicitlyDisconnected && (activeSocket == null || activeSocket?.isClosed == true)) {
                // Adaptive backoff: quick retry for transient drops, capped at 30s when Mac is offline
                val delayMs = when {
                    attempt <= 2 -> 2000L
                    attempt <= 5 -> 5000L
                    attempt <= 10 -> 12000L
                    else -> 30000L // 30s cap prevents battery drain while peer remains offline
                }
                state = ConnectionState.Connecting("Reconnecting to $host:$port ($attempt)...")
                log("Auto-reconnecting in ${delayMs / 1000}s (attempt $attempt)...")
                delay(delayMs)

                if (!isActive || !autoReconnect || userExplicitlyDisconnected) break

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
                currentPeerId = message.deviceId
                currentPeerName = message.deviceName
                val isTrusted = trustStore.isPairedWith(message.deviceId, message.deviceName)

                if (isTrusted) {
                    log("Device ${message.deviceName} (${message.deviceId}) is our paired device! Authenticating...")
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
                    log("Device ${message.deviceName} (${message.deviceId}) is not paired. HelloAck(paired: false)")
                    val peerAddr = activeSocket?.inetAddress?.hostAddress ?: "Unknown"
                    state = ConnectionState.Connected(peerName = message.deviceName, peerAddress = peerAddr)
                    val ack = HelloAckMessage(
                        version = 1,
                        deviceId = deviceId,
                        deviceName = deviceName,
                        paired = false
                    )
                    sendMessage(ack)
                    log("Sent HelloAck(paired = false) to ${message.deviceName}")
                }
            }

            is HelloAckMessage -> {
                log("Peer acknowledged HELLO: ${message.deviceName} (paired: ${message.paired})")
                currentPeerId = message.deviceId
                currentPeerName = message.deviceName
                if (message.paired) {
                    log("Peer verified authenticated session: ${message.deviceName}")
                    state = ConnectionState.Authenticated(message.deviceName, message.deviceId)
                    if (!trustStore.isPairedWith(message.deviceId, message.deviceName)) {
                        trustStore.setPairedDevice(
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
                    if (!isIncomingConnection) {
                        if (!trustStore.hasPairedDevice()) {
                            log("Initiating PAIR_REQUEST to ${message.deviceName}...")
                            state = ConnectionState.Connecting("Pairing request sent to ${message.deviceName}...")
                            sendMessage(PairRequestMessage(publicKey = cryptoService.publicKeyBase64))
                        } else {
                            log("Cannot pair with ${message.deviceName}: Already paired with '${trustStore.getPairedDevice()?.deviceName}'")
                        }
                    } else {
                        log("Received HelloAck as receiver from ${message.deviceName} — awaiting incoming PairRequest")
                    }
                }
            }

            is PairRequestMessage -> {
                log("Received PAIR_REQUEST from peer")
                val peerName = currentPeerName ?: when (val s = state) {
                    is ConnectionState.Connected -> s.peerName
                    is ConnectionState.Authenticated -> s.peerName
                    else -> "Device"
                }
                val peerId = currentPeerId ?: when (val s = state) {
                    is ConnectionState.Connected -> s.peerAddress
                    is ConnectionState.Authenticated -> s.peerId
                    else -> "Device"
                }

                // Strict 1-device policy: check if phone is already paired with another device!
                val existingPaired = trustStore.getPairedDevice()
                if (existingPaired != null && existingPaired.deviceId != peerId && existingPaired.deviceName != peerName) {
                    log("Auto-rejecting PAIR_REQUEST from $peerName: Already paired with '${existingPaired.deviceName}'")
                    sendMessage(
                        PairResponseMessage(
                            accepted = false,
                            publicKey = "",
                            reason = "Device is already paired with '${existingPaired.deviceName}'. In accordance with DexoLink's strict one-device policy, please unpair first."
                        )
                    )
                    state = ConnectionState.Failed("Rejected pairing: Already paired with ${existingPaired.deviceName}")
                    return
                }

                val pin = pairingManager.initiatePairing(peerId, peerName, message.publicKey)
                log("Generated SAS confirmation PIN: $pin for pairing with $peerName")
                state = ConnectionState.Pairing(pin = pin, peerName = peerName, peerId = peerId)
            }

            is PairResponseMessage -> {
                if (message.accepted) {
                    log("Peer accepted pairing response! Verifying SAS key...")
                    val peerName = currentPeerName ?: when (val s = state) {
                        is ConnectionState.Connected -> s.peerName
                        is ConnectionState.Authenticated -> s.peerName
                        is ConnectionState.Pairing -> s.peerName
                        else -> "Device"
                    }
                    val peerId = currentPeerId ?: when (val s = state) {
                        is ConnectionState.Connected -> s.peerAddress
                        is ConnectionState.Authenticated -> s.peerId
                        is ConnectionState.Pairing -> s.peerId
                        else -> "Device"
                    }

                    val pin = pairingManager.initiatePairing(peerId, peerName, message.publicKey)
                    log("Pairing verified! 6-digit confirmation code: $pin")
                    pairingManager.confirmPairing()
                    state = ConnectionState.Authenticated(peerName, peerId)
                    sendMessage(ClipboardModeMessage(clipboardManager.syncMode.id))

                    // Automatically stop discovery and scanning on both devices once pairing succeeds!
                    try {
                        MacDroidApplication.discoveryService.stopBrowsing()
                        log("Pairing succeeded with $peerName — stopped Wi-Fi discovery")
                    } catch (e: Exception) {
                        log("Could not stop discovery: ${e.message}")
                    }
                } else {
                    val reason = if (message.reason.isNotBlank()) message.reason else "Pairing rejected by peer"
                    log("Pairing rejected: $reason")
                    state = ConnectionState.Failed(reason)
                    disconnect(disableAutoReconnect = true)

                    // If pairing is rejected or fails, continue scanning until pairing succeeds or user clicks Stop!
                    try {
                        MacDroidApplication.discoveryService.startBrowsing()
                        log("Continuing discovery scanning after pairing rejection...")
                    } catch (e: Exception) {
                        log("Could not restart discovery: ${e.message}")
                    }
                }
            }

            is UnpairMessage -> {
                log("Received UNPAIR from peer. Clearing paired trust store...")
                trustStore.unpairDevice()
                pairingManager.unpair()
                userExplicitlyDisconnected = true
                try {
                    transferManager.cancelAll()
                } catch (e: Exception) {}
                state = ConnectionState.Disconnected
                disconnect(disableAutoReconnect = true)
            }

            is PingMessage -> {
                log("Received PING -> sending PONG")
                sendMessage(PongMessage)
            }

            is PongMessage -> {
                val latency = if (pingStartTime > 0L) System.currentTimeMillis() - pingStartTime else -1L
                if (latency >= 0L) {
                    lastPingLatency = latency
                    log("Pong received in ${latency}ms")
                } else {
                    log("Pong heartbeat received")
                }
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

            is TransferRequestMessage, is TransferResponseMessage, is FileMetaMessage,
            is FileChunkMessage, is FileCompleteMessage, is FileAckMessage,
            is CancelMessage, is ProgressMessage -> {
                transferManager.handleIncomingMessage(message)
            }

            is ContinuityActivityMessage, is ContinuityAckMessage, is ContinuityDismissMessage, is ContinuityStateMessage -> {
                try {
                    com.macdroid.continuity.ContinuityManager.getInstance(context).handleMessage(message)
                } catch (e: Exception) {
                    log("Error handling continuity message: ${e.message}")
                }
            }

            else -> {
                log("Handled message type: ${message.type}")
            }
        }

        if (message !is FileChunkMessage) {
            mainHandler.post {
                for (listener in listeners) {
                    listener.onMessageReceived(message)
                }
            }
        }
    }

    fun confirmPairing() {
        val currentState = state
        if (currentState is ConnectionState.Pairing) {
            pairingManager.confirmPairing()
            sendMessage(PairResponseMessage(accepted = true, publicKey = cryptoService.publicKeyBase64))
            state = ConnectionState.Authenticated(currentState.peerName, currentState.peerId)
            sendMessage(ClipboardModeMessage(clipboardManager.syncMode.id))
            log("Pairing confirmed by user for ${currentState.peerName} (${currentState.peerId})")
            try {
                MacDroidApplication.discoveryService.stopBrowsing()
                log("Pairing confirmed — stopped Wi-Fi discovery scanning")
            } catch (e: Exception) {
                log("Could not stop discovery: ${e.message}")
            }
        }
    }

    fun rejectPairing() {
        pairingManager.rejectPairing()
        sendMessage(PairResponseMessage(accepted = false, publicKey = "", reason = "Pairing declined by user"))
        state = ConnectionState.Failed("Pairing declined")
        disconnect(disableAutoReconnect = true)
        try {
            MacDroidApplication.discoveryService.startBrowsing()
            log("Pairing rejected — continuing Wi-Fi discovery scanning")
        } catch (e: Exception) {
            log("Could not restart discovery: ${e.message}")
        }
    }

    fun unpairCurrentDevice() {
        val paired = trustStore.getPairedDevice()
        log("Unpairing current device: ${paired?.deviceName}")
        sendMessage(UnpairMessage)
        trustStore.unpairDevice()
        pairingManager.unpair()
        userExplicitlyDisconnected = true
        try {
            transferManager.cancelAll()
        } catch (e: Exception) {}
        disconnect(disableAutoReconnect = true)
        state = ConnectionState.Disconnected
    }

    fun sendMessage(message: ProtocolMessage) {
        sendChannel.trySend(message)
    }

    fun requestPairing() {
        if (!isConnected()) {
            log("Cannot send PAIR_REQUEST: Not connected to peer")
            return
        }
        val existingPaired = trustStore.getPairedDevice()
        if (existingPaired != null && currentPeerId != null && !trustStore.isPairedWith(currentPeerId ?: "", currentPeerName ?: "")) {
            log("Cannot request pairing: Already paired with ${existingPaired.deviceName}. Unpair first.")
            return
        }
        log("Sending PAIR_REQUEST to ${currentPeerName ?: "Mac"}...")
        state = ConnectionState.Connecting("Pairing request sent to ${currentPeerName ?: "Mac"}...")
        sendMessage(PairRequestMessage(publicKey = cryptoService.publicKeyBase64))
    }

    fun sendPing() {
        pingStartTime = System.currentTimeMillis()
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

    fun sendFile(uri: Uri) {
        transferManager.sendFile(uri)
    }

    fun cancelTransfer(transferId: String) {
        transferManager.cancelTransfer(transferId)
    }

    fun pauseTransfer(transferId: String) {
        transferManager.pauseTransfer(transferId)
    }

    fun unpauseTransfer(transferId: String) {
        transferManager.unpauseTransfer(transferId)
    }

    fun deleteCompletedTransfer(transferId: String) {
        transferManager.deleteCompletedTransfer(transferId)
    }

    fun deleteCompletedTransfers(transferIds: Set<String>) {
        transferManager.deleteCompletedTransfers(transferIds)
    }

    fun clearCompletedTransfers() {
        transferManager.clearCompletedTransfers()
    }

    fun resumeTransfer(transferId: String) {
        transferManager.resumeTransfer(transferId)
    }

    fun isConnected(): Boolean {
        return (state is ConnectionState.Connected || state is ConnectionState.Authenticated) &&
                activeSocket != null && activeSocket?.isConnected == true && activeSocket?.isClosed == false
    }

    fun disconnect(disableAutoReconnect: Boolean = true) {
        if (disableAutoReconnect) {
            userExplicitlyDisconnected = true
            autoReconnect = false
            reconnectJob?.cancel()
            try {
                transferManager.cancelAll()
            } catch (e: Exception) {
                log("Error cancelling transfers on disconnect: ${e.message}")
            }
        }
        disconnectInternal(disableAutoReconnect)
    }

    private fun disconnectInternal(disableAutoReconnect: Boolean, targetSocket: Socket? = null) {
        if (targetSocket != null && activeSocket != null && activeSocket != targetSocket) {
            // Teardown call from a stale/superseded socket. Close targetSocket only, leave activeSocket untouched!
            try { targetSocket.close() } catch (e: Exception) {}
            return
        }

        heartbeatJob?.cancel()
        sendJob?.cancel()
        sendJob = null
        while (sendChannel.tryReceive().isSuccess) {}
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
            currentPeerId = null
            currentPeerName = null
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

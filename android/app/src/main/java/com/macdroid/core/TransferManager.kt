package com.macdroid.core

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Base64
import android.util.Log
import com.macdroid.networking.*
import com.macdroid.storage.DexoLinkSettings
import com.macdroid.storage.TrustStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.ConcurrentHashMap

data class PendingIncomingTransfer(
    val transferId: String,
    val totalSize: Long,
    val peerName: String = "MacBook",
    val timestamp: Long = System.currentTimeMillis()
)

enum class TransferStatus {
    PENDING,
    TRANSFERRING,
    PAUSED,
    VERIFYING,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class TransferItem(
    val id: String,
    val fileId: String,
    val name: String,
    val size: Long,
    var bytesTransferred: Long = 0L,
    var progress: Float = 0f,
    val isOutgoing: Boolean,
    var state: TransferStatus = TransferStatus.PENDING,
    val uri: Uri? = null,
    var localFile: File? = null,
    val startTime: Long = System.currentTimeMillis(),
    var speedBytesPerSec: Double = 0.0,
    var errorMessage: String? = null
) {
    val etaSeconds: Long
        get() = if (speedBytesPerSec > 1024 && size > bytesTransferred) {
            ((size - bytesTransferred) / speedBytesPerSec).toLong()
        } else 0L
}

interface TransferEventListener {
    fun onTransferUpdated(item: TransferItem)
    fun onTransferCompleted(item: TransferItem)
    fun onTransferFailed(item: TransferItem, error: String)
    fun onTransfersListChanged() {}
}

class TransferManager(
    private val context: Context,
    private val trustStore: TrustStore,
    private val isConnectionActive: () -> Boolean = { true },
    private val sendCallback: (ProtocolMessage) -> Unit
) {
    companion object {
        private const val TAG = "TransferManager"
        const val MAX_FILE_SIZE = 10L * 1024 * 1024 * 1024 // 10 GB limit
    }

    private val fileStreamer = FileStreamer(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val transferExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "DexoLink-TransferWorker").apply { isDaemon = true }
    }
    private val transferScope = CoroutineScope(transferExecutor.asCoroutineDispatcher() + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<TransferEventListener>()

    val activeTransfers = ConcurrentHashMap<String, TransferItem>()
    val completedTransfers = Collections.synchronizedList(mutableListOf<TransferItem>())
    val pendingIncomingTransfer = MutableStateFlow<PendingIncomingTransfer?>(null)

    fun acceptPendingTransfer(transferId: String) {
        val pending = pendingIncomingTransfer.value
        if (pending != null && pending.transferId == transferId) {
            pendingIncomingTransfer.value = null
            val item = TransferItem(
                id = transferId,
                fileId = "",
                name = "Incoming file...",
                size = pending.totalSize,
                isOutgoing = false,
                state = TransferStatus.TRANSFERRING
            )
            activeTransfers[transferId] = item
            acquireTransferLocks()
            notifyUpdate(item)
            sendCallback(TransferResponseMessage(transferId, accepted = true))
            Log.d(TAG, "Accepted pending TransferRequest $transferId")
        }
    }

    fun declinePendingTransfer(transferId: String) {
        val pending = pendingIncomingTransfer.value
        if (pending != null && pending.transferId == transferId) {
            pendingIncomingTransfer.value = null
            sendCallback(TransferResponseMessage(transferId, accepted = false))
            Log.d(TAG, "Declined pending TransferRequest $transferId")
        }
    }

    val downloadDirectory: File by lazy {
        val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dexoFolder = File(publicDownloads, "DexoLink")
        if (!dexoFolder.exists()) {
            dexoFolder.mkdirs()
        }
        if (dexoFolder.canWrite()) {
            dexoFolder
        } else {
            // Fallback to app external files dir
            val appDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
            val fallback = File(appDir, "DexoLink")
            fallback.mkdirs()
            fallback
        }
    }

    // Receiver context for handling chunk writes & progressive SHA-256
    private data class ReceiverContext(
        val transferId: String,
        val destinationFile: File,
        val raf: java.io.RandomAccessFile,
        val channel: java.nio.channels.FileChannel,
        val digest: MessageDigest,
        val expectedSize: Long,
        var receivedBytes: Long = 0L,
        var lastProgressReportBytes: Long = 0L,
        var lastNotifyTimeMs: Long = 0L
    )

    private val activeReceivers = ConcurrentHashMap<String, ReceiverContext>()
    private val senderCancelTokens = Collections.synchronizedSet(mutableSetOf<String>())

    // Transient power and network locks: held ONLY during active chunk transmission/reception.
    // 30-minute safety timeout prevents indefinite battery drain.
    private var transferWakeLock: PowerManager.WakeLock? = null
    private var transferWifiLock: WifiManager.WifiLock? = null

    @Synchronized
    private fun acquireTransferLocks() {
        try {
            if (transferWakeLock == null) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                transferWakeLock = powerManager?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "DexoLink:ActiveFileTransfer"
                )?.apply {
                    setReferenceCounted(false)
                }
            }
            if (transferWakeLock?.isHeld != true) {
                // Safety cap of 30 minutes prevents indefinite battery drain if peer crashes
                transferWakeLock?.acquire(30 * 60 * 1000L)
                Log.d(TAG, "Acquired transfer WakeLock (30-min safety cap)")
            }

            if (transferWifiLock == null) {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                transferWifiLock = wifiManager?.createWifiLock(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                    } else {
                        WifiManager.WIFI_MODE_FULL_HIGH_PERF
                    },
                    "DexoLink:TransferWifiLock"
                )?.apply {
                    setReferenceCounted(false)
                }
            }
            if (transferWifiLock?.isHeld != true) {
                transferWifiLock?.acquire()
                Log.d(TAG, "Acquired transfer WifiLock")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire transfer locks: ${e.message}")
        }
    }

    @Synchronized
    private fun releaseTransferLocksIfNeeded() {
        if (activeTransfers.isEmpty() && activeReceivers.isEmpty()) {
            try {
                if (transferWakeLock?.isHeld == true) {
                    transferWakeLock?.release()
                    Log.d(TAG, "Released transfer WakeLock (all transfers finished)")
                }
                if (transferWifiLock?.isHeld == true) {
                    transferWifiLock?.release()
                    Log.d(TAG, "Released transfer WifiLock (all transfers finished)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not release transfer locks: ${e.message}")
            }
        }
    }

    fun cleanup() {
        try {
            if (transferWakeLock?.isHeld == true) transferWakeLock?.release()
            if (transferWifiLock?.isHeld == true) transferWifiLock?.release()
        } catch (e: Exception) {
            // Ignore
        }
    }

    fun addListener(listener: TransferEventListener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: TransferEventListener) {
        listeners.remove(listener)
    }

    // MARK: - Sender API

    fun sendFile(uri: Uri) {
        scope.launch {
            if (!isConnectionActive()) {
                Log.w(TAG, "Cannot send file: Device is not connected")
                return@launch
            }
            try {
                val (size, mimeType) = fileStreamer.getFileMetadata(uri)
                val filename = fileStreamer.getFileName(uri)

                if (size <= 0) {
                    Log.w(TAG, "Cannot send empty file: $filename")
                    return@launch
                }
                if (size > MAX_FILE_SIZE) {
                    Log.w(TAG, "File exceeds 10 GB limit: $filename ($size bytes)")
                    return@launch
                }

                val transferId = UUID.randomUUID().toString()
                val fileId = UUID.randomUUID().toString()

                val item = TransferItem(
                    id = transferId,
                    fileId = fileId,
                    name = filename,
                    size = size,
                    isOutgoing = true,
                    state = TransferStatus.PENDING,
                    uri = uri
                )

                activeTransfers[transferId] = item
                acquireTransferLocks()
                notifyUpdate(item)

                val request = TransferRequestMessage(
                    transferId = transferId,
                    totalFiles = 1,
                    totalSize = size
                )
                sendCallback(request)
                Log.d(TAG, "Sent TransferRequest for $filename ($size bytes, id: $transferId)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed initiating file transfer: ${e.message}", e)
            }
        }
    }

    val pausedTransferIds = Collections.synchronizedSet(mutableSetOf<String>())

    fun pauseTransfer(id: String) {
        pausedTransferIds.add(id)
        val item = activeTransfers[id]
        if (item != null) {
            item.state = TransferStatus.PAUSED
            notifyUpdate(item)
        }
        sendCallback(PauseMessage(transferId = id))
        Log.d(TAG, "Paused transfer: $id")
    }

    fun unpauseTransfer(id: String) {
        pausedTransferIds.remove(id)
        val item = activeTransfers[id]
        if (item != null) {
            item.state = TransferStatus.TRANSFERRING
            notifyUpdate(item)
        }
        sendCallback(ResumeMessage(transferId = id))
        Log.d(TAG, "Resumed transfer: $id")
    }

    fun deleteCompletedTransfer(id: String) {
        synchronized(completedTransfers) {
            completedTransfers.removeAll { it.id == id }
        }
        notifyListChanged()
        Log.d(TAG, "Deleted transfer history item: $id")
    }

    fun deleteCompletedTransfers(ids: Set<String>) {
        synchronized(completedTransfers) {
            completedTransfers.removeAll { ids.contains(it.id) }
        }
        notifyListChanged()
        Log.d(TAG, "Deleted ${ids.size} transfer history items")
    }

    fun clearCompletedTransfers() {
        synchronized(completedTransfers) {
            completedTransfers.clear()
        }
        notifyListChanged()
        Log.d(TAG, "Cleared all transfer history items")
    }

    fun cancelTransfer(id: String) {
        scope.launch {
            pausedTransferIds.remove(id)
            senderCancelTokens.add(id)
            sendCallback(CancelMessage(transferId = id))

            val item = activeTransfers.remove(id)
            if (item != null) {
                item.state = TransferStatus.CANCELLED
                completedTransfers.add(0, item)
                notifyUpdate(item)
                notifyListChanged()
            }

            // Cleanup any receiver context matching transferId
            for ((fId, ctx) in activeReceivers) {
                if (ctx.transferId == id) {
                    try {
                        ctx.raf.close()
                        ctx.destinationFile.delete()
                    } catch (e: Exception) {
                        // Ignore
                    }
                    activeReceivers.remove(fId)
                }
            }
            Log.d(TAG, "Cancelled transfer: $id")
            releaseTransferLocksIfNeeded()
        }
    }

    fun cancelAll() {
        val ids = activeTransfers.keys().toList()
        for (id in ids) {
            cancelTransfer(id)
        }
    }

    fun resumeTransfer(id: String) {
        scope.launch {
            val item = completedTransfers.firstOrNull { it.id == id }
            if (item != null && item.isOutgoing && item.uri != null) {
                completedTransfers.remove(item)
                notifyListChanged()
                Log.d(TAG, "Resuming transfer for ${item.name} ($id)")
                sendFile(item.uri)
            }
        }
    }

    // MARK: - Message Handler

    fun handleIncomingMessage(message: ProtocolMessage) {
        transferScope.launch {
            when (message) {
                is TransferRequestMessage -> {
                    // Security 1: Enforce connection or pairing authorization
                    if (!trustStore.hasPairedDevice() && !isConnectionActive()) {
                        Log.w(TAG, "Rejecting TransferRequest: Device not paired or connected")
                        sendCallback(TransferResponseMessage(message.transferId, accepted = false))
                        return@launch
                    }

                    // Security 2: Size limits
                    if (message.totalSize <= 0 || message.totalSize > MAX_FILE_SIZE) {
                        Log.w(TAG, "Rejecting TransferRequest: Invalid size ${message.totalSize}")
                        sendCallback(TransferResponseMessage(message.transferId, accepted = false))
                        return@launch
                    }

                    // Security 3: Available storage check
                    try {
                        val stat = android.os.StatFs(downloadDirectory.absolutePath)
                        val freeBytes = stat.availableBytes
                        if (freeBytes < message.totalSize + (100L * 1024 * 1024)) {
                            Log.w(TAG, "Rejecting TransferRequest: Insufficient device storage (needs ${message.totalSize}, available $freeBytes)")
                            sendCallback(TransferResponseMessage(message.transferId, accepted = false))
                            return@launch
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not check StatFs: ${e.message}")
                    }

                    // Check 1: Is Quick File Sharing enabled in Settings?
                    val settings = DexoLinkSettings.getInstance(context)
                    if (!settings.quickFileSharing.value) {
                        Log.w(TAG, "Rejecting TransferRequest: Quick File Sharing is disabled in Settings")
                        sendCallback(TransferResponseMessage(message.transferId, accepted = false))
                        return@launch
                    }

                    // Check 2: Auto-Accept from Paired Devices
                    val isAutoAccept = settings.autoAcceptPaired.value
                    if (isAutoAccept) {
                        val item = TransferItem(
                            id = message.transferId,
                            fileId = "",
                            name = "Incoming file...",
                            size = message.totalSize,
                            isOutgoing = false,
                            state = TransferStatus.TRANSFERRING
                        )
                        activeTransfers[message.transferId] = item
                        acquireTransferLocks()
                        notifyUpdate(item)

                        sendCallback(TransferResponseMessage(message.transferId, accepted = true))
                        Log.d(TAG, "Auto-accepted TransferRequest ${message.transferId} (${message.totalSize} bytes)")
                    } else {
                        val peerName = trustStore.getPairedDevice()?.deviceName ?: "Paired Mac"
                        val pending = PendingIncomingTransfer(
                            transferId = message.transferId,
                            totalSize = message.totalSize,
                            peerName = peerName
                        )
                        pendingIncomingTransfer.value = pending
                        Log.d(TAG, "Auto-accept is OFF: Held pending TransferRequest ${message.transferId} for user approval")
                    }
                }

                is TransferResponseMessage -> {
                    val item = activeTransfers[message.transferId] ?: return@launch
                    if (message.accepted) {
                        item.state = TransferStatus.TRANSFERRING
                        acquireTransferLocks()
                        notifyUpdate(item)

                        // Start sending file chunks
                        val fileUri = item.uri ?: return@launch
                        startStreamingFile(item.id, item.fileId, fileUri, item.size)
                    } else {
                        item.state = TransferStatus.FAILED
                        item.errorMessage = "Transfer declined by peer"
                        activeTransfers.remove(message.transferId)
                        completedTransfers.add(0, item)
                        releaseTransferLocksIfNeeded()
                        notifyUpdate(item)
                        notifyFailed(item, "Declined by peer")
                    }
                }

                is FileMetaMessage -> {
                    // Security: Sanitize filename to prevent directory traversal
                    val cleanName = FileStreamer.sanitizeFilename(message.name)

                    try {
                        val destination = FileStreamer.getSafeDestinationFile(downloadDirectory, cleanName)

                        // Restrict executable permissions
                        destination.createNewFile()
                        destination.setReadable(true, false)
                        destination.setWritable(true, false)
                        destination.setExecutable(false, false)

                        val raf = java.io.RandomAccessFile(destination, "rw")
                        raf.setLength(0) // Start fresh
                        val channel = raf.channel
                        val digest = MessageDigest.getInstance("SHA-256")

                        val ctx = ReceiverContext(
                            transferId = message.transferId,
                            destinationFile = destination,
                            raf = raf,
                            channel = channel,
                            digest = digest,
                            expectedSize = message.size,
                            receivedBytes = 0L,
                            lastProgressReportBytes = 0L,
                            lastNotifyTimeMs = System.currentTimeMillis()
                        )
                        activeReceivers[message.fileId] = ctx
                        acquireTransferLocks()

                        val item = TransferItem(
                            id = message.transferId,
                            fileId = message.fileId,
                            name = destination.name,
                            size = message.size,
                            bytesTransferred = 0L,
                            progress = 0f,
                            isOutgoing = false,
                            state = TransferStatus.TRANSFERRING,
                            localFile = destination,
                            startTime = System.currentTimeMillis()
                        )
                        activeTransfers[message.transferId] = item
                        notifyUpdate(item)
                        Log.d(TAG, "Receiving file ${message.name} -> ${destination.absolutePath}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed creating destination file: ${e.message}", e)
                        sendCallback(CancelMessage(message.transferId))
                        releaseTransferLocksIfNeeded()
                    }
                }

                is FileChunkMessage -> {
                    val ctx = activeReceivers[message.fileId] ?: return@launch
                    val chunkData = Base64.decode(message.data, Base64.NO_WRAP)

                    // Security: Prevent buffer overflow attacks exceeding declared size
                    if (ctx.receivedBytes + chunkData.size > ctx.expectedSize) {
                        Log.w(TAG, "Security alert: Received bytes exceed expected size! Aborting.")
                        try {
                            ctx.channel.close()
                            ctx.raf.close()
                            ctx.destinationFile.delete()
                        } catch (e: Exception) {}
                        activeReceivers.remove(message.fileId)
                        sendCallback(CancelMessage(ctx.transferId))
                        return@launch
                    }

                    try {
                        val buffer = java.nio.ByteBuffer.wrap(chunkData)
                        ctx.channel.write(buffer, message.offset)
                        ctx.digest.update(chunkData)
                        ctx.receivedBytes += chunkData.size

                        val item = activeTransfers[ctx.transferId]
                        if (item != null) {
                            item.bytesTransferred = ctx.receivedBytes
                            item.progress = if (ctx.expectedSize > 0) ctx.receivedBytes.toFloat() / ctx.expectedSize else 0f

                            val elapsedSec = (System.currentTimeMillis() - item.startTime) / 1000.0
                            if (elapsedSec > 0.1) {
                                item.speedBytesPerSec = ctx.receivedBytes / elapsedSec
                            }

                            // Throttle UI updates to at most once per 100ms or on completion
                            val now = System.currentTimeMillis()
                            if (now - ctx.lastNotifyTimeMs >= 100L || ctx.receivedBytes >= ctx.expectedSize) {
                                ctx.lastNotifyTimeMs = now
                                notifyUpdate(item)
                            }
                        }

                        // Send progress feedback to macOS every 2 MB or upon completion
                        if (ctx.receivedBytes - ctx.lastProgressReportBytes >= 2 * 1024 * 1024 || ctx.receivedBytes >= ctx.expectedSize) {
                            ctx.lastProgressReportBytes = ctx.receivedBytes
                            sendCallback(ProgressMessage(ctx.transferId, message.fileId, ctx.receivedBytes))
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed writing chunk: ${e.message}", e)
                    }
                }

                is FileCompleteMessage -> {
                    val ctx = activeReceivers.remove(message.fileId) ?: return@launch
                    try {
                        ctx.channel.force(true)
                        ctx.channel.close()
                        ctx.raf.close()
                    } catch (e: Exception) {}

                    var computedHash = ctx.digest.digest().joinToString("") { "%02x".format(it) }
                    var verified = computedHash.equals(message.sha256, ignoreCase = true)

                    // If stream digest didn't match, double check directly from written disk file
                    if (!verified && ctx.destinationFile.exists() && ctx.destinationFile.length() == ctx.expectedSize) {
                        try {
                            val md = MessageDigest.getInstance("SHA-256")
                            ctx.destinationFile.inputStream().use { fis ->
                                val buf = ByteArray(65536)
                                var r: Int
                                while (fis.read(buf).also { r = it } != -1) {
                                    md.update(buf, 0, r)
                                }
                            }
                            val diskHash = md.digest().joinToString("") { "%02x".format(it) }
                            if (diskHash.equals(message.sha256, ignoreCase = true)) {
                                Log.d(TAG, "SHA-256 verified via disk file stream ($diskHash)")
                                verified = true
                            } else {
                                Log.e(TAG, "SHA-256 mismatch! Expected: ${message.sha256}, Stream: $computedHash, Disk: $diskHash")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Disk verification error: ${e.message}")
                        }
                    }

                    sendCallback(FileAckMessage(message.fileId, verified = verified))

                    val item = activeTransfers.remove(ctx.transferId)
                    releaseTransferLocksIfNeeded()
                    if (item != null) {
                        if (verified) {
                            item.state = TransferStatus.COMPLETED
                            item.progress = 1.0f
                            item.bytesTransferred = item.size
                            item.localFile = ctx.destinationFile
                            completedTransfers.add(0, item)
                            notifyUpdate(item)
                            notifyCompleted(item)
                            try {
                                android.media.MediaScannerConnection.scanFile(
                                    context,
                                    arrayOf(ctx.destinationFile.absolutePath),
                                    null
                                ) { path, uri ->
                                    Log.d(TAG, "Indexed to Android Downloads media store: $path -> $uri")
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Media scanner indexing error: ${e.message}")
                            }
                            Log.d(TAG, "File verified & completed: ${item.name} (${item.size} bytes)")
                        } else {
                            // Discard corrupt file
                            ctx.destinationFile.delete()
                            item.state = TransferStatus.FAILED
                            item.errorMessage = "SHA-256 verification mismatch"
                            completedTransfers.add(0, item)
                            notifyUpdate(item)
                            notifyFailed(item, "SHA-256 checksum mismatch")
                            Log.e(TAG, "SHA-256 mismatch for ${item.name}. Corrupt file discarded.")
                        }
                    }
                }

                is FileAckMessage -> {
                    for ((tId, item) in activeTransfers) {
                        if (item.fileId == message.fileId) {
                            activeTransfers.remove(tId)
                            releaseTransferLocksIfNeeded()
                            if (message.verified) {
                                item.state = TransferStatus.COMPLETED
                                item.progress = 1.0f
                                item.bytesTransferred = item.size
                                completedTransfers.add(0, item)
                                notifyUpdate(item)
                                notifyCompleted(item)
                                Log.d(TAG, "Peer confirmed receipt of ${item.name}!")
                            } else {
                                item.state = TransferStatus.FAILED
                                item.errorMessage = "Peer verification failed"
                                completedTransfers.add(0, item)
                                notifyUpdate(item)
                                notifyFailed(item, "Peer checksum failure")
                            }
                            break
                        }
                    }
                }

                is CancelMessage -> {
                    val item = activeTransfers.remove(message.transferId)
                    if (item != null) {
                        item.state = TransferStatus.CANCELLED
                        completedTransfers.add(0, item)
                        notifyUpdate(item)
                    }
                    for ((fId, ctx) in activeReceivers) {
                        if (ctx.transferId == message.transferId) {
                            try {
                                ctx.channel.close()
                                ctx.raf.close()
                                ctx.destinationFile.delete()
                            } catch (e: Exception) {}
                            activeReceivers.remove(fId)
                        }
                    }
                    Log.d(TAG, "Transfer ${message.transferId} cancelled by peer")
                    releaseTransferLocksIfNeeded()
                }

                is ProgressMessage -> {
                    val item = activeTransfers[message.transferId]
                    if (item != null && item.isOutgoing) {
                        item.bytesTransferred = message.bytesReceived
                        item.progress = if (item.size > 0) minOf(0.99f, message.bytesReceived.toFloat() / item.size) else 0f
                        val elapsedSec = (System.currentTimeMillis() - item.startTime) / 1000.0
                        if (elapsedSec > 0.1) {
                            item.speedBytesPerSec = message.bytesReceived / elapsedSec
                        }
                        notifyUpdate(item)
                    }
                }

                is PauseMessage -> {
                    Log.d(TAG, "Transfer ${message.transferId} paused by peer")
                    pausedTransferIds.add(message.transferId)
                    val item = activeTransfers[message.transferId]
                    if (item != null) {
                        item.state = TransferStatus.PAUSED
                        notifyUpdate(item)
                    }
                }

                is ResumeMessage -> {
                    Log.d(TAG, "Transfer ${message.transferId} resumed by peer")
                    pausedTransferIds.remove(message.transferId)
                    val item = activeTransfers[message.transferId]
                    if (item != null) {
                        item.state = TransferStatus.TRANSFERRING
                        notifyUpdate(item)
                    }
                }

                else -> {}
            }
        }
    }

    // MARK: - Streaming Worker

    private fun startStreamingFile(transferId: String, fileId: String, uri: Uri, declaredSize: Long) {
        scope.launch {
            try {
                val filename = fileStreamer.getFileName(uri)
                val (_, mimeType) = fileStreamer.getFileMetadata(uri)

                // Announce file metadata
                sendCallback(
                    FileMetaMessage(
                        transferId = transferId,
                        fileId = fileId,
                        name = filename,
                        size = declaredSize,
                        mimeType = mimeType
                    )
                )

                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("Cannot read file stream: $uri")

                val buffer = ByteArray(FileStreamer.CHUNK_SIZE)
                var offset = 0L
                val digest = MessageDigest.getInstance("SHA-256")
                var lastUiNotifyMs = System.currentTimeMillis()

                inputStream.use { stream ->
                    var bytesRead: Int
                    while (stream.read(buffer).also { bytesRead = it } != -1) {
                        if (senderCancelTokens.contains(transferId)) {
                            senderCancelTokens.remove(transferId)
                            Log.d(TAG, "Streaming aborted: transfer $transferId cancelled")
                            releaseTransferLocksIfNeeded()
                            return@launch
                        }

                        while (pausedTransferIds.contains(transferId)) {
                            delay(100)
                            if (senderCancelTokens.contains(transferId)) {
                                senderCancelTokens.remove(transferId)
                                Log.d(TAG, "Streaming aborted while paused: transfer $transferId cancelled")
                                releaseTransferLocksIfNeeded()
                                return@launch
                            }
                        }

                        val chunkBytes = if (bytesRead == buffer.size) buffer else buffer.copyOf(bytesRead)
                        digest.update(chunkBytes)
                        val base64Data = Base64.encodeToString(chunkBytes, Base64.NO_WRAP)

                        sendCallback(
                            FileChunkMessage(
                                fileId = fileId,
                                offset = offset,
                                data = base64Data
                            )
                        )

                        offset += bytesRead

                        val item = activeTransfers[transferId]
                        if (item != null) {
                            item.bytesTransferred = offset
                            item.progress = if (declaredSize > 0) minOf(0.99f, offset.toFloat() / declaredSize) else 0f
                            val elapsedSec = (System.currentTimeMillis() - item.startTime) / 1000.0
                            if (elapsedSec > 0.1) {
                                item.speedBytesPerSec = offset / elapsedSec
                            }
                            val now = System.currentTimeMillis()
                            if (now - lastUiNotifyMs >= 100L || offset >= declaredSize) {
                                lastUiNotifyMs = now
                                notifyUpdate(item)
                            }
                        }

                        // Cooperative yield every 4 MB to keep coroutine responsive without artificial sleep
                        if (offset % (FileStreamer.CHUNK_SIZE * 8L) == 0L) {
                            yield()
                        }
                    }
                }

                val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
                pausedTransferIds.remove(transferId)
                sendCallback(FileCompleteMessage(fileId = fileId, sha256 = sha256))

                val item = activeTransfers[transferId]
                if (item != null) {
                    item.state = TransferStatus.VERIFYING
                    notifyUpdate(item)
                }
                Log.d(TAG, "Sent FileComplete for $filename (SHA-256: ${sha256.take(10)}...)")
            } catch (e: Exception) {
                Log.e(TAG, "Error streaming file: ${e.message}", e)
                pausedTransferIds.remove(transferId)
                val item = activeTransfers.remove(transferId)
                if (item != null) {
                    item.state = TransferStatus.FAILED
                    item.errorMessage = e.localizedMessage
                    completedTransfers.add(0, item)
                    releaseTransferLocksIfNeeded()
                    notifyUpdate(item)
                    notifyListChanged()
                    notifyFailed(item, e.localizedMessage ?: "Stream error")
                }
            }
        }
    }

    private fun notifyUpdate(item: TransferItem) {
        mainHandler.post {
            for (listener in listeners) {
                listener.onTransferUpdated(item)
            }
        }
    }

    private fun notifyCompleted(item: TransferItem) {
        mainHandler.post {
            for (listener in listeners) {
                listener.onTransferCompleted(item)
            }
        }
    }

    private fun notifyFailed(item: TransferItem, error: String) {
        mainHandler.post {
            for (listener in listeners) {
                listener.onTransferFailed(item, error)
            }
        }
    }

    fun notifyListChanged() {
        mainHandler.post {
            for (listener in listeners) {
                listener.onTransfersListChanged()
            }
        }
    }
}

package com.macdroid.core

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.macdroid.networking.*
import com.macdroid.storage.TrustStore
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.ConcurrentHashMap

enum class TransferStatus {
    PENDING,
    TRANSFERRING,
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
)

interface TransferEventListener {
    fun onTransferUpdated(item: TransferItem)
    fun onTransferCompleted(item: TransferItem)
    fun onTransferFailed(item: TransferItem, error: String)
}

class TransferManager(
    private val context: Context,
    private val trustStore: TrustStore,
    private val sendCallback: (ProtocolMessage) -> Unit
) {
    companion object {
        private const val TAG = "TransferManager"
        const val MAX_FILE_SIZE = 2L * 1024 * 1024 * 1024 // 2 GB limit
    }

    private val fileStreamer = FileStreamer(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<TransferEventListener>()

    val activeTransfers = ConcurrentHashMap<String, TransferItem>()
    val completedTransfers = Collections.synchronizedList(mutableListOf<TransferItem>())

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
        val outputStream: FileOutputStream,
        val digest: MessageDigest,
        val expectedSize: Long,
        var receivedBytes: Long = 0L
    )

    private val activeReceivers = ConcurrentHashMap<String, ReceiverContext>()
    private val senderCancelTokens = Collections.synchronizedSet(mutableSetOf<String>())

    fun addListener(listener: TransferEventListener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: TransferEventListener) {
        listeners.remove(listener)
    }

    // MARK: - Sender API

    fun sendFile(uri: Uri) {
        scope.launch {
            try {
                val (size, mimeType) = fileStreamer.getFileMetadata(uri)
                val filename = fileStreamer.getFileName(uri)

                if (size <= 0) {
                    Log.w(TAG, "Cannot send empty file: $filename")
                    return@launch
                }
                if (size > MAX_FILE_SIZE) {
                    Log.w(TAG, "File exceeds 2 GB limit: $filename ($size bytes)")
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

    fun cancelTransfer(id: String) {
        scope.launch {
            senderCancelTokens.add(id)
            sendCallback(CancelMessage(transferId = id))

            val item = activeTransfers.remove(id)
            if (item != null) {
                item.state = TransferStatus.CANCELLED
                completedTransfers.add(0, item)
                notifyUpdate(item)
            }

            // Cleanup any receiver context matching transferId
            for ((fId, ctx) in activeReceivers) {
                if (ctx.transferId == id) {
                    try {
                        ctx.outputStream.close()
                        ctx.destinationFile.delete()
                    } catch (e: Exception) {
                        // Ignore
                    }
                    activeReceivers.remove(fId)
                }
            }
            Log.d(TAG, "Cancelled transfer: $id")
        }
    }

    // MARK: - Message Handler

    fun handleIncomingMessage(message: ProtocolMessage) {
        scope.launch {
            when (message) {
                is TransferRequestMessage -> {
                    // Security 1: Enforce strict 1-device policy & pairing authorization
                    if (!trustStore.hasPairedDevice()) {
                        Log.w(TAG, "⛔ Rejecting TransferRequest: Device not paired")
                        sendCallback(TransferResponseMessage(message.transferId, accepted = false))
                        return@launch
                    }

                    // Security 2: Size limits
                    if (message.totalSize <= 0 || message.totalSize > MAX_FILE_SIZE) {
                        Log.w(TAG, "⛔ Rejecting TransferRequest: Invalid size ${message.totalSize}")
                        sendCallback(TransferResponseMessage(message.transferId, accepted = false))
                        return@launch
                    }

                    // Auto-accept from authorized paired device
                    val item = TransferItem(
                        id = message.transferId,
                        fileId = "",
                        name = "Incoming file...",
                        size = message.totalSize,
                        isOutgoing = false,
                        state = TransferStatus.TRANSFERRING
                    )
                    activeTransfers[message.transferId] = item
                    notifyUpdate(item)

                    sendCallback(TransferResponseMessage(message.transferId, accepted = true))
                    Log.d(TAG, "Accepted TransferRequest ${message.transferId} (${message.totalSize} bytes)")
                }

                is TransferResponseMessage -> {
                    val item = activeTransfers[message.transferId] ?: return@launch
                    if (message.accepted) {
                        item.state = TransferStatus.TRANSFERRING
                        notifyUpdate(item)

                        // Start sending file chunks
                        val fileUri = item.uri ?: return@launch
                        startStreamingFile(item.id, item.fileId, fileUri, item.size)
                    } else {
                        item.state = TransferStatus.FAILED
                        item.errorMessage = "Transfer declined by peer"
                        activeTransfers.remove(message.transferId)
                        completedTransfers.add(0, item)
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

                        val fos = FileOutputStream(destination)
                        val digest = MessageDigest.getInstance("SHA-256")

                        val ctx = ReceiverContext(
                            transferId = message.transferId,
                            destinationFile = destination,
                            outputStream = fos,
                            digest = digest,
                            expectedSize = message.size,
                            receivedBytes = 0L
                        )
                        activeReceivers[message.fileId] = ctx

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
                    }
                }

                is FileChunkMessage -> {
                    val ctx = activeReceivers[message.fileId] ?: return@launch
                    val chunkData = Base64.decode(message.data, Base64.NO_WRAP)

                    // Security: Prevent buffer overflow attacks exceeding declared size
                    if (ctx.receivedBytes + chunkData.size > ctx.expectedSize) {
                        Log.w(TAG, "Security alert: Received bytes exceed expected size! Aborting.")
                        try {
                            ctx.outputStream.close()
                            ctx.destinationFile.delete()
                        } catch (e: Exception) {}
                        activeReceivers.remove(message.fileId)
                        sendCallback(CancelMessage(ctx.transferId))
                        return@launch
                    }

                    try {
                        ctx.outputStream.write(chunkData)
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
                            notifyUpdate(item)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed writing chunk: ${e.message}", e)
                    }
                }

                is FileCompleteMessage -> {
                    val ctx = activeReceivers.remove(message.fileId) ?: return@launch
                    try {
                        ctx.outputStream.flush()
                        ctx.outputStream.close()
                    } catch (e: Exception) {}

                    val computedHash = ctx.digest.digest().joinToString("") { "%02x".format(it) }
                    val verified = computedHash.equals(message.sha256, ignoreCase = true)

                    sendCallback(FileAckMessage(message.fileId, verified = verified))

                    val item = activeTransfers.remove(ctx.transferId)
                    if (item != null) {
                        if (verified) {
                            item.state = TransferStatus.COMPLETED
                            item.progress = 1.0f
                            item.bytesTransferred = item.size
                            item.localFile = ctx.destinationFile
                            completedTransfers.add(0, item)
                            notifyUpdate(item)
                            notifyCompleted(item)
                            Log.d(TAG, "✅ File verified & completed: ${item.name} (${item.size} bytes)")
                        } else {
                            // Discard corrupt file
                            ctx.destinationFile.delete()
                            item.state = TransferStatus.FAILED
                            item.errorMessage = "SHA-256 verification mismatch"
                            completedTransfers.add(0, item)
                            notifyUpdate(item)
                            notifyFailed(item, "SHA-256 checksum mismatch")
                            Log.e(TAG, "❌ SHA-256 mismatch for ${item.name}. Corrupt file discarded.")
                        }
                    }
                }

                is FileAckMessage -> {
                    for ((tId, item) in activeTransfers) {
                        if (item.fileId == message.fileId) {
                            activeTransfers.remove(tId)
                            if (message.verified) {
                                item.state = TransferStatus.COMPLETED
                                item.progress = 1.0f
                                item.bytesTransferred = item.size
                                completedTransfers.add(0, item)
                                notifyUpdate(item)
                                notifyCompleted(item)
                                Log.d(TAG, "🎉 Peer confirmed receipt of ${item.name}!")
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
                                ctx.outputStream.close()
                                ctx.destinationFile.delete()
                            } catch (e: Exception) {}
                            activeReceivers.remove(fId)
                        }
                    }
                    Log.d(TAG, "Transfer ${message.transferId} cancelled by peer")
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

                inputStream.use { stream ->
                    var bytesRead: Int
                    while (stream.read(buffer).also { bytesRead = it } != -1) {
                        if (senderCancelTokens.contains(transferId)) {
                            senderCancelTokens.remove(transferId)
                            Log.d(TAG, "Streaming aborted: transfer $transferId cancelled")
                            return@launch
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
                            item.progress = if (declaredSize > 0) offset.toFloat() / declaredSize else 0f
                            val elapsedSec = (System.currentTimeMillis() - item.startTime) / 1000.0
                            if (elapsedSec > 0.1) {
                                item.speedBytesPerSec = offset / elapsedSec
                            }
                            notifyUpdate(item)
                        }

                        // Slight yield every few chunks to prevent flooding
                        if (offset % (FileStreamer.CHUNK_SIZE * 4L) == 0L) {
                            delay(5)
                        }
                    }
                }

                val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
                sendCallback(FileCompleteMessage(fileId = fileId, sha256 = sha256))

                val item = activeTransfers[transferId]
                if (item != null) {
                    item.state = TransferStatus.VERIFYING
                    notifyUpdate(item)
                }
                Log.d(TAG, "Sent FileComplete for $filename (SHA-256: ${sha256.take(10)}...)")
            } catch (e: Exception) {
                Log.e(TAG, "Error streaming file: ${e.message}", e)
                val item = activeTransfers.remove(transferId)
                if (item != null) {
                    item.state = TransferStatus.FAILED
                    item.errorMessage = e.localizedMessage
                    completedTransfers.add(0, item)
                    notifyUpdate(item)
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
}

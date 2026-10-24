package com.macdroid.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.macdroid.MacDroidApplication
import com.macdroid.core.ClipboardHistoryItem
import com.macdroid.core.ClipboardSyncMode
import com.macdroid.core.TransferEventListener
import com.macdroid.core.TransferItem
import com.macdroid.core.TransferStatus
import com.macdroid.networking.*
import com.macdroid.service.MacDroidService
import com.macdroid.storage.DexoLinkSettings
import com.macdroid.storage.TrustStore
import com.macdroid.storage.TrustedDevice
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

enum class NavTab {
    HOME,
    FILES,
    TRANSFERS,
    SETTINGS,
    QR_CONNECT
}

class MainActivity : ComponentActivity(), ConnectionEventListener, DiscoveryListener, TransferEventListener {

    private val connectionManager: ConnectionManager
        get() = MacDroidApplication.connectionManager

    private val discoveryService: DiscoveryService
        get() = MacDroidApplication.discoveryService

    // UI state
    private val connectionState = mutableStateOf<ConnectionState>(ConnectionState.Disconnected)
    private val currentSyncMode = mutableStateOf(ClipboardSyncMode.TWO_WAY)
    private val isEnhancedClipboardEnabled = mutableStateOf(false)
    private val discoveredDevices = mutableStateListOf<DiscoveredDevice>()
    private val pairedDevice = mutableStateOf<TrustedDevice?>(null)
    private val logs = mutableStateListOf<String>()
    private val lastSyncedSnippet = mutableStateOf<String?>(null)
    private val lastPingLatency = mutableStateOf<Long?>(null)
    private val localDeviceName = mutableStateOf("")
    private val isDiscoveryScanning = mutableStateOf(true)
    private var isManualPingRequested = false

    // File Sharing state
    private val activeTransfers = mutableStateListOf<TransferItem>()
    private val completedTransfers = mutableStateListOf<TransferItem>()
    private val receivedFiles = mutableStateListOf<File>()
    private var pendingReconnectDeviceName: String? = null
    private val clipboardHistory = mutableStateListOf<ClipboardHistoryItem>()

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            log("Notification permission granted (background sync active)")
        } else {
            log("Notification permission denied; notifications may be hidden")
        }
    }

    private val activityClipListener = ClipboardManager.OnPrimaryClipChangedListener {
        checkAndSyncClipboard("OnPrimaryClipChanged")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Start foreground service
        MacDroidService.start(this)

        // 2. Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // 3. Attach listeners
        connectionManager.addListener(this)
        discoveryService.addListener(this)
        connectionManager.transferManager.addListener(this)
        connectionManager.clipboardManager.onHistoryChanged = {
            runOnUiThread { syncClipboardHistory() }
        }

        // 4. Initial state sync
        connectionState.value = connectionManager.state
        currentSyncMode.value = connectionManager.clipboardManager.syncMode
        isEnhancedClipboardEnabled.value = connectionManager.clipboardManager.isEnhancedClipboardEnabled
        lastSyncedSnippet.value = connectionManager.clipboardManager.lastSyncedSnippet
        pairedDevice.value = TrustStore.getInstance(this).getPairedDevice()
        localDeviceName.value = connectionManager.deviceName
        val settings = DexoLinkSettings.getInstance(this)
        val sPort = connectionManager.listeningPort.takeIf { it > 0 }
            ?: connectionManager.startServer(52840).takeIf { it > 0 }
            ?: connectionManager.startServer(0)
        if (sPort > 0 && !discoveryService.isAdvertising) {
            discoveryService.startAdvertising(sPort)
        }
        if (settings.autoDeviceDiscovery.value) {
            if (!discoveryService.isBrowsing) {
                discoveryService.startBrowsing()
            }
        } else {
            discoveryService.stopBrowsing()
        }
        isDiscoveryScanning.value = discoveryService.isBrowsing
        for (device in discoveryService.discoveredDevices.values) {
            val existingIndex = discoveredDevices.indexOfFirst {
                (!device.deviceId.isNullOrBlank() && it.deviceId == device.deviceId) ||
                (it.name.isNotBlank() && it.name == device.name) ||
                (it.host == device.host && it.port == device.port)
            }
            if (existingIndex >= 0) {
                discoveredDevices[existingIndex] = device
            } else {
                discoveredDevices.add(device)
            }
        }

        // Sync transfers & files
        syncTransferState()
        refreshReceivedFiles()
        syncClipboardHistory()

        setContent {
            DexoLinkTheme {
                val pendingTransfer by connectionManager.transferManager.pendingIncomingTransfer.collectAsState()
                if (pendingTransfer != null) {
                    val pending = pendingTransfer!!
                    val formattedSize = remember(pending.totalSize) {
                        if (pending.totalSize >= 1024 * 1024 * 1024) {
                            String.format(Locale.US, "%.1f GB", pending.totalSize / (1024.0 * 1024.0 * 1024.0))
                        } else {
                            String.format(Locale.US, "%.1f MB", pending.totalSize / (1024.0 * 1024.0))
                        }
                    }
                    AlertDialog(
                        onDismissRequest = {
                            connectionManager.transferManager.declinePendingTransfer(pending.transferId)
                        },
                        containerColor = Color.White,
                        shape = RoundedCornerShape(20.dp),
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFFEFF6FF)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    DexoIcon(
                                        imageVector = DexoIcons.Folder,
                                        contentDescription = null,
                                        tint = Color(0xFF0066FF),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    "Incoming File Transfer",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 17.sp,
                                    color = Color(0xFF0F172A)
                                )
                            }
                        },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    "Your paired Mac (${pairedDevice.value?.deviceName ?: "Mac"}) wants to send a file to this device.",
                                    fontSize = 13.sp,
                                    color = Color(0xFF334155)
                                )
                                Text(
                                    "File Size: $formattedSize",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF0F172A)
                                )
                                Text(
                                    "Auto-Accept is turned OFF in Settings. Would you like to accept this incoming file?",
                                    fontSize = 11.sp,
                                    color = Color(0xFF64748B)
                                )
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    connectionManager.transferManager.acceptPendingTransfer(pending.transferId)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0066FF)),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Accept Transfer", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        },
                        dismissButton = {
                            OutlinedButton(
                                onClick = {
                                    connectionManager.transferManager.declinePendingTransfer(pending.transferId)
                                },
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, Color(0xFFCBD5E1))
                            ) {
                                Text("Decline", color = Color(0xFF64748B))
                            }
                        }
                    )
                }

                MainScreenRoot(
                    connectionState = connectionState.value,
                    syncMode = currentSyncMode.value,
                    devices = discoveredDevices,
                    pairedDevice = pairedDevice.value,
                    localDeviceName = localDeviceName.value,
                    localPort = connectionManager.listeningPort.takeIf { it > 0 } ?: 52840,
                    lastPingLatency = lastPingLatency.value,
                    logs = logs,
                    lastSyncedSnippet = lastSyncedSnippet.value,
                    activeTransfers = activeTransfers,
                    completedTransfers = completedTransfers,
                    receivedFiles = receivedFiles,
                    isEnhancedClipboardEnabled = isEnhancedClipboardEnabled.value,
                    onToggleEnhancedClipboard = { enabled ->
                        connectionManager.clipboardManager.isEnhancedClipboardEnabled = enabled
                        isEnhancedClipboardEnabled.value = enabled
                        log("Enhanced Clipboard ${if (enabled) "enabled" else "disabled"}")
                    },
                    onModeSelected = { mode ->
                        currentSyncMode.value = mode
                        connectionManager.setClipboardSyncMode(mode)
                    },
                    onConnect = { device ->
                        val settings = com.macdroid.storage.DexoLinkSettings.getInstance(this)
                        val trustStore = TrustStore.getInstance(this)
                        val isSingleMode = !settings.multiPairedDevices.value && (trustStore.hasPairedDevice() || connectionManager.isConnected())
                        val devId = device.deviceId ?: ""
                        if (isSingleMode && !trustStore.isPairedWith(devId, device.name)) {
                            val currentPairedName = trustStore.getPairedDevice()?.deviceName ?: "Current Device"
                            Toast.makeText(
                                this,
                                "Single Device Mode active. Unpair '$currentPairedName' or enable Multiple Paired Devices in Settings.",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            connectionManager.connect(device.host, device.port)
                        }
                    },
                    onConnectPaired = {
                        val paired = pairedDevice.value ?: TrustStore.getInstance(this).getPairedDevice()
                        if (paired != null) {
                            val matchingDiscovered = discoveredDevices.firstOrNull { it.name.equals(paired.deviceName, ignoreCase = true) }
                            if (matchingDiscovered != null) {
                                connectionManager.connect(matchingDiscovered.host, matchingDiscovered.port)
                            } else {
                                val host = connectionManager.lastConnectedHost ?: ""
                                val port = if (connectionManager.lastConnectedPort > 0) connectionManager.lastConnectedPort else 52840
                                if (host.isNotBlank()) {
                                    connectionManager.connect(host, port)
                                } else {
                                    pendingReconnectDeviceName = paired.deviceName
                                    discoveredDevices.clear()
                                    discoveryService.stopBrowsing()
                                    discoveryService.startBrowsing()
                                    Toast.makeText(this, "Searching for ${paired.deviceName} on Wi-Fi...", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    onConnectManual = { host, port ->
                        val settings = com.macdroid.storage.DexoLinkSettings.getInstance(this)
                        val trustStore = TrustStore.getInstance(this)
                        val isSingleMode = !settings.multiPairedDevices.value && (trustStore.hasPairedDevice() || connectionManager.isConnected())
                        val existing = trustStore.getPairedDevice()
                        if (isSingleMode && existing != null && existing.deviceId != host && existing.deviceName != host) {
                            val currentPairedName = existing.deviceName
                            Toast.makeText(
                                this,
                                "Single Device Mode active. Unpair '$currentPairedName' or enable Multiple Paired Devices in Settings.",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            connectionManager.connect(host, port)
                            log("Connecting to manual IP: $host:$port")
                        }
                    },
                    onDisconnect = {
                        connectionManager.disconnect(disableAutoReconnect = true)
                    },
                    onUnpair = {
                        connectionManager.unpairCurrentDevice()
                        pairedDevice.value = null
                    },
                    onConfirmPairing = {
                        connectionManager.confirmPairing()
                        pairedDevice.value = TrustStore.getInstance(this).getPairedDevice()
                    },
                    onRejectPairing = {
                        connectionManager.rejectPairing()
                    },
                    onSendPing = {
                        isManualPingRequested = true
                        connectionManager.sendPing()
                    },
                    onCheckClipboard = {
                        checkAndSyncClipboard("QuickAction")
                    },
                    onRefreshDiscovery = {
                        DexoLinkSettings.getInstance(this).setAutoDeviceDiscovery(true)
                        discoveredDevices.clear()
                        discoveryService.stopBrowsing()
                        discoveryService.startBrowsing()
                        isDiscoveryScanning.value = true
                        log("Refreshed Wi-Fi discovery")
                    },
                    isScanning = isDiscoveryScanning.value,
                    onStartDiscovery = {
                        DexoLinkSettings.getInstance(this).setAutoDeviceDiscovery(true)
                        discoveredDevices.clear()
                        discoveryService.startBrowsing()
                        isDiscoveryScanning.value = true
                        log("Started nearby device discovery")
                    },
                    onStopDiscovery = {
                        DexoLinkSettings.getInstance(this).setAutoDeviceDiscovery(false)
                        discoveryService.stopBrowsing()
                        isDiscoveryScanning.value = false
                        log("Stopped device discovery")
                    },
                    onSendFiles = { uris ->
                        uris.forEach { uri ->
                            connectionManager.sendFile(uri)
                            log("Queueing file for transfer: $uri")
                        }
                    },
                    onCancelTransfer = { id ->
                        connectionManager.cancelTransfer(id)
                        log("Cancelled transfer $id")
                    },
                    onOpenFile = { file ->
                        openReceivedFile(file)
                    },
                    onShareFile = { file ->
                        shareReceivedFile(file)
                    },
                    onDeleteFile = { file ->
                        deleteReceivedFile(file)
                    },
                    onResumeTransfer = { id ->
                        connectionManager.resumeTransfer(id)
                    },
                    onClearLogs = {
                        logs.clear()
                    },
                    onUpdateDeviceName = { newName ->
                        connectionManager.setDeviceName(newName)
                        localDeviceName.value = newName
                        discoveryService.stopAdvertising()
                        discoveryService.startAdvertising(connectionManager.lastConnectedPort.takeIf { it > 0 } ?: 52840)
                        log("Device renamed to $newName")
                        Toast.makeText(this, "Device renamed to $newName", Toast.LENGTH_SHORT).show()
                    },
                    onRenamePaired = { newName ->
                        val current = pairedDevice.value
                        if (current != null) {
                            val updated = current.copy(deviceName = newName)
                            TrustStore.getInstance(this).setPairedDevice(updated)
                            pairedDevice.value = updated
                            log("Renamed paired Mac to $newName")
                            Toast.makeText(this, "Paired device renamed to $newName", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onRequestPairing = {
                        if (connectionManager.isConnected()) {
                            connectionManager.requestPairing()
                            Toast.makeText(this, "Sent pairing request to Mac...", Toast.LENGTH_SHORT).show()
                        } else {
                            val paired = pairedDevice.value
                            if (paired != null) {
                                val host = connectionManager.lastConnectedHost ?: ""
                                val port = if (connectionManager.lastConnectedPort > 0) connectionManager.lastConnectedPort else 52840
                                if (host.isNotBlank()) {
                                    connectionManager.connect(host, port)
                                    Toast.makeText(this, "Connecting to ${paired.deviceName}...", Toast.LENGTH_SHORT).show()
                                } else {
                                    discoveredDevices.clear()
                                    discoveryService.stopBrowsing()
                                    discoveryService.startBrowsing()
                                    Toast.makeText(this, "Searching for ${paired.deviceName} on Wi-Fi...", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                discoveredDevices.clear()
                                discoveryService.stopBrowsing()
                                discoveryService.startBrowsing()
                                Toast.makeText(this, "Scanning for nearby devices to pair...", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    clipboardHistory = clipboardHistory,
                    onPauseTransfer = { id ->
                        connectionManager.pauseTransfer(id)
                        log("Paused transfer: $id")
                    },
                    onResumeOngoingTransfer = { id ->
                        connectionManager.unpauseTransfer(id)
                        log("Resumed transfer: $id")
                    },
                    onDeleteTransfer = { id ->
                        connectionManager.deleteCompletedTransfer(id)
                        syncTransferState()
                        log("Deleted transfer from history: $id")
                    },
                    onDeleteTransfers = { ids ->
                        connectionManager.deleteCompletedTransfers(ids)
                        syncTransferState()
                        log("Deleted ${ids.size} transfers from history")
                    },
                    onClearTransfers = {
                        connectionManager.clearCompletedTransfers()
                        syncTransferState()
                        log("Cleared transfer history")
                        Toast.makeText(this, "Cleared transfer history", Toast.LENGTH_SHORT).show()
                    },
                    onDeleteClipboardItem = { id ->
                        connectionManager.clipboardManager.deleteHistoryItem(id)
                        syncClipboardHistory()
                        log("Deleted clipboard history item: $id")
                    },
                    onDeleteClipboardItems = { ids ->
                        connectionManager.clipboardManager.deleteHistoryItems(ids)
                        syncClipboardHistory()
                        log("Deleted ${ids.size} clipboard items from history")
                    },
                    onClearClipboardHistory = {
                        connectionManager.clipboardManager.clearHistory()
                        syncClipboardHistory()
                        log("Cleared clipboard history")
                        Toast.makeText(this, "Cleared clipboard history", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }

    private fun syncTransferState() {
        activeTransfers.clear()
        activeTransfers.addAll(connectionManager.transferManager.activeTransfers.values)
        completedTransfers.clear()
        completedTransfers.addAll(connectionManager.transferManager.completedTransfers)
    }

    private fun syncClipboardHistory() {
        clipboardHistory.clear()
        clipboardHistory.addAll(connectionManager.clipboardManager.getHistory())
    }

    private fun refreshReceivedFiles() {
        val dir = connectionManager.transferManager.downloadDirectory
        val files = dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()
        receivedFiles.clear()
        receivedFiles.addAll(files)
    }

    private fun openReceivedFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "com.macdroid.fileprovider", file)
            val mimeType = contentResolver.getType(uri) ?: getMimeTypeFromExtension(file.extension)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Unable to open file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareReceivedFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "com.macdroid.fileprovider", file)
            val mimeType = contentResolver.getType(uri) ?: getMimeTypeFromExtension(file.extension)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share ${file.name}"))
        } catch (e: Exception) {
            Toast.makeText(this, "Unable to share file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteReceivedFile(file: File) {
        try {
            if (file.exists() && file.delete()) {
                refreshReceivedFiles()
                log("Deleted file: ${file.name}")
                Toast.makeText(this, "Deleted ${file.name}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Failed to delete ${file.name}", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error deleting file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getMimeTypeFromExtension(ext: String): String {
        return when (ext.lowercase(Locale.ROOT)) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "doc", "docx" -> "application/msword"
            "zip" -> "application/zip"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            else -> "*/*"
        }
    }

    override fun onStart() {
        super.onStart()
        try {
            val clipManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipManager?.addPrimaryClipChangedListener(activityClipListener)
        } catch (e: Exception) {
            log("[Clipboard Error] Failed registering clip listener: ${e.message}")
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            val clipManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipManager?.removePrimaryClipChangedListener(activityClipListener)
        } catch (e: Exception) {
            // Ignore
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            checkAndSyncClipboard("WindowFocusChanged")
        }
    }

    override fun onResume() {
        super.onResume()
        connectionState.value = connectionManager.state
        currentSyncMode.value = connectionManager.clipboardManager.syncMode
        isEnhancedClipboardEnabled.value = connectionManager.clipboardManager.isEnhancedClipboardEnabled
        lastSyncedSnippet.value = connectionManager.clipboardManager.lastSyncedSnippet
        pairedDevice.value = TrustStore.getInstance(this).getPairedDevice()
        val activePort = connectionManager.listeningPort.takeIf { it > 0 }
            ?: connectionManager.startServer(52840).takeIf { it > 0 }
            ?: connectionManager.startServer(0)
        if (activePort > 0 && !discoveryService.isAdvertising) {
            discoveryService.startAdvertising(activePort)
        }
        refreshReceivedFiles()
        checkAndSyncClipboard("onResume")
    }

    private fun checkAndSyncClipboard(trigger: String) {
        try {
            val clipManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            val clip = clipManager.primaryClip ?: return
            if (clip.itemCount == 0) return
            val text = clip.getItemAt(0)?.text?.toString() ?: return
            if (text.isEmpty()) return

            if (connectionManager.clipboardManager.shouldSend(text)) {
                log("[Clipboard Detect:$trigger] Found text: \"${text.take(30)}...\" (${text.length} chars)")
                if (connectionManager.isConnected()) {
                    connectionManager.sendClipboard(text, force = true)
                    lastSyncedSnippet.value = text
                    Toast.makeText(this, "Copied text synced to Mac", Toast.LENGTH_SHORT).show()
                } else {
                    log("[Clipboard Stored] Mac not connected right now")
                }
            }
        } catch (e: Exception) {
            log("[Clipboard Error] Failed reading clip ($trigger): ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        connectionManager.clipboardManager.onHistoryChanged = null
        connectionManager.removeListener(this)
        discoveryService.removeListener(this)
        connectionManager.transferManager.removeListener(this)
    }

    // ConnectionEventListener
    override fun onStateChanged(state: ConnectionState) {
        connectionState.value = state
        pairedDevice.value = TrustStore.getInstance(this).getPairedDevice()
        if (state is ConnectionState.Authenticated || state is ConnectionState.Connected) {
            isDiscoveryScanning.value = false
        } else if (state is ConnectionState.Disconnected || state is ConnectionState.Failed) {
            val autoDisc = DexoLinkSettings.getInstance(this).autoDeviceDiscovery.value
            if (autoDisc && pairedDevice.value == null) {
                discoveryService.startBrowsing()
                isDiscoveryScanning.value = true
            }
        }
    }

    override fun onMessageReceived(message: ProtocolMessage) {
        if (message is PongMessage) {
            val latency = connectionManager.lastPingLatency
            runOnUiThread {
                lastPingLatency.value = latency
                if (isManualPingRequested) {
                    isManualPingRequested = false
                    Toast.makeText(this, "Ping reply: ${latency ?: 0}ms", Toast.LENGTH_SHORT).show()
                }
            }
        } else if (message is ClipboardPushMessage) {
            lastSyncedSnippet.value = message.content
        } else if (message is ClipboardAckMessage) {
            log("[Clipboard ACK] Mac confirmed receipt of sequence ${message.sequenceNumber}")
        }
    }

    override fun onLog(log: String) {
        log(log)
    }

    // DiscoveryListener
    override fun onDeviceFound(device: DiscoveredDevice) {
        val existingIndex = discoveredDevices.indexOfFirst {
            (!device.deviceId.isNullOrBlank() && it.deviceId == device.deviceId) ||
            (it.name.isNotBlank() && it.name == device.name) ||
            (it.host == device.host && it.port == device.port)
        }
        if (existingIndex >= 0) {
            discoveredDevices[existingIndex] = device
        } else {
            discoveredDevices.add(device)
            log("Discovered: ${device.name} @ ${device.host}:${device.port}")
        }
        if (pendingReconnectDeviceName.equals(device.name, ignoreCase = true)) {
            pendingReconnectDeviceName = null
            runOnUiThread { connectionManager.connect(device.host, device.port) }
        }
    }

    override fun onDeviceLost(name: String) {
        discoveredDevices.removeAll { it.name == name }
        log("Device lost: $name")
    }

    override fun onError(message: String) {
        log("Discovery error: $message")
    }

    // TransferEventListener
    override fun onTransferUpdated(item: TransferItem) {
        runOnUiThread {
            val idx = activeTransfers.indexOfFirst { it.id == item.id }
            if (idx >= 0) {
                activeTransfers[idx] = item
            } else {
                activeTransfers.add(0, item)
            }
        }
    }

    override fun onTransferCompleted(item: TransferItem) {
        runOnUiThread {
            activeTransfers.removeAll { it.id == item.id }
            val existing = completedTransfers.indexOfFirst { it.id == item.id }
            if (existing >= 0) {
                completedTransfers[existing] = item
            } else {
                completedTransfers.add(0, item)
            }
            refreshReceivedFiles()
            Toast.makeText(this, "File transfer completed: ${item.name}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onTransferFailed(item: TransferItem, error: String) {
        runOnUiThread {
            activeTransfers.removeAll { it.id == item.id }
            val existing = completedTransfers.indexOfFirst { it.id == item.id }
            if (existing >= 0) {
                completedTransfers[existing] = item
            } else {
                completedTransfers.add(0, item)
            }
            Toast.makeText(this, "Transfer failed: $error", Toast.LENGTH_LONG).show()
        }
    }

    override fun onTransfersListChanged() {
        runOnUiThread {
            syncTransferState()
        }
    }

    private fun log(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logs.add(0, "[$time] $message")
        if (logs.size > 80) logs.removeAt(logs.lastIndex)
    }
}

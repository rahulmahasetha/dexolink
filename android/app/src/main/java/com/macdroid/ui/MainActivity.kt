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
import com.macdroid.core.ClipboardSyncMode
import com.macdroid.core.TransferEventListener
import com.macdroid.core.TransferItem
import com.macdroid.core.TransferStatus
import com.macdroid.networking.*
import com.macdroid.service.MacDroidService
import com.macdroid.storage.TrustStore
import com.macdroid.storage.TrustedDevice
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

enum class NavTab {
    HOME,
    FILES,
    TRANSFERS,
    SETTINGS
}

class MainActivity : ComponentActivity(), ConnectionEventListener, DiscoveryListener, TransferEventListener {

    private val connectionManager: ConnectionManager
        get() = MacDroidApplication.connectionManager

    private val discoveryService: DiscoveryService
        get() = MacDroidApplication.discoveryService

    // UI state
    private val connectionState = mutableStateOf<ConnectionState>(ConnectionState.Disconnected)
    private val currentSyncMode = mutableStateOf(ClipboardSyncMode.TWO_WAY)
    private val discoveredDevices = mutableStateListOf<DiscoveredDevice>()
    private val pairedDevice = mutableStateOf<TrustedDevice?>(null)
    private val logs = mutableStateListOf<String>()
    private val lastSyncedSnippet = mutableStateOf<String?>(null)

    // File Sharing state
    private val activeTransfers = mutableStateListOf<TransferItem>()
    private val completedTransfers = mutableStateListOf<TransferItem>()
    private val receivedFiles = mutableStateListOf<File>()

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

        // 4. Initial state sync
        connectionState.value = connectionManager.state
        currentSyncMode.value = connectionManager.clipboardManager.syncMode
        lastSyncedSnippet.value = connectionManager.clipboardManager.lastSyncedSnippet
        pairedDevice.value = TrustStore.getInstance(this).getPairedDevice()
        for (device in discoveryService.discoveredDevices.values) {
            if (discoveredDevices.none { it.host == device.host && it.port == device.port }) {
                discoveredDevices.add(device)
            }
        }

        // Sync transfers & files
        syncTransferState()
        refreshReceivedFiles()

        setContent {
            DexoLinkTheme {
                MainScreenRoot(
                    connectionState = connectionState.value,
                    syncMode = currentSyncMode.value,
                    devices = discoveredDevices,
                    pairedDevice = pairedDevice.value,
                    logs = logs,
                    lastSyncedSnippet = lastSyncedSnippet.value,
                    activeTransfers = activeTransfers,
                    completedTransfers = completedTransfers,
                    receivedFiles = receivedFiles,
                    onModeSelected = { mode ->
                        currentSyncMode.value = mode
                        connectionManager.setClipboardSyncMode(mode)
                    },
                    onConnect = { device ->
                        connectionManager.connect(device.host, device.port)
                    },
                    onConnectManual = { host, port ->
                        connectionManager.connect(host, port)
                        log("Connecting to manual IP: $host:$port")
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
                        connectionManager.sendPing()
                    },
                    onRefreshDiscovery = {
                        discoveredDevices.clear()
                        discoveryService.stopBrowsing()
                        discoveryService.startBrowsing()
                        log("Refreshed Wi-Fi discovery")
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
                    onClearLogs = {
                        logs.clear()
                    },
                    onUpdateDeviceName = { newName ->
                        connectionManager.setDeviceName(newName)
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
        lastSyncedSnippet.value = connectionManager.clipboardManager.lastSyncedSnippet
        pairedDevice.value = TrustStore.getInstance(this).getPairedDevice()
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
                    Toast.makeText(this, "Copied text synced to Mac! 📋", Toast.LENGTH_SHORT).show()
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
        connectionManager.removeListener(this)
        discoveryService.removeListener(this)
        connectionManager.transferManager.removeListener(this)
    }

    // ConnectionEventListener
    override fun onStateChanged(state: ConnectionState) {
        connectionState.value = state
        pairedDevice.value = TrustStore.getInstance(this).getPairedDevice()
    }

    override fun onMessageReceived(message: ProtocolMessage) {
        if (message is PongMessage) {
            Toast.makeText(this, "Pong received from Mac! 🚀", Toast.LENGTH_SHORT).show()
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
        if (discoveredDevices.none { it.host == device.host && it.port == device.port }) {
            discoveredDevices.add(device)
            log("Discovered: ${device.name} @ ${device.host}:${device.port}")
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
            Toast.makeText(this, "File transfer completed: ${item.name} ✅", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "Transfer failed: $error ⚠️", Toast.LENGTH_LONG).show()
        }
    }

    private fun log(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logs.add(0, "[$time] $message")
        if (logs.size > 80) logs.removeAt(logs.lastIndex)
    }
}

// -------------------------------------------------------------
// UI Design System & Theme
// -------------------------------------------------------------

@Composable
fun DexoLinkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF388AF6),
            onPrimary = Color.White,
            secondary = Color(0xFF64D2FF),
            background = Color(0xFF070B14),
            surface = Color(0xFF101726),
            onSurface = Color(0xFFF1F5F9),
            surfaceVariant = Color(0xFF162033),
            outline = Color(0xFF233047)
        ),
        content = content
    )
}

// Modifier extension for dashed border (used for dropzone)
fun Modifier.dashedBorder(
    strokeWidth: Dp,
    color: Color,
    cornerRadius: Dp,
    dashLength: Dp = 10.dp,
    gapLength: Dp = 8.dp
) = this.drawBehind {
    val stroke = Stroke(
        width = strokeWidth.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashLength.toPx(), gapLength.toPx()), 0f)
    )
    drawRoundRect(
        color = color,
        topLeft = Offset(strokeWidth.toPx() / 2, strokeWidth.toPx() / 2),
        size = Size(size.width - strokeWidth.toPx(), size.height - strokeWidth.toPx()),
        cornerRadius = CornerRadius(cornerRadius.toPx(), cornerRadius.toPx()),
        style = stroke
    )
}

// -------------------------------------------------------------
// Vector Graphics (MacBook, Phone, DexoLink Chains, Icons)
// -------------------------------------------------------------

@Composable
fun DexoLinkChainIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        val gradient = Brush.linearGradient(
            colors = listOf(Color(0xFF00E5FF), Color(0xFF2979FF)),
            start = Offset(0f, 0f),
            end = Offset(w, h)
        )
        // Left loop
        withTransform({
            rotate(-35f, pivot = Offset(w * 0.4f, h * 0.5f))
        }) {
            drawRoundRect(
                brush = gradient,
                topLeft = Offset(w * 0.1f, h * 0.32f),
                size = Size(w * 0.52f, h * 0.36f),
                cornerRadius = CornerRadius(h * 0.18f, h * 0.18f),
                style = stroke
            )
        }
        // Right loop
        withTransform({
            rotate(-35f, pivot = Offset(w * 0.6f, h * 0.5f))
        }) {
            drawRoundRect(
                brush = gradient,
                topLeft = Offset(w * 0.38f, h * 0.32f),
                size = Size(w * 0.52f, h * 0.36f),
                cornerRadius = CornerRadius(h * 0.18f, h * 0.18f),
                style = stroke
            )
        }
    }
}

@Composable
fun MacBookGraphic(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val screenW = w * 0.84f
        val screenH = h * 0.72f
        val screenLeft = (w - screenW) / 2
        val screenTop = h * 0.04f

        // Screen Bezel
        drawRoundRect(
            color = Color(0xFF1E242B),
            topLeft = Offset(screenLeft, screenTop),
            size = Size(screenW, screenH),
            cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
        )
        // Screen Wallpaper (Vibrant Sunset Purple/Magenta)
        drawRoundRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color(0xFF7C3AED), Color(0xFFEC4899), Color(0xFFF97316))
            ),
            topLeft = Offset(screenLeft + 3.dp.toPx(), screenTop + 3.dp.toPx()),
            size = Size(screenW - 6.dp.toPx(), screenH - 6.dp.toPx()),
            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
        )
        // Laptop Base
        val baseH = h * 0.16f
        val baseTop = screenTop + screenH
        drawRoundRect(
            color = Color(0xFF94A3B8),
            topLeft = Offset(w * 0.04f, baseTop),
            size = Size(w * 0.92f, baseH),
            cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
        )
        // Notch opening
        drawRoundRect(
            color = Color(0xFF475569),
            topLeft = Offset(w * 0.42f, baseTop),
            size = Size(w * 0.16f, baseH * 0.45f),
            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
        )
    }
}

@Composable
fun PhoneGraphic(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        // Phone Body
        drawRoundRect(
            color = Color(0xFF1E242B),
            topLeft = Offset(0f, 0f),
            size = Size(w, h),
            cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
        )
        // Wallpaper (Flame orange/red gradient)
        drawRoundRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color(0xFFF97316), Color(0xFFDC2626), Color(0xFF7C2D12))
            ),
            topLeft = Offset(2.dp.toPx(), 2.dp.toPx()),
            size = Size(w - 4.dp.toPx(), h - 4.dp.toPx()),
            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
        )
        // Camera hole
        drawCircle(
            color = Color(0xFF0F172A),
            radius = 1.6.dp.toPx(),
            center = Offset(w / 2, 4.dp.toPx())
        )
    }
}

// -------------------------------------------------------------
// Main Screen Layout (Root with Bottom Navigation & Tabs)
// -------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreenRoot(
    connectionState: ConnectionState,
    syncMode: ClipboardSyncMode,
    devices: List<DiscoveredDevice>,
    pairedDevice: TrustedDevice?,
    logs: List<String>,
    lastSyncedSnippet: String?,
    activeTransfers: List<TransferItem>,
    completedTransfers: List<TransferItem>,
    receivedFiles: List<File>,
    onModeSelected: (ClipboardSyncMode) -> Unit,
    onConnect: (DiscoveredDevice) -> Unit,
    onConnectManual: (String, Int) -> Unit,
    onDisconnect: () -> Unit,
    onUnpair: () -> Unit,
    onConfirmPairing: () -> Unit,
    onRejectPairing: () -> Unit,
    onSendPing: () -> Unit,
    onRefreshDiscovery: () -> Unit,
    onSendFiles: (List<Uri>) -> Unit,
    onCancelTransfer: (String) -> Unit,
    onOpenFile: (File) -> Unit,
    onShareFile: (File) -> Unit,
    onClearLogs: () -> Unit,
    onUpdateDeviceName: (String) -> Unit
) {
    var selectedTab by remember { mutableStateOf(NavTab.HOME) }
    var showManualIPDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showSecurityDialog by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }

    // File picker launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri>? ->
        uris?.let { if (it.isNotEmpty()) onSendFiles(it) }
    }

    // Modal: Device Pairing Request
    if (connectionState is ConnectionState.Pairing) {
        AlertDialog(
            onDismissRequest = onRejectPairing,
            containerColor = Color(0xFF131A2B),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🔐", fontSize = 20.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Device Pairing Request", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Pairing with ${connectionState.peerName}",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        color = Color.White
                    )
                    Text(
                        "Verify that this 6-digit PIN matches the code displayed on your Mac:",
                        fontSize = 13.sp,
                        color = Color(0xFF94A3B8),
                        textAlign = TextAlign.Center
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF24143D))
                            .border(1.dp, Color(0xFFC084FC), RoundedCornerShape(12.dp))
                            .padding(horizontal = 24.dp, vertical = 12.dp)
                    ) {
                        Text(
                            connectionState.pin,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 32.sp,
                            letterSpacing = 6.sp,
                            color = Color(0xFFE9D5FF)
                        )
                    }
                    Text(
                        "Strict 1-Device Policy: Only 1 authorized device can be paired. Other requests will be automatically rejected.",
                        fontSize = 11.sp,
                        color = Color(0xFFC084FC),
                        textAlign = TextAlign.Center
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = onConfirmPairing,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9333EA))
                ) {
                    Text("Pair & Trust Device", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = onRejectPairing) {
                    Text("Decline", color = Color.Gray)
                }
            }
        )
    }

    // Modal: Manual IP Entry Fallback
    if (showManualIPDialog) {
        var ipInput by remember { mutableStateOf("") }
        var portInput by remember { mutableStateOf("8765") }

        AlertDialog(
            onDismissRequest = { showManualIPDialog = false },
            containerColor = Color(0xFF131A2B),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🔗", fontSize = 20.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Manual IP Connection", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Connect directly using your Mac's local IP address if Wi-Fi discovery is restricted by your router.",
                        fontSize = 12.sp,
                        color = Color(0xFF94A3B8)
                    )
                    OutlinedTextField(
                        value = ipInput,
                        onValueChange = { ipInput = it },
                        label = { Text("Mac IP Address (e.g. 192.168.1.15)") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF388AF6),
                            unfocusedBorderColor = Color(0xFF334155)
                        )
                    )
                    OutlinedTextField(
                        value = portInput,
                        onValueChange = { portInput = it },
                        label = { Text("Port (default: 8765)") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF388AF6),
                            unfocusedBorderColor = Color(0xFF334155)
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val port = portInput.toIntOrNull() ?: 8765
                        if (ipInput.isNotBlank()) {
                            onConnectManual(ipInput.trim(), port)
                            showManualIPDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                ) {
                    Text("Connect", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualIPDialog = false }) {
                    Text("Cancel", color = Color.Gray)
                }
            }
        )
    }

    // Modal: Security Overview & Defense-in-depth Notice
    if (showSecurityDialog) {
        AlertDialog(
            onDismissRequest = { showSecurityDialog = false },
            containerColor = Color(0xFF131A2B),
            title = {
                Text("🛡️ Security & Privacy", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("• Hardware-backed Android Keystore: Pairing keys encrypted using AES-256-GCM.", fontSize = 12.sp, color = Color(0xFFE2E8F0))
                    Text("• TLS & Framing: All socket payloads encrypted with replay protection and length-prefixed framing.", fontSize = 12.sp, color = Color(0xFFE2E8F0))
                    Text("• Strict 1-Device Policy: Only 1 authorized device can be paired and transfer files.", fontSize = 12.sp, color = Color(0xFFE2E8F0))
                    Text("• Sandboxed File Storage: Path traversal prevention (../ filtered) & SHA-256 integrity verification.", fontSize = 12.sp, color = Color(0xFFE2E8F0))
                    Divider(color = Color(0xFF334155))
                    Text(
                        "Defense-in-Depth Notice: We adhere to strict security best practices. However, no wireless software system is completely unhackable. Always keep your devices updated and pair only in trusted networks.",
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8),
                        lineHeight = 15.sp
                    )
                }
            },
            confirmButton = {
                Button(onClick = { showSecurityDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        DexoLinkChainIcon(modifier = Modifier.size(32.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "DexoLink",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 20.sp,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color(0xFF381B6D))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        "v2",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFC084FC)
                                    )
                                }
                            }
                            Text(
                                "Connect • Sync • Share",
                                fontSize = 11.sp,
                                color = Color(0xFF8E9BAE)
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { selectedTab = NavTab.SETTINGS }) {
                        Text("⚙️", fontSize = 18.sp)
                    }
                    Box {
                        IconButton(onClick = { showOverflowMenu = true }) {
                            Text("⋮", fontSize = 20.sp, color = Color.White)
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false },
                            modifier = Modifier.background(Color(0xFF1E293B))
                        ) {
                            DropdownMenuItem(
                                text = { Text("Scan / Refresh Wi-Fi", color = Color.White) },
                                onClick = {
                                    showOverflowMenu = false
                                    onRefreshDiscovery()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Manual IP Connection", color = Color.White) },
                                onClick = {
                                    showOverflowMenu = false
                                    showManualIPDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Security Overview", color = Color.White) },
                                onClick = {
                                    showOverflowMenu = false
                                    showSecurityDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Clear Activity Log", color = Color.White) },
                                onClick = {
                                    showOverflowMenu = false
                                    onClearLogs()
                                }
                            )
                            if (pairedDevice != null) {
                                DropdownMenuItem(
                                    text = { Text("Unpair Device", color = Color(0xFFEF4444)) },
                                    onClick = {
                                        showOverflowMenu = false
                                        onUnpair()
                                    }
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF070B14)
                )
            )
        },
        bottomBar = {
            DexoLinkBottomNavBar(
                selectedTab = selectedTab,
                activeTransfersCount = activeTransfers.size,
                onTabSelected = { selectedTab = it }
            )
        },
        containerColor = Color(0xFF070B14)
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (selectedTab) {
                NavTab.HOME -> {
                    HomeScreenContent(
                        connectionState = connectionState,
                        syncMode = syncMode,
                        devices = devices,
                        pairedDevice = pairedDevice,
                        logs = logs,
                        lastSyncedSnippet = lastSyncedSnippet,
                        activeTransfers = activeTransfers,
                        onModeSelected = onModeSelected,
                        onConnect = onConnect,
                        onDisconnect = onDisconnect,
                        onUnpair = onUnpair,
                        onSendPing = onSendPing,
                        onRefreshDiscovery = onRefreshDiscovery,
                        onOpenFilePicker = { filePickerLauncher.launch(arrayOf("*/*")) },
                        onOpenManualIP = { showManualIPDialog = true },
                        onNavigateFiles = { selectedTab = NavTab.FILES },
                        onNavigateTransfers = { selectedTab = NavTab.TRANSFERS },
                        onCancelTransfer = onCancelTransfer
                    )
                }
                NavTab.FILES -> {
                    FilesTabContent(
                        receivedFiles = receivedFiles,
                        onOpenFile = onOpenFile,
                        onShareFile = onShareFile,
                        onSendFiles = { filePickerLauncher.launch(arrayOf("*/*")) },
                        onRefresh = onRefreshDiscovery
                    )
                }
                NavTab.TRANSFERS -> {
                    TransfersTabContent(
                        activeTransfers = activeTransfers,
                        completedTransfers = completedTransfers,
                        onCancelTransfer = onCancelTransfer,
                        onSendNewFile = { filePickerLauncher.launch(arrayOf("*/*")) }
                    )
                }
                NavTab.SETTINGS -> {
                    SettingsTabContent(
                        pairedDevice = pairedDevice,
                        onUnpair = onUnpair,
                        onShowSecurity = { showSecurityDialog = true },
                        onSendPing = onSendPing
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------
// Home Tab Screen Content (Matches Screenshot Layout)
// -------------------------------------------------------------

@Composable
fun HomeScreenContent(
    connectionState: ConnectionState,
    syncMode: ClipboardSyncMode,
    devices: List<DiscoveredDevice>,
    pairedDevice: TrustedDevice?,
    logs: List<String>,
    lastSyncedSnippet: String?,
    activeTransfers: List<TransferItem>,
    onModeSelected: (ClipboardSyncMode) -> Unit,
    onConnect: (DiscoveredDevice) -> Unit,
    onDisconnect: () -> Unit,
    onUnpair: () -> Unit,
    onSendPing: () -> Unit,
    onRefreshDiscovery: () -> Unit,
    onOpenFilePicker: () -> Unit,
    onOpenManualIP: () -> Unit,
    onNavigateFiles: () -> Unit,
    onNavigateTransfers: () -> Unit,
    onCancelTransfer: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { Spacer(modifier = Modifier.height(2.dp)) }

        // 1. Ready to Connect / Status Banner
        item {
            StatusBannerGradient(
                connectionState = connectionState,
                onScan = onRefreshDiscovery
            )
        }

        // 2. Paired Device Card (MacBook mockup, Disconnect, etc.)
        item {
            PairedMacDeviceCard(
                pairedDevice = pairedDevice,
                connectionState = connectionState,
                onDisconnect = onDisconnect,
                onUnpair = onUnpair
            )
        }

        // 3. Quick Action Cards (Row of 3: Clipboard Sync, Quick File Sharing, Device Pairing)
        item {
            QuickActionCardsRow(
                onClipboardClick = { /* Focus sync mode */ },
                onFileShareClick = onOpenFilePicker,
                onPairingClick = onRefreshDiscovery
            )
        }

        // 4. Clipboard Sync Mode Card (3 Horizontal Options)
        item {
            ClipboardSyncModeSelectorCard(
                currentMode = syncMode,
                onModeSelected = onModeSelected
            )
        }

        // 5. Quick File Sharing Card with Dropzone & Active Transfers
        item {
            QuickFileSharingCard(
                activeTransfers = activeTransfers,
                onDropzoneClick = onOpenFilePicker,
                onOpenFiles = onNavigateFiles,
                onCancelTransfer = onCancelTransfer
            )
        }

        // 6. Nearby Devices Card (Wi-Fi Discovery + Manual IP Button)
        item {
            NearbyDevicesCard(
                devices = devices,
                pairedDevice = pairedDevice,
                onConnect = onConnect,
                onRefresh = onRefreshDiscovery,
                onManualIP = onOpenManualIP
            )
        }

        // 7. Activity Log (Collapsible / Compact)
        item {
            ActivityLogCard(logs = logs)
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

// -------------------------------------------------------------
// Component 1: Status Banner Gradient
// -------------------------------------------------------------

@Composable
fun StatusBannerGradient(
    connectionState: ConnectionState,
    onScan: () -> Unit
) {
    val isConnected = connectionState is ConnectionState.Connected || connectionState is ConnectionState.Authenticated
    val isPairing = connectionState is ConnectionState.Pairing

    val titleText = when {
        isConnected -> "Connected & Synced"
        isPairing -> "Pairing in Progress"
        connectionState is ConnectionState.Connecting -> "Connecting..."
        else -> "Ready to Connect"
    }

    val subtitleText = when {
        isConnected -> "Background service active • Bidirectional sync online"
        isPairing -> "Enter matching PIN to authenticate"
        connectionState is ConnectionState.Connecting -> "Establishing secure socket..."
        else -> "Background service active • Wi-Fi discovery enabled"
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.horizontalGradient(
                    colors = listOf(Color(0xFF09292F), Color(0xFF0D234A))
                )
            )
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Glowing Green Dot
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(if (isConnected) Color(0x3300E676) else Color(0x3334D399)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (isConnected) Color(0xFF00E676) else Color(0xFF34D399))
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    titleText,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = Color(0xFF00E676)
                )
                Text(
                    subtitleText,
                    fontSize = 11.sp,
                    color = Color(0xFF94A3B8)
                )
            }

            // Scan Pill Button
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(Color(0xFF1D4ED8), Color(0xFF2563EB))
                        )
                    )
                    .clickable { onScan() }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("((•))", fontSize = 12.sp, color = Color.White)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "Scan",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------
// Component 2: Paired Mac Device Card
// -------------------------------------------------------------

@Composable
fun PairedMacDeviceCard(
    pairedDevice: TrustedDevice?,
    connectionState: ConnectionState,
    onDisconnect: () -> Unit,
    onUnpair: () -> Unit
) {
    val isConnected = connectionState is ConnectionState.Connected || connectionState is ConnectionState.Authenticated
    val deviceName = pairedDevice?.deviceName ?: "Rahul's MacBook"

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF101726)),
        border = BorderStroke(1.dp, Color(0xFF1E293B)),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // MacBook Vector Graphic
                MacBookGraphic(modifier = Modifier.size(width = 64.dp, height = 44.dp))

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Paired Device",
                            fontSize = 12.sp,
                            color = Color(0xFF94A3B8)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isConnected) Color(0xFF064E3B) else Color(0xFF1F2937))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                if (isConnected) "Connected" else "Saved",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isConnected) Color(0xFF34D399) else Color(0xFF9CA3AF)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            deviceName,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("✏️", fontSize = 12.sp)
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        " macOS Sonoma",
                        fontSize = 12.sp,
                        color = Color(0xFF94A3B8)
                    )
                    Text(
                        if (isConnected) "Last seen: Just now" else "Last seen: Recently",
                        fontSize = 11.sp,
                        color = Color(0xFF64748B)
                    )
                }

                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.height(68.dp)
                ) {
                    Text("⋮", fontSize = 18.sp, color = Color(0xFF94A3B8))

                    // Disconnect Button
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF361013))
                            .border(1.dp, Color(0xFF7F1D1D), RoundedCornerShape(16.dp))
                            .clickable {
                                if (isConnected) onDisconnect() else onUnpair()
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("⟲", fontSize = 12.sp, color = Color(0xFFF87171))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                if (isConnected) "Disconnect" else "Unpair",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFF87171)
                            )
                        }
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------
// Component 3: Quick Action Cards Row (3 Cards)
// -------------------------------------------------------------

@Composable
fun QuickActionCardsRow(
    onClipboardClick: () -> Unit,
    onFileShareClick: () -> Unit,
    onPairingClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Card 1: Clipboard Sync
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF0F1B30))
                .border(1.dp, Color(0xFF1E2E4A), RoundedCornerShape(14.dp))
                .clickable { onClipboardClick() }
                .padding(12.dp)
        ) {
            Column {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1E3A8A)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("📋", fontSize = 18.sp)
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Clipboard Sync",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("›", fontSize = 16.sp, color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Sync text, links, images across devices",
                    fontSize = 10.sp,
                    color = Color(0xFF64748B),
                    lineHeight = 14.sp
                )
            }
        }

        // Card 2: Quick File Sharing
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF0D251E))
                .border(1.dp, Color(0xFF164E3B), RoundedCornerShape(14.dp))
                .clickable { onFileShareClick() }
                .padding(12.dp)
        ) {
            Column {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF065F46)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("📁", fontSize = 18.sp)
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Quick File Sharing",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("↗", fontSize = 14.sp, color = Color(0xFF34D399), fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Send & receive files instantly",
                    fontSize = 10.sp,
                    color = Color(0xFF64748B),
                    lineHeight = 14.sp
                )
            }
        }

        // Card 3: Device Pairing
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF221433))
                .border(1.dp, Color(0xFF3B1E54), RoundedCornerShape(14.dp))
                .clickable { onPairingClick() }
                .padding(12.dp)
        ) {
            Column {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF581C87)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("📱", fontSize = 18.sp)
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Device Pairing",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("›", fontSize = 16.sp, color = Color(0xFFA855F7), fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Find and pair devices nearby",
                    fontSize = 10.sp,
                    color = Color(0xFF64748B),
                    lineHeight = 14.sp
                )
            }
        }
    }
}

// -------------------------------------------------------------
// Component 4: Clipboard Sync Mode Selector Card (3 Columns)
// -------------------------------------------------------------

@Composable
fun ClipboardSyncModeSelectorCard(
    currentMode: ClipboardSyncMode,
    onModeSelected: (ClipboardSyncMode) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF101726)),
        border = BorderStroke(1.dp, Color(0xFF1E293B)),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("📋", fontSize = 18.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Clipboard Sync Mode",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Color.White
                    )
                    Text(
                        "Choose how clipboard updates sync between devices",
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8)
                    )
                }
                Text("?", fontSize = 16.sp, color = Color(0xFF64748B))
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 3 Horizontal Option Cards
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Option 1: Android -> Mac
                ModeOptionColumn(
                    modifier = Modifier.weight(1f),
                    title = "Android → Mac",
                    desc = "Copy on Android updates Mac",
                    isSelected = currentMode == ClipboardSyncMode.ANDROID_TO_MAC,
                    badge = null,
                    onClick = { onModeSelected(ClipboardSyncMode.ANDROID_TO_MAC) }
                )

                // Option 2: Mac -> Android
                ModeOptionColumn(
                    modifier = Modifier.weight(1f),
                    title = "Mac → Android",
                    desc = "Copy on Mac updates Android",
                    isSelected = currentMode == ClipboardSyncMode.MAC_TO_ANDROID,
                    badge = null,
                    onClick = { onModeSelected(ClipboardSyncMode.MAC_TO_ANDROID) }
                )

                // Option 3: Two-Way Sync
                ModeOptionColumn(
                    modifier = Modifier.weight(1f),
                    title = "Two-Way Sync",
                    desc = "Copy on either device updates the other",
                    isSelected = currentMode == ClipboardSyncMode.TWO_WAY,
                    badge = "ACTIVE",
                    onClick = { onModeSelected(ClipboardSyncMode.TWO_WAY) }
                )
            }
        }
    }
}

@Composable
fun ModeOptionColumn(
    modifier: Modifier = Modifier,
    title: String,
    desc: String,
    isSelected: Boolean,
    badge: String?,
    onClick: () -> Unit
) {
    val bgColor = if (isSelected) Color(0xFF0F261C) else Color(0xFF131B2A)
    val borderColor = if (isSelected) Color(0xFF22C55E) else Color(0xFF1E293B)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .border(if (isSelected) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(10.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Radio indicator
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .border(1.5.dp, if (isSelected) Color(0xFF22C55E) else Color(0xFF64748B), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF22C55E))
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                Text(
                    title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (badge != null && isSelected) {
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF064E3B))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text(
                        badge,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF34D399)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                desc,
                fontSize = 10.sp,
                color = if (isSelected) Color(0xFF86EFAC) else Color(0xFF64748B),
                lineHeight = 13.sp
            )
        }
    }
}

// -------------------------------------------------------------
// Component 5: Quick File Sharing Card with Dropzone
// -------------------------------------------------------------

@Composable
fun QuickFileSharingCard(
    activeTransfers: List<TransferItem>,
    onDropzoneClick: () -> Unit,
    onOpenFiles: () -> Unit,
    onCancelTransfer: (String) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF101726)),
        border = BorderStroke(1.dp, Color(0xFF1E293B)),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF3A2508)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("📁", fontSize = 18.sp)
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Quick File Sharing",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Color.White
                    )
                    Text(
                        "Send photos, PDFs, documents, and more between devices",
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8)
                    )
                }
                // Open > Pill Button
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF1D4ED8))
                        .clickable { onOpenFiles() }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Open", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Spacer(modifier = Modifier.width(2.dp))
                        Text("›", fontSize = 14.sp, color = Color.White)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Dashed Border Dropzone Area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF0B111D))
                    .dashedBorder(
                        strokeWidth = 1.dp,
                        color = Color(0xFF25344D),
                        cornerRadius = 12.dp
                    )
                    .clickable { onDropzoneClick() }
                    .padding(vertical = 20.dp, horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // Cloud Upload Icon
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(Color(0xFF2563EB), Color(0xFF7C3AED))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("☁️", fontSize = 24.sp)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        "Tap to select files or drag & drop",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = Color.White
                    )

                    Spacer(modifier = Modifier.height(3.dp))

                    Text(
                        "Photos • PDFs • Documents • Downloads • Any file",
                        fontSize = 11.sp,
                        color = Color(0xFF64748B)
                    )
                }
            }

            // Active Transfers Progress Bar
            if (activeTransfers.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    activeTransfers.forEach { item ->
                        ActiveTransferItemView(
                            item = item,
                            onCancel = { onCancelTransfer(item.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ActiveTransferItemView(
    item: TransferItem,
    onCancel: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF141F33))
            .border(1.dp, Color(0xFF1E3A8A), RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (item.isOutgoing) "⬆️" else "⬇️",
                    fontSize = 16.sp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        item.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${formatFileSize(item.bytesTransferred)} / ${formatFileSize(item.size)} • ${formatSpeed(item.speedBytesPerSec)}",
                        fontSize = 10.sp,
                        color = Color(0xFF94A3B8)
                    )
                }
                Text(
                    "${(item.progress * 100).toInt()}%",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = Color(0xFF38BDF8)
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier.size(24.dp)
                ) {
                    Text("✕", fontSize = 12.sp, color = Color(0xFFEF4444))
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            LinearProgressIndicator(
                progress = item.progress,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = Color(0xFF388AF6),
                trackColor = Color(0xFF1E293B)
            )
        }
    }
}

// -------------------------------------------------------------
// Component 6: Nearby Devices Card
// -------------------------------------------------------------

@Composable
fun NearbyDevicesCard(
    devices: List<DiscoveredDevice>,
    pairedDevice: TrustedDevice?,
    onConnect: (DiscoveredDevice) -> Unit,
    onRefresh: () -> Unit,
    onManualIP: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF101726)),
        border = BorderStroke(1.dp, Color(0xFF1E293B)),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("📡", fontSize = 18.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Nearby Devices",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = Color.White
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    "⟲",
                    fontSize = 16.sp,
                    color = Color(0xFF38BDF8),
                    modifier = Modifier.clickable { onRefresh() }
                )
                Spacer(modifier = Modifier.weight(1f))

                // Manual IP Fallback Pill Button
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF1A2333))
                        .border(1.dp, Color(0xFF2B3A52), RoundedCornerShape(16.dp))
                        .clickable { onManualIP() }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🔗", fontSize = 11.sp)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "Manual IP",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }
                }
            }

            Text(
                "Automatically discover devices on the same Wi-Fi",
                fontSize = 11.sp,
                color = Color(0xFF64748B)
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (devices.isEmpty()) {
                // Show default / illustrative Rahul's Android or searching state
                DiscoveredDeviceRow(
                    name = "Rahul's Android",
                    subtext = "Android 15 • Last seen: Just now",
                    isPaired = false,
                    anotherDevicePaired = pairedDevice != null,
                    onAction = { onRefresh() }
                )
            } else {
                devices.forEach { device ->
                    val isPaired = pairedDevice != null &&
                            (device.deviceId == pairedDevice.deviceId || device.name == pairedDevice.deviceName)
                    val anotherPaired = pairedDevice != null && !isPaired

                    DiscoveredDeviceRow(
                        name = device.name,
                        subtext = "${device.host}:${device.port} • Auto-discovered",
                        isPaired = isPaired,
                        anotherDevicePaired = anotherPaired,
                        onAction = { onConnect(device) }
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
        }
    }
}

@Composable
fun DiscoveredDeviceRow(
    name: String,
    subtext: String,
    isPaired: Boolean,
    anotherDevicePaired: Boolean,
    onAction: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0C121E))
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Phone graphic
            PhoneGraphic(modifier = Modifier.size(width = 24.dp, height = 38.dp))

            Spacer(modifier = Modifier.width(10.dp))

            // Glowing indicator dot
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF00E676))
            )

            Spacer(modifier = Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = Color.White
                )
                Text(
                    subtext,
                    fontSize = 11.sp,
                    color = Color(0xFF64748B)
                )
            }

            // Pair / Connect Pill Button
            val buttonColor = if (isPaired) Color(0xFF1D4ED8) else Color(0xFF4F46E5)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(buttonColor)
                    .clickable { onAction() }
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🔗", fontSize = 11.sp)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        if (isPaired) "Connect" else "Pair",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------
// Component 7: Compact Activity Log
// -------------------------------------------------------------

@Composable
fun ActivityLogCard(logs: List<String>) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0B101A)),
        border = BorderStroke(1.dp, Color(0xFF1E293B)),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
            ) {
                Text("⚡", fontSize = 14.sp)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    "Activity Log",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = Color(0xFF94A3B8)
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    if (expanded) "Hide ▲" else "View ▼",
                    fontSize = 11.sp,
                    color = Color(0xFF388AF6)
                )
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF05070D))
                        .padding(8.dp)
                ) {
                    if (logs.isEmpty()) {
                        Text("No events logged yet.", color = Color.Gray, fontSize = 11.sp)
                    } else {
                        LazyColumn(reverseLayout = true, modifier = Modifier.fillMaxSize()) {
                            items(logs) { log ->
                                Text(
                                    log,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    color = Color(0xFF94A3B8),
                                    lineHeight = 14.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------
// Bottom Navigation Bar
// -------------------------------------------------------------

@Composable
fun DexoLinkBottomNavBar(
    selectedTab: NavTab,
    activeTransfersCount: Int,
    onTabSelected: (NavTab) -> Unit
) {
    Surface(
        color = Color(0xFF090E1A),
        border = BorderStroke(1.dp, Color(0xFF1E293B)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp, horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavBarItem(
                title = "Home",
                icon = "🏠",
                isSelected = selectedTab == NavTab.HOME,
                badge = null,
                onClick = { onTabSelected(NavTab.HOME) }
            )
            NavBarItem(
                title = "Files",
                icon = "📁",
                isSelected = selectedTab == NavTab.FILES,
                badge = null,
                onClick = { onTabSelected(NavTab.FILES) }
            )
            NavBarItem(
                title = "Transfers",
                icon = "⇅",
                isSelected = selectedTab == NavTab.TRANSFERS,
                badge = if (activeTransfersCount > 0) activeTransfersCount.toString() else null,
                onClick = { onTabSelected(NavTab.TRANSFERS) }
            )
            NavBarItem(
                title = "Settings",
                icon = "⚙️",
                isSelected = selectedTab == NavTab.SETTINGS,
                badge = null,
                onClick = { onTabSelected(NavTab.SETTINGS) }
            )
        }
    }
}

@Composable
fun NavBarItem(
    title: String,
    icon: String,
    isSelected: Boolean,
    badge: String?,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) Color(0xFF1E3A8A) else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box {
                Text(
                    icon,
                    fontSize = 18.sp,
                    color = if (isSelected) Color(0xFF38BDF8) else Color(0xFF64748B)
                )
                if (badge != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 6.dp, y = (-2).dp)
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFEF4444)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(badge, fontSize = 8.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                title,
                fontSize = 11.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) Color.White else Color(0xFF64748B)
            )
        }
    }
}

// -------------------------------------------------------------
// Tab Content: Files (Received Files in Downloads/DexoLink)
// -------------------------------------------------------------

@Composable
fun FilesTabContent(
    receivedFiles: List<File>,
    onOpenFile: (File) -> Unit,
    onShareFile: (File) -> Unit,
    onSendFiles: () -> Unit,
    onRefresh: () -> Unit
) {
    var selectedFilter by remember { mutableStateOf("All") }
    val filters = listOf("All", "Photos", "PDFs", "Docs", "Downloads")

    val filteredFiles = remember(receivedFiles, selectedFilter) {
        when (selectedFilter) {
            "Photos" -> receivedFiles.filter { it.extension.lowercase() in listOf("jpg", "jpeg", "png", "gif", "webp") }
            "PDFs" -> receivedFiles.filter { it.extension.lowercase() == "pdf" }
            "Docs" -> receivedFiles.filter { it.extension.lowercase() in listOf("doc", "docx", "txt", "pages", "rtf") }
            else -> receivedFiles
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "Received Files",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = Color.White
            )
            Spacer(modifier = Modifier.weight(1f))
            Button(
                onClick = onSendFiles,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Send File ⬆️", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Filter chips
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(filters) { f ->
                val isSel = f == selectedFilter
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (isSel) Color(0xFF2563EB) else Color(0xFF131A29))
                        .border(1.dp, if (isSel) Color(0xFF388AF6) else Color(0xFF1E293B), RoundedCornerShape(16.dp))
                        .clickable { selectedFilter = f }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        f,
                        fontSize = 12.sp,
                        color = if (isSel) Color.White else Color(0xFF94A3B8),
                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (filteredFiles.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("📥", fontSize = 40.sp)
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        "No files received yet",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Files sent from your paired Mac appear here automatically in Downloads/DexoLink.",
                        fontSize = 12.sp,
                        color = Color(0xFF64748B),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredFiles) { file ->
                    ReceivedFileCard(
                        file = file,
                        onOpen = { onOpenFile(file) },
                        onShare = { onShareFile(file) }
                    )
                }
            }
        }
    }
}

@Composable
fun ReceivedFileCard(
    file: File,
    onOpen: () -> Unit,
    onShare: () -> Unit
) {
    val icon = when (file.extension.lowercase()) {
        "jpg", "jpeg", "png", "webp" -> "🖼️"
        "pdf" -> "📄"
        "zip", "tar", "gz" -> "📦"
        "mp4", "mov" -> "🎬"
        "mp3", "m4a" -> "🎵"
        else -> "📑"
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF101726))
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(12.dp))
            .clickable { onOpen() }
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(icon, fontSize = 24.sp)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    file.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    formatFileSize(file.length()),
                    fontSize = 11.sp,
                    color = Color(0xFF64748B)
                )
            }
            IconButton(onClick = onShare) {
                Text("🔗", fontSize = 16.sp)
            }
            Button(
                onClick = onOpen,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1D4ED8)),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text("Open", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// -------------------------------------------------------------
// Tab Content: Transfers (Active & Completed Transfers)
// -------------------------------------------------------------

@Composable
fun TransfersTabContent(
    activeTransfers: List<TransferItem>,
    completedTransfers: List<TransferItem>,
    onCancelTransfer: (String) -> Unit,
    onSendNewFile: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "Transfer Manager",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = Color.White
            )
            Spacer(modifier = Modifier.weight(1f))
            Button(
                onClick = onSendNewFile,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Upload ⬆️", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Active Transfers Section
            if (activeTransfers.isNotEmpty()) {
                item {
                    Text(
                        "In Progress (${activeTransfers.size})",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = Color(0xFF38BDF8)
                    )
                }
                items(activeTransfers) { item ->
                    ActiveTransferItemView(item = item, onCancel = { onCancelTransfer(item.id) })
                }
            }

            // Completed Transfers Section
            item {
                Text(
                    "Transfer History",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = Color(0xFF94A3B8)
                )
            }

            if (completedTransfers.isEmpty()) {
                item {
                    Text(
                        "No transfers yet. Send or receive files using Quick File Sharing.",
                        fontSize = 12.sp,
                        color = Color(0xFF64748B)
                    )
                }
            } else {
                items(completedTransfers) { item ->
                    CompletedTransferItemView(item = item)
                }
            }
        }
    }
}

@Composable
fun CompletedTransferItemView(item: TransferItem) {
    val isSuccess = item.state == TransferStatus.COMPLETED
    val statusColor = if (isSuccess) Color(0xFF34D399) else Color(0xFFF87171)
    val statusText = when (item.state) {
        TransferStatus.COMPLETED -> "Completed"
        TransferStatus.FAILED -> "Failed: ${item.errorMessage ?: "Network error"}"
        TransferStatus.CANCELLED -> "Cancelled"
        else -> item.state.name
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF0F1728))
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (item.isOutgoing) "⬆️" else "⬇️", fontSize = 18.sp)
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.name,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${formatFileSize(item.size)} • $statusText",
                    fontSize = 11.sp,
                    color = statusColor
                )
            }
            if (isSuccess) {
                Text("✓", fontSize = 16.sp, color = Color(0xFF34D399), fontWeight = FontWeight.Bold)
            } else {
                Text("!", fontSize = 16.sp, color = Color(0xFFF87171), fontWeight = FontWeight.Bold)
            }
        }
    }
}

// -------------------------------------------------------------
// Tab Content: Settings & Security Overview
// -------------------------------------------------------------

@Composable
fun SettingsTabContent(
    pairedDevice: TrustedDevice?,
    onUnpair: () -> Unit,
    onShowSecurity: () -> Unit,
    onSendPing: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { Spacer(modifier = Modifier.height(4.dp)) }

        item {
            Text(
                "Preferences & Security",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = Color.White
            )
        }

        // 1. Device Pairing Policy Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF101726)),
                border = BorderStroke(1.dp, Color(0xFF1E293B)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        "🔒 Strict 1-Device Policy",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Only 1 device may be paired at a time to prevent unauthorized access. All other incoming pairing and transfer requests are rejected.",
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    if (pairedDevice != null) {
                        Text(
                            "Paired with: ${pairedDevice.deviceName}",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = Color(0xFF34D399)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = onUnpair,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Unpair Device", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Text("No device currently paired.", fontSize = 12.sp, color = Color.Gray)
                    }
                }
            }
        }

        // 2. Security Overview Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF101726)),
                border = BorderStroke(1.dp, Color(0xFF1E293B)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        "🛡️ Cryptographic & Transfer Security",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("• Android Keystore: AES-256-GCM hardware key isolation", fontSize = 11.sp, color = Color(0xFFCBD5E1))
                    Text("• TLS framing: Length-prefixed framing channel", fontSize = 11.sp, color = Color(0xFFCBD5E1))
                    Text("• Replay Protection: Unique UUIDs & SHA-256 checksums", fontSize = 11.sp, color = Color(0xFFCBD5E1))
                    Text("• Sandboxed Destination: Path traversal defense (../ blocked)", fontSize = 11.sp, color = Color(0xFFCBD5E1))
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = onShowSecurity,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("View Security Details", fontSize = 12.sp)
                    }
                }
            }
        }

        // 3. Connection Diagnostics
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF101726)),
                border = BorderStroke(1.dp, Color(0xFF1E293B)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        "⚡ Diagnostics",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onSendPing,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E3A8A)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Test Ping Latency 🏓", fontSize = 12.sp)
                    }
                }
            }
        }

        // 4. App Info
        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "DexoLink v2.0.0 (Build 3)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = Color(0xFF64748B)
                )
                Text(
                    "Secure bidirectional sync & quick file sharing",
                    fontSize = 11.sp,
                    color = Color(0xFF475569)
                )
            }
        }

        item { Spacer(modifier = Modifier.height(20.dp)) }
    }
}

// -------------------------------------------------------------
// Format Helpers
// -------------------------------------------------------------

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    val group = digitGroups.coerceIn(0, units.size - 1)
    return String.format(Locale.US, "%.1f %s", bytes / Math.pow(1024.0, group.toDouble()), units[group])
}

fun formatSpeed(bytesPerSec: Double): String {
    if (bytesPerSec <= 0) return "0 KB/s"
    val mb = bytesPerSec / (1024.0 * 1024.0)
    return if (mb >= 1.0) {
        String.format(Locale.US, "%.1f MB/s", mb)
    } else {
        String.format(Locale.US, "%.0f KB/s", bytesPerSec / 1024.0)
    }
}

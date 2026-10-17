package com.macdroid.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.macdroid.MacDroidApplication
import com.macdroid.core.ClipboardSyncMode
import com.macdroid.networking.*
import com.macdroid.service.MacDroidService
import com.macdroid.storage.TrustStore
import com.macdroid.storage.TrustedDevice
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity(), ConnectionEventListener, DiscoveryListener {

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

        setContent {
            MacDroidAppTheme {
                MainScreen(
                    connectionState = connectionState.value,
                    syncMode = currentSyncMode.value,
                    devices = discoveredDevices,
                    pairedDevice = pairedDevice.value,
                    logs = logs,
                    lastSyncedSnippet = lastSyncedSnippet.value,
                    onModeSelected = { mode ->
                        currentSyncMode.value = mode
                        connectionManager.setClipboardSyncMode(mode)
                    },
                    onConnect = { device ->
                        connectionManager.connect(device.host, device.port)
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
                    }
                )
            }
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
        // Do NOT shut down connection or service!
        // Just detach UI listeners so background service continues maintaining socket & clipboard!
        connectionManager.removeListener(this)
        discoveryService.removeListener(this)
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

    private fun log(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logs.add(0, "[$time] $message")
        if (logs.size > 80) logs.removeAt(logs.lastIndex)
    }
}

@Composable
fun MacDroidAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF388AF6),
            onPrimary = Color.White,
            secondary = Color(0xFF64D2FF),
            background = Color(0xFF121417),
            surface = Color(0xFF1E2228),
            onSurface = Color(0xFFF0F3F8),
            surfaceVariant = Color(0xFF282E38)
        ),
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    connectionState: ConnectionState,
    syncMode: ClipboardSyncMode,
    devices: List<DiscoveredDevice>,
    pairedDevice: TrustedDevice?,
    logs: List<String>,
    lastSyncedSnippet: String?,
    onModeSelected: (ClipboardSyncMode) -> Unit,
    onConnect: (DiscoveredDevice) -> Unit,
    onDisconnect: () -> Unit,
    onUnpair: () -> Unit,
    onConfirmPairing: () -> Unit,
    onRejectPairing: () -> Unit,
    onSendPing: () -> Unit,
    onRefreshDiscovery: () -> Unit
) {
    if (connectionState is ConnectionState.Pairing) {
        AlertDialog(
            onDismissRequest = onRejectPairing,
            title = {
                Text("🔐 Device Pairing Request", fontWeight = FontWeight.Bold, fontSize = 18.sp)
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
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "Verify that this 6-digit PIN matches the code shown on your Mac:",
                        fontSize = 13.sp,
                        color = Color.Gray,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF2A1B4E))
                            .border(1.dp, Color(0xFFBF5AF2), RoundedCornerShape(12.dp))
                            .padding(horizontal = 24.dp, vertical = 12.dp)
                    ) {
                        Text(
                            connectionState.pin,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 32.sp,
                            letterSpacing = 6.sp,
                            color = Color(0xFFE2B6FF)
                        )
                    }
                    Text(
                        "Strict 1-Device Policy: Only 1 device may be paired at a time. All other pairing requests will be rejected until unpaired.",
                        fontSize = 11.sp,
                        color = Color(0xFFE2B6FF),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = onConfirmPairing,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9D4EDD))
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "DexoLink",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF1B3A28))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                "⚡ 1-Device Policy Active",
                                fontSize = 11.sp,
                                color = Color(0xFF34C759)
                            )
                        }
                    }
                },
                actions = {
                    TextButton(onClick = onRefreshDiscovery) {
                        Text("Scan", color = MaterialTheme.colorScheme.primary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Connection Status Card
            item {
                ConnectionStatusBanner(connectionState = connectionState)
            }

            // 2. Paired Device Card (Strict 1-Device Policy)
            item {
                PairedDeviceCard(
                    pairedDevice = pairedDevice,
                    isConnected = connectionState is ConnectionState.Connected || connectionState is ConnectionState.Authenticated,
                    onUnpair = onUnpair
                )
            }

            // 3. Active Session Card if Connected/Authenticated
            if (connectionState is ConnectionState.Connected || connectionState is ConnectionState.Authenticated) {
                item {
                    val peerName = when (connectionState) {
                        is ConnectionState.Authenticated -> connectionState.peerName
                        is ConnectionState.Connected -> connectionState.peerName
                        else -> "MacBook"
                    }
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF1F3B66)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("💻", fontSize = 22.sp)
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        peerName,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 17.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        "Connected via Local Wi-Fi (IP-Free)",
                                        fontSize = 12.sp,
                                        color = Color(0xFF86EFAC)
                                    )
                                }
                                Button(
                                    onClick = onDisconnect,
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text("Disconnect", fontSize = 12.sp)
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))
                            Divider(color = Color(0xFF2C323D))
                            Spacer(modifier = Modifier.height(10.dp))

                            Text(
                                "Live Sync Status",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                color = Color.Gray
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            // Automatic Sync Status Banner
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF10281E))
                                    .border(1.dp, Color(0xFF1B5E3C), RoundedCornerShape(12.dp))
                                    .padding(12.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF1B5E3C)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("🔄", fontSize = 18.sp)
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            syncMode.title,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            color = Color(0xFF4ADE80)
                                        )
                                        val snippet = lastSyncedSnippet
                                        if (!snippet.isNullOrBlank()) {
                                            Text(
                                                "Last synced: \"${snippet.take(28)}...\"",
                                                fontSize = 11.sp,
                                                color = Color(0xFFB4F2CB)
                                            )
                                        } else {
                                            Text(
                                                syncMode.description,
                                                fontSize = 11.sp,
                                                color = Color(0xFFB4F2CB)
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            OutlinedButton(
                                onClick = onSendPing,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("⚡ Test Ping Latency", fontSize = 13.sp)
                            }
                        }
                    }
                }
            }

            // 4. Clipboard Sync Mode Selector Card
            item {
                ClipboardSyncModeCard(
                    currentMode = syncMode,
                    onModeSelected = onModeSelected
                )
            }

            // 5. Discovered Devices (Automatic Wi-Fi Discovery - Zero IP)
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        Text(
                            "Nearby Devices",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "Auto-discovered on Wi-Fi • Zero IP address entry",
                            fontSize = 11.sp,
                            color = Color.Gray
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = onRefreshDiscovery) {
                        Text("Scan", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            if (devices.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("📡", fontSize = 28.sp)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Searching for devices on local Wi-Fi...",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Make sure DexoLink is open on your Mac or nearby device. Both devices discover each other automatically without any IP address entry.",
                                fontSize = 12.sp,
                                color = Color.Gray,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                items(devices) { device ->
                    val isCurrentPaired = pairedDevice != null &&
                            (device.deviceId == pairedDevice.deviceId || device.name == pairedDevice.deviceName)
                    val anotherDevicePaired = pairedDevice != null && !isCurrentPaired

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isCurrentPaired) Color(0xFF16251C) else MaterialTheme.colorScheme.surface
                        ),
                        border = if (isCurrentPaired) BorderStroke(1.dp, Color(0xFF285435)) else null,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (device.name.contains("iPhone", ignoreCase = true) || device.name.contains("iPad", ignoreCase = true) || device.name.contains("Android", ignoreCase = true)) "📱" else "💻",
                                fontSize = 24.sp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        device.name,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 15.sp
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    if (isCurrentPaired) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(Color(0xFF1B4E2E))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                "★ Paired",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF34C759)
                                            )
                                        }
                                    } else if (anotherDevicePaired) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(Color(0xFF3E2D18))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                "Blocked",
                                                fontSize = 10.sp,
                                                color = Color(0xFFFFB340)
                                            )
                                        }
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(Color(0xFF282E38))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                "Available",
                                                fontSize = 10.sp,
                                                color = Color.LightGray
                                            )
                                        }
                                    }
                                }
                                Text(
                                    if (isCurrentPaired) "Paired & trusted device"
                                    else if (anotherDevicePaired) "1-device policy: unpair current device first"
                                    else "Local Wi-Fi discovery • IP-free",
                                    fontSize = 12.sp,
                                    color = Color.Gray
                                )
                            }
                            if (isCurrentPaired) {
                                Button(
                                    onClick = { onConnect(device) },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                ) {
                                    Text("Connect", fontSize = 12.sp)
                                }
                            } else if (anotherDevicePaired) {
                                OutlinedButton(
                                    onClick = { /* Blocked by 1-device policy */ },
                                    enabled = false,
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Unpair First", fontSize = 11.sp)
                                }
                            } else {
                                Button(
                                    onClick = { onConnect(device) },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9D4EDD))
                                ) {
                                    Text("Pair Device", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            // 6. Live Activity Log
            item {
                Text(
                    "Activity Log",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = Color.Gray
                )
            }

            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF0D0F12))
                        .padding(10.dp)
                ) {
                    LazyColumn(
                        reverseLayout = true,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(logs) { log ->
                            Text(
                                log,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = Color(0xFF8B949E),
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}

@Composable
fun PairedDeviceCard(
    pairedDevice: TrustedDevice?,
    isConnected: Boolean,
    onUnpair: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (pairedDevice != null) Color(0xFF16231A) else MaterialTheme.colorScheme.surface
        ),
        border = if (pairedDevice != null) BorderStroke(1.dp, Color(0xFF285435)) else null,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (pairedDevice != null) Color(0xFF1B4E2E) else Color(0xFF282E38)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(if (pairedDevice != null) "🔒" else "🔓", fontSize = 18.sp)
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (pairedDevice != null) "Paired Device (1-Device Policy)" else "No Device Paired",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = if (pairedDevice != null) Color(0xFF4ADE80) else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        if (pairedDevice != null) "Strictly 1 device • Auto-connects on Wi-Fi" else "Strict 1-device policy enforced",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                }
                if (pairedDevice != null) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isConnected) Color(0xFF1B5E3C) else Color(0xFF2B3A42))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            if (isConnected) "CONNECTED" else "SAVED",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isConnected) Color(0xFF4ADE80) else Color(0xFF64D2FF)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (pairedDevice != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF0F1A13))
                        .padding(12.dp)
                ) {
                    Text("💻", fontSize = 22.sp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            pairedDevice.deviceName,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            color = Color.White
                        )
                        Text(
                            "Trusted device • Auto-sync active",
                            fontSize = 11.sp,
                            color = Color(0xFF86EFAC)
                        )
                    }
                    Button(
                        onClick = onUnpair,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("Unpair", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "To pair another Mac or device, you must explicitly unpair this one first.",
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            } else {
                Text(
                    "DexoLink strictly allows only one paired device at a time. Tap 'Pair Device' below on any discovered device to send a pairing request.",
                    fontSize = 12.sp,
                    color = Color.LightGray,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

@Composable
fun ConnectionStatusBanner(connectionState: ConnectionState) {
    val (bgColor, dotColor, text, desc) = when (connectionState) {
        is ConnectionState.Authenticated -> Quadruple(
            Color(0xFF0F3820),
            Color(0xFF34C759),
            "Connected & Synced (Background Active)",
            "Maintaining persistent link with ${connectionState.peerName}"
        )
        is ConnectionState.Pairing -> Quadruple(
            Color(0xFF2A1B4E),
            Color(0xFFBF5AF2),
            "Pairing in Progress (Code: ${connectionState.pin})",
            "Verify matching 6-digit code on ${connectionState.peerName}"
        )
        is ConnectionState.Connected -> Quadruple(
            Color(0xFF142C48),
            Color(0xFF388AF6),
            "Connected (Handshaking...)",
            "Connected to ${connectionState.peerAddress}"
        )
        is ConnectionState.Connecting -> Quadruple(
            Color(0xFF3D3212),
            Color(0xFFFFB340),
            "Connecting / Auto-Reconnecting...",
            connectionState.target
        )
        is ConnectionState.Failed -> {
            val isRejection = connectionState.reason.contains("pair", ignoreCase = true) ||
                    connectionState.reason.contains("policy", ignoreCase = true)
            Quadruple(
                Color(0xFF421515),
                Color(0xFFFF453A),
                if (isRejection) "Pairing Blocked / Rejected" else "Connection Failed (Retrying in background)",
                connectionState.reason
            )
        }
        is ConnectionState.Disconnected -> Quadruple(
            Color(0xFF1C2027),
            Color(0xFF6E7681),
            "Idle & Listening in Background",
            "Foreground service active • Ready to connect"
        )
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = bgColor),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = Color.White
                )
                Text(
                    desc,
                    fontSize = 12.sp,
                    color = Color(0xFFD0D7DE)
                )
            }
        }
    }
}

@Composable
fun ClipboardSyncModeCard(
    currentMode: ClipboardSyncMode,
    onModeSelected: (ClipboardSyncMode) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1F3B66)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("⚙️", fontSize = 18.sp)
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        "Clipboard Sync Mode",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "Automatic sync direction between Android & Mac",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            val modes = listOf(
                ClipboardSyncMode.ANDROID_TO_MAC,
                ClipboardSyncMode.MAC_TO_ANDROID,
                ClipboardSyncMode.TWO_WAY
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                modes.forEach { mode ->
                    val isSelected = mode == currentMode
                    val bgColor = if (isSelected) Color(0xFF142B1E) else Color(0xFF252B35)
                    val borderColor = if (isSelected) Color(0xFF4ADE80) else Color(0xFF323945)

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(bgColor)
                            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                            .clickable { onModeSelected(mode) }
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { onModeSelected(mode) },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = Color(0xFF4ADE80),
                                    unselectedColor = Color.Gray
                                )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        mode.title,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = if (isSelected) Color(0xFF4ADE80) else MaterialTheme.colorScheme.onSurface
                                    )
                                    if (isSelected) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(Color(0xFF1B5E3C))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                "ACTIVE",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF4ADE80)
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    mode.description,
                                    fontSize = 11.sp,
                                    color = if (isSelected) Color(0xFFB4F2CB) else Color.Gray,
                                    lineHeight = 15.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

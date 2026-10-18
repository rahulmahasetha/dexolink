package com.macdroid.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.foundation.*
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.macdroid.MacDroidApplication
import com.macdroid.core.FileStreamer
import com.macdroid.core.TransferEventListener
import com.macdroid.core.TransferItem
import com.macdroid.core.TransferStatus
import com.macdroid.networking.ConnectionEventListener
import com.macdroid.networking.ConnectionManager
import com.macdroid.networking.ConnectionState
import com.macdroid.networking.ProtocolMessage
import com.macdroid.service.MacDroidService
import com.macdroid.storage.TrustStore
import com.macdroid.storage.TrustedDevice
import java.io.File
import java.util.*

class ShareActivity : ComponentActivity(), ConnectionEventListener, TransferEventListener {

    private val connectionManager: ConnectionManager
        get() = MacDroidApplication.connectionManager

    private val connectionState = mutableStateOf<ConnectionState>(ConnectionState.Disconnected)
    private val pairedDevice = mutableStateOf<TrustedDevice?>(null)

    // Items extracted from Intent
    private val sharedUris = mutableStateListOf<Uri>()
    private val sharedText = mutableStateOf<String?>(null)

    // Sending & Progress State
    private val isTransferring = mutableStateOf(false)
    private val transferProgress = mutableStateOf(0f)
    private val transferSpeed = mutableStateOf(0.0)
    private val activeTransferName = mutableStateOf("")
    private val currentTransferId = mutableStateOf<String?>(null)
    private val transferComplete = mutableStateOf(false)
    private val transferError = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ensure background service is running
        try {
            MacDroidService.start(this)
        } catch (e: Exception) {
            // Ignore
        }

        pairedDevice.value = TrustStore.getInstance(this).getPairedDevice()
        connectionState.value = connectionManager.state

        connectionManager.addListener(this)
        connectionManager.transferManager.addListener(this)

        parseSharedIntent(intent)

        setContent {
            DexoLinkTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f))
                        .clickable {
                            if (!isTransferring.value) finish()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        shape = RoundedCornerShape(24.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
                        modifier = Modifier
                            .fillMaxWidth(0.92f)
                            .padding(vertical = 24.dp)
                            .clickable(enabled = false) {}
                    ) {
                        ShareDialogContent(
                            connectionState = connectionState.value,
                            pairedDevice = pairedDevice.value,
                            sharedText = sharedText.value,
                            sharedUris = sharedUris,
                            isTransferring = isTransferring.value,
                            progress = transferProgress.value,
                            speed = transferSpeed.value,
                            transferName = activeTransferName.value,
                            isComplete = transferComplete.value,
                            errorMessage = transferError.value,
                            onSend = { startSending() },
                            onCancelTransfer = { cancelActiveTransfer() },
                            onReconnect = { reconnectToPaired() },
                            onOpenMainApp = {
                                startActivity(Intent(this@ShareActivity, MainActivity::class.java))
                                finish()
                            },
                            onDismiss = { finish() }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { parseSharedIntent(it) }
    }

    override fun onDestroy() {
        connectionManager.removeListener(this)
        connectionManager.transferManager.removeListener(this)
        super.onDestroy()
    }

    private fun parseSharedIntent(intent: Intent) {
        sharedUris.clear()
        sharedText.value = null

        val action = intent.action
        val type = intent.type ?: ""

        when (action) {
            Intent.ACTION_SEND -> {
                if (type.startsWith("text/") && intent.hasExtra(Intent.EXTRA_TEXT)) {
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                        ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                    sharedText.value = text
                }

                // Check for single file / media URI
                val streamUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                } ?: intent.data

                if (streamUri != null && !sharedUris.contains(streamUri)) {
                    sharedUris.add(streamUri)
                } else if (intent.clipData != null && intent.clipData!!.itemCount > 0) {
                    val uri = intent.clipData!!.getItemAt(0).uri
                    if (uri != null && !sharedUris.contains(uri)) {
                        sharedUris.add(uri)
                    }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                uris?.forEach { uri ->
                    if (uri != null && !sharedUris.contains(uri)) {
                        sharedUris.add(uri)
                    }
                }
                // Fallback to ClipData
                intent.clipData?.let { clip ->
                    for (i in 0 until clip.itemCount) {
                        val uri = clip.getItemAt(i).uri
                        if (uri != null && !sharedUris.contains(uri)) {
                            sharedUris.add(uri)
                        }
                    }
                }
            }
        }
    }

    private fun startSending() {
        if (!connectionManager.isConnected()) {
            Toast.makeText(this, "Device is disconnected. Reconnecting...", Toast.LENGTH_SHORT).show()
            reconnectToPaired()
            return
        }

        isTransferring.value = true
        transferComplete.value = false
        transferError.value = null

        // 1. If plain text or URL is shared without files
        val text = sharedText.value
        if (!text.isNullOrBlank() && sharedUris.isEmpty()) {
            connectionManager.sendClipboard(text, force = true)
            transferComplete.value = true
            isTransferring.value = false
            Toast.makeText(this, "Text sent to ${pairedDevice.value?.deviceName ?: "Mac"}! 📋", Toast.LENGTH_SHORT).show()
            window.decorView.postDelayed({ finish() }, 1200)
            return
        }

        // 2. If files are shared
        if (sharedUris.isNotEmpty()) {
            val streamer = FileStreamer(this)
            val firstUri = sharedUris.first()
            activeTransferName.value = streamer.getFileName(firstUri)
            transferProgress.value = 0.05f

            sharedUris.forEach { uri ->
                connectionManager.sendFile(uri)
            }
        }
    }

    private fun cancelActiveTransfer() {
        val id = currentTransferId.value
        if (id != null) {
            connectionManager.cancelTransfer(id)
        } else {
            connectionManager.transferManager.cancelAll()
        }
        isTransferring.value = false
        transferError.value = "Transfer cancelled by user"
    }

    private fun reconnectToPaired() {
        val paired = pairedDevice.value ?: return
        val host = connectionManager.lastConnectedHost ?: ""
        val port = if (connectionManager.lastConnectedPort > 0) connectionManager.lastConnectedPort else 52840
        if (host.isNotBlank()) {
            connectionManager.connect(host, port)
            Toast.makeText(this, "Connecting to ${paired.deviceName}...", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Searching for ${paired.deviceName} on Wi-Fi...", Toast.LENGTH_SHORT).show()
            MacDroidApplication.discoveryService.startBrowsing()
        }
    }

    // --- ConnectionEventListener ---
    override fun onStateChanged(state: ConnectionState) {
        runOnUiThread {
            connectionState.value = state
        }
    }

    override fun onMessageReceived(message: ProtocolMessage) {}
    override fun onLog(log: String) {}

    // --- TransferEventListener ---
    override fun onTransferUpdated(item: TransferItem) {
        if (!item.isOutgoing) return
        runOnUiThread {
            currentTransferId.value = item.id
            activeTransferName.value = item.name
            transferProgress.value = item.progress
            transferSpeed.value = item.speedBytesPerSec
        }
    }

    override fun onTransferCompleted(item: TransferItem) {
        if (!item.isOutgoing) return
        runOnUiThread {
            transferComplete.value = true
            isTransferring.value = false
            transferProgress.value = 1.0f
            Toast.makeText(this, "Sent ${item.name} to ${pairedDevice.value?.deviceName ?: "Mac"}! 🎉", Toast.LENGTH_SHORT).show()
            window.decorView.postDelayed({ finish() }, 1400)
        }
    }

    override fun onTransferFailed(item: TransferItem, error: String) {
        if (!item.isOutgoing) return
        runOnUiThread {
            isTransferring.value = false
            transferError.value = error
        }
    }
}

// -------------------------------------------------------------
// Share Dialog Content Composable
// -------------------------------------------------------------

@Composable
fun ShareDialogContent(
    connectionState: ConnectionState,
    pairedDevice: TrustedDevice?,
    sharedText: String?,
    sharedUris: List<Uri>,
    isTransferring: Boolean,
    progress: Float,
    speed: Double,
    transferName: String,
    isComplete: Boolean,
    errorMessage: String?,
    onSend: () -> Unit,
    onCancelTransfer: () -> Unit,
    onReconnect: () -> Unit,
    onOpenMainApp: () -> Unit,
    onDismiss: () -> Unit
) {
    val isConnected = connectionState is ConnectionState.Connected || connectionState is ConnectionState.Authenticated
    val isConnecting = connectionState is ConnectionState.Connecting

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 1. Header with DexoLink icon & title
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            DexoLinkChainIcon(modifier = Modifier.size(28.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "DexoLink Share",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = Color(0xFF0F172A)
                )
                Text(
                    "Direct local transfer • Encrypted",
                    fontSize = 11.sp,
                    color = Color(0xFF64748B)
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(28.dp)
            ) {
                Text("✕", fontSize = 16.sp, color = Color(0xFF94A3B8), fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 2. Case: No device paired yet
        if (pairedDevice == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFFFEF2F2))
                    .border(1.dp, Color(0xFFFECACA), RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🔒", fontSize = 28.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "No Paired Mac Found",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Color(0xFF991B1B)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "DexoLink enforces a strict 1-device pairing policy. Please open DexoLink to securely pair your Mac before sharing.",
                        fontSize = 12.sp,
                        color = Color(0xFF7F1D1D),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Button(
                        onClick = onOpenMainApp,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Open DexoLink to Pair", fontWeight = FontWeight.Bold)
                    }
                }
            }
            return
        }

        // 3. Paired Device Destination Card
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
            border = BorderStroke(1.dp, if (isConnected) Color(0xFFBAE6FD) else Color(0xFFE2E8F0)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(12.dp)
            ) {
                MacBookGraphic(modifier = Modifier.size(width = 44.dp, height = 30.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        pairedDevice.deviceName,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = Color(0xFF0F172A),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        isConnected -> Color(0xFF10B981)
                                        isConnecting -> Color(0xFFF59E0B)
                                        else -> Color(0xFFEF4444)
                                    }
                                )
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            when {
                                isConnected -> "Connected • Ready to receive"
                                isConnecting -> "Connecting to Mac..."
                                else -> "Disconnected • Tap Reconnect"
                            },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = when {
                                isConnected -> Color(0xFF059669)
                                isConnecting -> Color(0xFFD97706)
                                else -> Color(0xFFDC2626)
                            }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 4. Content Preview
        if (sharedUris.isNotEmpty()) {
            val context = androidx.compose.ui.platform.LocalContext.current
            val streamer = remember { FileStreamer(context) }
            val totalSize = remember(sharedUris) {
                sharedUris.sumOf { streamer.getFileMetadata(it).first }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFFF1F5F9))
                    .padding(12.dp)
            ) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "${sharedUris.size} item${if (sharedUris.size > 1) "s" else ""} to send",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = Color(0xFF1E293B)
                        )
                        if (totalSize > 0) {
                            Text(
                                formatFileSize(totalSize),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF64748B)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 120.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(sharedUris.take(6)) { uri ->
                            val name = streamer.getFileName(uri)
                            val (size, _) = streamer.getFileMetadata(uri)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("📄", fontSize = 14.sp)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    name,
                                    fontSize = 12.sp,
                                    color = Color(0xFF334155),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                if (size > 0) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        formatFileSize(size),
                                        fontSize = 10.sp,
                                        color = Color(0xFF94A3B8)
                                    )
                                }
                            }
                        }
                        if (sharedUris.size > 6) {
                            item {
                                Text(
                                    "+ ${sharedUris.size - 6} more files",
                                    fontSize = 11.sp,
                                    color = Color(0xFF0066FF),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        } else if (!sharedText.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFFEFF6FF))
                    .border(1.dp, Color(0xFFBFDBFE), RoundedCornerShape(14.dp))
                    .padding(12.dp)
            ) {
                Column {
                    Text(
                        "Text snippet / URL:",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                        color = Color(0xFF0066FF)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        sharedText,
                        fontSize = 12.sp,
                        color = Color(0xFF1E293B),
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        } else {
            Text(
                "No supported content found in share intent.",
                fontSize = 12.sp,
                color = Color(0xFF64748B)
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 5. Active Transfer Progress Bar or Error message
        if (isTransferring) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Sending: $transferName",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF0F172A),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "${(progress * 100).toInt()}%",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0066FF)
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                LinearProgressIndicator(
                    progress = progress.coerceIn(0f, 1f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = Color(0xFF0066FF),
                    trackColor = Color(0xFFE2E8F0)
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        if (speed > 1000) "${formatSpeed(speed)}" else "Streaming...",
                        fontSize = 11.sp,
                        color = Color(0xFF64748B)
                    )
                    TextButton(
                        onClick = onCancelTransfer,
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.height(24.dp)
                    ) {
                        Text("Cancel", fontSize = 11.sp, color = Color(0xFFDC2626), fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else if (isComplete) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFDCFCE7))
                    .padding(10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Transfer Complete! 🎉",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = Color(0xFF16A34A)
                )
            }
        } else if (!errorMessage.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFFEE2E2))
                    .padding(10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    errorMessage,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    color = Color(0xFFDC2626),
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 6. Action Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text("Cancel", color = Color(0xFF64748B), fontWeight = FontWeight.SemiBold)
            }

            if (isConnected) {
                Button(
                    onClick = onSend,
                    enabled = !isTransferring && !isComplete && (sharedUris.isNotEmpty() || !sharedText.isNullOrBlank()),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0066FF)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1.3f)
                ) {
                    Text("Send to Mac 🚀", fontWeight = FontWeight.Bold, color = Color.White)
                }
            } else {
                Button(
                    onClick = onReconnect,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0066FF)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1.3f)
                ) {
                    Text(if (isConnecting) "Connecting..." else "⚡ Reconnect", fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    }
}

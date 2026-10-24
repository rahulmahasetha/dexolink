package com.macdroid.ui
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.core.content.FileProvider
import java.io.FileOutputStream

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.pdf.PdfRenderer
import android.media.ThumbnailUtils
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.provider.MediaStore
import android.util.LruCache
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.macdroid.core.ClipboardHistoryItem
import com.macdroid.core.ClipboardSyncMode
import com.macdroid.core.TransferItem
import com.macdroid.core.TransferStatus
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import com.macdroid.networking.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.macdroid.storage.TrustedDevice
import com.macdroid.storage.TrustStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// UI Design System & Theme

@Composable
fun DexoLinkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF0066FF),
            onPrimary = Color.White,
            secondary = Color(0xFF0284C7),
            background = Color(0xFFF8FAFC),
            surface = Color.White,
            onSurface = Color(0xFF0F172A),
            surfaceVariant = Color(0xFFF1F5F9),
            outline = Color(0xFFE2E8F0)
        ),
        content = content
    )
}

// Modifier extension for dashed border (used for dropzone/containers)
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

// Vector Graphics

@Composable
fun DexoLinkChainIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        val gradient = Brush.linearGradient(
            colors = listOf(Color(0xFF00C4FF), Color(0xFF0066FF)),
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
        val screenW = w * 0.86f
        val screenH = h * 0.70f
        val screenLeft = (w - screenW) / 2
        val screenTop = h * 0.06f

        // Screen Bezel (Dark Graphite Aluminum)
        drawRoundRect(
            color = Color(0xFF1E242B),
            topLeft = Offset(screenLeft, screenTop),
            size = Size(screenW, screenH),
            cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
        )
        // Screen Wallpaper (Vibrant Purple to Magenta to Orange gradient)
        drawRoundRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color(0xFF7C3AED), Color(0xFFEC4899), Color(0xFFF97316))
            ),
            topLeft = Offset(screenLeft + 2.5.dp.toPx(), screenTop + 2.5.dp.toPx()),
            size = Size(screenW - 5.dp.toPx(), screenH - 5.dp.toPx()),
            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
        )
        // Camera notch
        drawRoundRect(
            color = Color(0xFF0F172A),
            topLeft = Offset(w / 2 - 5.dp.toPx(), screenTop),
            size = Size(10.dp.toPx(), 2.5.dp.toPx()),
            cornerRadius = CornerRadius(1.dp.toPx(), 1.dp.toPx())
        )
        // Laptop Base (Silver Aluminum)
        val baseH = h * 0.16f
        val baseTop = screenTop + screenH
        drawRoundRect(
            color = Color(0xFF94A3B8),
            topLeft = Offset(w * 0.03f, baseTop),
            size = Size(w * 0.94f, baseH),
            cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
        )
        // Thumb opening notch
        drawRoundRect(
            color = Color(0xFF64748B),
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
        // Phone Body (Dark Graphite with rounded corners)
        drawRoundRect(
            color = Color(0xFF0F172A),
            topLeft = Offset(0f, 0f),
            size = Size(w, h),
            cornerRadius = CornerRadius(7.dp.toPx(), 7.dp.toPx())
        )
        // Wallpaper (Vibrant Cyan to Purple to Indigo gradient)
        drawRoundRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color(0xFF06B6D4), Color(0xFF6366F1), Color(0xFF9333EA))
            ),
            topLeft = Offset(2.dp.toPx(), 2.dp.toPx()),
            size = Size(w - 4.dp.toPx(), h - 4.dp.toPx()),
            cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx())
        )
        // Camera hole punch
        drawCircle(
            color = Color(0xFF0F172A),
            radius = 1.8.dp.toPx(),
            center = Offset(w / 2, 4.dp.toPx())
        )
    }
}

@Composable
fun FileCategoryIcon(
    extension: String,
    modifier: Modifier = Modifier
) {
    val ext = extension.lowercase(Locale.ROOT)
    val (bgColor, iconVector, iconTint) = when {
        ext in listOf("jpg", "jpeg", "png", "webp", "gif", "heic", "bmp") -> {
            Triple(Color(0xFFDCFCE7), DexoIcons.CategoryImage, Color(0xFF16A34A))
        }
        ext == "pdf" -> {
            Triple(Color(0xFFFEE2E2), DexoIcons.CategoryPdf, Color(0xFFDC2626))
        }
        ext in listOf("doc", "docx", "txt", "rtf", "pages", "xls", "xlsx", "csv", "ppt", "pptx") -> {
            Triple(Color(0xFFDBEAFE), DexoIcons.CategoryDoc, Color(0xFF2563EB))
        }
        ext in listOf("mp4", "mkv", "mov", "avi", "webm", "3gp") -> {
            Triple(Color(0xFFF3E8FF), DexoIcons.CategoryVideo, Color(0xFF9333EA))
        }
        ext in listOf("zip", "rar", "7z", "tar", "gz") -> {
            Triple(Color(0xFFFEF3C7), DexoIcons.CategoryZip, Color(0xFFD97706))
        }
        ext in listOf("mp3", "m4a", "wav", "flac", "aac") -> {
            Triple(Color(0xFFFCE7F3), DexoIcons.CategoryAudio, Color(0xFFDB2777))
        }
        else -> {
            Triple(Color(0xFFF1F5F9), DexoIcons.CategoryFile, Color(0xFF64748B))
        }
    }

    Box(
        modifier = modifier
            .size(46.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = iconVector,
            contentDescription = ext,
            modifier = Modifier.size(22.dp),
            tint = iconTint
        )
    }
}

// File Thumbnail Cache & Loader

object FileThumbnailCache {
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = (maxMemory / 8).coerceAtLeast(4 * 1024)

    val memoryCache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }
}

suspend fun loadFileThumbnail(file: File): Bitmap? = withContext(Dispatchers.IO) {
    if (!file.exists() || !file.canRead()) return@withContext null

    val cacheKey = "${file.absolutePath}_${file.lastModified()}"
    FileThumbnailCache.memoryCache.get(cacheKey)?.let { return@withContext it }

    val ext = file.extension.lowercase(Locale.ROOT)
    val bitmap: Bitmap? = try {
        when {
            ext == "pdf" -> {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)?.use { pfd ->
                    PdfRenderer(pfd).use { renderer ->
                        if (renderer.pageCount > 0) {
                            renderer.openPage(0).use { page ->
                                val targetWidth = 140
                                val targetHeight = ((page.height.toFloat() / page.width.toFloat()) * targetWidth)
                                    .toInt().coerceIn(100, 200)
                                val bmp = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                                val canvas = Canvas(bmp)
                                canvas.drawColor(android.graphics.Color.WHITE)
                                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                bmp
                            }
                        } else null
                    }
                }
            }
            ext in listOf("jpg", "jpeg", "png", "webp", "bmp") -> {
                val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, boundsOptions)
                val targetSize = 140
                var sampleSize = 1
                if (boundsOptions.outHeight > targetSize || boundsOptions.outWidth > targetSize) {
                    val halfHeight = boundsOptions.outHeight / 2
                    val halfWidth = boundsOptions.outWidth / 2
                    while (halfHeight / sampleSize >= targetSize && halfWidth / sampleSize >= targetSize) {
                        sampleSize *= 2
                    }
                }
                val decodeOptions = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
            }
            ext in listOf("mp4", "mkv", "mov", "3gp", "webm") -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ThumbnailUtils.createVideoThumbnail(file, android.util.Size(140, 140), null)
                } else {
                    @Suppress("DEPRECATION")
                    ThumbnailUtils.createVideoThumbnail(file.absolutePath, MediaStore.Images.Thumbnails.MINI_KIND)
                }
            }
            else -> null
        }
    } catch (e: Exception) {
        null
    }

    if (bitmap != null) {
        FileThumbnailCache.memoryCache.put(cacheKey, bitmap)
    }
    bitmap
}

@Composable
fun FileThumbnailOrIcon(
    file: File,
    modifier: Modifier = Modifier
) {
    val ext = file.extension.lowercase(Locale.ROOT)
    val isSupportedPreview = ext in listOf("pdf", "jpg", "jpeg", "png", "webp", "bmp", "mp4", "mkv", "mov", "3gp", "webm")

    var thumbnailBitmap by remember(file.absolutePath, file.lastModified()) {
        val cacheKey = "${file.absolutePath}_${file.lastModified()}"
        mutableStateOf(FileThumbnailCache.memoryCache.get(cacheKey))
    }

    LaunchedEffect(file.absolutePath, file.lastModified()) {
        if (isSupportedPreview && thumbnailBitmap == null) {
            thumbnailBitmap = loadFileThumbnail(file)
        }
    }

    val currentBmp = thumbnailBitmap
    if (currentBmp != null) {
        Box(
            modifier = modifier
                .size(46.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(12.dp))
                .background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = currentBmp.asImageBitmap(),
                contentDescription = file.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            if (ext == "pdf") {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .background(
                            Color(0xFFDC2626),
                            RoundedCornerShape(topStart = 4.dp, bottomEnd = 11.dp)
                        )
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text(
                        "PDF",
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
    } else {
        FileCategoryIcon(extension = file.extension, modifier = modifier)
    }
}

// Main Screen Layout

// QR Code Bitmap Generator
fun generateQRCodeBitmap(content: String, sizePx: Int = 512): Bitmap {
    return try {
        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
            put(EncodeHintType.CHARACTER_SET, "UTF-8")
            put(EncodeHintType.MARGIN, 1)
        }
        val matrix = MultiFormatWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            sizePx,
            sizePx,
            hints
        )
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)
        val darkColor = android.graphics.Color.parseColor("#0C1322")
        val lightColor = android.graphics.Color.WHITE
        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                pixels[offset + x] = if (matrix.get(x, y)) darkColor else lightColor
            }
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        bitmap
    } catch (e: Exception) {
        Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.WHITE)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreenRoot(
    connectionState: ConnectionState,
    syncMode: ClipboardSyncMode,
    devices: List<DiscoveredDevice>,
    pairedDevice: TrustedDevice?,
    localDeviceName: String,
    localPort: Int,
    lastPingLatency: Long?,
    logs: List<String>,
    lastSyncedSnippet: String?,
    activeTransfers: List<TransferItem>,
    completedTransfers: List<TransferItem>,
    receivedFiles: List<File>,
    onModeSelected: (ClipboardSyncMode) -> Unit,
    onConnect: (DiscoveredDevice) -> Unit,
    onConnectPaired: () -> Unit,
    onConnectManual: (String, Int) -> Unit,
    onDisconnect: () -> Unit,
    onUnpair: () -> Unit,
    onConfirmPairing: () -> Unit,
    onRejectPairing: () -> Unit,
    onSendPing: () -> Unit,
    onCheckClipboard: () -> Unit,
    onRefreshDiscovery: () -> Unit,
    isScanning: Boolean,
    onStartDiscovery: () -> Unit,
    onStopDiscovery: () -> Unit,
    onSendFiles: (List<Uri>) -> Unit,
    onCancelTransfer: (String) -> Unit,
    onOpenFile: (File) -> Unit,
    onShareFile: (File) -> Unit,
    onDeleteFile: (File) -> Unit,
    onResumeTransfer: (String) -> Unit,
    onClearLogs: () -> Unit,
    onUpdateDeviceName: (String) -> Unit,
    onRenamePaired: (String) -> Unit,
    onRequestPairing: () -> Unit,
    onClearTransfers: () -> Unit,
    clipboardHistory: List<ClipboardHistoryItem> = emptyList(),
    onPauseTransfer: (String) -> Unit = {},
    onResumeOngoingTransfer: (String) -> Unit = {},
    onDeleteTransfer: (String) -> Unit = {},
    onDeleteTransfers: (Set<String>) -> Unit = {},
    onDeleteClipboardItem: (String) -> Unit = {},
    onDeleteClipboardItems: (Set<String>) -> Unit = {},
    onClearClipboardHistory: () -> Unit = {},
    isEnhancedClipboardEnabled: Boolean = false,
    onToggleEnhancedClipboard: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val backStack = remember { mutableStateListOf(NavTab.HOME) }
    val currentTab = backStack.lastOrNull() ?: NavTab.HOME
    var showManualIPDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf("local") } // "local" or "paired"
    var showSecurityDialog by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showPairDevicesSheet by remember { mutableStateOf(false) }
    var showSyncModeDialog by remember { mutableStateOf(false) }
    var showNotificationsDialog by remember { mutableStateOf(false) }
    var showClipboardHistorySheet by remember { mutableStateOf(false) }
    var fileToDelete by remember { mutableStateOf<File?>(null) }

    fun navigateToTab(tab: NavTab) {
        if (backStack.lastOrNull() != tab) {
            backStack.add(tab)
        }
    }

    fun navigateBack(): Boolean {
        if (fileToDelete != null) {
            fileToDelete = null
            return true
        }
        if (showOverflowMenu) {
            showOverflowMenu = false
            return true
        }
        if (showNotificationsDialog) {
            showNotificationsDialog = false
            return true
        }
        if (showPairDevicesSheet) {
            showPairDevicesSheet = false
            return true
        }
        if (showManualIPDialog) {
            showManualIPDialog = false
            return true
        }
        if (showRenameDialog) {
            showRenameDialog = false
            return true
        }
        if (showSecurityDialog) {
            showSecurityDialog = false
            return true
        }
        if (showSyncModeDialog) {
            showSyncModeDialog = false
            return true
        }
        if (showClipboardHistorySheet) {
            showClipboardHistorySheet = false
            return true
        }

        if (backStack.size > 1) {
            backStack.removeAt(backStack.lastIndex)
            return true
        }
        return false
    }

    val anyModalActive = fileToDelete != null || showOverflowMenu || showNotificationsDialog ||
            showPairDevicesSheet || showManualIPDialog || showRenameDialog ||
            showSecurityDialog || showSyncModeDialog || showClipboardHistorySheet

    BackHandler(enabled = backStack.size > 1 || anyModalActive) {
        navigateBack()
    }

    // File picker launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri>? ->
        uris?.let { if (it.isNotEmpty()) onSendFiles(it) }
    }

    // Modal: Device Pairing Request (PIN verification dialog)
    if (connectionState is ConnectionState.Pairing) {
        AlertDialog(
            onDismissRequest = onRejectPairing,
            containerColor = Color.White,
            shape = RoundedCornerShape(20.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = DexoIcons.Lock,
                        contentDescription = "Pairing",
                        modifier = Modifier.size(22.dp),
                        tint = Color(0xFF0066FF)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Device Pairing Request",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = Color(0xFF0F172A)
                    )
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
                        color = Color(0xFF0F172A)
                    )
                    Text(
                        "Verify that this 6-digit PIN matches the code displayed on your Mac:",
                        fontSize = 13.sp,
                        color = Color(0xFF64748B),
                        textAlign = TextAlign.Center
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFFF3E8FF))
                            .border(1.5.dp, Color(0xFFC084FC), RoundedCornerShape(14.dp))
                            .padding(horizontal = 28.dp, vertical = 12.dp)
                    ) {
                        Text(
                            connectionState.pin,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 32.sp,
                            letterSpacing = 6.sp,
                            color = Color(0xFF7E22CE)
                        )
                    }
                    Text(
                        "Strict 1-Device Policy: Only 1 authorized device can be paired. Other requests will be automatically rejected.",
                        fontSize = 11.sp,
                        color = Color(0xFF7E22CE),
                        textAlign = TextAlign.Center
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = onConfirmPairing,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Accept & Pair", fontWeight = FontWeight.Bold, color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = onRejectPairing) {
                    Text("Reject", color = Color(0xFFEF4444), fontWeight = FontWeight.SemiBold)
                }
            }
        )
    }

    // Modal: Pair Devices Nearby Sheet / Dialog
    if (showPairDevicesSheet) {
        AlertDialog(
            onDismissRequest = { showPairDevicesSheet = false },
            containerColor = Color.White,
            shape = RoundedCornerShape(20.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = DexoIcons.Link,
                        contentDescription = "Pair Devices",
                        modifier = Modifier.size(20.dp),
                        tint = Color(0xFF0066FF)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Pair Devices Nearby",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = Color(0xFF0F172A)
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "Select your Mac or Android device found on local Wi-Fi to establish a secure pairing link:",
                        fontSize = 12.sp,
                        color = Color(0xFF64748B)
                    )

                    if (devices.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFFF8FAFC))
                                .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(12.dp))
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.5.dp,
                                    color = Color(0xFF0066FF)
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    "Scanning Wi-Fi for devices...",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp,
                                    color = Color(0xFF0F172A)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "Make sure DexoLink is open on your Mac and connected to the same Wi-Fi network.",
                                    fontSize = 11.sp,
                                    color = Color(0xFF64748B),
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    } else {
                        val settings = remember { com.macdroid.storage.DexoLinkSettings.getInstance(context) }
                        val multiPairedEnabled by settings.multiPairedDevices.collectAsState()
                        val trustStore = remember { TrustStore.getInstance(context) }
                        val isSingleDeviceBlocked = !multiPairedEnabled && trustStore.hasPairedDevice()

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(devices) { dev ->
                                val isBlocked = isSingleDeviceBlocked && !trustStore.isPairedWith(dev.deviceId ?: "", dev.name)
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(0xFFF8FAFC))
                                        .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(12.dp))
                                        .clickable {
                                            if (isBlocked) {
                                                Toast.makeText(
                                                    context,
                                                    "Single Device Mode active. Enable 'Multiple Paired Devices' in Settings to pair additional devices.",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            } else {
                                                onConnect(dev)
                                                showPairDevicesSheet = false
                                            }
                                        }
                                        .padding(10.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        MacBookGraphic(modifier = Modifier.size(width = 36.dp, height = 24.dp))
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    dev.name,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 13.sp,
                                                    color = if (isBlocked) Color(0xFF64748B) else Color(0xFF0F172A)
                                                )
                                                if (isBlocked) {
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Surface(
                                                        color = Color(0xFFFEE2E2),
                                                        shape = RoundedCornerShape(4.dp)
                                                    ) {
                                                        Text(
                                                            "Blocked",
                                                            fontSize = 9.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = Color(0xFFDC2626),
                                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                        )
                                                    }
                                                }
                                            }
                                            Text(
                                                if (isBlocked) "Single device mode active" else "${dev.host}:${dev.port}",
                                                fontSize = 11.sp,
                                                color = Color(0xFF64748B)
                                            )
                                        }
                                        Button(
                                            onClick = {
                                                if (isBlocked) {
                                                    Toast.makeText(
                                                        context,
                                                        "Single Device Mode active. Enable 'Multiple Paired Devices' in Settings to pair additional devices.",
                                                        Toast.LENGTH_LONG
                                                    ).show()
                                                } else {
                                                    onConnect(dev)
                                                    showPairDevicesSheet = false
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (isBlocked) Color(0xFFE2E8F0) else Color(0xFF0066FF),
                                                contentColor = if (isBlocked) Color(0xFF64748B) else Color.White
                                            ),
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                        ) {
                                            Text("Pair", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onRefreshDiscovery,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(DexoIcons.Sync, null, modifier = Modifier.size(13.dp), tint = Color(0xFF0066FF))
                                Spacer(modifier = Modifier.width(5.dp))
                                Text("Scan Again", fontSize = 11.sp, color = Color(0xFF0066FF))
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                showPairDevicesSheet = false
                                showManualIPDialog = true
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(DexoIcons.Wifi, null, modifier = Modifier.size(13.dp), tint = Color(0xFF0F172A))
                                Spacer(modifier = Modifier.width(5.dp))
                                Text("Direct IP", fontSize = 11.sp, color = Color(0xFF0F172A))
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showPairDevicesSheet = false }) {
                    Text("Close", color = Color(0xFF64748B))
                }
            }
        )
    }

    // Modal: Manual IP Entry Dialog
    if (showManualIPDialog) {
        var ipInput by remember { mutableStateOf("") }
        var portInput by remember { mutableStateOf("52840") }

        AlertDialog(
            onDismissRequest = { showManualIPDialog = false },
            containerColor = Color.White,
            shape = RoundedCornerShape(20.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = DexoIcons.Wifi,
                        contentDescription = "Manual IP",
                        modifier = Modifier.size(20.dp),
                        tint = Color(0xFF0066FF)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Connect Remote Device / Online IP",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = Color(0xFF0F172A)
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Connect directly to your computer or phone across local Wi-Fi, VPN, or over the Internet. Live streaming uses WebRTC with STUN direct traversal and TURN relay fallback.",
                        fontSize = 12.sp,
                        color = Color(0xFF64748B)
                    )
                    OutlinedTextField(
                        value = ipInput,
                        onValueChange = { ipInput = it },
                        label = { Text("Host or IP Address (e.g. 192.168.1.15, remote.net)") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color(0xFF0F172A),
                            unfocusedTextColor = Color(0xFF0F172A),
                            focusedBorderColor = Color(0xFF0066FF),
                            unfocusedBorderColor = Color(0xFFCBD5E1)
                        )
                    )
                    OutlinedTextField(
                        value = portInput,
                        onValueChange = { portInput = it },
                        label = { Text("Port (default: 52840)") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color(0xFF0F172A),
                            unfocusedTextColor = Color(0xFF0F172A),
                            focusedBorderColor = Color(0xFF0066FF),
                            unfocusedBorderColor = Color(0xFFCBD5E1)
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val port = portInput.toIntOrNull() ?: 52840
                        if (ipInput.isNotBlank()) {
                            onConnectManual(ipInput.trim(), port)
                            showManualIPDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0066FF)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Connect", fontWeight = FontWeight.Bold, color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualIPDialog = false }) {
                    Text("Cancel", color = Color(0xFF64748B))
                }
            }
        )
    }

    // Modal: Rename Device Dialog (Local Phone or Paired Mac)
    if (showRenameDialog) {
        val initialName = if (renameTarget == "paired") {
            pairedDevice?.deviceName ?: "MacBook"
        } else {
            localDeviceName
        }
        var nameInput by remember(showRenameDialog, renameTarget) { mutableStateOf(initialName) }

        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            containerColor = Color.White,
            shape = RoundedCornerShape(20.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = DexoIcons.Edit,
                        contentDescription = "Rename",
                        modifier = Modifier.size(20.dp),
                        tint = Color(0xFF0066FF)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (renameTarget == "paired") "Rename Paired Mac" else "Rename My Device",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = Color(0xFF0F172A)
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        if (renameTarget == "paired")
                            "Customize the display name for your paired Mac:"
                        else
                            "Set the name broadcasted to your Mac and local Wi-Fi devices:",
                        fontSize = 12.sp,
                        color = Color(0xFF64748B)
                    )
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("Device Name") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color(0xFF0F172A),
                            unfocusedTextColor = Color(0xFF0F172A),
                            focusedBorderColor = Color(0xFF0066FF),
                            unfocusedBorderColor = Color(0xFFCBD5E1)
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = nameInput.trim()
                        if (trimmed.isNotBlank()) {
                            if (renameTarget == "paired") {
                                onRenamePaired(trimmed)
                            } else {
                                onUpdateDeviceName(trimmed)
                            }
                            showRenameDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0066FF)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Save", fontWeight = FontWeight.Bold, color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Cancel", color = Color(0xFF64748B))
                }
            }
        )
    }

    // Modal: Sync Direction Dialog
    if (showSyncModeDialog) {
        AlertDialog(
            onDismissRequest = { showSyncModeDialog = false },
            containerColor = Color.White,
            shape = RoundedCornerShape(20.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = DexoIcons.Sync,
                        contentDescription = "Sync Direction",
                        modifier = Modifier.size(20.dp),
                        tint = Color(0xFF0066FF)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Sync Direction",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = Color(0xFF0F172A)
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Configure how clipboard data flows between your phone and Mac:",
                        fontSize = 12.sp,
                        color = Color(0xFF64748B)
                    )
                    listOf(
                        ClipboardSyncMode.TWO_WAY to ("Two-Way Sync" to "Copy on either device syncs across both"),
                        ClipboardSyncMode.ANDROID_TO_MAC to ("Android → Mac" to "Copy on Android updates Mac only"),
                        ClipboardSyncMode.MAC_TO_ANDROID to ("Mac → Android" to "Copy on Mac updates Android only")
                    ).forEach { (mode, pair) ->
                        val (title, desc) = pair
                        val isSelected = syncMode == mode
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) Color(0xFFEFF6FF) else Color(0xFFF8FAFC))
                                .border(
                                    1.dp,
                                    if (isSelected) Color(0xFF0066FF) else Color(0xFFE2E8F0),
                                    RoundedCornerShape(12.dp)
                                )
                                .clickable {
                                    onModeSelected(mode)
                                    showSyncModeDialog = false
                                }
                                .padding(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .border(
                                            1.5.dp,
                                            if (isSelected) Color(0xFF0066FF) else Color(0xFF94A3B8),
                                            CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSelected) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xFF0066FF))
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        title,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = Color(0xFF0F172A)
                                    )
                                    Text(
                                        desc,
                                        fontSize = 11.sp,
                                        color = Color(0xFF64748B)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showSyncModeDialog = false }) {
                    Text("Close", color = Color(0xFF64748B))
                }
            }
        )
    }

    // Modal: Security Overview Dialog
    if (showSecurityDialog) {
        AlertDialog(
            onDismissRequest = { showSecurityDialog = false },
            containerColor = Color.White,
            shape = RoundedCornerShape(20.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = DexoIcons.Shield,
                        contentDescription = "Security",
                        modifier = Modifier.size(20.dp),
                        tint = Color(0xFF0066FF)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Security & Defense-in-Depth",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = Color(0xFF0F172A)
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("• Hardware-backed Android Keystore: Pairing keys isolated using AES-256-GCM.", fontSize = 12.sp, color = Color(0xFF334155))
                    Text("• TLS & Framing: All socket payloads encrypted with replay protection and length framing.", fontSize = 12.sp, color = Color(0xFF334155))
                    Text("• Strict 1-Device Policy: Only 1 authorized device can be paired and transfer files.", fontSize = 12.sp, color = Color(0xFF334155))
                    Text("• Sandboxed File Storage: Path traversal prevention (../ filtered) & SHA-256 integrity verification.", fontSize = 12.sp, color = Color(0xFF334155))
                    Divider(color = Color(0xFFE2E8F0))
                    Text(
                        "Defense-in-Depth Notice: We adhere to strict security best practices. However, no wireless software system is completely unhackable. Always keep your devices updated and pair only in trusted networks.",
                        fontSize = 11.sp,
                        color = Color(0xFF64748B),
                        lineHeight = 15.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { showSecurityDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0066FF)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Close", fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    // Modal: Delete File Confirmation Dialog
    if (fileToDelete != null) {
        val target = fileToDelete!!
        AlertDialog(
            onDismissRequest = { fileToDelete = null },
            containerColor = Color.White,
            shape = RoundedCornerShape(20.dp),
            title = {
                Text(
                    "Delete File?",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = Color(0xFF0F172A)
                )
            },
            text = {
                Text(
                    "Are you sure you want to permanently delete \"${target.name}\" from your Downloads folder? This cannot be undone.",
                    color = Color(0xFF475569),
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteFile(target)
                        fileToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Delete", fontWeight = FontWeight.Bold, color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { fileToDelete = null }) {
                    Text("Cancel", color = Color(0xFF64748B))
                }
            }
        )
    }

    // Modal: Notifications & Activity Logs Dialog
    if (showNotificationsDialog) {
        AlertDialog(
            onDismissRequest = { showNotificationsDialog = false },
            containerColor = Color.White,
            shape = RoundedCornerShape(20.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = DexoIcons.Bell,
                        contentDescription = "Notifications",
                        modifier = Modifier.size(20.dp),
                        tint = Color(0xFF0066FF)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Activity & Notifications",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = Color(0xFF0F172A)
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 350.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (!lastSyncedSnippet.isNullOrBlank()) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text("Last Synced Clipboard", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color(0xFF0066FF))
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    "\"${lastSyncedSnippet.take(60)}${if (lastSyncedSnippet.length > 60) "..." else ""}\"",
                                    fontSize = 12.sp,
                                    color = Color(0xFF1E293B)
                                )
                            }
                        }
                    }

                    Text("Recent Events:", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Color(0xFF64748B))

                    if (logs.isEmpty()) {
                        Text(
                            "No recent background alerts. Service is active and monitoring Wi-Fi & clipboard.",
                            fontSize = 12.sp,
                            color = Color(0xFF94A3B8)
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(logs.takeLast(20).reversed()) { logEntry ->
                                Text(
                                    "• $logEntry",
                                    fontSize = 11.sp,
                                    color = Color(0xFF334155),
                                    lineHeight = 15.sp
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onCheckClipboard() }) {
                        Text("Sync Clipboard", color = Color(0xFF0066FF), fontWeight = FontWeight.SemiBold)
                    }
                    if (logs.isNotEmpty()) {
                        TextButton(onClick = { onClearLogs() }) {
                            Text("Clear", color = Color(0xFFEF4444))
                        }
                    }
                    Button(
                        onClick = { showNotificationsDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0066FF)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("OK", color = Color.White)
                    }
                }
            }
        )
    }

    if (showClipboardHistorySheet) {
        ClipboardHistoryDialog(
            history = clipboardHistory,
            onDismiss = { showClipboardHistorySheet = false },
            onDeleteItem = onDeleteClipboardItem,
            onDeleteItems = onDeleteClipboardItems,
            onClearHistory = onClearClipboardHistory
        )
    }

    Scaffold(
        topBar = {
            if (currentTab != NavTab.QR_CONNECT) {
            Surface(
                color = Color.White,
                shadowElevation = 0.5.dp,
                border = BorderStroke(0.5.dp, Color(0xFFF1F5F9))
            ) {
                TopAppBar(
                    navigationIcon = {
                        if (backStack.size > 1 && currentTab != NavTab.HOME) {
                            IconButton(onClick = { navigateBack() }) {
                                Icon(
                                    imageVector = DexoIcons.ArrowBack,
                                    contentDescription = "Back",
                                    modifier = Modifier.size(22.dp),
                                    tint = Color(0xFF0F172A)
                                )
                            }
                        }
                    },
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            DexoLinkChainIcon(modifier = Modifier.size(34.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    "DexoLink",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 20.sp,
                                    color = Color(0xFF0F172A)
                                )
                                Text(
                                    "$localDeviceName · Visible",
                                    fontSize = 11.sp,
                                    color = Color(0xFF64748B),
                                    modifier = Modifier.clickable {
                                        renameTarget = "local"
                                        showRenameDialog = true
                                    }
                                )
                            }
                        }
                    },
                    actions = {
                        // Notification bell icon with red dot
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFF1F5F9))
                                .clickable { showNotificationsDialog = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = DexoIcons.Bell,
                                contentDescription = "Notifications",
                                modifier = Modifier.size(18.dp),
                                tint = Color(0xFF475569)
                            )
                            Box(
                                modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFEF4444))
                                .align(Alignment.TopEnd)
                                .offset(x = (-6).dp, y = 6.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))

                        // 3-dots overflow menu
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFF1F5F9)),
                            contentAlignment = Alignment.Center
                        ) {
                            IconButton(onClick = { showOverflowMenu = true }) {
                                DexoIcon(
                                    imageVector = DexoIcons.MoreVert,
                                    contentDescription = "More Options",
                                    tint = Color(0xFF334155),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            DropdownMenu(
                                expanded = showOverflowMenu,
                                onDismissRequest = { showOverflowMenu = false },
                                modifier = Modifier.background(Color.White)
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Clipboard History (${clipboardHistory.size})", color = Color(0xFF0F172A)) },
                                    onClick = {
                                        showOverflowMenu = false
                                        showClipboardHistorySheet = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Scan / Refresh Wi-Fi", color = Color(0xFF0F172A)) },
                                    onClick = {
                                        showOverflowMenu = false
                                        onRefreshDiscovery()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Manual IP Connection", color = Color(0xFF0F172A)) },
                                    onClick = {
                                        showOverflowMenu = false
                                        showManualIPDialog = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Pair Devices Nearby", color = Color(0xFF0066FF), fontWeight = FontWeight.Bold) },
                                    onClick = {
                                        showOverflowMenu = false
                                        showPairDevicesSheet = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Security Overview", color = Color(0xFF0F172A)) },
                                    onClick = {
                                        showOverflowMenu = false
                                        showSecurityDialog = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Rename My Device", color = Color(0xFF0F172A)) },
                                    onClick = {
                                        showOverflowMenu = false
                                        renameTarget = "local"
                                        showRenameDialog = true
                                    }
                                )
                                if (pairedDevice != null) {
                                    DropdownMenuItem(
                                        text = { Text("Rename Paired Mac", color = Color(0xFF0F172A)) },
                                        onClick = {
                                            showOverflowMenu = false
                                            renameTarget = "paired"
                                            showRenameDialog = true
                                        }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Clear Activity Log", color = Color(0xFF0F172A)) },
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

                        Spacer(modifier = Modifier.width(6.dp))
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.White
                    )
                )
            }
            }
        },
        bottomBar = {
            if (currentTab != NavTab.QR_CONNECT) {
                DexoLinkBottomNavBar(
                    selectedTab = currentTab,
                    activeTransfersCount = activeTransfers.size,
                    onTabSelected = { navigateToTab(it) },
                    onCenterPlusClick = { filePickerLauncher.launch(arrayOf("*/*")) }
                )
            }
        },
        containerColor = Color(0xFFF8FAFC)
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (currentTab) {
                NavTab.HOME -> {
                    HomeScreenContent(
                        connectionState = connectionState,
                        syncMode = syncMode,
                        devices = devices,
                        pairedDevice = pairedDevice,
                        localDeviceName = localDeviceName,
                        lastPingLatency = lastPingLatency,
                        activeTransfers = activeTransfers,
                        onConnectPaired = onConnectPaired,
                        onDisconnect = onDisconnect,
                        onUnpair = onUnpair,
                        onOpenManualIP = { showManualIPDialog = true },
                        onRequestPairing = onRequestPairing,
                        onOpenPairSheet = { showPairDevicesSheet = true },
                        onOpenSyncModeDialog = { showSyncModeDialog = true },
                        onOpenFilePicker = { filePickerLauncher.launch(arrayOf("*/*")) },
                        onNavigateFiles = { navigateToTab(NavTab.FILES) },
                        onNavigateTransfers = { navigateToTab(NavTab.TRANSFERS) },
                        onNavigateSettings = { navigateToTab(NavTab.SETTINGS) },
                        onSendPing = onSendPing,
                        onOpenClipboardHistory = { showClipboardHistorySheet = true },
                        isEnhancedClipboardEnabled = isEnhancedClipboardEnabled,
                        onNavigateQR = { navigateToTab(NavTab.QR_CONNECT) },
                        onConnect = onConnect,
                        onRefreshDiscovery = onRefreshDiscovery,
                        onOpenNotifications = { showNotificationsDialog = true },
                        onRenameDevice = {
                            renameTarget = "local"
                            showRenameDialog = true
                        }
                    )
                }
                NavTab.QR_CONNECT -> {
                    QRConnectScreen(
                        localDeviceName = localDeviceName,
                        localPort = localPort,
                        onBack = { navigateBack() },
                        onConnectManual = onConnectManual,
                        onQRScanned = { payload ->
                            val scanned = runCatching { Uri.parse(payload) }.getOrNull()
                            val host = scanned?.getQueryParameter("host")
                            val port = scanned?.getQueryParameter("port")?.toIntOrNull() ?: 52840
                            if (scanned?.scheme == "dexolink" && scanned.host == "connect" && !host.isNullOrBlank()) {
                                onConnectManual(host, port)
                                navigateBack()
                            } else {
                                Toast.makeText(context, "This QR code is not a DexoLink connection code", Toast.LENGTH_LONG).show()
                            }
                        }
                    )
                }
                NavTab.FILES -> {
                    FilesTabContent(
                        receivedFiles = receivedFiles,
                        onOpenFile = onOpenFile,
                        onShareFile = onShareFile,
                        onDeleteFile = { fileToDelete = it },
                        onSendFiles = { filePickerLauncher.launch(arrayOf("*/*")) }
                    )
                }
                NavTab.TRANSFERS -> {
                    TransfersTabContent(
                        activeTransfers = activeTransfers,
                        completedTransfers = completedTransfers,
                        onCancelTransfer = onCancelTransfer,
                        onResumeTransfer = onResumeTransfer,
                        onPauseTransfer = onPauseTransfer,
                        onResumeOngoingTransfer = onResumeOngoingTransfer,
                        onDeleteTransfer = onDeleteTransfer,
                        onDeleteTransfers = onDeleteTransfers,
                        onClearTransfers = onClearTransfers,
                        onOpenFile = onOpenFile,
                        onShareFile = onShareFile,
                        onSendNewFile = { filePickerLauncher.launch(arrayOf("*/*")) }
                    )
                }
                NavTab.SETTINGS -> {
                    SettingsTabContent(
                        pairedDevice = pairedDevice,
                        localDeviceName = localDeviceName,
                        syncMode = syncMode,
                        isScanning = isScanning,
                        onToggleScan = { if (isScanning) onStopDiscovery() else onStartDiscovery() },
                        onModeSelected = onModeSelected,
                        onOpenSyncModeDialog = { showSyncModeDialog = true },
                        onUnpair = onUnpair,
                        onOpenRenameLocal = {
                            renameTarget = "local"
                            showRenameDialog = true
                        },
                        onOpenRenamePaired = {
                            renameTarget = "paired"
                            showRenameDialog = true
                        },
                        onShowSecurity = { showSecurityDialog = true },
                        onSendPing = onSendPing,
                        onOpenClipboardHistory = { showClipboardHistorySheet = true },
                        isEnhancedClipboardEnabled = isEnhancedClipboardEnabled,
                        onToggleEnhancedClipboard = onToggleEnhancedClipboard
                    )
                }
            }
        }
    }
}

// Home Tab Screen Content

@Composable
fun QRConnectScreen(
    localDeviceName: String,
    localPort: Int,
    onBack: () -> Unit,
    onConnectManual: (String, Int) -> Unit,
    onQRScanned: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    var qrPayload by remember(localDeviceName, localPort) {
        val port = localPort
        val devName = localDeviceName
        val host = com.macdroid.MacDroidApplication.discoveryService.getLocalIpAddress().orEmpty()
        mutableStateOf(if (host.isNotBlank()) "dexolink://connect?host=$host&port=$port&name=${Uri.encode(devName)}" else "")
    }
    var qrBitmap by remember(qrPayload) {
        mutableStateOf(qrPayload.takeIf { it.isNotBlank() }?.let { generateQRCodeBitmap(it, 600) })
    }

    val infiniteTransition = rememberInfiniteTransition(label = "scanLine")
    val scanLineOffset by infiniteTransition.animateFloat(
        initialValue = 0.1f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scanLineY"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF3F6FC))
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Top Bar
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White)
                    .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(14.dp))
                    .clickable { onBack() },
                contentAlignment = Alignment.Center
            ) {
                DexoIcon(
                    imageVector = DexoIcons.ArrowBack,
                    contentDescription = "Back",
                    tint = Color(0xFF0F172A),
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                "QR connect",
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                color = Color(0xFF0F172A)
            )
        }

        // Card 1: My QR code
        Card(
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "My QR code",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = Color(0xFF0F172A)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            "Let the other device scan this",
                            fontSize = 13.sp,
                            color = Color(0xFF64748B)
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Share Button
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(12.dp))
                                .background(Color.White)
                                .clickable {
                                    val bitmap = qrBitmap ?: return@clickable
                                    val qrFile = File(context.cacheDir, "dexolink-connection-qr.png")
                                    val wroteImage = runCatching {
                                        qrFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                                    }.getOrDefault(false)
                                    if (!wroteImage) {
                                        Toast.makeText(context, "Could not prepare the QR image", Toast.LENGTH_LONG).show()
                                        return@clickable
                                    }
                                    val imageUri = FileProvider.getUriForFile(context, "com.macdroid.fileprovider", qrFile)
                                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "image/png"
                                        putExtra(Intent.EXTRA_STREAM, imageUri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(sendIntent, "Share DexoLink QR Code"))
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            DexoIcon(
                                imageVector = DexoIcons.Share,
                                contentDescription = "Share",
                                tint = Color(0xFF2563EB),
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Refresh Button
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(12.dp))
                                .background(Color.White)
                                .clickable {
                                    val port = localPort
                                    val devName = localDeviceName
                                    val t = System.currentTimeMillis()
                                    val host = com.macdroid.MacDroidApplication.discoveryService.getLocalIpAddress()
                                    if (host.isNullOrBlank()) {
                                        Toast.makeText(context, "Connect to Wi-Fi before generating a QR code", Toast.LENGTH_LONG).show()
                                    } else {
                                        qrPayload = "dexolink://connect?host=$host&port=$port&name=${Uri.encode(devName)}&t=$t"
                                        qrBitmap = generateQRCodeBitmap(qrPayload, 600)
                                        Toast.makeText(context, "QR code refreshed", Toast.LENGTH_SHORT).show()
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            DexoIcon(
                                imageVector = DexoIcons.Refresh,
                                contentDescription = "Refresh",
                                tint = Color(0xFF2563EB),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // QR Container
                Box(
                    modifier = Modifier
                        .size(230.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(18.dp))
                        .background(Color.White)
                        .padding(14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    qrBitmap?.let { bmp ->
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "QR Code",
                            modifier = Modifier.fillMaxSize()
                        )
                    } ?: Text("Connect to Wi-Fi to generate your QR code", color = Color(0xFF64748B), fontSize = 13.sp)
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    localDeviceName.ifBlank { "This device" },
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Color(0xFF0F172A)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Section 2: Scan QR code
        Text(
            "Scan QR code",
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            color = Color(0xFF334155),
            modifier = Modifier.padding(bottom = 12.dp)
        )

        // Viewfinder Card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF0C1322)),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(150.dp)
                    .drawBehind {
                        val stroke = 3.dp.toPx()
                        val length = 28.dp.toPx()
                        val color = Color.White
                        val w = size.width
                        val h = size.height

                        // Top-left
                        drawLine(color, Offset(0f, 0f), Offset(length, 0f), stroke)
                        drawLine(color, Offset(0f, 0f), Offset(0f, length), stroke)

                        // Top-right
                        drawLine(color, Offset(w - length, 0f), Offset(w, 0f), stroke)
                        drawLine(color, Offset(w, 0f), Offset(w, length), stroke)

                        // Bottom-left
                        drawLine(color, Offset(0f, h - length), Offset(0f, h), stroke)
                        drawLine(color, Offset(0f, h), Offset(length, h), stroke)

                        // Bottom-right
                        drawLine(color, Offset(w - length, h), Offset(w, h), stroke)
                        drawLine(color, Offset(w, h - length), Offset(w, h), stroke)

                        // Animated scanning line
                        val lineY = h * scanLineOffset
                        drawLine(
                            Color(0xFF3B82F6),
                            Offset(8.dp.toPx(), lineY),
                            Offset(w - 8.dp.toPx(), lineY),
                            strokeWidth = 2.5.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    }
            )

            Text(
                "Point the camera at the other device's QR",
                color = Color(0xFFCBD5E1),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Full-width blue Scan QR Button
        Button(
            onClick = {
                GmsBarcodeScanning.getClient(context).startScan()
                    .addOnSuccessListener { barcode ->
                        val payload = barcode.rawValue
                        if (!payload.isNullOrBlank()) onQRScanned?.invoke(payload)
                        else Toast.makeText(context, "No QR code was detected", Toast.LENGTH_LONG).show()
                    }
                    .addOnCanceledListener {
                        // User dismissed the scanner; keep the QR screen open.
                    }
                    .addOnFailureListener { error ->
                        Toast.makeText(context, "Unable to scan QR code: ${error.localizedMessage}", Toast.LENGTH_LONG).show()
                    }
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DexoIcon(
                    imageVector = DexoIcons.QrCode,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Scan QR",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Color.White
                )
            }
        }
    }
}

// Data model for Connected Devices matching mobile design
data class MobileConnectedDevice(
    val id: String,
    val name: String,
    val status: String,
    val isDirectIp: Boolean = false,
    val rawTrusted: TrustedDevice? = null
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreenContent(
    connectionState: ConnectionState,
    syncMode: ClipboardSyncMode,
    devices: List<DiscoveredDevice>,
    pairedDevice: TrustedDevice?,
    localDeviceName: String,
    lastPingLatency: Long?,
    activeTransfers: List<TransferItem>,
    onConnectPaired: () -> Unit,
    onDisconnect: () -> Unit,
    onUnpair: () -> Unit,
    onOpenManualIP: () -> Unit,
    onRequestPairing: () -> Unit,
    onOpenPairSheet: () -> Unit,
    onOpenSyncModeDialog: () -> Unit,
    onOpenFilePicker: () -> Unit,
    onNavigateFiles: () -> Unit,
    onNavigateTransfers: () -> Unit,
    onNavigateSettings: () -> Unit,
    onSendPing: () -> Unit,
    onOpenClipboardHistory: () -> Unit = {},
    isEnhancedClipboardEnabled: Boolean = false,
    onNavigateQR: () -> Unit = {},
    onConnect: (DiscoveredDevice) -> Unit = {},
    onRefreshDiscovery: () -> Unit = {},
    onOpenNotifications: () -> Unit = {},
    onRenameDevice: () -> Unit = {}
) {
    val context = LocalContext.current
    val settings = remember { com.macdroid.storage.DexoLinkSettings.getInstance(context) }
    val multiPairedEnabled by settings.multiPairedDevices.collectAsState()
    val trustStore = remember { TrustStore.getInstance(context) }
    val trustedList = remember(pairedDevice, multiPairedEnabled) { trustStore.getTrustedDevices() }
    val isConnected = connectionState is ConnectionState.Connected || connectionState is ConnectionState.Authenticated

    var showReceiveDialog by remember { mutableStateOf(false) }
    var selectedDeviceMenu by remember { mutableStateOf<MobileConnectedDevice?>(null) }

    // Aggregate real connected and trusted devices
    val connectedList = remember(connectionState, pairedDevice, trustedList, isConnected) {
        val list = mutableListOf<MobileConnectedDevice>()
        val activeName = when (connectionState) {
            is ConnectionState.Connected -> connectionState.peerName
            is ConnectionState.Authenticated -> connectionState.peerName
            else -> null
        }
        val activeAddr = (connectionState as? ConnectionState.Connected)?.peerAddress

        if (isConnected && !activeName.isNullOrBlank()) {
            val isIp = activeAddr?.contains(":") == true || activeAddr?.startsWith("192") == true
            list.add(
                MobileConnectedDevice(
                    id = "active_peer",
                    name = activeName,
                    status = if (isIp) "Connected by IP" else "Connected",
                    isDirectIp = isIp
                )
            )
        }

        // Include paired trusted devices from TrustStore
        for (td in trustedList) {
            if (list.none { it.name.equals(td.deviceName, ignoreCase = true) }) {
                list.add(
                    MobileConnectedDevice(
                        id = td.deviceId,
                        name = td.deviceName,
                        status = "Disconnected",
                        rawTrusted = td
                    )
                )
            }
        }
        list
    }

    if (showReceiveDialog) {
        AlertDialog(
            onDismissRequest = { showReceiveDialog = false },
            containerColor = Color.White,
            shape = RoundedCornerShape(24.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFFEFF6FF)),
                        contentAlignment = Alignment.Center
                    ) {
                        DexoIcon(
                            imageVector = DexoIcons.ArrowDown,
                            contentDescription = null,
                            tint = Color(0xFF2563EB),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        "Receive Files",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = Color(0xFF0F172A)
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Your device '${localDeviceName.ifBlank { "This device" }}' is ready to receive files over high-speed local Wi-Fi.",
                        fontSize = 13.sp,
                        color = Color(0xFF475569)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFFF8FAFC))
                            .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(14.dp))
                            .padding(14.dp)
                    ) {
                        Column {
                            Text("Port: 52840", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF0F172A))
                            Text("Discovery: Active on local network", fontSize = 11.sp, color = Color(0xFF16A34A))
                            Text("Storage: Downloads/DexoLink", fontSize = 11.sp, color = Color(0xFF64748B))
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showReceiveDialog = false
                        onNavigateQR()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Show QR Code", fontWeight = FontWeight.Bold, color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showReceiveDialog = false }) {
                    Text("Close", color = Color(0xFF64748B))
                }
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF3F6FC))
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Home actions: Send, Receive, Scan QR
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Send (Blue solid)
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2563EB)),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(106.dp)
                        .clickable { onOpenFilePicker() }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        DexoIcon(
                            imageVector = DexoIcons.ArrowUp,
                            contentDescription = "Send",
                            tint = Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Send",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = Color.White
                        )
                    }
                }

                // Receive (White card)
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(106.dp)
                        .clickable { showReceiveDialog = true }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        DexoIcon(
                            imageVector = DexoIcons.ArrowDown,
                            contentDescription = "Receive",
                            tint = Color(0xFF2563EB),
                            modifier = Modifier.size(26.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Receive",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = Color(0xFF0F172A)
                        )
                    }
                }

                // Scan QR (White card)
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(106.dp)
                        .clickable { onNavigateQR() }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        DexoIcon(
                            imageVector = DexoIcons.QrCode,
                            contentDescription = "Scan QR",
                            tint = Color(0xFF2563EB),
                            modifier = Modifier.size(26.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Scan QR",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = Color(0xFF0F172A)
                        )
                    }
                }
            }
        }

        // 3. Paired devices Section
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Section Header Row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Paired devices",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = Color(0xFF334155)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    // Badge: e.g. "3 connected"
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFFDBEAFE))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            "${connectedList.count { dev ->
                                isConnected && (dev.id == "active_peer" || dev.name.equals(
                                    (connectionState as? ConnectionState.Connected)?.peerName
                                        ?: (connectionState as? ConnectionState.Authenticated)?.peerName,
                                    ignoreCase = true
                                ))
                            }} connected",
                            color = Color(0xFF2563EB),
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // Radar Scan Network Button
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(12.dp))
                            .background(Color.White)
                            .clickable {
                                onRefreshDiscovery()
                                Toast.makeText(context, "Scanning local network for devices...", Toast.LENGTH_SHORT).show()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        DexoIcon(
                            imageVector = DexoIcons.BroadcastWaves,
                            contentDescription = "Scan Network",
                            tint = Color(0xFF2563EB),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // White Card with Connected Device Rows
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                        if (connectedList.isEmpty()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp)
                            ) {
                                DexoIcon(
                                    imageVector = DexoIcons.Antenna,
                                    contentDescription = null,
                                    tint = Color(0xFF94A3B8),
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(14.dp))
                                Column {
                                    Text(
                                        "No devices connected",
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp,
                                        color = Color(0xFF334155)
                                    )
                                    Text(
                                        "Select a device below or scan QR to connect",
                                        fontSize = 12.sp,
                                        color = Color(0xFF64748B)
                                    )
                                }
                            }
                        } else {
                            connectedList.forEachIndexed { index, dev ->
                                val activePeerName = (connectionState as? ConnectionState.Connected)?.peerName
                                    ?: (connectionState as? ConnectionState.Authenticated)?.peerName
                                val deviceIsConnected = isConnected && (dev.id == "active_peer" || dev.name.equals(activePeerName, ignoreCase = true))
                                val deviceIcon = when {
                                    dev.name.contains("Mac", ignoreCase = true) || dev.name.contains("Laptop", ignoreCase = true) -> DexoIcons.DeviceMac
                                    dev.name.contains("iPhone", ignoreCase = true) || dev.name.contains("Phone", ignoreCase = true) || dev.name.contains("Android", ignoreCase = true) || dev.name.contains("Samsung", ignoreCase = true) -> DexoIcons.DevicePhone
                                    dev.name.contains("PC", ignoreCase = true) || dev.name.contains("Windows", ignoreCase = true) -> DexoIcons.DevicePc
                                    else -> DexoIcons.DeviceMac
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 14.dp)
                                ) {
                                    Box(
                                        modifier = Modifier.size(34.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        DexoIcon(
                                            imageVector = deviceIcon,
                                            contentDescription = dev.name,
                                            tint = if (deviceIsConnected) Color(0xFF2563EB) else Color(0xFF64748B),
                                            modifier = Modifier.size(24.dp)
                                        )
                                        // Status Badge: green Wi-Fi badge for online/connected, gray Wi-Fi-disabled badge for offline/disconnected
                                        Box(
                                            modifier = Modifier
                                                .size(14.dp)
                                                .align(Alignment.BottomEnd)
                                                .clip(CircleShape)
                                                .background(if (deviceIsConnected) Color(0xFF16A34A) else Color(0xFF94A3B8))
                                                .border(1.5.dp, Color.White, CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            DexoIcon(
                                                imageVector = if (deviceIsConnected) DexoIcons.Wifi else DexoIcons.WifiOff,
                                                contentDescription = if (deviceIsConnected) "Connected" else "Disconnected",
                                                tint = Color.White,
                                                modifier = Modifier.size(8.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(14.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            dev.name,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp,
                                            color = if (deviceIsConnected) Color(0xFF2563EB) else Color(0xFF334155)
                                        )
                                        Text(
                                            if (deviceIsConnected) (if (dev.status.isBlank() || dev.status.equals("Disconnected", ignoreCase = true)) "Connected" else dev.status) else "Disconnected",
                                            fontSize = 12.sp,
                                            color = if (deviceIsConnected) Color(0xFF16A34A) else Color(0xFF94A3B8)
                                        )
                                    }

                                    // Keep paired devices available when disconnected.
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(20.dp))
                                            .background(if (deviceIsConnected) Color(0xFFFEE2E2) else Color(0xFFDBEAFE))
                                            .clickable { if (deviceIsConnected) onDisconnect() else onConnectPaired() }
                                            .padding(horizontal = 14.dp, vertical = 7.dp)
                                    ) {
                                        Text(
                                            if (deviceIsConnected) "Disconnect" else "Connect",
                                            color = if (deviceIsConnected) Color(0xFFDC2626) else Color(0xFF2563EB),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(4.dp))

                                    // 3-dots Menu Button
                                    Box {
                                        IconButton(onClick = { selectedDeviceMenu = dev }) {
                                            DexoIcon(
                                                imageVector = DexoIcons.MoreVert,
                                                contentDescription = "Options",
                                                tint = Color(0xFF64748B),
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }

                                        DropdownMenu(
                                            expanded = selectedDeviceMenu == dev,
                                            onDismissRequest = { selectedDeviceMenu = null },
                                            modifier = Modifier.background(Color.White)
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text("Send File", color = Color(0xFF0F172A)) },
                                                onClick = {
                                                    selectedDeviceMenu = null
                                                    onOpenFilePicker()
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Ping Latency", color = Color(0xFF0F172A)) },
                                                onClick = {
                                                    selectedDeviceMenu = null
                                                    onSendPing()
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Unpair", color = Color(0xFFEF4444), fontWeight = FontWeight.Bold) },
                                                onClick = {
                                                    selectedDeviceMenu = null
                                                    onUnpair()
                                                }
                                            )
                                        }
                                    }
                                }

                                if (index < connectedList.lastIndex) {
                                    Divider(color = Color(0xFFF1F5F9), thickness = 1.dp)
                                }
                            }
                        }
                    }
                }
            }
        }

        // 4. Available devices Section
        item {
            val isSingleDeviceMode = !multiPairedEnabled
            val hasPairedOrConnected = connectedList.isNotEmpty() || (pairedDevice != null) || trustedList.isNotEmpty()
            val isSingleDeviceBlocked = isSingleDeviceMode && hasPairedOrConnected

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Available devices",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Color(0xFF334155)
                )

                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                        // Filter out already connected
                        val available = devices.filter { dev -> connectedList.none { it.name.equals(dev.name, ignoreCase = true) } }

                        if (available.isNotEmpty()) {
                            available.forEachIndexed { index, dev ->
                                val isBlocked = isSingleDeviceBlocked && !trustStore.isPairedWith(dev.deviceId ?: "", dev.name)
                                val devIcon = if (dev.name.contains("iPad", ignoreCase = true) || dev.name.contains("Tablet", ignoreCase = true)) DexoIcons.DevicePhone else DexoIcons.DevicePhone
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 14.dp)
                                ) {
                                    DexoIcon(
                                        imageVector = devIcon,
                                        contentDescription = dev.name,
                                        tint = if (isBlocked) Color(0xFF94A3B8) else Color(0xFF2563EB),
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(14.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                dev.name,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 15.sp,
                                                color = if (isBlocked) Color(0xFF64748B) else Color(0xFF0F172A)
                                            )
                                            if (isBlocked) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Surface(
                                                    color = Color(0xFFFEE2E2),
                                                    shape = RoundedCornerShape(4.dp)
                                                ) {
                                                    Text(
                                                        "Blocked",
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color(0xFFDC2626),
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            if (isBlocked) "Single device mode active" else "Nearby",
                                            fontSize = 12.sp,
                                            color = Color(0xFF64748B)
                                        )
                                    }

                                    // Connect Button
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(20.dp))
                                            .background(if (isBlocked) Color(0xFFF1F5F9) else Color(0xFFDBEAFE))
                                            .clickable {
                                                if (isBlocked) {
                                                    Toast.makeText(
                                                        context,
                                                        "Single Device Mode active. Enable 'Multiple Paired Devices' in Settings to pair additional devices.",
                                                        Toast.LENGTH_LONG
                                                    ).show()
                                                } else {
                                                    onConnect(dev)
                                                }
                                            }
                                            .padding(horizontal = 16.dp, vertical = 7.dp)
                                    ) {
                                        Text(
                                            "Connect",
                                            color = if (isBlocked) Color(0xFF94A3B8) else Color(0xFF2563EB),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp
                                        )
                                    }
                                }

                                Divider(color = Color(0xFFF1F5F9), thickness = 1.dp)
                            }
                        }

                        // Connect by IP address row
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenManualIP() }
                                .padding(vertical = 16.dp)
                        ) {
                            DexoIcon(
                                imageVector = DexoIcons.Wifi,
                                contentDescription = "Connect by IP",
                                tint = Color(0xFF2563EB),
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Text(
                                "Connect by IP address",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = Color(0xFF0F172A),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(100.dp))
        }
    }
}

@Composable
fun CameraSharingDialog(
    onDismiss: () -> Unit,
    hasPermission: Boolean,
    isConnected: Boolean,
    connectedDeviceName: String?,
    isFrontCamera: Boolean = false,
    onSwitchCamera: (() -> Unit)? = null,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onStartStream: () -> Unit,
    onRequestRemoteStream: () -> Unit,
    onStopStream: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        shape = RoundedCornerShape(20.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFFFCE7F3)),
                    contentAlignment = Alignment.Center
                ) {
                    DexoIcon(imageVector = DexoIcons.CategoryVideo, contentDescription = null, tint = Color(0xFFEC4899), modifier = Modifier.size(20.dp))
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text("Camera Sharing", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color(0xFF0F172A))
                    Text(if (hasPermission) "Hardware Access: Allowed" else "Hardware Access: Permission Required", fontSize = 11.sp, color = if (hasPermission) Color(0xFF16A34A) else Color(0xFFDC2626))
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Stream your phone's camera feed directly to your Mac, PC, or iPhone, or request remote camera video securely over local Wi-Fi.",
                    fontSize = 12.sp,
                    color = Color(0xFF475569)
                )

                if (!hasPermission) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF1F2)),
                        border = BorderStroke(1.dp, Color(0xFFFECDD3)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("Camera Permission Required", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFF9F1239))
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Grant camera permission so DexoLink can capture video frames.", fontSize = 11.sp, color = Color(0xFFBE123C))
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = onRequestPermission,
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE11D48)),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text("Grant Access", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                OutlinedButton(
                                    onClick = onOpenSettings,
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text("App Settings", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                } else if (!isConnected || connectedDeviceName == null) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFBEB)),
                        border = BorderStroke(1.dp, Color(0xFFFDE68A)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier.size(16.dp).clip(CircleShape).background(Color(0xFFF59E0B)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("!", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("No Compatible Device Connected", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFF92400E))
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Connect and pair with a Mac, Windows PC, iPhone, or another Android device over local Wi-Fi to start camera sharing.",
                                fontSize = 11.sp,
                                color = Color(0xFFB45309)
                            )
                        }
                    }
                } else {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                        border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF16A34A)))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Verified Connected Device: $connectedDeviceName", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFF0F172A))
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Active Camera Lens:", fontSize = 11.sp, color = Color(0xFF475569), fontWeight = FontWeight.Medium)
                                OutlinedButton(
                                    onClick = { onSwitchCamera?.invoke() },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        if (isFrontCamera) "Front Camera" else "Rear Camera",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF0284C7)
                                    )
                                }
                            }

                            Button(
                                onClick = onStartStream,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEC4899)),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Stream Phone Camera (Android -> $connectedDeviceName)", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            }
                            OutlinedButton(
                                onClick = onRequestRemoteStream,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("View Remote Camera ($connectedDeviceName -> Android)", fontSize = 11.sp, color = Color(0xFF0F172A), fontWeight = FontWeight.SemiBold)
                            }
                            OutlinedButton(
                                onClick = onStopStream,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Stop Active Camera Feed", fontSize = 11.sp, color = Color(0xFFDC2626))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = Color(0xFF64748B), fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
fun ScreenSharingDialog(
    onDismiss: () -> Unit,
    isConnected: Boolean,
    connectedDeviceName: String?,
    onStartMirroring: () -> Unit,
    onRequestRemoteScreen: () -> Unit,
    onStopMirroring: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        shape = RoundedCornerShape(20.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFFEFF6FF)),
                    contentAlignment = Alignment.Center
                ) {
                    DexoIcon(imageVector = DexoIcons.DeviceMac, contentDescription = null, tint = Color(0xFF2563EB), modifier = Modifier.size(20.dp))
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text("Screen Sharing", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color(0xFF0F172A))
                    Text("Display Mirroring", fontSize = 11.sp, color = Color(0xFF64748B))
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Mirror your device display to paired screens in real time using local hardware-accelerated capture.",
                    fontSize = 12.sp,
                    color = Color(0xFF475569)
                )

                if (!isConnected || connectedDeviceName == null) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFBEB)),
                        border = BorderStroke(1.dp, Color(0xFFFDE68A)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier.size(16.dp).clip(CircleShape).background(Color(0xFFF59E0B)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("!", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("No Compatible Device Connected", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFF92400E))
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Connect and pair with a Mac, Windows PC, iPhone, or another Android device over local Wi-Fi to start screen mirroring.",
                                fontSize = 11.sp,
                                color = Color(0xFFB45309)
                            )
                        }
                    }
                } else {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                        border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF16A34A)))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Verified Connected Device: $connectedDeviceName", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFF0F172A))
                            }

                            Button(
                                onClick = onStartMirroring,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Mirror Phone Screen (Android -> $connectedDeviceName)", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            }
                            OutlinedButton(
                                onClick = onRequestRemoteScreen,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("View Remote Screen ($connectedDeviceName -> Android)", fontSize = 11.sp, color = Color(0xFF0F172A), fontWeight = FontWeight.SemiBold)
                            }
                            OutlinedButton(
                                onClick = onStopMirroring,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Stop Screen Mirroring", fontSize = 11.sp, color = Color(0xFFDC2626))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = Color(0xFF64748B), fontWeight = FontWeight.Bold)
            }
        }
    )
}

fun isLaptopDevice(deviceName: String): Boolean {
    val lower = deviceName.lowercase()
    return lower.contains("macbook") || lower.contains("mac") || lower.contains("windows") || lower.contains("pc") || lower.contains("laptop") || lower.contains("desktop")
}

@Composable
fun ScreenStreamViewerDialog(
    onDismiss: () -> Unit,
    bitmap: Bitmap?,
    fps: Int,
    deviceName: String,
    onStop: () -> Unit,
    onSendInput: (eventType: String, x: Double, y: Double, button: Int, key: String?) -> Unit
) {
    var rotationAngle by remember { mutableStateOf(0f) }
    var showWriteDialog by remember { mutableStateOf(false) }
    var showTrackpadOverlay by remember { mutableStateOf(false) }
    var posX by remember { mutableStateOf(0.5) }
    var posY by remember { mutableStateOf(0.5) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // Live Display Stream Feed with Interactive Screen Control
            if (bitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "$deviceName Screen Stream",
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { rotationZ = rotationAngle }
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { offset ->
                                    val normX = (offset.x / size.width.toFloat()).toDouble().coerceIn(0.0, 1.0)
                                    val normY = (offset.y / size.height.toFloat()).toDouble().coerceIn(0.0, 1.0)
                                    posX = normX
                                    posY = normY
                                    onSendInput("click", normX, normY, 0, null)
                                }
                            )
                        },
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit
                )
            } else {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(
                        color = Color(0xFF38BDF8),
                        modifier = Modifier.size(44.dp)
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "Waiting for display stream from $deviceName...",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Ensure Screen Sharing is active on the sending device",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp
                    )
                }
            }

            // Top Floating Header Bar
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(top = 36.dp, start = 14.dp, end = 14.dp),
                color = Color(0xCC0F172A),
                shape = RoundedCornerShape(14.dp),
                tonalElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF22C55E))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "LIVE MIRROR",
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = Color(0xFF22C55E)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = deviceName,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            color = Color(0x2222C55E),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = if (bitmap != null) "${bitmap.width}x${bitmap.height} ${fps} FPS" else "${fps} FPS",
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = Color(0xFF22C55E),
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                            )
                        }

                        // Write / Keyboard Option
                        Button(
                            onClick = { showWriteDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4F46E5)),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text("Write", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color.White)
                        }

                        // Trackpad Control Option
                        OutlinedButton(
                            onClick = { showTrackpadOverlay = !showTrackpadOverlay },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF10B981)),
                            border = BorderStroke(1.dp, Color(0xFF059669)),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(if (showTrackpadOverlay) "Hide Pad" else "Trackpad", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color(0xFF10B981))
                        }

                        // Route Orientation Cycle Button
                        OutlinedButton(
                            onClick = {
                                rotationAngle = (rotationAngle + 90f) % 360f
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF38BDF8)),
                            border = BorderStroke(1.dp, Color(0xFF0284C7)),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            val dirLabel = when (rotationAngle.toInt()) {
                                90 -> "Right (90 deg)"
                                180 -> "Down (180 deg)"
                                270 -> "Left (270 deg)"
                                else -> "Up (0 deg)"
                            }
                            Text("Route: $dirLabel", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color(0xFF38BDF8))
                        }

                        Button(
                            onClick = onStop,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text("Stop", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.White)
                        }

                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = DexoIcons.Close,
                                contentDescription = "Close Viewer",
                                tint = Color(0xFF94A3B8)
                            )
                        }
                    }
                }
            }

            // Write / Type Text to Remote Screen Dialog
            if (showWriteDialog) {
                var textInput by remember { mutableStateOf("") }
                AlertDialog(
                    onDismissRequest = { showWriteDialog = false },
                    containerColor = Color.White,
                    shape = RoundedCornerShape(20.dp),
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFFEEF2FF)),
                                contentAlignment = Alignment.Center
                            ) {
                                DexoIcon(imageVector = DexoIcons.Sparkles, contentDescription = null, tint = Color(0xFF4F46E5), modifier = Modifier.size(20.dp))
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text("Write to Remote Screen", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Color(0xFF0F172A))
                                Text("Host: $deviceName", fontSize = 11.sp, color = Color(0xFF64748B))
                            }
                        }
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                "Type text or commands to send directly to your remote computer or device.",
                                fontSize = 12.sp,
                                color = Color(0xFF475569)
                            )

                            OutlinedTextField(
                                value = textInput,
                                onValueChange = { textInput = it },
                                label = { Text("Text or command to write...") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = false,
                                maxLines = 4,
                                shape = RoundedCornerShape(10.dp)
                            )

                            // Quick Key Actions
                            Text("Quick Keys:", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF334155))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Button(
                                    onClick = { onSendInput("key_press", 0.0, 0.0, 0, "return") },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                                ) {
                                    Text("Enter", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                Button(
                                    onClick = { onSendInput("key_press", 0.0, 0.0, 0, "backspace") },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF64748B)),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                                ) {
                                    Text("Backspace", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                                Button(
                                    onClick = { onSendInput("key_press", 0.0, 0.0, 0, "space") },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF64748B)),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                                ) {
                                    Text("Space", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                Button(
                                    onClick = { onSendInput("key_press", 0.0, 0.0, 0, "tab") },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF64748B)),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                                ) {
                                    Text("Tab", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                if (textInput.isNotEmpty()) {
                                    onSendInput("write", 0.0, 0.0, 0, textInput)
                                    textInput = ""
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4F46E5)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Send Text", fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showWriteDialog = false }) {
                            Text("Done", color = Color(0xFF64748B), fontWeight = FontWeight.Bold)
                        }
                    }
                )
            }

            // Virtual Trackpad Floating Overlay
            if (showTrackpadOverlay) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 72.dp, start = 16.dp, end = 16.dp)
                        .fillMaxWidth(0.9f)
                        .height(170.dp),
                    color = Color(0xE60F172A),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Color(0xFF334155)),
                    tonalElevation = 8.dp
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Virtual Trackpad (Drag to Move)", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFCBD5E1))
                            IconButton(onClick = { showTrackpadOverlay = false }, modifier = Modifier.size(24.dp)) {
                                Icon(imageVector = DexoIcons.Close, contentDescription = "Close Trackpad", tint = Color(0xFF94A3B8), modifier = Modifier.size(16.dp))
                            }
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(80.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF1E293B))
                                .pointerInput(Unit) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        posX = (posX + (dragAmount.x / 400.0)).coerceIn(0.0, 1.0)
                                        posY = (posY + (dragAmount.y / 300.0)).coerceIn(0.0, 1.0)
                                        onSendInput("mouse_move", posX, posY, 0, null)
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Slide finger to navigate cursor", fontSize = 10.sp, color = Color(0xFF94A3B8))
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(
                                onClick = { onSendInput("click", posX, posY, 0, null) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(vertical = 4.dp)
                            ) {
                                Text("Left Click", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = { onSendInput("click", posX, posY, 1, null) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF475569)),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(vertical = 4.dp)
                            ) {
                                Text("Right Click", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // Bottom Directional Route Dock (Up, Right, Down, Left)
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp),
                color = Color(0xCC0F172A),
                shape = RoundedCornerShape(24.dp),
                tonalElevation = 6.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf(
                        0f to "Up",
                        90f to "Right",
                        180f to "Down",
                        270f to "Left"
                    ).forEach { (deg, label) ->
                        val isSelected = (rotationAngle == deg)
                        TextButton(
                            onClick = { rotationAngle = deg },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = if (isSelected) Color(0xFF38BDF8) else Color(0xFF94A3B8)
                            ),
                            shape = RoundedCornerShape(16.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = label,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CameraStreamViewerDialog(
    onDismiss: () -> Unit,
    bitmap: Bitmap?,
    fps: Int,
    deviceName: String,
    onStop: () -> Unit,
    onFlipCamera: (() -> Unit)? = null
) {
    val isLaptop = isLaptopDevice(deviceName)
    var rotationAngle by remember { mutableStateOf(0f) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // Live Camera Feed
            if (bitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "$deviceName Camera Feed",
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { rotationZ = rotationAngle },
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit
                )
            } else {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(
                        color = Color(0xFFEC4899),
                        modifier = Modifier.size(44.dp)
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "Connecting to $deviceName camera feed...",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Make sure Camera Sharing is allowed and turned ON on $deviceName",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp
                    )
                }
            }

            // Top Floating Header Bar
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(top = 36.dp, start = 14.dp, end = 14.dp),
                color = Color(0xCC0F172A),
                shape = RoundedCornerShape(14.dp),
                tonalElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFEF4444))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "LIVE CAMERA",
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = Color(0xFFEF4444)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = deviceName,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            color = Color(0x2222C55E),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = if (bitmap != null) "${bitmap.width}x${bitmap.height} ${fps} FPS" else "${fps} FPS",
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = Color(0xFF22C55E),
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                            )
                        }

                        // Route Orientation Cycle Button
                        OutlinedButton(
                            onClick = {
                                rotationAngle = (rotationAngle + 90f) % 360f
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFA855F7)),
                            border = BorderStroke(1.dp, Color(0xFF7E22CE)),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            val dirLabel = when (rotationAngle.toInt()) {
                                90 -> "Right (90 deg)"
                                180 -> "Down (180 deg)"
                                270 -> "Left (270 deg)"
                                else -> "Up (0 deg)"
                            }
                            Text("Route: $dirLabel", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color(0xFFA855F7))
                        }

                        // Flip Camera button: only shown for mobile devices (laptops only have a single built-in webcam)
                        if (!isLaptop && onFlipCamera != null) {
                            OutlinedButton(
                                onClick = onFlipCamera,
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF38BDF8)),
                                border = BorderStroke(1.dp, Color(0xFF0284C7)),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("Flip Camera", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color(0xFF38BDF8))
                            }
                        }

                        Button(
                            onClick = onStop,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text("Stop Feed", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.White)
                        }

                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = DexoIcons.Close,
                                contentDescription = "Close Viewer",
                                tint = Color(0xFF94A3B8)
                            )
                        }
                    }
                }
            }

            // Bottom Directional Route Dock (Up, Right, Down, Left)
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 28.dp),
                color = Color(0xCC0F172A),
                shape = RoundedCornerShape(24.dp),
                tonalElevation = 6.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf(
                        0f to "Up",
                        90f to "Right",
                        180f to "Down",
                        270f to "Left"
                    ).forEach { (deg, label) ->
                        val isSelected = (rotationAngle == deg)
                        TextButton(
                            onClick = { rotationAngle = deg },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = if (isSelected) Color(0xFF38BDF8) else Color(0xFF94A3B8)
                            ),
                            shape = RoundedCornerShape(16.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = label,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DesktopModeDialog(
    onDismiss: () -> Unit,
    pairedDeviceName: String?,
    onSendInput: (String, Double, Double, Int) -> Unit
) {
    var posX by remember { mutableStateOf(0.5) }
    var posY by remember { mutableStateOf(0.5) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        shape = RoundedCornerShape(20.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFFD1FAE5)),
                    contentAlignment = Alignment.Center
                ) {
                    DexoIcon(imageVector = DexoIcons.Globe, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(20.dp))
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text("Desktop Mode (Beta)", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color(0xFF0F172A))
                    Text(if (pairedDeviceName != null) "Host: $pairedDeviceName" else "Host: Not Connected", fontSize = 11.sp, color = if (pairedDeviceName != null) Color(0xFF16A34A) else Color(0xFF64748B))
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Touch and drag inside the trackpad below to control your Mac or Windows cursor in real time.",
                    fontSize = 12.sp,
                    color = Color(0xFF475569)
                )

                // Virtual Trackpad
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF0F172A))
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                posX = (posX + (dragAmount.x / 400.0)).coerceIn(0.0, 1.0)
                                posY = (posY + (dragAmount.y / 300.0)).coerceIn(0.0, 1.0)
                                onSendInput("mouse_move", posX, posY, 0)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        DexoIcon(imageVector = DexoIcons.Sparkles, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Interactive Trackpad Area", fontSize = 12.sp, color = Color(0xFFCBD5E1), fontWeight = FontWeight.SemiBold)
                        Text("Drag to move pointer", fontSize = 10.sp, color = Color(0xFF64748B))
                    }
                }

                // Mouse Click Actions
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            onSendInput("mouse_down", posX, posY, 0)
                            onSendInput("mouse_up", posX, posY, 0)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Left Click", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = {
                            onSendInput("mouse_down", posX, posY, 1)
                            onSendInput("mouse_up", posX, posY, 1)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Right Click", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close Trackpad", color = Color(0xFF64748B), fontWeight = FontWeight.Bold)
            }
        }
    )
}

// Files Tab Screen Content

@Composable
fun FilesTabContent(
    receivedFiles: List<File>,
    onOpenFile: (File) -> Unit,
    onShareFile: (File) -> Unit,
    onDeleteFile: (File) -> Unit,
    onSendFiles: () -> Unit
) {
    var selectedFilter by remember { mutableStateOf("All") }
    val filters = listOf("All", "Photos", "Videos", "Documents", "Downloads")

    val filteredFiles = remember(receivedFiles, selectedFilter) {
        when (selectedFilter) {
            "Photos" -> receivedFiles.filter { it.extension.lowercase() in listOf("jpg", "jpeg", "png", "webp", "gif", "heic", "bmp") }
            "Videos" -> receivedFiles.filter { it.extension.lowercase() in listOf("mp4", "mkv", "mov", "avi", "webm", "3gp") }
            "Documents" -> receivedFiles.filter { it.extension.lowercase() in listOf("pdf", "doc", "docx", "txt", "pages", "xls", "xlsx", "csv", "ppt", "pptx", "rtf") }
            else -> receivedFiles
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(6.dp))

        // Title Row with "Send File" Pill Button
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                Text(
                    "Files",
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp,
                    color = Color(0xFF0F172A)
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    "Browse, send and manage files",
                    fontSize = 12.sp,
                    color = Color(0xFF64748B)
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Button(
                onClick = onSendFiles,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0066FF)),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DexoIcon(
                        imageVector = DexoIcons.ArrowUp,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Send File", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Horizontal Category Filter Pills
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(filters) { f ->
                val isSel = f == selectedFilter
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (isSel) Color(0xFF0066FF) else Color(0xFFF1F5F9))
                        .clickable { selectedFilter = f }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        f,
                        fontSize = 12.sp,
                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSel) Color.White else Color(0xFF475569)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // File List / Real Data Display
        if (filteredFiles.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFEFF6FF)),
                        contentAlignment = Alignment.Center
                    ) {
                        DexoIcon(
                            imageVector = DexoIcons.Folder,
                            contentDescription = null,
                            tint = Color(0xFF0066FF),
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "No Received Files",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = Color(0xFF0F172A)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Files sent from your paired Mac appear here automatically in Downloads/DexoLink.",
                        fontSize = 12.sp,
                        color = Color(0xFF64748B),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filteredFiles) { file ->
                    var showItemMenu by remember { mutableStateOf(false) }

                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenFile(file) }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(12.dp)
                        ) {
                            FileThumbnailOrIcon(file = file)
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    file.name,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = Color(0xFF0F172A),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    "${formatFileSize(file.length())} • ${formatFileDate(file.lastModified())}",
                                    fontSize = 12.sp,
                                    color = Color(0xFF64748B)
                                )
                            }

                            // 3-dots overflow menu
                            Box {
                                IconButton(onClick = { showItemMenu = true }) {
                                    DexoIcon(
                                        imageVector = DexoIcons.MoreVert,
                                        contentDescription = "More Options",
                                        tint = Color(0xFF64748B),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                DropdownMenu(
                                    expanded = showItemMenu,
                                    onDismissRequest = { showItemMenu = false },
                                    modifier = Modifier.background(Color.White)
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Open", color = Color(0xFF0F172A)) },
                                        onClick = {
                                            showItemMenu = false
                                            onOpenFile(file)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Share with All Apps...", color = Color(0xFF0F172A)) },
                                        onClick = {
                                            showItemMenu = false
                                            onShareFile(file)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Delete", color = Color(0xFFDC2626)) },
                                        onClick = {
                                            showItemMenu = false
                                            onDeleteFile(file)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// Transfers Tab Screen Content

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TransfersTabContent(
    activeTransfers: List<TransferItem>,
    completedTransfers: List<TransferItem>,
    onCancelTransfer: (String) -> Unit,
    onResumeTransfer: (String) -> Unit,
    onPauseTransfer: (String) -> Unit = {},
    onResumeOngoingTransfer: (String) -> Unit = {},
    onDeleteTransfer: (String) -> Unit = {},
    onDeleteTransfers: (Set<String>) -> Unit = {},
    onClearTransfers: () -> Unit,
    onOpenFile: (File) -> Unit,
    onShareFile: (File) -> Unit,
    onSendNewFile: () -> Unit
) {
    var selectedFilter by remember { mutableStateOf("Ongoing") }
    var isSelectionMode by remember { mutableStateOf(false) }
    val selectedTransferIds = remember { mutableStateListOf<String>() }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showClearAllConfirmDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(6.dp))

        // Title Row
        Column {
            Text(
                "Transfers",
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
                color = Color(0xFF0F172A)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                "Send and receive files between devices",
                fontSize = 12.sp,
                color = Color(0xFF64748B)
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Filter Pills Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Pill 1: Ongoing with Badge
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (selectedFilter == "Ongoing") Color(0xFF0066FF) else Color(0xFFF1F5F9))
                    .clickable { selectedFilter = "Ongoing" }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Ongoing",
                        fontSize = 12.sp,
                        fontWeight = if (selectedFilter == "Ongoing") FontWeight.Bold else FontWeight.Medium,
                        color = if (selectedFilter == "Ongoing") Color.White else Color(0xFF475569)
                    )
                    if (activeTransfers.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (selectedFilter == "Ongoing") Color.White else Color(0xFF0066FF))
                                .padding(horizontal = 6.dp, vertical = 1.dp)
                        ) {
                            Text(
                                activeTransfers.size.toString(),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (selectedFilter == "Ongoing") Color(0xFF0066FF) else Color.White
                            )
                        }
                    }
                }
            }

            // Pill 2: Completed
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (selectedFilter == "Completed") Color(0xFF0066FF) else Color(0xFFF1F5F9))
                    .clickable { selectedFilter = "Completed" }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    "Completed",
                    fontSize = 12.sp,
                    fontWeight = if (selectedFilter == "Completed") FontWeight.Bold else FontWeight.Medium,
                    color = if (selectedFilter == "Completed") Color.White else Color(0xFF475569)
                )
            }

            // Pill 3: Failed
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (selectedFilter == "Failed") Color(0xFF0066FF) else Color(0xFFF1F5F9))
                    .clickable { selectedFilter = "Failed" }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    "Failed",
                    fontSize = 12.sp,
                    fontWeight = if (selectedFilter == "Failed") FontWeight.Bold else FontWeight.Medium,
                    color = if (selectedFilter == "Failed") Color.White else Color(0xFF475569)
                )
            }
        }

        val visibleCompleted = remember(completedTransfers, selectedFilter) {
            when (selectedFilter) {
                "Completed" -> completedTransfers.filter { it.state == TransferStatus.COMPLETED }
                "Failed" -> completedTransfers.filter { it.state != TransferStatus.COMPLETED }
                else -> completedTransfers
            }
        }

        // Main List Content
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // If Ongoing filter selected, show active transfers
            if (selectedFilter == "Ongoing" || selectedFilter == "All") {
                if (activeTransfers.isNotEmpty()) {
                    items(activeTransfers) { item ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    FileCategoryIcon(extension = item.name.substringAfterLast('.', ""))
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            item.name,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            color = Color(0xFF0F172A),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        val isPaused = item.state == TransferStatus.PAUSED
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            if (isPaused) "Paused • ${formatFileSize(item.bytesTransferred)} / ${formatFileSize(item.size)}"
                                            else "${if (item.isOutgoing) "Sending to Mac" else "Receiving from Mac"} • ${formatFileSize(item.bytesTransferred)} / ${formatFileSize(item.size)}",
                                            fontSize = 11.sp,
                                            color = if (isPaused) Color(0xFFD97706) else Color(0xFF64748B)
                                        )
                                    }

                                    // Pause / Resume and Cancel Action Buttons
                                    val isPaused = item.state == TransferStatus.PAUSED
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Box(
                                            modifier = Modifier
                                                .size(32.dp)
                                                .clip(CircleShape)
                                                .background(if (isPaused) Color(0xFFFEF3C7) else Color(0xFFEFF6FF))
                                                .clickable {
                                                    if (isPaused) {
                                                        onResumeOngoingTransfer(item.id)
                                                    } else {
                                                        onPauseTransfer(item.id)
                                                    }
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (isPaused) {
                                                DexoIcon(
                                                    imageVector = DexoIcons.Play,
                                                    contentDescription = "Resume",
                                                    tint = Color(0xFFD97706),
                                                    modifier = Modifier.size(13.dp)
                                                )
                                            } else {
                                                DexoIcon(
                                                    imageVector = DexoIcons.Pause,
                                                    contentDescription = "Pause",
                                                    tint = Color(0xFF0066FF),
                                                    modifier = Modifier.size(13.dp)
                                                )
                                            }
                                        }
                                        Box(
                                            modifier = Modifier
                                                .size(32.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xFFFEE2E2))
                                                .clickable { onCancelTransfer(item.id) },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            DexoIcon(
                                                imageVector = DexoIcons.Close,
                                                contentDescription = "Cancel",
                                                tint = Color(0xFFEF4444),
                                                modifier = Modifier.size(13.dp)
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                // Progress Bar with Percentage
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    LinearProgressIndicator(
                                        progress = item.progress,
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(6.dp)
                                            .clip(RoundedCornerShape(3.dp)),
                                        color = if (item.progress >= 0.99f) Color(0xFF10B981) else Color(0xFF0066FF),
                                        trackColor = Color(0xFFE2E8F0)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        "${(item.progress * 100).toInt()}%",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = if (item.progress >= 0.99f) Color(0xFF10B981) else Color(0xFF0066FF)
                                    )
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                // Speed and ETA Row
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    DexoIcon(
                                        imageVector = DexoIcons.Flash,
                                        contentDescription = null,
                                        tint = Color(0xFFF59E0B),
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        formatSpeed(item.speedBytesPerSec),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFFF59E0B)
                                    )
                                    Text(
                                        " | ${formatEta(item.etaSeconds)} remaining",
                                        fontSize = 11.sp,
                                        color = Color(0xFF64748B)
                                    )
                                }
                            }
                        }
                    }
                } else if (completedTransfers.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    modifier = Modifier
                                        .size(60.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFEFF6FF)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    DexoIcon(
                                        imageVector = DexoIcons.Transfer,
                                        contentDescription = null,
                                        tint = Color(0xFF0066FF),
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    "No Active Transfers",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    color = Color(0xFF0F172A)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "Tap 'Send File' below or in Files tab to transfer up to 10 GB between phone & Mac.",
                                    fontSize = 12.sp,
                                    color = Color(0xFF64748B),
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                                Button(
                                    onClick = onSendNewFile,
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0066FF)),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        DexoIcon(
                                            imageVector = DexoIcons.ArrowUp,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Send File", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Recent Transfers Header
            if (visibleCompleted.isNotEmpty()) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                    ) {
                        if (isSelectionMode) {
                            Text(
                                "${selectedTransferIds.size} Selected",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = Color(0xFF0F172A)
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            TextButton(
                                onClick = {
                                    if (selectedTransferIds.size == visibleCompleted.size) {
                                        selectedTransferIds.clear()
                                    } else {
                                        selectedTransferIds.clear()
                                        selectedTransferIds.addAll(visibleCompleted.map { it.id })
                                    }
                                }
                            ) {
                                Text(
                                    if (selectedTransferIds.size == visibleCompleted.size) "Deselect All" else "Select All",
                                    fontSize = 12.sp,
                                    color = Color(0xFF0066FF),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (selectedTransferIds.isNotEmpty()) Color(0xFFDC2626) else Color(0xFFCBD5E1))
                                    .clickable(enabled = selectedTransferIds.isNotEmpty()) {
                                        showDeleteConfirmDialog = true
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    "Delete (${selectedTransferIds.size})",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            TextButton(
                                onClick = {
                                    isSelectionMode = false
                                    selectedTransferIds.clear()
                                }
                            ) {
                                Text("Cancel", fontSize = 12.sp, color = Color(0xFF64748B))
                            }
                        } else {
                            Text(
                                "Recent Transfers",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = Color(0xFF0F172A)
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFFEFF6FF))
                                    .clickable { isSelectionMode = true }
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    "Select",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF0066FF)
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFFFEE2E2))
                                    .clickable { showClearAllConfirmDialog = true }
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    DexoIcon(
                                        imageVector = DexoIcons.Trash,
                                        contentDescription = null,
                                        tint = Color(0xFFDC2626),
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        "Clear All",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFDC2626)
                                    )
                                }
                            }
                        }
                    }
                }

                items(visibleCompleted) { item ->
                    val isSuccess = item.state == TransferStatus.COMPLETED
                    val ext = item.name.substringAfterLast('.', "")
                    val localFile: File? = item.localFile ?: run {
                        val downloads: File? = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        if (downloads != null) {
                            val subFile = File(downloads, "DexoLink/${item.name}")
                            if (subFile.exists()) subFile else File(downloads, item.name).takeIf { f: File -> f.exists() }
                        } else null
                    }
                    var showItemMenu by remember { mutableStateOf(false) }

                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        border = BorderStroke(
                            1.dp,
                            if (selectedTransferIds.contains(item.id)) Color(0xFF0066FF) else Color(0xFFE2E8F0)
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {
                                    if (isSelectionMode) {
                                        if (selectedTransferIds.contains(item.id)) selectedTransferIds.remove(item.id)
                                        else selectedTransferIds.add(item.id)
                                    } else if (localFile != null && localFile.exists()) {
                                        onOpenFile(localFile)
                                    }
                                },
                                onLongClick = {
                                    isSelectionMode = true
                                    if (!selectedTransferIds.contains(item.id)) {
                                        selectedTransferIds.add(item.id)
                                    }
                                }
                            )
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(12.dp)
                        ) {
                            if (isSelectionMode) {
                                Checkbox(
                                    checked = selectedTransferIds.contains(item.id),
                                    onCheckedChange = { checked ->
                                        if (checked) selectedTransferIds.add(item.id) else selectedTransferIds.remove(item.id)
                                    },
                                    colors = CheckboxDefaults.colors(checkedColor = Color(0xFF0066FF))
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                            }

                            if (localFile != null && localFile.exists()) {
                                FileThumbnailOrIcon(file = localFile)
                            } else {
                                FileCategoryIcon(extension = ext)
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    item.name,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = Color(0xFF0F172A),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    "${formatFileSize(item.size)} • ${formatFileDate(item.startTime)}",
                                    fontSize = 11.sp,
                                    color = Color(0xFF64748B)
                                )
                            }

                            // Status Pill Badge
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isSuccess) Color(0xFFDCFCE7) else Color(0xFFFEE2E2))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    if (isSuccess) "Completed" else "Failed",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSuccess) Color(0xFF16A34A) else Color(0xFFDC2626)
                                )
                            }

                            Spacer(modifier = Modifier.width(6.dp))

                            if (isSuccess && localFile != null && localFile.exists()) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFFF1F5F9))
                                        .clickable { onOpenFile(localFile) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    DexoIcon(
                                        imageVector = DexoIcons.Folder,
                                        contentDescription = "Open File",
                                        tint = Color(0xFF0F172A),
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                            }

                            if (!isSuccess && item.isOutgoing) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFEFF6FF))
                                        .clickable { onResumeTransfer(item.id) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    DexoIcon(
                                        imageVector = DexoIcons.Retry,
                                        contentDescription = "Retry",
                                        tint = Color(0xFF0066FF),
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                            }

                            // Direct Delete from History Button
                            IconButton(
                                onClick = { onDeleteTransfer(item.id) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                DexoIcon(
                                    imageVector = DexoIcons.Trash,
                                    contentDescription = "Delete",
                                    tint = Color(0xFF64748B),
                                    modifier = Modifier.size(14.dp)
                                )
                            }

                            // 3-dots menu button
                            Box {
                                IconButton(
                                    onClick = { showItemMenu = true },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    DexoIcon(
                                        imageVector = DexoIcons.MoreVert,
                                        contentDescription = "More Options",
                                        tint = Color(0xFF64748B),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                DropdownMenu(
                                    expanded = showItemMenu,
                                    onDismissRequest = { showItemMenu = false },
                                    modifier = Modifier.background(Color.White)
                                ) {
                                    if (localFile != null && localFile.exists()) {
                                        DropdownMenuItem(
                                            text = { Text("Open File", color = Color(0xFF0F172A)) },
                                            onClick = {
                                                showItemMenu = false
                                                onOpenFile(localFile)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Share with All Apps...", color = Color(0xFF0F172A)) },
                                            onClick = {
                                                showItemMenu = false
                                                onShareFile(localFile)
                                            }
                                        )
                                    }
                                    if (!isSuccess) {
                                        DropdownMenuItem(
                                            text = { Text("Retry Transfer", color = Color(0xFF0066FF)) },
                                            onClick = {
                                                showItemMenu = false
                                                onResumeTransfer(item.id)
                                            }
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text("Delete from History", color = Color(0xFFDC2626)) },
                                        onClick = {
                                            showItemMenu = false
                                            onDeleteTransfer(item.id)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("Delete Selected History?", fontWeight = FontWeight.Bold, color = Color(0xFF0F172A)) },
            text = {
                Text(
                    "Remove ${selectedTransferIds.size} item(s) from transfer history?\n\nOriginal files on this device and your Mac will NOT be deleted, and paired devices will stay connected.",
                    fontSize = 13.sp,
                    color = Color(0xFF475569)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteTransfers(selectedTransferIds.toSet())
                        selectedTransferIds.clear()
                        isSelectionMode = false
                        showDeleteConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                ) {
                    Text("Delete", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("Cancel", color = Color(0xFF64748B))
                }
            }
        )
    }

    if (showClearAllConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllConfirmDialog = false },
            title = { Text("Clear All Transfer History?", fontWeight = FontWeight.Bold, color = Color(0xFF0F172A)) },
            text = {
                Text(
                    "Clear all transfer history entries?\n\nOriginal files on this device and your Mac will NOT be deleted, and paired devices will stay connected.",
                    fontSize = 13.sp,
                    color = Color(0xFF475569)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onClearTransfers()
                        selectedTransferIds.clear()
                        isSelectionMode = false
                        showClearAllConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                ) {
                    Text("Clear All", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllConfirmDialog = false }) {
                    Text("Cancel", color = Color(0xFF64748B))
                }
            }
        )
    }
}

// Settings Tab Screen Content

@Composable
fun SettingsTabContent(
    pairedDevice: TrustedDevice?,
    localDeviceName: String,
    syncMode: ClipboardSyncMode,
    isScanning: Boolean,
    onToggleScan: () -> Unit,
    onModeSelected: (ClipboardSyncMode) -> Unit,
    onOpenSyncModeDialog: () -> Unit,
    onUnpair: () -> Unit,
    onOpenRenameLocal: () -> Unit,
    onOpenRenamePaired: () -> Unit,
    onShowSecurity: () -> Unit,
    onSendPing: () -> Unit,
    onOpenClipboardHistory: () -> Unit = {},
    isEnhancedClipboardEnabled: Boolean = false,
    onToggleEnhancedClipboard: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val settings = remember { com.macdroid.storage.DexoLinkSettings.getInstance(context) }
    val quickFileSharingEnabled by settings.quickFileSharing.collectAsState()
    val notificationsEnabled by settings.notificationsEnabled.collectAsState()
    val largeFilesSyncEnabled by settings.largeFilesSync.collectAsState()
    val autoAcceptEnabled by settings.autoAcceptPaired.collectAsState()
    val autoDiscoveryEnabled by settings.autoDeviceDiscovery.collectAsState()
    val cameraSharingEnabled by settings.cameraSharing.collectAsState()
    val screenSharingEnabled by settings.screenSharing.collectAsState()
    val desktopModeEnabled by settings.desktopMode.collectAsState()
    var showEnhancedClipboardInfoDialog by remember { mutableStateOf(false) }
    var showCameraPermissionDialog by remember { mutableStateOf(false) }
    var showDesktopModeInfoDialog by remember { mutableStateOf(false) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            settings.setCameraSharing(true)
            Toast.makeText(context, "Camera permission granted. Camera Sharing is active.", Toast.LENGTH_SHORT).show()
        } else {
            settings.setCameraSharing(false)
            showCameraPermissionDialog = true
        }
    }

    val mediaProjectionManager = remember {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
    }
    val screenCaptureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            settings.setScreenSharing(true)
            Toast.makeText(context, "Screen capture permission granted. Screen Sharing is active.", Toast.LENGTH_SHORT).show()
        } else {
            settings.setScreenSharing(false)
            Toast.makeText(context, "Screen capture permission was cancelled.", Toast.LENGTH_SHORT).show()
        }
    }

    val coroutineScope = rememberCoroutineScope()
    val trustStore = remember { com.macdroid.storage.TrustStore.getInstance(context) }
    val multiPairedEnabled by settings.multiPairedDevices.collectAsState()
    val trustedList = remember(multiPairedEnabled, pairedDevice) { trustStore.getTrustedDevices() }
    val continuityManager = remember { com.macdroid.continuity.ContinuityManager.getInstance(context) }
    val isContinuityEnabled by continuityManager.isContinuityEnabled.collectAsState()
    val updateManager = remember { com.macdroid.core.UpdateManager.getInstance(context) }
    val updateStatus by updateManager.status.collectAsState()
    val currentAppVersion = remember { updateManager.getCurrentVersion() }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(key = "header_spacer") { Spacer(modifier = Modifier.height(4.dp)) }

        // Title Header
        item(key = "title_header") {
            Column {
                Text(
                    "Settings",
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp,
                    color = Color(0xFF0F172A)
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    "Customize features and security",
                    fontSize = 12.sp,
                    color = Color(0xFF64748B)
                )
            }
        }

        // Section 1: Features (Matching Reference Design Image 2)
        item(key = "section_features") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DexoIcon(
                        imageVector = DexoIcons.CategoryDoc,
                        contentDescription = null,
                        tint = Color(0xFF64748B),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "FEATURES",
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = Color(0xFF64748B),
                        letterSpacing = 0.5.sp
                    )
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        // 1. Clipboard Sync
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF0066FF)),
                                contentAlignment = Alignment.Center
                            ) {
                                DexoIcon(
                                    imageVector = DexoIcons.Clipboard,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Clipboard Sync", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFF0F172A))
                                Text("Sync text, links and images", fontSize = 11.sp, color = Color(0xFF64748B))
                            }
                            Switch(
                                checked = syncMode != ClipboardSyncMode.DISABLED,
                                onCheckedChange = { isChecked ->
                                    onModeSelected(if (isChecked) ClipboardSyncMode.TWO_WAY else ClipboardSyncMode.DISABLED)
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF0066FF))
                            )
                        }

                        // View Clipboard History row
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onOpenClipboardHistory() }
                                .padding(vertical = 4.dp, horizontal = 4.dp)
                        ) {
                            DexoIcon(
                                imageVector = DexoIcons.History,
                                contentDescription = null,
                                tint = Color(0xFF0066FF),
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "View Clipboard History",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF0066FF)
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            DexoIcon(
                                imageVector = DexoIcons.ChevronRight,
                                contentDescription = null,
                                tint = Color(0xFF64748B),
                                modifier = Modifier.size(14.dp)
                            )
                        }

                        Divider(color = Color(0xFFF1F5F9), modifier = Modifier.padding(vertical = 10.dp))

                        // Enhanced Clipboard - Independent ON/OFF Toggle (OFF by default)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF6366F1)),
                                contentAlignment = Alignment.Center
                            ) {
                                DexoIcon(
                                    imageVector = DexoIcons.Sparkles,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Enhanced Clipboard", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFF0F172A))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Color(0xFFEEF2FF))
                                            .padding(horizontal = 5.dp, vertical = 1.dp)
                                    ) {
                                        Text("BETA", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4F46E5))
                                    }
                                }
                                Text("Rich text & web paste compatibility", fontSize = 11.sp, color = Color(0xFF64748B))
                            }
                            Switch(
                                checked = isEnhancedClipboardEnabled,
                                onCheckedChange = { onToggleEnhancedClipboard(it) },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF6366F1))
                            )
                        }

                        // Compatibility & Limitations Info Clickable Row
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { showEnhancedClipboardInfoDialog = true }
                                .padding(vertical = 4.dp, horizontal = 4.dp)
                        ) {
                            DexoIcon(
                                imageVector = DexoIcons.Shield,
                                contentDescription = null,
                                tint = Color(0xFF6366F1),
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Compatibility, Security & Limitations",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF6366F1)
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            DexoIcon(
                                imageVector = DexoIcons.ChevronRight,
                                contentDescription = null,
                                tint = Color(0xFF64748B),
                                modifier = Modifier.size(14.dp)
                            )
                        }

                        Divider(color = Color(0xFFF1F5F9), modifier = Modifier.padding(vertical = 10.dp))

                        // 2. Quick File Sharing
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFFF59E0B)),
                                contentAlignment = Alignment.Center
                            ) {
                                DexoIcon(
                                    imageVector = DexoIcons.Folder,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Quick File Sharing", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFF0F172A))
                                Text("Send and receive files", fontSize = 11.sp, color = Color(0xFF64748B))
                            }
                            Switch(
                                checked = quickFileSharingEnabled,
                                onCheckedChange = { settings.setQuickFileSharing(it) },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF0066FF))
                            )
                        }

                        Divider(color = Color(0xFFF1F5F9), modifier = Modifier.padding(vertical = 10.dp))

                        // 3. Auto Device Discovery
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFFA855F7)),
                                contentAlignment = Alignment.Center
                            ) {
                                DexoIcon(
                                    imageVector = DexoIcons.Search,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Auto Device Discovery", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFF0F172A))
                                Text("Find devices on local Wi-Fi", fontSize = 11.sp, color = Color(0xFF64748B))
                            }
                            Switch(
                                checked = autoDiscoveryEnabled,
                                onCheckedChange = { enabled ->
                                    settings.setAutoDeviceDiscovery(enabled)
                                    if (enabled != isScanning) {
                                        onToggleScan()
                                    }
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF0066FF))
                            )
                        }

                        Divider(color = Color(0xFFF1F5F9), modifier = Modifier.padding(vertical = 10.dp))

                        // Continuity (Handoff) - Independent ON/OFF Toggle
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF06B6D4)),
                                contentAlignment = Alignment.Center
                            ) {
                                DexoIcon(
                                    imageVector = DexoIcons.Sync,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Continuity (Handoff)", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFF0F172A))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Surface(
                                        color = if (isContinuityEnabled) Color(0xFFE0F7FA) else Color(0xFFF1F5F9),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            if (isContinuityEnabled) "ON" else "OFF",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = if (isContinuityEnabled) Color(0xFF00838F) else Color(0xFF64748B),
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                                Text("Continue reading PDFs, docs & browsing across devices", fontSize = 11.sp, color = Color(0xFF64748B))
                            }
                            Switch(
                                checked = isContinuityEnabled,
                                onCheckedChange = { continuityManager.setContinuityEnabled(it) },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF06B6D4))
                            )
                        }

                        Divider(color = Color(0xFFF1F5F9), modifier = Modifier.padding(vertical = 10.dp))

                        // 4. Show Notifications
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFFEF4444)),
                                contentAlignment = Alignment.Center
                            ) {
                                DexoIcon(
                                    imageVector = DexoIcons.Bell,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Show Notifications", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFF0F172A))
                                Text("Pairing and transfer alerts", fontSize = 11.sp, color = Color(0xFF64748B))
                            }
                            Switch(
                                checked = notificationsEnabled,
                                onCheckedChange = { settings.setNotificationsEnabled(it) },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF0066FF))
                            )
                        }
                    }
                }
            }
        }

        // Section 2: Sync Options (Matching Reference Design Image 2)
        item(key = "section_sync_options") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DexoIcon(
                        imageVector = DexoIcons.Sync,
                        contentDescription = null,
                        tint = Color(0xFF64748B),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "SYNC OPTIONS",
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = Color(0xFF64748B),
                        letterSpacing = 0.5.sp
                    )
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        // 1. Sync Direction
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenSyncModeDialog() }
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF0066FF)),
                                contentAlignment = Alignment.Center
                            ) {
                                DexoIcon(
                                    imageVector = DexoIcons.Clipboard,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                "Sync Direction",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = Color(0xFF0F172A),
                                modifier = Modifier.weight(1f)
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    syncMode.title,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF0066FF)
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                DexoIcon(
                                    imageVector = DexoIcons.ChevronRight,
                                    contentDescription = null,
                                    tint = Color(0xFF0066FF),
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }

                        Divider(color = Color(0xFFF1F5F9), modifier = Modifier.padding(vertical = 10.dp))

                        // 2. Sync Large Files (up to 10 GB)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF10B981)),
                                contentAlignment = Alignment.Center
                            ) {
                                DexoIcon(
                                    imageVector = DexoIcons.ArrowDown,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Sync Large Files (up to 10 GB)", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFF0F172A))
                                Text("Enable high-speed transfer", fontSize = 11.sp, color = Color(0xFF64748B))
                            }
                            Switch(
                                checked = largeFilesSyncEnabled,
                                onCheckedChange = { settings.setLargeFilesSync(it) },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF0066FF))
                            )
                        }

                        Divider(color = Color(0xFFF1F5F9), modifier = Modifier.padding(vertical = 10.dp))

                        // 3. Auto Accept from Paired Device
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF64748B)),
                                contentAlignment = Alignment.Center
                            ) {
                                DexoIcon(
                                    imageVector = DexoIcons.Shield,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Auto Accept from Paired Device", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFF0F172A))
                                Text("Only from trusted device", fontSize = 11.sp, color = Color(0xFF64748B))
                            }
                            Switch(
                                checked = autoAcceptEnabled,
                                onCheckedChange = { settings.setAutoAcceptPaired(it) },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF0066FF))
                            )
                        }
                    }
                }
            }
        }

        // Section 3: Media Streaming & Remote Control (Matching Reference Design Image 2)
        item(key = "section_media_streaming") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DexoIcon(
                        imageVector = DexoIcons.CategoryVideo,
                        contentDescription = null,
                        tint = Color(0xFF64748B),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "MEDIA STREAMING & REMOTE CONTROL",
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = Color(0xFF64748B),
                        letterSpacing = 0.5.sp
                    )
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        // 1. Camera Sharing
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFFEC4899)),
                                contentAlignment = Alignment.Center
                            ) {
                                DexoIcon(
                                    imageVector = DexoIcons.CategoryVideo,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Camera Sharing", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFF0F172A))
                                    if (!cameraSharingEnabled) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Surface(
                                            color = Color(0xFFF1F5F9),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                "OFF",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = Color(0xFF64748B),
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                }
                                Text(
                                    "Stream camera to another device",
                                    fontSize = 11.sp,
                                    color = Color(0xFF64748B)
                                )
                            }
                            Switch(
                                checked = cameraSharingEnabled,
                                onCheckedChange = { enabled ->
                                    if (enabled) {
                                        val hasCam = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                                        if (hasCam) {
                                            settings.setCameraSharing(true)
                                            Toast.makeText(context, "Camera Sharing active", Toast.LENGTH_SHORT).show()
                                        } else {
                                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                        }
                                    } else {
                                        settings.setCameraSharing(false)
                                    }
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF0066FF))
                            )
                        }

                        Divider(color = Color(0xFFF1F5F9), modifier = Modifier.padding(vertical = 10.dp))

                        // 2. Screen Sharing
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF3B82F6)),
                                contentAlignment = Alignment.Center
                            ) {
                                DexoIcon(
                                    imageVector = DexoIcons.DeviceMac,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Screen Sharing", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFF0F172A))
                                    if (!screenSharingEnabled) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Surface(
                                            color = Color(0xFFF1F5F9),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                "OFF",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = Color(0xFF64748B),
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                }
                                Text(
                                    "Mirror screen to another device",
                                    fontSize = 11.sp,
                                    color = Color(0xFF64748B)
                                )
                            }
                            Switch(
                                checked = screenSharingEnabled,
                                onCheckedChange = { enabled ->
                                    if (enabled) {
                                        if (mediaProjectionManager != null) {
                                            screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
                                        } else {
                                            settings.setScreenSharing(true)
                                        }
                                    } else {
                                        settings.setScreenSharing(false)
                                    }
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF0066FF))
                            )
                        }

                        Divider(color = Color(0xFFF1F5F9), modifier = Modifier.padding(vertical = 10.dp))

                        // 3. Desktop Mode (beta)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF10B981)),
                                contentAlignment = Alignment.Center
                            ) {
                                DexoIcon(
                                    imageVector = DexoIcons.Globe,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Desktop Mode", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFF0F172A))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Color(0xFFDBEAFE))
                                            .padding(horizontal = 5.dp, vertical = 1.dp)
                                    ) {
                                        Text("BETA", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1D4ED8))
                                    }
                                    if (!desktopModeEnabled) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Surface(
                                            color = Color(0xFFF1F5F9),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                "OFF",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = Color(0xFF64748B),
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                }
                                Text(
                                    "Remote desktop viewing & control",
                                    fontSize = 11.sp,
                                    color = Color(0xFF64748B)
                                )
                            }
                            Switch(
                                checked = desktopModeEnabled,
                                onCheckedChange = { enabled ->
                                    if (enabled) {
                                        settings.setDesktopMode(true)
                                        showDesktopModeInfoDialog = true
                                    } else {
                                        settings.setDesktopMode(false)
                                    }
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF0066FF))
                            )
                        }
                    }
                }
            }
        }

        // Section 4: Security (Matching Reference Design Image 2)
        item(key = "section_security") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DexoIcon(
                        imageVector = DexoIcons.Shield,
                        contentDescription = null,
                        tint = Color(0xFF64748B),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "SECURITY",
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = Color(0xFF64748B),
                        letterSpacing = 0.5.sp
                    )
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        // 1. Multiple Paired Devices
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF8B5CF6)),
                                contentAlignment = Alignment.Center
                            ) {
                                DexoIcon(
                                    imageVector = DexoIcons.Lock,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Multiple Paired Devices", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFF0F172A))
                                Text(
                                    "Connect multiple devices simultaneously",
                                    fontSize = 11.sp,
                                    color = Color(0xFF64748B)
                                )
                            }
                            Switch(
                                checked = multiPairedEnabled,
                                onCheckedChange = { settings.setMultiPairedDevices(it) },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF0066FF))
                            )
                        }

                        Divider(color = Color(0xFFF1F5F9), modifier = Modifier.padding(vertical = 10.dp))

                        // 2. Require Pairing Approval
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF0066FF)),
                                contentAlignment = Alignment.Center
                            ) {
                                DexoIcon(
                                    imageVector = DexoIcons.Key,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Require Pairing Approval", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFF0F172A))
                                Text("Ask before connecting", fontSize = 11.sp, color = Color(0xFF64748B))
                            }
                            Switch(
                                checked = true,
                                onCheckedChange = {},
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF0066FF))
                            )
                        }

                        Divider(color = Color(0xFFF1F5F9), modifier = Modifier.padding(vertical = 10.dp))

                        // 3. Paired Devices List & Device-Specific Unpair
                        if (trustedList.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Trusted Paired Devices (${trustedList.size})", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Color(0xFF64748B))
                                for (device in trustedList) {
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                                        border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text(
                                                    device.deviceName,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 13.sp,
                                                    color = Color(0xFF0F172A)
                                                )
                                                Spacer(modifier = Modifier.weight(1f))
                                                Surface(
                                                    color = Color(0xFFDCFCE7),
                                                    shape = RoundedCornerShape(4.dp)
                                                ) {
                                                    Text(
                                                        "Verified",
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color(0xFF16A34A),
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                            Text(
                                                "TrustStore • trusted device",
                                                fontSize = 11.sp,
                                                color = Color(0xFF64748B)
                                            )
                                            Spacer(modifier = Modifier.height(10.dp))
                                            if (device.deviceId == pairedDevice?.deviceId) {
                                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                                    OutlinedButton(
                                                        onClick = onOpenRenamePaired,
                                                        shape = RoundedCornerShape(8.dp),
                                                        modifier = Modifier.weight(1f),
                                                        contentPadding = PaddingValues(vertical = 4.dp)
                                                    ) {
                                                        Text("Rename", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF0F172A))
                                                    }
                                                    Surface(
                                                        color = Color(0xFFFEF2F2),
                                                        border = BorderStroke(1.dp, Color(0xFFFECACA)),
                                                        shape = RoundedCornerShape(8.dp),
                                                        modifier = Modifier
                                                            .weight(1f)
                                                            .clickable {
                                                                trustStore.unpairDevice(device.deviceId)
                                                                onUnpair()
                                                            }
                                                    ) {
                                                        Box(
                                                            contentAlignment = Alignment.Center,
                                                            modifier = Modifier.padding(vertical = 8.dp)
                                                        ) {
                                                            Text("Unpair", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFEF4444))
                                                        }
                                                    }
                                                }
                                            } else {
                                                Surface(
                                                    color = Color(0xFFFEF2F2),
                                                    border = BorderStroke(1.dp, Color(0xFFFECACA)),
                                                    shape = RoundedCornerShape(8.dp),
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clickable {
                                                            trustStore.unpairDevice(device.deviceId)
                                                            onUnpair()
                                                        }
                                                ) {
                                                    Box(
                                                        contentAlignment = Alignment.Center,
                                                        modifier = Modifier.padding(vertical = 8.dp)
                                                    ) {
                                                        Text("Unpair", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFEF4444))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            Text(
                                if (multiPairedEnabled) "No devices currently paired" else "No device currently paired • Strict 1-Device Policy active",
                                fontSize = 12.sp,
                                color = Color(0xFF64748B)
                            )
                        }

                        Divider(color = Color(0xFFF1F5F9), modifier = Modifier.padding(vertical = 10.dp))

                        // 4. Battery & Power Optimization
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Battery & Doze Optimization",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = Color(0xFF0F172A)
                                )
                                Text(
                                    "Allow background sync during sleep",
                                    fontSize = 11.sp,
                                    color = Color(0xFF64748B)
                                )
                            }
                            OutlinedButton(
                                onClick = {
                                    try {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                            val intent = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                            context.startActivity(intent)
                                        }
                                    } catch (e: Exception) {
                                        // Ignore
                                    }
                                },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text("Configure", fontSize = 11.sp, color = Color(0xFF0066FF))
                            }
                        }

                        Divider(color = Color(0xFFF1F5F9), modifier = Modifier.padding(vertical = 10.dp))

                        // 5. This Device Identity
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "This Device",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = Color(0xFF0F172A)
                                )
                                Text(
                                    "$localDeviceName • broadcast on local Wi-Fi",
                                    fontSize = 11.sp,
                                    color = Color(0xFF64748B)
                                )
                            }
                            OutlinedButton(
                                onClick = onOpenRenameLocal,
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text("Rename", fontSize = 11.sp, color = Color(0xFF0066FF))
                            }
                        }

                        Divider(color = Color(0xFFF1F5F9), modifier = Modifier.padding(vertical = 10.dp))

                        // 6. Network Latency & Diagnostics
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Test Connection Latency",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = Color(0xFF0F172A)
                                )
                                Text(
                                    "Round-trip ping to paired device",
                                    fontSize = 11.sp,
                                    color = Color(0xFF64748B)
                                )
                            }
                            OutlinedButton(
                                onClick = onSendPing,
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text("Ping Mac", fontSize = 11.sp, color = Color(0xFF0066FF))
                            }
                        }
                    }
                }
            }
        }

        // Section 5: Software Update (Phase 5)
        item(key = "section_software_update") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DexoIcon(
                        imageVector = DexoIcons.Refresh,
                        contentDescription = null,
                        tint = Color(0xFF64748B),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "SOFTWARE UPDATE",
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = Color(0xFF64748B),
                        letterSpacing = 0.5.sp
                    )
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        // Current version & Status header
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF0284C7)),
                                contentAlignment = Alignment.Center
                            ) {
                                DexoIcon(
                                    imageVector = DexoIcons.Refresh,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("DexoLink for Android", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFF0F172A))
                                Text("Installed: v$currentAppVersion", fontSize = 11.sp, color = Color(0xFF64748B))
                            }

                            // Dynamic status badge
                            when (val status = updateStatus) {
                                is com.macdroid.core.UpdateStatus.UpToDate -> {
                                    Surface(
                                        color = Color(0xFFDCFCE7),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            "Up to date",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF16A34A),
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                                is com.macdroid.core.UpdateStatus.Available -> {
                                    Surface(
                                        color = Color(0xFFE0F2FE),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            "v${status.info.latestVersion} Available",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF0284C7),
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                                is com.macdroid.core.UpdateStatus.Checking -> {
                                    Surface(
                                        color = Color(0xFFFEF3C7),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            "Checking...",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFFD97706),
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                                is com.macdroid.core.UpdateStatus.Downloading -> {
                                    Surface(
                                        color = Color(0xFFF3E8FF),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            "${(status.progressFraction * 100).toInt()}%",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF7C3AED),
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                                is com.macdroid.core.UpdateStatus.Verifying -> {
                                    Surface(
                                        color = Color(0xFFEEF2FF),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            "Verifying SHA-256",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF4F46E5),
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                                is com.macdroid.core.UpdateStatus.ReadyToInstall -> {
                                    Surface(
                                        color = Color(0xFFDCFCE7),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            "Ready to install",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF16A34A),
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                                is com.macdroid.core.UpdateStatus.Error -> {
                                    Surface(
                                        color = Color(0xFFFEE2E2),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            "Check Failed",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFFDC2626),
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                                else -> {
                                    Surface(
                                        color = Color(0xFFF1F5F9),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            "GitHub Releases",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF64748B),
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Progress bar if downloading
                        val status = updateStatus
                        if (status is com.macdroid.core.UpdateStatus.Downloading) {
                            Spacer(modifier = Modifier.height(10.dp))
                            LinearProgressIndicator(
                                progress = status.progressFraction,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = Color(0xFF0066FF),
                                trackColor = Color(0xFFE2E8F0)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(
                                    "${status.bytesDownloaded / (1024 * 1024)} MB of ${if (status.totalBytes > 0) "${status.totalBytes / (1024 * 1024)} MB" else "..."}",
                                    fontSize = 11.sp,
                                    color = Color(0xFF64748B)
                                )
                                Text(
                                    "${(status.progressFraction * 100).toInt()}%",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF0066FF)
                                )
                            }
                        }

                        // Release Notes snippet if update is available
                        if (status is com.macdroid.core.UpdateStatus.Available) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Surface(
                                color = Color(0xFFF8FAFC),
                                border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(
                                        status.info.releaseTitle,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = Color(0xFF0F172A)
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        status.info.releaseNotes.take(180) + if (status.info.releaseNotes.length > 180) "..." else "",
                                        fontSize = 11.sp,
                                        color = Color(0xFF475569)
                                    )
                                }
                            }
                        }

                        // Action buttons
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedButton(
                                onClick = {
                                    coroutineScope.launch {
                                        updateManager.checkForUpdates()
                                    }
                                },
                                enabled = status !is com.macdroid.core.UpdateStatus.Checking && status !is com.macdroid.core.UpdateStatus.Downloading,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(vertical = 8.dp)
                            ) {
                                Text(
                                    if (status is com.macdroid.core.UpdateStatus.Checking) "Checking..." else "Check for Updates",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF0066FF)
                                )
                            }

                            if (status is com.macdroid.core.UpdateStatus.Available) {
                                Button(
                                    onClick = {
                                        coroutineScope.launch {
                                            val apk = updateManager.downloadAndVerifyApk(status.info)
                                            if (apk != null) {
                                                updateManager.installApk(apk)
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0066FF)),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(vertical = 8.dp)
                                ) {
                                    Text(
                                        "Update Now",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                            } else if (status is com.macdroid.core.UpdateStatus.ReadyToInstall) {
                                Button(
                                    onClick = {
                                        updateManager.installApk(status.apkFile)
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(vertical = 8.dp)
                                ) {
                                    Text(
                                        "Install APK",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Footer (Matching Reference Design Image 2)
        item(key = "footer") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Developed by Rahul Mahaseth | DexoLink v4.0.2",
                    fontSize = 11.sp,
                    color = Color(0xFF94A3B8),
                    textAlign = TextAlign.Center
                )
            }
        }

        item(key = "footer_spacer") { Spacer(modifier = Modifier.height(20.dp)) }
    }

    if (showEnhancedClipboardInfoDialog) {
        AlertDialog(
            onDismissRequest = { showEnhancedClipboardInfoDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF6366F1)),
                        contentAlignment = Alignment.Center
                    ) {
                        DexoIcon(
                            imageVector = DexoIcons.Sparkles,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        "Enhanced Clipboard",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = Color(0xFF0F172A)
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "Enhanced Clipboard improves cross-device copy-paste across web browsers, rich-text editors, and restricted web inputs while strictly preserving system security.",
                        fontSize = 12.sp,
                        color = Color(0xFF475569),
                        lineHeight = 17.sp
                    )

                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                        border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("1. Multi-Representation Output", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFF0F172A))
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                "Sets both clean plain text and rich HTML (text/html) on the clipboard. Editors like Google Docs, Notion, Slack, and Samsung Notes receive proper paragraph structure without collapsing lines or losing formatting.",
                                fontSize = 11.sp,
                                color = Color(0xFF64748B),
                                lineHeight = 15.sp
                            )
                        }
                    }

                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                        border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("2. Sanitization & Normalization", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFF0F172A))
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                "Normalizes Windows/Mac carriage returns (\\r\\n -> \\n) and strips invisible zero-width characters (\\u200B, \\uFEFF) that cause web form validation failures and double-spaced inputs.",
                                fontSize = 11.sp,
                                color = Color(0xFF64748B),
                                lineHeight = 15.sp
                            )
                        }
                    }

                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                        border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("3. Alternative Paste Workflow", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFF0F172A))
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                "When websites block standard pasting via onpaste event listeners or browser gesture restrictions, use the DexoLink Paste Assistant notification or Share-to-App action to deliver text directly.",
                                fontSize = 11.sp,
                                color = Color(0xFF64748B),
                                lineHeight = 15.sp
                            )
                        }
                    }

                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF2F2)),
                        border = BorderStroke(1.dp, Color(0xFFFEE2E2)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("Security Boundaries & Limitations", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFF991B1B))
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                "DexoLink does not defeat browser security controls, bypass authentication protections, or inject unauthorized scripts into web pages. Pure canvas-rendered interfaces (e.g. Figma canvas, Google Docs canvas mode) or secure OS PIN fields require manual keyboard input.",
                                fontSize = 11.sp,
                                color = Color(0xFFB91C1C),
                                lineHeight = 15.sp
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showEnhancedClipboardInfoDialog = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF6366F1))
                ) {
                    Text("Got it", fontWeight = FontWeight.Bold)
                }
            },
            containerColor = Color.White,
            shape = RoundedCornerShape(16.dp)
        )
    }

    if (showCameraPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showCameraPermissionDialog = false },
            containerColor = Color.White,
            shape = RoundedCornerShape(16.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFFFCE7F3)),
                        contentAlignment = Alignment.Center
                    ) {
                        DexoIcon(imageVector = DexoIcons.CategoryVideo, contentDescription = null, tint = Color(0xFFEC4899), modifier = Modifier.size(16.dp))
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Camera Permission Required", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Color(0xFF0F172A))
                }
            },
            text = {
                Text(
                    "DexoLink requires Camera permission to stream your phone's live video feed to your Mac or PC. Please enable Camera access in App Settings.",
                    fontSize = 12.sp,
                    color = Color(0xFF475569),
                    lineHeight = 17.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showCameraPermissionDialog = false
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                        context.startActivity(intent)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEC4899)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Open App Settings", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCameraPermissionDialog = false }) {
                    Text("Cancel", color = Color(0xFF64748B))
                }
            }
        )
    }

    if (showDesktopModeInfoDialog) {
        AlertDialog(
            onDismissRequest = { showDesktopModeInfoDialog = false },
            containerColor = Color.White,
            shape = RoundedCornerShape(16.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFFD1FAE5)),
                        contentAlignment = Alignment.Center
                    ) {
                        DexoIcon(imageVector = DexoIcons.Globe, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(16.dp))
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Desktop Mode (Beta)", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Color(0xFF0F172A))
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Desktop Mode lets you view and control your connected Mac or Windows computer directly from your Android phone with an interactive touchpad and keyboard.",
                        fontSize = 12.sp,
                        color = Color(0xFF475569),
                        lineHeight = 17.sp
                    )
                    Text(
                        "• Host control on macOS requires Accessibility permission (DexoLink prompts automatically on your Mac).\n• Tap the Desktop Mode card on Home anytime to start an interactive remote control session.",
                        fontSize = 11.sp,
                        color = Color(0xFF64748B),
                        lineHeight = 16.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { showDesktopModeInfoDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Ready to Use", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}

// Floating Bottom Navigation Bar matching Mobile UI Design
@Composable
fun DexoLinkBottomNavBar(
    selectedTab: NavTab,
    activeTransfersCount: Int,
    onTabSelected: (NavTab) -> Unit,
    onCenterPlusClick: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = Color.White,
            shape = RoundedCornerShape(36.dp),
            shadowElevation = 14.dp,
            border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 1. Home
                FloatingNavTabItem(
                    title = "Home",
                    icon = DexoIcons.Home,
                    isSelected = selectedTab == NavTab.HOME,
                    onClick = { onTabSelected(NavTab.HOME) }
                )

                // 2. Files
                FloatingNavTabItem(
                    title = "Files",
                    icon = DexoIcons.Folder,
                    isSelected = selectedTab == NavTab.FILES,
                    onClick = { onTabSelected(NavTab.FILES) }
                )

                // 3. Center Elevated Plus Button
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF2563EB))
                        .clickable { onCenterPlusClick() },
                    contentAlignment = Alignment.Center
                ) {
                    DexoIcon(
                        imageVector = DexoIcons.Plus,
                        contentDescription = "Send File",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }

                // 4. Transfers
                FloatingNavTabItem(
                    title = "Transfers",
                    icon = DexoIcons.Transfer,
                    isSelected = selectedTab == NavTab.TRANSFERS,
                    badge = if (activeTransfersCount > 0) activeTransfersCount.toString() else null,
                    onClick = { onTabSelected(NavTab.TRANSFERS) }
                )

                // 5. Settings
                FloatingNavTabItem(
                    title = "Settings",
                    icon = DexoIcons.Settings,
                    isSelected = selectedTab == NavTab.SETTINGS,
                    onClick = { onTabSelected(NavTab.SETTINGS) }
                )
            }
        }
    }
}

@Composable
fun FloatingNavTabItem(
    title: String,
    icon: ImageVector,
    isSelected: Boolean,
    badge: String? = null,
    onClick: () -> Unit
) {
    val activeColor = Color(0xFF2563EB)
    val inactiveColor = Color(0xFF64748B)
    val color = if (isSelected) activeColor else inactiveColor

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            DexoIcon(
                imageVector = icon,
                contentDescription = title,
                tint = color,
                modifier = Modifier.size(22.dp)
            )
            if (badge != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 8.dp, y = (-4).dp)
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFEF4444)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        badge,
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            title,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = color
        )
    }
}


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

fun formatEta(seconds: Long): String {
    if (seconds <= 0L) return "00:00:00"
    return if (seconds < 60L) {
        String.format(Locale.US, "00:00:%02d", seconds)
    } else if (seconds < 3600L) {
        val m = seconds / 60L
        val s = seconds % 60L
        String.format(Locale.US, "00:%02d:%02d", m, s)
    } else {
        val h = seconds / 3600L
        val m = (seconds % 3600L) / 60L
        val s = seconds % 60L
        String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }
}

fun formatFileDate(timestamp: Long): String {
    if (timestamp <= 0L) return "Recent"
    val now = Calendar.getInstance()
    val fileCal = Calendar.getInstance().apply { timeInMillis = timestamp }
    val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
    val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

    val isSameDay = now.get(Calendar.YEAR) == fileCal.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == fileCal.get(Calendar.DAY_OF_YEAR)
    val isYesterday = now.get(Calendar.YEAR) == fileCal.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) - fileCal.get(Calendar.DAY_OF_YEAR) == 1

    return when {
        isSameDay -> "Today, ${timeFormat.format(Date(timestamp))}"
        isYesterday -> "Yesterday, ${timeFormat.format(Date(timestamp))}"
        else -> dateFormat.format(Date(timestamp))
    }
}

// Clipboard History Dialog

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ClipboardHistoryDialog(
    history: List<ClipboardHistoryItem>,
    onDismiss: () -> Unit,
    onDeleteItem: (String) -> Unit,
    onDeleteItems: (Set<String>) -> Unit,
    onClearHistory: () -> Unit
) {
    val context = LocalContext.current
    var isSelectionMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<String>() }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showClearAllConfirmDialog by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(20.dp),
            color = Color.White,
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Clipboard History",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = Color(0xFF0F172A)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color(0xFFEFF6FF))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            "${history.size}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0066FF)
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        DexoIcon(
                            imageVector = DexoIcons.Close,
                            contentDescription = "Close",
                            tint = Color(0xFF64748B),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Toolbar / Selection Mode Row
                if (history.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        if (isSelectionMode) {
                            Text(
                                "${selectedIds.size} Selected",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = Color(0xFF0F172A)
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            TextButton(
                                onClick = {
                                    if (selectedIds.size == history.size) {
                                        selectedIds.clear()
                                    } else {
                                        selectedIds.clear()
                                        selectedIds.addAll(history.map { it.id })
                                    }
                                }
                            ) {
                                Text(
                                    if (selectedIds.size == history.size) "Deselect All" else "Select All",
                                    fontSize = 12.sp,
                                    color = Color(0xFF0066FF),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selectedIds.isNotEmpty()) Color(0xFFDC2626) else Color(0xFFCBD5E1))
                                    .clickable(enabled = selectedIds.isNotEmpty()) {
                                        showDeleteConfirmDialog = true
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    "Delete (${selectedIds.size})",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            TextButton(
                                onClick = {
                                    isSelectionMode = false
                                    selectedIds.clear()
                                }
                            ) {
                                Text("Cancel", fontSize = 12.sp, color = Color(0xFF64748B))
                            }
                        } else {
                            Text(
                                "Recent copied items",
                                fontSize = 12.sp,
                                color = Color(0xFF64748B)
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFFEFF6FF))
                                    .clickable { isSelectionMode = true }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    "Select",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF0066FF)
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFFFEE2E2))
                                    .clickable { showClearAllConfirmDialog = true }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    DexoIcon(
                                        imageVector = DexoIcons.Trash,
                                        contentDescription = null,
                                        tint = Color(0xFFDC2626),
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        "Clear All",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFDC2626)
                                    )
                                }
                            }
                        }
                    }
                    Divider(color = Color(0xFFF1F5F9), modifier = Modifier.padding(vertical = 4.dp))
                }

                if (history.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFEFF6FF)),
                                contentAlignment = Alignment.Center
                            ) {
                                DexoIcon(
                                    imageVector = DexoIcons.Clipboard,
                                    contentDescription = null,
                                    tint = Color(0xFF0066FF),
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                "No clipboard history yet",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = Color(0xFF0F172A)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Text and snippets synced between your phone and Mac will appear here.",
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
                        items(history, key = { it.id }) { item ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                                border = BorderStroke(
                                    1.dp,
                                    if (selectedIds.contains(item.id)) Color(0xFF0066FF) else Color(0xFFE2E8F0)
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {
                                            if (isSelectionMode) {
                                                if (selectedIds.contains(item.id)) selectedIds.remove(item.id)
                                                else selectedIds.add(item.id)
                                            }
                                        },
                                        onLongClick = {
                                            isSelectionMode = true
                                            if (!selectedIds.contains(item.id)) selectedIds.add(item.id)
                                        }
                                    )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (isSelectionMode) {
                                            Checkbox(
                                                checked = selectedIds.contains(item.id),
                                                onCheckedChange = { checked ->
                                                    if (checked) selectedIds.add(item.id) else selectedIds.remove(item.id)
                                                },
                                                colors = CheckboxDefaults.colors(checkedColor = Color(0xFF0066FF))
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                        }

                                        // Direction Pill
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(if (item.isOutgoing) Color(0xFFEFF6FF) else Color(0xFFDCFCE7))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                if (item.isOutgoing) "Sent to Mac" else "From Mac",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (item.isOutgoing) Color(0xFF0066FF) else Color(0xFF16A34A)
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            formatFileDate(item.timestamp),
                                            fontSize = 10.sp,
                                            color = Color(0xFF94A3B8)
                                        )

                                        Spacer(modifier = Modifier.weight(1f))

                                        // Copy action button
                                        Box(
                                            modifier = Modifier
                                                .size(28.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(Color.White)
                                                .clickable {
                                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                                    cm?.setPrimaryClip(android.content.ClipData.newPlainText("DexoLink", item.text))
                                                    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            DexoIcon(
                                                imageVector = DexoIcons.Clipboard,
                                                contentDescription = "Copy",
                                                tint = Color(0xFF0066FF),
                                                modifier = Modifier.size(13.dp)
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(6.dp))

                                        // Delete single action button
                                        Box(
                                            modifier = Modifier
                                                .size(28.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(Color(0xFFFEE2E2))
                                                .clickable { onDeleteItem(item.id) },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            DexoIcon(
                                                imageVector = DexoIcons.Trash,
                                                contentDescription = "Delete",
                                                tint = Color(0xFFDC2626),
                                                modifier = Modifier.size(13.dp)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))

                                    Text(
                                        text = item.text,
                                        fontSize = 12.sp,
                                        color = Color(0xFF1E293B),
                                        maxLines = 4,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Safety Note Footnote
                Text(
                    "Deleting history removes entries from this log. Your active pairing and files are unaffected.",
                    fontSize = 11.sp,
                    color = Color(0xFF94A3B8),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("Delete Selected Items?", fontWeight = FontWeight.Bold, color = Color(0xFF0F172A)) },
            text = {
                Text(
                    "Delete ${selectedIds.size} clipboard history item(s)? Original files and paired devices are not affected.",
                    fontSize = 13.sp,
                    color = Color(0xFF475569)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteItems(selectedIds.toSet())
                        selectedIds.clear()
                        isSelectionMode = false
                        showDeleteConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                ) {
                    Text("Delete", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("Cancel", color = Color(0xFF64748B))
                }
            }
        )
    }

    if (showClearAllConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllConfirmDialog = false },
            title = { Text("Clear Clipboard History?", fontWeight = FontWeight.Bold, color = Color(0xFF0F172A)) },
            text = {
                Text(
                    "Clear all clipboard history entries? This only removes saved snippets from history.",
                    fontSize = 13.sp,
                    color = Color(0xFF475569)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onClearHistory()
                        selectedIds.clear()
                        isSelectionMode = false
                        showClearAllConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                ) {
                    Text("Clear All", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllConfirmDialog = false }) {
                    Text("Cancel", color = Color(0xFF64748B))
                }
            }
        )
    }
}

// DexoLink Continuity: Handoff to Mac Dialog
@Composable
fun HandoffToMacDialog(
    onDismiss: () -> Unit,
    onSend: (com.macdroid.continuity.ContinuityActivity) -> Unit
) {
    var activityType by remember { mutableStateOf("webpage") }
    var title by remember { mutableStateOf("") }
    var urlOrPath by remember { mutableStateOf("") }
    var position by remember { mutableStateOf(1) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color.White,
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .padding(vertical = 16.dp),
            shadowElevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF06B6D4)),
                        contentAlignment = Alignment.Center
                    ) {
                        DexoIcon(
                            imageVector = DexoIcons.Send,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Continue on Mac",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = Color(0xFF0F172A)
                        )
                        Text(
                            "Seamlessly handoff activity to your Mac",
                            fontSize = 11.sp,
                            color = Color(0xFF64748B)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Type selector row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val types = listOf(
                        "webpage" to "Webpage",
                        "pdf" to "PDF",
                        "presentation" to "Slide",
                        "document" to "Doc"
                    )
                    types.forEach { (typeKey, typeLabel) ->
                        val isSelected = activityType == typeKey
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) Color(0xFF06B6D4) else Color(0xFFF1F5F9),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { activityType = typeKey }
                        ) {
                            Text(
                                text = typeLabel,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) Color.White else Color(0xFF475569),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text("Title", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF475569))
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    placeholder = {
                        Text(
                            if (activityType == "pdf") "e.g. DexoLink Guide.pdf" else "e.g. Wikipedia article",
                            fontSize = 13.sp
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    if (activityType == "webpage") "Web URL" else "File URL / Name",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF475569)
                )
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = urlOrPath,
                    onValueChange = { urlOrPath = it },
                    placeholder = { Text("https://example.com/page", fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )

                if (activityType == "pdf" || activityType == "presentation" || activityType == "document") {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (activityType == "presentation") "Slide Number" else "Page Number",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF475569)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { if (position > 1) position-- }) {
                                Text("-", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            }
                            Text(
                                "$position",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                            IconButton(onClick = { position++ }) {
                                Text("+", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            val trimmedUrl = urlOrPath.trim()
                            val resolvedTitle = title.trim().ifEmpty {
                                Uri.parse(trimmedUrl).lastPathSegment ?: "DexoLink Activity"
                            }
                            val act = com.macdroid.continuity.ContinuityActivity(
                                activityId = UUID.randomUUID().toString(),
                                activityType = activityType,
                                title = resolvedTitle,
                                subtitle = if (activityType == "webpage") (Uri.parse(trimmedUrl).host ?: "") else "Page $position",
                                url = trimmedUrl,
                                contentPayload = null,
                                position = position,
                                progress = 0.0,
                                timestamp = System.currentTimeMillis(),
                                sourceDevice = Build.MODEL ?: "Android Phone"
                            )
                            onSend(act)
                        },
                        enabled = urlOrPath.isNotBlank(),
                        modifier = Modifier.weight(1.3f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF06B6D4))
                    ) {
                        Text("Continue on Mac", fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
        }
    }
}


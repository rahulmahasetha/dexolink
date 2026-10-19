package com.macdroid.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.macdroid.MacDroidApplication
import com.macdroid.R
import com.macdroid.core.TransferEventListener
import com.macdroid.core.TransferItem
import com.macdroid.core.TransferStatus
import com.macdroid.networking.*
import com.macdroid.storage.TrustStore
import com.macdroid.ui.MainActivity
import java.util.Locale

class MacDroidService : Service(), ConnectionEventListener, DiscoveryListener, TransferEventListener {

    companion object {
        private const val TAG = "MacDroidService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "macdroid_foreground_service"
        private const val CHANNEL_NAME = "DexoLink Service"

        const val ACTION_START = "com.macdroid.action.START"
        const val ACTION_STOP = "com.macdroid.action.STOP"
        const val ACTION_DISCONNECT = "com.macdroid.action.DISCONNECT"
        const val ACTION_CONNECT = "com.macdroid.action.CONNECT"
        const val ACTION_ACCEPT_PAIRING = "com.macdroid.action.ACCEPT_PAIRING"
        const val ACTION_REJECT_PAIRING = "com.macdroid.action.REJECT_PAIRING"
        const val ACTION_CANCEL_TRANSFER = "com.macdroid.action.CANCEL_TRANSFER"

        const val EXTRA_HOST = "extra_host"
        const val EXTRA_PORT = "extra_port"
        const val EXTRA_TRANSFER_ID = "extra_transfer_id"

        @Volatile
        var instance: MacDroidService? = null
            private set

        @Volatile
        private var isPendingStart = false

        @Volatile
        private var isPendingStop = false

        fun isRunning(): Boolean = (instance != null || isPendingStart) && !isPendingStop

        fun start(context: Context) {
            isPendingStart = true
            isPendingStop = false
            DexoLinkTileService.onServiceStateChanged()

            try {
                val intent = Intent(context, MacDroidService::class.java).apply {
                    action = ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to startForegroundService, fallback startService: ${e.message}")
                try {
                    val fallbackIntent = Intent(context, MacDroidService::class.java).apply {
                        action = ACTION_START
                    }
                    context.startService(fallbackIntent)
                } catch (inner: Exception) {
                    Log.e(TAG, "Failed to start service: ${inner.message}")
                    isPendingStart = false
                    DexoLinkTileService.onServiceStateChanged()
                }
            }
        }

        fun stop(context: Context) {
            isPendingStop = true
            isPendingStart = false
            DexoLinkTileService.onServiceStateChanged()

            try {
                val intent = Intent(context, MacDroidService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop service: ${e.message}")
                isPendingStop = false
                DexoLinkTileService.onServiceStateChanged()
            }
        }

        val connectionManager: ConnectionManager
            get() = MacDroidApplication.connectionManager

        val discoveryService: DiscoveryService
            get() = MacDroidApplication.discoveryService
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var notificationManager: NotificationManager? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastProgressNotificationTime = 0L

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        onPrimaryClipChanged()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        isPendingStart = false
        isPendingStop = false
        DexoLinkTileService.onServiceStateChanged()
        DexoLinkTileService.requestUpdate(this)
        Log.d(TAG, "MacDroidService onCreate")

        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        createNotificationChannel()

        connectionManager.addListener(this@MacDroidService)
        discoveryService.addListener(this@MacDroidService)
        connectionManager.transferManager.addListener(this@MacDroidService)

        // Start socket server for incoming Mac connections & advertise
        try {
            val port = connectionManager.startServer(0)
            if (port > 0) {
                discoveryService.startAdvertising(port)
            }
            discoveryService.startBrowsing()
        } catch (e: Exception) {
            Log.e(TAG, "Error starting server or discovery: ${e.message}", e)
        }

        // Register clipboard listener (sync runs quietly without toasts)
        try {
            connectionManager.clipboardManager.addClipChangedListener(clipListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error adding clipboard listener: ${e.message}", e)
        }

        registerNetworkMonitoring()

        // Auto-reconnect to last known Mac if available
        val lastHost = connectionManager.lastConnectedHost
        val lastPort = connectionManager.lastConnectedPort
        if (!lastHost.isNullOrBlank() && lastPort > 0) {
            connectionManager.connect(lastHost, lastPort)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        Log.d(TAG, "onStartCommand action: $action")

        // Start foreground immediately with one stable active notification
        try {
            val notification = buildActiveNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                }
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not start foreground: ${e.message}", e)
        }

        when (action) {
            ACTION_STOP, ACTION_DISCONNECT -> {
                connectionManager.disconnect(disableAutoReconnect = true)
                discoveryService.stopAll()
                stopForeground(STOP_FOREGROUND_REMOVE)
                notificationManager?.cancel(NOTIFICATION_ID)
                notificationManager?.cancelAll()
                stopSelf()
            }

            ACTION_CONNECT -> {
                val host = intent?.getStringExtra(EXTRA_HOST)
                val port = intent?.getIntExtra(EXTRA_PORT, 52840) ?: 52840
                if (!host.isNullOrBlank()) {
                    connectionManager.connect(host, port)
                }
            }

            ACTION_ACCEPT_PAIRING -> {
                connectionManager.confirmPairing()
                syncTransferNotification()
            }

            ACTION_REJECT_PAIRING -> {
                connectionManager.rejectPairing()
                syncTransferNotification()
            }

            ACTION_CANCEL_TRANSFER -> {
                val transferId = intent?.getStringExtra(EXTRA_TRANSFER_ID)
                if (!transferId.isNullOrBlank()) {
                    connectionManager.transferManager.cancelTransfer(transferId)
                } else {
                    connectionManager.transferManager.cancelAll()
                }
                syncTransferNotification()
            }

            ACTION_START -> {
                syncTransferNotification()
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "MacDroidService onDestroy")
        stopForeground(STOP_FOREGROUND_REMOVE)
        notificationManager?.cancel(NOTIFICATION_ID)
        notificationManager?.cancelAll()

        unregisterNetworkMonitoring()
        connectionManager.clipboardManager.removeClipChangedListener(clipListener)
        connectionManager.transferManager.removeListener(this)
        connectionManager.removeListener(this)
        discoveryService.removeListener(this)
        releaseLocks()

        instance = null
        isPendingStart = false
        isPendingStop = false
        DexoLinkTileService.onServiceStateChanged()
        DexoLinkTileService.requestUpdate(this)

        super.onDestroy()
    }

    // --- DiscoveryListener ---

    override fun onDeviceFound(device: DiscoveredDevice) {
        val trustStore = TrustStore.getInstance(this)
        val paired = trustStore.getPairedDevice()

        if (paired != null && !connectionManager.isConnected() && connectionManager.state !is ConnectionState.Connecting) {
            val isMyPairedDevice = (device.deviceId != null && device.deviceId == paired.deviceId) ||
                    device.name == paired.deviceName

            if (isMyPairedDevice && connectionManager.autoReconnect && !connectionManager.userExplicitlyDisconnected) {
                connectionManager.connect(device.host, device.port)
            }
        } else if (connectionManager.isConnected()) {
            if (paired != null && (device.deviceId == paired.deviceId || device.name == paired.deviceName)) {
                connectionManager.updateTargetIfChanged(device.host, device.port)
            }
        }
    }

    override fun onDeviceLost(name: String) {}

    override fun onError(message: String) {}

    // --- Clipboard Handling ---

    private fun onPrimaryClipChanged() {
        if (!connectionManager.isConnected() || connectionManager.userExplicitlyDisconnected) return
        val currentText = connectionManager.clipboardManager.getCurrentClipText() ?: return
        if (currentText.isNotBlank() && connectionManager.clipboardManager.shouldSend(currentText)) {
            Log.d(TAG, "Local clipboard changed, auto-syncing to Mac (${currentText.take(30)}...)")
            connectionManager.sendClipboard(currentText, force = true)
        }
    }

    // --- ConnectionEventListener ---

    override fun onStateChanged(state: ConnectionState) {
        DexoLinkTileService.onServiceStateChanged()
        DexoLinkTileService.requestUpdate(this)

        when (state) {
            is ConnectionState.Authenticated, is ConnectionState.Connected -> {
                acquireWifiLock()
                discoveryService.pauseForActiveConnection()
                syncTransferNotification()
            }
            is ConnectionState.Disconnected, is ConnectionState.Failed -> {
                releaseLocks()
                discoveryService.resumeAfterDisconnect()
                syncTransferNotification()
            }
            is ConnectionState.Pairing -> {
                notificationManager?.notify(NOTIFICATION_ID, buildPairingNotification(state))
            }
            else -> {}
        }
    }

    override fun onMessageReceived(message: ProtocolMessage) {
        // Clipboard synchronization runs completely silent as required
    }

    override fun onLog(log: String) {}

    // --- TransferEventListener ---

    override fun onTransferUpdated(item: TransferItem) {
        if (item.state == TransferStatus.TRANSFERRING || item.state == TransferStatus.VERIFYING) {
            val now = System.currentTimeMillis()
            // Throttle notification updates to at most ~3/sec for minimal CPU & battery usage
            if (now - lastProgressNotificationTime >= 300L || item.progress >= 1.0f) {
                lastProgressNotificationTime = now
                notificationManager?.notify(NOTIFICATION_ID, buildTransferNotification(item))
            }
        } else {
            syncTransferNotification()
        }
    }

    override fun onTransferCompleted(item: TransferItem) {
        syncTransferNotification()
    }

    override fun onTransferFailed(item: TransferItem, error: String) {
        syncTransferNotification()
    }

    override fun onTransfersListChanged() {
        syncTransferNotification()
    }

    private fun syncTransferNotification() {
        if (!isRunning()) return
        val activeTransfer = connectionManager.transferManager.activeTransfers.values.firstOrNull {
            it.state == TransferStatus.TRANSFERRING || it.state == TransferStatus.VERIFYING
        }
        if (activeTransfer != null) {
            notificationManager?.notify(NOTIFICATION_ID, buildTransferNotification(activeTransfer))
        } else {
            // Revert to stable "DexoLink is active" notification (no progress bar, quiet)
            notificationManager?.notify(NOTIFICATION_ID, buildActiveNotification())
        }
    }

    // --- Network Monitoring ---

    private fun registerNetworkMonitoring() {
        try {
            connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    discoveryService.restartBrowsing()
                }

                override fun onLost(network: Network) {}
            }
            connectivityManager?.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            Log.w(TAG, "Could not register network callback: ${e.message}")
        }
    }

    private fun unregisterNetworkMonitoring() {
        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (e: Exception) {
            // Ignore
        }
        networkCallback = null
    }

    // --- Locks ---

    private fun acquireWifiLock() {
        if (wifiLock == null) {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifiManager?.createWifiLock(mode, "DexoLink:WifiConnectionLock")?.apply {
                setReferenceCounted(false)
            }
        }
        try {
            if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire WifiLock: ${e.message}")
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    // --- Notification Handling ---

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "DexoLink background status and file transfer progress"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun buildActiveNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DexoLink")
            .setContentText("DexoLink is active")
            .setSmallIcon(R.drawable.ic_qs_dexolink)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun buildPairingNotification(state: ConnectionState.Pairing): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val acceptIntent = Intent(this, MacDroidService::class.java).apply {
            action = ACTION_ACCEPT_PAIRING
        }
        val acceptPendingIntent = PendingIntent.getService(
            this,
            10,
            acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val rejectIntent = Intent(this, MacDroidService::class.java).apply {
            action = ACTION_REJECT_PAIRING
        }
        val rejectPendingIntent = PendingIntent.getService(
            this,
            11,
            rejectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DexoLink: Pairing Request")
            .setContentText("Pairing with ${state.peerName} • Code: ${state.pin}")
            .setSmallIcon(R.drawable.ic_qs_dexolink)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(android.R.drawable.checkbox_on_background, "Accept", acceptPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Reject", rejectPendingIntent)
            .build()
    }

    private fun buildTransferNotification(item: TransferItem): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(this, MacDroidService::class.java).apply {
            action = ACTION_CANCEL_TRANSFER
            putExtra(EXTRA_TRANSFER_ID, item.id)
        }
        val cancelPendingIntent = PendingIntent.getService(
            this,
            12,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (item.isOutgoing) "Sending: ${item.name}" else "Receiving: ${item.name}"
        val percent = (item.progress * 100).coerceIn(0f, 100f).toInt()
        val sizeText = "${formatSize(item.bytesTransferred)} / ${formatSize(item.size)}"
        val speedText = formatSpeed(item.speedBytesPerSec)
        val etaText = formatEta(item.etaSeconds)
        val contentText = "$sizeText ($percent%) • $speedText • ETA: $etaText"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_qs_dexolink)
            .setProgress(100, percent, false)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPendingIntent)
            .build()
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        val group = digitGroups.coerceIn(0, units.size - 1)
        return String.format(Locale.US, "%.1f %s", bytes / Math.pow(1024.0, group.toDouble()), units[group])
    }

    private fun formatSpeed(bytesPerSec: Double): String {
        if (bytesPerSec <= 0) return "0 KB/s"
        val mb = bytesPerSec / (1024.0 * 1024.0)
        return if (mb >= 1.0) {
            String.format(Locale.US, "%.1f MB/s", mb)
        } else {
            String.format(Locale.US, "%.0f KB/s", bytesPerSec / 1024.0)
        }
    }

    private fun formatEta(seconds: Long): String {
        if (seconds <= 0L) return "--"
        if (seconds < 60L) return "${seconds}s"
        if (seconds < 3600L) return "${seconds / 60}m ${seconds % 60}s"
        return "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    }
}

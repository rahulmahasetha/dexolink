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
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.macdroid.MacDroidApplication
import com.macdroid.core.MacDroidClipboardManager
import com.macdroid.networking.*
import com.macdroid.storage.TrustStore
import com.macdroid.ui.MainActivity

class MacDroidService : Service(), ConnectionEventListener, DiscoveryListener {

    companion object {
        private const val TAG = "MacDroidService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "macdroid_foreground_service"
        private const val CHANNEL_NAME = "MacDroid Background Connection"

        const val ACTION_START = "com.macdroid.action.START"
        const val ACTION_STOP = "com.macdroid.action.STOP"
        const val ACTION_SEND_CLIPBOARD = "com.macdroid.action.SEND_CLIPBOARD"
        const val ACTION_DISCONNECT = "com.macdroid.action.DISCONNECT"
        const val ACTION_CONNECT = "com.macdroid.action.CONNECT"
        const val ACTION_ACCEPT_PAIRING = "com.macdroid.action.ACCEPT_PAIRING"
        const val ACTION_REJECT_PAIRING = "com.macdroid.action.REJECT_PAIRING"

        const val EXTRA_HOST = "extra_host"
        const val EXTRA_PORT = "extra_port"

        @Volatile
        var instance: MacDroidService? = null
            private set

        val connectionManager: ConnectionManager
            get() = MacDroidApplication.connectionManager

        val discoveryService: DiscoveryService
            get() = MacDroidApplication.discoveryService

        fun isRunning(): Boolean = instance != null

        fun start(context: Context) {
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
                Log.e(TAG, "Failed to startForegroundService, trying startService: ${e.message}")
                try {
                    val fallbackIntent = Intent(context, MacDroidService::class.java).apply {
                        action = ACTION_START
                    }
                    context.startService(fallbackIntent)
                } catch (inner: Exception) {
                    Log.e(TAG, "Failed to start service: ${inner.message}")
                }
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, MacDroidService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop service: ${e.message}")
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var notificationManager: NotificationManager? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        onPrimaryClipChanged()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "MacDroidService onCreate")

        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        createNotificationChannel()

        connectionManager.addListener(this@MacDroidService)
        discoveryService.addListener(this@MacDroidService)

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

        // Register clipboard listener
        try {
            connectionManager.clipboardManager.addClipChangedListener(clipListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error adding clipboard listener: ${e.message}", e)
        }

        // Register network callback to automatically restart discovery upon Wi-Fi reconnect
        registerNetworkMonitoring()

        // Try auto-reconnect to last known Mac if available
        val lastHost = connectionManager.lastConnectedHost
        val lastPort = connectionManager.lastConnectedPort
        if (!lastHost.isNullOrBlank() && lastPort > 0) {
            Log.d(TAG, "Initiating auto-reconnect to last known Mac: $lastHost:$lastPort")
            connectionManager.connect(lastHost, lastPort)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        Log.d(TAG, "onStartCommand action: $action")

        // Start foreground immediately and safely
        try {
            val notification = buildNotification(connectionManager.state)
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
            ACTION_STOP -> {
                connectionManager.disconnect(disableAutoReconnect = true)
                discoveryService.stopAll()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            ACTION_DISCONNECT -> {
                connectionManager.disconnect(disableAutoReconnect = true)
                updateNotification(connectionManager.state)
                Toast.makeText(this, "MacDroid disconnected", Toast.LENGTH_SHORT).show()
            }

            ACTION_SEND_CLIPBOARD -> {
                handleSendClipboardFromNotification()
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
                updateNotification(connectionManager.state)
            }

            ACTION_REJECT_PAIRING -> {
                connectionManager.rejectPairing()
                updateNotification(connectionManager.state)
            }

            ACTION_START -> {
                // Ensure ongoing notification is active
                updateNotification(connectionManager.state)
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "MacDroidService onDestroy")
        unregisterNetworkMonitoring()
        connectionManager.clipboardManager.removeClipChangedListener(clipListener)
        connectionManager.removeListener(this)
        discoveryService.removeListener(this)
        releaseLocks()
        instance = null
        super.onDestroy()
    }

    // --- DiscoveryListener (Automatic Connection without manual IP) ---

    override fun onDeviceFound(device: DiscoveredDevice) {
        Log.d(TAG, "Discovery: Found ${device.name} @ ${device.host}:${device.port} (trusted: ${device.isTrusted})")

        // Strict 1-device policy: ONLY auto-connect to the single saved paired device!
        val trustStore = TrustStore.getInstance(this)
        val paired = trustStore.getPairedDevice()

        if (paired != null && !connectionManager.isConnected() && connectionManager.state !is ConnectionState.Connecting) {
            val isMyPairedDevice = (device.deviceId != null && device.deviceId == paired.deviceId) ||
                    device.name == paired.deviceName

            if (isMyPairedDevice && connectionManager.autoReconnect) {
                Log.d(TAG, "⚡ Automatic reconnection to paired device: ${device.name} (${device.host}:${device.port})")
                connectionManager.connect(device.host, device.port)
            }
        } else if (connectionManager.isConnected()) {
            if (paired != null && (device.deviceId == paired.deviceId || device.name == paired.deviceName)) {
                connectionManager.updateTargetIfChanged(device.host, device.port)
            }
        }
    }

    override fun onDeviceLost(name: String) {
        Log.d(TAG, "Discovery: Lost $name")
    }

    override fun onError(message: String) {
        Log.w(TAG, "Discovery error: $message")
    }

    // --- Clipboard Sync ---

    private fun onPrimaryClipChanged() {
        if (!connectionManager.isConnected()) return
        val currentText = connectionManager.clipboardManager.getCurrentClipText() ?: return
        if (currentText.isNotBlank() && connectionManager.clipboardManager.shouldSend(currentText)) {
            Log.d(TAG, "Local clipboard changed, auto-syncing to Mac (${currentText.take(30)}...)")
            connectionManager.sendClipboard(currentText, force = true)
        }
    }

    private fun handleSendClipboardFromNotification() {
        val currentText = connectionManager.clipboardManager.getCurrentClipText()
        if (!currentText.isNullOrBlank()) {
            if (connectionManager.isConnected()) {
                connectionManager.sendClipboard(currentText)
                Toast.makeText(this, "Sent clipboard to Mac! 📋", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Not connected to Mac", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
        }
    }

    // --- ConnectionEventListener ---

    override fun onStateChanged(state: ConnectionState) {
        updateNotification(state)

        when (state) {
            is ConnectionState.Authenticated -> {
                acquireLocks()
            }
            is ConnectionState.Disconnected, is ConnectionState.Failed -> {
                releaseLocks()
            }
            else -> {}
        }
    }

    override fun onMessageReceived(message: ProtocolMessage) {
        if (message is ClipboardPushMessage) {
            // Show toast when clipboard is received from Mac while app is in background
            val preview = message.content.take(35)
            Toast.makeText(this, "Copied from Mac: \"$preview\"", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onLog(log: String) {
        // Handled by UI
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
                    Log.d(TAG, "Wi-Fi network connected. Restarting discovery...")
                    discoveryService.restartBrowsing()
                }

                override fun onLost(network: Network) {
                    Log.d(TAG, "Wi-Fi network lost.")
                }
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

    // --- Power & Wifi Locks ---

    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "MacDroid::PersistentConnectionWakeLock"
                ).apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(12 * 60 * 60 * 1000L) // 12 hours
                Log.d(TAG, "Acquired WakeLock for background persistence")
            }

            if (wifiLock == null) {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wifiManager.createWifiLock(lockMode, "MacDroid::PersistentWifiLock").apply {
                    setReferenceCounted(false)
                }
            }
            if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
                Log.d(TAG, "Acquired WifiLock for background persistence")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring locks: ${e.message}", e)
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "Released WakeLock")
            }
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
                Log.d(TAG, "Released WifiLock")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing locks: ${e.message}", e)
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
                description = "Keeps MacDroid connected in background for seamless clipboard and file transfer"
                setShowBadge(false)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(state: ConnectionState): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val sendClipIntent = Intent(this, MacDroidService::class.java).apply {
            action = ACTION_SEND_CLIPBOARD
        }
        val sendClipPendingIntent = PendingIntent.getService(
            this,
            1,
            sendClipIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val disconnectIntent = Intent(this, MacDroidService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPendingIntent = PendingIntent.getService(
            this,
            2,
            disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val (title, contentText) = when (state) {
            is ConnectionState.Authenticated -> Pair(
                "MacDroid: Connected & Synced",
                "Connected to ${state.peerName} • Auto Clipboard Active"
            )
            is ConnectionState.Connected -> Pair(
                "MacDroid: Handshaking",
                "Connected to ${state.peerAddress}..."
            )
            is ConnectionState.Pairing -> Pair(
                "MacDroid: Pairing",
                "Pairing with ${state.peerName} • Code: ${state.pin}"
            )
            is ConnectionState.Connecting -> Pair(
                "MacDroid: Connecting",
                state.target
            )
            is ConnectionState.Failed -> Pair(
                "MacDroid: Reconnecting",
                "Connection interrupted, auto-reconnecting..."
            )
            is ConnectionState.Disconnected -> Pair(
                "MacDroid: Searching",
                "Automatic discovery active • Ready to pair"
            )
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (state is ConnectionState.Pairing) {
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

            builder.addAction(
                android.R.drawable.checkbox_on_background,
                "Accept",
                acceptPendingIntent
            )
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Reject",
                rejectPendingIntent
            )
            builder.setPriority(NotificationCompat.PRIORITY_HIGH)
        } else if (state is ConnectionState.Authenticated || state is ConnectionState.Connected) {
            builder.addAction(
                android.R.drawable.ic_menu_send,
                "📋 Send Clip",
                sendClipPendingIntent
            )
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Disconnect",
                disconnectPendingIntent
            )
        }

        return builder.build()
    }

    private fun updateNotification(state: ConnectionState) {
        notificationManager?.notify(NOTIFICATION_ID, buildNotification(state))
    }
}

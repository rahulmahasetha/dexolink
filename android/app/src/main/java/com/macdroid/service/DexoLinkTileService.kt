package com.macdroid.service

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.macdroid.MacDroidApplication
import com.macdroid.R
import com.macdroid.networking.ConnectionEventListener
import com.macdroid.networking.ConnectionManager
import com.macdroid.networking.ConnectionState
import com.macdroid.networking.ProtocolMessage
import com.macdroid.storage.TrustStore
import com.macdroid.ui.MainActivity

/**
 * Android Quick Settings Tile for DexoLink.
 * Provides instant, zero-lag On/Off toggling synchronized with MacDroidService.
 */
class DexoLinkTileService : TileService(), ConnectionEventListener {

    companion object {
        private const val TAG = "DexoLinkTileService"

        @Volatile
        private var activeInstance: DexoLinkTileService? = null
        private val mainHandler = Handler(Looper.getMainLooper())

        /**
         * Instantly refreshes any actively listening Quick Settings tile in the notification shade.
         */
        fun onServiceStateChanged() {
            val service = activeInstance
            if (service != null) {
                mainHandler.post {
                    service.updateTileState()
                }
            }
        }

        /**
         * Requests the system to schedule a tile update when not actively listening.
         */
        fun requestUpdate(context: Context) {
            mainHandler.post {
                try {
                    val component = ComponentName(context, DexoLinkTileService::class.java)
                    requestListeningState(context, component)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed requestListeningState: ${e.message}")
                }
            }
        }
    }

    private val connectionManager: ConnectionManager
        get() = MacDroidApplication.connectionManager

    override fun onStartListening() {
        super.onStartListening()
        activeInstance = this
        try {
            connectionManager.addListener(this)
        } catch (e: Exception) {
            Log.e(TAG, "Error attaching listener: ${e.message}")
        }
        updateTileState()
    }

    override fun onStopListening() {
        if (activeInstance === this) {
            activeInstance = null
        }
        try {
            connectionManager.removeListener(this)
        } catch (e: Exception) {
            Log.e(TAG, "Error removing listener: ${e.message}")
        }
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val trustStore = TrustStore.getInstance(this)
        val paired = trustStore.getPairedDevice()

        if (paired == null) {
            openMainActivity()
            return
        }

        val tile = qsTile ?: return
        val isServiceRunning = MacDroidService.isRunning()
        val isExplicitlyDisconnected = connectionManager.userExplicitlyDisconnected
        val isConnected = connectionManager.isConnected()
        val isTileActive = isServiceRunning && !isExplicitlyDisconnected && (isConnected || connectionManager.autoReconnect)

        if (isTileActive) {
            // Turning OFF: Synchronously update tile visual state to INACTIVE immediately
            connectionManager.userExplicitlyDisconnected = true
            connectionManager.disconnect(disableAutoReconnect = true)

            tile.state = Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = "Off (${paired.deviceName})"
            }
            tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_dexolink)
            tile.updateTile()

            MacDroidService.stop(this)
        } else {
            // Turning ON: Synchronously update tile visual state to ACTIVE immediately
            connectionManager.userExplicitlyDisconnected = false

            tile.state = Tile.STATE_ACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = "Searching ${paired.deviceName}..."
            }
            tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_dexolink)
            tile.updateTile()

            MacDroidService.start(this)
            val host = connectionManager.lastConnectedHost ?: ""
            val port = if (connectionManager.lastConnectedPort > 0) connectionManager.lastConnectedPort else 52840
            if (host.isNotBlank()) {
                connectionManager.connect(host, port)
            }
        }
    }

    private fun openMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    fun updateTileState() {
        val tile = qsTile ?: return
        val isServiceRunning = MacDroidService.isRunning()
        val trustStore = TrustStore.getInstance(this)
        val paired = trustStore.getPairedDevice()
        val state = connectionManager.state
        val isExplicitlyDisconnected = connectionManager.userExplicitlyDisconnected
        val isConnected = state is ConnectionState.Connected || state is ConnectionState.Authenticated
        val isConnecting = state is ConnectionState.Connecting

        tile.label = "DexoLink"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_dexolink)

        // The tile is visually ACTIVE (ON) when the service is actively running, NOT explicitly disconnected,
        // and either connected, connecting, or actively waiting for paired device connection.
        val isTileActive = isServiceRunning && !isExplicitlyDisconnected && (isConnected || isConnecting || (paired != null && connectionManager.autoReconnect))

        if (isTileActive) {
            tile.state = Tile.STATE_ACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = when (state) {
                    is ConnectionState.Authenticated -> "Connected: ${state.peerName}"
                    is ConnectionState.Connected -> "Connected: ${state.peerName}"
                    is ConnectionState.Connecting -> "Connecting..."
                    else -> if (paired != null) "Searching ${paired.deviceName}..." else "Active"
                }
            }
        } else {
            tile.state = Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = if (paired != null) "Off (${paired.deviceName})" else "Tap to Set Up"
            }
        }

        try {
            tile.updateTile()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update tile: ${e.message}")
        }
    }

    override fun onStateChanged(state: ConnectionState) {
        updateTileState()
    }

    override fun onMessageReceived(message: ProtocolMessage) {}
    override fun onLog(log: String) {}
}

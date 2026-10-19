package com.macdroid.service

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
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
 * Operates like native Wi-Fi / Bluetooth toggles:
 * - Single tap to toggle DexoLink On / Off.
 * - If already paired, turning On immediately searches & reconnects without prompting for pairing.
 * - If not yet paired, tapping opens DexoLink to perform one-time pairing.
 * - Icon matches the antenna broadcast symbol from the macOS menu bar / DMG app.
 */
class DexoLinkTileService : TileService(), ConnectionEventListener {

    companion object {
        private const val TAG = "DexoLinkTileService"

        /**
         * Requests the Android System UI to refresh the DexoLink Quick Settings Tile.
         */
        fun requestUpdate(context: Context) {
            try {
                val component = ComponentName(context, DexoLinkTileService::class.java)
                requestListeningState(context, component)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to requestListeningState: ${e.message}")
            }
        }
    }

    private val connectionManager: ConnectionManager
        get() = MacDroidApplication.connectionManager

    override fun onStartListening() {
        super.onStartListening()
        try {
            connectionManager.addListener(this)
        } catch (e: Exception) {
            Log.e(TAG, "Error attaching listener: ${e.message}")
        }
        updateTileState()
    }

    override fun onStopListening() {
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

        // If no device has been paired yet, open the app directly to set up
        if (paired == null) {
            openMainActivity()
            return
        }

        // Toggle service ON / OFF like native Bluetooth & Wi-Fi
        if (MacDroidService.isRunning()) {
            Log.d(TAG, "Quick Settings: Toggling DexoLink OFF")
            MacDroidService.stop(this)
        } else {
            Log.d(TAG, "Quick Settings: Toggling DexoLink ON (auto-reconnecting to ${paired.deviceName})")
            MacDroidService.start(this)
        }

        updateTileState()
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

    private fun updateTileState() {
        val tile = qsTile ?: return
        val isServiceRunning = MacDroidService.isRunning()
        val trustStore = TrustStore.getInstance(this)
        val paired = trustStore.getPairedDevice()
        val state = connectionManager.state

        tile.label = "DexoLink"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_dexolink)

        if (!isServiceRunning) {
            tile.state = Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = if (paired != null) "Off (${paired.deviceName})" else "Tap to Set Up"
            }
        } else {
            when (state) {
                is ConnectionState.Authenticated -> {
                    tile.state = Tile.STATE_ACTIVE
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        tile.subtitle = "Connected: ${state.peerName}"
                    }
                }
                is ConnectionState.Connecting -> {
                    tile.state = Tile.STATE_ACTIVE
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        tile.subtitle = "Connecting..."
                    }
                }
                is ConnectionState.Connected -> {
                    tile.state = Tile.STATE_ACTIVE
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        tile.subtitle = "Connected: ${state.peerName}"
                    }
                }
                else -> {
                    tile.state = Tile.STATE_ACTIVE
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        tile.subtitle = if (paired != null) "Searching ${paired.deviceName}..." else "Active"
                    }
                }
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

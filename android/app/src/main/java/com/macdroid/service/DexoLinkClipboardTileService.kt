package com.macdroid.service

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.macdroid.MacDroidApplication
import com.macdroid.ui.ClipboardSyncActivity

/**
 * Android Quick Settings Tile dedicated to 1-tap clipboard sync.
 * Allows syncing copied Android text to Mac directly from Quick Settings.
 */
class DexoLinkClipboardTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val isConnected = MacDroidApplication.connectionManager.isConnected()
        tile.state = if (isConnected) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Sync to Mac"
        tile.subtitle = if (isConnected) {
            MacDroidApplication.connectionManager.currentPeerName ?: "Connected"
        } else {
            "Disconnected"
        }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, ClipboardSyncActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = android.app.PendingIntent.getActivity(
                this,
                0,
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}

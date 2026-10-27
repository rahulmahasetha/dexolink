package com.macdroid.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.macdroid.storage.TrustStore

/**
 * Ensures MacDroidService starts automatically on device boot or app update
 * so that background clipboard sync and device connectivity are preserved.
 */
class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "Received broadcast action: $action")
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val trustStore = TrustStore.getInstance(context)
            if (trustStore.hasPairedDevice()) {
                Log.d(TAG, "Paired device exists, starting MacDroidService in background...")
                MacDroidService.start(context)
            }
        }
    }
}

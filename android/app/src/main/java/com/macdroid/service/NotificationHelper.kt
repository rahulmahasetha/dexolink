package com.macdroid.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.macdroid.R
import com.macdroid.storage.DexoLinkSettings
import com.macdroid.ui.MainActivity

/**
 * Manages clean, non-duplicated status event notifications and transfer audio feedback
 * for DexoLink across connection, disconnection, pairing, and unpairing events.
 */
object NotificationHelper {

    private const val TAG = "NotificationHelper"
    private const val CHANNEL_EVENTS_ID = "dexolink_status_events"
    private const val CHANNEL_EVENTS_NAME = "DexoLink Status Alerts"

    private const val NOTIFICATION_ID_CONNECT = 2001
    private const val NOTIFICATION_ID_DISCONNECT = 2002
    private const val NOTIFICATION_ID_PAIR = 2003
    private const val NOTIFICATION_ID_UNPAIR = 2004

    @Volatile
    private var lastNotifiedState: String? = null

    @Volatile
    private var wasPreviouslyConnected: Boolean = false

    private fun getNotificationManager(context: Context): NotificationManager? {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null) {
            val existing = manager.getNotificationChannel(CHANNEL_EVENTS_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_EVENTS_ID,
                    CHANNEL_EVENTS_NAME,
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Alerts for successful DexoLink connect, disconnect, pair, and unpair events"
                    setShowBadge(true)
                    enableVibration(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
        return manager
    }

    private fun getOpenAppPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            100,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Suppresses noisy status drawer spam for connect events per Phase 7 requirements.
     * Cancels any lingering alert notifications.
     */
    fun showConnectedNotification(context: Context, peerName: String) {
        val nm = getNotificationManager(context) ?: return
        nm.cancel(NOTIFICATION_ID_DISCONNECT)
        nm.cancel(NOTIFICATION_ID_CONNECT)
        Log.d(TAG, "Device connected: $peerName (drawer notification suppressed for privacy/cleanliness)")
    }

    /**
     * Suppresses noisy status drawer spam for disconnect events per Phase 7 requirements.
     */
    fun showDisconnectedNotification(context: Context, peerName: String?) {
        val nm = getNotificationManager(context) ?: return
        nm.cancel(NOTIFICATION_ID_CONNECT)
        nm.cancel(NOTIFICATION_ID_DISCONNECT)
        Log.d(TAG, "Device disconnected: ${peerName ?: "device"} (drawer notification suppressed)")
    }

    /**
     * Suppresses noisy status drawer spam for pairing events per Phase 7 requirements.
     */
    fun showPairedNotification(context: Context, peerName: String) {
        val nm = getNotificationManager(context) ?: return
        nm.cancel(NOTIFICATION_ID_UNPAIR)
        nm.cancel(NOTIFICATION_ID_PAIR)
        Log.d(TAG, "Device paired: $peerName (drawer notification suppressed)")
    }

    /**
     * Suppresses noisy status drawer spam for unpair events per Phase 7 requirements.
     */
    fun showUnpairedNotification(context: Context, peerName: String?) {
        val nm = getNotificationManager(context) ?: return
        nm.cancel(NOTIFICATION_ID_PAIR)
        nm.cancel(NOTIFICATION_ID_UNPAIR)
        Log.d(TAG, "Device unpaired: ${peerName ?: "device"} (drawer notification suppressed)")
    }

    /**
     * Plays a subtle notification sound when a file is successfully sent or received.
     * Avoids sounds for unrelated events and respects user notification settings.
     */
    fun playTransferSuccessSound(context: Context) {
        try {
            val settings = DexoLinkSettings.getInstance(context)
            if (!settings.notificationsEnabled.value) return

            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(context.applicationContext, uri)
            if (ringtone != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    ringtone.isLooping = false
                    ringtone.volume = 0.5f // subtle volume
                }
                ringtone.play()
                Log.d(TAG, "Played subtle transfer completion sound")
            } else {
                playFallbackTone()
            }
        } catch (e: Exception) {
            playFallbackTone()
        }
    }

    private fun playFallbackTone() {
        try {
            val toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 40)
            toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            toneGenerator.release()
        } catch (t: Throwable) {
            // Ignore sound errors
        }
    }
}

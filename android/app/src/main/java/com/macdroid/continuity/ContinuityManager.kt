package com.macdroid.continuity

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.macdroid.R
import com.macdroid.networking.ConnectionManager
import com.macdroid.networking.ContinuityAckMessage
import com.macdroid.networking.ContinuityActivityMessage
import com.macdroid.networking.ContinuityDismissMessage
import com.macdroid.networking.ContinuityStateMessage
import com.macdroid.networking.ProtocolMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ContinuityManager private constructor(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "dexolink_continuity_prefs"
        private const val KEY_ENABLED = "continuity_enabled"
        private const val CHANNEL_ID = "dexolink_continuity_channel"

        @Volatile
        private var instance: ContinuityManager? = null

        fun getInstance(context: Context): ContinuityManager {
            return instance ?: synchronized(this) {
                instance ?: ContinuityManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var connectionManager: ConnectionManager? = null
    private val adapters = mutableListOf<ContinuityAdapter>()

    private val _isContinuityEnabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))
    val isContinuityEnabled: StateFlow<Boolean> = _isContinuityEnabled.asStateFlow()

    private val _activeIncomingActivity = MutableStateFlow<ContinuityActivity?>(null)
    val activeIncomingActivity: StateFlow<ContinuityActivity?> = _activeIncomingActivity.asStateFlow()

    private val _lastSentActivity = MutableStateFlow<ContinuityActivity?>(null)
    val lastSentActivity: StateFlow<ContinuityActivity?> = _lastSentActivity.asStateFlow()

    init {
        createNotificationChannel()
        registerDefaultAdapters()
    }

    fun setConnectionManager(manager: ConnectionManager) {
        this.connectionManager = manager
    }

    fun setContinuityEnabled(enabled: Boolean) {
        if (_isContinuityEnabled.value == enabled) return
        _isContinuityEnabled.value = enabled
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()

        if (!enabled) {
            _activeIncomingActivity.value = null
            dismissNotification()
        }

        connectionManager?.sendMessage(ContinuityStateMessage(enabled))
    }

    private fun registerDefaultAdapters() {
        adapters.add(WebContinuityAdapter())
        adapters.add(PdfContinuityAdapter())
        adapters.add(PresentationContinuityAdapter())
        adapters.add(DocumentContinuityAdapter())
    }

    fun handleMessage(message: ProtocolMessage) {
        if (!_isContinuityEnabled.value) return

        when (message) {
            is ContinuityActivityMessage -> {
                val activity = ContinuityActivity(
                    activityId = message.activityId,
                    activityType = message.activityType,
                    title = message.title,
                    subtitle = message.subtitle,
                    url = message.url,
                    contentPayload = message.contentPayload,
                    position = message.position,
                    progress = message.progress,
                    timestamp = message.timestamp,
                    sourceDevice = message.sourceDevice
                )
                _activeIncomingActivity.value = activity
                postContinuityNotification(activity)
                connectionManager?.sendMessage(ContinuityAckMessage(activity.activityId, true))
            }
            is ContinuityDismissMessage -> {
                if (_activeIncomingActivity.value?.activityId == message.activityId) {
                    _activeIncomingActivity.value = null
                    dismissNotification()
                }
            }
            is ContinuityStateMessage -> {
                // Peer continuity state updated
            }
            else -> {}
        }
    }

    fun continueIncomingActivity(context: Context): Boolean {
        val activity = _activeIncomingActivity.value ?: return false
        for (adapter in adapters) {
            if (adapter.canHandle(activity)) {
                val success = adapter.continueActivity(context, activity)
                if (success) {
                    _activeIncomingActivity.value = null
                    dismissNotification()
                    return true
                }
            }
        }
        return false
    }

    fun dismissIncomingActivity() {
        val id = _activeIncomingActivity.value?.activityId
        if (id != null) {
            connectionManager?.sendMessage(ContinuityDismissMessage(id))
        }
        _activeIncomingActivity.value = null
        dismissNotification()
    }

    fun broadcastActivity(activity: ContinuityActivity) {
        if (!_isContinuityEnabled.value) return
        _lastSentActivity.value = activity
        val msg = ContinuityActivityMessage(
            activityId = activity.activityId,
            activityType = activity.activityType,
            title = activity.title,
            subtitle = activity.subtitle,
            url = activity.url,
            contentPayload = activity.contentPayload,
            position = activity.position,
            progress = activity.progress,
            timestamp = activity.timestamp,
            sourceDevice = Build.MODEL ?: "Android Phone"
        )
        connectionManager?.sendMessage(msg)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "DexoLink Continuity"
            val descriptionText = "Notifications for cross-device activity continuation"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun postContinuityNotification(activity: ContinuityActivity) {
        val continueIntent = Intent(context, ContinuityActionReceiver::class.java).apply {
            action = ContinuityActionReceiver.ACTION_CONTINUE
        }
        val continuePending = PendingIntent.getBroadcast(
            context,
            1,
            continueIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val dismissIntent = Intent(context, ContinuityActionReceiver::class.java).apply {
            action = ContinuityActionReceiver.ACTION_DISMISS
        }
        val dismissPending = PendingIntent.getBroadcast(
            context,
            2,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Continue on Android")
            .setContentText("${activity.sourceDevice} • ${activity.title}")
            .setSubText(activity.displaySubtitle)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(continuePending)
            .addAction(0, "Continue on Android", continuePending)
            .addAction(0, "Dismiss", dismissPending)

        try {
            NotificationManagerCompat.from(context).notify(ContinuityActionReceiver.NOTIFICATION_ID, builder.build())
        } catch (_: SecurityException) {
            // Notification permission might not be granted
        }
    }

    private fun dismissNotification() {
        try {
            NotificationManagerCompat.from(context).cancel(ContinuityActionReceiver.NOTIFICATION_ID)
        } catch (_: Exception) {}
    }
}

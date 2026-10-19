package com.macdroid.continuity

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat

class ContinuityActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_CONTINUE = "com.macdroid.continuity.ACTION_CONTINUE"
        const val ACTION_DISMISS = "com.macdroid.continuity.ACTION_DISMISS"
        const val NOTIFICATION_ID = 8820
    }

    override fun onReceive(context: Context, intent: Intent) {
        val manager = ContinuityManager.getInstance(context.applicationContext)
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)

        when (intent.action) {
            ACTION_CONTINUE -> {
                manager.continueIncomingActivity(context)
            }
            ACTION_DISMISS -> {
                manager.dismissIncomingActivity()
            }
        }
    }
}

package com.macdroid

import android.app.Application
import android.util.Log
import com.macdroid.networking.ConnectionManager
import com.macdroid.networking.DiscoveryService

class MacDroidApplication : Application() {

    companion object {
        private const val TAG = "MacDroidApp"

        @Volatile
        lateinit var instance: MacDroidApplication
            private set

        val connectionManager: ConnectionManager by lazy {
            ConnectionManager(instance.applicationContext)
        }

        val discoveryService: DiscoveryService by lazy {
            DiscoveryService(instance.applicationContext)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "MacDroidApplication started")

        // Eagerly instantiate core services so they are available immediately
        try {
            val cm = connectionManager
            val ds = discoveryService
            Log.d(TAG, "Initialized ConnectionManager & DiscoveryService in Application.onCreate")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing core services: ${e.message}", e)
        }
    }
}

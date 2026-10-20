package com.macdroid.storage

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Centralized, reactive persistent settings store for DexoLink on Android.
 * All settings are backed by SharedPreferences and exposed as StateFlow
 * for real-time UI updates and zero-cost retrieval across services.
 */
class DexoLinkSettings private constructor(context: Context) {
    companion object {
        private const val PREFS_NAME = "dexolink_app_settings"
        private const val KEY_AUTO_DISCOVERY = "auto_device_discovery"
        private const val KEY_AUTO_ACCEPT_PAIRED = "auto_accept_paired"
        private const val KEY_QUICK_FILE_SHARING = "quick_file_sharing"
        private const val KEY_NOTIFICATIONS = "notifications_enabled"
        private const val KEY_LARGE_FILES_SYNC = "large_files_sync"
        private const val KEY_MULTI_PAIRED_DEVICES = "multi_paired_devices"
        private const val KEY_CAMERA_SHARING = "camera_sharing"
        private const val KEY_SCREEN_SHARING = "screen_sharing"
        private const val KEY_DESKTOP_MODE = "desktop_mode"

        @Volatile
        private var instance: DexoLinkSettings? = null

        fun getInstance(context: Context): DexoLinkSettings {
            return instance ?: synchronized(this) {
                instance ?: DexoLinkSettings(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _autoDeviceDiscovery = MutableStateFlow(prefs.getBoolean(KEY_AUTO_DISCOVERY, true))
    val autoDeviceDiscovery: StateFlow<Boolean> = _autoDeviceDiscovery.asStateFlow()

    private val _autoAcceptPaired = MutableStateFlow(prefs.getBoolean(KEY_AUTO_ACCEPT_PAIRED, true))
    val autoAcceptPaired: StateFlow<Boolean> = _autoAcceptPaired.asStateFlow()

    private val _quickFileSharing = MutableStateFlow(prefs.getBoolean(KEY_QUICK_FILE_SHARING, true))
    val quickFileSharing: StateFlow<Boolean> = _quickFileSharing.asStateFlow()

    private val _notificationsEnabled = MutableStateFlow(prefs.getBoolean(KEY_NOTIFICATIONS, true))
    val notificationsEnabled: StateFlow<Boolean> = _notificationsEnabled.asStateFlow()

    private val _largeFilesSync = MutableStateFlow(prefs.getBoolean(KEY_LARGE_FILES_SYNC, true))
    val largeFilesSync: StateFlow<Boolean> = _largeFilesSync.asStateFlow()

    private val _multiPairedDevices = MutableStateFlow(prefs.getBoolean(KEY_MULTI_PAIRED_DEVICES, false))
    val multiPairedDevices: StateFlow<Boolean> = _multiPairedDevices.asStateFlow()

    // v4.0.1: Media Streaming & Remote Control (OFF by default)
    private val _cameraSharing = MutableStateFlow(prefs.getBoolean(KEY_CAMERA_SHARING, false))
    val cameraSharing: StateFlow<Boolean> = _cameraSharing.asStateFlow()

    private val _screenSharing = MutableStateFlow(prefs.getBoolean(KEY_SCREEN_SHARING, false))
    val screenSharing: StateFlow<Boolean> = _screenSharing.asStateFlow()

    private val _desktopMode = MutableStateFlow(prefs.getBoolean(KEY_DESKTOP_MODE, false))
    val desktopMode: StateFlow<Boolean> = _desktopMode.asStateFlow()

    fun setAutoDeviceDiscovery(enabled: Boolean) {
        _autoDeviceDiscovery.value = enabled
        prefs.edit().putBoolean(KEY_AUTO_DISCOVERY, enabled).apply()
    }

    fun setAutoAcceptPaired(enabled: Boolean) {
        _autoAcceptPaired.value = enabled
        prefs.edit().putBoolean(KEY_AUTO_ACCEPT_PAIRED, enabled).apply()
    }

    fun setQuickFileSharing(enabled: Boolean) {
        _quickFileSharing.value = enabled
        prefs.edit().putBoolean(KEY_QUICK_FILE_SHARING, enabled).apply()
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        _notificationsEnabled.value = enabled
        prefs.edit().putBoolean(KEY_NOTIFICATIONS, enabled).apply()
    }

    fun setLargeFilesSync(enabled: Boolean) {
        _largeFilesSync.value = enabled
        prefs.edit().putBoolean(KEY_LARGE_FILES_SYNC, enabled).apply()
    }

    fun setMultiPairedDevices(enabled: Boolean) {
        _multiPairedDevices.value = enabled
        prefs.edit().putBoolean(KEY_MULTI_PAIRED_DEVICES, enabled).apply()
    }

    fun setCameraSharing(enabled: Boolean) {
        _cameraSharing.value = enabled
        prefs.edit().putBoolean(KEY_CAMERA_SHARING, enabled).apply()
    }

    fun setScreenSharing(enabled: Boolean) {
        _screenSharing.value = enabled
        prefs.edit().putBoolean(KEY_SCREEN_SHARING, enabled).apply()
    }

    fun setDesktopMode(enabled: Boolean) {
        _desktopMode.value = enabled
        prefs.edit().putBoolean(KEY_DESKTOP_MODE, enabled).apply()
    }
}

package com.macdroid.storage

import android.content.Context
import android.content.SharedPreferences
import com.macdroid.security.KeystoreHelper
import org.json.JSONObject

data class TrustedDevice(
    val deviceId: String,
    val deviceName: String,
    val publicKeyBase64: String,
    val pairedAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("deviceId", deviceId)
        put("deviceName", deviceName)
        put("publicKeyBase64", publicKeyBase64)
        put("pairedAt", pairedAt)
    }

    companion object {
        fun fromJson(json: JSONObject): TrustedDevice = TrustedDevice(
            deviceId = json.getString("deviceId"),
            deviceName = json.optString("deviceName", "MacBook"),
            publicKeyBase64 = json.optString("publicKeyBase64", ""),
            pairedAt = json.optLong("pairedAt", System.currentTimeMillis())
        )
    }
}

class TrustStore(context: Context) {
    companion object {
        private const val PREFS_NAME = "macdroid_trust_store"
        private const val KEY_PAIRED_DEVICE = "single_paired_device"

        @Volatile
        private var instance: TrustStore? = null

        fun getInstance(context: Context): TrustStore {
            return instance ?: synchronized(this) {
                instance ?: TrustStore(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun getPairedDevice(): TrustedDevice? {
        val raw = prefs.getString(KEY_PAIRED_DEVICE, null) ?: return null
        val decrypted = KeystoreHelper.decrypt(raw) ?: raw
        return try {
            TrustedDevice.fromJson(JSONObject(decrypted))
        } catch (e: Exception) {
            try {
                TrustedDevice.fromJson(JSONObject(raw))
            } catch (ex: Exception) {
                null
            }
        }
    }

    @Synchronized
    fun hasPairedDevice(): Boolean {
        return getPairedDevice() != null
    }

    @Synchronized
    fun isPairedWith(deviceId: String, deviceName: String? = null): Boolean {
        if (deviceId.isBlank() && deviceName.isNullOrBlank()) return false
        val paired = getPairedDevice() ?: return false
        if (deviceId.isNotBlank() && paired.deviceId == deviceId) return true
        if (!deviceName.isNullOrBlank() && paired.deviceName == deviceName) return true
        return false
    }

    @Synchronized
    fun setPairedDevice(device: TrustedDevice) {
        // Enforce strictly one paired device at a time, encrypted with Android Keystore
        val jsonStr = device.toJson().toString()
        val encrypted = KeystoreHelper.encrypt(jsonStr)
        prefs.edit().putString(KEY_PAIRED_DEVICE, encrypted).apply()
    }

    @Synchronized
    fun unpairDevice() {
        prefs.edit().remove(KEY_PAIRED_DEVICE).apply()
    }

    // Backwards-compatible facade methods
    @Synchronized
    fun getTrustedDevices(): List<TrustedDevice> {
        val paired = getPairedDevice() ?: return emptyList()
        return listOf(paired)
    }

    @Synchronized
    fun isTrusted(deviceId: String): Boolean {
        return isPairedWith(deviceId)
    }

    @Synchronized
    fun getDevice(deviceId: String): TrustedDevice? {
        val paired = getPairedDevice() ?: return null
        return if (paired.deviceId == deviceId) paired else null
    }

    @Synchronized
    fun addTrustedDevice(device: TrustedDevice) {
        setPairedDevice(device)
    }

    @Synchronized
    fun removeTrustedDevice(deviceId: String) {
        val paired = getPairedDevice()
        if (paired != null && paired.deviceId == deviceId) {
            unpairDevice()
        }
    }

    @Synchronized
    fun clear() {
        unpairDevice()
    }
}

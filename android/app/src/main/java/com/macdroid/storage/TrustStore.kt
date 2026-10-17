package com.macdroid.storage

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
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
        private const val KEY_TRUSTED_DEVICES = "trusted_devices"

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
    fun getTrustedDevices(): List<TrustedDevice> {
        val jsonStr = prefs.getString(KEY_TRUSTED_DEVICES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<TrustedDevice>()
            for (i in 0 until arr.length()) {
                list.add(TrustedDevice.fromJson(arr.getJSONObject(i)))
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun isTrusted(deviceId: String): Boolean {
        if (deviceId.isBlank()) return false
        return getTrustedDevices().any { it.deviceId == deviceId }
    }

    @Synchronized
    fun getDevice(deviceId: String): TrustedDevice? {
        return getTrustedDevices().find { it.deviceId == deviceId }
    }

    @Synchronized
    fun addTrustedDevice(device: TrustedDevice) {
        val current = getTrustedDevices().toMutableList()
        current.removeAll { it.deviceId == device.deviceId }
        current.add(device)
        saveList(current)
    }

    @Synchronized
    fun removeTrustedDevice(deviceId: String) {
        val current = getTrustedDevices().toMutableList()
        current.removeAll { it.deviceId == deviceId }
        saveList(current)
    }

    @Synchronized
    fun clear() {
        prefs.edit().remove(KEY_TRUSTED_DEVICES).apply()
    }

    private fun saveList(list: List<TrustedDevice>) {
        val arr = JSONArray()
        for (item in list) {
            arr.put(item.toJson())
        }
        prefs.edit().putString(KEY_TRUSTED_DEVICES, arr.toString()).apply()
    }
}

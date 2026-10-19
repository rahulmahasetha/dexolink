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

class TrustStore(private val context: Context) {
    companion object {
        private const val PREFS_NAME = "macdroid_trust_store"
        private const val KEY_PAIRED_DEVICE = "single_paired_device"
        private const val KEY_MULTI_DEVICES = "multi_paired_devices_list"

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
        val settings = DexoLinkSettings.getInstance(context)
        if (settings.multiPairedDevices.value) {
            val devices = getTrustedDevices()
            if (devices.isNotEmpty()) return devices.first()
        }
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
        return getTrustedDevices().isNotEmpty()
    }

    @Synchronized
    fun isPairedWith(deviceId: String, deviceName: String? = null): Boolean {
        if (deviceId.isBlank() && deviceName.isNullOrBlank()) return false
        val devices = getTrustedDevices()
        for (dev in devices) {
            if (deviceId.isNotBlank() && dev.deviceId == deviceId) return true
            if (!deviceName.isNullOrBlank() && dev.deviceName.trim().equals(deviceName.trim(), ignoreCase = true)) return true
        }
        return false
    }

    @Synchronized
    fun setPairedDevice(device: TrustedDevice) {
        val settings = DexoLinkSettings.getInstance(context)
        if (settings.multiPairedDevices.value) {
            addTrustedDevice(device)
        } else {
            // Strictly enforce single device when Multiple Paired Devices is OFF
            val jsonStr = device.toJson().toString()
            val encrypted = KeystoreHelper.encrypt(jsonStr)
            prefs.edit()
                .putString(KEY_PAIRED_DEVICE, encrypted)
                .remove(KEY_MULTI_DEVICES)
                .apply()
        }
    }

    @Synchronized
    fun unpairDevice(deviceId: String? = null) {
        if (deviceId != null) {
            removeTrustedDevice(deviceId)
        } else {
            prefs.edit().remove(KEY_PAIRED_DEVICE).remove(KEY_MULTI_DEVICES).apply()
        }
    }

    @Synchronized
    fun getTrustedDevices(): List<TrustedDevice> {
        val settings = DexoLinkSettings.getInstance(context)
        if (!settings.multiPairedDevices.value) {
            val raw = prefs.getString(KEY_PAIRED_DEVICE, null) ?: return emptyList()
            val decrypted = KeystoreHelper.decrypt(raw) ?: raw
            val single = try {
                TrustedDevice.fromJson(JSONObject(decrypted))
            } catch (e: Exception) {
                try { TrustedDevice.fromJson(JSONObject(raw)) } catch (ex: Exception) { null }
            }
            return single?.let { listOf(it) } ?: emptyList()
        }

        val raw = prefs.getString(KEY_MULTI_DEVICES, null)
        if (raw.isNullOrBlank()) {
            // Fallback to single paired device if multi list not initialized yet
            val rawSingle = prefs.getString(KEY_PAIRED_DEVICE, null) ?: return emptyList()
            val decryptedSingle = KeystoreHelper.decrypt(rawSingle) ?: rawSingle
            val single = try {
                TrustedDevice.fromJson(JSONObject(decryptedSingle))
            } catch (e: Exception) {
                try { TrustedDevice.fromJson(JSONObject(rawSingle)) } catch (ex: Exception) { null }
            }
            return single?.let { listOf(it) } ?: emptyList()
        }

        val decrypted = KeystoreHelper.decrypt(raw) ?: raw
        return try {
            val jsonArray = org.json.JSONArray(decrypted)
            val list = mutableListOf<TrustedDevice>()
            for (i in 0 until jsonArray.length()) {
                list.add(TrustedDevice.fromJson(jsonArray.getJSONObject(i)))
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun isTrusted(deviceId: String): Boolean {
        return isPairedWith(deviceId)
    }

    @Synchronized
    fun getDevice(deviceId: String): TrustedDevice? {
        return getTrustedDevices().find { it.deviceId == deviceId }
    }

    @Synchronized
    fun addTrustedDevice(device: TrustedDevice) {
        val current = getTrustedDevices().filter { it.deviceId != device.deviceId }.toMutableList()
        current.add(0, device)
        val array = org.json.JSONArray()
        for (d in current) {
            array.put(d.toJson())
        }
        val encrypted = KeystoreHelper.encrypt(array.toString())
        val singleEncrypted = KeystoreHelper.encrypt(device.toJson().toString())
        prefs.edit()
            .putString(KEY_MULTI_DEVICES, encrypted)
            .putString(KEY_PAIRED_DEVICE, singleEncrypted)
            .apply()
    }

    @Synchronized
    fun removeTrustedDevice(deviceId: String) {
        val current = getTrustedDevices().filter { it.deviceId != deviceId }
        val editor = prefs.edit()
        if (current.isEmpty()) {
            editor.remove(KEY_MULTI_DEVICES).remove(KEY_PAIRED_DEVICE)
        } else {
            val array = org.json.JSONArray()
            for (d in current) {
                array.put(d.toJson())
            }
            val encrypted = KeystoreHelper.encrypt(array.toString())
            val singleEncrypted = KeystoreHelper.encrypt(current.first().toJson().toString())
            editor.putString(KEY_MULTI_DEVICES, encrypted)
                .putString(KEY_PAIRED_DEVICE, singleEncrypted)
        }
        editor.apply()
    }

    @Synchronized
    fun clear() {
        unpairDevice()
    }
}

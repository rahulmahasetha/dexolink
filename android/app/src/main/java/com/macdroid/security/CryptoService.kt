package com.macdroid.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

class CryptoService(context: Context) {
    companion object {
        private const val PREFS_NAME = "macdroid_crypto_identity"
        private const val KEY_PRIVATE_KEY = "identity_private_key"
        private const val KEY_PUBLIC_KEY = "identity_public_key"

        @Volatile
        private var instance: CryptoService? = null

        fun getInstance(context: Context): CryptoService {
            return instance ?: synchronized(this) {
                instance ?: CryptoService(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val publicKeyBase64: String
    val deviceId: String

    init {
        var storedPub = prefs.getString(KEY_PUBLIC_KEY, null)
        var storedPriv = prefs.getString(KEY_PRIVATE_KEY, null)

        if (storedPub == null || storedPriv == null) {
            // Generate a secure 32-byte cryptographic identity seed
            val random = SecureRandom()
            val privBytes = ByteArray(32)
            random.nextBytes(privBytes)

            // Derive public identity: SHA-256 of private key representation (32 bytes)
            val md = MessageDigest.getInstance("SHA-256")
            val pubBytes = md.digest(privBytes)

            storedPriv = Base64.encodeToString(privBytes, Base64.NO_WRAP)
            storedPub = Base64.encodeToString(pubBytes, Base64.NO_WRAP)

            prefs.edit()
                .putString(KEY_PRIVATE_KEY, storedPriv)
                .putString(KEY_PUBLIC_KEY, storedPub)
                .apply()
        }

        publicKeyBase64 = storedPub ?: ""

        // deviceId: First 16 hex chars of SHA-256 of public key raw bytes (matches macOS CryptoService format)
        val pubBytes = Base64.decode(publicKeyBase64, Base64.NO_WRAP)
        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest(pubBytes)
        deviceId = hash.take(8).joinToString("") { "%02x".format(it) }
    }
}

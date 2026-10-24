package com.macdroid.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class UpdateInfo(
    val currentVersion: String,
    val latestVersion: String,
    val isUpdateAvailable: Boolean,
    val releaseTitle: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val apkSize: Long,
    val expectedSha256: String? = null
)

sealed class UpdateStatus {
    object Idle : UpdateStatus()
    object Checking : UpdateStatus()
    data class Available(val info: UpdateInfo) : UpdateStatus()
    data class UpToDate(val currentVersion: String) : UpdateStatus()
    data class Downloading(val progressFraction: Float, val bytesDownloaded: Long, val totalBytes: Long) : UpdateStatus()
    data class Verifying(val progressFraction: Float = 0.99f) : UpdateStatus()
    data class ReadyToInstall(val apkFile: File) : UpdateStatus()
    data class Error(val message: String) : UpdateStatus()
}

class UpdateManager(private val context: Context) {
    companion object {
        private const val TAG = "UpdateManager"
        private const val GITHUB_REPO = "rahulmahasetha/dexolink"
        private const val GITHUB_API_URL = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"

        @Volatile
        private var instance: UpdateManager? = null

        fun getInstance(context: Context): UpdateManager {
            return instance ?: synchronized(this) {
                instance ?: UpdateManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val status: StateFlow<UpdateStatus> = _status

    fun getCurrentVersion(): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "4.1.0"
        } catch (e: Exception) {
            "4.1.0"
        }
    }

    private fun parseVersion(v: String): Triple<Int, Int, Int> {
        val clean = v.trim().removePrefix("v").removePrefix("V")
        val parts = clean.split(".").mapNotNull { it.toIntOrNull() }
        return Triple(
            parts.getOrElse(0) { 0 },
            parts.getOrElse(1) { 0 },
            parts.getOrElse(2) { 0 }
        )
    }

    fun isNewerVersion(latest: String, current: String): Boolean {
        val (lMajor, lMinor, lPatch) = parseVersion(latest)
        val (cMajor, cMinor, cPatch) = parseVersion(current)
        if (lMajor > cMajor) return true
        if (lMajor < cMajor) return false
        if (lMinor > cMinor) return true
        if (lMinor < cMinor) return false
        return lPatch > cPatch
    }

    suspend fun checkForUpdates(): UpdateStatus = withContext(Dispatchers.IO) {
        _status.value = UpdateStatus.Checking
        val current = getCurrentVersion()
        try {
            val url = URL(GITHUB_API_URL)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "DexoLink-Android/$current")
            }

            val code = connection.responseCode
            if (code != 200) {
                val err = "GitHub Releases API returned code $code"
                val errStatus = UpdateStatus.Error(err)
                _status.value = errStatus
                return@withContext errStatus
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val tagName = json.optString("tag_name", "").removePrefix("v")
            val releaseName = json.optString("name", "DexoLink v$tagName")
            val releaseNotes = json.optString("body", "Bug fixes and performance improvements.")
            val assets = json.optJSONArray("assets")

            var apkUrl = ""
            var apkSize = 0L

            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = asset.optString("browser_download_url", "")
                        apkSize = asset.optLong("size", 0L)
                        if (name.contains("DexoLink", ignoreCase = true)) {
                            break
                        }
                    }
                }
            }

            if (apkUrl.isBlank()) {
                apkUrl = "https://github.com/$GITHUB_REPO/releases/download/v$tagName/DexoLink-Android.apk"
            }

            val updateAvailable = isNewerVersion(tagName, current)
            val updateInfo = UpdateInfo(
                currentVersion = current,
                latestVersion = tagName,
                isUpdateAvailable = updateAvailable,
                releaseTitle = releaseName,
                releaseNotes = releaseNotes,
                downloadUrl = apkUrl,
                apkSize = apkSize
            )

            val resultStatus = if (updateAvailable) {
                UpdateStatus.Available(updateInfo)
            } else {
                UpdateStatus.UpToDate(current)
            }
            _status.value = resultStatus
            resultStatus
        } catch (e: Exception) {
            Log.e(TAG, "Update check failed: ${e.message}", e)
            val errStatus = UpdateStatus.Error("Failed to check for updates: ${e.localizedMessage}")
            _status.value = errStatus
            errStatus
        }
    }

    suspend fun downloadAndVerifyApk(info: UpdateInfo): File? = withContext(Dispatchers.IO) {
        val updatesDir = File(context.cacheDir, "updates")
        if (!updatesDir.exists()) updatesDir.mkdirs()
        val tempApk = File(updatesDir, "DexoLink-${info.latestVersion}.apk")
        if (tempApk.exists()) tempApk.delete()

        try {
            val url = URL(info.downloadUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = true
            }

            val totalBytes = if (info.apkSize > 0) info.apkSize else conn.contentLengthLong
            val inputStream = conn.inputStream
            val outputStream = FileOutputStream(tempApk)
            val buffer = ByteArray(64 * 1024)
            var bytesDownloaded = 0L
            var read: Int
            val digest = MessageDigest.getInstance("SHA-256")

            while (inputStream.read(buffer).also { read = it } != -1) {
                outputStream.write(buffer, 0, read)
                digest.update(buffer, 0, read)
                bytesDownloaded += read
                val progress = if (totalBytes > 0) (bytesDownloaded.toFloat() / totalBytes) else 0.5f
                _status.value = UpdateStatus.Downloading(progress, bytesDownloaded, totalBytes)
            }

            outputStream.flush()
            outputStream.close()
            inputStream.close()

            _status.value = UpdateStatus.Verifying(0.99f)
            val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
            Log.d(TAG, "Downloaded APK SHA-256: $sha256 (${tempApk.length()} bytes)")

            _status.value = UpdateStatus.ReadyToInstall(tempApk)
            tempApk
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}", e)
            _status.value = UpdateStatus.Error("Download failed: ${e.localizedMessage}")
            null
        }
    }

    fun installApk(apkFile: File) {
        try {
            if (!apkFile.exists()) {
                Log.e(TAG, "Cannot install: APK file does not exist: ${apkFile.absolutePath}")
                return
            }

            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }

            context.startActivity(intent)
            Log.d(TAG, "Launched PackageInstaller for ${apkFile.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed launching installer: ${e.message}", e)
            _status.value = UpdateStatus.Error("Failed to launch package installer: ${e.localizedMessage}")
        }
    }
}

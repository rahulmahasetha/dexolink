package com.macdroid.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

class FileStreamer(private val context: Context) {
    companion object {
        const val CHUNK_SIZE = 64 * 1024 // 64 KB
        const val MAX_FILE_SIZE = 2L * 1024 * 1024 * 1024 // 2 GB limit

        fun sanitizeFilename(name: String): String {
            // Prevent path traversal attacks
            var clean = File(name).name
            clean = clean.replace("..", "").replace("/", "").replace("\\", "")
            // Allow safe alphanumeric, space, dot, underscore, dash
            clean = clean.replace(Regex("[^a-zA-Z0-9._ -]"), "_")
            if (clean.isBlank()) {
                clean = "received_file_${System.currentTimeMillis()}"
            }
            return clean
        }

        fun getSafeDestinationFile(baseDir: File, filename: String): File {
            if (!baseDir.exists()) {
                baseDir.mkdirs()
            }
            val cleanName = sanitizeFilename(filename)
            var destination = File(baseDir, cleanName)

            // Strictly enforce directory containment
            val canonicalBase = baseDir.canonicalPath
            if (!destination.canonicalPath.startsWith(canonicalBase)) {
                throw SecurityException("Path traversal attempt detected in filename: $filename")
            }

            var counter = 1
            val dotIndex = cleanName.lastIndexOf('.')
            val nameWithoutExt = if (dotIndex > 0) cleanName.substring(0, dotIndex) else cleanName
            val ext = if (dotIndex > 0) cleanName.substring(dotIndex) else ""

            while (destination.exists()) {
                val candidateName = "$nameWithoutExt ($counter)$ext"
                destination = File(baseDir, candidateName)
                if (!destination.canonicalPath.startsWith(canonicalBase)) {
                    throw SecurityException("Path traversal attempt detected in renamed file")
                }
                counter++
            }
            return destination
        }
    }

    fun getFileName(uri: Uri): String {
        var name = "unnamed_file"
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        val str = cursor.getString(nameIndex)
                        if (!str.isNullOrBlank()) {
                            name = str
                        }
                    }
                }
            }
        } catch (e: Exception) {
            val lastSegment = uri.lastPathSegment
            if (!lastSegment.isNullOrBlank()) {
                name = lastSegment
            }
        }
        return sanitizeFilename(name)
    }

    fun getFileMetadata(uri: Uri): Pair<Long, String> {
        var size = 0L
        val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"

        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex != -1) {
                        size = cursor.getLong(sizeIndex)
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore
        }

        // Fallback size estimation from stream if content provider didn't return size
        if (size <= 0) {
            try {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                    size = afd.length
                }
            } catch (e: Exception) {
                // Ignore
            }
        }

        return Pair(size, mimeType)
    }

    fun computeSHA256(uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val buffer = ByteArray(CHUNK_SIZE)
            var read: Int
            while (stream.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun computeSHA256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(CHUNK_SIZE)
            var read: Int
            while (stream.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

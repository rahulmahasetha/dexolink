package com.macdroid.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

class FileStreamer(private val context: Context) {
    companion object {
        const val CHUNK_SIZE = 64 * 1024
    }

    fun getFileMetadata(uri: Uri): Pair<Long, String> {
        var size = 0L
        val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"

        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex != -1) {
                    size = cursor.getLong(sizeIndex)
                }
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
    
    // Additional methods for reading/writing chunks would go here
}

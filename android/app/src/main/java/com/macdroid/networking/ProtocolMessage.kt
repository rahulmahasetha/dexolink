package com.macdroid.networking

import org.json.JSONObject

enum class MessageType(val value: String) {
    HELLO("HELLO"),
    HELLO_ACK("HELLO_ACK"),
    PAIR_REQUEST("PAIR_REQUEST"),
    PAIR_RESPONSE("PAIR_RESPONSE"),
    TRANSFER_REQUEST("TRANSFER_REQUEST"),
    TRANSFER_RESPONSE("TRANSFER_RESPONSE"),
    FILE_META("FILE_META"),
    FILE_CHUNK("FILE_CHUNK"),
    FILE_COMPLETE("FILE_COMPLETE"),
    FILE_ACK("FILE_ACK"),
    PROGRESS("PROGRESS"),
    CANCEL("CANCEL"),
    ERROR("ERROR"),
    PING("PING"),
    PONG("PONG"),
    CLIPBOARD_PUSH("CLIPBOARD_PUSH"),
    CLIPBOARD_ACK("CLIPBOARD_ACK"),
    CLIPBOARD_MODE("CLIPBOARD_MODE");

    companion object {
        fun fromValue(value: String): MessageType? {
            return entries.find { it.value == value }
        }
    }
}

sealed class ProtocolMessage {
    abstract val type: MessageType
    abstract fun toJson(): JSONObject

    companion object {
        fun fromJson(jsonStr: String): ProtocolMessage {
            val json = JSONObject(jsonStr)
            val typeStr = json.getString("type")
            val messageType = MessageType.fromValue(typeStr)
                ?: throw IllegalArgumentException("Unknown message type: $typeStr")

            return when (messageType) {
                MessageType.HELLO -> HelloMessage(
                    version = json.optInt("version", 1),
                    deviceId = json.optString("deviceId", ""),
                    deviceName = json.optString("deviceName", "")
                )
                MessageType.HELLO_ACK -> HelloAckMessage(
                    version = json.optInt("version", 1),
                    deviceId = json.optString("deviceId", ""),
                    deviceName = json.optString("deviceName", ""),
                    paired = json.optBoolean("paired", false)
                )
                MessageType.PAIR_REQUEST -> PairRequestMessage(
                    publicKey = json.optString("publicKey", "")
                )
                MessageType.PAIR_RESPONSE -> PairResponseMessage(
                    accepted = json.optBoolean("accepted", false),
                    publicKey = json.optString("publicKey", "")
                )
                MessageType.TRANSFER_REQUEST -> TransferRequestMessage(
                    transferId = json.optString("transferId", ""),
                    totalFiles = json.optInt("totalFiles", 0),
                    totalSize = json.optLong("totalSize", 0L)
                )
                MessageType.TRANSFER_RESPONSE -> TransferResponseMessage(
                    transferId = json.optString("transferId", ""),
                    accepted = json.optBoolean("accepted", false)
                )
                MessageType.FILE_META -> FileMetaMessage(
                    transferId = json.optString("transferId", ""),
                    fileId = json.optString("fileId", ""),
                    name = json.optString("name", ""),
                    size = json.optLong("size", 0L),
                    mimeType = json.optString("mimeType", "application/octet-stream")
                )
                MessageType.FILE_CHUNK -> FileChunkMessage(
                    fileId = json.optString("fileId", ""),
                    offset = json.optLong("offset", 0L)
                )
                MessageType.FILE_COMPLETE -> FileCompleteMessage(
                    fileId = json.optString("fileId", ""),
                    sha256 = json.optString("sha256", "")
                )
                MessageType.FILE_ACK -> FileAckMessage(
                    fileId = json.optString("fileId", ""),
                    verified = json.optBoolean("verified", false)
                )
                MessageType.PROGRESS -> ProgressMessage(
                    transferId = json.optString("transferId", ""),
                    fileId = json.optString("fileId", ""),
                    bytesReceived = json.optLong("bytesReceived", 0L)
                )
                MessageType.CANCEL -> CancelMessage(
                    transferId = json.optString("transferId", "")
                )
                MessageType.ERROR -> ErrorMessage(
                    code = json.optInt("code", 0),
                    message = json.optString("message", "")
                )
                MessageType.PING -> PingMessage
                MessageType.PONG -> PongMessage
                MessageType.CLIPBOARD_PUSH -> ClipboardPushMessage(
                    sequenceNumber = json.optInt("sequenceNumber", 0),
                    content = json.optString("content", "")
                )
                MessageType.CLIPBOARD_ACK -> ClipboardAckMessage(
                    sequenceNumber = json.optInt("sequenceNumber", 0)
                )
                MessageType.CLIPBOARD_MODE -> ClipboardModeMessage(
                    mode = json.optString("mode", "two_way")
                )
            }
        }
    }
}

data class HelloMessage(val version: Int, val deviceId: String, val deviceName: String) : ProtocolMessage() {
    override val type = MessageType.HELLO
    override fun toJson() = JSONObject().apply {
        put("type", type.value)
        put("version", version)
        put("deviceId", deviceId)
        put("deviceName", deviceName)
    }
}

data class HelloAckMessage(val version: Int, val deviceId: String, val deviceName: String, val paired: Boolean) : ProtocolMessage() {
    override val type = MessageType.HELLO_ACK
    override fun toJson() = JSONObject().apply {
        put("type", type.value)
        put("version", version)
        put("deviceId", deviceId)
        put("deviceName", deviceName)
        put("paired", paired)
    }
}

data class PairRequestMessage(val publicKey: String) : ProtocolMessage() {
    override val type = MessageType.PAIR_REQUEST
    override fun toJson() = JSONObject().apply {
        put("type", type.value)
        put("publicKey", publicKey)
    }
}

data class PairResponseMessage(val accepted: Boolean, val publicKey: String) : ProtocolMessage() {
    override val type = MessageType.PAIR_RESPONSE
    override fun toJson() = JSONObject().apply {
        put("type", type.value)
        put("accepted", accepted)
        put("publicKey", publicKey)
    }
}

data class TransferRequestMessage(val transferId: String, val totalFiles: Int, val totalSize: Long) : ProtocolMessage() {
    override val type = MessageType.TRANSFER_REQUEST
    override fun toJson() = JSONObject().apply {
        put("type", type.value)
        put("transferId", transferId)
        put("totalFiles", totalFiles)
        put("totalSize", totalSize)
    }
}

data class TransferResponseMessage(val transferId: String, val accepted: Boolean) : ProtocolMessage() {
    override val type = MessageType.TRANSFER_RESPONSE
    override fun toJson() = JSONObject().apply {
        put("type", type.value)
        put("transferId", transferId)
        put("accepted", accepted)
    }
}

data class FileMetaMessage(val transferId: String, val fileId: String, val name: String, val size: Long, val mimeType: String) : ProtocolMessage() {
    override val type = MessageType.FILE_META
    override fun toJson() = JSONObject().apply {
        put("type", type.value)
        put("transferId", transferId)
        put("fileId", fileId)
        put("name", name)
        put("size", size)
        put("mimeType", mimeType)
    }
}

data class FileChunkMessage(val fileId: String, val offset: Long) : ProtocolMessage() {
    override val type = MessageType.FILE_CHUNK
    override fun toJson() = JSONObject().apply {
        put("type", type.value)
        put("fileId", fileId)
        put("offset", offset)
    }
}

data class FileCompleteMessage(val fileId: String, val sha256: String) : ProtocolMessage() {
    override val type = MessageType.FILE_COMPLETE
    override fun toJson() = JSONObject().apply {
        put("type", type.value)
        put("fileId", fileId)
        put("sha256", sha256)
    }
}

data class FileAckMessage(val fileId: String, val verified: Boolean) : ProtocolMessage() {
    override val type = MessageType.FILE_ACK
    override fun toJson() = JSONObject().apply {
        put("type", type.value)
        put("fileId", fileId)
        put("verified", verified)
    }
}

data class ProgressMessage(val transferId: String, val fileId: String, val bytesReceived: Long) : ProtocolMessage() {
    override val type = MessageType.PROGRESS
    override fun toJson() = JSONObject().apply {
        put("type", type.value)
        put("transferId", transferId)
        put("fileId", fileId)
        put("bytesReceived", bytesReceived)
    }
}

data class CancelMessage(val transferId: String) : ProtocolMessage() {
    override val type = MessageType.CANCEL
    override fun toJson() = JSONObject().apply {
        put("type", type.value)
        put("transferId", transferId)
    }
}

data class ErrorMessage(val code: Int, val message: String) : ProtocolMessage() {
    override val type = MessageType.ERROR
    override fun toJson() = JSONObject().apply {
        put("type", type.value)
        put("code", code)
        put("message", message)
    }
}

object PingMessage : ProtocolMessage() {
    override val type = MessageType.PING
    override fun toJson() = JSONObject().apply {
        put("type", type.value)
    }
}

object PongMessage : ProtocolMessage() {
    override val type = MessageType.PONG
    override fun toJson() = JSONObject().apply {
        put("type", type.value)
    }
}

data class ClipboardPushMessage(val sequenceNumber: Int, val content: String) : ProtocolMessage() {
    override val type = MessageType.CLIPBOARD_PUSH
    override fun toJson() = JSONObject().apply {
        put("type", type.value)
        put("sequenceNumber", sequenceNumber)
        put("content", content)
    }
}

data class ClipboardAckMessage(val sequenceNumber: Int) : ProtocolMessage() {
    override val type = MessageType.CLIPBOARD_ACK
    override fun toJson() = JSONObject().apply {
        put("type", type.value)
        put("sequenceNumber", sequenceNumber)
    }
}

data class ClipboardModeMessage(val mode: String) : ProtocolMessage() {
    override val type = MessageType.CLIPBOARD_MODE
    override fun toJson() = JSONObject().apply {
        put("type", type.value)
        put("mode", mode)
    }
}

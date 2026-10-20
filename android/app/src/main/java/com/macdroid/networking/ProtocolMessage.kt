package com.macdroid.networking

import org.json.JSONObject

enum class MessageType(val value: String) {
    HELLO("HELLO"),
    HELLO_ACK("HELLO_ACK"),
    PAIR_REQUEST("PAIR_REQUEST"),
    PAIR_RESPONSE("PAIR_RESPONSE"),
    UNPAIR("UNPAIR"),
    TRANSFER_REQUEST("TRANSFER_REQUEST"),
    TRANSFER_RESPONSE("TRANSFER_RESPONSE"),
    FILE_META("FILE_META"),
    FILE_CHUNK("FILE_CHUNK"),
    FILE_COMPLETE("FILE_COMPLETE"),
    FILE_ACK("FILE_ACK"),
    PROGRESS("PROGRESS"),
    CANCEL("CANCEL"),
    PAUSE("PAUSE"),
    RESUME("RESUME"),
    ERROR("ERROR"),
    PING("PING"),
    PONG("PONG"),
    CLIPBOARD_PUSH("CLIPBOARD_PUSH"),
    CLIPBOARD_ACK("CLIPBOARD_ACK"),
    CLIPBOARD_MODE("CLIPBOARD_MODE"),
    CONTINUITY_ACTIVITY("CONTINUITY_ACTIVITY"),
    CONTINUITY_ACK("CONTINUITY_ACK"),
    CONTINUITY_DISMISS("CONTINUITY_DISMISS"),
    CONTINUITY_STATE("CONTINUITY_STATE"),
    STREAM_REQUEST("STREAM_REQUEST"),
    STREAM_RESPONSE("STREAM_RESPONSE"),
    STREAM_FRAME("STREAM_FRAME"),
    STREAM_STOP("STREAM_STOP"),
    DESKTOP_INPUT_EVENT("DESKTOP_INPUT_EVENT");

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
                    publicKey = json.optString("publicKey", ""),
                    reason = json.optString("reason", "")
                )
                MessageType.UNPAIR -> UnpairMessage
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
                    offset = json.optLong("offset", 0L),
                    data = json.optString("data", "")
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
                MessageType.PAUSE -> PauseMessage(
                    transferId = json.optString("transferId", "")
                )
                MessageType.RESUME -> ResumeMessage(
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
                MessageType.CONTINUITY_ACTIVITY -> ContinuityActivityMessage(
                    activityId = json.optString("activityId", ""),
                    activityType = json.optString("activityType", "webpage"),
                    title = json.optString("title", ""),
                    subtitle = json.optString("subtitle", ""),
                    url = if (json.has("url") && !json.isNull("url")) json.getString("url") else null,
                    contentPayload = if (json.has("contentPayload") && !json.isNull("contentPayload")) json.getString("contentPayload") else null,
                    position = json.optInt("position", 0),
                    progress = json.optDouble("progress", 0.0),
                    timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                    sourceDevice = json.optString("sourceDevice", "Mac")
                )
                MessageType.CONTINUITY_ACK -> ContinuityAckMessage(
                    activityId = json.optString("activityId", ""),
                    accepted = json.optBoolean("accepted", false)
                )
                MessageType.CONTINUITY_DISMISS -> ContinuityDismissMessage(
                    activityId = json.optString("activityId", "")
                )
                MessageType.CONTINUITY_STATE -> ContinuityStateMessage(
                    enabled = json.optBoolean("enabled", true)
                )
                MessageType.STREAM_REQUEST -> StreamRequestMessage(
                    streamId = json.optString("streamId", ""),
                    streamType = json.optString("streamType", ""),
                    options = if (json.has("options")) {
                        val optObj = json.getJSONObject("options")
                        val map = mutableMapOf<String, String>()
                        optObj.keys().forEach { k -> map[k] = optObj.getString(k) }
                        map
                    } else null
                )
                MessageType.STREAM_RESPONSE -> StreamResponseMessage(
                    streamId = json.optString("streamId", ""),
                    streamType = json.optString("streamType", ""),
                    accepted = json.optBoolean("accepted", false),
                    reason = if (json.has("reason") && !json.isNull("reason")) json.getString("reason") else null
                )
                MessageType.STREAM_FRAME -> StreamFrameMessage(
                    streamId = json.optString("streamId", ""),
                    sequence = json.optLong("sequence", 0L),
                    format = json.optString("format", "jpeg"),
                    width = json.optInt("width", 0),
                    height = json.optInt("height", 0),
                    data = json.optString("data", "")
                )
                MessageType.STREAM_STOP -> StreamStopMessage(
                    streamId = json.optString("streamId", ""),
                    reason = if (json.has("reason") && !json.isNull("reason")) json.getString("reason") else null
                )
                MessageType.DESKTOP_INPUT_EVENT -> DesktopInputEventMessage(
                    streamId = json.optString("streamId", ""),
                    eventType = json.optString("eventType", ""),
                    x = json.optDouble("x", 0.0),
                    y = json.optDouble("y", 0.0),
                    button = json.optInt("button", 0),
                    key = if (json.has("key") && !json.isNull("key")) json.getString("key") else null
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

data class PairResponseMessage(
    val accepted: Boolean,
    val publicKey: String,
    val reason: String = ""
) : ProtocolMessage() {
    override val type = MessageType.PAIR_RESPONSE
    override fun toJson() = JSONObject().apply {
        put("type", type.value)
        put("accepted", accepted)
        put("publicKey", publicKey)
        if (reason.isNotBlank()) {
            put("reason", reason)
        }
    }
}

object UnpairMessage : ProtocolMessage() {
    override val type = MessageType.UNPAIR
    override fun toJson() = JSONObject().apply {
        put("type", type.value)
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

data class FileChunkMessage(val fileId: String, val offset: Long, val data: String = "") : ProtocolMessage() {
    override val type = MessageType.FILE_CHUNK
    override fun toJson() = JSONObject().apply {
        put("type", type.value)
        put("fileId", fileId)
        put("offset", offset)
        put("data", data)
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

data class PauseMessage(val transferId: String) : ProtocolMessage() {
    override val type = MessageType.PAUSE
    override fun toJson() = JSONObject().apply {
        put("type", type.value)
        put("transferId", transferId)
    }
}

data class ResumeMessage(val transferId: String) : ProtocolMessage() {
    override val type = MessageType.RESUME
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

data class ContinuityActivityMessage(
    val activityId: String,
    val activityType: String,
    val title: String,
    val subtitle: String = "",
    val url: String? = null,
    val contentPayload: String? = null,
    val position: Int = 0,
    val progress: Double = 0.0,
    val timestamp: Long = System.currentTimeMillis(),
    val sourceDevice: String = ""
) : ProtocolMessage() {
    override val type = MessageType.CONTINUITY_ACTIVITY
    override fun toJson() = JSONObject().apply {
        put("type", type.value)
        put("activityId", activityId)
        put("activityType", activityType)
        put("title", title)
        put("subtitle", subtitle)
        url?.let { put("url", it) }
        contentPayload?.let { put("contentPayload", it) }
        put("position", position)
        put("progress", progress)
        put("timestamp", timestamp)
        put("sourceDevice", sourceDevice)
    }
}

data class ContinuityAckMessage(
    val activityId: String,
    val accepted: Boolean
) : ProtocolMessage() {
    override val type = MessageType.CONTINUITY_ACK
    override fun toJson() = JSONObject().apply {
        put("type", type.value)
        put("activityId", activityId)
        put("accepted", accepted)
    }
}

data class ContinuityDismissMessage(
    val activityId: String
) : ProtocolMessage() {
    override val type = MessageType.CONTINUITY_DISMISS
    override fun toJson() = JSONObject().apply {
        put("type", type.value)
        put("activityId", activityId)
    }
}

data class ContinuityStateMessage(
    val enabled: Boolean
) : ProtocolMessage() {
    override val type = MessageType.CONTINUITY_STATE
    override fun toJson() = JSONObject().apply {
        put("type", type.value)
        put("enabled", enabled)
    }
}

data class StreamRequestMessage(
    val streamId: String,
    val streamType: String,
    val options: Map<String, String>? = null
) : ProtocolMessage() {
    override val type = MessageType.STREAM_REQUEST
    override fun toJson() = JSONObject().apply {
        put("type", type.value)
        put("streamId", streamId)
        put("streamType", streamType)
        options?.let { opt ->
            val optObj = JSONObject()
            opt.forEach { (k, v) -> optObj.put(k, v) }
            put("options", optObj)
        }
    }
}

data class StreamResponseMessage(
    val streamId: String,
    val streamType: String,
    val accepted: Boolean,
    val reason: String? = null
) : ProtocolMessage() {
    override val type = MessageType.STREAM_RESPONSE
    override fun toJson() = JSONObject().apply {
        put("type", type.value)
        put("streamId", streamId)
        put("streamType", streamType)
        put("accepted", accepted)
        reason?.let { put("reason", it) }
    }
}

data class StreamFrameMessage(
    val streamId: String,
    val sequence: Long,
    val format: String,
    val width: Int,
    val height: Int,
    val data: String
) : ProtocolMessage() {
    override val type = MessageType.STREAM_FRAME
    override fun toJson() = JSONObject().apply {
        put("type", type.value)
        put("streamId", streamId)
        put("sequence", sequence)
        put("format", format)
        put("width", width)
        put("height", height)
        put("data", data)
    }
}

data class StreamStopMessage(
    val streamId: String,
    val reason: String? = null
) : ProtocolMessage() {
    override val type = MessageType.STREAM_STOP
    override fun toJson() = JSONObject().apply {
        put("type", type.value)
        put("streamId", streamId)
        reason?.let { put("reason", it) }
    }
}

data class DesktopInputEventMessage(
    val streamId: String,
    val eventType: String,
    val x: Double,
    val y: Double,
    val button: Int = 0,
    val key: String? = null
) : ProtocolMessage() {
    override val type = MessageType.DESKTOP_INPUT_EVENT
    override fun toJson() = JSONObject().apply {
        put("type", type.value)
        put("streamId", streamId)
        put("eventType", eventType)
        put("x", x)
        put("y", y)
        put("button", button)
        key?.let { put("key", it) }
    }
}



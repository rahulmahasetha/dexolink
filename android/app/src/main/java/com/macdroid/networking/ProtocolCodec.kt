package com.macdroid.networking

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

class ProtocolCodec {
    companion object {
        const val MAX_MESSAGE_SIZE = 10 * 1024 * 1024 // 10 MB
    }

    fun encode(message: ProtocolMessage): ByteArray {
        val jsonBytes = message.toJson().toString().toByteArray(StandardCharsets.UTF_8)
        return encodePayload(jsonBytes)
    }

    fun encodePayload(payload: ByteArray): ByteArray {
        require(payload.size <= MAX_MESSAGE_SIZE) { "Message too large" }
        
        val buffer = ByteBuffer.allocate(4 + payload.size)
        buffer.putInt(payload.size)
        buffer.put(payload)
        return buffer.array()
    }

    fun decodePayload(buffer: ByteBuffer): Pair<ByteArray, Int>? {
        if (buffer.remaining() < 4) return null
        
        buffer.mark()
        val length = buffer.int
        
        if (length > MAX_MESSAGE_SIZE || length < 0) {
            throw IllegalArgumentException("Invalid message length: $length")
        }
        
        if (buffer.remaining() < length) {
            buffer.reset()
            return null
        }
        
        val payload = ByteArray(length)
        buffer.get(payload)
        return Pair(payload, 4 + length)
    }

    fun decodeMessage(payload: ByteArray): ProtocolMessage {
        val jsonStr = String(payload, StandardCharsets.UTF_8)
        return ProtocolMessage.fromJson(jsonStr)
    }
}

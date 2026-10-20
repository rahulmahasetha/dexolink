import Foundation

public enum ProtocolMessage: Codable, Equatable {
    case hello(version: Int, deviceId: String, deviceName: String)
    case helloAck(version: Int, deviceId: String, deviceName: String, paired: Bool)
    case pairRequest(publicKey: String)
    case pairResponse(accepted: Bool, publicKey: String, reason: String? = nil)
    case unpair
    case transferRequest(transferId: String, totalFiles: Int, totalSize: Int64)
    case transferResponse(transferId: String, accepted: Bool)
    case fileMeta(transferId: String, fileId: String, name: String, size: Int64, mimeType: String)
    case fileChunk(fileId: String, offset: Int64, data: String)
    case fileComplete(fileId: String, sha256: String)
    case fileAck(fileId: String, verified: Bool)
    case progress(transferId: String, fileId: String, bytesReceived: Int64)
    case cancel(transferId: String)
    case pause(transferId: String)
    case resume(transferId: String)
    case error(code: Int, message: String)
    case ping
    case pong
    case clipboardPush(sequenceNumber: Int, content: String)
    case clipboardAck(sequenceNumber: Int)
    case clipboardMode(mode: String)
    case continuityActivity(
        activityId: String,
        activityType: String,
        title: String,
        subtitle: String,
        url: String?,
        contentPayload: String?,
        position: Int,
        progress: Double,
        timestamp: Int64,
        sourceDevice: String
    )
    case continuityAck(activityId: String, accepted: Bool)
    case continuityDismiss(activityId: String)
    case continuityState(enabled: Bool)
    case streamRequest(streamId: String, streamType: String, options: [String: String]? = nil)
    case streamResponse(streamId: String, streamType: String, accepted: Bool, reason: String? = nil)
    case streamFrame(streamId: String, sequence: Int64, format: String, width: Int, height: Int, data: String)
    case streamStop(streamId: String, reason: String? = nil)
    case desktopInputEvent(streamId: String, eventType: String, x: Double, y: Double, button: Int, key: String? = nil)
    case webrtcSignal(streamId: String, streamType: String, signalType: String, sdp: String? = nil, candidate: String? = nil, sdpMid: String? = nil, sdpMLineIndex: Int? = nil, options: [String: String]? = nil)

    private enum CodingKeys: String, CodingKey {
        case type, version, deviceId, deviceName, paired, publicKey, accepted, reason
        case transferId, totalFiles, totalSize, fileId, name, size, mimeType
        case offset, data, sha256, verified, bytesReceived, code, message
        case sequenceNumber, content, mode
        case activityId, activityType, title, subtitle, url, contentPayload, position, progress, timestamp, sourceDevice, enabled
        case streamId, streamType, format, sequence, width, height, eventType, x, y, button, key, options
        case signalType, sdp, candidate, sdpMid, sdpMLineIndex
    }

    private enum MessageType: String, Codable {
        case hello = "HELLO"
        case helloAck = "HELLO_ACK"
        case pairRequest = "PAIR_REQUEST"
        case pairResponse = "PAIR_RESPONSE"
        case unpair = "UNPAIR"
        case transferRequest = "TRANSFER_REQUEST"
        case transferResponse = "TRANSFER_RESPONSE"
        case fileMeta = "FILE_META"
        case fileChunk = "FILE_CHUNK"
        case fileComplete = "FILE_COMPLETE"
        case fileAck = "FILE_ACK"
        case progress = "PROGRESS"
        case cancel = "CANCEL"
        case pause = "PAUSE"
        case resume = "RESUME"
        case error = "ERROR"
        case ping = "PING"
        case pong = "PONG"
        case clipboardPush = "CLIPBOARD_PUSH"
        case clipboardAck = "CLIPBOARD_ACK"
        case clipboardMode = "CLIPBOARD_MODE"
        case continuityActivity = "CONTINUITY_ACTIVITY"
        case continuityAck = "CONTINUITY_ACK"
        case continuityDismiss = "CONTINUITY_DISMISS"
        case continuityState = "CONTINUITY_STATE"
        case streamRequest = "STREAM_REQUEST"
        case streamResponse = "STREAM_RESPONSE"
        case streamFrame = "STREAM_FRAME"
        case streamStop = "STREAM_STOP"
        case desktopInputEvent = "DESKTOP_INPUT_EVENT"
        case webrtcSignal = "WEBRTC_SIGNAL"
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let type = try container.decode(MessageType.self, forKey: .type)

        switch type {
        case .hello:
            self = .hello(
                version: try container.decode(Int.self, forKey: .version),
                deviceId: try container.decode(String.self, forKey: .deviceId),
                deviceName: try container.decode(String.self, forKey: .deviceName)
            )
        case .helloAck:
            self = .helloAck(
                version: try container.decode(Int.self, forKey: .version),
                deviceId: try container.decode(String.self, forKey: .deviceId),
                deviceName: try container.decode(String.self, forKey: .deviceName),
                paired: try container.decode(Bool.self, forKey: .paired)
            )
        case .pairRequest:
            self = .pairRequest(publicKey: try container.decode(String.self, forKey: .publicKey))
        case .pairResponse:
            self = .pairResponse(
                accepted: try container.decode(Bool.self, forKey: .accepted),
                publicKey: try container.decode(String.self, forKey: .publicKey),
                reason: try container.decodeIfPresent(String.self, forKey: .reason)
            )
        case .unpair:
            self = .unpair
        case .transferRequest:
            self = .transferRequest(
                transferId: try container.decode(String.self, forKey: .transferId),
                totalFiles: try container.decode(Int.self, forKey: .totalFiles),
                totalSize: try container.decode(Int64.self, forKey: .totalSize)
            )
        case .transferResponse:
            self = .transferResponse(
                transferId: try container.decode(String.self, forKey: .transferId),
                accepted: try container.decode(Bool.self, forKey: .accepted)
            )
        case .fileMeta:
            self = .fileMeta(
                transferId: try container.decode(String.self, forKey: .transferId),
                fileId: try container.decode(String.self, forKey: .fileId),
                name: try container.decode(String.self, forKey: .name),
                size: try container.decode(Int64.self, forKey: .size),
                mimeType: try container.decode(String.self, forKey: .mimeType)
            )
        case .fileChunk:
            self = .fileChunk(
                fileId: try container.decode(String.self, forKey: .fileId),
                offset: try container.decode(Int64.self, forKey: .offset),
                data: try container.decodeIfPresent(String.self, forKey: .data) ?? ""
            )
        case .fileComplete:
            self = .fileComplete(
                fileId: try container.decode(String.self, forKey: .fileId),
                sha256: try container.decode(String.self, forKey: .sha256)
            )
        case .fileAck:
            self = .fileAck(
                fileId: try container.decode(String.self, forKey: .fileId),
                verified: try container.decode(Bool.self, forKey: .verified)
            )
        case .progress:
            self = .progress(
                transferId: try container.decode(String.self, forKey: .transferId),
                fileId: try container.decode(String.self, forKey: .fileId),
                bytesReceived: try container.decode(Int64.self, forKey: .bytesReceived)
            )
        case .cancel:
            self = .cancel(transferId: try container.decode(String.self, forKey: .transferId))
        case .pause:
            self = .pause(transferId: try container.decode(String.self, forKey: .transferId))
        case .resume:
            self = .resume(transferId: try container.decode(String.self, forKey: .transferId))
        case .error:
            self = .error(
                code: try container.decode(Int.self, forKey: .code),
                message: try container.decode(String.self, forKey: .message)
            )
        case .ping: self = .ping
        case .pong: self = .pong
        case .clipboardPush:
            self = .clipboardPush(
                sequenceNumber: try container.decode(Int.self, forKey: .sequenceNumber),
                content: try container.decode(String.self, forKey: .content)
            )
        case .clipboardAck:
            self = .clipboardAck(sequenceNumber: try container.decode(Int.self, forKey: .sequenceNumber))
        case .clipboardMode:
            self = .clipboardMode(mode: try container.decode(String.self, forKey: .mode))
        case .continuityActivity:
            self = .continuityActivity(
                activityId: try container.decode(String.self, forKey: .activityId),
                activityType: try container.decode(String.self, forKey: .activityType),
                title: try container.decode(String.self, forKey: .title),
                subtitle: try container.decodeIfPresent(String.self, forKey: .subtitle) ?? "",
                url: try container.decodeIfPresent(String.self, forKey: .url),
                contentPayload: try container.decodeIfPresent(String.self, forKey: .contentPayload),
                position: try container.decodeIfPresent(Int.self, forKey: .position) ?? 0,
                progress: try container.decodeIfPresent(Double.self, forKey: .progress) ?? 0.0,
                timestamp: try container.decodeIfPresent(Int64.self, forKey: .timestamp) ?? Int64(Date().timeIntervalSince1970 * 1000),
                sourceDevice: try container.decodeIfPresent(String.self, forKey: .sourceDevice) ?? ""
            )
        case .continuityAck:
            self = .continuityAck(
                activityId: try container.decode(String.self, forKey: .activityId),
                accepted: try container.decode(Bool.self, forKey: .accepted)
            )
        case .continuityDismiss:
            self = .continuityDismiss(
                activityId: try container.decode(String.self, forKey: .activityId)
            )
        case .continuityState:
            self = .continuityState(
                enabled: try container.decode(Bool.self, forKey: .enabled)
            )
        case .streamRequest:
            self = .streamRequest(
                streamId: try container.decode(String.self, forKey: .streamId),
                streamType: try container.decode(String.self, forKey: .streamType),
                options: try container.decodeIfPresent([String: String].self, forKey: .options)
            )
        case .streamResponse:
            self = .streamResponse(
                streamId: try container.decode(String.self, forKey: .streamId),
                streamType: try container.decode(String.self, forKey: .streamType),
                accepted: try container.decode(Bool.self, forKey: .accepted),
                reason: try container.decodeIfPresent(String.self, forKey: .reason)
            )
        case .streamFrame:
            self = .streamFrame(
                streamId: try container.decode(String.self, forKey: .streamId),
                sequence: try container.decode(Int64.self, forKey: .sequence),
                format: try container.decode(String.self, forKey: .format),
                width: try container.decode(Int.self, forKey: .width),
                height: try container.decode(Int.self, forKey: .height),
                data: try container.decode(String.self, forKey: .data)
            )
        case .streamStop:
            self = .streamStop(
                streamId: try container.decode(String.self, forKey: .streamId),
                reason: try container.decodeIfPresent(String.self, forKey: .reason)
            )
        case .desktopInputEvent:
            self = .desktopInputEvent(
                streamId: try container.decode(String.self, forKey: .streamId),
                eventType: try container.decode(String.self, forKey: .eventType),
                x: try container.decode(Double.self, forKey: .x),
                y: try container.decode(Double.self, forKey: .y),
                button: try container.decode(Int.self, forKey: .button),
                key: try container.decodeIfPresent(String.self, forKey: .key)
            )
        case .webrtcSignal:
            self = .webrtcSignal(
                streamId: try container.decode(String.self, forKey: .streamId),
                streamType: try container.decode(String.self, forKey: .streamType),
                signalType: try container.decode(String.self, forKey: .signalType),
                sdp: try container.decodeIfPresent(String.self, forKey: .sdp),
                candidate: try container.decodeIfPresent(String.self, forKey: .candidate),
                sdpMid: try container.decodeIfPresent(String.self, forKey: .sdpMid),
                sdpMLineIndex: try container.decodeIfPresent(Int.self, forKey: .sdpMLineIndex),
                options: try container.decodeIfPresent([String: String].self, forKey: .options)
            )
        }
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        switch self {
        case let .hello(version, deviceId, deviceName):
            try container.encode(MessageType.hello, forKey: .type)
            try container.encode(version, forKey: .version)
            try container.encode(deviceId, forKey: .deviceId)
            try container.encode(deviceName, forKey: .deviceName)
        case let .helloAck(version, deviceId, deviceName, paired):
            try container.encode(MessageType.helloAck, forKey: .type)
            try container.encode(version, forKey: .version)
            try container.encode(deviceId, forKey: .deviceId)
            try container.encode(deviceName, forKey: .deviceName)
            try container.encode(paired, forKey: .paired)
        case let .pairRequest(publicKey):
            try container.encode(MessageType.pairRequest, forKey: .type)
            try container.encode(publicKey, forKey: .publicKey)
        case let .pairResponse(accepted, publicKey, reason):
            try container.encode(MessageType.pairResponse, forKey: .type)
            try container.encode(accepted, forKey: .accepted)
            try container.encode(publicKey, forKey: .publicKey)
            if let reason = reason {
                try container.encode(reason, forKey: .reason)
            }
        case .unpair:
            try container.encode(MessageType.unpair, forKey: .type)
        case let .transferRequest(transferId, totalFiles, totalSize):
            try container.encode(MessageType.transferRequest, forKey: .type)
            try container.encode(transferId, forKey: .transferId)
            try container.encode(totalFiles, forKey: .totalFiles)
            try container.encode(totalSize, forKey: .totalSize)
        case let .transferResponse(transferId, accepted):
            try container.encode(MessageType.transferResponse, forKey: .type)
            try container.encode(transferId, forKey: .transferId)
            try container.encode(accepted, forKey: .accepted)
        case let .fileMeta(transferId, fileId, name, size, mimeType):
            try container.encode(MessageType.fileMeta, forKey: .type)
            try container.encode(transferId, forKey: .transferId)
            try container.encode(fileId, forKey: .fileId)
            try container.encode(name, forKey: .name)
            try container.encode(size, forKey: .size)
            try container.encode(mimeType, forKey: .mimeType)
        case let .fileChunk(fileId, offset, data):
            try container.encode(MessageType.fileChunk, forKey: .type)
            try container.encode(fileId, forKey: .fileId)
            try container.encode(offset, forKey: .offset)
            try container.encode(data, forKey: .data)
        case let .fileComplete(fileId, sha256):
            try container.encode(MessageType.fileComplete, forKey: .type)
            try container.encode(fileId, forKey: .fileId)
            try container.encode(sha256, forKey: .sha256)
        case let .fileAck(fileId, verified):
            try container.encode(MessageType.fileAck, forKey: .type)
            try container.encode(fileId, forKey: .fileId)
            try container.encode(verified, forKey: .verified)
        case let .progress(transferId, fileId, bytesReceived):
            try container.encode(MessageType.progress, forKey: .type)
            try container.encode(transferId, forKey: .transferId)
            try container.encode(fileId, forKey: .fileId)
            try container.encode(bytesReceived, forKey: .bytesReceived)
        case let .cancel(transferId):
            try container.encode(MessageType.cancel, forKey: .type)
            try container.encode(transferId, forKey: .transferId)
        case let .pause(transferId):
            try container.encode(MessageType.pause, forKey: .type)
            try container.encode(transferId, forKey: .transferId)
        case let .resume(transferId):
            try container.encode(MessageType.resume, forKey: .type)
            try container.encode(transferId, forKey: .transferId)
        case let .error(code, message):
            try container.encode(MessageType.error, forKey: .type)
            try container.encode(code, forKey: .code)
            try container.encode(message, forKey: .message)
        case .ping:
            try container.encode(MessageType.ping, forKey: .type)
        case .pong:
            try container.encode(MessageType.pong, forKey: .type)
        case let .clipboardPush(sequenceNumber, content):
            try container.encode(MessageType.clipboardPush, forKey: .type)
            try container.encode(sequenceNumber, forKey: .sequenceNumber)
            try container.encode(content, forKey: .content)
        case let .clipboardAck(sequenceNumber):
            try container.encode(MessageType.clipboardAck, forKey: .type)
            try container.encode(sequenceNumber, forKey: .sequenceNumber)
        case let .clipboardMode(mode):
            try container.encode(MessageType.clipboardMode, forKey: .type)
            try container.encode(mode, forKey: .mode)
        case let .continuityActivity(activityId, activityType, title, subtitle, url, contentPayload, position, progress, timestamp, sourceDevice):
            try container.encode(MessageType.continuityActivity, forKey: .type)
            try container.encode(activityId, forKey: .activityId)
            try container.encode(activityType, forKey: .activityType)
            try container.encode(title, forKey: .title)
            try container.encode(subtitle, forKey: .subtitle)
            if let url = url { try container.encode(url, forKey: .url) }
            if let contentPayload = contentPayload { try container.encode(contentPayload, forKey: .contentPayload) }
            try container.encode(position, forKey: .position)
            try container.encode(progress, forKey: .progress)
            try container.encode(timestamp, forKey: .timestamp)
            try container.encode(sourceDevice, forKey: .sourceDevice)
        case let .continuityAck(activityId, accepted):
            try container.encode(MessageType.continuityAck, forKey: .type)
            try container.encode(activityId, forKey: .activityId)
            try container.encode(accepted, forKey: .accepted)
        case let .continuityDismiss(activityId):
            try container.encode(MessageType.continuityDismiss, forKey: .type)
            try container.encode(activityId, forKey: .activityId)
        case let .continuityState(enabled):
            try container.encode(MessageType.continuityState, forKey: .type)
            try container.encode(enabled, forKey: .enabled)
        case let .streamRequest(streamId, streamType, options):
            try container.encode(MessageType.streamRequest, forKey: .type)
            try container.encode(streamId, forKey: .streamId)
            try container.encode(streamType, forKey: .streamType)
            if let options = options { try container.encode(options, forKey: .options) }
        case let .streamResponse(streamId, streamType, accepted, reason):
            try container.encode(MessageType.streamResponse, forKey: .type)
            try container.encode(streamId, forKey: .streamId)
            try container.encode(streamType, forKey: .streamType)
            try container.encode(accepted, forKey: .accepted)
            if let reason = reason { try container.encode(reason, forKey: .reason) }
        case let .streamFrame(streamId, sequence, format, width, height, data):
            try container.encode(MessageType.streamFrame, forKey: .type)
            try container.encode(streamId, forKey: .streamId)
            try container.encode(sequence, forKey: .sequence)
            try container.encode(format, forKey: .format)
            try container.encode(width, forKey: .width)
            try container.encode(height, forKey: .height)
            try container.encode(data, forKey: .data)
        case let .streamStop(streamId, reason):
            try container.encode(MessageType.streamStop, forKey: .type)
            try container.encode(streamId, forKey: .streamId)
            if let reason = reason { try container.encode(reason, forKey: .reason) }
        case let .desktopInputEvent(streamId, eventType, x, y, button, key):
            try container.encode(MessageType.desktopInputEvent, forKey: .type)
            try container.encode(streamId, forKey: .streamId)
            try container.encode(eventType, forKey: .eventType)
            try container.encode(x, forKey: .x)
            try container.encode(y, forKey: .y)
            try container.encode(button, forKey: .button)
            if let key = key { try container.encode(key, forKey: .key) }
        case let .webrtcSignal(streamId, streamType, signalType, sdp, candidate, sdpMid, sdpMLineIndex, options):
            try container.encode(MessageType.webrtcSignal, forKey: .type)
            try container.encode(streamId, forKey: .streamId)
            try container.encode(streamType, forKey: .streamType)
            try container.encode(signalType, forKey: .signalType)
            if let sdp = sdp { try container.encode(sdp, forKey: .sdp) }
            if let candidate = candidate { try container.encode(candidate, forKey: .candidate) }
            if let sdpMid = sdpMid { try container.encode(sdpMid, forKey: .sdpMid) }
            if let sdpMLineIndex = sdpMLineIndex { try container.encode(sdpMLineIndex, forKey: .sdpMLineIndex) }
            if let options = options { try container.encode(options, forKey: .options) }
        }
    }
}

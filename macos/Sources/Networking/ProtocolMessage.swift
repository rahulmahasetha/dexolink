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
    case fileChunk(fileId: String, offset: Int64)
    case fileComplete(fileId: String, sha256: String)
    case fileAck(fileId: String, verified: Bool)
    case progress(transferId: String, fileId: String, bytesReceived: Int64)
    case cancel(transferId: String)
    case error(code: Int, message: String)
    case ping
    case pong
    case clipboardPush(sequenceNumber: Int, content: String)
    case clipboardAck(sequenceNumber: Int)
    case clipboardMode(mode: String)

    private enum CodingKeys: String, CodingKey {
        case type, version, deviceId, deviceName, paired, publicKey, accepted, reason
        case transferId, totalFiles, totalSize, fileId, name, size, mimeType
        case offset, sha256, verified, bytesReceived, code, message
        case sequenceNumber, content, mode
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
        case error = "ERROR"
        case ping = "PING"
        case pong = "PONG"
        case clipboardPush = "CLIPBOARD_PUSH"
        case clipboardAck = "CLIPBOARD_ACK"
        case clipboardMode = "CLIPBOARD_MODE"
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
                offset: try container.decode(Int64.self, forKey: .offset)
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
        case let .fileChunk(fileId, offset):
            try container.encode(MessageType.fileChunk, forKey: .type)
            try container.encode(fileId, forKey: .fileId)
            try container.encode(offset, forKey: .offset)
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
        }
    }
}

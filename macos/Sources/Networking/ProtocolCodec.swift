import Foundation

public enum ProtocolCodecError: Error {
    case invalidLength
    case messageTooLarge
    case invalidJSON
    case insufficientData
}

public class ProtocolCodec {
    public static let maxMessageSize: Int = 10 * 1024 * 1024 // 10 MB max for normal messages, chunk binary is separate
    
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()
    
    public init() {}
    
    /// Encodes a protocol message into length-prefixed data.
    public func encode(message: ProtocolMessage) throws -> Data {
        let jsonData = try encoder.encode(message)
        return try encode(payload: jsonData)
    }
    
    /// Encodes a raw payload into length-prefixed data.
    public func encode(payload: Data) throws -> Data {
        let length = UInt32(payload.count)
        guard length <= ProtocolCodec.maxMessageSize else {
            throw ProtocolCodecError.messageTooLarge
        }
        
        var data = Data(capacity: 4 + payload.count)
        var bigEndianLength = length.bigEndian
        data.append(Data(bytes: &bigEndianLength, count: 4))
        data.append(payload)
        
        return data
    }
    
    /// Attempts to decode a payload from the given buffer.
    /// - Returns: A tuple containing the decoded payload and the number of bytes consumed, or nil if more data is needed.
    public func decodePayload(from buffer: Data) throws -> (payload: Data, consumedBytes: Int)? {
        guard buffer.count >= 4 else {
            return nil
        }
        
        let start = buffer.startIndex
        let length = UInt32(buffer[start]) << 24 |
                     UInt32(buffer[start + 1]) << 16 |
                     UInt32(buffer[start + 2]) << 8 |
                     UInt32(buffer[start + 3])
        
        guard length <= ProtocolCodec.maxMessageSize else {
            throw ProtocolCodecError.messageTooLarge
        }
        
        let totalRequired = 4 + Int(length)
        guard buffer.count >= totalRequired else {
            return nil
        }
        
        let payload = buffer.subdata(in: start + 4 ..< start + totalRequired)
        return (payload, totalRequired)
    }
    
    /// Attempts to decode a `ProtocolMessage` from a JSON payload.
    public func decodeMessage(from payload: Data) throws -> ProtocolMessage {
        return try decoder.decode(ProtocolMessage.self, from: payload)
    }
}

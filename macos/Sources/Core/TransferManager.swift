import Foundation
import Networking
import Crypto

public enum TransferState {
    case pending
    case transferring(progress: Double)
    case verifying
    case completed
    case failed(Error)
    case cancelled
}

public struct TransferInfo {
    public let id: String
    public let totalFiles: Int
    public let totalSize: Int64
    public var bytesTransferred: Int64 = 0
}

public protocol TransferManagerDelegate: AnyObject {
    func transferManager(_ manager: TransferManager, didRequestTransfer info: TransferInfo)
    func transferManager(_ manager: TransferManager, didUpdateState state: TransferState, for transferId: String)
}

public class TransferManager {
    public weak var delegate: TransferManagerDelegate?
    
    private let connectionManager: ConnectionManager
    private var activeTransfers: [String: TransferInfo] = [:]
    
    // For simplicity in this mock, we just store the download directory
    private let downloadDirectory: URL
    
    public init(connectionManager: ConnectionManager) {
        self.connectionManager = connectionManager
        
        let urls = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)
        self.downloadDirectory = urls.first!
    }
    
    public func requestTransfer(files: [URL]) {
        guard !files.isEmpty else { return }
        
        let transferId = UUID().uuidString
        var totalSize: Int64 = 0
        
        for file in files {
            if let meta = try? FileStreamer.getFileMetadata(url: file) {
                totalSize += meta.size
            }
        }
        
        let info = TransferInfo(id: transferId, totalFiles: files.count, totalSize: totalSize)
        activeTransfers[transferId] = info
        
        let request = ProtocolMessage.transferRequest(
            transferId: transferId,
            totalFiles: files.count,
            totalSize: totalSize
        )
        
        connectionManager.send(message: request)
        delegate?.transferManager(self, didUpdateState: .pending, for: transferId)
    }
    
    public func acceptTransfer(transferId: String) {
        let response = ProtocolMessage.transferResponse(transferId: transferId, accepted: true)
        connectionManager.send(message: response)
        delegate?.transferManager(self, didUpdateState: .transferring(progress: 0), for: transferId)
    }
    
    public func rejectTransfer(transferId: String) {
        let response = ProtocolMessage.transferResponse(transferId: transferId, accepted: false)
        connectionManager.send(message: response)
        activeTransfers.removeValue(forKey: transferId)
        delegate?.transferManager(self, didUpdateState: .cancelled, for: transferId)
    }
    
    public func handleMessage(_ message: ProtocolMessage) {
        switch message {
        case let .transferRequest(transferId, totalFiles, totalSize):
            let info = TransferInfo(id: transferId, totalFiles: totalFiles, totalSize: totalSize)
            activeTransfers[transferId] = info
            delegate?.transferManager(self, didRequestTransfer: info)
            
        case let .transferResponse(transferId, accepted):
            if accepted {
                delegate?.transferManager(self, didUpdateState: .transferring(progress: 0), for: transferId)
                // Start sending files here
            } else {
                activeTransfers.removeValue(forKey: transferId)
                delegate?.transferManager(self, didUpdateState: .cancelled, for: transferId)
            }
            
        case let .progress(transferId, _, bytesReceived):
            if var info = activeTransfers[transferId] {
                info.bytesTransferred = bytesReceived
                activeTransfers[transferId] = info
                
                let progress = info.totalSize > 0 ? Double(info.bytesTransferred) / Double(info.totalSize) : 0
                delegate?.transferManager(self, didUpdateState: .transferring(progress: progress), for: transferId)
            }
            
        case let .cancel(transferId):
            activeTransfers.removeValue(forKey: transferId)
            delegate?.transferManager(self, didUpdateState: .cancelled, for: transferId)
            
        default:
            break // Handle other file chunks, metadata, completion in a full implementation
        }
    }
}

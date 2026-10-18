import Foundation
import Networking
import Crypto
import CryptoKit

public enum TransferState: Equatable {
    case pending
    case transferring(progress: Double)
    case paused(progress: Double)
    case verifying
    case completed
    case failed(String)
    case cancelled

    public static func == (lhs: TransferState, rhs: TransferState) -> Bool {
        switch (lhs, rhs) {
        case (.pending, .pending): return true
        case let (.transferring(p1), .transferring(p2)): return abs(p1 - p2) < 0.001
        case let (.paused(p1), .paused(p2)): return abs(p1 - p2) < 0.001
        case (.verifying, .verifying): return true
        case (.completed, .completed): return true
        case let (.failed(e1), .failed(e2)): return e1 == e2
        case (.cancelled, .cancelled): return true
        default: return false
        }
    }
}

public struct TransferItem: Identifiable, Equatable {
    public let id: String
    public let fileId: String
    public let name: String
    public let size: Int64
    public var bytesTransferred: Int64
    public var progress: Double
    public var isOutgoing: Bool
    public var state: TransferState
    public var localURL: URL?
    public var startTime: Date
    public var speedBytesPerSec: Double

    public init(
        id: String,
        fileId: String,
        name: String,
        size: Int64,
        bytesTransferred: Int64 = 0,
        progress: Double = 0.0,
        isOutgoing: Bool,
        state: TransferState = .pending,
        localURL: URL? = nil,
        startTime: Date = Date(),
        speedBytesPerSec: Double = 0.0
    ) {
        self.id = id
        self.fileId = fileId
        self.name = name
        self.size = size
        self.bytesTransferred = bytesTransferred
        self.progress = progress
        self.isOutgoing = isOutgoing
        self.state = state
        self.localURL = localURL
        self.startTime = startTime
        self.speedBytesPerSec = speedBytesPerSec
    }

    public var etaSeconds: Int {
        guard speedBytesPerSec > 1024, size > bytesTransferred else { return 0 }
        return Int(Double(size - bytesTransferred) / speedBytesPerSec)
    }
}

public protocol TransferManagerDelegate: AnyObject {
    func transferManager(_ manager: TransferManager, didUpdateItem item: TransferItem)
    func transferManager(_ manager: TransferManager, didCompleteItem item: TransferItem)
    func transferManager(_ manager: TransferManager, didFailItem item: TransferItem, error: String)
}

public class TransferManager {
    public static let maxFileSize: Int64 = 10 * 1024 * 1024 * 1024 // 10 GB limit
    public weak var delegate: TransferManagerDelegate?
    
    private let connectionManager: ConnectionManager
    public private(set) var activeTransfers: [String: TransferItem] = [:]
    public private(set) var completedTransfers: [TransferItem] = []
    
    public let downloadDirectory: URL
    private let queue = DispatchQueue(label: "com.macdroid.transfermanager", qos: .utility)
    
    // Receiver active state: fileId -> (destinationURL, fileHandle, hasher, expectedSize, receivedBytes)
    private struct ReceiverContext {
        let transferId: String
        let destinationURL: URL
        let fileHandle: FileHandle
        var hasher: SHA256
        let expectedSize: Int64
        var receivedBytes: Int64
    }
    private var activeReceivers: [String: ReceiverContext] = [:]
    private var senderCancelTokens: Set<String> = []
    private var pausedTransferIds: Set<String> = []
    private let historyKey = "com.dexolink.transfer_history"

    private struct CachedTransferItem: Codable {
        let id: String
        let fileId: String
        let name: String
        let size: Int64
        let isOutgoing: Bool
        let stateDescription: String
        let localPath: String?
        let startTime: Date
    }

    // Transient macOS power activity: prevents App Nap or system sleep from throttling active file transfers
    private var transferActivityToken: NSObjectProtocol?
    
    private func acquireTransferActivityIfNeeded() {
        if transferActivityToken == nil {
            transferActivityToken = ProcessInfo.processInfo.beginActivity(
                options: [.userInitiated, .idleSystemSleepDisabled],
                reason: "Active DexoLink File Transfer"
            )
            print("⚡ [Power Management] Acquired macOS userInitiated transfer activity (App Nap disabled during transfer)")
        }
    }
    
    private func releaseTransferActivityIfNeeded() {
        if activeTransfers.isEmpty && activeReceivers.isEmpty, let token = transferActivityToken {
            ProcessInfo.processInfo.endActivity(token)
            transferActivityToken = nil
            print("💤 [Power Management] Released macOS transfer activity (idle)")
        }
    }

    public init(connectionManager: ConnectionManager) {
        self.connectionManager = connectionManager
        
        let urls = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)
        let baseDir = urls.first ?? URL(fileURLWithPath: NSHomeDirectory()).appendingPathComponent("Downloads")
        let dexoDir = baseDir.appendingPathComponent("DexoLink", isDirectory: true)
        try? FileManager.default.createDirectory(at: dexoDir, withIntermediateDirectories: true)
        self.downloadDirectory = dexoDir
        loadCompletedTransfers()
    }

    private func loadCompletedTransfers() {
        guard let data = UserDefaults.standard.data(forKey: historyKey),
              let list = try? JSONDecoder().decode([CachedTransferItem].self, from: data) else {
            return
        }
        self.completedTransfers = list.map { c in
            let st: TransferState
            switch c.stateDescription {
            case "completed": st = .completed
            case "cancelled": st = .cancelled
            default: st = .failed(c.stateDescription)
            }
            let url = c.localPath != nil ? URL(fileURLWithPath: c.localPath!) : nil
            return TransferItem(
                id: c.id,
                fileId: c.fileId,
                name: c.name,
                size: c.size,
                bytesTransferred: c.size,
                progress: 1.0,
                isOutgoing: c.isOutgoing,
                state: st,
                localURL: url,
                startTime: c.startTime
            )
        }
    }

    public func persistCompletedTransfers() {
        let cached = completedTransfers.prefix(50).map { item -> CachedTransferItem in
            let desc: String
            switch item.state {
            case .completed: desc = "completed"
            case .cancelled: desc = "cancelled"
            case let .failed(msg): desc = msg
            default: desc = "completed"
            }
            return CachedTransferItem(
                id: item.id,
                fileId: item.fileId,
                name: item.name,
                size: item.size,
                isOutgoing: item.isOutgoing,
                stateDescription: desc,
                localPath: item.localURL?.path,
                startTime: item.startTime
            )
        }
        if let data = try? JSONEncoder().encode(cached) {
            UserDefaults.standard.set(data, forKey: historyKey)
        }
    }
    
    // MARK: - Sender API
    
    public func sendFile(url: URL) {
        queue.async { [weak self] in
            guard let self = self else { return }
            guard self.connectionManager.state.isConnected else {
                print("⛔ [Quick File Sharing] Cannot send file: Device not connected")
                return
            }
            
            do {
                guard FileManager.default.fileExists(atPath: url.path) else {
                    throw FileStreamerError.fileNotFound
                }
                
                let (size, _) = try FileStreamer.getFileMetadata(url: url)
                guard size > 0 else {
                    throw NSError(domain: "DexoLink", code: 400, userInfo: [NSLocalizedDescriptionKey: "File is empty"])
                }
                guard size <= Self.maxFileSize else {
                    throw NSError(domain: "DexoLink", code: 413, userInfo: [NSLocalizedDescriptionKey: "File exceeds 10 GB limit"])
                }
                
                let transferId = UUID().uuidString
                let fileId = UUID().uuidString
                let filename = url.lastPathComponent
                
                let item = TransferItem(
                    id: transferId,
                    fileId: fileId,
                    name: filename,
                    size: size,
                    isOutgoing: true,
                    state: .pending,
                    localURL: url
                )
                
                self.activeTransfers[transferId] = item
                self.acquireTransferActivityIfNeeded()
                self.notifyDelegate(item)
                
                let request = ProtocolMessage.transferRequest(
                    transferId: transferId,
                    totalFiles: 1,
                    totalSize: size
                )
                self.connectionManager.send(message: request)
                print("📤 [Quick File Sharing] Transfer request sent for \(filename) (\(size) bytes, id: \(transferId))")
            } catch {
                let errDesc = error.localizedDescription
                print("❌ [Transfer Error] Failed initiating transfer: \(errDesc)")
            }
        }
    }
    
    public func cancelTransfer(id: String) {
        queue.async { [weak self] in
            guard let self = self else { return }
            self.senderCancelTokens.insert(id)
            self.connectionManager.send(message: .cancel(transferId: id))
            
            if var item = self.activeTransfers[id] {
                item.state = .cancelled
                self.activeTransfers.removeValue(forKey: id)
                self.completedTransfers.insert(item, at: 0)
                self.notifyDelegate(item)
            }
            
            // Clean up any receiver context matching transferId
            for (fId, ctx) in self.activeReceivers where ctx.transferId == id {
                try? ctx.fileHandle.close()
                try? FileManager.default.removeItem(at: ctx.destinationURL)
                self.activeReceivers.removeValue(forKey: fId)
            }
            print("🚫 [Quick File Sharing] Cancelled transfer \(id)")
            self.releaseTransferActivityIfNeeded()
        }
    }
    
    public func cancelAll() {
        queue.async { [weak self] in
            guard let self = self else { return }
            let ids = Array(self.activeTransfers.keys)
            for id in ids {
                self.cancelTransfer(id: id)
            }
        }
    }
    
    public func resumeTransfer(id: String) {
        queue.async { [weak self] in
            guard let self = self else { return }
            if let item = self.completedTransfers.first(where: { $0.id == id }),
               item.isOutgoing, let url = item.localURL {
                self.completedTransfers.removeAll(where: { $0.id == id })
                self.persistCompletedTransfers()
                print("🔄 [Quick File Sharing] Resuming transfer for \(item.name)")
                self.sendFile(url: url)
            }
        }
    }

    public func pauseTransfer(id: String) {
        queue.async { [weak self] in
            guard let self = self else { return }
            self.pausedTransferIds.insert(id)
            self.connectionManager.send(message: .pause(transferId: id))
            if var item = self.activeTransfers[id] {
                item.state = .paused(progress: item.progress)
                self.activeTransfers[id] = item
                self.notifyDelegate(item)
                print("⏸️ [Quick File Sharing] Paused transfer \(id) at \(Int(item.progress * 100))%")
            }
        }
    }

    public func unpauseTransfer(id: String) {
        queue.async { [weak self] in
            guard let self = self else { return }
            self.pausedTransferIds.remove(id)
            self.connectionManager.send(message: .resume(transferId: id))
            if var item = self.activeTransfers[id] {
                item.state = .transferring(progress: item.progress)
                self.activeTransfers[id] = item
                self.notifyDelegate(item)
                print("▶️ [Quick File Sharing] Resumed transfer \(id) from \(Int(item.progress * 100))%")
            }
        }
    }

    // MARK: - History Management (Safe: does not delete disk files or disconnect)
    public func deleteCompletedTransfer(id: String) {
        queue.async { [weak self] in
            guard let self = self else { return }
            self.completedTransfers.removeAll(where: { $0.id == id })
            self.persistCompletedTransfers()
            print("🗑️ Removed transfer \(id) from history (file preserved)")
        }
    }

    public func deleteCompletedTransfers(ids: Set<String>) {
        queue.async { [weak self] in
            guard let self = self else { return }
            self.completedTransfers.removeAll(where: { ids.contains($0.id) })
            self.persistCompletedTransfers()
            print("🗑️ Removed \(ids.count) transfers from history (files preserved)")
        }
    }

    public func clearCompletedTransfers() {
        queue.async { [weak self] in
            guard let self = self else { return }
            self.completedTransfers.removeAll()
            self.persistCompletedTransfers()
            print("🗑️ Cleared all transfer history (files preserved)")
        }
    }
    
    // MARK: - Incoming Message Handler
    
    public func handleMessage(_ message: ProtocolMessage) {
        queue.async { [weak self] in
            guard let self = self else { return }
            
            switch message {
            case let .transferRequest(transferId, _, totalSize):
                // Security check 1: Enforce connection or paired authorization
                guard self.connectionManager.state.isConnected || TrustStore.shared.hasPairedDevice() else {
                    print("⛔ [Access Control] Rejected transferRequest: Device not connected")
                    self.connectionManager.send(message: .transferResponse(transferId: transferId, accepted: false))
                    return
                }
                
                // Security check 2: Validate size constraint
                guard totalSize > 0 && totalSize <= Self.maxFileSize else {
                    print("⛔ [Validation] Rejected transferRequest: Invalid size \(totalSize)")
                    self.connectionManager.send(message: .transferResponse(transferId: transferId, accepted: false))
                    return
                }

                // Security check 3: Verify available disk space
                if let values = try? self.downloadDirectory.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]),
                   let freeBytes = values.volumeAvailableCapacityForImportantUsage {
                    if freeBytes < totalSize + (100 * 1024 * 1024) {
                        print("⛔ [Storage] Insufficient disk space on Mac: required \(totalSize), available \(freeBytes)")
                        self.connectionManager.send(message: .transferResponse(transferId: transferId, accepted: false))
                        return
                    }
                }
                
                // Auto-accept transfer from authenticated paired device
                let item = TransferItem(
                    id: transferId,
                    fileId: "",
                    name: "Incoming file...",
                    size: totalSize,
                    isOutgoing: false,
                    state: .transferring(progress: 0.0)
                )
                self.activeTransfers[transferId] = item
                self.acquireTransferActivityIfNeeded()
                self.notifyDelegate(item)
                
                self.connectionManager.send(message: .transferResponse(transferId: transferId, accepted: true))
                print("📥 [Quick File Sharing] Accepted transfer request \(transferId) (\(totalSize) bytes)")
                
            case let .transferResponse(transferId, accepted):
                guard var item = self.activeTransfers[transferId] else { return }
                
                if accepted {
                    item.state = .transferring(progress: 0.0)
                    self.activeTransfers[transferId] = item
                    self.acquireTransferActivityIfNeeded()
                    self.notifyDelegate(item)
                    
                    // Start streaming file
                    guard let fileURL = item.localURL else { return }
                    self.startStreamingFile(transferId: transferId, fileId: item.fileId, url: fileURL, size: item.size)
                } else {
                    item.state = .failed("Transfer rejected by peer")
                    self.activeTransfers.removeValue(forKey: transferId)
                    self.completedTransfers.insert(item, at: 0)
                    self.releaseTransferActivityIfNeeded()
                    self.notifyDelegate(item)
                    print("⛔ [Quick File Sharing] Peer rejected transfer \(transferId)")
                }
                
            case let .fileMeta(transferId, fileId, name, size, _):
                // Security check: Path traversal prevention & filename sanitization
                let cleanName = FileStreamer.sanitizeFilename(name)
                
                do {
                    let destURL = try FileStreamer.getSafeDestinationURL(baseDirectory: self.downloadDirectory, filename: cleanName)
                    
                    // Ensure destination is strictly inside downloadDirectory
                    guard destURL.path.hasPrefix(self.downloadDirectory.path) else {
                        throw FileStreamerError.invalidPath
                    }
                    
                    // Create empty destination file
                    FileManager.default.createFile(atPath: destURL.path, contents: nil)
                    
                    // Restrict permissions to read/write only (non-executable: 0644)
                    try? FileManager.default.setAttributes([.posixPermissions: 0o644], ofItemAtPath: destURL.path)
                    
                    let handle = try FileHandle(forWritingTo: destURL)
                    let ctx = ReceiverContext(
                        transferId: transferId,
                        destinationURL: destURL,
                        fileHandle: handle,
                        hasher: SHA256(),
                        expectedSize: size,
                        receivedBytes: 0
                    )
                    self.activeReceivers[fileId] = ctx
                    self.acquireTransferActivityIfNeeded()
                    
                    var item = self.activeTransfers[transferId] ?? TransferItem(
                        id: transferId,
                        fileId: fileId,
                        name: cleanName,
                        size: size,
                        isOutgoing: false,
                        state: .transferring(progress: 0.0),
                        localURL: destURL
                    )
                    item = TransferItem(
                        id: transferId,
                        fileId: fileId,
                        name: cleanName,
                        size: size,
                        bytesTransferred: 0,
                        progress: 0.0,
                        isOutgoing: false,
                        state: .transferring(progress: 0.0),
                        localURL: destURL,
                        startTime: Date()
                    )
                    self.activeTransfers[transferId] = item
                    self.notifyDelegate(item)
                    print("📁 [Quick File Sharing] Receiving file \(cleanName) -> \(destURL.lastPathComponent)")
                } catch {
                    print("❌ [File Error] Failed creating destination file: \(error.localizedDescription)")
                    self.connectionManager.send(message: .cancel(transferId: transferId))
                }
                
            case let .fileChunk(fileId, offset, dataBase64):
                guard var ctx = self.activeReceivers[fileId] else { return }
                guard let chunkData = Data(base64Encoded: dataBase64) else { return }
                
                // Security: Prevent buffer overflow beyond expectedSize
                if ctx.receivedBytes + Int64(chunkData.count) > ctx.expectedSize {
                    print("⚠️ [Security Violation] Received more bytes than declared! Aborting.")
                    try? ctx.fileHandle.close()
                    try? FileManager.default.removeItem(at: ctx.destinationURL)
                    self.activeReceivers.removeValue(forKey: fileId)
                    self.connectionManager.send(message: .cancel(transferId: ctx.transferId))
                    return
                }
                
                do {
                    try ctx.fileHandle.seek(toOffset: UInt64(offset))
                    try ctx.fileHandle.write(contentsOf: chunkData)
                    ctx.hasher.update(data: chunkData)
                    ctx.receivedBytes += Int64(chunkData.count)
                    self.activeReceivers[fileId] = ctx
                    
                    if var item = self.activeTransfers[ctx.transferId] {
                        item.bytesTransferred = ctx.receivedBytes
                        let progress = ctx.expectedSize > 0 ? Double(ctx.receivedBytes) / Double(ctx.expectedSize) : 0
                        item.progress = progress
                        
                        let elapsed = Date().timeIntervalSince(item.startTime)
                        if elapsed > 0.1 {
                            item.speedBytesPerSec = Double(ctx.receivedBytes) / elapsed
                        }
                        self.activeTransfers[ctx.transferId] = item
                        self.notifyDelegate(item)
                    }
                } catch {
                    print("❌ [Write Error] Failed writing chunk: \(error.localizedDescription)")
                }
                
            case let .fileComplete(fileId, declaredSHA256):
                guard let ctx = self.activeReceivers.removeValue(forKey: fileId) else { return }
                try? ctx.fileHandle.close()
                
                let computedHash = ctx.hasher.finalize().map { String(format: "%02x", $0) }.joined()
                var verified = (computedHash.lowercased() == declaredSHA256.lowercased())
                if !verified, FileManager.default.fileExists(atPath: ctx.destinationURL.path) {
                    if let diskHash = try? FileStreamer.computeSHA256(url: ctx.destinationURL),
                       diskHash.lowercased() == declaredSHA256.lowercased() {
                        print("✅ [File Verified] Verified via disk stream: \(diskHash)")
                        verified = true
                    }
                }
                
                self.connectionManager.send(message: .fileAck(fileId: fileId, verified: verified))
                
                if var item = self.activeTransfers.removeValue(forKey: ctx.transferId) {
                    self.releaseTransferActivityIfNeeded()
                    if verified {
                        item.state = .completed
                        item.progress = 1.0
                        item.bytesTransferred = item.size
                        self.completedTransfers.insert(item, at: 0)
                        self.persistCompletedTransfers()
                        self.notifyDelegate(item)
                        DispatchQueue.main.async { [weak self] in
                            guard let self = self else { return }
                            self.delegate?.transferManager(self, didCompleteItem: item)
                        }
                        print("✅ [File Verified] Successfully received and verified \(item.name) (SHA-256 match)")
                    } else {
                        // Integrity violation: discard corrupted file
                        try? FileManager.default.removeItem(at: ctx.destinationURL)
                        item.state = .failed("SHA-256 integrity checksum mismatch")
                        self.completedTransfers.insert(item, at: 0)
                        self.persistCompletedTransfers()
                        self.notifyDelegate(item)
                        DispatchQueue.main.async { [weak self] in
                            guard let self = self else { return }
                            self.delegate?.transferManager(self, didFailItem: item, error: "SHA-256 mismatch")
                        }
                        print("❌ [Integrity Failure] SHA-256 mismatch for \(item.name). Discarding corrupted file.")
                    }
                }
                
            case let .fileAck(fileId, verified):
                // Find sender transfer matching fileId
                for (tId, var item) in self.activeTransfers where item.fileId == fileId {
                    self.activeTransfers.removeValue(forKey: tId)
                    self.releaseTransferActivityIfNeeded()
                    if verified {
                        item.state = .completed
                        item.progress = 1.0
                        item.bytesTransferred = item.size
                        self.completedTransfers.insert(item, at: 0)
                        self.persistCompletedTransfers()
                        self.notifyDelegate(item)
                        DispatchQueue.main.async { [weak self] in
                            guard let self = self else { return }
                            self.delegate?.transferManager(self, didCompleteItem: item)
                        }
                        print("🎉 [Transfer Complete] Peer verified receipt of \(item.name)!")
                    } else {
                        item.state = .failed("Peer reported checksum verification failed")
                        self.completedTransfers.insert(item, at: 0)
                        self.persistCompletedTransfers()
                        self.notifyDelegate(item)
                        DispatchQueue.main.async { [weak self] in
                            guard let self = self else { return }
                            self.delegate?.transferManager(self, didFailItem: item, error: "Peer checksum mismatch")
                        }
                    }
                    break
                }
                
            case let .cancel(transferId):
                if var item = self.activeTransfers.removeValue(forKey: transferId) {
                    item.state = .cancelled
                    self.completedTransfers.insert(item, at: 0)
                    self.persistCompletedTransfers()
                    self.notifyDelegate(item)
                }
                for (fId, ctx) in self.activeReceivers where ctx.transferId == transferId {
                    try? ctx.fileHandle.close()
                    try? FileManager.default.removeItem(at: ctx.destinationURL)
                    self.activeReceivers.removeValue(forKey: fId)
                }
                print("🚫 [Quick File Sharing] Transfer \(transferId) cancelled by peer")
                self.releaseTransferActivityIfNeeded()

            case let .pause(transferId):
                self.pausedTransferIds.insert(transferId)
                if var item = self.activeTransfers[transferId] {
                    item.state = .paused(progress: item.progress)
                    self.activeTransfers[transferId] = item
                    self.notifyDelegate(item)
                }
                print("⏸️ [Quick File Sharing] Transfer \(transferId) paused by peer")

            case let .resume(transferId):
                self.pausedTransferIds.remove(transferId)
                if var item = self.activeTransfers[transferId] {
                    item.state = .transferring(progress: item.progress)
                    self.activeTransfers[transferId] = item
                    self.notifyDelegate(item)
                }
                print("▶️ [Quick File Sharing] Transfer \(transferId) resumed by peer")
                
            default:
                break
            }
        }
    }
    
    // MARK: - Streaming Worker
    
    private func startStreamingFile(transferId: String, fileId: String, url: URL, size: Int64) {
        queue.async { [weak self] in
            guard let self = self else { return }
            self.acquireTransferActivityIfNeeded()
            
            do {
                let (declaredSize, mimeType) = try FileStreamer.getFileMetadata(url: url)
                let filename = url.lastPathComponent
                
                // Send file metadata
                self.connectionManager.send(message: .fileMeta(
                    transferId: transferId,
                    fileId: fileId,
                    name: filename,
                    size: declaredSize,
                    mimeType: mimeType
                ))
                
                let fileHandle = try FileHandle(forReadingFrom: url)
                defer { try? fileHandle.close() }
                
                var offset: Int64 = 0
                var hasher = SHA256()
                let chunkSize = FileStreamer.chunkSize
                
                while offset < declaredSize {
                    if self.senderCancelTokens.contains(transferId) {
                        self.senderCancelTokens.remove(transferId)
                        print("⏹️ Sender loop aborted due to cancel")
                        self.releaseTransferActivityIfNeeded()
                        return
                    }

                    // Loop suspension while paused
                    while self.pausedTransferIds.contains(transferId) {
                        if self.senderCancelTokens.contains(transferId) {
                            self.senderCancelTokens.remove(transferId)
                            self.pausedTransferIds.remove(transferId)
                            self.releaseTransferActivityIfNeeded()
                            return
                        }
                        usleep(100_000)
                    }
                    
                    try fileHandle.seek(toOffset: UInt64(offset))
                    let readLength = min(Int(declaredSize - offset), chunkSize)
                    guard let chunk = try fileHandle.read(upToCount: readLength), !chunk.isEmpty else {
                        break
                    }
                    
                    hasher.update(data: chunk)
                    let base64Chunk = chunk.base64EncodedString()
                    
                    self.connectionManager.send(message: .fileChunk(
                        fileId: fileId,
                        offset: offset,
                        data: base64Chunk
                    ))
                    
                    offset += Int64(chunk.count)
                    
                    if var item = self.activeTransfers[transferId] {
                        item.bytesTransferred = offset
                        item.progress = declaredSize > 0 ? Double(offset) / Double(declaredSize) : 0
                        let elapsed = Date().timeIntervalSince(item.startTime)
                        if elapsed > 0.1 {
                            item.speedBytesPerSec = Double(offset) / elapsed
                        }
                        self.activeTransfers[transferId] = item
                        self.notifyDelegate(item)
                    }
                    
                    // Minimal yield every 4 MB to keep runloop responsive without artificial latency
                    if offset % (Int64(chunkSize) * 8) == 0 {
                        usleep(50)
                    }
                }
                
                let sha256 = hasher.finalize().map { String(format: "%02x", $0) }.joined()
                self.connectionManager.send(message: .fileComplete(fileId: fileId, sha256: sha256))
                
                if var item = self.activeTransfers[transferId] {
                    item.state = .verifying
                    self.activeTransfers[transferId] = item
                    self.notifyDelegate(item)
                }
                print("📤 [File Sent] Finished transmitting \(filename) (SHA-256: \(sha256.prefix(10))...). Awaiting ACK...")
            } catch {
                print("❌ [Streaming Error] Failed streaming file: \(error.localizedDescription)")
                if var item = self.activeTransfers.removeValue(forKey: transferId) {
                    item.state = .failed(error.localizedDescription)
                    self.completedTransfers.insert(item, at: 0)
                    self.releaseTransferActivityIfNeeded()
                    self.notifyDelegate(item)
                }
            }
        }
    }
    
    private func notifyDelegate(_ item: TransferItem) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.delegate?.transferManager(self, didUpdateItem: item)
        }
    }
}

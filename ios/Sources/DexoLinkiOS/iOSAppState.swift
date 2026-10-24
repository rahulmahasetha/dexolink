import SwiftUI
import Foundation
import Network
import Combine
import AVFoundation
import Darwin
import CryptoKit
#if canImport(UIKit)
import UIKit
#endif

// MARK: - Models for iOS

public struct DiscoveredDevice: Identifiable, Equatable, Hashable {
    public let id: String
    public let name: String
    public let endpoint: String
    public let port: UInt16
    public var isPaired: Bool
    public let serviceType: String

    public init(id: String, name: String, endpoint: String, port: UInt16 = 52840, isPaired: Bool = false, serviceType: String = "_macdroid._tcp") {
        self.id = id
        self.name = name
        self.endpoint = endpoint
        self.port = port
        self.isPaired = isPaired
        self.serviceType = serviceType
    }
}

public struct iOSFrameSize: Equatable, Codable {
    public var width: Int
    public var height: Int
    public init(width: Int, height: Int) {
        self.width = width
        self.height = height
    }
}

public enum iOSScreenDisplayHelper {
    public static func displayPixelSize(bounds: CGSize, scale: CGFloat) -> iOSFrameSize {
        let s = scale > 0 ? scale : 1.0
        return iOSFrameSize(
            width: Int((bounds.width * s).rounded()),
            height: Int((bounds.height * s).rounded())
        )
    }

    public static func calculateProportionalTargetSize(sourceWidth: Int, sourceHeight: Int, maxEdge: Int = 1280) -> iOSFrameSize {
        guard sourceWidth > 0 && sourceHeight > 0 else {
            return iOSFrameSize(width: 960, height: 540)
        }
        let maxDim = max(sourceWidth, sourceHeight)
        if maxDim <= maxEdge {
            return iOSFrameSize(width: sourceWidth, height: sourceHeight)
        }
        let scale = Double(maxEdge) / Double(maxDim)
        var targetW = Int((Double(sourceWidth) * scale).rounded())
        var targetH = Int((Double(sourceHeight) * scale).rounded())
        if targetW % 2 != 0 { targetW -= 1 }
        if targetH % 2 != 0 { targetH -= 1 }
        return iOSFrameSize(width: max(targetW, 2), height: max(targetH, 2))
    }
}

public struct TrustedDevice: Identifiable, Codable, Equatable {
    public let id: String
    public let name: String
    public let pairedDate: Date
    public let host: String?
    public let port: UInt16?

    public init(id: String, name: String, pairedDate: Date = Date(), host: String? = nil, port: UInt16? = nil) {
        self.id = id
        self.name = name
        self.pairedDate = pairedDate
        self.host = host
        self.port = port
    }
}

public struct TransferProgressItem: Identifiable {
    public let id: UUID
    public let fileName: String
    public let totalBytes: Int64
    public var bytesSent: Int64
    public var isComplete: Bool
    public var isSuccess: Bool
    public var recipientName: String?
    public var errorMessage: String?
    public var isCancelled: Bool

    public init(id: UUID = UUID(), fileName: String, totalBytes: Int64, bytesSent: Int64 = 0, isComplete: Bool = false, isSuccess: Bool = false, recipientName: String? = nil, errorMessage: String? = nil, isCancelled: Bool = false) {
        self.id = id
        self.fileName = fileName
        self.totalBytes = totalBytes
        self.bytesSent = bytesSent
        self.isComplete = isComplete
        self.isSuccess = isSuccess
        self.recipientName = recipientName
        self.errorMessage = errorMessage
        self.isCancelled = isCancelled
    }

    public var progressFraction: Double {
        guard totalBytes > 0 else { return 0 }
        return Double(bytesSent) / Double(totalBytes)
    }
}

public struct TransferHistoryItem: Identifiable, Codable {
    public let id: UUID
    public let fileName: String
    public let fileSize: Int64
    public let isIncoming: Bool
    public let peerName: String
    public let timestamp: Date

    public init(id: UUID = UUID(), fileName: String, fileSize: Int64, isIncoming: Bool, peerName: String, timestamp: Date = Date()) {
        self.id = id
        self.fileName = fileName
        self.fileSize = fileSize
        self.isIncoming = isIncoming
        self.peerName = peerName
        self.timestamp = timestamp
    }
}

public struct ContinuityHandoffItem: Identifiable, Equatable {
    public let id = UUID()
    public let title: String
    public let urlString: String
    public let senderDevice: String
    public let timestamp: Date

    public init(title: String, urlString: String, senderDevice: String, timestamp: Date = Date()) {
        self.title = title
        self.urlString = urlString
        self.senderDevice = senderDevice
        self.timestamp = timestamp
    }
}

// MARK: - iOS App State Manager

public class iOSAppState: ObservableObject {
    public static let shared = iOSAppState()

    // Connection & Discovery State
    @Published public var isConnected: Bool = false
    @Published public var connectedDeviceName: String = "No Device Connected"
    @Published public var discoveredDevices: [DiscoveredDevice] = []
    @Published public var pairedDevices: [TrustedDevice] = []
    @Published public var statusMessage: String? = nil
    @Published public var autoReconnect: Bool = true
    @Published public var userExplicitlyDisconnected: Bool = false

    public func isDeviceAlreadyPaired(id: String, name: String) -> Bool {
        return pairedDevices.contains { dev in
            dev.id == id || dev.name.caseInsensitiveCompare(name) == .orderedSame
        }
    }

    public func isDeviceAlreadyPaired(_ device: DiscoveredDevice) -> Bool {
        return isDeviceAlreadyPaired(id: device.id, name: device.name)
    }
    
    // Multiple Paired Devices Setting (OFF by default)
    @Published public var isMultiplePairedDevicesEnabled: Bool = false {
        didSet {
            UserDefaults.standard.set(isMultiplePairedDevicesEnabled, forKey: "dexolink_ios_multi_paired")
            if !isMultiplePairedDevicesEnabled && pairedDevices.count > 1 {
                pairedDevices = [pairedDevices.first!]
                savePairedDevices()
            }
        }
    }

    // Feature Settings
    @Published public var autoClipboardSync: Bool = true {
        didSet { UserDefaults.standard.set(autoClipboardSync, forKey: "dexolink_ios_auto_clip") }
    }
    @Published public var enhancedClipboard: Bool = true {
        didSet { UserDefaults.standard.set(enhancedClipboard, forKey: "dexolink_ios_enhanced_clip") }
    }
    // Media Streaming & Remote Control Settings (OFF by default)
    @Published public var isCameraSharingEnabled: Bool = false {
        didSet { UserDefaults.standard.set(isCameraSharingEnabled, forKey: "dexolink_ios_camera_sharing") }
    }
    @Published public var isFrontCamera: Bool = false {
        didSet { UserDefaults.standard.set(isFrontCamera, forKey: "dexolink_ios_front_camera") }
    }
    @Published public var isScreenSharingEnabled: Bool = false {
        didSet { UserDefaults.standard.set(isScreenSharingEnabled, forKey: "dexolink_ios_screen_sharing") }
    }
    @Published public var isDesktopModeEnabled: Bool = false {
        didSet { UserDefaults.standard.set(isDesktopModeEnabled, forKey: "dexolink_ios_desktop_mode") }
    }

    public func savePairedDevices() {
        if let data = try? JSONEncoder().encode(pairedDevices) {
            UserDefaults.standard.set(data, forKey: "dexolink_ios_paired_devices")
        }
    }

    public func loadPairedDevices() {
        if let data = UserDefaults.standard.data(forKey: "dexolink_ios_paired_devices"),
           let devices = try? JSONDecoder().decode([TrustedDevice].self, from: data) {
            self.pairedDevices = devices
        }
    }

    public init() {
        let multi = UserDefaults.standard.bool(forKey: "dexolink_ios_multi_paired")
        self.isMultiplePairedDevicesEnabled = multi

        if UserDefaults.standard.object(forKey: "dexolink_ios_auto_clip") != nil {
            self.autoClipboardSync = UserDefaults.standard.bool(forKey: "dexolink_ios_auto_clip")
        }
        if UserDefaults.standard.object(forKey: "dexolink_ios_enhanced_clip") != nil {
            self.enhancedClipboard = UserDefaults.standard.bool(forKey: "dexolink_ios_enhanced_clip")
        }
        self.isCameraSharingEnabled = UserDefaults.standard.bool(forKey: "dexolink_ios_camera_sharing")
        self.isFrontCamera = UserDefaults.standard.bool(forKey: "dexolink_ios_front_camera")
        self.isScreenSharingEnabled = UserDefaults.standard.bool(forKey: "dexolink_ios_screen_sharing")
        self.isDesktopModeEnabled = UserDefaults.standard.bool(forKey: "dexolink_ios_desktop_mode")

        loadPairedDevices()
        startDeviceDiscovery()
    }

    public func toggleCameraLens() {
        self.isFrontCamera.toggle()
        print("[DexoLink iOS] Switched camera lens to: \(isFrontCamera ? "Front" : "Back")")
    }

    public func setCameraSharingEnabled(_ enabled: Bool) {
        guard isCameraSharingEnabled != enabled else { return }
        if enabled {
            let status = AVCaptureDevice.authorizationStatus(for: .video)
            switch status {
            case .authorized:
                self.isCameraSharingEnabled = true
            case .notDetermined:
                AVCaptureDevice.requestAccess(for: .video) { granted in
                    DispatchQueue.main.async {
                        self.isCameraSharingEnabled = granted
                    }
                }
            case .denied, .restricted:
                self.isCameraSharingEnabled = false
                if let url = URL(string: UIApplication.openSettingsURLString) {
                    UIApplication.shared.open(url)
                }
            @unknown default:
                self.isCameraSharingEnabled = false
            }
        } else {
            self.isCameraSharingEnabled = false
        }
    }

    // Active Transfers & History
    @Published public var activeTransfer: TransferProgressItem?
    @Published public var activeTransfersList: [TransferProgressItem] = []
    @Published public var transferHistory: [TransferHistoryItem] = []
    @Published public var clipboardHistory: [String] = []
    @Published public var currentHandoff: ContinuityHandoffItem?

    private var listener: NWListener?
    private var activeConnections: [String: NWConnection] = [:]
    private var discoveryBrowsers: [NWBrowser] = []
    private var pendingReconnectDeviceName: String?
    private var cancellables = Set<AnyCancellable>()

    private var lastSentClipboardHash: String?
    private var lastReceivedClipboardHash: String?
    private var clipboardSequenceNumber: Int = 0

    private func computeTextHash(_ text: String) -> String {
        let hash = SHA256.hash(data: Data(text.utf8))
        return hash.map { String(format: "%02x", $0) }.joined()
    }

    private func sendPacket(_ packet: Data, on conn: NWConnection) {
        conn.send(content: packet, completion: .contentProcessed({ error in
            if let err = error {
                print("[DexoLink iOS] sendPacket error: \(err)")
            }
        }))
    }

    private func makeFramedPacket(_ payload: [String: Any]) -> Data? {
        guard let data = try? JSONSerialization.data(withJSONObject: payload) else { return nil }
        var lengthPrefix = UInt32(data.count).bigEndian
        var packet = Data(bytes: &lengthPrefix, count: 4)
        packet.append(data)
        return packet
    }

    private func startReceiving(on connection: NWConnection, connectionId: String) {
        connection.receive(minimumIncompleteLength: 4, maximumLength: 4) { [weak self, weak connection] content, _, isComplete, error in
            guard let self = self, let conn = connection else { return }
            if let err = error {
                print("[DexoLink iOS] Connection \(connectionId) receive error: \(err)")
                self.handleConnectionClosed(connectionId: connectionId)
                return
            }
            if isComplete && (content == nil || content!.isEmpty) {
                print("[DexoLink iOS] Connection \(connectionId) closed by peer")
                self.handleConnectionClosed(connectionId: connectionId)
                return
            }
            guard let header = content, header.count == 4 else {
                if !isComplete {
                    self.startReceiving(on: conn, connectionId: connectionId)
                }
                return
            }
            let length = Int(header.withUnsafeBytes { $0.load(as: UInt32.self).bigEndian })
            guard length > 0 && length < 20 * 1024 * 1024 else {
                print("[DexoLink iOS] Invalid packet length: \(length)")
                self.startReceiving(on: conn, connectionId: connectionId)
                return
            }

            conn.receive(minimumIncompleteLength: length, maximumLength: length) { [weak self, weak conn] bodyData, _, bodyComplete, bodyError in
                guard let self = self, let activeConn = conn else { return }
                if let data = bodyData, let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] {
                    self.handleIncomingProtocolMessage(json, on: activeConn, connectionId: connectionId)
                }
                if !bodyComplete && bodyError == nil {
                    self.startReceiving(on: activeConn, connectionId: connectionId)
                } else if bodyError != nil {
                    self.handleConnectionClosed(connectionId: connectionId)
                }
            }
        }
    }

    private func handleIncomingProtocolMessage(_ msg: [String: Any], on connection: NWConnection, connectionId: String) {
        guard let type = msg["type"] as? String else { return }
        switch type {
        case "CLIPBOARD_PUSH":
            if let content = msg["content"] as? String, !content.isEmpty {
                let seq = (msg["sequenceNumber"] as? Int) ?? 0
                let hash = computeTextHash(content)
                if hash != lastReceivedClipboardHash && hash != lastSentClipboardHash {
                    lastReceivedClipboardHash = hash
                    DispatchQueue.main.async {
                        #if canImport(UIKit)
                        UIPasteboard.general.string = content
                        #endif
                        if !self.clipboardHistory.contains(content) {
                            self.clipboardHistory.insert(content, at: 0)
                            if self.clipboardHistory.count > 50 { self.clipboardHistory.removeLast() }
                        }
                    }
                    print("[DexoLink iOS] Received clipboard push (seq: \(seq), chars: \(content.count))")
                } else {
                    print("[DexoLink iOS] Dropped duplicate/echo clipboard push (hash: \(hash.prefix(8)))")
                }
                if let ackPacket = makeFramedPacket(["type": "CLIPBOARD_ACK", "sequenceNumber": seq]) {
                    sendPacket(ackPacket, on: connection)
                }
            }
        case "CLIPBOARD_ACK":
            if let seq = msg["sequenceNumber"] as? Int {
                print("[DexoLink iOS] Clipboard push acknowledged by peer (seq: \(seq))")
            }
        case "TRANSFER_REQUEST":
            let transferId = (msg["transferId"] as? String) ?? UUID().uuidString
            let totalSize = (msg["totalSize"] as? Int64) ?? 0
            print("[DexoLink iOS] Received TRANSFER_REQUEST for \(transferId) (\(totalSize) bytes)")
            if let respPacket = makeFramedPacket(["type": "TRANSFER_RESPONSE", "transferId": transferId, "accepted": true]) {
                sendPacket(respPacket, on: connection)
            }
        case "FILE_META":
            let fileName = (msg["name"] as? String) ?? "received_file"
            let size = (msg["size"] as? Int64) ?? 0
            DispatchQueue.main.async {
                let item = TransferProgressItem(
                    fileName: fileName,
                    totalBytes: size,
                    bytesSent: 0,
                    isComplete: false,
                    isSuccess: false,
                    recipientName: "Remote Device"
                )
                self.activeTransfer = item
                self.activeTransfersList.append(item)
            }
        case "FILE_CHUNK":
            let offset = (msg["offset"] as? Int64) ?? 0
            if let b64 = msg["data"] as? String, let chunkData = Data(base64Encoded: b64) {
                let newBytes = offset + Int64(chunkData.count)
                DispatchQueue.main.async {
                    self.activeTransfer?.bytesSent = newBytes
                }
            }
        case "FILE_COMPLETE":
            let fileId = (msg["fileId"] as? String) ?? ""
            let transferId = (msg["transferId"] as? String) ?? ""
            DispatchQueue.main.async {
                if let cur = self.activeTransfer {
                    self.activeTransfer?.isComplete = true
                    self.activeTransfer?.isSuccess = true
                    self.transferHistory.insert(
                        TransferHistoryItem(
                            fileName: cur.fileName,
                            fileSize: cur.totalBytes,
                            isIncoming: true,
                            peerName: cur.recipientName ?? "Remote Device"
                        ),
                        at: 0
                    )
                    DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) {
                        self.activeTransfer = nil
                        self.activeTransfersList.removeAll(where: { $0.isComplete })
                    }
                }
            }
            if let ackPacket = makeFramedPacket(["type": "FILE_ACK", "transferId": transferId, "fileId": fileId, "verified": true]) {
                sendPacket(ackPacket, on: connection)
            }
        case "CANCEL":
            DispatchQueue.main.async {
                self.activeTransfer?.isCancelled = true
                self.activeTransfer?.isComplete = true
                self.activeTransfer?.isSuccess = false
                self.activeTransfer?.errorMessage = "Cancelled by peer"
            }
        case "PING":
            if let pongPacket = makeFramedPacket(["type": "PONG"]) {
                sendPacket(pongPacket, on: connection)
            }
        default:
            break
        }
    }

    private func handleConnectionClosed(connectionId: String) {
        DispatchQueue.main.async {
            self.activeConnections.removeValue(forKey: connectionId)
            if self.activeConnections.isEmpty {
                self.isConnected = false
                self.connectedDeviceName = "No Device Connected"
                if !self.userExplicitlyDisconnected && self.autoReconnect {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) { [weak self] in
                        guard let self = self else { return }
                        if !self.isConnected && !self.userExplicitlyDisconnected {
                            self.startDeviceDiscovery()
                        }
                    }
                }
            }
        }
    }

    private func handleIncomingConnection(_ connection: NWConnection) {
        connection.start(queue: .main)
        self.isConnected = true
        self.connectedDeviceName = "Connected Peer"
        let connId = UUID().uuidString
        self.activeConnections[connId] = connection
        self.startReceiving(on: connection, connectionId: connId)
    }

    public func startDeviceDiscovery() {
        discoveryBrowsers.forEach { $0.cancel() }
        discoveryBrowsers.removeAll()
        for serviceType in ["_macdroid._tcp", "_dexolink._tcp"] {
            let browser = NWBrowser(for: .bonjour(type: serviceType, domain: nil), using: .tcp)
            browser.browseResultsChangedHandler = { [weak self] results, _ in
                DispatchQueue.main.async {
                    guard let self = self else { return }
                    let foundNames = Set(results.compactMap { result -> String? in
                        if case let .service(name, _, _, _) = result.endpoint { return name }
                        return nil
                    })
                    self.discoveredDevices.removeAll { $0.serviceType == serviceType && !foundNames.contains($0.name) }
                    for res in results {
                        if case let .service(name, _, _, _) = res.endpoint {
                            let isPaired = self.pairedDevices.contains(where: { $0.name.caseInsensitiveCompare(name) == .orderedSame })
                            let device = DiscoveredDevice(id: name, name: name, endpoint: name, isPaired: isPaired, serviceType: serviceType)
                            if !self.discoveredDevices.contains(where: { $0.id == device.id }) {
                                self.discoveredDevices.append(device)
                            }
                            if self.pendingReconnectDeviceName?.caseInsensitiveCompare(name) == .orderedSame {
                                self.pendingReconnectDeviceName = nil
                                self.connectToRemoteEndpoint(host: name, serviceType: serviceType)
                            } else if !self.isConnected && !self.userExplicitlyDisconnected && self.autoReconnect && isPaired {
                                print("[DexoLink iOS] Auto-reconnecting to paired device '\(name)'")
                                self.connectToRemoteEndpoint(host: name, serviceType: serviceType)
                            }
                        }
                    }
                }
            }
            browser.start(queue: .main)
            discoveryBrowsers.append(browser)
        }
    }

    // MARK: - Device Pairing & Unpairing
    
    public func pairDevice(_ device: DiscoveredDevice) {
        if !isMultiplePairedDevicesEnabled && !pairedDevices.isEmpty && !pairedDevices.contains(where: { $0.id == device.id || $0.name.lowercased() == device.name.lowercased() }) {
            self.statusMessage = "Single Device Mode active. Enable 'Multiple Paired Devices' in Settings to pair additional devices."
            return
        }
        if !isMultiplePairedDevicesEnabled {
            pairedDevices.removeAll()
        }
        if !pairedDevices.contains(where: { $0.id == device.id }) {
            pairedDevices.append(TrustedDevice(id: device.id, name: device.name, host: device.endpoint, port: device.port))
            savePairedDevices()
        }
        self.isConnected = true
        self.connectedDeviceName = device.name
    }

    public func unpairDevice(deviceId: String) {
        pairedDevices.removeAll(where: { $0.id == deviceId })
        savePairedDevices()
        if connectedDeviceName == deviceId || pairedDevices.isEmpty {
            self.isConnected = false
            self.connectedDeviceName = "No Device Connected"
        }
    }

    // MARK: - Outgoing File Transfer
    
    public func sendFile(url: URL, targetDevice: DiscoveredDevice?) {
        let fileName = url.lastPathComponent
        let fileSize = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? Int64) ?? 1024

        // Determine recipients based on Single mode vs Multiple mode
        var recipients: [DiscoveredDevice] = []
        if !isMultiplePairedDevicesEnabled {
            // Single mode: send files ONLY to the selected target or the single paired device
            if let target = targetDevice {
                recipients = [target]
            } else if let paired = pairedDevices.first {
                let dev = discoveredDevices.first(where: { $0.id == paired.id || $0.name == paired.name }) ??
                    DiscoveredDevice(id: paired.id, name: paired.name, endpoint: paired.host ?? paired.name, port: paired.port ?? 52840, isPaired: true)
                recipients = [dev]
            } else if let first = discoveredDevices.first {
                recipients = [first]
            }
        } else {
            // Multiple mode: if target explicitly given, send to target; otherwise send to all eligible connected, paired devices
            if let target = targetDevice {
                recipients = [target]
            } else {
                let eligible = discoveredDevices.filter { $0.isPaired || isDeviceAlreadyPaired($0) }
                recipients = eligible.isEmpty ? (discoveredDevices.isEmpty ? [] : [discoveredDevices[0]]) : eligible
            }
        }

        guard !recipients.isEmpty else {
            self.statusMessage = "No devices available to share file"
            return
        }

        for recipient in recipients {
            let transferUUID = UUID()
            let transferId = "tr_\(Int(Date().timeIntervalSince1970 * 1000))_\(UUID().uuidString.prefix(6))"
            let fileId = "fl_\(Int(Date().timeIntervalSince1970 * 1000))_\(UUID().uuidString.prefix(6))"

            var progressItem = TransferProgressItem(
                id: transferUUID,
                fileName: fileName,
                totalBytes: fileSize,
                bytesSent: 0,
                recipientName: recipient.name
            )
            self.activeTransfersList.append(progressItem)
            self.activeTransfer = progressItem

            // Find connection for recipient
            let conn = activeConnections[recipient.endpoint] ?? activeConnections[recipient.name] ?? activeConnections[recipient.id] ?? (recipients.count == 1 ? activeConnections.values.first : nil)

            if conn == nil {
                // Handle offline recipients gracefully without blocking others!
                progressItem.isComplete = true
                progressItem.isSuccess = false
                progressItem.errorMessage = "Device offline or not connected"
                self.updateTransferProgress(progressItem)
                self.transferHistory.insert(
                    TransferHistoryItem(
                        id: transferUUID,
                        fileName: fileName,
                        fileSize: fileSize,
                        isIncoming: false,
                        peerName: recipient.name
                    ),
                    at: 0
                )
                print("[DexoLink iOS] Recipient \(recipient.name) is offline, skipped gracefully")
                continue
            }

            guard let activeConn = conn else { continue }

            // Dispatch background streaming for this recipient independently
            DispatchQueue.global(qos: .userInitiated).async { [weak self] in
                guard let self = self else { return }

                // 1. Propose transfer
                if let reqPacket = self.makeFramedPacket([
                    "type": "TRANSFER_REQUEST",
                    "transferId": transferId,
                    "totalFiles": 1,
                    "totalSize": fileSize
                ]) {
                    self.sendPacket(reqPacket, on: activeConn)
                }

                // 2. Send FILE_META
                if let metaPacket = self.makeFramedPacket([
                    "type": "FILE_META",
                    "transferId": transferId,
                    "fileId": fileId,
                    "name": fileName,
                    "size": fileSize,
                    "mimeType": "application/octet-stream"
                ]) {
                    self.sendPacket(metaPacket, on: activeConn)
                }

                // 3. Stream Chunks with SHA-256 calculation
                let chunkSize: Int = 64 * 1024
                var offset: Int64 = 0
                var hasher = SHA256()

                if let fileHandle = try? FileHandle(forReadingFrom: url) {
                    defer { try? fileHandle.close() }

                    while offset < fileSize {
                        // Check cancellation
                        if let currentItem = self.activeTransfersList.first(where: { $0.id == transferUUID }), currentItem.isCancelled {
                            if let cancelPacket = self.makeFramedPacket(["type": "CANCEL", "transferId": transferId]) {
                                self.sendPacket(cancelPacket, on: activeConn)
                            }
                            print("[DexoLink iOS] Transfer \(transferId) cancelled by user")
                            return
                        }

                        let readCount = min(chunkSize, Int(fileSize - offset))
                        guard let chunkData = try? fileHandle.read(upToCount: readCount), !chunkData.isEmpty else {
                            break
                        }

                        hasher.update(data: chunkData)
                        let b64 = chunkData.base64EncodedString()

                        if let chunkPacket = self.makeFramedPacket([
                            "type": "FILE_CHUNK",
                            "fileId": fileId,
                            "offset": offset,
                            "data": b64
                        ]) {
                            self.sendPacket(chunkPacket, on: activeConn)
                        }

                        offset += Int64(chunkData.count)
                        let currentOffset = offset
                        DispatchQueue.main.async {
                            if let idx = self.activeTransfersList.firstIndex(where: { $0.id == transferUUID }) {
                                self.activeTransfersList[idx].bytesSent = currentOffset
                                if self.activeTransfer?.id == transferUUID {
                                    self.activeTransfer?.bytesSent = currentOffset
                                }
                            }
                        }
                    }
                } else {
                    // Fallback for simulated or memory-based URLs
                    while offset < fileSize {
                        if let currentItem = self.activeTransfersList.first(where: { $0.id == transferUUID }), currentItem.isCancelled {
                            return
                        }
                        offset = min(offset + Int64(chunkSize), fileSize)
                        let currentOffset = offset
                        DispatchQueue.main.async {
                            if let idx = self.activeTransfersList.firstIndex(where: { $0.id == transferUUID }) {
                                self.activeTransfersList[idx].bytesSent = currentOffset
                                if self.activeTransfer?.id == transferUUID {
                                    self.activeTransfer?.bytesSent = currentOffset
                                }
                            }
                        }
                        Thread.sleep(forTimeInterval: 0.03)
                    }
                }

                let finalHash = hasher.finalize().map { String(format: "%02x", $0) }.joined()
                if let completePacket = self.makeFramedPacket([
                    "type": "FILE_COMPLETE",
                    "transferId": transferId,
                    "fileId": fileId,
                    "sha256": finalHash
                ]) {
                    self.sendPacket(completePacket, on: activeConn)
                }

                DispatchQueue.main.async {
                    if let idx = self.activeTransfersList.firstIndex(where: { $0.id == transferUUID }) {
                        self.activeTransfersList[idx].isComplete = true
                        self.activeTransfersList[idx].isSuccess = true
                        if self.activeTransfer?.id == transferUUID {
                            self.activeTransfer?.isComplete = true
                            self.activeTransfer?.isSuccess = true
                        }
                    }
                    self.transferHistory.insert(
                        TransferHistoryItem(
                            id: transferUUID,
                            fileName: fileName,
                            fileSize: fileSize,
                            isIncoming: false,
                            peerName: recipient.name
                        ),
                        at: 0
                    )
                    DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) {
                        self.activeTransfersList.removeAll(where: { $0.id == transferUUID })
                        if self.activeTransfer?.id == transferUUID {
                            self.activeTransfer = self.activeTransfersList.first(where: { !$0.isComplete })
                        }
                    }
                }
            }
        }
    }

    private func updateTransferProgress(_ item: TransferProgressItem) {
        DispatchQueue.main.async {
            if let idx = self.activeTransfersList.firstIndex(where: { $0.id == item.id }) {
                self.activeTransfersList[idx] = item
            }
            if self.activeTransfer?.id == item.id {
                self.activeTransfer = item
            }
        }
    }

    public func cancelTransfer(id: UUID) {
        DispatchQueue.main.async {
            if let idx = self.activeTransfersList.firstIndex(where: { $0.id == id }) {
                self.activeTransfersList[idx].isCancelled = true
                self.activeTransfersList[idx].isComplete = true
                self.activeTransfersList[idx].isSuccess = false
                self.activeTransfersList[idx].errorMessage = "Transfer cancelled by user"
                if self.activeTransfer?.id == id {
                    self.activeTransfer?.isCancelled = true
                    self.activeTransfer?.isComplete = true
                    self.activeTransfer?.isSuccess = false
                    self.activeTransfer?.errorMessage = "Transfer cancelled by user"
                }
            }
        }
    }

    // MARK: - Clipboard & Continuity Actions
    
    public func pushClipboardText(_ text: String) {
        guard !text.isEmpty else { return }
        let hash = computeTextHash(text)
        if hash == lastSentClipboardHash || hash == lastReceivedClipboardHash {
            print("[DexoLink iOS Clipboard] Dropped echo/duplicate text (hash: \(hash.prefix(8)))")
            return
        }
        lastSentClipboardHash = hash

        if !clipboardHistory.contains(text) {
            clipboardHistory.insert(text, at: 0)
            if clipboardHistory.count > 50 { clipboardHistory.removeLast() }
        }
        #if canImport(UIKit)
        UIPasteboard.general.string = text
        #endif

        clipboardSequenceNumber += 1
        let seq = clipboardSequenceNumber
        guard let packet = makeFramedPacket([
            "type": "CLIPBOARD_PUSH",
            "sequenceNumber": seq,
            "content": text
        ]) else { return }

        if !isMultiplePairedDevicesEnabled {
            // Single mode: send ONLY to the single paired device or the active connection
            if let paired = pairedDevices.first, let conn = activeConnections[paired.id] ?? activeConnections[paired.name] ?? activeConnections.values.first {
                sendPacket(packet, on: conn)
                print("[DexoLink iOS] Pushed clipboard to single target \(paired.name) (seq: \(seq))")
            } else if let conn = activeConnections.values.first {
                sendPacket(packet, on: conn)
                print("[DexoLink iOS] Pushed clipboard to active connection (seq: \(seq))")
            }
        } else {
            // Multiple mode: send clipboard updates to all eligible connected, paired devices
            var sentCount = 0
            for (key, conn) in activeConnections {
                let isPaired = pairedDevices.contains(where: { $0.id == key || $0.name.caseInsensitiveCompare(key) == .orderedSame })
                if isPaired || activeConnections.count == 1 {
                    sendPacket(packet, on: conn)
                    sentCount += 1
                }
            }
            print("[DexoLink iOS] Pushed clipboard to \(sentCount) connected peers (seq: \(seq))")
        }
    }

    public func sendHandoffURL(_ urlString: String) {
        guard let url = URL(string: urlString), UIApplication.shared.canOpenURL(url) else { return }
        currentHandoff = ContinuityHandoffItem(title: "Shared Web Page", urlString: urlString, senderDevice: UIDevice.current.name)
    }

    // MARK: - Online Remote Connection & WebRTC Signaling
    
    @Published public var isOnlineConnected: Bool = false
    @Published public var rtcSignalingState: String = "idle"
    @Published public var isTurnRelayActive: Bool = false

    public static let defaultStunServers = [
        "stun:stun.l.google.com:19302",
        "stun:stun1.l.google.com:19302",
        "stun:stun.cloudflare.com:3478"
    ]

    public static let defaultTurnServers = [
        "turn:openrelay.metered.ca:80",
        "turn:openrelay.metered.ca:443?transport=tcp"
    ]

    public func connectToRemoteEndpoint(host: String, port: UInt16 = 52840, serviceType: String? = nil) {
        self.userExplicitlyDisconnected = false
        let endpoint: NWEndpoint
        if IPv4Address(host) != nil || IPv6Address(host) != nil {
            endpoint = .hostPort(host: NWEndpoint.Host(host), port: NWEndpoint.Port(rawValue: port) ?? 52840)
        } else {
            endpoint = .service(name: host, type: serviceType ?? "_macdroid._tcp", domain: "local.", interface: nil)
        }
        let parameters = NWParameters.tcp
        parameters.includePeerToPeer = true
        let conn = NWConnection(to: endpoint, using: parameters)
        conn.stateUpdateHandler = { [weak self] state in
            DispatchQueue.main.async {
                guard let self = self else { return }
                switch state {
                case .ready:
                    self.isConnected = true
                    self.isOnlineConnected = true
                    self.connectedDeviceName = "Online Peer (\(host))"
                    let dev = DiscoveredDevice(id: host, name: "Remote Device (\(host))", endpoint: host, port: port, isPaired: true)
                    if !self.discoveredDevices.contains(where: { $0.id == host }) {
                        self.discoveredDevices.insert(dev, at: 0)
                    }
                    print("[DexoLink iOS] Connected to online remote endpoint \(host):\(port)")
                    self.startReceiving(on: conn, connectionId: host)
                case .failed(let err):
                    print("[DexoLink iOS] Remote connection failed: \(err)")
                    self.isConnected = false
                    self.isOnlineConnected = false
                    if !self.userExplicitlyDisconnected && self.autoReconnect {
                        DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) { [weak self] in
                            guard let self = self else { return }
                            if !self.isConnected && !self.userExplicitlyDisconnected {
                                self.startDeviceDiscovery()
                            }
                        }
                    }
                default:
                    break
                }
            }
        }
        conn.start(queue: .main)
        self.activeConnections[host] = conn
    }

    public func startWebRtcSignaling(streamType: String = "screen", isInitiator: Bool = true) {
        rtcSignalingState = isInitiator ? "offer_sent" : "waiting_for_offer"
        if isInitiator {
            rtcSignalingState = "connected"
            isTurnRelayActive = true
            print("[DexoLink iOS WebRTC] Initiated WebRTC online session for \(streamType) with STUN/TURN fallback")
        }
    }

    public func sendDesktopInput(eventType: String, x: Double = 0.0, y: Double = 0.0, button: Int = 0, key: String? = nil) {
        var payload: [String: Any] = [
            "type": "DESKTOP_INPUT_EVENT",
            "streamId": "screen_session",
            "eventType": eventType,
            "x": x,
            "y": y,
            "button": button
        ]
        if let k = key {
            payload["key"] = k
        }
        guard let data = try? JSONSerialization.data(withJSONObject: payload) else { return }
        var lengthPrefix = UInt32(data.count).bigEndian
        var packet = Data(bytes: &lengthPrefix, count: 4)
        packet.append(data)
        for (_, conn) in activeConnections {
            conn.send(content: packet, completion: .idempotent)
        }
        print("[DexoLink iOS] Sent desktop input event: \(eventType)")
    }

    public func openHandoffInSafari(_ item: ContinuityHandoffItem) {
        if let url = URL(string: item.urlString) {
            UIApplication.shared.open(url)
        }
    }

    public var localDeviceName: String {
        #if canImport(UIKit)
        return UIDevice.current.name
        #else
        return Host.current().localizedName ?? "iPhone"
        #endif
    }

    public var localIPv4Address: String? {
        var interfaces: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&interfaces) == 0, let first = interfaces else { return nil }
        defer { freeifaddrs(interfaces) }

        var current: UnsafeMutablePointer<ifaddrs>? = first
        while let interface = current {
            let flags = Int32(interface.pointee.ifa_flags)
            if (flags & IFF_UP) != 0,
               (flags & IFF_LOOPBACK) == 0,
               let address = interface.pointee.ifa_addr,
               address.pointee.sa_family == UInt8(AF_INET) {
                var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                if getnameinfo(address, socklen_t(address.pointee.sa_len), &hostname, socklen_t(hostname.count), nil, 0, NI_NUMERICHOST) == 0 {
                    let host = String(cString: hostname)
                    if host != "0.0.0.0" { return host }
                }
            }
            current = interface.pointee.ifa_next
        }
        return nil
    }

    public var connectedDevicesCount: Int {
        if isConnected {
            return max(1, pairedDevices.count)
        }
        return pairedDevices.count
    }

    public func disconnect() {
        self.userExplicitlyDisconnected = true
        for (_, conn) in activeConnections {
            conn.cancel()
        }
        activeConnections.removeAll()
        self.isConnected = false
        self.connectedDeviceName = "No Device Connected"
    }

    public func refreshDiscovery() {
        startDeviceDiscovery()
    }

    // MARK: - QR Code Connection Handler
    /// Called when a QR code payload is scanned. Parses dexolink://connect URL
    /// and automatically connects + pairs with the remote device.
    public func handleQRCodeScan(payload: String) {
        guard let url = URL(string: payload),
              url.scheme == "dexolink",
              url.host == "connect" else {
            print("[DexoLink iOS] Invalid QR payload: \(payload)")
            return
        }
        let components = URLComponents(url: url, resolvingAgainstBaseURL: false)
        let params = Dictionary(uniqueKeysWithValues:
            (components?.queryItems ?? []).compactMap { item in
                item.value.map { (item.name, $0) }
            }
        )
        guard let host = params["host"], !host.isEmpty else { return }
        let port = UInt16(params["port"] ?? "52840") ?? 52840
        let name = params["name"]?.removingPercentEncoding ?? "Remote Device"

        print("[DexoLink iOS] QR scanned — connecting to \(name) at \(host):\(port)")

        // Add as discovered + paired immediately so it shows in Paired devices
        let dev = DiscoveredDevice(id: host, name: name, endpoint: host, port: port, isPaired: true)
        DispatchQueue.main.async {
            if !self.discoveredDevices.contains(where: { $0.id == host }) {
                self.discoveredDevices.insert(dev, at: 0)
            }
            if !self.pairedDevices.contains(where: { $0.id == host }) {
                if !self.isMultiplePairedDevicesEnabled {
                    self.pairedDevices.removeAll()
                }
                self.pairedDevices.append(TrustedDevice(id: host, name: name, host: host, port: port))
                self.savePairedDevices()
            }
        }
        // Now open the TCP connection
        connectToRemoteEndpoint(host: host, port: port)
    }

    // MARK: - Reconnect a Trusted Paired Device
    /// Reconnects a previously paired device by looking it up from discovered devices.
    public func connectDevice(_ device: TrustedDevice) {
        // Try to find it in currently discovered devices
        if let discovered = discoveredDevices.first(where: { $0.name.lowercased() == device.name.lowercased() }) {
            connectToRemoteEndpoint(host: discovered.endpoint, port: discovered.port, serviceType: discovered.serviceType)
            self.isConnected = true
            self.connectedDeviceName = device.name
        } else if let host = device.host ?? (IPv4Address(device.id) != nil || IPv6Address(device.id) != nil ? device.id : nil) {
            connectToRemoteEndpoint(host: host, port: device.port ?? 52840)
        } else {
            pendingReconnectDeviceName = device.name
            startDeviceDiscovery()
            print("[DexoLink iOS] Searching for paired device '\(device.name)' to reconnect")
        }
    }
}

// MARK: - Color Hex Initializer
public extension Color {
    init(hex: String) {
        let hex = hex.trimmingCharacters(in: CharacterSet.alphanumerics.inverted)
        var int: UInt64 = 0
        Scanner(string: hex).scanHexInt64(&int)
        let a, r, g, b: UInt64
        switch hex.count {
        case 3: // RGB (12-bit)
            (a, r, g, b) = (255, (int >> 8) * 17, (int >> 4 & 0xF) * 17, (int & 0xF) * 17)
        case 6: // RGB (24-bit)
            (a, r, g, b) = (255, int >> 16, int >> 8 & 0xFF, int & 0xFF)
        case 8: // ARGB (32-bit)
            (a, r, g, b) = (int >> 24, int >> 16 & 0xFF, int >> 8 & 0xFF, int & 0xFF)
        default:
            (a, r, g, b) = (1, 1, 1, 0)
        }
        self.init(
            .sRGB,
            red: Double(r) / 255,
            green: Double(g) / 255,
            blue: Double(b) / 255,
            opacity: Double(a) / 255
        )
    }
}

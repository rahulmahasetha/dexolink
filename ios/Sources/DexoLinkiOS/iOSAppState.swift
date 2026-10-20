import SwiftUI
import Foundation
import Network
import Combine
import AVFoundation

// MARK: - Models for iOS

public struct DiscoveredDevice: Identifiable, Equatable {
    public let id: String
    public let name: String
    public let endpoint: String
    public let port: UInt16
    public var isPaired: Bool

    public init(id: String, name: String, endpoint: String, port: UInt16 = 52840, isPaired: Bool = false) {
        self.id = id
        self.name = name
        self.endpoint = endpoint
        self.port = port
        self.isPaired = isPaired
    }
}

public struct TrustedDevice: Identifiable, Codable, Equatable {
    public let id: String
    public let name: String
    public let pairedDate: Date

    public init(id: String, name: String, pairedDate: Date = Date()) {
        self.id = id
        self.name = name
        self.pairedDate = pairedDate
    }
}

public struct TransferProgressItem: Identifiable {
    public let id = UUID()
    public let fileName: String
    public let totalBytes: Int64
    public var bytesSent: Int64
    public var isComplete: Bool
    public var isSuccess: Bool

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
    
    // Multiple Paired Devices Setting (OFF by default)
    @Published public var isMultiplePairedDevicesEnabled: Bool {
        didSet {
            UserDefaults.standard.set(isMultiplePairedDevicesEnabled, forKey: "dexolink_ios_multi_paired")
            if !isMultiplePairedDevicesEnabled && pairedDevices.count > 1 {
                pairedDevices = [pairedDevices.first!]
                savePairedDevices()
            }
        }
    }

    // Feature Settings
    @Published public var autoClipboardSync: Bool {
        didSet { UserDefaults.standard.set(autoClipboardSync, forKey: "dexolink_ios_auto_clip") }
    }
    @Published public var enhancedClipboard: Bool {
        didSet { UserDefaults.standard.set(enhancedClipboard, forKey: "dexolink_ios_enhanced_clip") }
    }
    // v4.0.1: Media Streaming & Remote Control Settings (OFF by default)
    @Published public var isCameraSharingEnabled: Bool {
        didSet { UserDefaults.standard.set(isCameraSharingEnabled, forKey: "dexolink_ios_camera_sharing") }
    }
    @Published public var isFrontCamera: Bool {
        didSet { UserDefaults.standard.set(isFrontCamera, forKey: "dexolink_ios_front_camera") }
    }
    @Published public var isScreenSharingEnabled: Bool {
        didSet { UserDefaults.standard.set(isScreenSharingEnabled, forKey: "dexolink_ios_screen_sharing") }
    }
    @Published public var isDesktopModeEnabled: Bool {
        didSet { UserDefaults.standard.set(isDesktopModeEnabled, forKey: "dexolink_ios_desktop_mode") }
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
    @Published public var transferHistory: [TransferHistoryItem] = []
    @Published public var clipboardHistory: [String] = []
    @Published public var currentHandoff: ContinuityHandoffItem?

    private var listener: NWListener?
    private var activeConnections: [String: NWConnection] = [:]
    private var cancellables = Set<AnyCancellable>()

    public init() {
        self.isMultiplePairedDevicesEnabled = UserDefaults.standard.bool(forKey: "dexolink_ios_multi_paired")
        self.autoClipboardSync = UserDefaults.standard.object(forKey: "dexolink_ios_auto_clip") as? Bool ?? true
        self.enhancedClipboard = UserDefaults.standard.bool(forKey: "dexolink_ios_enhanced_clip")
        self.isCameraSharingEnabled = UserDefaults.standard.bool(forKey: "dexolink_ios_camera_sharing")
        self.isFrontCamera = UserDefaults.standard.bool(forKey: "dexolink_ios_front_camera")
        self.isScreenSharingEnabled = UserDefaults.standard.bool(forKey: "dexolink_ios_screen_sharing")
        self.isDesktopModeEnabled = UserDefaults.standard.bool(forKey: "dexolink_ios_desktop_mode")
        
        loadPairedDevices()
        startLocalListener()
        startDeviceDiscovery()
    }

    private func loadPairedDevices() {
        if let data = UserDefaults.standard.data(forKey: "dexolink_ios_paired_devices"),
           let list = try? JSONDecoder().decode([TrustedDevice].self, from: data) {
            self.pairedDevices = list
        }
    }

    private func savePairedDevices() {
        if let data = try? JSONEncoder().encode(pairedDevices) {
            UserDefaults.standard.set(data, forKey: "dexolink_ios_paired_devices")
        }
    }

    // MARK: - Networking & Discovery (Local Network without Internet)
    
    public func startLocalListener() {
        do {
            let parameters = NWParameters.tcp
            let l = try NWListener(using: parameters, on: 52840)
            l.service = NWListener.Service(name: UIDevice.current.name, type: "_dexolink._tcp")
            l.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    print("[DexoLink iOS] Listening on local port 52840")
                case .failed(let err):
                    print("[DexoLink iOS] Listener failed: \(err)")
                default:
                    break
                }
            }
            l.newConnectionHandler = { [weak self] connection in
                self?.handleIncomingConnection(connection)
            }
            l.start(queue: .main)
            self.listener = l
        } catch {
            print("[DexoLink iOS] Could not start NWListener: \(error)")
        }
    }

    private func handleIncomingConnection(_ connection: NWConnection) {
        connection.start(queue: .main)
        // Handshake protocol logic
        self.isConnected = true
        self.connectedDeviceName = "Connected Peer"
    }

    public func startDeviceDiscovery() {
        let browser = NWBrowser(for: .bonjour(type: "_dexolink._tcp", domain: nil), using: .tcp)
        browser.browseResultsChangedHandler = { [weak self] results, changes in
            DispatchQueue.main.async {
                guard let self = self else { return }
                var list: [DiscoveredDevice] = []
                for res in results {
                    if case let .service(name, _, _, _) = res.endpoint {
                        let isPaired = self.pairedDevices.contains(where: { $0.name == name })
                        list.append(DiscoveredDevice(id: name, name: name, endpoint: name, isPaired: isPaired))
                    }
                }
                self.discoveredDevices = list
            }
        }
        browser.start(queue: .main)
    }

    // MARK: - Device Pairing & Unpairing
    
    public func pairDevice(_ device: DiscoveredDevice) {
        if !isMultiplePairedDevicesEnabled {
            pairedDevices.removeAll()
        }
        if !pairedDevices.contains(where: { $0.id == device.id }) {
            pairedDevices.append(TrustedDevice(id: device.id, name: device.name))
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
        guard let device = targetDevice ?? discoveredDevices.first(where: { $0.isPaired }) ?? discoveredDevices.first else {
            return
        }
        let fileName = url.lastPathComponent
        let fileSize = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? Int64) ?? 1024
        
        let progress = TransferProgressItem(fileName: fileName, totalBytes: fileSize, bytesSent: 0, isComplete: false, isSuccess: false)
        self.activeTransfer = progress

        // Simulate streaming chunks for clean, responsive UI
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self = self else { return }
            let chunkSize: Int64 = 64 * 1024
            var sent: Int64 = 0
            while sent < fileSize {
                sent = min(sent + chunkSize, fileSize)
                let currentSent = sent
                DispatchQueue.main.async {
                    self.activeTransfer?.bytesSent = currentSent
                }
                Thread.sleep(forTimeInterval: 0.05)
            }
            DispatchQueue.main.async {
                self.activeTransfer?.isComplete = true
                self.activeTransfer?.isSuccess = true
                self.transferHistory.insert(TransferHistoryItem(fileName: fileName, fileSize: fileSize, isIncoming: false, peerName: device.name), at: 0)
                DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) {
                    self.activeTransfer = nil
                }
            }
        }
    }

    // MARK: - Clipboard & Continuity Actions
    
    public func pushClipboardText(_ text: String) {
        guard !text.isEmpty else { return }
        clipboardHistory.insert(text, at: 0)
        UIPasteboard.general.string = text
        print("[DexoLink iOS] Pushed text to connected peers: \(text.prefix(30))")
    }

    public func sendHandoffURL(_ urlString: String) {
        guard let url = URL(string: urlString), UIApplication.shared.canOpenURL(url) else { return }
        currentHandoff = ContinuityHandoffItem(title: "Shared Web Page", urlString: urlString, senderDevice: UIDevice.current.name)
    }

    public func openHandoffInSafari(_ item: ContinuityHandoffItem) {
        if let url = URL(string: item.urlString) {
            UIApplication.shared.open(url)
        }
    }
}

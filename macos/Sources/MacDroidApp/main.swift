import AppKit
import SwiftUI
import Network
import UserNotifications
import Core
import Networking
import Crypto
import UI

// Use NSApplication directly instead of SwiftUI @main App,
// because `swift run` does not create a proper .app bundle
// and SwiftUI MenuBarExtra silently fails without one.

let app = NSApplication.shared
app.setActivationPolicy(.accessory) // Menu-bar-only app, no Dock icon

// NSApplication.delegate is weak — we must keep a strong global reference
// to prevent the delegate from being deallocated.
let appDelegate = MacDroidAppDelegate()
app.delegate = appDelegate
app.run()


class MacDroidAppDelegate: NSObject, NSApplicationDelegate, UNUserNotificationCenterDelegate {
    private var statusItem: NSStatusItem!
    private var popover: NSPopover!
    let appState = AppState()

    func applicationDidFinishLaunching(_ notification: Notification) {
        // Configure native system notifications for device pairing
        let center = UNUserNotificationCenter.current()
        center.delegate = self

        let acceptAction = UNNotificationAction(identifier: "ACCEPT_PAIRING", title: "Accept", options: [.foreground])
        let rejectAction = UNNotificationAction(identifier: "REJECT_PAIRING", title: "Reject", options: [.destructive])
        let category = UNNotificationCategory(identifier: "PAIRING_CATEGORY", actions: [acceptAction, rejectAction], intentIdentifiers: [], options: [])
        center.setNotificationCategories([category])

        center.requestAuthorization(options: [.alert, .sound, .badge]) { granted, error in
            if granted {
                print("🔔 macOS system notification authorization granted")
            } else if let error = error {
                print("⚠️ Notification auth error: \(error.localizedDescription)")
            }
        }

        // Create the status bar item
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)

        if let button = statusItem.button {
            button.image = NSImage(systemSymbolName: "antenna.radiowaves.left.and.right", accessibilityDescription: "MacDroid")
            button.action = #selector(togglePopover)
            button.target = self
        }

        // Create the popover that shows our SwiftUI view
        popover = NSPopover()
        popover.contentSize = NSSize(width: 360, height: 520)
        popover.behavior = .transient
        popover.contentViewController = NSHostingController(
            rootView: MenuBarView(appState: appState)
        )

        print("✅ MacDroid is running in the menu bar!")
        print("   Click the antenna icon (⌘) in your menu bar to open.")

        // Register native system Services provider so DexoLink appears in macOS Services & Share menus
        NSApplication.shared.servicesProvider = self
        NSUpdateDynamicServices()

        // Handle direct file launches via CLI or Finder open
        let launchArgs = Array(CommandLine.arguments.dropFirst())
        let fileArgs = launchArgs.filter { FileManager.default.fileExists(atPath: $0) }.map { URL(fileURLWithPath: $0) }
        if !fileArgs.isEmpty {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { [weak self] in
                guard let self = self else { return }
                ShareWindowController.present(appState: self.appState, urls: fileArgs, text: nil)
            }
        }

        // Automatically open the popover on launch so the user sees the interface immediately
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { [weak self] in
            guard let self = self, let button = self.statusItem.button else { return }
            self.popover.show(relativeTo: button.bounds, of: button, preferredEdge: .minY)
            self.popover.contentViewController?.view.window?.makeKey()
        }
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification, withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound])
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse, withCompletionHandler completionHandler: @escaping () -> Void) {
        if response.actionIdentifier == "ACCEPT_PAIRING" {
            DispatchQueue.main.async {
                self.appState.acceptIncomingPairing()
            }
        } else if response.actionIdentifier == "REJECT_PAIRING" {
            DispatchQueue.main.async {
                self.appState.rejectIncomingPairing()
            }
        } else {
            DispatchQueue.main.async {
                if let button = self.statusItem.button, !self.popover.isShown {
                    self.popover.show(relativeTo: button.bounds, of: button, preferredEdge: .minY)
                }
            }
        }
        completionHandler()
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        return false // Keep running as a menu bar app even with no windows
    }

    @objc func togglePopover() {
        guard let button = statusItem.button else { return }

        if popover.isShown {
            popover.performClose(nil)
        } else {
            popover.show(relativeTo: button.bounds, of: button, preferredEdge: .minY)
            // Bring popover window to front
            popover.contentViewController?.view.window?.makeKey()
        }
    }

    // MARK: - Native macOS Services Provider & System Share Target
    @objc func shareToDexoLink(_ pboard: NSPasteboard, userData: String, error: AutoreleasingUnsafeMutablePointer<NSString?>) {
        var urls: [URL] = []
        var text: String? = nil

        // 1. Try reading file URLs
        if let fileURLs = pboard.readObjects(forClasses: [NSURL.self], options: nil) as? [URL], !fileURLs.isEmpty {
            urls = fileURLs
        } else if let filenames = pboard.propertyList(forType: .init("NSFilenamesPboardType")) as? [String] {
            urls = filenames.map { URL(fileURLWithPath: $0) }
        }

        // 2. Try reading string or public URL if no files
        if urls.isEmpty {
            if let string = pboard.string(forType: .string) {
                text = string
            } else if let urlString = pboard.string(forType: .init("public.url")) {
                text = urlString
            }
        }

        guard !urls.isEmpty || (text != nil && !text!.isEmpty) else {
            error.pointee = "No compatible files or text found to share via DexoLink." as NSString
            return
        }

        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            ShareWindowController.present(appState: self.appState, urls: urls, text: text)
        }
    }

    // MARK: - Finder "Open With" / File Handlers
    func application(_ sender: NSApplication, openFiles filenames: [String]) {
        let urls = filenames.map { URL(fileURLWithPath: $0) }
        guard !urls.isEmpty else { return }
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            ShareWindowController.present(appState: self.appState, urls: urls, text: nil)
        }
        sender.reply(toOpenOrPrint: .success)
    }

    // MARK: - Custom URL Scheme & Universal Open (dexolink://share?text=... or dexolink://share?file=...)
    func application(_ application: NSApplication, open urls: [URL]) {
        var fileUrls: [URL] = []
        var shareText: String? = nil

        for url in urls {
            if url.scheme?.lowercased() == "dexolink" {
                if let components = URLComponents(url: url, resolvingAgainstBaseURL: false) {
                    if let textItem = components.queryItems?.first(where: { $0.name == "text" || $0.name == "url" })?.value {
                        shareText = textItem
                    }
                    if let fileItem = components.queryItems?.first(where: { $0.name == "file" || $0.name == "path" })?.value {
                        fileUrls.append(URL(fileURLWithPath: fileItem))
                    }
                }
            } else if url.isFileURL {
                fileUrls.append(url)
            }
        }

        if !fileUrls.isEmpty || shareText != nil {
            DispatchQueue.main.async { [weak self] in
                guard let self = self else { return }
                ShareWindowController.present(appState: self.appState, urls: fileUrls, text: shareText)
            }
        }
    }
}

public struct PairingRequestInfo: Equatable {
    public let peerName: String
    public let publicKey: String
    public let pin: String
}

// A state object to hold our managers and pass them to the UI
class AppState: ObservableObject {
    let connectionManager = ConnectionManager()
    let discoveryService = DiscoveryService()
    let transferManager: TransferManager
    let clipboardManager = ClipboardManager()
    let pairingManager = PairingManager()

    @Published var isDiscovering = false
    @Published var isActivelyScanning = false
    @Published var discoveryEnabled = true
    @Published var discoveredDevices: [DiscoveredDevice] = []
    @Published var connectionState: ConnectionState = .disconnected
    @Published var connectedDeviceName: String? = nil
    @Published var connectedDeviceId: String? = nil
    @Published var pairingPin: String? = nil
    @Published var incomingPairingRequest: PairingRequestInfo? = nil
    @Published var lastPingLatencyMs: Int? = nil
    @Published var statusMessage: String = "Ready to connect"
    @Published var localIP: String = "Detecting..."
    @Published var lastSyncedClipboardSnippet: String? = nil
    @Published var activeTransfer: TransferItem? = nil
    @Published var recentTransfers: [TransferItem] = []
    @Published var userExplicitlyDisconnected: Bool = false

    private var pingStartTime: Date?
    private var activeScanTimer: Timer?
    private var backgroundAvailabilityTimer: Timer?
    private var heartbeatTimer: Timer?
    private var lastDataActivityTimestamp = Date()

    init() {
        self.transferManager = TransferManager(connectionManager: connectionManager)
        self.localIP = AppState.getLocalIP() ?? "Unavailable"

        // Wire up delegates
        discoveryService.delegate = self
        connectionManager.delegate = self
        clipboardManager.setConnectionManager(connectionManager)
        clipboardManager.delegate = self
        transferManager.delegate = self

        // Start advertiser listener
        discoveryService.startAdvertising(port: 52840)

        // Start discovery with an active scanning window
        startDiscovery(showActiveIndicator: true)

        // Periodic background availability refresh (every 30 seconds with 5s tolerance to coalesce CPU wakeups)
        backgroundAvailabilityTimer = Timer.scheduledTimer(withTimeInterval: 30.0, repeats: true) { [weak self] _ in
            guard let self = self, self.discoveryEnabled, !self.connectionState.isConnected else { return }
            self.discoveryService.refreshDeviceAvailability()
        }
        backgroundAvailabilityTimer?.tolerance = 5.0

        print("📡 Discovery started on \(localIP):\(discoveryService.listeningPort)...")
    }

    func startDiscovery(showActiveIndicator: Bool = true) {
        guard discoveryEnabled else { return }
        if showActiveIndicator {
            isActivelyScanning = true
            activeScanTimer?.invalidate()
            activeScanTimer = Timer.scheduledTimer(withTimeInterval: 6.0, repeats: false) { [weak self] _ in
                DispatchQueue.main.async {
                    self?.isActivelyScanning = false
                }
            }
        }
        discoveryService.startBrowsing()
        UDPBeaconService.shared.resumeBroadcasting(activeBurst: true)
        isDiscovering = true
        statusMessage = "Discovery enabled"
    }

    func stopDiscovery() {
        discoveryService.stopBrowsing()
        UDPBeaconService.shared.pauseBroadcasting()
        isDiscovering = false
        isActivelyScanning = false
        activeScanTimer?.invalidate()
        statusMessage = "Discovery stopped"
    }

    func setDiscoveryEnabled(_ enabled: Bool) {
        discoveryEnabled = enabled
        if enabled {
            startDiscovery(showActiveIndicator: true)
            print("▶️ Discovery started — listening for devices & enabling pairing")
        } else {
            stopDiscovery()
            print("⏹️ Discovery stopped — suppressing pairing notifications")
        }
    }

    func refreshDiscovery() {
        guard discoveryEnabled else {
            setDiscoveryEnabled(true)
            return
        }
        isActivelyScanning = true
        activeScanTimer?.invalidate()
        activeScanTimer = Timer.scheduledTimer(withTimeInterval: 6.0, repeats: false) { [weak self] _ in
            DispatchQueue.main.async {
                self?.isActivelyScanning = false
            }
        }
        discoveryService.refreshBrowsing()
        UDPBeaconService.shared.resumeBroadcasting(activeBurst: true)
        print("🔄 Manual discovery rescan initiated")
    }

    static func getLocalIP() -> String? {
        var address: String?
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let firstAddr = ifaddr else { return nil }
        defer { freeifaddrs(ifaddr) }

        for ptr in sequence(first: firstAddr, next: { $0.pointee.ifa_next }) {
            let interface = ptr.pointee
            let addrFamily = interface.ifa_addr.pointee.sa_family
            if addrFamily == UInt8(AF_INET) {
                let flags = Int32(interface.ifa_flags)
                let isUp = (flags & IFF_UP) == IFF_UP
                let isLoopback = (flags & IFF_LOOPBACK) == IFF_LOOPBACK
                if isUp && !isLoopback {
                    let name = String(cString: interface.ifa_name)
                    var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                    if getnameinfo(interface.ifa_addr, socklen_t(interface.ifa_addr.pointee.sa_len),
                                   &hostname, socklen_t(hostname.count),
                                   nil, socklen_t(0), NI_NUMERICHOST) == 0 {
                        let ip = String(cString: hostname)
                        if !ip.isEmpty && ip != "127.0.0.1" {
                            if name.hasPrefix("en") {
                                return ip // Prefer active Wi-Fi or Ethernet
                            }
                            if address == nil {
                                address = ip
                            }
                        }
                    }
                }
            }
        }
        return address
    }

    func startHeartbeat() {
        stopHeartbeat()
        lastDataActivityTimestamp = Date()
        heartbeatTimer = Timer.scheduledTimer(withTimeInterval: 15.0, repeats: true) { [weak self] _ in
            guard let self = self, self.connectionState.isConnected else { return }
            let idle = Date().timeIntervalSince(self.lastDataActivityTimestamp)
            if idle >= 45.0 {
                print("⚠️ No data or heartbeat received for 45s. Disconnecting stale connection...")
                self.connectionManager.disconnect()
            } else if idle >= 15.0 {
                self.pingStartTime = Date()
                self.connectionManager.send(message: .ping)
                print("⚡ Heartbeat PING sent from macOS (idle: \(Int(idle))s)")
            }
        }
        heartbeatTimer?.tolerance = 2.0
    }

    func stopHeartbeat() {
        heartbeatTimer?.invalidate()
        heartbeatTimer = nil
    }

    func connect(to device: DiscoveredDevice) {
        if self.connectionState.isConnected {
            print("ℹ️ Already connected, skipping connect to \(device.name)")
            return
        }
        userExplicitlyDisconnected = false
        print("🔗 Connecting to \(device.name)...")
        statusMessage = "Connecting to \(device.name)..."
        if let host = device.host, let port = device.port, !host.isEmpty, port > 0 {
            connectDirect(host: host, port: port)
        } else if let endpoint = device.endpoint {
            connectionManager.connect(to: endpoint)
        } else {
            let sName = device.serviceName ?? device.name
            let ep = NWEndpoint.service(name: sName, type: "_macdroid._tcp", domain: "local.", interface: nil)
            connectionManager.connect(to: ep)
        }
    }

    func disconnect() {
        print("🔌 Disconnecting...")
        stopHeartbeat()
        userExplicitlyDisconnected = true
        clipboardManager.direction = .disabled
        transferManager.cancelAll()
        connectionManager.disconnect()
        connectedDeviceName = nil
        connectedDeviceId = nil
        statusMessage = "Disconnected"
    }

    func unpair() {
        let pairedName = TrustStore.shared.getPairedDevice()?.deviceName ?? (connectedDeviceName ?? "Device")
        print("🗑️ Unpairing from \(pairedName)...")
        stopHeartbeat()
        userExplicitlyDisconnected = true
        clipboardManager.direction = .disabled
        transferManager.cancelAll()
        connectionManager.send(message: .unpair)
        TrustStore.shared.unpairDevice()
        connectionManager.disconnect()
        connectedDeviceName = nil
        connectedDeviceId = nil
        pairingPin = nil
        statusMessage = "Unpaired from \(pairedName)"
    }

    func sendPing() {
        pingStartTime = Date()
        connectionManager.send(message: .ping)
        print("⚡ Ping sent to peer")
    }

    func sendClipboard() {
        let pasteboard = NSPasteboard.general
        guard let text = pasteboard.string(forType: .string), !text.isEmpty else {
            statusMessage = "Mac clipboard is empty"
            return
        }
        let seq = Int(Date().timeIntervalSince1970) % 100000
        connectionManager.send(message: .clipboardPush(sequenceNumber: seq, content: text))
        statusMessage = "Clipboard sent to \(connectedDeviceName ?? "Android")"
        print("📋 Clipboard pushed: \(text.prefix(30))...")
    }

    func connectDirect(host: String, port: Int) {
        if self.connectionState.isConnected {
            print("ℹ️ Already connected, skipping direct connect")
            return
        }
        userExplicitlyDisconnected = false
        print("🔗 Connecting directly to \(host):\(port)...")
        statusMessage = "Connecting to \(host):\(port)..."
        connectionManager.connect(to: .hostPort(host: .init(host), port: .init(integerLiteral: UInt16(port))))
    }

    func sendFile(url: URL) {
        transferManager.sendFile(url: url)
    }

    func cancelTransfer(id: String) {
        transferManager.cancelTransfer(id: id)
    }

    func resumeTransfer(id: String) {
        transferManager.resumeTransfer(id: id)
    }

    func acceptIncomingPairing() {
        guard let req = incomingPairingRequest else { return }
        let peerName = req.peerName
        self.connectionManager.send(message: .pairResponse(accepted: true, publicKey: CryptoService.shared.publicKeyBase64))
        self.pairingManager.confirmPairing()
        self.incomingPairingRequest = nil
        self.pairingPin = nil
        self.clipboardManager.direction = .bidirectional
        self.statusMessage = "Connected to \(peerName) (Paired)"
        
        // Automatically stop discovery and scanning on both devices once pairing succeeds!
        self.discoveryService.stopBrowsing()
        self.isActivelyScanning = false
        print("✅ Accepted pairing with \(peerName) — stopped discovery & scanning")
    }

    func rejectIncomingPairing() {
        guard let req = incomingPairingRequest else { return }
        let peerName = req.peerName
        self.connectionManager.send(message: .pairResponse(accepted: false, publicKey: "", reason: "Pairing was rejected on Mac"))
        self.incomingPairingRequest = nil
        self.pairingPin = nil
        self.connectionManager.disconnect()
        
        // If pairing is rejected or fails, continue scanning until pairing succeeds or user clicks Stop!
        if self.discoveryEnabled {
            self.discoveryService.startBrowsing()
            self.isActivelyScanning = true
            self.statusMessage = "Pairing rejected. Continuing scanning..."
        } else {
            self.statusMessage = "Pairing rejected."
        }
        print("⛔ Rejected pairing with \(peerName) — continuing discovery if enabled")
    }
}

extension AppState: DiscoveryServiceDelegate {
    func discoveryService(_ service: DiscoveryService, didDiscover devices: [DiscoveredDevice]) {
        DispatchQueue.main.async {
            // Filter out self if discovered
            let myName = Host.current().localizedName ?? "MacBook"
            self.discoveredDevices = devices.filter { !$0.name.contains(myName) }

            // Strict 1-device policy: only auto-connect if discovery is enabled, NOT explicitly disconnected, currently disconnected, and device is online!
            if self.discoveryEnabled, !self.userExplicitlyDisconnected, case .disconnected = self.connectionState {
                if let paired = TrustStore.shared.getPairedDevice(),
                   let matched = self.discoveredDevices.first(where: { ($0.id == paired.deviceId || $0.name.caseInsensitiveCompare(paired.deviceName) == .orderedSame) && $0.isOnline }) {
                    print("🔄 Discovered paired online device: \(matched.name), auto-connecting...")
                    self.connect(to: matched)
                }
            }
        }
    }

    func discoveryService(_ service: DiscoveryService, didReceiveIncomingConnection connection: NWConnection) {
        if self.userExplicitlyDisconnected {
            print("⛔ Rejecting incoming connection because user explicitly disconnected")
            connection.cancel()
            return
        }
        if self.connectionState.isConnected {
            print("ℹ️ Ignoring redundant incoming connection from \(connection.endpoint) because active session is already connected")
            connection.cancel()
            return
        }
        print("📲 Accepting incoming connection from: \(connection.endpoint)")
        connectionManager.accept(connection: connection)
    }
}

extension AppState: ConnectionManagerDelegate {
    func connectionManager(_ manager: ConnectionManager, didChangeState state: ConnectionState) {
        DispatchQueue.main.async {
            self.connectionState = state
            switch state {
            case .connected:
                self.startHeartbeat()
                self.statusMessage = "Connected. Handshaking..."
                let myName = Host.current().localizedName ?? "MacBook"
                let hello = ProtocolMessage.hello(version: 2, deviceId: CryptoService.shared.deviceId, deviceName: myName)
                self.connectionManager.send(message: hello)
                self.clipboardManager.direction = .bidirectional
            case .authenticated:
                self.startHeartbeat()
                self.clipboardManager.direction = .bidirectional
                self.statusMessage = "Connected to \(self.connectedDeviceName ?? "Device") (Paired)"
                // Automatically stop discovery, beacons, and scanning once connected & authenticated to save battery
                self.discoveryService.stopBrowsing()
                UDPBeaconService.shared.pauseBroadcasting()
                self.isActivelyScanning = false
            case .transferring:
                self.statusMessage = "Transferring..."
            case .disconnected:
                self.stopHeartbeat()
                self.connectedDeviceName = nil
                self.connectedDeviceId = nil
                self.pairingPin = nil
                self.incomingPairingRequest = nil
                self.statusMessage = "Disconnected"
                self.clipboardManager.direction = .disabled
                // If discovery is enabled, resume beacons and browsing
                if self.discoveryEnabled {
                    UDPBeaconService.shared.resumeBroadcasting(activeBurst: true)
                    if !TrustStore.shared.hasPairedDevice() {
                        self.discoveryService.startBrowsing()
                        self.isActivelyScanning = true
                    }
                }
            case .failed(let err):
                self.stopHeartbeat()
                self.connectedDeviceId = nil
                self.pairingPin = nil
                self.incomingPairingRequest = nil
                self.statusMessage = "Connection failed: \(err.localizedDescription)"
                self.clipboardManager.direction = .disabled
                if self.discoveryEnabled {
                    UDPBeaconService.shared.resumeBroadcasting(activeBurst: true)
                    if !TrustStore.shared.hasPairedDevice() {
                        self.discoveryService.startBrowsing()
                        self.isActivelyScanning = true
                    }
                }
            case .connecting:
                self.statusMessage = "Connecting..."
            }
        }
    }

    func connectionManager(_ manager: ConnectionManager, didReceiveMessage message: ProtocolMessage) {
        DispatchQueue.main.async { [weak self] in
            self?.handleProtocolMessage(message)
        }
    }

    private func handleProtocolMessage(_ message: ProtocolMessage) {
        self.lastDataActivityTimestamp = Date()
        switch message {
        case let .hello(_, deviceId, deviceName):
            self.connectedDeviceName = deviceName
            self.connectedDeviceId = deviceId
            let myName = Host.current().localizedName ?? "MacBook"
            let isTrusted = TrustStore.shared.isPaired(deviceId: deviceId, deviceName: deviceName)

            if isTrusted {
                self.statusMessage = "Paired with \(deviceName) (Trusted) • Syncing"
                self.clipboardManager.direction = .bidirectional
                let ack = ProtocolMessage.helloAck(
                    version: 2,
                    deviceId: CryptoService.shared.deviceId,
                    deviceName: myName,
                    paired: true
                )
                self.connectionManager.send(message: ack)
                // Stop discovery since we are paired and connected
                self.discoveryService.stopBrowsing()
                self.isActivelyScanning = false
                print("🤝 Hello received from paired device \(deviceName) — sent HelloAck(paired: true)")
            } else {
                self.statusMessage = "Handshaking with \(deviceName)..."
                let ack = ProtocolMessage.helloAck(
                    version: 2,
                    deviceId: CryptoService.shared.deviceId,
                    deviceName: myName,
                    paired: false
                )
                self.connectionManager.send(message: ack)
                print("🤝 Sent HelloAck(paired: false) to \(deviceName)")
            }

        case let .helloAck(_, deviceId, deviceName, paired):
            self.connectedDeviceName = deviceName
            self.connectedDeviceId = deviceId
            if paired {
                self.statusMessage = "Connected to \(deviceName) • Syncing"
                self.clipboardManager.direction = .bidirectional
                if !TrustStore.shared.isPaired(deviceId: deviceId, deviceName: deviceName) {
                    TrustStore.shared.setPairedDevice(
                        TrustedDevice(deviceId: deviceId, deviceName: deviceName, publicKeyBase64: "", pairedAt: Date())
                    )
                }
                self.discoveryService.stopBrowsing()
                self.isActivelyScanning = false
                print("🤝 HelloAck(paired: true) received from \(deviceName)")
            } else {
                if !self.connectionManager.isIncoming {
                    if !self.discoveryEnabled {
                        print("🔇 Discovery is stopped: Suppressing pair request to \(deviceName)")
                    } else if !TrustStore.shared.hasPairedDevice() {
                        self.statusMessage = "Pairing request sent to \(deviceName)..."
                        self.connectionManager.send(message: .pairRequest(publicKey: CryptoService.shared.publicKeyBase64))
                        print("🔐 HelloAck(paired: false) received from \(deviceName) — Mac initiated PairRequest")
                    } else {
                        print("ℹ️ Cannot pair with \(deviceName): Already paired with \(TrustStore.shared.getPairedDevice()?.deviceName ?? "")")
                    }
                } else {
                    print("ℹ️ Received HelloAck as receiver from \(deviceName) — awaiting incoming PairRequest")
                }
            }

        case let .pairRequest(publicKey):
            let peerName = self.connectedDeviceName ?? "Device"
            let peerId = self.connectedDeviceId ?? peerName

            // 1. If discovery is stopped, suppress notifications and reject:
            if !self.discoveryEnabled {
                print("🔇 Discovery is stopped: Suppressing pairing request and rejecting connection from \(peerName)")
                self.connectionManager.send(message: .pairResponse(
                    accepted: false,
                    publicKey: "",
                    reason: "Discovery is currently disabled on this Mac."
                ))
                self.statusMessage = "Rejected pairing request (Discovery Stopped)"
                return
            }

            // 2. Strict 1-device policy: check if Mac is already paired with another device
            if let paired = TrustStore.shared.getPairedDevice(), paired.deviceName != peerName && paired.deviceId != peerId {
                print("⛔ Pairing request from \(peerName) rejected: Mac already paired with \(paired.deviceName)")
                self.connectionManager.send(message: .pairResponse(
                    accepted: false,
                    publicKey: "",
                    reason: "Mac is already paired with '\(paired.deviceName)'. In accordance with DexoLink's strict one-device policy, please unpair first."
                ))
                self.statusMessage = "Rejected pairing from \(peerName) (Already paired with \(paired.deviceName))"
                return
            }

            self.pairingManager.initiatePairing(peerId: peerId, peerName: peerName, peerKeyBase64: publicKey)
            var pin = ""
            if case .waitingForPin(let p) = self.pairingManager.state {
                pin = p
                self.pairingPin = p
            }

            self.incomingPairingRequest = PairingRequestInfo(peerName: peerName, publicKey: publicKey, pin: pin)
            self.statusMessage = "Pairing request from \(peerName) • Awaiting approval"

            // Send system notification with Accept and Reject actions
            let content = UNMutableNotificationContent()
            content.title = "DexoLink: Pairing Request"
            content.body = "\(peerName) wants to pair with your Mac. Accept connection?"
            content.sound = .default
            content.categoryIdentifier = "PAIRING_CATEGORY"
            let req = UNNotificationRequest(identifier: "dexolink_pairing_\(peerName)", content: content, trigger: nil)
            UNUserNotificationCenter.current().add(req, withCompletionHandler: nil)
            print("🔔 Sent pairing notification with Accept/Reject for \(peerName)")

        case let .pairResponse(accepted, publicKey, reason):
            let peerName = self.connectedDeviceName ?? "Device"
            let peerId = self.connectedDeviceId ?? peerName
            if accepted {
                self.pairingManager.initiatePairing(peerId: peerId, peerName: peerName, peerKeyBase64: publicKey)
                self.pairingManager.confirmPairing()
                self.pairingPin = nil
                self.clipboardManager.direction = .bidirectional
                self.statusMessage = "Connected to \(peerName) (Paired)"
                
                // Automatically stop discovery and scanning on both devices once pairing succeeds!
                self.discoveryService.stopBrowsing()
                self.isActivelyScanning = false
                print("✅ Completed pairing with \(peerName) — stopped scanning")
            } else {
                let msg = reason ?? "Pairing declined by \(peerName)"
                self.statusMessage = msg
                print("⛔ Pairing declined by \(peerName): \(msg)")
                self.connectionManager.disconnect()
                
                // If pairing is rejected or fails, continue scanning until pairing succeeds or user clicks Stop!
                if self.discoveryEnabled {
                    self.discoveryService.startBrowsing()
                    self.isActivelyScanning = true
                    self.statusMessage = "Pairing rejected: \(msg). Continuing discovery..."
                }
            }

        case .unpair:
            let peer = self.connectedDeviceName ?? "Device"
            print("🗑️ Peer \(peer) requested unpair")
            self.userExplicitlyDisconnected = true
            self.clipboardManager.direction = .disabled
            self.transferManager.cancelAll()
            TrustStore.shared.unpairDevice()
            self.connectionManager.disconnect()
            self.connectedDeviceName = nil
            self.pairingPin = nil
            self.statusMessage = "\(peer) unpaired"

            case .ping:
                self.connectionManager.send(message: .pong)

            case .pong:
                if let start = self.pingStartTime {
                    let latency = Int(Date().timeIntervalSince(start) * 1000)
                    self.lastPingLatencyMs = latency
                    self.statusMessage = "Ping reply: \(latency)ms"
                    print("🚀 Pong received in \(latency)ms")
                }

            case let .clipboardMode(mode):
                print("⚙️ [macOS Clipboard Mode] Received sync mode change from peer: \(mode)")
                switch mode {
                case "android_to_mac":
                    self.clipboardManager.direction = .receiveOnly
                    self.statusMessage = "Mode: Android → Mac (Receive Only)"
                case "mac_to_android":
                    self.clipboardManager.direction = .sendOnly
                    self.statusMessage = "Mode: Mac → Android (Send Only)"
                default:
                    self.clipboardManager.direction = .bidirectional
                    self.statusMessage = "Mode: Two-Way Sync Active"
                }

            case .clipboardPush, .clipboardAck:
                self.clipboardManager.handleMessage(message)

            default:
                self.transferManager.handleMessage(message)
            }
        }
    }

extension AppState: ClipboardManagerDelegate {
    func clipboardManager(_ manager: ClipboardManager, didReceiveText text: String) {
        DispatchQueue.main.async {
            self.lastSyncedClipboardSnippet = text
            self.statusMessage = "Synced from \(self.connectedDeviceName ?? "Android")"
            print("📋 Auto-synced clipboard from Android: \"\(text.prefix(35))...\"")
        }
    }

    func clipboardManager(_ manager: ClipboardManager, didSendText text: String) {
        DispatchQueue.main.async {
            self.lastSyncedClipboardSnippet = text
            self.statusMessage = "Synced to \(self.connectedDeviceName ?? "Android")"
            print("📋 Auto-synced clipboard to Android: \"\(text.prefix(35))...\"")
        }
    }
}

extension AppState: TransferManagerDelegate {
    func transferManager(_ manager: TransferManager, didUpdateItem item: TransferItem) {
        DispatchQueue.main.async {
            self.activeTransfer = item
        }
    }

    func transferManager(_ manager: TransferManager, didCompleteItem item: TransferItem) {
        DispatchQueue.main.async {
            self.activeTransfer = nil
            self.recentTransfers.insert(item, at: 0)
            self.statusMessage = item.isOutgoing ? "Sent: \(item.name)" : "Received: \(item.name)"
        }
    }

    func transferManager(_ manager: TransferManager, didFailItem item: TransferItem, error: String) {
        DispatchQueue.main.async {
            self.activeTransfer = nil
            self.recentTransfers.insert(item, at: 0)
            self.statusMessage = "Transfer failed: \(error)"
        }
    }
}

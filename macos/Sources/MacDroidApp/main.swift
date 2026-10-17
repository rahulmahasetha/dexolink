import AppKit
import SwiftUI
import Network
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


class MacDroidAppDelegate: NSObject, NSApplicationDelegate {
    private var statusItem: NSStatusItem!
    private var popover: NSPopover!
    let appState = AppState()

    func applicationDidFinishLaunching(_ notification: Notification) {
        // Create the status bar item
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)

        if let button = statusItem.button {
            button.image = NSImage(systemSymbolName: "antenna.radiowaves.left.and.right", accessibilityDescription: "MacDroid")
            button.action = #selector(togglePopover)
            button.target = self
        }

        // Create the popover that shows our SwiftUI view
        popover = NSPopover()
        popover.contentSize = NSSize(width: 320, height: 400)
        popover.behavior = .transient
        popover.contentViewController = NSHostingController(
            rootView: MenuBarView(appState: appState)
        )

        print("✅ MacDroid is running in the menu bar!")
        print("   Click the antenna icon (⌘) in your menu bar to open.")
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
    @Published var pairingPin: String? = nil
    @Published var lastPingLatencyMs: Int? = nil
    @Published var statusMessage: String = "Ready to connect"
    @Published var localIP: String = "Detecting..."
    @Published var lastSyncedClipboardSnippet: String? = nil
    @Published var activeTransfer: TransferItem? = nil
    @Published var recentTransfers: [TransferItem] = []

    private var pingStartTime: Date?
    private var activeScanTimer: Timer?
    private var backgroundAvailabilityTimer: Timer?

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

        // Periodic background availability refresh (every 30 seconds)
        backgroundAvailabilityTimer = Timer.scheduledTimer(withTimeInterval: 30.0, repeats: true) { [weak self] _ in
            guard let self = self, self.discoveryEnabled else { return }
            self.discoveryService.refreshDeviceAvailability()
        }

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
        isDiscovering = true
        statusMessage = "Discovery enabled"
    }

    func stopDiscovery() {
        discoveryService.stopBrowsing()
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

    func connect(to device: DiscoveredDevice) {
        print("🔗 Connecting to \(device.name)...")
        statusMessage = "Connecting to \(device.name)..."
        if let endpoint = device.endpoint {
            connectionManager.connect(to: endpoint)
        } else if let host = device.host, let port = device.port {
            connectDirect(host: host, port: port)
        } else {
            let sName = device.serviceName ?? device.name
            let ep = NWEndpoint.service(name: sName, type: "_macdroid._tcp", domain: "local.", interface: nil)
            connectionManager.connect(to: ep)
        }
    }

    func disconnect() {
        print("🔌 Disconnecting...")
        connectionManager.disconnect()
        connectedDeviceName = nil
        statusMessage = "Disconnected"
    }

    func unpair() {
        let pairedName = TrustStore.shared.getPairedDevice()?.deviceName ?? (connectedDeviceName ?? "Device")
        print("🗑️ Unpairing from \(pairedName)...")
        connectionManager.send(message: .unpair)
        TrustStore.shared.unpairDevice()
        connectionManager.disconnect()
        connectedDeviceName = nil
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
}

extension AppState: DiscoveryServiceDelegate {
    func discoveryService(_ service: DiscoveryService, didDiscover devices: [DiscoveredDevice]) {
        DispatchQueue.main.async {
            // Filter out self if discovered
            let myName = Host.current().localizedName ?? "MacBook"
            self.discoveredDevices = devices.filter { !$0.name.contains(myName) }

            // Strict 1-device policy: only auto-connect if discovery is enabled, currently disconnected, and device is online!
            if self.discoveryEnabled, case .disconnected = self.connectionState {
                if let paired = TrustStore.shared.getPairedDevice(),
                   let matched = self.discoveredDevices.first(where: { ($0.id == paired.deviceId || $0.name == paired.deviceName) && $0.isOnline }) {
                    print("🔄 Discovered paired online device: \(matched.name), auto-connecting...")
                    self.connect(to: matched)
                }
            }
        }
    }

    func discoveryService(_ service: DiscoveryService, didReceiveIncomingConnection connection: NWConnection) {
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
                self.statusMessage = "Connected. Handshaking..."
                let myName = Host.current().localizedName ?? "MacBook"
                let hello = ProtocolMessage.hello(version: 2, deviceId: CryptoService.shared.deviceId, deviceName: myName)
                self.connectionManager.send(message: hello)
                self.clipboardManager.direction = .bidirectional
            case .authenticated:
                self.clipboardManager.direction = .bidirectional
                self.statusMessage = "Connected • Auto Clipboard Sync Active"
            case .transferring:
                self.statusMessage = "Transferring..."
            case .disconnected:
                self.connectedDeviceName = nil
                self.pairingPin = nil
                self.statusMessage = "Disconnected"
                self.clipboardManager.direction = .disabled
            case .failed(let err):
                self.pairingPin = nil
                self.statusMessage = "Connection failed: \(err.localizedDescription)"
                self.clipboardManager.direction = .disabled
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
        switch message {
        case let .hello(_, deviceId, deviceName):
            self.connectedDeviceName = deviceName
            let myName = Host.current().localizedName ?? "MacBook"
            let isTrusted = TrustStore.shared.isPaired(deviceId: deviceId) ||
                            (TrustStore.shared.getPairedDevice()?.deviceName == deviceName)

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
                // Strict 1-device policy & discoveryEnabled check:
                if !self.discoveryEnabled {
                    print("🔇 Discovery is stopped: Suppressing new device pairing request from \(deviceName)")
                } else if !TrustStore.shared.hasPairedDevice() {
                    self.connectionManager.send(message: .pairRequest(publicKey: CryptoService.shared.publicKeyBase64))
                    print("🔐 Hello received from unpaired device \(deviceName) — sent HelloAck(paired: false) & PairRequest")
                } else {
                    print("ℹ️ Hello from \(deviceName), but Mac is already paired with \(TrustStore.shared.getPairedDevice()?.deviceName ?? ""). Awaiting manual action.")
                }
            }

        case let .helloAck(_, deviceId, deviceName, paired):
            self.connectedDeviceName = deviceName
            if paired {
                self.statusMessage = "Connected to \(deviceName) • Syncing"
                self.clipboardManager.direction = .bidirectional
                if !TrustStore.shared.isPaired(deviceId: deviceId) {
                    TrustStore.shared.setPairedDevice(
                        TrustedDevice(deviceId: deviceId, deviceName: deviceName, publicKeyBase64: "", pairedAt: Date())
                    )
                }
                print("🤝 HelloAck(paired: true) received from \(deviceName)")
            } else {
                if !self.discoveryEnabled {
                    print("🔇 Discovery is stopped: Suppressing pair request to \(deviceName)")
                } else if !TrustStore.shared.hasPairedDevice() {
                    self.statusMessage = "Pairing with \(deviceName)..."
                    self.connectionManager.send(message: .pairRequest(publicKey: CryptoService.shared.publicKeyBase64))
                    print("🔐 HelloAck(paired: false) received from \(deviceName) — initiated PairRequest")
                } else {
                    print("ℹ️ Cannot pair with \(deviceName): Already paired with \(TrustStore.shared.getPairedDevice()?.deviceName ?? "")")
                }
            }

        case let .pairRequest(publicKey):
            let peerName = self.connectedDeviceName ?? "Device"

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
            if let paired = TrustStore.shared.getPairedDevice(), paired.deviceName != peerName {
                print("⛔ Pairing request from \(peerName) rejected: Mac already paired with \(paired.deviceName)")
                self.connectionManager.send(message: .pairResponse(
                    accepted: false,
                    publicKey: "",
                    reason: "Mac is already paired with '\(paired.deviceName)'. In accordance with DexoLink's strict one-device policy, please unpair first."
                ))
                self.statusMessage = "Rejected pairing from \(peerName) (Already paired with \(paired.deviceName))"
                return
            }

            self.pairingManager.initiatePairing(peerId: peerName, peerName: peerName, peerKeyBase64: publicKey)
            if case .waitingForPin(let pin) = self.pairingManager.state {
                self.pairingPin = pin
                self.statusMessage = "Pairing Code: \(pin) • Confirming..."
                print("🔐 SAS 6-digit confirmation PIN: \(pin) for \(peerName)")
            }
            self.connectionManager.send(message: .pairResponse(accepted: true, publicKey: CryptoService.shared.publicKeyBase64))
            self.pairingManager.confirmPairing()
            self.pairingPin = nil
            self.clipboardManager.direction = .bidirectional
            self.statusMessage = "Paired with \(peerName) • Auto Syncing"
            print("✅ Completed one-time pairing with \(peerName)")

        case let .pairResponse(accepted, publicKey, reason):
            let peerName = self.connectedDeviceName ?? "Device"
            if accepted {
                self.pairingManager.initiatePairing(peerId: peerName, peerName: peerName, peerKeyBase64: publicKey)
                self.pairingManager.confirmPairing()
                self.pairingPin = nil
                self.clipboardManager.direction = .bidirectional
                self.statusMessage = "Paired with \(peerName) • Auto Syncing"
                print("✅ Completed one-time pairing response with \(peerName)")
            } else {
                let msg = reason ?? "Pairing declined by \(peerName)"
                self.statusMessage = msg
                print("⛔ Pairing declined by \(peerName): \(msg)")
                self.connectionManager.disconnect()
            }

        case .unpair:
            let peer = self.connectedDeviceName ?? "Device"
            print("🗑️ Peer \(peer) requested unpair")
            TrustStore.shared.unpairDevice()
            self.connectionManager.disconnect()
            self.connectedDeviceName = nil
            self.pairingPin = nil
            self.statusMessage = "\(peer) unpaired"

            case .ping:
                self.connectionManager.send(message: .pong)
                print("🏓 Ping received — sent Pong")

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

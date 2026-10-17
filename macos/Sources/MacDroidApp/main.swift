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
    @Published var discoveredDevices: [DiscoveredDevice] = []
    @Published var connectionState: ConnectionState = .disconnected
    @Published var connectedDeviceName: String? = nil
    @Published var pairingPin: String? = nil
    @Published var lastPingLatencyMs: Int? = nil
    @Published var statusMessage: String = "Ready to connect"
    @Published var localIP: String = "10.7.196.204"
    @Published var lastSyncedClipboardSnippet: String? = nil

    private var pingStartTime: Date?

    init() {
        self.transferManager = TransferManager(connectionManager: connectionManager)

        if let ip = Self.getLocalIP() {
            self.localIP = ip
        }

        // Wire up delegates
        discoveryService.delegate = self
        connectionManager.delegate = self
        clipboardManager.setConnectionManager(connectionManager)
        clipboardManager.delegate = self

        // Start discovering right away
        discoveryService.startBrowsing()
        discoveryService.startAdvertising(port: 52840)
        isDiscovering = true

        print("📡 Discovery started on \(localIP):\(discoveryService.listeningPort)...")
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
                let name = String(cString: interface.ifa_name)
                if name == "en0" || name == "en1" {
                    var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                    getnameinfo(interface.ifa_addr, socklen_t(interface.ifa_addr.pointee.sa_len),
                                &hostname, socklen_t(hostname.count),
                                nil, socklen_t(0), NI_NUMERICHOST)
                    address = String(cString: hostname)
                    break
                }
            }
        }
        return address
    }

    func connect(to device: DiscoveredDevice) {
        print("🔗 Connecting to \(device.name)...")
        statusMessage = "Connecting to \(device.name)..."
        connectionManager.connect(to: device.endpoint)
    }

    func disconnect() {
        print("🔌 Disconnecting...")
        connectionManager.disconnect()
        connectedDeviceName = nil
        statusMessage = "Disconnected"
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
}

extension AppState: DiscoveryServiceDelegate {
    func discoveryService(_ service: DiscoveryService, didDiscover devices: [DiscoveredDevice]) {
        DispatchQueue.main.async {
            // Filter out self if discovered
            let myName = Host.current().localizedName ?? "MacBook"
            self.discoveredDevices = devices.filter { !$0.name.contains(myName) }

            // Automatic reconnection to trusted device if currently disconnected
            if case .disconnected = self.connectionState {
                if let trustedDevice = self.discoveredDevices.first(where: { $0.isPaired || TrustStore.shared.isTrusted(deviceId: $0.id) }) {
                    print("🔄 Discovered trusted device: \(trustedDevice.name), auto-connecting...")
                    self.connect(to: trustedDevice)
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
                let hello = ProtocolMessage.hello(version: 1, deviceId: CryptoService.shared.deviceId, deviceName: myName)
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
            let isTrusted = TrustStore.shared.isTrusted(deviceId: deviceId) ||
                            TrustStore.shared.getTrustedDevices().contains(where: { $0.deviceName == deviceName })

            if isTrusted {
                self.statusMessage = "Paired with \(deviceName) (Trusted) • Syncing"
                self.clipboardManager.direction = .bidirectional
                let ack = ProtocolMessage.helloAck(
                    version: 1,
                    deviceId: CryptoService.shared.deviceId,
                    deviceName: myName,
                    paired: true
                )
                self.connectionManager.send(message: ack)
                print("🤝 Hello received from trusted device \(deviceName) — sent HelloAck(paired: true)")
            } else {
                self.statusMessage = "Handshaking with \(deviceName)..."
                let ack = ProtocolMessage.helloAck(
                    version: 1,
                    deviceId: CryptoService.shared.deviceId,
                    deviceName: myName,
                    paired: false
                )
                self.connectionManager.send(message: ack)
                self.connectionManager.send(message: .pairRequest(publicKey: CryptoService.shared.publicKeyBase64))
                print("🔐 Hello received from untrusted device \(deviceName) — sent HelloAck(paired: false) & PairRequest")
            }

        case let .helloAck(_, deviceId, deviceName, paired):
            self.connectedDeviceName = deviceName
            if paired {
                self.statusMessage = "Connected to \(deviceName) • Syncing"
                self.clipboardManager.direction = .bidirectional
                if !TrustStore.shared.isTrusted(deviceId: deviceId) {
                    TrustStore.shared.addTrustedDevice(
                        TrustedDevice(deviceId: deviceId, deviceName: deviceName, publicKeyBase64: "", pairedAt: Date())
                    )
                }
                print("🤝 HelloAck(paired: true) received from \(deviceName)")
            } else {
                self.statusMessage = "Pairing with \(deviceName)..."
                self.connectionManager.send(message: .pairRequest(publicKey: CryptoService.shared.publicKeyBase64))
                print("🔐 HelloAck(paired: false) received from \(deviceName) — initiated PairRequest")
            }

        case let .pairRequest(publicKey):
            let peerName = self.connectedDeviceName ?? "Android Device"
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
            self.statusMessage = "Paired with \(peerName) (Trusted) • Auto Syncing"
            print("✅ Completed one-time pairing with \(peerName)")

        case let .pairResponse(accepted, publicKey):
            let peerName = self.connectedDeviceName ?? "Android Device"
            if accepted {
                self.pairingManager.initiatePairing(peerId: peerName, peerName: peerName, peerKeyBase64: publicKey)
                self.pairingManager.confirmPairing()
                self.pairingPin = nil
                self.clipboardManager.direction = .bidirectional
                self.statusMessage = "Paired with \(peerName) (Trusted) • Auto Syncing"
                print("✅ Completed one-time pairing response with \(peerName)")
            } else {
                self.statusMessage = "Pairing declined by \(peerName)"
                self.connectionManager.disconnect()
            }

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

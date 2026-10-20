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


class MacDroidAppDelegate: NSObject, NSApplicationDelegate, UNUserNotificationCenterDelegate, NSPopoverDelegate {
    private var statusItem: NSStatusItem!
    private var popover: NSPopover!
    private var globalEventMonitor: Any?
    private var localEventMonitor: Any?
    let appState = AppState()

    func applicationDidFinishLaunching(_ notification: Notification) {
        // Configure native system notifications for device pairing and continuity
        let center = UNUserNotificationCenter.current()
        center.delegate = self

        let acceptAction = UNNotificationAction(identifier: "ACCEPT_PAIRING", title: "Accept", options: [.foreground])
        let rejectAction = UNNotificationAction(identifier: "REJECT_PAIRING", title: "Reject", options: [.destructive])
        let category = UNNotificationCategory(identifier: "PAIRING_CATEGORY", actions: [acceptAction, rejectAction], intentIdentifiers: [], options: [])

        let continueAction = UNNotificationAction(identifier: "CONTINUE_ACTION", title: "Continue on Mac", options: [.foreground])
        let dismissAction = UNNotificationAction(identifier: "DISMISS_ACTION", title: "Dismiss", options: [.destructive])
        let continuityCategory = UNNotificationCategory(identifier: "CONTINUITY_CATEGORY", actions: [continueAction, dismissAction], intentIdentifiers: [], options: [])

        center.setNotificationCategories([category, continuityCategory])

        center.requestAuthorization(options: [.alert, .sound, .badge]) { granted, error in
            if granted {
                print("macOS system notification authorization granted")
            } else if let error = error {
                print("Notification auth error: \(error.localizedDescription)")
            }
        }

        // Create the status bar item
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)

        if let button = statusItem.button {
            updateStatusBarIcon()
            button.action = #selector(handleStatusBarClick(_:))
            button.target = self
            button.sendAction(on: [.leftMouseUp, .rightMouseUp])
        }

        appState.onMasterStateChanged = { [weak self] _ in
            DispatchQueue.main.async {
                self?.updateStatusBarIcon()
            }
        }

        // Create the popover that shows our SwiftUI view
        popover = NSPopover()
        popover.contentSize = NSSize(width: 360, height: 520)
        popover.behavior = .transient
        popover.delegate = self
        popover.contentViewController = NSHostingController(
            rootView: MenuBarView(appState: appState)
        )

        print("MacDroid is running in the menu bar!")
        print("   Click the antenna icon in your menu bar to open.")

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
        } else if response.actionIdentifier == "CONTINUE_ACTION" {
            DispatchQueue.main.async {
                _ = self.appState.continuityManager.continueIncomingActivity()
            }
        } else if response.actionIdentifier == "DISMISS_ACTION" {
            DispatchQueue.main.async {
                self.appState.continuityManager.dismissIncomingActivity()
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

    func updateStatusBarIcon() {
        guard let button = statusItem?.button else { return }
        let symbolName = appState.isEnabled ? "antenna.radiowaves.left.and.right" : "antenna.radiowaves.left.and.right.slash"
        let description = appState.isEnabled ? "DexoLink: On" : "DexoLink: Off"
        button.image = NSImage(systemSymbolName: symbolName, accessibilityDescription: description)
    }

    @objc func handleStatusBarClick(_ sender: NSStatusBarButton) {
        guard let event = NSApp.currentEvent else {
            togglePopover()
            return
        }

        if event.type == .rightMouseUp || event.modifierFlags.contains(.control) {
            showContextMenu(at: sender)
        } else {
            togglePopover()
        }
    }

    private func showContextMenu(at button: NSStatusBarButton) {
        if popover.isShown {
            popover.performClose(nil)
        }

        let menu = NSMenu()

        // Header Title
        let headerItem = NSMenuItem(title: "DexoLink", action: nil, keyEquivalent: "")
        headerItem.isEnabled = false
        menu.addItem(headerItem)

        // Toggle Switch Item
        let isEnabled = appState.isEnabled
        let toggleTitle = isEnabled ? "Turn DexoLink Off" : "Turn DexoLink On"
        let toggleItem = NSMenuItem(title: toggleTitle, action: #selector(toggleMasterFromMenu), keyEquivalent: "")
        toggleItem.target = self
        toggleItem.image = NSImage(systemSymbolName: isEnabled ? "antenna.radiowaves.left.and.right.slash" : "antenna.radiowaves.left.and.right", accessibilityDescription: nil)
        menu.addItem(toggleItem)

        menu.addItem(NSMenuItem.separator())

        // Quick Status Info
        let statusText: String
        if !isEnabled {
            statusText = "Status: Off (0% Battery & CPU)"
        } else if appState.connectionState.isConnected {
            statusText = "Connected to \(appState.connectedDeviceName ?? "Device")"
        } else if let paired = TrustStore.shared.getPairedDevice() {
            statusText = "Paired with \(paired.deviceName) (Searching...)"
        } else {
            statusText = "Active • Ready to pair"
        }
        let statusMenuItem = NSMenuItem(title: statusText, action: nil, keyEquivalent: "")
        statusMenuItem.isEnabled = false
        menu.addItem(statusMenuItem)

        menu.addItem(NSMenuItem.separator())

        // Open Popover
        let openItem = NSMenuItem(title: "Open DexoLink...", action: #selector(openPopoverFromMenu), keyEquivalent: "o")
        openItem.target = self
        menu.addItem(openItem)

        // Quit
        let quitItem = NSMenuItem(title: "Quit DexoLink", action: #selector(quitApp), keyEquivalent: "q")
        quitItem.target = self
        menu.addItem(quitItem)

        menu.popUp(positioning: nil, at: NSPoint(x: 0, y: button.bounds.height + 4), in: button)
    }

    @objc func toggleMasterFromMenu() {
        appState.toggleMasterSwitch()
    }

    @objc func openPopoverFromMenu() {
        if !popover.isShown, let button = statusItem.button {
            popover.show(relativeTo: button.bounds, of: button, preferredEdge: .minY)
            popover.contentViewController?.view.window?.makeKey()
            startDismissalMonitors()
        }
    }

    @objc func quitApp() {
        NSApplication.shared.terminate(nil)
    }

    @objc func togglePopover() {
        guard let button = statusItem.button else { return }

        if popover.isShown {
            popover.close()
            stopDismissalMonitors()
        } else {
            popover.show(relativeTo: button.bounds, of: button, preferredEdge: .minY)
            // Bring popover window to front
            popover.contentViewController?.view.window?.makeKey()
            startDismissalMonitors()
        }
    }

    // MARK: - NSPopoverDelegate & Outside Click Dismissal
    func popoverShouldClose(_ popover: NSPopover) -> Bool {
        return true
    }

    func popoverWillShow(_ notification: Notification) {
        startDismissalMonitors()
    }

    func popoverDidClose(_ notification: Notification) {
        stopDismissalMonitors()
    }

    private func startDismissalMonitors() {
        stopDismissalMonitors()

        // 1. Global monitor: Intercepts clicks on external apps, the desktop, or other menu bar items (Wi-Fi, Bluetooth)
        globalEventMonitor = NSEvent.addGlobalMonitorForEvents(matching: [.leftMouseDown, .rightMouseDown, .otherMouseDown]) { [weak self] _ in
            guard let self = self, self.popover.isShown else { return }
            DispatchQueue.main.async {
                if self.popover.isShown {
                    self.popover.close()
                }
            }
        }

        // 2. Local monitor: Intercepts clicks within our own process (e.g. outside popover window but on other elements)
        localEventMonitor = NSEvent.addLocalMonitorForEvents(matching: [.leftMouseDown, .rightMouseDown, .otherMouseDown]) { [weak self] event in
            guard let self = self, self.popover.isShown else { return event }

            // Allow interaction inside the popover window or any sheet window attached to it
            let popoverWindow = self.popover.contentViewController?.view.window
            if let pw = popoverWindow {
                if event.window == pw || event.window?.sheetParent == pw {
                    return event
                }
            }

            // Allow toggle clicks directly on the status bar icon button
            if let buttonWindow = self.statusItem.button?.window, event.window == buttonWindow {
                let pointInButton = self.statusItem.button?.convert(event.locationInWindow, from: nil) ?? .zero
                if self.statusItem.button?.bounds.contains(pointInButton) == true {
                    return event
                }
            }

            DispatchQueue.main.async {
                if self.popover.isShown {
                    self.popover.close()
                }
            }
            return event
        }
    }

    private func stopDismissalMonitors() {
        if let monitor = globalEventMonitor {
            NSEvent.removeMonitor(monitor)
            globalEventMonitor = nil
        }
        if let monitor = localEventMonitor {
            NSEvent.removeMonitor(monitor)
            localEventMonitor = nil
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
            if !self.appState.isEnabled {
                self.appState.setMasterEnabled(true)
            }
            ShareWindowController.present(appState: self.appState, urls: urls, text: text)
        }
    }

    // MARK: - Finder "Open With" / File Handlers
    func application(_ sender: NSApplication, openFiles filenames: [String]) {
        let urls = filenames.map { URL(fileURLWithPath: $0) }
        guard !urls.isEmpty else { return }
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            if !self.appState.isEnabled {
                self.appState.setMasterEnabled(true)
            }
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
                if !self.appState.isEnabled {
                    self.appState.setMasterEnabled(true)
                }
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
    let continuityManager = ContinuityManager.shared
    let streamManager = StreamManager.shared

    @Published var isEnabled: Bool
    @Published var isDiscovering = false
    @Published var isActivelyScanning = false
    @Published var discoveryEnabled = true
    @Published var discoveredDevices: [DiscoveredDevice] = []
    @Published var connectionState: ConnectionState = .disconnected
    @Published var connectedDeviceName: String? = nil
    @Published var connectedDeviceId: String? = nil
    @Published var connectedDeviceType: String? = nil
    @Published var pairingPin: String? = nil
    @Published var incomingPairingRequest: PairingRequestInfo? = nil
    @Published var lastPingLatencyMs: Int? = nil
    @Published var statusMessage: String = "Ready to connect"
    @Published var localIP: String = "Detecting..."
    @Published var lastSyncedClipboardSnippet: String? = nil
    @Published var activeTransfer: TransferItem? = nil
    @Published var recentTransfers: [TransferItem] = []
    @Published var clipboardHistory: [ClipboardHistoryItem] = []
    @Published var isClipboardSyncEnabled: Bool = true
    @Published var isEnhancedClipboardEnabled: Bool = false
    @Published var isMultiplePairedDevicesEnabled: Bool = false
    @Published var isCameraSharingEnabled: Bool = false
    @Published var isScreenSharingEnabled: Bool = false
    @Published var isDesktopModeEnabled: Bool = false
    @Published var selectedTargetDeviceId: String? = nil
    @Published var userExplicitlyDisconnected: Bool = false
    var onMasterStateChanged: ((Bool) -> Void)?

    var activeFeaturesCount: Int {
        var count = 0
        if continuityManager.isEnabled { count += 1 }
        if isClipboardSyncEnabled { count += 1 }
        if isEnhancedClipboardEnabled { count += 1 }
        if isMultiplePairedDevicesEnabled { count += 1 }
        if isCameraSharingEnabled { count += 1 }
        if isScreenSharingEnabled { count += 1 }
        if isDesktopModeEnabled { count += 1 }
        return count
    }

    private var pingStartTime: Date?
    private var activeScanTimer: Timer?
    private var backgroundAvailabilityTimer: Timer?
    private var heartbeatTimer: Timer?
    private var lastDataActivityTimestamp = Date()

    init() {
        let savedState = UserDefaults.standard.object(forKey: "dexolink_master_enabled") as? Bool ?? true
        self.isEnabled = savedState

        self.transferManager = TransferManager(connectionManager: connectionManager)
        self.localIP = AppState.getLocalIP() ?? "Unavailable"
        self.recentTransfers = self.transferManager.completedTransfers
        self.clipboardHistory = self.clipboardManager.history
        self.isClipboardSyncEnabled = UserDefaults.standard.object(forKey: "dexolink_clipboard_sync_enabled") as? Bool ?? true
        self.isEnhancedClipboardEnabled = self.clipboardManager.isEnhancedClipboardEnabled
        self.isMultiplePairedDevicesEnabled = UserDefaults.standard.bool(forKey: "dexolink_multiple_paired_devices_enabled")
        self.isCameraSharingEnabled = UserDefaults.standard.bool(forKey: "dexolink_camera_sharing_enabled")
        self.isScreenSharingEnabled = UserDefaults.standard.bool(forKey: "dexolink_screen_sharing_enabled")
        self.isDesktopModeEnabled = UserDefaults.standard.bool(forKey: "dexolink_desktop_mode_enabled")
        TrustStore.shared.isMultiplePairedDevicesEnabled = self.isMultiplePairedDevicesEnabled

        // Wire up delegates
        discoveryService.delegate = self
        connectionManager.delegate = self
        clipboardManager.setConnectionManager(connectionManager)
        clipboardManager.delegate = self
        continuityManager.setConnectionManager(connectionManager)
        streamManager.setConnectionManager(connectionManager)
        streamManager.isCameraSharingEnabled = self.isCameraSharingEnabled
        streamManager.isScreenSharingEnabled = self.isScreenSharingEnabled
        streamManager.isDesktopModeEnabled = self.isDesktopModeEnabled
        transferManager.delegate = self

        // Wire camera & screen streaming to native macOS viewers
        streamManager.onRemoteCameraFrameReceived = { [weak self] streamId, image, width, height in
            _ = self
            CameraStreamWindowController.shared.updateFrame(image, width: width, height: height)
        }
        streamManager.onRemoteScreenFrameReceived = { [weak self] streamId, image, width, height in
            _ = self
            ScreenStreamWindowController.shared.updateFrame(image, width: width, height: height)
        }
        streamManager.onRemoteFrameReceived = { [weak self] streamId, image, width, height in
            _ = self
            let type = self?.streamManager.getStreamType(streamId: streamId)
            if type == .screen || type == .desktopMode {
                ScreenStreamWindowController.shared.updateFrame(image, width: width, height: height)
            } else {
                CameraStreamWindowController.shared.updateFrame(image, width: width, height: height)
            }
        }
        streamManager.onRemoteStreamStarted = { [weak self] streamId, streamType in
            let devName = self?.connectedDeviceName ?? "Connected Device"
            if streamType == "camera" {
                CameraStreamWindowController.shared.showStream(streamId: streamId, deviceName: devName)
            } else if streamType == "screen" || streamType == "desktop_mode" {
                ScreenStreamWindowController.shared.showStream(streamId: streamId, deviceName: devName)
            }
        }
        streamManager.onRemoteStreamStopped = { _ in
            CameraStreamWindowController.shared.close()
            ScreenStreamWindowController.shared.close()
        }
        CameraStreamWindowController.shared.onClose = { [weak self] in
            guard let self = self else { return }
            let streamId = CameraStreamWindowController.shared.viewModel.streamId
            if !streamId.isEmpty {
                self.streamManager.stopStream(streamId: streamId)
                try? self.connectionManager.send(message: .streamStop(streamId: streamId, reason: "User closed camera viewer on Mac"))
            }
        }
        CameraStreamWindowController.shared.viewModel.onFlipCameraRequested = { [weak self] in
            guard let self = self else { return }
            let streamId = CameraStreamWindowController.shared.viewModel.streamId
            if !streamId.isEmpty {
                let msg = ProtocolMessage.streamRequest(
                    streamId: streamId,
                    streamType: "camera",
                    options: ["action": "switch_camera", "lens": "toggle"]
                )
                try? self.connectionManager.send(message: msg)
            }
        }
        ScreenStreamWindowController.shared.onClose = { [weak self] in
            guard let self = self else { return }
            let streamId = ScreenStreamWindowController.shared.viewModel.streamId
            if !streamId.isEmpty {
                self.streamManager.stopStream(streamId: streamId)
                try? self.connectionManager.send(message: .streamStop(streamId: streamId, reason: "User closed screen mirror viewer on Mac"))
            }
        }
        ScreenStreamWindowController.shared.viewModel.onSendInput = { [weak self] eventType, x, y, button, key in
            guard let self = self else { return }
            let streamId = ScreenStreamWindowController.shared.viewModel.streamId
            if !streamId.isEmpty {
                let msg = ProtocolMessage.desktopInputEvent(
                    streamId: streamId,
                    eventType: eventType,
                    x: x,
                    y: y,
                    button: button,
                    key: key
                )
                try? self.connectionManager.send(message: msg)
            }
        }

        if isEnabled {
            // Start advertiser listener
            discoveryService.startAdvertising(port: 52840)

            // Start discovery with an active scanning window
            startDiscovery(showActiveIndicator: true)

            // Periodic background availability refresh (every 60s with 10s tolerance to coalesce CPU wakeups)
            scheduleBackgroundAvailabilityTimer()

            print("Discovery started on \(localIP):\(discoveryService.listeningPort)...")
        } else {
            statusMessage = "DexoLink is Off"
            print("DexoLink initialized in OFF state — network services halted to conserve power")
        }
    }

    func toggleMasterSwitch() {
        setMasterEnabled(!isEnabled)
    }

    func setMasterEnabled(_ enabled: Bool) {
        guard isEnabled != enabled else { return }
        isEnabled = enabled
        UserDefaults.standard.set(enabled, forKey: "dexolink_master_enabled")

        if enabled {
            userExplicitlyDisconnected = false
            statusMessage = "Ready to connect"

            // 1. Restart Advertising / Listener
            discoveryService.startAdvertising(port: 52840)

            // 2. Resume UDP Beacons and start browsing if discoveryEnabled
            if discoveryEnabled {
                startDiscovery(showActiveIndicator: true)
                scheduleBackgroundAvailabilityTimer()
            }
            print("[DexoLink Master Toggle] Turned ON: Networking and discovery activated")
        } else {
            // Turning OFF: TRUE ZERO-COST IDLE STATE
            // 1. Disconnect active connection and stop heartbeat
            stopHeartbeat()
            clipboardManager.direction = .disabled
            transferManager.cancelAll()
            connectionManager.disconnect()
            connectedDeviceName = nil
            connectedDeviceId = nil
            pairingPin = nil
            incomingPairingRequest = nil
            continuityManager.incomingActivity = nil

            // 2. Stop Discovery & Browsing
            discoveryService.stopBrowsing()
            isDiscovering = false
            isActivelyScanning = false
            activeScanTimer?.invalidate()
            activeScanTimer = nil

            // 3. Stop Background availability timer
            stopBackgroundAvailabilityTimer()

            // 4. Stop Advertiser & Pause UDP Beacons
            discoveryService.stopAdvertising()
            UDPBeaconService.shared.pauseBroadcasting()

            // 5. Clear discovered devices
            discoveredDevices.removeAll()

            statusMessage = "DexoLink is Off"
            print("[DexoLink Master Toggle] Turned OFF: All network listeners, timers, and sockets halted")
        }

        onMasterStateChanged?(enabled)
    }

    private func scheduleBackgroundAvailabilityTimer() {
        backgroundAvailabilityTimer?.invalidate()
        backgroundAvailabilityTimer = Timer.scheduledTimer(withTimeInterval: 60.0, repeats: true) { [weak self] _ in
            guard let self = self, self.isEnabled, self.discoveryEnabled, !self.connectionState.isConnected else { return }
            self.discoveryService.refreshDeviceAvailability()
        }
        backgroundAvailabilityTimer?.tolerance = 10.0
    }

    private func stopBackgroundAvailabilityTimer() {
        backgroundAvailabilityTimer?.invalidate()
        backgroundAvailabilityTimer = nil
    }

    func startDiscovery(showActiveIndicator: Bool = true) {
        guard isEnabled, discoveryEnabled else { return }
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
        statusMessage = isEnabled ? "Discovery stopped" : "DexoLink is Off"
    }

    func setDiscoveryEnabled(_ enabled: Bool) {
        guard isEnabled else { return }
        discoveryEnabled = enabled
        if enabled {
            startDiscovery(showActiveIndicator: true)
            print("Discovery started — listening for devices & enabling pairing")
        } else {
            stopDiscovery()
            print("Discovery stopped — suppressing pairing notifications")
        }
    }

    func refreshDiscovery() {
        guard isEnabled else {
            setMasterEnabled(true)
            return
        }
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
        print("Manual discovery rescan initiated")
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
        // Optimized: 30s interval with 5s tolerance for CPU coalescing (was 15s/2s).
        // This lets the OS batch timer wakeups with other system timers,
        // dramatically reducing idle CPU usage from ~5% to <1%.
        heartbeatTimer = Timer.scheduledTimer(withTimeInterval: 30.0, repeats: true) { [weak self] _ in
            guard let self = self, self.connectionState.isConnected else { return }
            let idle = Date().timeIntervalSince(self.lastDataActivityTimestamp)
            if idle >= 90.0 {
                // Stale connection: no data or heartbeat for 90s (was 45s)
                print("No data or heartbeat received for 90s. Disconnecting stale connection...")
                self.connectionManager.disconnect()
            } else if idle >= 25.0 {
                // Only send PING if truly idle (25s threshold to account for tolerance jitter)
                self.pingStartTime = Date()
                self.connectionManager.send(message: .ping)
                print("Heartbeat PING sent from macOS (idle: \(Int(idle))s)")
            }
            // If idle < 25s, recent data activity means connection is alive — skip PING entirely
        }
        heartbeatTimer?.tolerance = 5.0  // 5s tolerance allows macOS to coalesce with other timers
    }

    func stopHeartbeat() {
        heartbeatTimer?.invalidate()
        heartbeatTimer = nil
    }

    func connect(to device: DiscoveredDevice) {
        guard isEnabled else {
            print("Cannot connect: DexoLink is turned OFF")
            return
        }
        if self.connectionState.isConnected {
            print("Already connected, skipping connect to \(device.name)")
            return
        }
        userExplicitlyDisconnected = false
        print("Connecting to \(device.name)...")
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
        print("Disconnecting...")
        stopHeartbeat()
        userExplicitlyDisconnected = true
        clipboardManager.direction = .disabled
        transferManager.cancelAll()
        connectionManager.disconnect()
        connectedDeviceName = nil
        connectedDeviceId = nil
        statusMessage = "Disconnected"
    }

    func setMultiplePairedDevicesEnabled(_ enabled: Bool) {
        guard isMultiplePairedDevicesEnabled != enabled else { return }
        isMultiplePairedDevicesEnabled = enabled
        UserDefaults.standard.set(enabled, forKey: "dexolink_multiple_paired_devices_enabled")
        TrustStore.shared.isMultiplePairedDevicesEnabled = enabled
        print("[macOS Multiple Paired Devices] Toggled to: \(enabled ? "ON" : "OFF")")
    }

    func toggleMultiplePairedDevices() {
        setMultiplePairedDevicesEnabled(!isMultiplePairedDevicesEnabled)
    }

    func setCameraSharingEnabled(_ enabled: Bool) {
        guard isCameraSharingEnabled != enabled else { return }
        if enabled {
            isCameraSharingEnabled = true
            streamManager.isCameraSharingEnabled = true
            streamManager.checkCameraPermission { [weak self] granted in
                if !granted {
                    self?.statusMessage = "Camera permission required for Camera Sharing. Please grant access in System Settings."
                }
            }
        } else {
            isCameraSharingEnabled = false
            streamManager.isCameraSharingEnabled = false
        }
        print("[macOS Camera Sharing] Toggled to: \(enabled ? "ON" : "OFF")")
    }

    func requestRemoteCameraStream() {
        guard isEnabled else { return }
        guard connectionState.isConnected else {
            statusMessage = "Connect to your device first"
            return
        }
        let streamId = UUID().uuidString
        let devName = connectedDeviceName ?? "Connected Device"
        statusMessage = "Requesting camera stream from \(devName)..."
        streamManager.isCameraSharingEnabled = true
        streamManager.registerActiveStream(streamId: streamId, type: .camera)
        CameraStreamWindowController.shared.showStream(streamId: streamId, deviceName: devName)
        let msg = ProtocolMessage.streamRequest(
            streamId: streamId,
            streamType: "camera",
            options: ["direction": "outgoing", "sender": "mac", "action": "request_stream"]
        )
        try? connectionManager.send(message: msg)
    }

    func startSharingMacCameraToRemote() {
        guard isEnabled else { return }
        guard connectionState.isConnected else {
            statusMessage = "Connect to your device first"
            return
        }
        let streamId = UUID().uuidString
        let devName = connectedDeviceName ?? "Connected Device"
        statusMessage = "Streaming Mac camera to \(devName)..."
        streamManager.isCameraSharingEnabled = true
        streamManager.registerActiveStream(streamId: streamId, type: .camera)
        _ = streamManager.handleIncomingStreamRequest(
            streamId: streamId,
            streamType: "camera",
            options: ["direction": "incoming"]
        )
        let myName = Host.current().localizedName ?? "MacBook"
        let msg = ProtocolMessage.streamRequest(
            streamId: streamId,
            streamType: "camera",
            options: ["direction": "outgoing", "sender": "mac", "senderName": myName, "deviceType": "laptop"]
        )
        try? connectionManager.send(message: msg)
    }

    func requestRemoteScreenStream() {
        guard isEnabled else { return }
        guard connectionState.isConnected else {
            statusMessage = "Connect to your device first"
            return
        }
        let streamId = UUID().uuidString
        let devName = connectedDeviceName ?? "Connected Device"
        statusMessage = "Requesting display mirror from \(devName)..."
        streamManager.isScreenSharingEnabled = true
        streamManager.registerActiveStream(streamId: streamId, type: .screen)
        ScreenStreamWindowController.shared.showStream(streamId: streamId, deviceName: devName)
        let msg = ProtocolMessage.streamRequest(
            streamId: streamId,
            streamType: "screen",
            options: ["direction": "outgoing", "sender": "mac", "action": "request_stream"]
        )
        try? connectionManager.send(message: msg)
    }

    func startSharingMacScreenToRemote() {
        guard isEnabled else { return }
        guard connectionState.isConnected else {
            statusMessage = "Connect to your device first"
            return
        }
        let streamId = UUID().uuidString
        let devName = connectedDeviceName ?? "Connected Device"
        statusMessage = "Mirroring Mac screen to \(devName)..."
        streamManager.isScreenSharingEnabled = true
        streamManager.registerActiveStream(streamId: streamId, type: .screen)
        _ = streamManager.handleIncomingStreamRequest(
            streamId: streamId,
            streamType: "screen",
            options: ["direction": "incoming"]
        )
        let myName = Host.current().localizedName ?? "MacBook"
        let msg = ProtocolMessage.streamRequest(
            streamId: streamId,
            streamType: "screen",
            options: ["direction": "outgoing", "sender": "mac", "senderName": myName, "deviceType": "laptop"]
        )
        try? connectionManager.send(message: msg)
    }

    func inferDeviceType(from name: String) -> String {
        let lower = name.lowercased()
        if lower.contains("iphone") { return "iphone" }
        if lower.contains("ipad") { return "ipad" }
        if lower.contains("windows") || lower.contains("pc") { return "windows" }
        if lower.contains("mac") || lower.contains("macbook") || lower.contains("imac") { return "mac" }
        return "android"
    }

    func setScreenSharingEnabled(_ enabled: Bool) {
        guard isScreenSharingEnabled != enabled else { return }
        if enabled {
            isScreenSharingEnabled = true
            streamManager.isScreenSharingEnabled = true
            let granted = streamManager.checkScreenCapturePermission()
            if !granted {
                statusMessage = "Screen Recording permission required for Screen Sharing."
            }
        } else {
            isScreenSharingEnabled = false
            streamManager.isScreenSharingEnabled = false
        }
        print("[macOS Screen Sharing] Toggled to: \(enabled ? "ON" : "OFF")")
    }

    func setDesktopModeEnabled(_ enabled: Bool) {
        guard isDesktopModeEnabled != enabled else { return }
        if enabled {
            isDesktopModeEnabled = true
            streamManager.isDesktopModeEnabled = true
            let granted = streamManager.checkAccessibilityPermission()
            if !granted {
                statusMessage = "Accessibility permission required for Desktop Mode input control."
            }
        } else {
            isDesktopModeEnabled = false
            streamManager.isDesktopModeEnabled = false
        }
        print("[macOS Desktop Mode (beta)] Toggled to: \(enabled ? "ON" : "OFF")")
    }

    func unpair(deviceId: String? = nil) {
        if let id = deviceId {
            print("Unpairing specific device id: \(id)...")
            if connectedDeviceId == id {
                stopHeartbeat()
                userExplicitlyDisconnected = true
                clipboardManager.direction = .disabled
                transferManager.cancelAll()
                connectionManager.send(message: .unpair)
                connectionManager.disconnect()
                connectedDeviceName = nil
                connectedDeviceId = nil
                pairingPin = nil
                statusMessage = "Unpaired from device"
            }
            TrustStore.shared.unpairDevice(deviceId: id)
        } else {
            let pairedName = TrustStore.shared.getPairedDevice()?.deviceName ?? (connectedDeviceName ?? "Device")
            print("Unpairing from \(pairedName)...")
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
    }

    func sendPing() {
        pingStartTime = Date()
        connectionManager.send(message: .ping)
        print("Ping sent to peer")
    }

    func sendClipboard() {
        let pasteboard = NSPasteboard.general
        guard let rawText = pasteboard.string(forType: .string), !rawText.isEmpty else {
            statusMessage = "Mac clipboard is empty"
            return
        }
        let text = isEnhancedClipboardEnabled ? EnhancedClipboardHelper.sanitizePlainText(rawText) : rawText
        let seq = Int(Date().timeIntervalSince1970) % 100000
        connectionManager.send(message: .clipboardPush(sequenceNumber: seq, content: text))
        statusMessage = "Clipboard sent to \(connectedDeviceName ?? "Android")"
        print("Clipboard pushed: \(text.prefix(30))...")
    }

    func connectDirect(host: String, port: Int) {
        guard isEnabled else {
            print("Cannot direct connect: DexoLink is turned OFF")
            return
        }
        if self.connectionState.isConnected {
            print("Already connected, skipping direct connect")
            return
        }
        userExplicitlyDisconnected = false
        print("Connecting directly to \(host):\(port)...")
        statusMessage = "Connecting to \(host):\(port)..."
        connectionManager.connect(to: .hostPort(host: .init(host), port: .init(integerLiteral: UInt16(port))))
    }

    func sendFile(url: URL) {
        transferManager.sendFile(url: url)
    }

    func cancelTransfer(id: String) {
        transferManager.cancelTransfer(id: id)
        if activeTransfer?.id == id {
            activeTransfer = nil
        }
        recentTransfers = transferManager.completedTransfers
    }

    func pauseTransfer(id: String) {
        transferManager.pauseTransfer(id: id)
    }

    func unpauseTransfer(id: String) {
        transferManager.unpauseTransfer(id: id)
    }

    func resumeTransfer(id: String) {
        transferManager.resumeTransfer(id: id)
    }

    // MARK: - Transfer History Deletion (Safe: files and pairing preserved)
    func deleteTransferHistoryItem(id: String) {
        transferManager.deleteCompletedTransfer(id: id)
        recentTransfers = transferManager.completedTransfers
    }

    func deleteTransferHistoryItems(ids: Set<String>) {
        transferManager.deleteCompletedTransfers(ids: ids)
        recentTransfers = transferManager.completedTransfers
    }

    func clearTransferHistory() {
        transferManager.clearCompletedTransfers()
        recentTransfers.removeAll()
    }

    // MARK: - Clipboard History Deletion (Safe: files and pairing preserved)
    func deleteClipboardHistoryItem(id: String) {
        clipboardManager.deleteItem(id: id)
        clipboardHistory = clipboardManager.history
    }

    func deleteClipboardHistoryItems(ids: Set<String>) {
        clipboardManager.deleteItems(ids: ids)
        clipboardHistory = clipboardManager.history
    }

    func clearClipboardHistory() {
        clipboardManager.clearHistory()
        clipboardHistory.removeAll()
    }

    func setEnhancedClipboardEnabled(_ enabled: Bool) {
        clipboardManager.isEnhancedClipboardEnabled = enabled
        isEnhancedClipboardEnabled = enabled
        print("[macOS Enhanced Clipboard] Toggled to: \(enabled ? "ON" : "OFF")")
    }

    func applyClipboardSyncDirection() {
        if case .connected = connectionState, isClipboardSyncEnabled {
            clipboardManager.direction = .bidirectional
        } else {
            clipboardManager.direction = .disabled
        }
    }

    func setClipboardSyncEnabled(_ enabled: Bool) {
        guard isClipboardSyncEnabled != enabled else { return }
        isClipboardSyncEnabled = enabled
        UserDefaults.standard.set(enabled, forKey: "dexolink_clipboard_sync_enabled")
        applyClipboardSyncDirection()
        print("[macOS Auto Clipboard Sync] Toggled to: \(enabled ? "ON" : "OFF")")
    }

    func toggleClipboardSync() {
        setClipboardSyncEnabled(!isClipboardSyncEnabled)
    }

    func removeDiscoveredDevice(id: String) {
        discoveryService.removeDevice(id: id)
        discoveredDevices.removeAll(where: { $0.id == id })
    }

    func acceptIncomingPairing() {
        guard let req = incomingPairingRequest else { return }
        let peerName = req.peerName
        self.connectionManager.send(message: .pairResponse(accepted: true, publicKey: CryptoService.shared.publicKeyBase64))
        self.pairingManager.confirmPairing()
        self.incomingPairingRequest = nil
        self.pairingPin = nil
        self.applyClipboardSyncDirection()
        self.statusMessage = "Connected to \(peerName) (Paired)"
        
        // Automatically stop discovery and scanning on both devices once pairing succeeds!
        self.discoveryService.stopBrowsing()
        self.isActivelyScanning = false
        print("Accepted pairing with \(peerName) — stopped discovery & scanning")
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
        print("Rejected pairing with \(peerName) — continuing discovery if enabled")
    }
}

extension AppState: DiscoveryServiceDelegate {
    func discoveryService(_ service: DiscoveryService, didDiscover devices: [DiscoveredDevice]) {
        guard self.isEnabled else { return }
        DispatchQueue.main.async {
            // Filter out self if discovered
            let myName = Host.current().localizedName ?? "MacBook"
            let myId = CryptoService.shared.deviceId
            self.discoveredDevices = devices.filter { dev in
                if dev.id == myId { return false }
                if dev.name.trimmingCharacters(in: .whitespacesAndNewlines).caseInsensitiveCompare(myName.trimmingCharacters(in: .whitespacesAndNewlines)) == .orderedSame {
                    return false
                }
                return true
            }

            // Strict 1-device policy: only auto-connect if master is enabled, discovery is enabled, NOT explicitly disconnected, currently disconnected, and device is online!
            if self.isEnabled, self.discoveryEnabled, !self.userExplicitlyDisconnected, case .disconnected = self.connectionState {
                if let paired = TrustStore.shared.getPairedDevice(),
                   let matched = self.discoveredDevices.first(where: { ($0.id == paired.deviceId || $0.name.caseInsensitiveCompare(paired.deviceName) == .orderedSame) && $0.isOnline }) {
                    print("Discovered paired online device: \(matched.name), auto-connecting...")
                    self.connect(to: matched)
                }
            }
        }
    }

    func discoveryService(_ service: DiscoveryService, didReceiveIncomingConnection connection: NWConnection) {
        guard self.isEnabled else {
            print("Rejecting incoming connection because DexoLink is turned OFF")
            connection.cancel()
            return
        }
        if self.userExplicitlyDisconnected {
            print("Rejecting incoming connection because user explicitly disconnected")
            connection.cancel()
            return
        }
        if self.connectionState.isConnected {
            print("Ignoring redundant incoming connection from \(connection.endpoint) because active session is already connected")
            connection.cancel()
            return
        }
        print("Accepting incoming connection from: \(connection.endpoint)")
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
                self.applyClipboardSyncDirection()
            case .authenticated:
                self.startHeartbeat()
                self.applyClipboardSyncDirection()
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
                self.statusMessage = self.isEnabled ? "Disconnected" : "DexoLink is Off"
                self.clipboardManager.direction = .disabled
                // If discovery is enabled and master enabled, resume beacons and browsing
                if self.isEnabled && self.discoveryEnabled {
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
                self.statusMessage = self.isEnabled ? "Connection failed: \(err.localizedDescription)" : "DexoLink is Off"
                self.clipboardManager.direction = .disabled
                if self.isEnabled && self.discoveryEnabled {
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
            self.connectedDeviceType = self.discoveredDevices.first(where: { $0.id == deviceId })?.deviceType ?? self.inferDeviceType(from: deviceName)
            let myName = Host.current().localizedName ?? "MacBook"
            let isTrusted = TrustStore.shared.isPaired(deviceId: deviceId, deviceName: deviceName)

            if isTrusted {
                self.statusMessage = "Paired with \(deviceName) (Trusted) • Syncing"
                self.applyClipboardSyncDirection()
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
                print("Hello received from paired device \(deviceName) — sent HelloAck(paired: true)")
            } else {
                self.statusMessage = "Handshaking with \(deviceName)..."
                let ack = ProtocolMessage.helloAck(
                    version: 2,
                    deviceId: CryptoService.shared.deviceId,
                    deviceName: myName,
                    paired: false
                )
                self.connectionManager.send(message: ack)
                print("Sent HelloAck(paired: false) to \(deviceName)")
            }

        case let .helloAck(_, deviceId, deviceName, paired):
            self.connectedDeviceName = deviceName
            self.connectedDeviceId = deviceId
            self.connectedDeviceType = self.discoveredDevices.first(where: { $0.id == deviceId })?.deviceType ?? self.inferDeviceType(from: deviceName)
            if paired {
                self.statusMessage = "Connected to \(deviceName) • Syncing"
                self.applyClipboardSyncDirection()
                if !TrustStore.shared.isPaired(deviceId: deviceId, deviceName: deviceName) {
                    TrustStore.shared.setPairedDevice(
                        TrustedDevice(deviceId: deviceId, deviceName: deviceName, publicKeyBase64: "", pairedAt: Date())
                    )
                }
                self.discoveryService.stopBrowsing()
                self.isActivelyScanning = false
                print("HelloAck(paired: true) received from \(deviceName)")
            } else {
                if !self.connectionManager.isIncoming {
                    if !self.discoveryEnabled {
                        print("Discovery is stopped: Suppressing pair request to \(deviceName)")
                    } else if self.isMultiplePairedDevicesEnabled || !TrustStore.shared.hasPairedDevice() {
                        self.statusMessage = "Pairing request sent to \(deviceName)..."
                        self.connectionManager.send(message: .pairRequest(publicKey: CryptoService.shared.publicKeyBase64))
                        print("HelloAck(paired: false) received from \(deviceName) — Mac initiated PairRequest")
                    } else {
                        print("Cannot pair with \(deviceName): Already paired with \(TrustStore.shared.getPairedDevice()?.deviceName ?? "") (Multiple Paired Devices is OFF)")
                    }
                } else {
                    print("Received HelloAck as receiver from \(deviceName) — awaiting incoming PairRequest")
                }
            }

        case let .pairRequest(publicKey):
            let peerName = self.connectedDeviceName ?? "Device"
            let peerId = self.connectedDeviceId ?? peerName

            // 1. If discovery is stopped, suppress notifications and reject:
            if !self.discoveryEnabled {
                print("Discovery is stopped: Suppressing pairing request and rejecting connection from \(peerName)")
                self.connectionManager.send(message: .pairResponse(
                    accepted: false,
                    publicKey: "",
                    reason: "Discovery is currently disabled on this Mac."
                ))
                self.statusMessage = "Rejected pairing request (Discovery Stopped)"
                return
            }

            // 2. Strict 1-device policy: check if Mac is already paired with another device
            if !self.isMultiplePairedDevicesEnabled, let paired = TrustStore.shared.getPairedDevice(), paired.deviceName != peerName && paired.deviceId != peerId {
                print("Pairing request from \(peerName) rejected: Mac already paired with \(paired.deviceName) (Multiple Paired Devices is OFF)")
                self.connectionManager.send(message: .pairResponse(
                    accepted: false,
                    publicKey: "",
                    reason: "Mac is already paired with '\(paired.deviceName)'. In accordance with DexoLink's strict one-device policy, please unpair first or enable Multiple Paired Devices in Settings."
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
            print("Sent pairing notification with Accept/Reject for \(peerName)")

        case let .pairResponse(accepted, publicKey, reason):
            let peerName = self.connectedDeviceName ?? "Device"
            let peerId = self.connectedDeviceId ?? peerName
            if accepted {
                self.pairingManager.initiatePairing(peerId: peerId, peerName: peerName, peerKeyBase64: publicKey)
                self.pairingManager.confirmPairing()
                self.pairingPin = nil
                self.applyClipboardSyncDirection()
                self.statusMessage = "Connected to \(peerName) (Paired)"
                
                // Automatically stop discovery and scanning on both devices once pairing succeeds!
                self.discoveryService.stopBrowsing()
                self.isActivelyScanning = false
                print("Completed pairing with \(peerName) — stopped scanning")
            } else {
                let msg = reason ?? "Pairing declined by \(peerName)"
                self.statusMessage = msg
                print("Pairing declined by \(peerName): \(msg)")
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
            print("Peer \(peer) requested unpair")
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
                    print("Pong received in \(latency)ms")
                }

            case let .clipboardMode(mode):
                print("[macOS Clipboard Mode] Received sync mode change from peer: \(mode)")
                switch mode {
                case "android_to_mac":
                    self.clipboardManager.direction = .receiveOnly
                    self.statusMessage = "Mode: Android → Mac (Receive Only)"
                case "mac_to_android":
                    self.clipboardManager.direction = .sendOnly
                    self.statusMessage = "Mode: Mac → Android (Send Only)"
                default:
                    self.clipboardManager.direction = self.isClipboardSyncEnabled ? .bidirectional : .disabled
                    self.statusMessage = "Mode: Two-Way Sync Active"
                }

            case .clipboardPush, .clipboardAck:
                self.clipboardManager.handleMessage(message)

            case .continuityActivity, .continuityAck, .continuityDismiss, .continuityState:
                self.continuityManager.handleMessage(message)

            case let .streamFrame(streamId, sequence, format, width, height, data):
                self.streamManager.handleIncomingStreamFrame(streamId: streamId, sequence: sequence, format: format, width: width, height: height, data: data)

            case let .streamResponse(streamId, streamType, accepted, reason):
                let devName = self.connectedDeviceName ?? "Connected Device"
                if accepted {
                    if streamType == "camera" {
                        CameraStreamWindowController.shared.showStream(streamId: streamId, deviceName: devName)
                        self.statusMessage = "Streaming camera from \(devName)"
                    } else if streamType == "screen" || streamType == "desktop_mode" {
                        ScreenStreamWindowController.shared.showStream(streamId: streamId, deviceName: devName)
                        self.statusMessage = "Screen mirroring with \(devName)"
                    }
                } else {
                    self.statusMessage = "\(streamType.capitalized) stream rejected: \(reason ?? "unknown error")"
                }

            case let .streamRequest(streamId, streamType, options):
                let resp = self.streamManager.handleIncomingStreamRequest(streamId: streamId, streamType: streamType, options: options)
                try? self.connectionManager.send(message: resp)
                let devName = self.connectedDeviceName ?? "Connected Device"
                let isRemoteFeed = options?["direction"] == "outgoing" || options?["sender"] == "android" || options?["sender"] == "windows"
                if isRemoteFeed {
                    if streamType == "camera" {
                        CameraStreamWindowController.shared.showStream(streamId: streamId, deviceName: devName)
                    } else if streamType == "screen" || streamType == "desktop_mode" {
                        ScreenStreamWindowController.shared.showStream(streamId: streamId, deviceName: devName)
                    }
                }

            case let .desktopInputEvent(streamId, eventType, x, y, button, key):
                self.streamManager.handleDesktopInput(streamId: streamId, eventType: eventType, x: x, y: y, button: button, key: key)

            case let .streamStop(streamId, _):
                self.streamManager.stopStream(streamId: streamId)
                CameraStreamWindowController.shared.close()
                ScreenStreamWindowController.shared.close()

            case let .webrtcSignal(streamId, streamType, signalType, sdp, candidate, sdpMid, sdpMLineIndex, options):
                WebRtcSignalingManager.shared.handleIncomingSignal(
                    streamId: streamId,
                    streamType: streamType,
                    signalType: signalType,
                    sdp: sdp,
                    candidate: candidate,
                    sdpMid: sdpMid,
                    sdpMLineIndex: sdpMLineIndex,
                    options: options,
                    connectionManager: self.connectionManager
                )

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
            print("Auto-synced clipboard from Android: \"\(text.prefix(35))...\"")
        }
    }

    func clipboardManager(_ manager: ClipboardManager, didSendText text: String) {
        DispatchQueue.main.async {
            self.lastSyncedClipboardSnippet = text
            self.statusMessage = "Synced to \(self.connectedDeviceName ?? "Android")"
            self.clipboardHistory = manager.history
            print("Auto-synced clipboard to Android: \"\(text.prefix(35))...\"")
        }
    }

    func clipboardManagerDidUpdateHistory(_ manager: ClipboardManager) {
        DispatchQueue.main.async {
            self.clipboardHistory = manager.history
        }
    }
}

extension AppState: TransferManagerDelegate {
    func transferManager(_ manager: TransferManager, didUpdateItem item: TransferItem) {
        DispatchQueue.main.async {
            switch item.state {
            case .pending, .transferring, .paused, .verifying:
                self.activeTransfer = item
            case .completed, .failed, .cancelled:
                self.activeTransfer = nil
                self.recentTransfers = manager.completedTransfers
            }
        }
    }

    func transferManager(_ manager: TransferManager, didCompleteItem item: TransferItem) {
        DispatchQueue.main.async {
            self.activeTransfer = nil
            self.recentTransfers = manager.completedTransfers
            self.statusMessage = item.isOutgoing ? "Sent: \(item.name)" : "Received: \(item.name)"
        }
    }

    func transferManager(_ manager: TransferManager, didFailItem item: TransferItem, error: String) {
        DispatchQueue.main.async {
            self.activeTransfer = nil
            self.recentTransfers = manager.completedTransfers
            self.statusMessage = "Transfer failed: \(error)"
        }
    }
}

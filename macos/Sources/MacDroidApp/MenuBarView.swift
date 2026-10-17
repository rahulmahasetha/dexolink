import SwiftUI
import Networking
import Crypto
import Core

struct MenuBarView: View {
    @ObservedObject var appState: AppState
    @State private var showManualIPSheet = false
    @State private var manualIP = ""
    @State private var manualPort = "52840"
    @State private var isTargetedForDrop = false

    var body: some View {
        VStack(spacing: 0) {
            // MARK: - Header
            HStack(spacing: 8) {
                Image(systemName: "link.circle.fill")
                    .foregroundColor(.cyan)
                    .font(.title3)

                Text("DexoLink")
                    .font(.headline)
                    .fontWeight(.bold)

                Text("v2")
                    .font(.system(size: 10, weight: .bold))
                    .foregroundColor(.white)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(Color.purple)
                    .cornerRadius(6)

                Spacer()

                Button(action: {
                    showManualIPSheet.toggle()
                }) {
                    Image(systemName: "link")
                        .foregroundColor(.secondary)
                }
                .buttonStyle(.plain)
                .help("Direct IP Connection (Optional Fallback)")

                Button(action: {
                    NSApplication.shared.terminate(nil)
                }) {
                    Image(systemName: "power")
                        .foregroundColor(.secondary)
                }
                .buttonStyle(.plain)
                .help("Quit DexoLink")
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(Color(nsColor: .windowBackgroundColor))

            Divider()

            ScrollView {
                VStack(spacing: 10) {
                    // MARK: - Strict 1-Device Pairing Status Card
                    if let paired = TrustStore.shared.getPairedDevice() {
                        HStack(spacing: 10) {
                            Image(systemName: isConnected ? "checkmark.seal.fill" : "lock.shield.fill")
                                .foregroundColor(isConnected ? .green : .blue)
                                .font(.title2)

                            VStack(alignment: .leading, spacing: 2) {
                                HStack(spacing: 6) {
                                    Text(paired.deviceName)
                                        .font(.subheadline)
                                        .fontWeight(.bold)
                                    Text(isConnected ? "Connected" : "★ Paired")
                                        .font(.system(size: 9, weight: .bold))
                                        .foregroundColor(isConnected ? .green : .cyan)
                                        .padding(.horizontal, 5)
                                        .padding(.vertical, 1)
                                        .background((isConnected ? Color.green : Color.cyan).opacity(0.18))
                                        .cornerRadius(4)
                                }
                                Text("Strict 1-Device Policy • Keychain Protected")
                                    .font(.caption2)
                                    .foregroundColor(.secondary)
                            }

                            Spacer()

                            Button("Unpair") {
                                appState.unpair()
                            }
                            .buttonStyle(.bordered)
                            .controlSize(.small)
                            .tint(.red)
                            .help("Unpair to allow pairing another device")
                        }
                        .padding(10)
                        .background(Color.green.opacity(0.08))
                        .cornerRadius(10)
                    }

                    // MARK: - Pairing PIN Banner if Active
                    if let pin = appState.pairingPin {
                        HStack(spacing: 10) {
                            Image(systemName: "key.fill")
                                .foregroundColor(.purple)
                                .font(.title3)
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Pairing PIN: \(pin)")
                                    .font(.subheadline)
                                    .fontWeight(.bold)
                                    .foregroundColor(.purple)
                                Text("Confirm this 6-digit code on the requesting device")
                                    .font(.caption2)
                                    .foregroundColor(.secondary)
                            }
                            Spacer()
                        }
                        .padding(10)
                        .background(Color.purple.opacity(0.12))
                        .cornerRadius(10)
                    }

                    // MARK: - Quick File Sharing Card
                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            Image(systemName: "folder.fill.badge.gearshape")
                                .foregroundColor(.orange)
                            Text("Quick File Sharing")
                                .font(.subheadline)
                                .fontWeight(.bold)
                            Spacer()

                            Button(action: {
                                NSWorkspace.shared.open(appState.transferManager.downloadDirectory)
                            }) {
                                HStack(spacing: 4) {
                                    Image(systemName: "arrow.down.circle")
                                    Text("Downloads")
                                }
                                .font(.caption2)
                            }
                            .buttonStyle(.bordered)
                            .controlSize(.mini)
                            .help("Open DexoLink Downloads folder in Finder")
                        }

                        // Drag & Drop / Select Box
                        ZStack {
                            RoundedRectangle(cornerRadius: 10)
                                .stroke(isTargetedForDrop ? Color.accentColor : Color.secondary.opacity(0.3), style: StrokeStyle(lineWidth: 1.5, dash: [5]))
                                .background(Color.accentColor.opacity(isTargetedForDrop ? 0.12 : 0.04))
                                .cornerRadius(10)

                            VStack(spacing: 6) {
                                Image(systemName: "arrow.up.circle.fill")
                                    .font(.system(size: 26))
                                    .foregroundColor(.accentColor)

                                Text(isConnected ? "Drop files here or click to send" : "Connect a device to send files")
                                    .font(.caption)
                                    .fontWeight(.medium)

                                Text("Photos • PDFs • Documents • Downloads • Any file")
                                    .font(.system(size: 10))
                                    .foregroundColor(.secondary)

                                if isConnected {
                                    Button("Choose File...") {
                                        selectAndSendFile()
                                    }
                                    .buttonStyle(.borderedProminent)
                                    .controlSize(.small)
                                    .padding(.top, 2)
                                }
                            }
                            .padding(.vertical, 12)
                        }
                        .onDrop(of: [.fileURL], isTargeted: $isTargetedForDrop) { providers in
                            handleFileDrop(providers: providers)
                        }

                        // Active Transfer Progress
                        if let transfer = appState.activeTransfer {
                            VStack(alignment: .leading, spacing: 4) {
                                HStack {
                                    Image(systemName: transfer.isOutgoing ? "arrow.up.circle" : "arrow.down.circle")
                                        .foregroundColor(transfer.isOutgoing ? .cyan : .green)
                                    Text(transfer.name)
                                        .font(.caption)
                                        .fontWeight(.semibold)
                                        .lineLimit(1)
                                    Spacer()
                                    Button(action: {
                                        appState.cancelTransfer(id: transfer.id)
                                    }) {
                                        Image(systemName: "xmark.circle.fill")
                                            .foregroundColor(.secondary)
                                    }
                                    .buttonStyle(.plain)
                                }

                                ProgressView(value: transfer.progress)
                                    .progressViewStyle(.linear)

                                HStack {
                                    Text("\(formatBytes(transfer.bytesTransferred)) of \(formatBytes(transfer.size))")
                                        .font(.caption2)
                                        .foregroundColor(.secondary)
                                    Spacer()
                                    if transfer.speedBytesPerSec > 0 {
                                        Text("\(formatBytes(Int64(transfer.speedBytesPerSec)))/s")
                                            .font(.caption2)
                                            .foregroundColor(.secondary)
                                    }
                                    Text("\(Int(transfer.progress * 100))%")
                                        .font(.caption2)
                                        .fontWeight(.bold)
                                }
                            }
                            .padding(8)
                            .background(Color.secondary.opacity(0.08))
                            .cornerRadius(8)
                        }

                        // Recent Completed Transfers
                        if !appState.recentTransfers.isEmpty {
                            VStack(alignment: .leading, spacing: 4) {
                                Text("Recent Transfers")
                                    .font(.system(size: 11, weight: .semibold))
                                    .foregroundColor(.secondary)

                                ForEach(appState.recentTransfers.prefix(2)) { item in
                                    HStack {
                                        Image(systemName: item.state == .completed ? "checkmark.circle.fill" : "exclamationmark.circle.fill")
                                            .foregroundColor(item.state == .completed ? .green : .red)
                                            .font(.caption)
                                        Text(item.name)
                                            .font(.caption2)
                                            .lineLimit(1)
                                        Spacer()
                                        Text(formatBytes(item.size))
                                            .font(.caption2)
                                            .foregroundColor(.secondary)
                                        if let url = item.localURL {
                                            Button("Show") {
                                                NSWorkspace.shared.activateFileViewerSelecting([url])
                                            }
                                            .buttonStyle(.borderless)
                                            .font(.caption2)
                                        }
                                    }
                                }
                            }
                            .padding(.top, 4)
                        }
                    }
                    .padding(10)
                    .background(Color(nsColor: .controlBackgroundColor).opacity(0.6))
                    .cornerRadius(10)

                    // MARK: - Clipboard Sync Mode Card
                    if isConnected {
                        HStack(spacing: 8) {
                            Image(systemName: "arrow.triangle.2.circlepath")
                                .foregroundColor(.green)
                                .font(.title3)

                            VStack(alignment: .leading, spacing: 2) {
                                Text("Auto Clipboard Sync Active")
                                    .font(.caption)
                                    .fontWeight(.semibold)
                                if let snippet = appState.lastSyncedClipboardSnippet {
                                    Text("Last synced: \"\(snippet.prefix(28))...\"")
                                        .font(.caption2)
                                        .foregroundColor(.secondary)
                                        .lineLimit(1)
                                } else {
                                    Text("Copy on either device to sync instantly")
                                        .font(.caption2)
                                        .foregroundColor(.secondary)
                                }
                            }

                            Spacer()

                            Button(action: {
                                appState.sendPing()
                            }) {
                                HStack(spacing: 3) {
                                    Image(systemName: "bolt.fill")
                                    if let latency = appState.lastPingLatencyMs {
                                        Text("\(latency)ms")
                                            .font(.system(size: 10, weight: .bold))
                                    }
                                }
                            }
                            .buttonStyle(.bordered)
                            .controlSize(.small)
                            .help(appState.lastPingLatencyMs != nil ? "Ping Latency: \(appState.lastPingLatencyMs!)ms • Click to test again" : "Test Ping Latency")
                        }
                        .padding(10)
                        .background(Color.green.opacity(0.10))
                        .cornerRadius(10)
                    }

                    // MARK: - Nearby Discovered Devices
                    VStack(alignment: .leading, spacing: 6) {
                        HStack {
                            Text("Nearby Devices (Wi-Fi)")
                                .font(.caption)
                                .fontWeight(.semibold)
                                .foregroundColor(.secondary)

                            if appState.isActivelyScanning {
                                ProgressView()
                                    .scaleEffect(0.6)
                            }

                            Spacer()

                            // Start / Stop Discovery Control
                            Button(action: {
                                appState.setDiscoveryEnabled(!appState.discoveryEnabled)
                            }) {
                                HStack(spacing: 3) {
                                    Circle()
                                        .fill(appState.discoveryEnabled ? Color.green : Color.red)
                                        .frame(width: 6, height: 6)
                                    Text(appState.discoveryEnabled ? "Stop" : "Start")
                                        .font(.system(size: 10, weight: .bold))
                                }
                            }
                            .buttonStyle(.bordered)
                            .controlSize(.mini)
                            .help(appState.discoveryEnabled ? "Stop discovery & suppress pairing notifications" : "Start discovery & enable pairing notifications")

                            // Manual Rescan Button
                            Button(action: {
                                appState.refreshDiscovery()
                            }) {
                                Image(systemName: "arrow.clockwise")
                                    .font(.system(size: 10))
                                    .foregroundColor(appState.discoveryEnabled ? .accentColor : .secondary)
                            }
                            .buttonStyle(.plain)
                            .disabled(!appState.discoveryEnabled)
                            .help("Rescan nearby devices now")
                        }

                        if appState.discoveredDevices.isEmpty {
                            VStack(spacing: 6) {
                                Image(systemName: appState.discoveryEnabled ? "antenna.radiowaves.left.and.right" : "eye.slash")
                                    .font(.title3)
                                    .foregroundColor(.secondary)
                                Text(appState.discoveryEnabled ? (appState.isActivelyScanning ? "Searching on local Wi-Fi..." : "No devices found nearby") : "Discovery paused")
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                                Text(appState.discoveryEnabled ? "Open DexoLink on your iPhone or Android phone on the same Wi-Fi." : "Click 'Start' above to resume Wi-Fi discovery & pairing.")
                                    .font(.caption2)
                                    .foregroundColor(.secondary.opacity(0.8))
                                    .multilineTextAlignment(.center)
                            }
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 12)
                        } else {
                            VStack(spacing: 4) {
                                ForEach(appState.discoveredDevices) { device in
                                    DeviceRow(device: device, appState: appState)
                                }
                            }
                        }
                    }
                    .padding(10)
                    .background(Color(nsColor: .controlBackgroundColor).opacity(0.4))
                    .cornerRadius(10)
                }
                .padding(10)
            }

            Divider()

            // MARK: - Footer
            HStack {
                Circle()
                    .fill(statusColor)
                    .frame(width: 8, height: 8)

                Text(footerStatusText)
                    .font(.caption2)
                    .foregroundColor(.secondary)

                Spacer()

                Button("Refresh") {
                    appState.refreshDiscovery()
                }
                .buttonStyle(.plain)
                .font(.caption2)
                .foregroundColor(.accentColor)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(Color(nsColor: .windowBackgroundColor))
        }
        .frame(width: 340, height: 500)
        .sheet(isPresented: $showManualIPSheet) {
            ManualIPSheet(
                isPresented: $showManualIPSheet,
                ip: $manualIP,
                port: $manualPort,
                onConnect: { host, port in
                    appState.connectDirect(host: host, port: port)
                }
            )
        }
    }

    private var isConnected: Bool {
        switch appState.connectionState {
        case .connected, .authenticated, .transferring:
            return true
        default:
            return false
        }
    }

    private var statusColor: Color {
        switch appState.connectionState {
        case .connected, .authenticated, .transferring:
            return .green
        case .connecting:
            return .orange
        case .disconnected:
            return .gray
        case .failed:
            return .red
        }
    }

    private var footerStatusText: String {
        switch appState.connectionState {
        case .connected, .authenticated, .transferring:
            return "Connected to \(appState.connectedDeviceName ?? "Device")"
        case .connecting:
            return "Connecting..."
        case .disconnected:
            return appState.discoveryEnabled ? "Ready to connect • Wi-Fi Auto-Discovery" : "Discovery paused • Foreground link ready"
        case .failed(let err):
            return "Error: \(err.localizedDescription)"
        }
    }

    private func selectAndSendFile() {
        let panel = NSOpenPanel()
        panel.canChooseFiles = true
        panel.canChooseDirectories = false
        panel.allowsMultipleSelection = false
        panel.prompt = "Send File"
        if panel.runModal() == .OK, let url = panel.url {
            appState.sendFile(url: url)
        }
    }

    private func handleFileDrop(providers: [NSItemProvider]) -> Bool {
        guard isConnected, let provider = providers.first else { return false }
        provider.loadItem(forTypeIdentifier: "public.file-url", options: nil) { item, error in
            if let data = item as? Data, let url = URL(dataRepresentation: data, relativeTo: nil) {
                DispatchQueue.main.async {
                    self.appState.sendFile(url: url)
                }
            } else if let url = item as? URL {
                DispatchQueue.main.async {
                    self.appState.sendFile(url: url)
                }
            }
        }
        return true
    }

    private func formatBytes(_ bytes: Int64) -> String {
        let formatter = ByteCountFormatter()
        formatter.allowedUnits = [.useAll]
        formatter.countStyle = .file
        return formatter.string(fromByteCount: bytes)
    }
}

// MARK: - Device Row
struct DeviceRow: View {
    let device: DiscoveredDevice
    @ObservedObject var appState: AppState

    var body: some View {
        let pairedDevice = TrustStore.shared.getPairedDevice()
        let isPaired = (pairedDevice != nil && (device.id == pairedDevice!.deviceId || device.name == pairedDevice!.deviceName))
        let isCurrentConnected = isPaired && (appState.connectionState.isConnected)
        let anotherDevicePaired = (pairedDevice != nil && !isPaired)

        HStack {
            // Device Icon (iPhone, iPad, Mac, Android)
            ZStack(alignment: .bottomTrailing) {
                Image(systemName: deviceIconName)
                    .foregroundColor(device.isOnline ? .accentColor : .secondary)
                    .font(.body)

                // Online/Offline status dot
                Circle()
                    .fill(device.isOnline ? Color.green : Color.secondary.opacity(0.5))
                    .frame(width: 6, height: 6)
                    .offset(x: 2, y: 2)
            }

            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 4) {
                    Text(device.name)
                        .font(.caption)
                        .fontWeight(.semibold)
                        .foregroundColor(device.isOnline ? .primary : .secondary)

                    if isCurrentConnected {
                        Text("★ Paired")
                            .font(.system(size: 8, weight: .bold))
                            .foregroundColor(.green)
                            .padding(.horizontal, 4)
                            .padding(.vertical, 1)
                            .background(Color.green.opacity(0.18))
                            .cornerRadius(4)
                        Text("Connected")
                            .font(.system(size: 8, weight: .bold))
                            .foregroundColor(.green)
                            .padding(.horizontal, 4)
                            .padding(.vertical, 1)
                            .background(Color.green.opacity(0.18))
                            .cornerRadius(4)
                    } else if isPaired {
                        Text("★ Paired")
                            .font(.system(size: 8, weight: .bold))
                            .foregroundColor(.cyan)
                            .padding(.horizontal, 4)
                            .padding(.vertical, 1)
                            .background(Color.cyan.opacity(0.18))
                            .cornerRadius(4)
                        Text(device.isOnline ? "Online" : "Offline")
                            .font(.system(size: 8, weight: .bold))
                            .foregroundColor(device.isOnline ? .green : .secondary)
                            .padding(.horizontal, 4)
                            .padding(.vertical, 1)
                            .background((device.isOnline ? Color.green : Color.secondary).opacity(0.15))
                            .cornerRadius(4)
                    } else if anotherDevicePaired {
                        Text("Blocked")
                            .font(.system(size: 8, weight: .bold))
                            .foregroundColor(.orange)
                            .padding(.horizontal, 4)
                            .padding(.vertical, 1)
                            .background(Color.orange.opacity(0.18))
                            .cornerRadius(4)
                    } else {
                        Text(device.isOnline ? "Available" : "Offline")
                            .font(.system(size: 8, weight: .bold))
                            .foregroundColor(device.isOnline ? .blue : .secondary)
                            .padding(.horizontal, 4)
                            .padding(.vertical, 1)
                            .background((device.isOnline ? Color.blue : Color.secondary).opacity(0.15))
                            .cornerRadius(4)
                    }
                }

                // Subtitle
                Text(subtitleText(isPaired: isPaired, isConnected: isCurrentConnected, anotherPaired: anotherDevicePaired))
                    .font(.caption2)
                    .foregroundColor(.secondary)
            }

            Spacer()

            if isCurrentConnected {
                Button("Disconnect") {
                    appState.disconnect()
                }
                .buttonStyle(.bordered)
                .controlSize(.mini)
                .tint(.red)
            } else if isPaired {
                Button("Connect") {
                    appState.connect(to: device)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.mini)
            } else if anotherDevicePaired {
                Button("Unpair Current") {
                    appState.statusMessage = "Strict 1-Device Policy: Unpair '\(pairedDevice!.deviceName)' first to pair with '\(device.name)'."
                }
                .buttonStyle(.bordered)
                .controlSize(.mini)
                .help("Unpair '\(pairedDevice!.deviceName)' above first to connect this device")
            } else {
                Button("Pair Device") {
                    appState.connect(to: device)
                }
                .buttonStyle(.bordered)
                .controlSize(.mini)
                .tint(.purple)
            }
        }
        .padding(8)
        .background(Color(nsColor: .controlBackgroundColor))
        .cornerRadius(6)
    }

    private var deviceIconName: String {
        let lower = device.name.lowercased()
        if device.deviceType == "iphone" || lower.contains("iphone") {
            return "iphone"
        } else if device.deviceType == "ipad" || lower.contains("ipad") {
            return "ipad"
        } else if device.deviceType == "mac" || lower.contains("mac") {
            return "laptopcomputer"
        } else {
            return "candybarphone"
        }
    }

    private func subtitleText(isPaired: Bool, isConnected: Bool, anotherPaired: Bool) -> String {
        if isConnected {
            return "Connected • Auto-sync active"
        } else if isPaired {
            if device.isOnline {
                return "Available on local Wi-Fi • Auto-connect ready"
            } else {
                return "Offline • Last seen: \(formattedLastSeen(device.lastSeen))"
            }
        } else if anotherPaired {
            return "1-device policy: unpair current device first"
        } else {
            if device.isOnline {
                return "Available on local Wi-Fi"
            } else {
                return "Offline • Last seen: \(formattedLastSeen(device.lastSeen))"
            }
        }
    }

    private func formattedLastSeen(_ date: Date) -> String {
        let seconds = Int(Date().timeIntervalSince(date))
        if seconds < 60 {
            return "Just now"
        } else if seconds < 3600 {
            return "\(seconds / 60)m ago"
        } else if seconds < 86400 {
            return "\(seconds / 3600)h ago"
        } else {
            return "\(seconds / 86400)d ago"
        }
    }
}

// MARK: - Manual IP Sheet (Optional Fallback)
struct ManualIPSheet: View {
    @Binding var isPresented: Bool
    @Binding var ip: String
    @Binding var port: String
    let onConnect: (String, Int) -> Void

    var body: some View {
        VStack(spacing: 12) {
            Text("Direct IP Connection")
                .font(.headline)

            Text("Optional fallback when local mDNS auto-discovery is restricted on this network:")
                .font(.caption)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)

            TextField("IP Address (e.g. 192.168.1.10)", text: $ip)
                .textFieldStyle(.roundedBorder)

            TextField("Port (e.g. 52840)", text: $port)
                .textFieldStyle(.roundedBorder)

            HStack {
                Button("Cancel") {
                    isPresented = false
                }
                .buttonStyle(.plain)

                Spacer()

                Button("Connect") {
                    if let portInt = Int(port), !ip.trimmingCharacters(in: .whitespaces).isEmpty {
                        onConnect(ip.trimmingCharacters(in: .whitespaces), portInt)
                        isPresented = false
                    }
                }
                .buttonStyle(.borderedProminent)
            }
            .padding(.top, 8)
        }
        .padding(16)
        .frame(width: 280)
    }
}

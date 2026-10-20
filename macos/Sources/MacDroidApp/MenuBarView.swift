import SwiftUI
import Networking
import Crypto
import Core

struct MenuBarView: View {
    @ObservedObject var appState: AppState
    @State private var showManualIPSheet = false
    @State private var showHistorySheet = false
    @State private var historyInitialTab = 0
    @State private var manualIP = ""
    @State private var manualPort = "52840"
    @State private var isTargetedForDrop = false
    @State private var showHandoffSheet = false
    @State private var showSettingsSheet = false

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

                Spacer()

                Button(action: {
                    showSettingsSheet.toggle()
                }) {
                    Image(systemName: "gearshape.fill")
                        .foregroundColor(.secondary)
                }
                .buttonStyle(.plain)
                .help("Settings & All Feature Controls")

                Button(action: {
                    historyInitialTab = 0
                    showHistorySheet.toggle()
                }) {
                    Image(systemName: "clock.arrow.circlepath")
                        .foregroundColor(.secondary)
                }
                .buttonStyle(.plain)
                .help("Transfer & Clipboard History")

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

            // MARK: - Master Quick Settings ON/OFF Card
            HStack(spacing: 12) {
                ZStack {
                    Circle()
                        .fill(appState.isEnabled ? Color.blue.opacity(0.16) : Color.secondary.opacity(0.12))
                        .frame(width: 36, height: 36)
                    Image(systemName: appState.isEnabled ? "antenna.radiowaves.left.and.right" : "antenna.radiowaves.left.and.right.slash")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundColor(appState.isEnabled ? .blue : .secondary)
                }

                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Text("DexoLink")
                            .font(.system(size: 13, weight: .bold))

                        Text(appState.isEnabled ? "ON" : "OFF")
                            .font(.system(size: 9, weight: .heavy))
                            .foregroundColor(appState.isEnabled ? .blue : .secondary)
                            .padding(.horizontal, 5)
                            .padding(.vertical, 1.5)
                            .background(
                                Capsule()
                                    .fill(appState.isEnabled ? Color.blue.opacity(0.15) : Color.secondary.opacity(0.12))
                            )
                    }

                    Text(masterSubtitleText)
                        .font(.caption2)
                        .foregroundColor(.secondary)
                        .lineLimit(1)
                }

                Spacer()

                Toggle("", isOn: Binding(
                    get: { appState.isEnabled },
                    set: { appState.setMasterEnabled($0) }
                ))
                .toggleStyle(.switch)
                .labelsHidden()
                .scaleEffect(0.85)
                .help(appState.isEnabled ? "Turn Off DexoLink (0% CPU & Battery)" : "Turn On DexoLink")
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(Color(nsColor: .controlBackgroundColor).opacity(0.5))

            Divider()

            ScrollView {
                VStack(spacing: 10) {
                    if !appState.isEnabled {
                        // DexoLink Turned Off State Banner
                        VStack(spacing: 12) {
                            ZStack {
                                Circle()
                                    .fill(Color.secondary.opacity(0.1))
                                    .frame(width: 58, height: 58)
                                Image(systemName: "antenna.radiowaves.left.and.right.slash")
                                    .font(.system(size: 26))
                                    .foregroundColor(.secondary)
                            }
                            .padding(.top, 24)

                            VStack(spacing: 4) {
                                Text("DexoLink is Turned Off")
                                    .font(.system(size: 14, weight: .bold))

                                Text("All background listeners, discovery broadcasts, and socket connections are stopped to ensure 0% CPU and battery usage.")
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                                    .multilineTextAlignment(.center)
                                    .padding(.horizontal, 24)
                            }

                            if let paired = TrustStore.shared.getPairedDevice() {
                                HStack(spacing: 6) {
                                    Image(systemName: "lock.shield.fill")
                                        .foregroundColor(.cyan)
                                        .font(.caption)
                                    Text("Paired device saved: \(paired.deviceName)")
                                        .font(.caption2)
                                        .foregroundColor(.secondary)
                                }
                                .padding(.horizontal, 10)
                                .padding(.vertical, 4)
                                .background(Color.cyan.opacity(0.1))
                                .cornerRadius(6)
                            }

                            Button(action: {
                                appState.setMasterEnabled(true)
                            }) {
                                HStack(spacing: 6) {
                                    Image(systemName: "bolt.fill")
                                    Text("Turn On DexoLink")
                                }
                                .padding(.horizontal, 12)
                                .padding(.vertical, 4)
                            }
                            .buttonStyle(.borderedProminent)
                            .tint(.blue)
                            .controlSize(.regular)
                            .padding(.top, 4)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 20)
                    } else {
                        // MARK: - Incoming Pairing Request Card
                    if let req = appState.incomingPairingRequest {
                        VStack(alignment: .leading, spacing: 8) {
                            HStack(spacing: 8) {
                                Image(systemName: "lock.shield.fill")
                                    .foregroundColor(.purple)
                                    .font(.title3)

                                VStack(alignment: .leading, spacing: 2) {
                                    Text("Pairing Request")
                                        .font(.subheadline)
                                        .fontWeight(.bold)
                                    Text("\(req.peerName) wants to pair with this Mac")
                                        .font(.caption2)
                                        .foregroundColor(.secondary)
                                }
                                Spacer()
                            }

                            if !req.pin.isEmpty {
                                HStack {
                                    Text("Code: \(req.pin)")
                                        .font(.system(size: 13, weight: .bold, design: .monospaced))
                                        .padding(.vertical, 3)
                                        .padding(.horizontal, 8)
                                        .background(Color.purple.opacity(0.12))
                                        .cornerRadius(6)
                                    Spacer()
                                }
                            }

                            HStack(spacing: 10) {
                                Button("Reject") {
                                    appState.rejectIncomingPairing()
                                }
                                .buttonStyle(.bordered)
                                .controlSize(.small)
                                .foregroundColor(.red)

                                Spacer()

                                Button("Accept") {
                                    appState.acceptIncomingPairing()
                                }
                                .buttonStyle(.borderedProminent)
                                .controlSize(.small)
                                .tint(.green)
                            }
                        }
                        .padding(10)
                        .background(Color.purple.opacity(0.08))
                        .cornerRadius(10)
                        .overlay(
                            RoundedRectangle(cornerRadius: 10)
                                .stroke(Color.purple.opacity(0.3), lineWidth: 1)
                        )
                    }

                    // MARK: - DexoLink Continuity (Handoff) Incoming Card
                    if let incoming = appState.continuityManager.incomingActivity {
                        ContinuityIncomingCard(
                            activity: incoming,
                            onContinue: {
                                _ = appState.continuityManager.continueIncomingActivity()
                            },
                            onDismiss: {
                                appState.continuityManager.dismissIncomingActivity()
                            }
                        )
                    }

                    // MARK: - Paired Devices Status Cards
                    let trustedDevices = TrustStore.shared.getTrustedDevices()
                    if !trustedDevices.isEmpty {
                        VStack(alignment: .leading, spacing: 6) {
                            if appState.isMultiplePairedDevicesEnabled && trustedDevices.count > 1 {
                                Text("Paired Devices (\(trustedDevices.count))")
                                    .font(.system(size: 11, weight: .semibold))
                                    .foregroundColor(.secondary)
                            }
                            ForEach(trustedDevices, id: \.deviceId) { paired in
                                let isThisConnected = isConnected && (appState.connectedDeviceId == paired.deviceId || appState.connectedDeviceName == paired.deviceName)
                                HStack(spacing: 8) {
                                    Image(systemName: isThisConnected ? "checkmark.seal.fill" : "lock.shield.fill")
                                        .foregroundColor(isThisConnected ? .green : .blue)
                                        .font(.title2)

                                    VStack(alignment: .leading, spacing: 3) {
                                        HStack(spacing: 6) {
                                            Text(paired.deviceName)
                                                .font(.subheadline)
                                                .fontWeight(.bold)
                                                .lineLimit(1)
                                                .truncationMode(.tail)
                                            Text(isThisConnected ? "Connected" : "Paired")
                                                .font(.system(size: 9, weight: .bold))
                                                .foregroundColor(isThisConnected ? .green : .cyan)
                                                .padding(.horizontal, 6)
                                                .padding(.vertical, 2)
                                                .background((isThisConnected ? Color.green : Color.cyan).opacity(0.18))
                                                .cornerRadius(4)
                                                .lineLimit(1)
                                                .fixedSize(horizontal: true, vertical: true)
                                        }
                                        Text(appState.isMultiplePairedDevicesEnabled ? "Multi-Device Mode • Keychain Protected" : "Strict 1-Device Policy • Keychain Protected")
                                            .font(.caption2)
                                            .foregroundColor(.secondary)
                                            .lineLimit(1)
                                    }

                                    Spacer(minLength: 4)

                                    HStack(spacing: 6) {
                                        if isThisConnected {
                                            Button("Disconnect") {
                                                appState.disconnect()
                                            }
                                            .buttonStyle(.bordered)
                                            .controlSize(.small)
                                            .tint(.orange)
                                            .help("Disconnect active session while preserving pairing")
                                        }

                                        Button("Unpair") {
                                            appState.unpair(deviceId: paired.deviceId)
                                        }
                                        .buttonStyle(.bordered)
                                        .controlSize(.small)
                                        .tint(.red)
                                        .help("Unpair this specific device")
                                    }
                                }
                                .padding(10)
                                .background(Color.green.opacity(0.08))
                                .cornerRadius(10)
                            }
                        }
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

                                HStack(spacing: 8) {
                                    if isConnected {
                                        let targetLabel = appState.connectedDeviceName.map { "Send to \($0)" } ?? "Send Files"
                                        Button(targetLabel) {
                                            selectAndSendFile()
                                        }
                                        .buttonStyle(.borderedProminent)
                                        .controlSize(.small)
                                    }

                                    Button("All Share Options...") {
                                        selectAndShareWithSystem()
                                    }
                                    .buttonStyle(.bordered)
                                    .controlSize(.small)
                                }
                                .padding(.top, 2)
                            }
                            .padding(.vertical, 12)
                        }
                        .onDrop(of: [.fileURL], isTargeted: $isTargetedForDrop) { providers in
                            handleFileDrop(providers: providers)
                        }

                        // Active Transfer Progress (with Pause / Resume & Cancel)
                        if let transfer = appState.activeTransfer {
                            let isPaused: Bool = {
                                if case .paused = transfer.state { return true }
                                return false
                            }()

                            VStack(alignment: .leading, spacing: 4) {
                                HStack {
                                    Image(systemName: transfer.isOutgoing ? "arrow.up.circle.fill" : "arrow.down.circle.fill")
                                        .foregroundColor(transfer.isOutgoing ? .cyan : .green)
                                    VStack(alignment: .leading, spacing: 1) {
                                        Text(transfer.name)
                                            .font(.caption)
                                            .fontWeight(.semibold)
                                            .lineLimit(1)
                                        Text(isPaused ? "Paused" : (transfer.isOutgoing ? "Uploading to phone..." : "Downloading from phone..."))
                                            .font(.system(size: 9))
                                            .foregroundColor(isPaused ? .orange : .secondary)
                                    }

                                    Spacer()

                                    // Pause / Resume Button
                                    Button(action: {
                                        if isPaused {
                                            appState.unpauseTransfer(id: transfer.id)
                                        } else {
                                            appState.pauseTransfer(id: transfer.id)
                                        }
                                    }) {
                                        Image(systemName: isPaused ? "play.circle.fill" : "pause.circle.fill")
                                            .foregroundColor(isPaused ? .green : .orange)
                                    }
                                    .buttonStyle(.plain)
                                    .help(isPaused ? "Resume transfer" : "Pause transfer")

                                    // Cancel Button
                                    Button(action: {
                                        appState.cancelTransfer(id: transfer.id)
                                    }) {
                                        Image(systemName: "xmark.circle.fill")
                                            .foregroundColor(.red.opacity(0.8))
                                    }
                                    .buttonStyle(.plain)
                                    .help("Cancel file sharing")
                                }

                                ProgressView(value: transfer.progress)
                                    .progressViewStyle(.linear)

                                HStack {
                                    Text("\(formatBytes(transfer.bytesTransferred)) of \(formatBytes(transfer.size))")
                                        .font(.caption2)
                                        .foregroundColor(.secondary)
                                    Spacer()
                                    if transfer.speedBytesPerSec > 0 && !isPaused {
                                        Text("\(formatBytes(Int64(transfer.speedBytesPerSec)))/s")
                                            .font(.caption2)
                                            .foregroundColor(.secondary)
                                        Text("•")
                                            .font(.caption2)
                                            .foregroundColor(.secondary)
                                        Text("ETA: \(formatETA(transfer.etaSeconds))")
                                            .font(.caption2)
                                            .foregroundColor(.secondary)
                                    }
                                    Text("\(Int(transfer.progress * 100))%")
                                        .font(.caption2)
                                        .fontWeight(.bold)
                                        .foregroundColor(isPaused ? .orange : .primary)
                                }
                            }
                            .padding(8)
                            .background((isPaused ? Color.orange : Color.secondary).opacity(0.10))
                            .cornerRadius(8)
                        }

                        // Recent Completed Transfers (with Manage History & Single Delete)
                        if !appState.recentTransfers.isEmpty {
                            VStack(alignment: .leading, spacing: 4) {
                                HStack {
                                    Text("Recent Transfers")
                                        .font(.system(size: 11, weight: .semibold))
                                        .foregroundColor(.secondary)

                                    Spacer()

                                    Button("Manage (\(appState.recentTransfers.count))") {
                                        historyInitialTab = 0
                                        showHistorySheet = true
                                    }
                                    .buttonStyle(.plain)
                                    .font(.system(size: 10, weight: .medium))
                                    .foregroundColor(.accentColor)
                                }

                                ForEach(appState.recentTransfers.prefix(3)) { item in
                                    HStack(spacing: 6) {
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
                                        if item.state != .completed && item.isOutgoing && item.localURL != nil {
                                            Button("Resume") {
                                                appState.resumeTransfer(id: item.id)
                                            }
                                            .buttonStyle(.borderedProminent)
                                            .controlSize(.mini)
                                            .font(.caption2)
                                        } else if let url = item.localURL {
                                            Button("Show") {
                                                NSWorkspace.shared.activateFileViewerSelecting([url])
                                            }
                                            .buttonStyle(.borderless)
                                            .font(.caption2)
                                        }

                                        // Individual Delete from history (safe: file preserved)
                                        Button(action: {
                                            appState.deleteTransferHistoryItem(id: item.id)
                                        }) {
                                            Image(systemName: "trash")
                                                .font(.system(size: 9))
                                                .foregroundColor(.secondary.opacity(0.7))
                                        }
                                        .buttonStyle(.plain)
                                        .help("Remove from history (preserves original file on Mac)")
                                    }
                                }
                            }
                            .padding(.top, 4)
                        }
                    }
                    .padding(10)
                    .background(Color(nsColor: .controlBackgroundColor).opacity(0.6))
                    .cornerRadius(10)

                    // MARK: - Feature Controls Card (Matching Reference Design)
                    HStack(spacing: 10) {
                        ZStack {
                            RoundedRectangle(cornerRadius: 8)
                                .fill(Color(red: 0.12, green: 0.23, blue: 0.54).opacity(0.4))
                                .frame(width: 32, height: 32)
                                .overlay(
                                    RoundedRectangle(cornerRadius: 8)
                                        .stroke(Color.blue.opacity(0.3), lineWidth: 1)
                                )
                            Image(systemName: "gearshape.fill")
                                .font(.system(size: 14, weight: .semibold))
                                .foregroundColor(Color(red: 0.38, green: 0.65, blue: 0.98))
                        }

                        HStack(spacing: 6) {
                            Text("Feature Controls")
                                .font(.system(size: 13, weight: .bold))
                                .foregroundColor(.primary)

                            Text("\(appState.activeFeaturesCount) ACTIVE")
                                .font(.system(size: 9, weight: .heavy))
                                .foregroundColor(Color(red: 0.29, green: 0.87, blue: 0.50))
                                .padding(.horizontal, 6)
                                .padding(.vertical, 2)
                                .background(Capsule().fill(Color(red: 0.08, green: 0.33, blue: 0.18).opacity(0.9)))
                        }

                        Spacer()

                        Button(action: {
                            showSettingsSheet = true
                        }) {
                            Text("Configure")
                                .font(.system(size: 11, weight: .semibold))
                                .foregroundColor(.white)
                                .padding(.horizontal, 12)
                                .padding(.vertical, 6)
                                .background(Color(red: 0.15, green: 0.39, blue: 0.92))
                                .cornerRadius(6)
                        }
                        .buttonStyle(.plain)
                        .help("Open dedicated Settings view containing all feature controls")
                    }
                    .padding(10)
                    .background(Color(nsColor: .controlBackgroundColor).opacity(0.6))
                    .cornerRadius(10)
                    .overlay(
                        RoundedRectangle(cornerRadius: 10)
                            .stroke(Color.white.opacity(0.06), lineWidth: 1)
                    )

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

            Divider()

            // MARK: - Developer Attribution Footer
            HStack {
                Spacer()
                Text("Developed by Rahul Mahaseth | DexoLink v4.0.1")
                    .font(.system(size: 9, weight: .medium))
                    .foregroundColor(.secondary.opacity(0.8))
                Spacer()
            }
            .padding(.vertical, 4)
            .background(Color(nsColor: .controlBackgroundColor))
        }
        .frame(width: 360, height: 520)
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
        .sheet(isPresented: $showHistorySheet) {
            HistorySheetView(
                appState: appState,
                isPresented: $showHistorySheet,
                selectedTab: historyInitialTab
            )
        }
        .sheet(isPresented: $showHandoffSheet) {
            HandoffSheetView(
                appState: appState,
                isPresented: $showHandoffSheet
            )
        }
        .sheet(isPresented: $showSettingsSheet) {
            SettingsSheetView(
                appState: appState,
                isPresented: $showSettingsSheet
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

    private var masterSubtitleText: String {
        guard appState.isEnabled else {
            return "Turned Off • 0% CPU & Battery"
        }
        if isConnected {
            return "Connected to \(appState.connectedDeviceName ?? "Device")"
        } else if let paired = TrustStore.shared.getPairedDevice() {
            return "Searching for \(paired.deviceName)..."
        } else {
            return "Active • Ready to connect"
        }
    }

    private var statusColor: Color {
        guard appState.isEnabled else {
            return .gray
        }
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
        guard appState.isEnabled else {
            return "DexoLink is Off"
        }
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
        panel.allowsMultipleSelection = true
        panel.prompt = "Send"
        if panel.runModal() == .OK {
            for url in panel.urls {
                appState.sendFile(url: url)
            }
        }
    }

    private func selectAndShareWithSystem() {
        let panel = NSOpenPanel()
        panel.canChooseFiles = true
        panel.canChooseDirectories = true
        panel.allowsMultipleSelection = true
        panel.prompt = "Share"
        if panel.runModal() == .OK, !panel.urls.isEmpty {
            ShareWindowController.present(appState: appState, urls: panel.urls, text: nil)
        }
    }

    private func handleFileDrop(providers: [NSItemProvider]) -> Bool {
        guard isConnected else { return false }
        for provider in providers {
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
        }
        return true
    }

    private func formatBytes(_ bytes: Int64) -> String {
        let formatter = ByteCountFormatter()
        formatter.allowedUnits = [.useAll]
        formatter.countStyle = .file
        return formatter.string(fromByteCount: bytes)
    }

    private func formatETA(_ seconds: Int) -> String {
        if seconds <= 0 { return "Calculating..." }
        if seconds < 60 {
            return "\(seconds)s"
        } else if seconds < 3600 {
            let m = seconds / 60
            let s = seconds % 60
            return "\(m)m \(s)s"
        } else {
            let h = seconds / 3600
            let m = (seconds % 3600) / 60
            return "\(h)h \(m)m"
        }
    }
}

// MARK: - Device Row
struct DeviceRow: View {
    let device: DiscoveredDevice
    @ObservedObject var appState: AppState

    var body: some View {
        let isPaired = TrustStore.shared.isPaired(deviceId: device.id, deviceName: device.name) || device.isPaired
        let isCurrentConnected = isPaired && (appState.connectionState.isConnected && (appState.connectedDeviceId == device.id || appState.connectedDeviceName == device.name))
        let anotherDevicePaired = !appState.isMultiplePairedDevicesEnabled && (TrustStore.shared.hasPairedDevice() && !isPaired)

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
                        Text("Paired")
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
                        Text("Paired")
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
                HStack(spacing: 4) {
                    if appState.isCameraSharingEnabled {
                        Button(action: {
                            appState.requestRemoteCameraStream()
                        }) {
                            HStack(spacing: 2) {
                                Image(systemName: "camera.fill")
                                Text("Camera")
                            }
                        }
                        .buttonStyle(.bordered)
                        .controlSize(.mini)
                        .tint(.teal)
                        .help("View live camera feed from this device")
                    }

                    if appState.isScreenSharingEnabled {
                        Button(action: {
                            appState.requestRemoteScreenStream()
                        }) {
                            HStack(spacing: 2) {
                                Image(systemName: "display")
                                Text("Screen")
                            }
                        }
                        .buttonStyle(.bordered)
                        .controlSize(.mini)
                        .tint(.orange)
                        .help("View live screen mirror from this device")
                    }

                    Button("Disconnect") {
                        appState.disconnect()
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.mini)
                    .tint(.red)
                }
            } else if isPaired {
                Button("Connect") {
                    appState.connect(to: device)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.mini)
            } else if anotherDevicePaired {
                let firstPairedName = TrustStore.shared.getPairedDevice()?.deviceName ?? "Current Device"
                HStack(spacing: 4) {
                    Button("Unpair Current") {
                        appState.statusMessage = "Strict 1-Device Policy: Unpair '\(firstPairedName)' first to pair with '\(device.name)'."
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.mini)
                    .help("Unpair '\(firstPairedName)' above first to connect this device")

                    Button(action: {
                        appState.removeDiscoveredDevice(id: device.id)
                    }) {
                        Image(systemName: "xmark")
                            .font(.system(size: 9))
                            .foregroundColor(.secondary)
                    }
                    .buttonStyle(.plain)
                    .help("Dismiss from nearby list")
                }
            } else {
                HStack(spacing: 4) {
                    Button("Pair Device") {
                        appState.connect(to: device)
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.mini)
                    .tint(.purple)

                    Button(action: {
                        appState.removeDiscoveredDevice(id: device.id)
                    }) {
                        Image(systemName: "xmark")
                            .font(.system(size: 9))
                            .foregroundColor(.secondary)
                    }
                    .buttonStyle(.plain)
                    .help("Dismiss from nearby list")
                }
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
            Text("Remote Online / IP Connection")
                .font(.headline)

            Text("Connect directly to a phone, Mac, or PC across local Wi-Fi, VPN, or the Internet with WebRTC STUN/TURN live streaming:")
                .font(.caption)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)

            TextField("Host or IP (e.g. 192.168.1.10, remote.net)", text: $ip)
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

// MARK: - History Sheet View (File Transfers & Clipboard Logs)
struct HistorySheetView: View {
    @ObservedObject var appState: AppState
    @Binding var isPresented: Bool
    @State var selectedTab: Int = 0 // 0: Transfers, 1: Clipboard

    // Multi-selection state
    @State private var isSelectionMode: Bool = false
    @State private var selectedTransferIds: Set<String> = []
    @State private var selectedClipboardIds: Set<String> = []

    // Confirmation alerts
    @State private var showDeleteSelectedTransfersAlert = false
    @State private var showClearTransfersAlert = false
    @State private var showDeleteSelectedClipboardAlert = false
    @State private var showClearClipboardAlert = false
    @State private var copiedClipboardId: String? = nil

    var body: some View {
        VStack(spacing: 0) {
            // Header
            HStack {
                Image(systemName: "clock.arrow.circlepath")
                    .foregroundColor(.accentColor)
                    .font(.title3)

                Text("Activity & History")
                    .font(.headline)
                    .fontWeight(.bold)

                Spacer()

                Button("Done") {
                    isPresented = false
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.small)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .background(Color(nsColor: .windowBackgroundColor))

            Divider()

            // Segmented Tab Picker
            Picker("", selection: $selectedTab) {
                Text("File Transfers (\(appState.recentTransfers.count))").tag(0)
                Text("Clipboard History (\(appState.clipboardHistory.count))").tag(1)
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, 16)
            .padding(.vertical, 10)

            // Content Area
            if selectedTab == 0 {
                transfersTabView
            } else {
                clipboardTabView
            }

            Divider()

            // Safe footnote
            HStack {
                Image(systemName: "shield.fill")
                    .font(.system(size: 10))
                    .foregroundColor(.secondary)
                Text("Deleting history preserves original files on disk and keeps paired connections active.")
                    .font(.system(size: 10))
                    .foregroundColor(.secondary)
                Spacer()
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .background(Color(nsColor: .controlBackgroundColor))
        }
        .frame(width: 480, height: 460)
        // Confirmation Alerts
        .alert("Delete Selected Transfers?", isPresented: $showDeleteSelectedTransfersAlert) {
            Button("Delete (\(selectedTransferIds.count))", role: .destructive) {
                appState.deleteTransferHistoryItems(ids: selectedTransferIds)
                selectedTransferIds.removeAll()
                isSelectionMode = false
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Are you sure you want to delete these \(selectedTransferIds.count) item(s) from transfer history? Original files on your Mac will NOT be deleted.")
        }
        .alert("Clear All Transfer History?", isPresented: $showClearTransfersAlert) {
            Button("Clear All", role: .destructive) {
                appState.clearTransferHistory()
                selectedTransferIds.removeAll()
                isSelectionMode = false
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Are you sure you want to clear your transfer history? Original files on your Mac will NOT be deleted.")
        }
        .alert("Delete Selected Clips?", isPresented: $showDeleteSelectedClipboardAlert) {
            Button("Delete (\(selectedClipboardIds.count))", role: .destructive) {
                appState.deleteClipboardHistoryItems(ids: selectedClipboardIds)
                selectedClipboardIds.removeAll()
                isSelectionMode = false
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Are you sure you want to delete these \(selectedClipboardIds.count) snippet(s) from clipboard history?")
        }
        .alert("Clear Clipboard History?", isPresented: $showClearClipboardAlert) {
            Button("Clear All", role: .destructive) {
                appState.clearClipboardHistory()
                selectedClipboardIds.removeAll()
                isSelectionMode = false
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Are you sure you want to clear all clipboard history?")
        }
    }

    // MARK: - Transfers Tab View
    private var transfersTabView: some View {
        VStack(spacing: 8) {
            // Sub-toolbar
            HStack {
                if !appState.recentTransfers.isEmpty {
                    Button(isSelectionMode ? "Cancel Selection" : "Select Multiple") {
                        isSelectionMode.toggle()
                        if !isSelectionMode { selectedTransferIds.removeAll() }
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.mini)

                    if isSelectionMode {
                        Button(selectedTransferIds.count == appState.recentTransfers.count ? "Deselect All" : "Select All") {
                            if selectedTransferIds.count == appState.recentTransfers.count {
                                selectedTransferIds.removeAll()
                            } else {
                                selectedTransferIds = Set(appState.recentTransfers.map { $0.id })
                            }
                        }
                        .buttonStyle(.bordered)
                        .controlSize(.mini)

                        Spacer()

                        Button(action: {
                            showDeleteSelectedTransfersAlert = true
                        }) {
                            HStack(spacing: 3) {
                                Image(systemName: "trash")
                                Text("Delete Selected (\(selectedTransferIds.count))")
                            }
                        }
                        .buttonStyle(.borderedProminent)
                        .controlSize(.mini)
                        .tint(.red)
                        .disabled(selectedTransferIds.isEmpty)
                    } else {
                        Spacer()

                        Button("Clear All History") {
                            showClearTransfersAlert = true
                        }
                        .buttonStyle(.bordered)
                        .controlSize(.mini)
                        .tint(.red)
                    }
                } else {
                    Spacer()
                }
            }
            .padding(.horizontal, 16)

            if appState.recentTransfers.isEmpty {
                VStack(spacing: 12) {
                    Spacer()
                    Image(systemName: "tray")
                        .font(.system(size: 36))
                        .foregroundColor(.secondary.opacity(0.4))
                    Text("No Transfer History")
                        .font(.headline)
                        .foregroundColor(.secondary)
                    Text("Files you send or receive between your Mac and paired phone will be logged here.")
                        .font(.caption)
                        .foregroundColor(.secondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 40)
                    Spacer()
                }
            } else {
                List {
                    ForEach(appState.recentTransfers) { item in
                        HStack(spacing: 10) {
                            if isSelectionMode {
                                Button(action: {
                                    if selectedTransferIds.contains(item.id) {
                                        selectedTransferIds.remove(item.id)
                                    } else {
                                        selectedTransferIds.insert(item.id)
                                    }
                                }) {
                                    Image(systemName: selectedTransferIds.contains(item.id) ? "checkmark.circle.fill" : "circle")
                                        .foregroundColor(selectedTransferIds.contains(item.id) ? .accentColor : .secondary)
                                }
                                .buttonStyle(.plain)
                            }

                            Image(systemName: item.isOutgoing ? "arrow.up.circle.fill" : "arrow.down.circle.fill")
                                .foregroundColor(item.isOutgoing ? .cyan : .green)
                                .font(.title3)

                            VStack(alignment: .leading, spacing: 2) {
                                Text(item.name)
                                    .font(.subheadline)
                                    .fontWeight(.medium)
                                    .lineLimit(1)

                                HStack(spacing: 6) {
                                    Text(formatBytes(item.size))
                                        .font(.caption2)
                                        .foregroundColor(.secondary)

                                    Text("•")
                                        .font(.caption2)
                                        .foregroundColor(.secondary)

                                    Text(item.isOutgoing ? "Sent" : "Received")
                                        .font(.caption2)
                                        .foregroundColor(item.isOutgoing ? .cyan : .green)

                                    Text("•")
                                        .font(.caption2)
                                        .foregroundColor(.secondary)

                                    Text(formattedDate(item.startTime))
                                        .font(.caption2)
                                        .foregroundColor(.secondary)
                                }
                            }

                            Spacer()

                            if let url = item.localURL {
                                Button("Show") {
                                    NSWorkspace.shared.activateFileViewerSelecting([url])
                                }
                                .buttonStyle(.bordered)
                                .controlSize(.mini)
                            }

                            Button(action: {
                                appState.deleteTransferHistoryItem(id: item.id)
                            }) {
                                Image(systemName: "trash")
                                    .foregroundColor(.secondary.opacity(0.8))
                            }
                            .buttonStyle(.plain)
                            .help("Remove from history")
                        }
                        .padding(.vertical, 3)
                    }
                }
                .listStyle(.inset)
            }
        }
    }

    // MARK: - Clipboard Tab View
    private var clipboardTabView: some View {
        VStack(spacing: 8) {
            // Sub-toolbar
            HStack {
                if !appState.clipboardHistory.isEmpty {
                    Button(isSelectionMode ? "Cancel Selection" : "Select Multiple") {
                        isSelectionMode.toggle()
                        if !isSelectionMode { selectedClipboardIds.removeAll() }
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.mini)

                    if isSelectionMode {
                        Button(selectedClipboardIds.count == appState.clipboardHistory.count ? "Deselect All" : "Select All") {
                            if selectedClipboardIds.count == appState.clipboardHistory.count {
                                selectedClipboardIds.removeAll()
                            } else {
                                selectedClipboardIds = Set(appState.clipboardHistory.map { $0.id })
                            }
                        }
                        .buttonStyle(.bordered)
                        .controlSize(.mini)

                        Spacer()

                        Button(action: {
                            showDeleteSelectedClipboardAlert = true
                        }) {
                            HStack(spacing: 3) {
                                Image(systemName: "trash")
                                Text("Delete Selected (\(selectedClipboardIds.count))")
                            }
                        }
                        .buttonStyle(.borderedProminent)
                        .controlSize(.mini)
                        .tint(.red)
                        .disabled(selectedClipboardIds.isEmpty)
                    } else {
                        Spacer()

                        Button("Clear All History") {
                            showClearClipboardAlert = true
                        }
                        .buttonStyle(.bordered)
                        .controlSize(.mini)
                        .tint(.red)
                    }
                } else {
                    Spacer()
                }
            }
            .padding(.horizontal, 16)

            if appState.clipboardHistory.isEmpty {
                VStack(spacing: 12) {
                    Spacer()
                    Image(systemName: "doc.on.clipboard")
                        .font(.system(size: 36))
                        .foregroundColor(.secondary.opacity(0.4))
                    Text("No Clipboard History")
                        .font(.headline)
                        .foregroundColor(.secondary)
                    Text("Copied texts synchronized between your Mac and paired phone will appear here.")
                        .font(.caption)
                        .foregroundColor(.secondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 40)
                    Spacer()
                }
            } else {
                List {
                    ForEach(appState.clipboardHistory) { item in
                        HStack(spacing: 10) {
                            if isSelectionMode {
                                Button(action: {
                                    if selectedClipboardIds.contains(item.id) {
                                        selectedClipboardIds.remove(item.id)
                                    } else {
                                        selectedClipboardIds.insert(item.id)
                                    }
                                }) {
                                    Image(systemName: selectedClipboardIds.contains(item.id) ? "checkmark.circle.fill" : "circle")
                                        .foregroundColor(selectedClipboardIds.contains(item.id) ? .accentColor : .secondary)
                                }
                                .buttonStyle(.plain)
                            }

                            Image(systemName: item.isOutgoing ? "arrow.up.right" : "arrow.down.left")
                                .foregroundColor(item.isOutgoing ? .cyan : .green)
                                .font(.body)

                            VStack(alignment: .leading, spacing: 2) {
                                Text(item.text)
                                    .font(.subheadline)
                                    .lineLimit(2)

                                HStack(spacing: 6) {
                                    Text(item.isOutgoing ? "Sent" : "Received")
                                        .font(.caption2)
                                        .foregroundColor(item.isOutgoing ? .cyan : .green)

                                    Text("•")
                                        .font(.caption2)
                                        .foregroundColor(.secondary)

                                    Text(formattedDate(item.timestamp))
                                        .font(.caption2)
                                        .foregroundColor(.secondary)
                                }
                            }

                            Spacer()

                            Button(action: {
                                appState.clipboardManager.applyTextToPasteboard(item.text)
                                copiedClipboardId = item.id
                                DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                                    if copiedClipboardId == item.id {
                                        copiedClipboardId = nil
                                    }
                                }
                            }) {
                                HStack(spacing: 2) {
                                    Image(systemName: copiedClipboardId == item.id ? "checkmark" : "doc.on.doc")
                                    Text(copiedClipboardId == item.id ? "Copied" : "Copy")
                                }
                                .font(.caption2)
                            }
                            .buttonStyle(.bordered)
                            .controlSize(.mini)

                            Button(action: {
                                appState.deleteClipboardHistoryItem(id: item.id)
                            }) {
                                Image(systemName: "trash")
                                    .foregroundColor(.secondary.opacity(0.8))
                            }
                            .buttonStyle(.plain)
                            .help("Remove from history")
                        }
                        .padding(.vertical, 3)
                    }
                }
                .listStyle(.inset)
            }
        }
    }

    private func formatBytes(_ bytes: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
    }

    private func formattedDate(_ date: Date) -> String {
        let diff = Int(Date().timeIntervalSince(date))
        if diff < 60 { return "Just now" }
        if diff < 3600 { return "\(diff / 60)m ago" }
        if diff < 86400 { return "\(diff / 3600)h ago" }
        let f = DateFormatter()
        f.dateStyle = .short
        f.timeStyle = .short
        return f.string(from: date)
    }
}

// MARK: - Continuity Incoming Activity Card (Apple Handoff inspired)
struct ContinuityIncomingCard: View {
    let activity: ContinuityActivity
    let onContinue: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                ZStack {
                    RoundedRectangle(cornerRadius: 8)
                        .fill(LinearGradient(
                            colors: [Color.cyan, Color.blue],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        ))
                        .frame(width: 32, height: 32)
                    Image(systemName: iconName(for: activity.activityType))
                        .foregroundColor(.white)
                        .font(.system(size: 15, weight: .bold))
                }

                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 4) {
                        Text("CONTINUE FROM \(activity.sourceDevice.uppercased())")
                            .font(.system(size: 8, weight: .black))
                            .foregroundColor(.cyan)
                        Spacer()
                    }
                    Text(activity.title)
                        .font(.system(size: 13, weight: .semibold))
                        .lineLimit(1)
                    Text(activity.displaySubtitle)
                        .font(.caption2)
                        .foregroundColor(.secondary)
                        .lineLimit(1)
                }

                Spacer()
            }

            HStack(spacing: 8) {
                Button(action: onDismiss) {
                    Text("Dismiss")
                        .font(.caption)
                }
                .buttonStyle(.bordered)
                .controlSize(.small)

                Spacer()

                Button(action: onContinue) {
                    HStack(spacing: 4) {
                        Image(systemName: "arrow.right.circle.fill")
                        Text("Continue on Mac")
                            .fontWeight(.semibold)
                    }
                    .font(.caption)
                }
                .buttonStyle(.borderedProminent)
                .tint(.cyan)
                .controlSize(.small)
            }
        }
        .padding(10)
        .background(
            RoundedRectangle(cornerRadius: 10)
                .fill(Color.cyan.opacity(0.12))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 10)
                .stroke(Color.cyan.opacity(0.4), lineWidth: 1)
        )
    }

    private func iconName(for type: String) -> String {
        switch type {
        case "webpage": return "safari.fill"
        case "pdf": return "doc.richtext.fill"
        case "presentation": return "sparkles.tv"
        case "document": return "doc.text.fill"
        default: return "arrow.triangle.2.circlepath"
        }
    }
}

// MARK: - Continuity Outgoing Handoff Sheet
struct HandoffSheetView: View {
    @ObservedObject var appState: AppState
    @Binding var isPresented: Bool

    @State private var activityType = "webpage"
    @State private var title = ""
    @State private var urlOrPath = ""
    @State private var position: Int = 1
    @State private var progressPercent: Double = 0.0

    let activityTypes = [
        ("webpage", "Webpage", "safari.fill"),
        ("pdf", "PDF", "doc.richtext.fill"),
        ("presentation", "Slide", "sparkles.tv"),
        ("document", "Doc", "doc.text.fill")
    ]

    var body: some View {
        VStack(spacing: 12) {
            HStack {
                Image(systemName: "arrow.triangle.2.circlepath.circle.fill")
                    .foregroundColor(.cyan)
                    .font(.title3)
                Text("Continue on Android")
                    .font(.headline)
                    .fontWeight(.bold)
                Spacer()
                Button("Done") {
                    isPresented = false
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
            }

            Picker("Type", selection: $activityType) {
                ForEach(activityTypes, id: \.0) { item in
                    Label(item.1, systemImage: item.2).tag(item.0)
                }
            }
            .pickerStyle(.segmented)

            VStack(alignment: .leading, spacing: 6) {
                Text("Title / Subject")
                    .font(.caption)
                    .foregroundColor(.secondary)
                TextField(titlePlaceholder, text: $title)
                    .textFieldStyle(.roundedBorder)

                Text(activityType == "webpage" ? "Web URL" : "File URL or Path")
                    .font(.caption)
                    .foregroundColor(.secondary)

                HStack {
                    TextField(urlPlaceholder, text: $urlOrPath)
                        .textFieldStyle(.roundedBorder)
                    if activityType == "webpage" {
                        Button("Paste") {
                            if let clip = NSPasteboard.general.string(forType: .string),
                               clip.hasPrefix("http://") || clip.hasPrefix("https://") {
                                urlOrPath = clip
                                if title.isEmpty {
                                    title = URL(string: clip)?.host ?? "Webpage"
                                }
                            }
                        }
                        .buttonStyle(.bordered)
                        .controlSize(.small)
                        .help("Paste URL from clipboard")
                    } else {
                        Button("Browse...") {
                            let panel = NSOpenPanel()
                            panel.canChooseFiles = true
                            panel.canChooseDirectories = false
                            if activityType == "pdf" {
                                panel.allowedContentTypes = [.pdf]
                            }
                            if panel.runModal() == .OK, let url = panel.url {
                                urlOrPath = url.absoluteString
                                if title.isEmpty {
                                    title = url.lastPathComponent
                                }
                            }
                        }
                        .buttonStyle(.bordered)
                        .controlSize(.small)
                    }
                }

                if activityType == "pdf" || activityType == "presentation" || activityType == "document" {
                    HStack(spacing: 12) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(activityType == "presentation" ? "Slide Number" : "Page Number")
                                .font(.caption)
                                .foregroundColor(.secondary)
                            Stepper(value: $position, in: 1...9999) {
                                Text("\(activityType == "presentation" ? "Slide" : "Page") \(position)")
                                    .font(.subheadline)
                                    .fontWeight(.medium)
                            }
                        }
                    }
                    .padding(.top, 4)
                }
            }

            Spacer()

            Button(action: sendHandoff) {
                HStack(spacing: 6) {
                    Image(systemName: "paperplane.fill")
                    Text("Continue on Android")
                        .fontWeight(.bold)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 4)
            }
            .buttonStyle(.borderedProminent)
            .tint(.cyan)
            .disabled(urlOrPath.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
        .padding(16)
        .frame(width: 380, height: 320)
        .onAppear {
            if let clip = NSPasteboard.general.string(forType: .string),
               clip.hasPrefix("http://") || clip.hasPrefix("https://") {
                urlOrPath = clip
                title = URL(string: clip)?.host ?? "Webpage"
            }
        }
    }

    private var titlePlaceholder: String {
        switch activityType {
        case "webpage": return "e.g. Wikipedia: Quantum Computing"
        case "pdf": return "e.g. DexoLink Architecture Guide"
        case "presentation": return "e.g. Q3 Product Roadmap"
        default: return "e.g. Project Notes"
        }
    }

    private var urlPlaceholder: String {
        switch activityType {
        case "webpage": return "https://example.com/article"
        case "pdf": return "https://example.com/doc.pdf or file path"
        default: return "URL or file name"
        }
    }

    private func sendHandoff() {
        let trimmedUrl = urlOrPath.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedUrl.isEmpty else { return }

        let resolvedTitle = title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ?
            (URL(string: trimmedUrl)?.lastPathComponent ?? "DexoLink Activity") : title

        let activity = ContinuityActivity(
            activityId: UUID().uuidString,
            activityType: activityType,
            title: resolvedTitle,
            subtitle: activityType == "webpage" ? (URL(string: trimmedUrl)?.host ?? "") : "Page \(position)",
            url: trimmedUrl,
            contentPayload: nil,
            position: position > 0 ? position : 0,
            progress: progressPercent,
            timestamp: Int64(Date().timeIntervalSince1970 * 1000),
            sourceDevice: Host.current().localizedName ?? "MacBook"
        )

        appState.continuityManager.broadcastActivity(activity)
        isPresented = false
    }
}

// MARK: - Compact Feature Control Button (Extensible architecture for existing & future features)
struct CompactFeatureControl: View {
    let title: String
    let subtitle: String?
    let icon: String
    let tintColor: Color
    var badgeText: String? = nil
    let isEnabled: Bool
    let onToggle: () -> Void
    var actionTitle: String? = nil
    var actionIcon: String? = nil
    var onAction: (() -> Void)? = nil
    var secondaryActionTitle: String? = nil
    var secondaryActionIcon: String? = nil
    var onSecondaryAction: (() -> Void)? = nil

    var body: some View {
        HStack(spacing: 8) {
            ZStack {
                Circle()
                    .fill(tintColor.opacity(isEnabled ? 0.18 : 0.08))
                    .frame(width: 26, height: 26)
                Image(systemName: icon)
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundColor(isEnabled ? tintColor : .secondary)
            }

            VStack(alignment: .leading, spacing: 1) {
                HStack(spacing: 4) {
                    Text(title)
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundColor(.primary)

                    if let badge = badgeText {
                        Text(badge)
                            .font(.system(size: 7, weight: .heavy))
                            .foregroundColor(tintColor)
                            .padding(.horizontal, 3)
                            .padding(.vertical, 0.5)
                            .background(
                                Capsule().fill(tintColor.opacity(0.12))
                            )
                    }

                    Text(isEnabled ? "ON" : "OFF")
                        .font(.system(size: 8, weight: .heavy))
                        .foregroundColor(isEnabled ? tintColor : .secondary)
                        .padding(.horizontal, 4)
                        .padding(.vertical, 1)
                        .background(
                            Capsule().fill((isEnabled ? tintColor : Color.secondary).opacity(0.12))
                        )
                }

                if let sub = subtitle {
                    Text(sub)
                        .font(.system(size: 9))
                        .foregroundColor(.secondary)
                        .lineLimit(1)
                }
            }

            Spacer(minLength: 4)

            // Secondary Action (e.g. Ping latency)
            if let secTitle = secondaryActionTitle, let onSec = onSecondaryAction, isEnabled {
                Button(action: onSec) {
                    HStack(spacing: 2) {
                        if let secIcon = secondaryActionIcon {
                            Image(systemName: secIcon)
                                .font(.system(size: 8))
                        }
                        Text(secTitle)
                            .font(.system(size: 9, weight: .semibold))
                    }
                }
                .buttonStyle(.bordered)
                .controlSize(.mini)
                .help("Test Ping Latency")
            }

            // Primary Action (e.g. Handoff... or History)
            if let actTitle = actionTitle, let onAct = onAction, isEnabled {
                Button(action: onAct) {
                    HStack(spacing: 2) {
                        if let actIcon = actionIcon {
                            Image(systemName: actIcon)
                                .font(.system(size: 8))
                        }
                        Text(actTitle)
                            .font(.system(size: 9, weight: .medium))
                    }
                }
                .buttonStyle(.bordered)
                .controlSize(.mini)
            }

            // Compact 1-click Toggle Switch
            Toggle("", isOn: Binding(
                get: { isEnabled },
                set: { _ in onToggle() }
            ))
            .toggleStyle(.switch)
            .labelsHidden()
            .scaleEffect(0.68)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 5)
        .background(
            RoundedRectangle(cornerRadius: 8)
                .fill(tintColor.opacity(isEnabled ? 0.05 : 0.02))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 8)
                .stroke(tintColor.opacity(isEnabled ? 0.20 : 0.08), lineWidth: 1)
        )
    }
}

// MARK: - Dedicated Settings & Feature Controls Sheet
struct SettingsSheetView: View {
    @ObservedObject var appState: AppState
    @Binding var isPresented: Bool
    @State private var showHandoffSheet = false
    @State private var showHistorySheet = false
    @State private var historyInitialTab = 1

    var body: some View {
        VStack(spacing: 0) {
            // Header
            HStack(spacing: 8) {
                Image(systemName: "gearshape.fill")
                    .foregroundColor(.cyan)
                    .font(.title3)

                VStack(alignment: .leading, spacing: 1) {
                    Text("Settings")
                        .font(.headline)
                        .fontWeight(.bold)
                    Text("Feature Controls & System Preferences")
                        .font(.system(size: 10))
                        .foregroundColor(.secondary)
                }

                Spacer()

                Button("Done") {
                    isPresented = false
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.small)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .background(Color(nsColor: .windowBackgroundColor))

            Divider()

            ScrollView {
                VStack(spacing: 14) {
                    // Section 1: Core Feature Controls
                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            Image(systemName: "switch.2")
                                .foregroundColor(.cyan)
                                .font(.caption)
                            Text("Core Feature Controls")
                                .font(.system(size: 11, weight: .semibold))
                                .foregroundColor(.secondary)
                        }

                        VStack(spacing: 8) {
                            // 1. Continuity (Handoff)
                            CompactFeatureControl(
                                title: "Continuity (Handoff)",
                                subtitle: appState.continuityManager.incomingActivity != nil ? "Incoming activity ready to open" : "Continue reading PDFs, docs & web across devices",
                                icon: "arrow.triangle.2.circlepath.circle.fill",
                                tintColor: .cyan,
                                isEnabled: appState.continuityManager.isEnabled,
                                onToggle: {
                                    appState.continuityManager.isEnabled.toggle()
                                },
                                actionTitle: "Send...",
                                actionIcon: "paperplane.fill",
                                onAction: {
                                    showHandoffSheet = true
                                }
                            )

                            // 2. Auto Clipboard Sync
                            CompactFeatureControl(
                                title: "Auto Clipboard Sync",
                                subtitle: appState.lastSyncedClipboardSnippet.map { "Synced: \"\($0.prefix(22))...\"" } ?? "Bidirectional text & links synchronization",
                                icon: "doc.on.clipboard.fill",
                                tintColor: .green,
                                isEnabled: appState.isClipboardSyncEnabled,
                                onToggle: {
                                    appState.toggleClipboardSync()
                                },
                                actionTitle: "History",
                                actionIcon: "clock.arrow.circlepath",
                                onAction: {
                                    historyInitialTab = 1
                                    showHistorySheet = true
                                },
                                secondaryActionTitle: appState.lastPingLatencyMs != nil ? "\(appState.lastPingLatencyMs!)ms" : "Ping",
                                secondaryActionIcon: "bolt.fill",
                                onSecondaryAction: {
                                    appState.sendPing()
                                }
                            )

                            // 3. Enhanced Clipboard
                            CompactFeatureControl(
                                title: "Enhanced Clipboard",
                                subtitle: "Rich HTML & multi-MIME paste compatibility across restricted apps",
                                icon: "sparkles",
                                tintColor: .purple,
                                badgeText: "BETA",
                                isEnabled: appState.isEnhancedClipboardEnabled,
                                onToggle: {
                                    appState.setEnhancedClipboardEnabled(!appState.isEnhancedClipboardEnabled)
                                }
                            )

                            // 4. Multiple Paired Devices
                            CompactFeatureControl(
                                title: "Multiple Paired Devices",
                                subtitle: appState.isMultiplePairedDevicesEnabled ? "Connect multiple devices simultaneously" : "Single device mode (Strict 1-device policy - OFF by default)",
                                icon: "person.2.badge.key.fill",
                                tintColor: .indigo,
                                isEnabled: appState.isMultiplePairedDevicesEnabled,
                                onToggle: {
                                    appState.toggleMultiplePairedDevices()
                                }
                            )
                        }
                        .padding(10)
                        .background(Color(nsColor: .controlBackgroundColor).opacity(0.6))
                        .cornerRadius(10)
                    }

                    // Section 2: Remote Viewing & Media Streaming (v4.0.1)
                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            Image(systemName: "video.fill")
                                .foregroundColor(.teal)
                                .font(.caption)
                            Text("Media Streaming & Remote Control")
                                .font(.system(size: 11, weight: .semibold))
                                .foregroundColor(.secondary)
                        }

                        VStack(spacing: 8) {
                            // 1. Camera Sharing
                            CompactFeatureControl(
                                title: "Camera Sharing",
                                subtitle: appState.isCameraSharingEnabled ? "Streaming active or ready for camera requests" : "Stream camera feed securely over local Wi-Fi (OFF by default)",
                                icon: "camera.fill",
                                tintColor: .teal,
                                isEnabled: appState.isCameraSharingEnabled,
                                onToggle: {
                                    appState.setCameraSharingEnabled(!appState.isCameraSharingEnabled)
                                }
                            )

                            if appState.isCameraSharingEnabled {
                                if appState.connectionState.isConnected, let remoteName = appState.connectedDeviceName {
                                    let devType = appState.connectedDeviceType ?? appState.inferDeviceType(from: remoteName)
                                    let typeDisplay: String = {
                                        switch devType.lowercased() {
                                        case "iphone": return "iPhone"
                                        case "ipad": return "iPad"
                                        case "windows": return "Windows PC"
                                        case "mac": return "macOS"
                                        default: return "Android"
                                        }
                                    }()

                                    VStack(alignment: .leading, spacing: 6) {
                                        HStack(spacing: 4) {
                                            Circle().fill(Color.green).frame(width: 6, height: 6)
                                            Text("Verified Connected Device: \(remoteName) (\(typeDisplay))")
                                                .font(.system(size: 10, weight: .bold))
                                                .foregroundColor(.primary)
                                        }
                                        .padding(.horizontal, 4)

                                        HStack(spacing: 6) {
                                            Button(action: {
                                                appState.requestRemoteCameraStream()
                                            }) {
                                                HStack(spacing: 4) {
                                                    Image(systemName: "video.fill")
                                                    Text("View Remote Camera (\(remoteName) -> Mac)")
                                                        .font(.system(size: 11, weight: .semibold))
                                                }
                                                .frame(maxWidth: .infinity)
                                                .padding(.vertical, 6)
                                                .background(Color.teal.opacity(0.18))
                                                .foregroundColor(.teal)
                                                .cornerRadius(6)
                                            }
                                            .buttonStyle(.plain)

                                            Button(action: {
                                                appState.startSharingMacCameraToRemote()
                                            }) {
                                                HStack(spacing: 4) {
                                                    Image(systemName: "arrow.up.right.video.fill")
                                                    Text("Stream Mac Camera (Mac -> \(remoteName))")
                                                        .font(.system(size: 11, weight: .semibold))
                                                }
                                                .frame(maxWidth: .infinity)
                                                .padding(.vertical, 6)
                                                .background(Color.teal.opacity(0.12))
                                                .foregroundColor(.teal)
                                                .cornerRadius(6)
                                            }
                                            .buttonStyle(.plain)
                                        }
                                        .padding(.horizontal, 4)
                                    }
                                    .padding(.vertical, 4)
                                } else {
                                    HStack(spacing: 6) {
                                        Image(systemName: "exclamationmark.triangle.fill")
                                            .foregroundColor(.orange)
                                            .font(.caption)
                                        Text("No verified device connected. Connect & pair with Android, Windows, iPhone, or Mac over Wi-Fi to start camera sharing.")
                                            .font(.system(size: 10))
                                            .foregroundColor(.secondary)
                                    }
                                    .padding(8)
                                    .background(Color.orange.opacity(0.08))
                                    .cornerRadius(6)
                                    .padding(.horizontal, 4)
                                }
                            }

                            // 2. Screen Sharing
                            CompactFeatureControl(
                                title: "Screen Sharing",
                                subtitle: appState.isScreenSharingEnabled ? "Display mirroring active or ready" : "Mirror device display in real-time over local network (OFF by default)",
                                icon: "display.2",
                                tintColor: .orange,
                                isEnabled: appState.isScreenSharingEnabled,
                                onToggle: {
                                    appState.setScreenSharingEnabled(!appState.isScreenSharingEnabled)
                                }
                            )

                            if appState.isScreenSharingEnabled {
                                if appState.connectionState.isConnected, let remoteName = appState.connectedDeviceName {
                                    let devType = appState.connectedDeviceType ?? appState.inferDeviceType(from: remoteName)
                                    let typeDisplay: String = {
                                        switch devType.lowercased() {
                                        case "iphone": return "iPhone"
                                        case "ipad": return "iPad"
                                        case "windows": return "Windows PC"
                                        case "mac": return "macOS"
                                        default: return "Android"
                                        }
                                    }()

                                    VStack(alignment: .leading, spacing: 6) {
                                        HStack(spacing: 4) {
                                            Circle().fill(Color.green).frame(width: 6, height: 6)
                                            Text("Verified Connected Device: \(remoteName) (\(typeDisplay))")
                                                .font(.system(size: 10, weight: .bold))
                                                .foregroundColor(.primary)
                                        }
                                        .padding(.horizontal, 4)

                                        HStack(spacing: 6) {
                                            Button(action: {
                                                appState.requestRemoteScreenStream()
                                            }) {
                                                HStack(spacing: 4) {
                                                    Image(systemName: "display")
                                                    Text("View Remote Screen (\(remoteName) -> Mac)")
                                                        .font(.system(size: 11, weight: .semibold))
                                                }
                                                .frame(maxWidth: .infinity)
                                                .padding(.vertical, 6)
                                                .background(Color.orange.opacity(0.18))
                                                .foregroundColor(.orange)
                                                .cornerRadius(6)
                                            }
                                            .buttonStyle(.plain)

                                            Button(action: {
                                                appState.startSharingMacScreenToRemote()
                                            }) {
                                                HStack(spacing: 4) {
                                                    Image(systemName: "arrow.up.right.square")
                                                    Text("Mirror Mac Screen (Mac -> \(remoteName))")
                                                        .font(.system(size: 11, weight: .semibold))
                                                }
                                                .frame(maxWidth: .infinity)
                                                .padding(.vertical, 6)
                                                .background(Color.orange.opacity(0.12))
                                                .foregroundColor(.orange)
                                                .cornerRadius(6)
                                            }
                                            .buttonStyle(.plain)
                                        }
                                        .padding(.horizontal, 4)
                                    }
                                    .padding(.vertical, 4)
                                } else {
                                    HStack(spacing: 6) {
                                        Image(systemName: "exclamationmark.triangle.fill")
                                            .foregroundColor(.orange)
                                            .font(.caption)
                                        Text("No verified device connected. Connect & pair with Android, Windows, iPhone, or Mac over Wi-Fi to start screen mirroring.")
                                            .font(.system(size: 10))
                                            .foregroundColor(.secondary)
                                    }
                                    .padding(8)
                                    .background(Color.orange.opacity(0.08))
                                    .cornerRadius(6)
                                    .padding(.horizontal, 4)
                                }
                            }

                            // 3. Desktop Mode (beta)
                            CompactFeatureControl(
                                title: "Desktop Mode",
                                subtitle: appState.isDesktopModeEnabled ? "Remote viewing and input control enabled" : "Remote desktop viewing & input control where supported (OFF by default)",
                                icon: "desktopcomputer",
                                tintColor: .purple,
                                badgeText: "BETA",
                                isEnabled: appState.isDesktopModeEnabled,
                                onToggle: {
                                    appState.setDesktopModeEnabled(!appState.isDesktopModeEnabled)
                                }
                            )
                        }
                        .padding(10)
                        .background(Color(nsColor: .controlBackgroundColor).opacity(0.6))
                        .cornerRadius(10)
                    }

                    // Section 3: Wi-Fi Discovery & Network Preferences
                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            Image(systemName: "network")
                                .foregroundColor(.blue)
                                .font(.caption)
                            Text("Discovery & Network")
                                .font(.system(size: 11, weight: .semibold))
                                .foregroundColor(.secondary)
                        }

                        VStack(spacing: 8) {
                            CompactFeatureControl(
                                title: "Auto Wi-Fi Discovery",
                                subtitle: appState.discoveryEnabled ? "Broadcasting and listening on local subnet" : "Discovery paused to conserve battery",
                                icon: "antenna.radiowaves.left.and.right",
                                tintColor: .blue,
                                isEnabled: appState.discoveryEnabled,
                                onToggle: {
                                    appState.setDiscoveryEnabled(!appState.discoveryEnabled)
                                }
                            )
                        }
                        .padding(10)
                        .background(Color(nsColor: .controlBackgroundColor).opacity(0.6))
                        .cornerRadius(10)
                    }

                    // Section 3: File Storage Preferences
                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            Image(systemName: "folder.fill")
                                .foregroundColor(.orange)
                                .font(.caption)
                            Text("Received Files Storage")
                                .font(.system(size: 11, weight: .semibold))
                                .foregroundColor(.secondary)
                        }

                        HStack(spacing: 10) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Downloads Directory")
                                    .font(.system(size: 11, weight: .semibold))
                                Text(appState.transferManager.downloadDirectory.path)
                                    .font(.system(size: 9))
                                    .foregroundColor(.secondary)
                                    .lineLimit(1)
                                    .truncationMode(.middle)
                            }
                            Spacer()
                            Button("Show in Finder") {
                                NSWorkspace.shared.open(appState.transferManager.downloadDirectory)
                            }
                            .buttonStyle(.bordered)
                            .controlSize(.small)
                        }
                        .padding(10)
                        .background(Color(nsColor: .controlBackgroundColor).opacity(0.6))
                        .cornerRadius(10)
                    }

                    // Section 4: Extensible Architecture for Future Feature Toggles
                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            Image(systemName: "slider.horizontal.3")
                                .foregroundColor(.secondary)
                                .font(.caption)
                            Text("Extensible Architecture")
                                .font(.system(size: 11, weight: .semibold))
                                .foregroundColor(.secondary)
                        }

                        HStack {
                            Text("All future toggles and capability flags dynamically mount in this central settings view.")
                                .font(.system(size: 10))
                                .foregroundColor(.secondary)
                            Spacer()
                        }
                        .padding(10)
                        .background(Color(nsColor: .controlBackgroundColor).opacity(0.3))
                        .cornerRadius(10)
                    }

                    // Section 5: About & System Info
                    VStack(alignment: .leading, spacing: 6) {
                        HStack {
                            Text("About DexoLink")
                                .font(.system(size: 11, weight: .semibold))
                                .foregroundColor(.secondary)
                            Spacer()
                            Text("v4.0.1")
                                .font(.system(size: 10, weight: .bold))
                                .foregroundColor(.cyan)
                        }

                        HStack {
                            Text("Port 52840 (TCP) • Port 52841 (UDP Beacon)")
                                .font(.system(size: 9))
                                .foregroundColor(.secondary)
                            Spacer()
                            Text("Developed by Rahul Mahaseth")
                                .font(.system(size: 9))
                                .foregroundColor(.secondary)
                        }
                    }
                    .padding(10)
                    .background(Color(nsColor: .controlBackgroundColor).opacity(0.3))
                    .cornerRadius(10)
                }
                .padding(12)
            }
        }
        .frame(width: 360, height: 500)
        .sheet(isPresented: $showHandoffSheet) {
            HandoffSheetView(appState: appState, isPresented: $showHandoffSheet)
        }
        .sheet(isPresented: $showHistorySheet) {
            HistorySheetView(appState: appState, isPresented: $showHistorySheet, selectedTab: historyInitialTab)
        }
    }
}




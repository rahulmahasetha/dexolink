import SwiftUI
import Networking
import Crypto

struct MenuBarView: View {
    @ObservedObject var appState: AppState

    var body: some View {
        VStack(spacing: 0) {
            // Header
            HStack {
                Image(systemName: "antenna.radiowaves.left.and.right")
                    .foregroundColor(.accentColor)
                Text("DexoLink")
                    .font(.headline)
                Spacer()
                Button(action: {
                    NSApplication.shared.terminate(nil)
                }) {
                    Image(systemName: "power")
                        .foregroundColor(.secondary)
                }
                .buttonStyle(.plain)
                .help("Quit DexoLink")
            }
            .padding(12)
            .background(Color(nsColor: .windowBackgroundColor))

            Divider()

            // Network Details Banner
            HStack(spacing: 6) {
                Image(systemName: "network")
                    .foregroundColor(.accentColor)
                    .font(.caption)
                Text("IP: \(appState.localIP) • Port: \(appState.discoveryService.listeningPort)")
                    .font(.caption)
                    .fontWeight(.medium)
                Spacer()
                Button("Copy") {
                    let pb = NSPasteboard.general
                    pb.clearContents()
                    pb.setString("\(appState.localIP):\(appState.discoveryService.listeningPort)", forType: .string)
                }
                .buttonStyle(.plain)
                .font(.caption2)
                .foregroundColor(.accentColor)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(Color.accentColor.opacity(0.08))

            Divider()

            // Pairing PIN Banner if Active
            if let pin = appState.pairingPin {
                HStack(spacing: 10) {
                    Image(systemName: "lock.shield.fill")
                        .foregroundColor(.purple)
                        .font(.title3)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Pairing Code: \(pin)")
                            .font(.subheadline)
                            .fontWeight(.bold)
                            .foregroundColor(.purple)
                        Text("Confirm this 6-digit code on Android device")
                            .font(.caption2)
                            .foregroundColor(.secondary)
                    }
                    Spacer()
                }
                .padding(10)
                .background(Color.purple.opacity(0.12))
                .cornerRadius(8)
                .padding(8)

                Divider()
            }

            Divider()

            // Active Connection Card (if connected)
            if isConnected {
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Image(systemName: "candybarphone")
                            .font(.title2)
                            .foregroundColor(.green)

                        VStack(alignment: .leading, spacing: 2) {
                            Text(appState.connectedDeviceName ?? "Connected Device")
                                .font(.headline)
                            Text(appState.statusMessage)
                                .font(.caption2)
                                .foregroundColor(.secondary)
                        }

                        Spacer()

                        Button("Disconnect") {
                            appState.disconnect()
                        }
                        .buttonStyle(.bordered)
                        .controlSize(.small)
                    }

                    // Real-time Automatic Sync Banner
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
                                Text("Copy text on either device to sync instantly")
                                    .font(.caption2)
                                    .foregroundColor(.secondary)
                            }
                        }

                        Spacer()

                        Button(action: {
                            appState.sendPing()
                        }) {
                            Image(systemName: "bolt.fill")
                        }
                        .buttonStyle(.bordered)
                        .controlSize(.small)
                        .help("Test Ping")
                    }
                    .padding(8)
                    .background(Color.green.opacity(0.12))
                    .cornerRadius(8)
                }
                .padding(12)
                .background(Color.green.opacity(0.08))
                .cornerRadius(10)
                .padding(8)

                Divider()
            }

            // Discovered Devices Section
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Text("Nearby Devices")
                        .font(.caption)
                        .fontWeight(.semibold)
                        .foregroundColor(.secondary)
                    Spacer()
                    if appState.isDiscovering {
                        ProgressView()
                            .scaleEffect(0.6)
                    }
                }
                .padding(.horizontal, 12)
                .padding(.top, 8)

                if appState.discoveredDevices.isEmpty {
                    VStack(spacing: 6) {
                        Image(systemName: "wifi.slash")
                            .font(.title3)
                            .foregroundColor(.secondary)
                        Text(appState.isDiscovering ? "Searching for Android devices..." : "Discovery idle")
                            .font(.caption)
                            .foregroundColor(.secondary)
                        Text("Ensure your Android phone is on the same Wi-Fi with DexoLink open.")
                            .font(.caption2)
                            .foregroundColor(.secondary.opacity(0.8))
                            .multilineTextAlignment(.center)
                            .padding(.horizontal)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .padding()
                } else {
                    ScrollView {
                        VStack(spacing: 4) {
                            ForEach(appState.discoveredDevices) { device in
                                DeviceRow(device: device, appState: appState)
                                    .padding(.horizontal, 8)
                                    .padding(.vertical, 2)
                            }
                        }
                        .padding(.vertical, 4)
                    }
                }
            }

            Divider()

            // Footer
            HStack {
                Circle()
                    .fill(statusColor)
                    .frame(width: 8, height: 8)

                Text(footerStatusText)
                    .font(.caption2)
                    .foregroundColor(.secondary)

                Spacer()

                Button("Refresh") {
                    appState.discoveryService.stopBrowsing()
                    appState.discoveryService.startBrowsing()
                }
                .buttonStyle(.plain)
                .font(.caption2)
                .foregroundColor(.accentColor)
            }
            .padding(10)
            .background(Color(nsColor: .windowBackgroundColor))
        }
        .frame(width: 320, height: 420)
    }

    private var isConnected: Bool {
        if case .connected = appState.connectionState { return true }
        if case .authenticated = appState.connectionState { return true }
        return appState.connectedDeviceName != nil
    }

    private var statusColor: Color {
        if isConnected { return .green }
        if case .connecting = appState.connectionState { return .orange }
        if appState.isDiscovering { return .blue }
        return .gray
    }

    private var footerStatusText: String {
        if isConnected {
            return "Connected to \(appState.connectedDeviceName ?? "Android")"
        }
        if case .connecting = appState.connectionState {
            return "Connecting..."
        }
        return appState.isDiscovering ? "Scanning on Wi-Fi" : "Idle"
    }
}

struct DeviceRow: View {
    let device: DiscoveredDevice
    @ObservedObject var appState: AppState

    var isTrusted: Bool {
        return device.isPaired ||
               TrustStore.shared.isTrusted(deviceId: device.id) ||
               TrustStore.shared.getTrustedDevices().contains(where: { $0.deviceName == device.name })
    }

    var body: some View {
        HStack {
            Image(systemName: "candybarphone")
                .font(.title3)
                .foregroundColor(isTrusted ? .green : .accentColor)

            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 4) {
                    Text(device.name)
                        .font(.subheadline)
                        .lineLimit(1)
                    if isTrusted {
                        Text("★ Trusted")
                            .font(.system(size: 9, weight: .bold))
                            .foregroundColor(.green)
                            .padding(.horizontal, 4)
                            .padding(.vertical, 1)
                            .background(Color.green.opacity(0.15))
                            .cornerRadius(4)
                    }
                }
                Text(deviceSubtitle)
                    .font(.caption2)
                    .foregroundColor(.secondary)
            }

            Spacer()

            Button(isTrusted ? "Connect" : "Pair & Connect") {
                appState.connect(to: device)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.small)
        }
        .padding(.vertical, 6)
        .padding(.horizontal, 10)
        .background(
            RoundedRectangle(cornerRadius: 8)
                .fill(Color(nsColor: .controlBackgroundColor))
        )
    }

    private var deviceSubtitle: String {
        if case .service(let name, _, _, _) = device.endpoint {
            return name
        }
        return "Discovered on Wi-Fi"
    }
}

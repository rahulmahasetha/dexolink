import SwiftUI
import Networking
import Crypto
import Core
import CoreImage.CIFilterBuiltins

// MARK: - DexoTheme (Adaptive Native macOS Light/Dark Aesthetic)
public struct DexoTheme {
    private static func dynamic(light: (CGFloat, CGFloat, CGFloat, CGFloat), dark: (CGFloat, CGFloat, CGFloat, CGFloat)) -> Color {
        Color(nsColor: NSColor(name: nil) { appearance in
            let match = appearance.bestMatch(from: [.darkAqua, .aqua])
            let isDark = match == .darkAqua
            let c = isDark ? dark : light
            return NSColor(calibratedRed: c.0, green: c.1, blue: c.2, alpha: c.3)
        })
    }

    // Windows & Containers
    // Light: Clean, soft, eye-friendly light off-white #F6F8FA. Dark: Refined macOS slate #16181D
    public static let background = dynamic(
        light: (0.965, 0.973, 0.980, 1.0),
        dark:  (0.086, 0.094, 0.114, 1.0)
    )

    // Card surfaces
    // Light: Pure crisp white #FFFFFF. Dark: Elevated card surface #1F232B
    public static let cardBackground = dynamic(
        light: (1.0, 1.0, 1.0, 1.0),
        dark:  (0.122, 0.137, 0.169, 1.0)
    )

    // Inner containers, search bars, pill backgrounds
    // Light: Soft subtle container grey #EEF2F6. Dark: Container #272C37
    public static let innerBackground = dynamic(
        light: (0.933, 0.949, 0.965, 1.0),
        dark:  (0.153, 0.173, 0.216, 1.0)
    )

    // Sidebar
    // Light: Soft clean sidebar #EFF2F6. Dark: Dark sidebar #181B22
    public static let sidebarBackground = dynamic(
        light: (0.937, 0.949, 0.965, 1.0),
        dark:  (0.094, 0.106, 0.133, 1.0)
    )

    // Borders
    // Light: Soft crisp border #E2E8F0. Dark: Crisp border #2F3644
    public static let border = dynamic(
        light: (0.886, 0.910, 0.941, 1.0),
        dark:  (0.184, 0.212, 0.267, 1.0)
    )
    public static let borderSubtle = dynamic(
        light: (0.922, 0.941, 0.961, 1.0),
        dark:  (0.145, 0.165, 0.212, 1.0)
    )

    // Accents
    public static let blue = Color(red: 0.145, green: 0.388, blue: 0.922)           // #2563EB
    public static let blueSubtle = dynamic(
        light: (0.859, 0.918, 0.996, 1.0), // #DBEAFE
        dark:  (0.145, 0.388, 0.922, 0.18)
    )
    public static let green = dynamic(
        light: (0.086, 0.639, 0.290, 1.0), // #16A34A (high contrast green in light mode)
        dark:  (0.133, 0.773, 0.369, 1.0)  // #22C55E
    )
    public static let greenSubtle = dynamic(
        light: (0.863, 0.969, 0.902, 1.0), // #DCFCE7
        dark:  (0.133, 0.773, 0.369, 0.18)
    )
    public static let red = Color(red: 0.937, green: 0.267, blue: 0.267)            // #EF4444 (Disconnect Red)
    public static let redSubtle = dynamic(
        light: (0.996, 0.886, 0.886, 1.0), // #FEE2E2
        dark:  (0.937, 0.267, 0.267, 0.18)
    )
    public static let orange = dynamic(
        light: (0.851, 0.467, 0.024, 1.0), // #D97706
        dark:  (0.961, 0.620, 0.043, 1.0)  // #F59E0B
    )
    public static let purple = dynamic(
        light: (0.486, 0.227, 0.929, 1.0), // #7C3AED
        dark:  (0.659, 0.333, 0.969, 1.0)  // #A855F7
    )

    // Typography
    // Light: Deep slate navy #0F172A. Dark: Pure crisp white #F8FAFC
    public static let textPrimary = dynamic(
        light: (0.059, 0.090, 0.165, 1.0), // #0F172A
        dark:  (0.973, 0.980, 0.988, 1.0)  // #F8FAFC
    )
    // Light: Cool slate #475569. Dark: Muted silver slate #94A3B8
    public static let textSecondary = dynamic(
        light: (0.278, 0.333, 0.412, 1.0), // #475569
        dark:  (0.580, 0.639, 0.722, 1.0)  // #94A3B8
    )
    // Light: Light slate #64748B. Dark: Medium muted #64748B
    public static let textMuted = dynamic(
        light: (0.392, 0.455, 0.545, 1.0), // #64748B
        dark:  (0.392, 0.455, 0.545, 1.0)
    )
}

// MARK: - Navigation Screens & Tabs
enum AppScreen {
    case main
    case settings
}

public enum SettingsTab: String, CaseIterable, Identifiable {
    case general = "General"
    case devices = "Devices"
    case fileSharing = "File Sharing"
    case clipboard = "Clipboard"
    case network = "Network"
    case storage = "Storage"
    case advanced = "Advanced"
    case about = "About"

    public var id: String { rawValue }

    public var iconName: String {
        switch self {
        case .general: return "gearshape.fill"
        case .devices: return "laptopcomputer.and.iphone"
        case .fileSharing: return "folder.fill"
        case .clipboard: return "doc.on.clipboard.fill"
        case .network: return "network"
        case .storage: return "internaldrive.fill"
        case .advanced: return "slider.horizontal.3"
        case .about: return "info.circle.fill"
        }
    }
}

// MARK: - Native Traffic Lights Component
struct TrafficLightsView: View {
    var onClose: (() -> Void)? = nil

    var body: some View {
        HStack(spacing: 7) {
            Button(action: {
                if let onClose = onClose {
                    onClose()
                } else {
                    NSApplication.shared.keyWindow?.performClose(nil)
                }
            }) {
                Circle()
                    .fill(Color(red: 254/255.0, green: 95/255.0, blue: 86/255.0)) // #FE5F56
                    .frame(width: 11, height: 11)
            }
            .buttonStyle(.plain)

            Circle()
                .fill(Color(red: 255/255.0, green: 189/255.0, blue: 45/255.0)) // #FFBD2D
                .frame(width: 11, height: 11)

            Circle()
                .fill(Color(red: 38/255.0, green: 201/255.0, blue: 64/255.0)) // #26C940
                .frame(width: 11, height: 11)
        }
    }
}

// MARK: - Helper Formatting Functions
func fileTypeIcon(for name: String) -> String {
    let ext = (name as NSString).pathExtension.lowercased()
    switch ext {
    case "pdf": return "doc.text.fill"
    case "png", "jpg", "jpeg", "heic", "webp", "gif": return "photo.fill"
    case "csv", "xlsx", "xls", "numbers": return "tablecells.fill"
    case "zip", "tar", "gz", "rar", "7z": return "archivebox.fill"
    case "mp4", "mov", "m4v", "avi", "mkv": return "film.fill"
    case "mp3", "m4a", "wav", "aac", "flac": return "music.note"
    case "swift", "kt", "go", "py", "js", "ts", "html", "css", "json": return "chevron.left.forwardslash.chevron.right"
    default: return "doc.fill"
    }
}

func fileTypeColor(for name: String) -> Color {
    let ext = (name as NSString).pathExtension.lowercased()
    switch ext {
    case "pdf": return DexoTheme.red
    case "png", "jpg", "jpeg", "heic", "webp", "gif": return DexoTheme.purple
    case "csv", "xlsx", "xls", "numbers": return DexoTheme.green
    case "zip", "tar", "gz", "rar", "7z": return DexoTheme.orange
    case "mp4", "mov", "m4v", "avi", "mkv": return Color.pink
    case "mp3", "m4a", "wav", "aac", "flac": return Color.cyan
    default: return DexoTheme.blue
    }
}

func formattedTimeAgo(from date: Date) -> String {
    let seconds = Int(-date.timeIntervalSinceNow)
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

// MARK: - Recent Transfer Row
struct RecentTransferRow: View {
    let item: TransferItem
    @ObservedObject var appState: AppState

    var body: some View {
        HStack(spacing: 8) {
            ZStack {
                RoundedRectangle(cornerRadius: 6)
                    .fill(fileTypeColor(for: item.name).opacity(0.2))
                    .frame(width: 28, height: 28)
                Image(systemName: fileTypeIcon(for: item.name))
                    .font(.system(size: 12))
                    .foregroundColor(fileTypeColor(for: item.name))
            }

            VStack(alignment: .leading, spacing: 1) {
                Text(item.name)
                    .font(.system(size: 12, weight: .medium))
                    .foregroundColor(DexoTheme.textPrimary)
                    .lineLimit(1)
                Text(subtitleText)
                    .font(.system(size: 9))
                    .foregroundColor(DexoTheme.textSecondary)
            }

            Spacer()

            Image(systemName: "checkmark.circle.fill")
                .foregroundColor(DexoTheme.green)
                .font(.system(size: 12))

            Menu {
                if let url = item.localURL {
                    Button("Show in Finder") {
                        NSWorkspace.shared.activateFileViewerSelecting([url])
                    }
                }
                Button("Delete from History") {
                    appState.deleteTransferHistoryItem(id: item.id)
                }
            } label: {
                Image(systemName: "ellipsis")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundColor(DexoTheme.textSecondary)
            }
            .menuStyle(.borderlessButton)
            .frame(width: 16)
        }
        .padding(8)
        .background(DexoTheme.innerBackground.opacity(0.6))
        .cornerRadius(8)
    }

    private var subtitleText: String {
        let sizeStr = ByteCountFormatter.string(fromByteCount: item.size, countStyle: .file)
        let dirStr = item.isOutgoing ? "Sent" : "Received"
        let timeStr = formattedTimeAgo(from: item.startTime)
        return "\(sizeStr) • \(dirStr) • \(timeStr)"
    }
}

// MARK: - MenuBarView
struct MenuBarView: View {
    @ObservedObject var appState: AppState
    @State private var currentScreen: AppScreen = .main
    @State private var selectedSettingsTab: SettingsTab = .general

    @State private var showManualIPSheet = false
    @State private var showHistorySheet = false
    @State private var historyInitialTab = 0
    @State private var manualIP = ""
    @State private var manualPort = "52840"
    @State private var isTargetedForDrop = false
    @State private var showHandoffSheet = false
    @State private var showSettingsSheet = false
    @State private var showQRCodeSheet = false
    @State private var connectionQRCode: NSImage?
    @AppStorage("autoReconnect") private var autoReconnect = true
    @AppStorage("launchAtLogin") private var launchAtLogin = false

    var body: some View {
        ZStack {
            DexoTheme.background.ignoresSafeArea()

            if currentScreen == .main {
                mainDashboardView
            } else {
                SettingsDetailView(
                    appState: appState,
                    selectedTab: $selectedSettingsTab,
                    onClose: {
                        withAnimation(.easeInOut(duration: 0.18)) {
                            currentScreen = .main
                        }
                    }
                )
            }
        }
        .frame(width: 580, height: 580)
        .preferredColorScheme(appState.appearanceMode.colorScheme)
        .sheet(isPresented: $showQRCodeSheet) {
            VStack(spacing: 14) {
                Text("Connect a Mobile Device")
                    .font(.headline)
                    .foregroundColor(DexoTheme.textPrimary)
                Text("Scan this code with DexoLink on Android or iPhone.")
                    .font(.caption)
                    .foregroundColor(DexoTheme.textSecondary)
                if let connectionQRCode {
                    Image(nsImage: connectionQRCode)
                        .interpolation(.none)
                        .resizable()
                        .frame(width: 240, height: 240)
                        .padding(12)
                        .background(.white)
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                    ShareLink(item: Image(nsImage: connectionQRCode), preview: SharePreview("DexoLink Connection QR")) {
                        Label("Share QR Image", systemImage: "square.and.arrow.up")
                    }
                } else {
                    Text("Connect this Mac to a network and try again.")
                        .foregroundColor(DexoTheme.textSecondary)
                }
                Button("Close") { showQRCodeSheet = false }
                    .keyboardShortcut(.cancelAction)
            }
            .padding(24)
            .frame(minWidth: 320, minHeight: 360)
            .background(DexoTheme.background)
        }
        .sheet(isPresented: $showManualIPSheet) {
            ManualIPSheet(
                appState: appState,
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

    // MARK: - Main Dashboard View (Matching Image 2 Left Window)
    private var mainDashboardView: some View {
        VStack(spacing: 0) {
            // MARK: Top Bar
            HStack(spacing: 10) {
                TrafficLightsView(onClose: {
                    NSApplication.shared.keyWindow?.performClose(nil)
                })

                HStack(spacing: 6) {
                    ZStack {
                        Circle()
                            .fill(DexoTheme.blue)
                            .frame(width: 22, height: 22)
                        Image(systemName: "link")
                            .font(.system(size: 11, weight: .bold))
                            .foregroundColor(.white)
                    }

                    Text("DexoLink")
                        .font(.system(size: 14, weight: .bold))
                        .foregroundColor(DexoTheme.textPrimary)
                }

                Spacer()

                HStack(spacing: 12) {
                    Button {
                        generateConnectionQRCode()
                        showQRCodeSheet = true
                    } label: {
                        Image(systemName: "qrcode")
                            .font(.system(size: 13))
                            .foregroundColor(DexoTheme.textSecondary)
                    }
                    .buttonStyle(.plain)
                    .help("Show Connection QR Code")

                    Button {
                        withAnimation(.easeInOut(duration: 0.18)) {
                            currentScreen = .settings
                        }
                    } label: {
                        Image(systemName: "gearshape")
                            .font(.system(size: 13))
                            .foregroundColor(DexoTheme.textSecondary)
                    }
                    .buttonStyle(.plain)
                    .help("Settings & Preferences")

                    Button {
                        historyInitialTab = 0
                        showHistorySheet = true
                    } label: {
                        Image(systemName: "clock.arrow.circlepath")
                            .font(.system(size: 13))
                            .foregroundColor(DexoTheme.textSecondary)
                    }
                    .buttonStyle(.plain)
                    .help("Transfer History")

                    Button {
                        showManualIPSheet = true
                    } label: {
                        Image(systemName: "link")
                            .font(.system(size: 13))
                            .foregroundColor(DexoTheme.textSecondary)
                    }
                    .buttonStyle(.plain)
                    .help("Direct IP Connection")

                    Button {
                        NSApplication.shared.terminate(nil)
                    } label: {
                        Image(systemName: "power")
                            .font(.system(size: 13))
                            .foregroundColor(DexoTheme.textSecondary)
                    }
                    .buttonStyle(.plain)
                    .help("Quit DexoLink")
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .background(DexoTheme.background)

            Divider()
                .background(DexoTheme.border)

            ScrollView(showsIndicators: false) {
                VStack(spacing: 14) {
                    // MARK: Master Switch Card
                    HStack(spacing: 12) {
                        ZStack {
                            Circle()
                                .fill(appState.isEnabled ? DexoTheme.blue.opacity(0.18) : DexoTheme.innerBackground)
                                .frame(width: 38, height: 38)
                            Image(systemName: appState.isEnabled ? "antenna.radiowaves.left.and.right" : "antenna.radiowaves.left.and.right.slash")
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundColor(appState.isEnabled ? DexoTheme.blue : DexoTheme.textSecondary)
                        }

                        VStack(alignment: .leading, spacing: 3) {
                            HStack(spacing: 6) {
                                Text("DexoLink")
                                    .font(.system(size: 14, weight: .bold))
                                    .foregroundColor(DexoTheme.textPrimary)

                                Text(appState.isEnabled ? "ON" : "OFF")
                                    .font(.system(size: 9, weight: .heavy))
                                    .foregroundColor(appState.isEnabled ? .white : DexoTheme.textSecondary)
                                    .padding(.horizontal, 6)
                                    .padding(.vertical, 2)
                                    .background(
                                        Capsule().fill(appState.isEnabled ? DexoTheme.blue : DexoTheme.innerBackground)
                                    )
                            }

                            Text(masterSubtitleText)
                                .font(.caption2)
                                .foregroundColor(DexoTheme.textSecondary)
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
                    }
                    .padding(12)
                    .background(DexoTheme.cardBackground)
                    .cornerRadius(12)
                    .overlay(
                        RoundedRectangle(cornerRadius: 12)
                            .stroke(DexoTheme.border, lineWidth: 1)
                    )

                    if !appState.isEnabled {
                        // DexoLink Off Banner
                        VStack(spacing: 12) {
                            ZStack {
                                Circle()
                                    .fill(DexoTheme.innerBackground)
                                    .frame(width: 54, height: 54)
                                Image(systemName: "antenna.radiowaves.left.and.right.slash")
                                    .font(.system(size: 24))
                                    .foregroundColor(DexoTheme.textSecondary)
                            }
                            .padding(.top, 16)

                            VStack(spacing: 4) {
                                Text("DexoLink is Turned Off")
                                    .font(.system(size: 14, weight: .bold))
                                    .foregroundColor(DexoTheme.textPrimary)

                                Text("All background discovery, sockets, and sync are paused to ensure 0% CPU and battery usage.")
                                    .font(.caption)
                                    .foregroundColor(DexoTheme.textSecondary)
                                    .multilineTextAlignment(.center)
                                    .padding(.horizontal, 20)
                            }

                            Button(action: {
                                appState.setMasterEnabled(true)
                            }) {
                                HStack(spacing: 6) {
                                    Image(systemName: "bolt.fill")
                                    Text("Turn On DexoLink")
                                }
                                .font(.system(size: 12, weight: .semibold))
                                .foregroundColor(.white)
                                .padding(.horizontal, 14)
                                .padding(.vertical, 6)
                                .background(DexoTheme.blue)
                                .cornerRadius(8)
                            }
                            .buttonStyle(.plain)
                            .padding(.top, 4)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 16)
                    } else {
                        // MARK: Incoming Pairing Request
                        if let req = appState.incomingPairingRequest {
                            VStack(alignment: .leading, spacing: 8) {
                                HStack(spacing: 8) {
                                    Image(systemName: "lock.shield.fill")
                                        .foregroundColor(DexoTheme.purple)
                                        .font(.title3)

                                    VStack(alignment: .leading, spacing: 2) {
                                        Text("Pairing Request")
                                            .font(.subheadline)
                                            .fontWeight(.bold)
                                            .foregroundColor(DexoTheme.textPrimary)
                                        Text("\(req.peerName) wants to pair with this Mac")
                                            .font(.caption2)
                                            .foregroundColor(DexoTheme.textSecondary)
                                    }
                                    Spacer()
                                }

                                if !req.pin.isEmpty {
                                    HStack {
                                        Text("Code: \(req.pin)")
                                            .font(.system(size: 13, weight: .bold, design: .monospaced))
                                            .padding(.vertical, 3)
                                            .padding(.horizontal, 8)
                                            .background(DexoTheme.purple.opacity(0.18))
                                            .foregroundColor(.white)
                                            .cornerRadius(6)
                                        Spacer()
                                    }
                                }

                                HStack(spacing: 10) {
                                    Button("Reject") {
                                        appState.rejectIncomingPairing()
                                    }
                                    .font(.system(size: 11, weight: .semibold))
                                    .foregroundColor(.white)
                                    .padding(.horizontal, 12)
                                    .padding(.vertical, 5)
                                    .background(DexoTheme.red)
                                    .cornerRadius(6)
                                    .buttonStyle(.plain)

                                    Spacer()

                                    Button("Accept") {
                                        appState.acceptIncomingPairing()
                                    }
                                    .font(.system(size: 11, weight: .semibold))
                                    .foregroundColor(.white)
                                    .padding(.horizontal, 12)
                                    .padding(.vertical, 5)
                                    .background(DexoTheme.green)
                                    .cornerRadius(6)
                                    .buttonStyle(.plain)
                                }
                            }
                            .padding(12)
                            .background(DexoTheme.cardBackground)
                            .cornerRadius(12)
                            .overlay(
                                RoundedRectangle(cornerRadius: 12)
                                    .stroke(DexoTheme.purple.opacity(0.5), lineWidth: 1)
                            )
                        }

                        // MARK: Continuity Activity
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

                        // MARK: - PAIRED DEVICE Section
                        VStack(alignment: .leading, spacing: 6) {
                            Text("PAIRED DEVICE")
                                .font(.system(size: 11, weight: .bold))
                                .foregroundColor(DexoTheme.textSecondary)

                            let trustedDevices = TrustStore.shared.getTrustedDevices()
                            if !trustedDevices.isEmpty {
                                ForEach(trustedDevices, id: \.deviceId) { paired in
                                    let isThisConnected = isConnected && (appState.connectedDeviceId == paired.deviceId || appState.connectedDeviceName == paired.deviceName)

                                    HStack(spacing: 10) {
                                        ZStack {
                                            RoundedRectangle(cornerRadius: 8)
                                                .fill(DexoTheme.innerBackground)
                                                .frame(width: 36, height: 36)
                                            Image(systemName: deviceSystemIcon(for: paired.deviceName))
                                                .font(.system(size: 15))
                                                .foregroundColor(isThisConnected ? DexoTheme.blue : DexoTheme.textSecondary)
                                        }

                                        VStack(alignment: .leading, spacing: 2) {
                                            HStack(spacing: 6) {
                                                Text(paired.deviceName)
                                                    .font(.system(size: 13, weight: .semibold))
                                                    .foregroundColor(DexoTheme.textPrimary)
                                                    .lineLimit(1)
                                                    .truncationMode(.tail)

                                                Circle()
                                                    .fill(isThisConnected ? DexoTheme.green : DexoTheme.textSecondary.opacity(0.5))
                                                    .frame(width: 6, height: 6)

                                                Text(isThisConnected ? "Online" : "Offline")
                                                    .font(.system(size: 10, weight: .medium))
                                                    .foregroundColor(isThisConnected ? DexoTheme.green : DexoTheme.textSecondary)
                                                    .lineLimit(1)
                                                    .fixedSize(horizontal: true, vertical: false)
                                            }

                                            Text(isThisConnected ? "Paired • Last seen: Just now" : "Paired • Disconnected")
                                                .font(.system(size: 10))
                                                .foregroundColor(DexoTheme.textSecondary)
                                                .lineLimit(1)
                                                .truncationMode(.tail)
                                        }

                                        Spacer(minLength: 4)

                                        HStack(spacing: 6) {
                                            if isThisConnected {
                                                Button(action: {
                                                    appState.disconnect()
                                                }) {
                                                    Text("Disconnect")
                                                        .font(.system(size: 11, weight: .semibold))
                                                        .foregroundColor(.white)
                                                        .lineLimit(1)
                                                        .fixedSize(horizontal: true, vertical: false)
                                                        .padding(.horizontal, 10)
                                                        .padding(.vertical, 5)
                                                        .background(DexoTheme.red)
                                                        .cornerRadius(6)
                                                }
                                                .buttonStyle(.plain)
                                                .help("Disconnect active session while preserving pairing")
                                            } else {
                                                Button(action: {
                                                    appState.connectToPairedDevice(deviceId: paired.deviceId)
                                                }) {
                                                    Text("Connect")
                                                        .font(.system(size: 11, weight: .semibold))
                                                        .foregroundColor(.white)
                                                        .lineLimit(1)
                                                        .fixedSize(horizontal: true, vertical: false)
                                                        .padding(.horizontal, 10)
                                                        .padding(.vertical, 5)
                                                        .background(DexoTheme.blue)
                                                        .cornerRadius(6)
                                                }
                                                .buttonStyle(.plain)
                                                .help("Reconnect to this paired device immediately")
                                            }

                                            Button(action: {
                                                appState.unpair(deviceId: paired.deviceId)
                                            }) {
                                                Text("Unpair")
                                                    .font(.system(size: 11, weight: .medium))
                                                    .foregroundColor(DexoTheme.textSecondary)
                                                    .lineLimit(1)
                                                    .fixedSize(horizontal: true, vertical: false)
                                                    .padding(.horizontal, 8)
                                                    .padding(.vertical, 5)
                                                    .background(DexoTheme.innerBackground)
                                                    .overlay(
                                                        RoundedRectangle(cornerRadius: 6)
                                                            .stroke(DexoTheme.border, lineWidth: 1)
                                                    )
                                                    .cornerRadius(6)
                                            }
                                            .buttonStyle(.plain)
                                            .help("Unpair this specific device")

                                            Button(action: {
                                                selectedSettingsTab = .devices
                                                withAnimation(.easeInOut(duration: 0.18)) {
                                                    currentScreen = .settings
                                                }
                                            }) {
                                                Image(systemName: "chevron.right")
                                                    .font(.system(size: 11, weight: .semibold))
                                                    .foregroundColor(DexoTheme.textSecondary)
                                                    .padding(4)
                                            }
                                            .buttonStyle(.plain)
                                        }
                                    }
                                    .padding(10)
                                    .background(DexoTheme.cardBackground)
                                    .cornerRadius(10)
                                    .overlay(
                                        RoundedRectangle(cornerRadius: 10)
                                            .stroke(DexoTheme.border, lineWidth: 1)
                                    )
                                }
                            } else {
                                HStack {
                                    ZStack {
                                        RoundedRectangle(cornerRadius: 8)
                                            .fill(DexoTheme.innerBackground)
                                            .frame(width: 36, height: 36)
                                        Image(systemName: "laptopcomputer.and.iphone")
                                            .foregroundColor(DexoTheme.textSecondary)
                                    }

                                    VStack(alignment: .leading, spacing: 2) {
                                        Text("No paired device")
                                            .font(.system(size: 12, weight: .semibold))
                                            .foregroundColor(DexoTheme.textPrimary)
                                        Text("Discover devices on Wi-Fi below or scan QR code")
                                            .font(.system(size: 10))
                                            .foregroundColor(DexoTheme.textSecondary)
                                    }

                                    Spacer()

                                    Button("Scan QR") {
                                        generateConnectionQRCode()
                                        showQRCodeSheet = true
                                    }
                                    .font(.system(size: 10, weight: .semibold))
                                    .foregroundColor(.white)
                                    .padding(.horizontal, 10)
                                    .padding(.vertical, 4)
                                    .background(DexoTheme.blue)
                                    .cornerRadius(6)
                                    .buttonStyle(.plain)
                                }
                                .padding(10)
                                .background(DexoTheme.cardBackground)
                                .cornerRadius(10)
                                .overlay(
                                    RoundedRectangle(cornerRadius: 10)
                                        .stroke(DexoTheme.border, lineWidth: 1)
                                )
                            }
                        }

                        // MARK: - NEARBY DEVICES Section
                        VStack(alignment: .leading, spacing: 6) {
                            HStack {
                                Text("NEARBY DEVICES")
                                    .font(.system(size: 11, weight: .bold))
                                    .foregroundColor(DexoTheme.textSecondary)

                                Spacer()

                                Button("Refresh") {
                                    appState.refreshDiscovery()
                                }
                                .font(.system(size: 11, weight: .medium))
                                .foregroundColor(DexoTheme.blue)
                                .buttonStyle(.plain)
                            }

                            VStack(spacing: 6) {
                                if !appState.discoveredDevices.isEmpty {
                                    ForEach(appState.discoveredDevices) { device in
                                        DeviceRow(device: device, appState: appState)
                                    }
                                } else {
                                    HStack(spacing: 8) {
                                        if appState.isActivelyScanning {
                                            ProgressView()
                                                .scaleEffect(0.6)
                                                .frame(width: 16, height: 16)
                                        }
                                        Text(appState.isActivelyScanning ? "Searching on local Wi-Fi..." : "No other devices found nearby")
                                            .font(.system(size: 11))
                                            .foregroundColor(DexoTheme.textSecondary)
                                        Spacer()
                                    }
                                    .padding(.vertical, 4)
                                    .padding(.horizontal, 6)
                                }

                                // Local device row
                                HStack(spacing: 10) {
                                    ZStack {
                                        RoundedRectangle(cornerRadius: 8)
                                            .fill(DexoTheme.innerBackground)
                                            .frame(width: 32, height: 32)
                                        Image(systemName: "laptopcomputer")
                                            .font(.system(size: 13))
                                            .foregroundColor(DexoTheme.textSecondary)
                                    }

                                    VStack(alignment: .leading, spacing: 1) {
                                        Text(Host.current().localizedName ?? "Rahul's MacBook")
                                            .font(.system(size: 12, weight: .semibold))
                                            .foregroundColor(DexoTheme.textPrimary)
                                        Text("Offline • This device")
                                            .font(.system(size: 10))
                                            .foregroundColor(DexoTheme.textSecondary)
                                    }

                                    Spacer()

                                    Text("Local")
                                        .font(.system(size: 9, weight: .bold))
                                        .foregroundColor(DexoTheme.textSecondary)
                                        .padding(.horizontal, 6)
                                        .padding(.vertical, 2)
                                        .background(DexoTheme.innerBackground)
                                        .cornerRadius(4)
                                }
                                .padding(8)
                                .background(DexoTheme.innerBackground.opacity(0.5))
                                .cornerRadius(8)
                            }
                            .padding(8)
                            .background(DexoTheme.cardBackground)
                            .cornerRadius(10)
                            .overlay(
                                RoundedRectangle(cornerRadius: 10)
                                    .stroke(DexoTheme.border, lineWidth: 1)
                            )
                        }

                        // MARK: - SEND FILES Section
                        VStack(alignment: .leading, spacing: 6) {
                            Text("SEND FILES")
                                .font(.system(size: 11, weight: .bold))
                                .foregroundColor(DexoTheme.textSecondary)

                            ZStack {
                                RoundedRectangle(cornerRadius: 10)
                                    .stroke(isTargetedForDrop ? DexoTheme.blue : DexoTheme.border, style: StrokeStyle(lineWidth: 1.5, dash: [4]))
                                    .background(isTargetedForDrop ? DexoTheme.blueSubtle : DexoTheme.cardBackground)
                                    .cornerRadius(10)

                                VStack(spacing: 8) {
                                    Image(systemName: "folder.fill")
                                        .font(.system(size: 32))
                                        .foregroundColor(DexoTheme.blue)

                                    VStack(spacing: 2) {
                                        Text(isConnected ? "Drag files here to send" : "Connect a device to send files")
                                            .font(.system(size: 13, weight: .semibold))
                                            .foregroundColor(DexoTheme.textPrimary)

                                        Text("Photos • PDFs • Documents • Downloads • Any file")
                                            .font(.system(size: 10))
                                            .foregroundColor(DexoTheme.textSecondary)
                                    }

                                    Button(action: {
                                        selectAndSendFile()
                                    }) {
                                        Text("Choose Files")
                                            .font(.system(size: 12, weight: .semibold))
                                            .foregroundColor(.white)
                                            .padding(.horizontal, 16)
                                            .padding(.vertical, 6)
                                            .background(DexoTheme.blue)
                                            .cornerRadius(14)
                                    }
                                    .buttonStyle(.plain)
                                }
                                .padding(.vertical, 16)
                            }
                            .onDrop(of: [.fileURL], isTargeted: $isTargetedForDrop) { providers in
                                handleFileDrop(providers: providers)
                            }

                            // Active Transfer Display
                            if let transfer = appState.activeTransfer {
                                let isPaused: Bool = {
                                    if case .paused = transfer.state { return true }
                                    return false
                                }()

                                VStack(alignment: .leading, spacing: 4) {
                                    HStack {
                                        Image(systemName: transfer.isOutgoing ? "arrow.up.circle.fill" : "arrow.down.circle.fill")
                                            .foregroundColor(transfer.isOutgoing ? DexoTheme.blue : DexoTheme.green)
                                        VStack(alignment: .leading, spacing: 1) {
                                            Text(transfer.name)
                                                .font(.caption)
                                                .fontWeight(.semibold)
                                                .foregroundColor(DexoTheme.textPrimary)
                                                .lineLimit(1)
                                            Text(isPaused ? "Paused" : (transfer.isOutgoing ? "Uploading to phone..." : "Downloading from phone..."))
                                                .font(.system(size: 9))
                                                .foregroundColor(isPaused ? DexoTheme.orange : DexoTheme.textSecondary)
                                        }

                                        Spacer()

                                        Button(action: {
                                            if isPaused {
                                                appState.unpauseTransfer(id: transfer.id)
                                            } else {
                                                appState.pauseTransfer(id: transfer.id)
                                            }
                                        }) {
                                            Image(systemName: isPaused ? "play.circle.fill" : "pause.circle.fill")
                                                .foregroundColor(isPaused ? DexoTheme.green : DexoTheme.orange)
                                        }
                                        .buttonStyle(.plain)

                                        Button(action: {
                                            appState.cancelTransfer(id: transfer.id)
                                        }) {
                                            Image(systemName: "xmark.circle.fill")
                                                .foregroundColor(DexoTheme.red)
                                        }
                                        .buttonStyle(.plain)
                                    }

                                    ProgressView(value: transfer.progress)
                                        .progressViewStyle(.linear)

                                    HStack {
                                        Text("\(formatBytes(transfer.bytesTransferred)) of \(formatBytes(transfer.size))")
                                            .font(.caption2)
                                            .foregroundColor(DexoTheme.textSecondary)
                                        Spacer()
                                        if transfer.speedBytesPerSec > 0 && !isPaused {
                                            Text("\(formatBytes(Int64(transfer.speedBytesPerSec)))/s")
                                                .font(.caption2)
                                                .foregroundColor(DexoTheme.textSecondary)
                                        }
                                        Text("\(Int(transfer.progress * 100))%")
                                            .font(.caption2)
                                            .fontWeight(.bold)
                                            .foregroundColor(isPaused ? DexoTheme.orange : .white)
                                    }
                                }
                                .padding(8)
                                .background(DexoTheme.innerBackground)
                                .cornerRadius(8)
                            }
                        }

                        // MARK: - RECENT TRANSFERS Section
                        VStack(alignment: .leading, spacing: 6) {
                            HStack {
                                Text("RECENT TRANSFERS")
                                    .font(.system(size: 11, weight: .bold))
                                    .foregroundColor(DexoTheme.textSecondary)

                                Spacer()

                                Button("See All") {
                                    historyInitialTab = 0
                                    showHistorySheet = true
                                }
                                .font(.system(size: 11, weight: .medium))
                                .foregroundColor(DexoTheme.blue)
                                .buttonStyle(.plain)
                            }

                            VStack(spacing: 4) {
                                if !appState.recentTransfers.isEmpty {
                                    ForEach(appState.recentTransfers.prefix(3)) { item in
                                        RecentTransferRow(item: item, appState: appState)
                                    }
                                } else {
                                    HStack {
                                        Text("No recent transfers • Sent and received files will appear here.")
                                            .font(.system(size: 10))
                                            .foregroundColor(DexoTheme.textSecondary)
                                        Spacer()
                                    }
                                    .padding(8)
                                }
                            }
                            .padding(8)
                            .background(DexoTheme.cardBackground)
                            .cornerRadius(10)
                            .overlay(
                                RoundedRectangle(cornerRadius: 10)
                                    .stroke(DexoTheme.border, lineWidth: 1)
                            )
                        }
                    }
                }
                .padding(14)
            }

            Divider()
                .background(DexoTheme.border)

            // MARK: Bottom Status Bar Footer
            HStack {
                Circle()
                    .fill(statusColor)
                    .frame(width: 8, height: 8)

                Text(footerStatusText)
                    .font(.system(size: 11))
                    .foregroundColor(DexoTheme.textSecondary)

                Spacer()

                Text("v4.0.3 • By Rahul Mahaseth")
                    .font(.system(size: 10, weight: .medium))
                    .foregroundColor(DexoTheme.textMuted)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(DexoTheme.sidebarBackground)
        }
    }

    private func deviceSystemIcon(for name: String) -> String {
        let lower = name.lowercased()
        if lower.contains("ipad") || lower.contains("tablet") {
            return "ipad"
        } else if lower.contains("mac") || lower.contains("book") {
            return "laptopcomputer"
        } else if lower.contains("pc") || lower.contains("windows") {
            return "desktopcomputer"
        } else {
            return "iphone"
        }
    }

    private func generateConnectionQRCode() {
        guard appState.localIP != "Unavailable", !appState.localIP.isEmpty else {
            connectionQRCode = nil
            return
        }
        var components = URLComponents()
        components.scheme = "dexolink"
        components.host = "connect"
        components.queryItems = [
            URLQueryItem(name: "host", value: appState.localIP),
            URLQueryItem(name: "port", value: String(appState.discoveryService.listeningPort)),
            URLQueryItem(name: "name", value: Host.current().localizedName ?? "Mac")
        ]
        guard let payload = components.string else {
            connectionQRCode = nil
            return
        }
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(payload.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 8, y: 8)) else {
            connectionQRCode = nil
            return
        }
        let representation = NSCIImageRep(ciImage: output)
        let image = NSImage(size: representation.size)
        image.addRepresentation(representation)
        connectionQRCode = image
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
            return DexoTheme.textSecondary
        }
        switch appState.connectionState {
        case .connected, .authenticated, .transferring:
            return DexoTheme.green
        case .connecting:
            return DexoTheme.orange
        case .disconnected:
            return DexoTheme.textSecondary
        case .failed:
            return DexoTheme.red
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
                    .foregroundColor(device.isOnline ? DexoTheme.blue : DexoTheme.textSecondary)
                    .font(.body)

                // Online/Offline status dot
                Circle()
                    .fill(device.isOnline ? DexoTheme.green : DexoTheme.textSecondary.opacity(0.5))
                    .frame(width: 6, height: 6)
                    .offset(x: 2, y: 2)
            }

            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 4) {
                    Text(device.name)
                        .font(.caption)
                        .fontWeight(.semibold)
                        .foregroundColor(device.isOnline ? .white : DexoTheme.textSecondary)

                    if isCurrentConnected {
                        Text("Connected")
                            .font(.system(size: 8, weight: .bold))
                            .foregroundColor(DexoTheme.green)
                            .padding(.horizontal, 4)
                            .padding(.vertical, 1)
                            .background(DexoTheme.greenSubtle)
                            .cornerRadius(4)
                    } else if isPaired {
                        Text("Paired")
                            .font(.system(size: 8, weight: .bold))
                            .foregroundColor(DexoTheme.blue)
                            .padding(.horizontal, 4)
                            .padding(.vertical, 1)
                            .background(DexoTheme.blueSubtle)
                            .cornerRadius(4)
                    } else if anotherDevicePaired {
                        Text("Blocked")
                            .font(.system(size: 8, weight: .bold))
                            .foregroundColor(DexoTheme.orange)
                            .padding(.horizontal, 4)
                            .padding(.vertical, 1)
                            .background(DexoTheme.orange.opacity(0.18))
                            .cornerRadius(4)
                    } else {
                        Text(device.isOnline ? "Available" : "Offline")
                            .font(.system(size: 8, weight: .bold))
                            .foregroundColor(device.isOnline ? DexoTheme.blue : DexoTheme.textSecondary)
                            .padding(.horizontal, 4)
                            .padding(.vertical, 1)
                            .background((device.isOnline ? DexoTheme.blueSubtle : DexoTheme.innerBackground))
                            .cornerRadius(4)
                    }
                }

                // Subtitle
                Text(subtitleText(isPaired: isPaired, isConnected: isCurrentConnected, anotherPaired: anotherDevicePaired))
                    .font(.caption2)
                    .foregroundColor(DexoTheme.textSecondary)
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
                        .buttonStyle(.plain)
                        .font(.system(size: 10, weight: .medium))
                        .foregroundColor(Color.teal)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 3)
                        .background(Color.teal.opacity(0.15))
                        .cornerRadius(4)
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
                        .buttonStyle(.plain)
                        .font(.system(size: 10, weight: .medium))
                        .foregroundColor(DexoTheme.orange)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 3)
                        .background(DexoTheme.orange.opacity(0.15))
                        .cornerRadius(4)
                    }

                    Button("Disconnect") {
                        appState.disconnect()
                    }
                    .buttonStyle(.plain)
                    .font(.system(size: 10, weight: .semibold))
                    .foregroundColor(.white)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(DexoTheme.red)
                    .cornerRadius(4)
                }
            } else if isPaired {
                Button("Connect") {
                    appState.connect(to: device)
                }
                .buttonStyle(.plain)
                .font(.system(size: 10, weight: .semibold))
                .foregroundColor(.white)
                .padding(.horizontal, 10)
                .padding(.vertical, 4)
                .background(DexoTheme.blue)
                .cornerRadius(4)
            } else if anotherDevicePaired {
                let firstPairedName = TrustStore.shared.getPairedDevice()?.deviceName ?? "Current Device"
                HStack(spacing: 4) {
                    Button("Unpair Current") {
                        appState.statusMessage = "Strict 1-Device Policy: Unpair '\(firstPairedName)' first to pair with '\(device.name)'."
                    }
                    .buttonStyle(.plain)
                    .font(.system(size: 10))
                    .foregroundColor(DexoTheme.textSecondary)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 3)
                    .background(DexoTheme.innerBackground)
                    .cornerRadius(4)

                    Button(action: {
                        appState.removeDiscoveredDevice(id: device.id)
                    }) {
                        Image(systemName: "xmark")
                            .font(.system(size: 9))
                            .foregroundColor(DexoTheme.textSecondary)
                    }
                    .buttonStyle(.plain)
                }
            } else {
                HStack(spacing: 4) {
                    Button("Pair Device") {
                        appState.connect(to: device)
                    }
                    .buttonStyle(.plain)
                    .font(.system(size: 10, weight: .semibold))
                    .foregroundColor(.white)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(DexoTheme.blue)
                    .cornerRadius(4)

                    Button(action: {
                        appState.removeDiscoveredDevice(id: device.id)
                    }) {
                        Image(systemName: "xmark")
                            .font(.system(size: 9))
                            .foregroundColor(DexoTheme.textSecondary)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .padding(8)
        .background(DexoTheme.innerBackground)
        .cornerRadius(6)
        .overlay(
            RoundedRectangle(cornerRadius: 6)
                .stroke(DexoTheme.border, lineWidth: 1)
        )
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
    @ObservedObject var appState: AppState
    @Binding var isPresented: Bool
    @Binding var ip: String
    @Binding var port: String
    let onConnect: (String, Int) -> Void

    var body: some View {
        VStack(spacing: 12) {
            Text("Remote Online / IP Connection")
                .font(.headline)
                .fontWeight(.bold)
                .foregroundColor(DexoTheme.textPrimary)

            Text("Connect directly to a phone, Mac, or PC across local Wi-Fi, VPN, or the Internet with WebRTC STUN/TURN live streaming:")
                .font(.caption)
                .foregroundColor(DexoTheme.textSecondary)
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
                .foregroundColor(DexoTheme.textSecondary)

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
        .padding(18)
        .frame(width: 320)
        .background(DexoTheme.background)
        .preferredColorScheme(appState.appearanceMode.colorScheme)
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
                    .foregroundColor(DexoTheme.blue)
                    .font(.title3)

                Text("Activity & History")
                    .font(.headline)
                    .fontWeight(.bold)
                    .foregroundColor(DexoTheme.textPrimary)

                Spacer()

                Button("Done") {
                    isPresented = false
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.small)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .background(DexoTheme.background)

            Divider()
                .background(DexoTheme.border)

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
                .background(DexoTheme.border)

            // Safe footnote
            HStack {
                Image(systemName: "shield.fill")
                    .font(.system(size: 10))
                    .foregroundColor(DexoTheme.textSecondary)
                Text("Deleting history preserves original files on disk and keeps paired connections active.")
                    .font(.system(size: 10))
                    .foregroundColor(DexoTheme.textSecondary)
                Spacer()
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .background(DexoTheme.cardBackground)
        }
        .frame(width: 480, height: 460)
        .background(DexoTheme.background)
        .preferredColorScheme(appState.appearanceMode.colorScheme)
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
                                        .foregroundColor(selectedTransferIds.contains(item.id) ? DexoTheme.blue : DexoTheme.textSecondary)
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
                                    .foregroundColor(DexoTheme.textPrimary)
                                    .lineLimit(1)

                                HStack(spacing: 6) {
                                    Text(formatBytes(item.size))
                                        .font(.caption2)
                                        .foregroundColor(DexoTheme.textSecondary)

                                    Text("•")
                                        .font(.caption2)
                                        .foregroundColor(DexoTheme.textSecondary)

                                    Text(item.isOutgoing ? "Sent" : "Received")
                                        .font(.caption2)
                                        .foregroundColor(item.isOutgoing ? .cyan : .green)

                                    Text("•")
                                        .font(.caption2)
                                        .foregroundColor(DexoTheme.textSecondary)

                                    Text(formattedDate(item.startTime))
                                        .font(.caption2)
                                        .foregroundColor(DexoTheme.textSecondary)
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
                                    .foregroundColor(DexoTheme.textSecondary.opacity(0.8))
                            }
                            .buttonStyle(.plain)
                            .help("Remove from history")
                        }
                        .padding(.vertical, 3)
                    }
                }
                .listStyle(.inset)
                .scrollContentBackground(.hidden)
                .background(DexoTheme.background)
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
                                        .foregroundColor(selectedClipboardIds.contains(item.id) ? DexoTheme.blue : DexoTheme.textSecondary)
                                }
                                .buttonStyle(.plain)
                            }

                            Image(systemName: item.isOutgoing ? "arrow.up.right" : "arrow.down.left")
                                .foregroundColor(item.isOutgoing ? .cyan : .green)
                                .font(.body)

                            VStack(alignment: .leading, spacing: 2) {
                                Text(item.text)
                                    .font(.subheadline)
                                    .foregroundColor(DexoTheme.textPrimary)
                                    .lineLimit(2)

                                HStack(spacing: 6) {
                                    Text(item.isOutgoing ? "Sent" : "Received")
                                        .font(.caption2)
                                        .foregroundColor(item.isOutgoing ? .cyan : .green)

                                    Text("•")
                                        .font(.caption2)
                                        .foregroundColor(DexoTheme.textSecondary)

                                    Text(formattedDate(item.timestamp))
                                        .font(.caption2)
                                        .foregroundColor(DexoTheme.textSecondary)
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
                                    .foregroundColor(DexoTheme.textSecondary.opacity(0.8))
                            }
                            .buttonStyle(.plain)
                            .help("Remove from history")
                        }
                        .padding(.vertical, 3)
                    }
                }
                .listStyle(.inset)
                .scrollContentBackground(.hidden)
                .background(DexoTheme.background)
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
                        .foregroundColor(DexoTheme.textPrimary)
                        .lineLimit(1)
                        .fixedSize(horizontal: true, vertical: false)
                        .layoutPriority(1)

                    if let badge = badgeText {
                        Text(badge)
                            .font(.system(size: 7, weight: .heavy))
                            .foregroundColor(tintColor)
                            .lineLimit(1)
                            .fixedSize(horizontal: true, vertical: false)
                            .padding(.horizontal, 3)
                            .padding(.vertical, 0.5)
                            .background(
                                Capsule().fill(tintColor.opacity(0.12))
                            )
                    }

                    Text(isEnabled ? "ON" : "OFF")
                        .font(.system(size: 8, weight: .heavy))
                        .foregroundColor(isEnabled ? tintColor : DexoTheme.textSecondary)
                        .lineLimit(1)
                        .fixedSize(horizontal: true, vertical: false)
                        .padding(.horizontal, 4)
                        .padding(.vertical, 1)
                        .background(
                            Capsule().fill((isEnabled ? tintColor : DexoTheme.textSecondary).opacity(0.12))
                        )
                }

                if let sub = subtitle {
                    Text(sub)
                        .font(.system(size: 9))
                        .foregroundColor(DexoTheme.textSecondary)
                        .lineLimit(1)
                        .truncationMode(.tail)
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
                            .lineLimit(1)
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
                            .lineLimit(1)
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
        .frame(maxWidth: .infinity)
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



// MARK: - Software Update Section View
struct SoftwareUpdateSectionView: View {
    @ObservedObject var updateManager: MacUpdateManager

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Image(systemName: "arrow.down.circle.fill")
                    .foregroundColor(DexoTheme.blue)
                    .font(.caption)
                Text("Software Update")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundColor(DexoTheme.textSecondary)
            }

            VStack(alignment: .leading, spacing: 0) {
                // App row
                HStack(spacing: 10) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 8)
                            .fill(DexoTheme.blueSubtle)
                            .frame(width: 32, height: 32)
                        Image(systemName: "arrow.clockwise")
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundColor(DexoTheme.blue)
                    }

                    VStack(alignment: .leading, spacing: 1) {
                        Text("DexoLink for macOS")
                            .font(.system(size: 12, weight: .bold))
                            .foregroundColor(DexoTheme.textPrimary)
                        Text("Installed: v\(updateManager.currentVersion)")
                            .font(.system(size: 10))
                            .foregroundColor(DexoTheme.textSecondary)
                    }

                    Spacer()

                    statusBadge
                }
                .padding(.bottom, 8)

                // Release notes if update available
                if case .available(let info) = updateManager.status {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(info.releaseTitle)
                            .font(.system(size: 11, weight: .semibold))
                            .foregroundColor(DexoTheme.textPrimary)
                        let notes = info.releaseNotes
                        let snippet = notes.prefix(200)
                        Text(snippet + (notes.count > 200 ? "..." : ""))
                            .font(.system(size: 10))
                            .foregroundColor(DexoTheme.textSecondary)
                    }
                    .padding(8)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(DexoTheme.blueSubtle)
                    .cornerRadius(6)
                    .padding(.bottom, 8)
                }

                // Action buttons
                HStack(spacing: 8) {
                    Button(action: {
                        Task { await updateManager.checkForUpdates() }
                    }) {
                        Text(updateManager.status == .checking ? "Checking..." : "Check for Updates")
                            .font(.system(size: 11, weight: .semibold))
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                    .disabled(updateManager.status == .checking)

                    if updateManager.isUpdateAvailable, let info = updateManager.availableUpdateInfo, let url = URL(string: info.downloadUrl), !info.downloadUrl.isEmpty {
                        Button(action: {
                            NSWorkspace.shared.open(url)
                        }) {
                            Text("Download")
                                .font(.system(size: 11, weight: .semibold))
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(DexoTheme.blue)
                        .controlSize(.small)
                    }
                }
            }
            .padding(10)
            .background(DexoTheme.cardBackground)
            .cornerRadius(10)
            .overlay(RoundedRectangle(cornerRadius: 10).stroke(DexoTheme.border, lineWidth: 1))
        }
    }

    @ViewBuilder
    private var statusBadge: some View {
        switch updateManager.status {
        case .idle:
            EmptyView()
        case .checking:
            HStack(spacing: 4) {
                ProgressView().scaleEffect(0.5).frame(width: 12, height: 12)
                Text("Checking...")
                    .font(.system(size: 9, weight: .semibold))
                    .foregroundColor(DexoTheme.textSecondary)
            }
        case .upToDate:
            HStack(spacing: 3) {
                Image(systemName: "checkmark.circle.fill")
                    .foregroundColor(DexoTheme.green)
                    .font(.system(size: 9))
                Text("Up to date")
                    .font(.system(size: 9, weight: .bold))
                    .foregroundColor(DexoTheme.green)
            }
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(Capsule().fill(DexoTheme.greenSubtle))
        case .available:
            HStack(spacing: 3) {
                Image(systemName: "sparkles")
                    .foregroundColor(DexoTheme.blue)
                    .font(.system(size: 9))
                Text("New Version")
                    .font(.system(size: 9, weight: .bold))
                    .foregroundColor(DexoTheme.blue)
            }
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(Capsule().fill(DexoTheme.blueSubtle))
        case .downloading(let fraction, _, _):
            HStack(spacing: 4) {
                ProgressView().scaleEffect(0.5).frame(width: 12, height: 12)
                Text("\(Int(fraction * 100))%")
                    .font(.system(size: 9, weight: .semibold))
                    .foregroundColor(DexoTheme.blue)
            }
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(Capsule().fill(DexoTheme.blueSubtle))
        case .error(let msg):
            Text("Check Failed")
                .font(.system(size: 9, weight: .medium))
                .foregroundColor(DexoTheme.red)
                .help(msg)
        }
    }
}

// MARK: - Settings Detail View (Matching Image 2 Right Window)
struct SettingsDetailView: View {
    @ObservedObject var appState: AppState
    @Binding var selectedTab: SettingsTab
    var onClose: () -> Void

    @AppStorage("autoReconnect") private var autoReconnect = true
    @AppStorage("launchAtLogin") private var launchAtLogin = false
    @State private var showHandoffSheet = false
    @State private var showHistorySheet = false
    @State private var historyInitialTab = 1
    @State private var showManualIPSheet = false
    @State private var showQRCodeSheet = false
    @State private var connectionQRCode: NSImage?
    @State private var manualIP = ""
    @State private var manualPort = "52840"

    var body: some View {
        VStack(spacing: 0) {
            // Header: Traffic lights + Back Button + Close
            HStack(spacing: 12) {
                TrafficLightsView(onClose: onClose)

                Button(action: onClose) {
                    HStack(spacing: 4) {
                        Image(systemName: "chevron.left")
                            .font(.system(size: 12, weight: .bold))
                        Text("Settings")
                            .font(.system(size: 14, weight: .bold))
                    }
                    .foregroundColor(DexoTheme.textPrimary)
                }
                .buttonStyle(.plain)

                Spacer()

                Button(action: onClose) {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 16))
                        .foregroundColor(DexoTheme.textSecondary)
                }
                .buttonStyle(.plain)
                .help("Close Settings")
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .background(DexoTheme.background)

            Divider()
                .background(DexoTheme.border)

            // Two-Pane Split View
            HStack(spacing: 0) {
                // Left Sidebar
                VStack(spacing: 3) {
                    ForEach(SettingsTab.allCases) { tab in
                        Button(action: {
                            selectedTab = tab
                        }) {
                            HStack(spacing: 8) {
                                Image(systemName: tab.iconName)
                                    .font(.system(size: 12))
                                    .frame(width: 16)
                                Text(tab.rawValue)
                                    .font(.system(size: 12, weight: selectedTab == tab ? .semibold : .regular))
                                    .lineLimit(1)
                                    .fixedSize(horizontal: true, vertical: false)
                                Spacer()
                            }
                            .padding(.horizontal, 10)
                            .padding(.vertical, 7)
                            .foregroundColor(selectedTab == tab ? .white : DexoTheme.textSecondary)
                            .background(selectedTab == tab ? DexoTheme.blue : Color.clear)
                            .cornerRadius(6)
                        }
                        .buttonStyle(.plain)
                    }
                    Spacer()
                }
                .padding(10)
                .frame(width: 150)
                .background(DexoTheme.sidebarBackground)

                Rectangle()
                    .fill(DexoTheme.border)
                    .frame(width: 1)

                // Right Content Area
                ScrollView {
                    VStack(alignment: .leading, spacing: 14) {
                        switch selectedTab {
                        case .general:
                            generalSettingsTab
                        case .devices:
                            devicesSettingsTab
                        case .fileSharing:
                            fileSharingSettingsTab
                        case .clipboard:
                            clipboardSettingsTab
                        case .network:
                            networkSettingsTab
                        case .storage:
                            storageSettingsTab
                        case .advanced:
                            advancedSettingsTab
                        case .about:
                            aboutSettingsTab
                        }
                    }
                    .padding(14)
                    .frame(maxWidth: .infinity, alignment: .topLeading)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(DexoTheme.background)
            }
        }
        .sheet(isPresented: $showQRCodeSheet) {
            VStack(spacing: 14) {
                Text("Connect a Mobile Device")
                    .font(.headline)
                    .foregroundColor(.white)
                Text("Scan this code with DexoLink on Android or iPhone.")
                    .font(.caption)
                    .foregroundColor(DexoTheme.textSecondary)
                if let connectionQRCode {
                    Image(nsImage: connectionQRCode)
                        .interpolation(.none)
                        .resizable()
                        .frame(width: 240, height: 240)
                        .padding(12)
                        .background(.white)
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                }
                Button("Close") { showQRCodeSheet = false }
                    .keyboardShortcut(.cancelAction)
            }
            .padding(24)
            .frame(minWidth: 320, minHeight: 360)
            .background(DexoTheme.background)
        }
        .sheet(isPresented: $showManualIPSheet) {
            ManualIPSheet(
                appState: appState,
                isPresented: $showManualIPSheet,
                ip: $manualIP,
                port: $manualPort,
                onConnect: { host, port in
                    appState.connectDirect(host: host, port: port)
                }
            )
        }
        .sheet(isPresented: $showHandoffSheet) {
            HandoffSheetView(appState: appState, isPresented: $showHandoffSheet)
        }
        .sheet(isPresented: $showHistorySheet) {
            HistorySheetView(appState: appState, isPresented: $showHistorySheet, selectedTab: historyInitialTab)
        }
    }

    // MARK: - Tab: General
    private var generalSettingsTab: some View {
        VStack(alignment: .leading, spacing: 12) {
            // Appearance Mode (System / Light / Dark)
            VStack(alignment: .leading, spacing: 6) {
                Text("APPEARANCE")
                    .font(.system(size: 10, weight: .bold))
                    .foregroundColor(DexoTheme.textSecondary)

                HStack(spacing: 8) {
                    ForEach(AppAppearanceMode.allCases) { mode in
                        Button(action: {
                            appState.appearanceMode = mode
                        }) {
                            HStack(spacing: 6) {
                                Image(systemName: mode == .system ? "circle.righthalf.filled" : (mode == .light ? "sun.max.fill" : "moon.fill"))
                                    .font(.system(size: 11))
                                Text(mode.rawValue)
                                    .font(.system(size: 11, weight: .medium))
                                    .lineLimit(1)
                                    .fixedSize(horizontal: true, vertical: false)
                            }
                            .foregroundColor(appState.appearanceMode == mode ? .white : DexoTheme.textSecondary)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 7)
                            .padding(.horizontal, 6)
                            .background(
                                RoundedRectangle(cornerRadius: 6)
                                    .fill(appState.appearanceMode == mode ? DexoTheme.blue : DexoTheme.innerBackground)
                            )
                            .overlay(
                                RoundedRectangle(cornerRadius: 6)
                                    .stroke(appState.appearanceMode == mode ? DexoTheme.blue : DexoTheme.border, lineWidth: 1)
                            )
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(10)
                .background(DexoTheme.cardBackground)
                .cornerRadius(10)
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(DexoTheme.border, lineWidth: 1))
            }

            // App (Launch at Login)
            VStack(alignment: .leading, spacing: 6) {
                Text("APP")
                    .font(.system(size: 10, weight: .bold))
                    .foregroundColor(DexoTheme.textSecondary)

                HStack {
                    VStack(alignment: .leading, spacing: 1) {
                        Text("Launch at Login")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundColor(DexoTheme.textPrimary)
                        Text("Automatically start DexoLink in the background when your Mac boots")
                            .font(.system(size: 9))
                            .foregroundColor(DexoTheme.textSecondary)
                    }
                    Spacer()
                    Toggle("", isOn: $launchAtLogin)
                        .toggleStyle(.switch)
                        .labelsHidden()
                        .scaleEffect(0.8)
                }
                .padding(10)
                .background(DexoTheme.cardBackground)
                .cornerRadius(10)
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(DexoTheme.border, lineWidth: 1))
            }

            // Device Pairing (Radio Options matching Image 2)
            VStack(alignment: .leading, spacing: 6) {
                Text("DEVICE PAIRING")
                    .font(.system(size: 10, weight: .bold))
                    .foregroundColor(DexoTheme.textSecondary)

                VStack(spacing: 8) {
                    Button(action: {
                        if appState.isMultiplePairedDevicesEnabled {
                            appState.toggleMultiplePairedDevices()
                        }
                    }) {
                        HStack(spacing: 8) {
                            Image(systemName: !appState.isMultiplePairedDevicesEnabled ? "largecircle.fill.circle" : "circle")
                                .foregroundColor(!appState.isMultiplePairedDevicesEnabled ? DexoTheme.blue : DexoTheme.textSecondary)
                            VStack(alignment: .leading, spacing: 1) {
                                Text("Single Device Pairing")
                                    .font(.system(size: 11, weight: .semibold))
                                    .foregroundColor(DexoTheme.textPrimary)
                                Text("Strict 1-device policy, keychain protected")
                                    .font(.system(size: 9))
                                    .foregroundColor(DexoTheme.textSecondary)
                            }
                            Spacer()
                        }
                    }
                    .buttonStyle(.plain)

                    Divider().background(DexoTheme.borderSubtle)

                    Button(action: {
                        if !appState.isMultiplePairedDevicesEnabled {
                            appState.toggleMultiplePairedDevices()
                        }
                    }) {
                        HStack(spacing: 8) {
                            Image(systemName: appState.isMultiplePairedDevicesEnabled ? "largecircle.fill.circle" : "circle")
                                .foregroundColor(appState.isMultiplePairedDevicesEnabled ? DexoTheme.blue : DexoTheme.textSecondary)
                            VStack(alignment: .leading, spacing: 1) {
                                Text("Multiple Device Pairing")
                                    .font(.system(size: 11, weight: .semibold))
                                    .foregroundColor(DexoTheme.textPrimary)
                                Text("Allow connecting multiple devices simultaneously")
                                    .font(.system(size: 9))
                                    .foregroundColor(DexoTheme.textSecondary)
                            }
                            Spacer()
                        }
                    }
                    .buttonStyle(.plain)
                }
                .padding(10)
                .background(DexoTheme.cardBackground)
                .cornerRadius(10)
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(DexoTheme.border, lineWidth: 1))
            }

            // Quick Controls
            VStack(alignment: .leading, spacing: 6) {
                Text("QUICK CONTROLS")
                    .font(.system(size: 10, weight: .bold))
                    .foregroundColor(DexoTheme.textSecondary)

                VStack(spacing: 8) {
                    HStack {
                        VStack(alignment: .leading, spacing: 1) {
                            Text("Auto Wi-Fi Discovery")
                                .font(.system(size: 11, weight: .semibold))
                                .foregroundColor(DexoTheme.textPrimary)
                            Text("Broadcast & listen on local subnet")
                                .font(.system(size: 9))
                                .foregroundColor(DexoTheme.textSecondary)
                        }
                        Spacer()
                        Toggle("", isOn: Binding(
                            get: { appState.discoveryEnabled },
                            set: { appState.setDiscoveryEnabled($0) }
                        ))
                        .toggleStyle(.switch)
                        .labelsHidden()
                        .scaleEffect(0.8)
                    }

                    Divider().background(DexoTheme.borderSubtle)

                    HStack {
                        VStack(alignment: .leading, spacing: 1) {
                            Text("Auto Reconnect")
                                .font(.system(size: 11, weight: .semibold))
                                .foregroundColor(DexoTheme.textPrimary)
                            Text("Automatically reconnect to paired devices when online")
                                .font(.system(size: 9))
                                .foregroundColor(DexoTheme.textSecondary)
                        }
                        Spacer()
                        Toggle("", isOn: $autoReconnect)
                            .toggleStyle(.switch)
                            .labelsHidden()
                            .scaleEffect(0.8)
                    }
                }
                .padding(10)
                .background(DexoTheme.cardBackground)
                .cornerRadius(10)
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(DexoTheme.border, lineWidth: 1))
            }

            // Received Files Storage
            VStack(alignment: .leading, spacing: 6) {
                Text("RECEIVED FILES")
                    .font(.system(size: 10, weight: .bold))
                    .foregroundColor(DexoTheme.textSecondary)

                VStack(alignment: .leading, spacing: 6) {
                    Text("Downloads Directory")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundColor(DexoTheme.textPrimary)
                    Text(appState.transferManager.downloadDirectory.path)
                        .font(.system(size: 9))
                        .foregroundColor(DexoTheme.textSecondary)
                        .lineLimit(1)
                        .truncationMode(.middle)

                    HStack(spacing: 8) {
                        Button("Show in Finder") {
                            NSWorkspace.shared.open(appState.transferManager.downloadDirectory)
                        }
                        .font(.system(size: 10, weight: .semibold))
                        .foregroundColor(.white)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 5)
                        .background(DexoTheme.blue)
                        .cornerRadius(6)
                        .buttonStyle(.plain)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(10)
                .background(DexoTheme.cardBackground)
                .cornerRadius(10)
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(DexoTheme.border, lineWidth: 1))
            }

            // Software Update Card
            VStack(alignment: .leading, spacing: 6) {
                Text("SOFTWARE UPDATE")
                    .font(.system(size: 10, weight: .bold))
                    .foregroundColor(DexoTheme.textSecondary)

                HStack(spacing: 10) {
                    ZStack {
                        Circle()
                            .fill(DexoTheme.blueSubtle)
                            .frame(width: 30, height: 30)
                        Image(systemName: "arrow.clockwise")
                            .font(.system(size: 12, weight: .bold))
                            .foregroundColor(DexoTheme.blue)
                    }

                    VStack(alignment: .leading, spacing: 1) {
                        Text("Current Version")
                            .font(.system(size: 11, weight: .semibold))
                            .foregroundColor(.white)
                        Text("v\(appState.updateManager.currentVersion)")
                            .font(.system(size: 10))
                            .foregroundColor(DexoTheme.textSecondary)
                    }

                    Spacer()

                    Text(appState.updateManager.isUpdateAvailable ? "Update Available" : "Up to date")
                        .font(.system(size: 9, weight: .bold))
                        .foregroundColor(appState.updateManager.isUpdateAvailable ? DexoTheme.blue : DexoTheme.green)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(
                            Capsule().fill(appState.updateManager.isUpdateAvailable ? DexoTheme.blueSubtle : DexoTheme.greenSubtle)
                        )

                    Button(action: {
                        Task { await appState.updateManager.checkForUpdates() }
                    }) {
                        Text(appState.updateManager.status == .checking ? "Checking..." : "Check Now")
                            .font(.system(size: 10, weight: .semibold))
                            .foregroundColor(.white)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 4)
                            .background(DexoTheme.blue)
                            .cornerRadius(6)
                    }
                    .buttonStyle(.plain)
                    .disabled(appState.updateManager.status == .checking)
                }
                .padding(10)
                .background(DexoTheme.cardBackground)
                .cornerRadius(10)
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(DexoTheme.border, lineWidth: 1))
            }

            // About Card
            Button(action: {
                selectedTab = .about
            }) {
                HStack(spacing: 10) {
                    ZStack {
                        Circle()
                            .fill(DexoTheme.blueSubtle)
                            .frame(width: 28, height: 28)
                        Image(systemName: "link")
                            .font(.system(size: 11, weight: .bold))
                            .foregroundColor(DexoTheme.blue)
                    }

                    VStack(alignment: .leading, spacing: 1) {
                        Text("DexoLink")
                            .font(.system(size: 11, weight: .bold))
                            .foregroundColor(DexoTheme.textPrimary)
                        Text("v4.0.3 • By Rahul Mahaseth")
                            .font(.system(size: 9))
                            .foregroundColor(DexoTheme.textSecondary)
                    }

                    Spacer()

                    Image(systemName: "chevron.right")
                        .font(.system(size: 11))
                        .foregroundColor(DexoTheme.textSecondary)
                }
                .padding(10)
                .background(DexoTheme.cardBackground)
                .cornerRadius(10)
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(DexoTheme.border, lineWidth: 1))
            }
            .buttonStyle(.plain)
        }
    }

    // MARK: - Tab: Devices
    private var devicesSettingsTab: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("DEVICE MANAGEMENT")
                .font(.system(size: 10, weight: .bold))
                .foregroundColor(DexoTheme.textSecondary)

            let trusted = TrustStore.shared.getTrustedDevices()
            if !trusted.isEmpty {
                ForEach(trusted, id: \.deviceId) { paired in
                    let isThisConnected = appState.connectionState.isConnected && (appState.connectedDeviceId == paired.deviceId || appState.connectedDeviceName == paired.deviceName)

                    HStack(spacing: 8) {
                        Image(systemName: "laptopcomputer.and.iphone")
                            .foregroundColor(isThisConnected ? DexoTheme.green : DexoTheme.blue)
                            .font(.title3)

                        VStack(alignment: .leading, spacing: 1) {
                            Text(paired.deviceName)
                                .font(.system(size: 12, weight: .bold))
                                .foregroundColor(DexoTheme.textPrimary)
                                .lineLimit(1)
                                .truncationMode(.tail)
                            Text(isThisConnected ? "Active Session • Connected" : "Paired Device Saved")
                                .font(.system(size: 9))
                                .foregroundColor(DexoTheme.textSecondary)
                                .lineLimit(1)
                        }

                        Spacer()

                        if isThisConnected {
                            Button("Disconnect") {
                                appState.disconnect()
                            }
                            .font(.system(size: 10, weight: .semibold))
                            .foregroundColor(.white)
                            .lineLimit(1)
                            .fixedSize(horizontal: true, vertical: false)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 4)
                            .background(DexoTheme.red)
                            .cornerRadius(4)
                            .buttonStyle(.plain)
                        } else {
                            Button("Connect") {
                                appState.connectToPairedDevice(deviceId: paired.deviceId)
                            }
                            .font(.system(size: 10, weight: .semibold))
                            .foregroundColor(.white)
                            .lineLimit(1)
                            .fixedSize(horizontal: true, vertical: false)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 4)
                            .background(DexoTheme.blue)
                            .cornerRadius(4)
                            .buttonStyle(.plain)
                        }

                        Button("Unpair") {
                            appState.unpair(deviceId: paired.deviceId)
                        }
                        .font(.system(size: 10, weight: .medium))
                        .foregroundColor(DexoTheme.textSecondary)
                        .lineLimit(1)
                        .fixedSize(horizontal: true, vertical: false)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(DexoTheme.innerBackground)
                        .cornerRadius(4)
                        .buttonStyle(.plain)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(10)
                    .background(DexoTheme.cardBackground)
                    .cornerRadius(10)
                    .overlay(RoundedRectangle(cornerRadius: 10).stroke(DexoTheme.border, lineWidth: 1))
                }
            } else {
                Text("No paired devices saved.")
                    .font(.caption)
                    .foregroundColor(DexoTheme.textSecondary)
            }

            HStack(spacing: 10) {
                Button("Direct IP Connection") {
                    showManualIPSheet = true
                }
                .font(.system(size: 11, weight: .medium))
                .foregroundColor(.white)
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .background(DexoTheme.innerBackground)
                .cornerRadius(6)
                .buttonStyle(.plain)

                Button("Show Pairing QR") {
                    generateQRCode()
                    showQRCodeSheet = true
                }
                .font(.system(size: 11, weight: .semibold))
                .foregroundColor(.white)
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .background(DexoTheme.blue)
                .cornerRadius(6)
                .buttonStyle(.plain)
            }
            .padding(.top, 4)
        }
    }

    // MARK: - Tab: File Sharing
    private var fileSharingSettingsTab: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("FILE SHARING PREFERENCES")
                .font(.system(size: 10, weight: .bold))
                .foregroundColor(DexoTheme.textSecondary)

            VStack(alignment: .leading, spacing: 8) {
                Text("Downloads Destination")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundColor(DexoTheme.textPrimary)
                Text(appState.transferManager.downloadDirectory.path)
                    .font(.system(size: 9))
                    .foregroundColor(DexoTheme.textSecondary)

                HStack(spacing: 8) {
                    Button("Open in Finder") {
                        NSWorkspace.shared.open(appState.transferManager.downloadDirectory)
                    }
                    .font(.system(size: 10, weight: .semibold))
                    .foregroundColor(.white)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(DexoTheme.blue)
                    .cornerRadius(6)
                    .buttonStyle(.plain)

                    Button("Clear Transfer History") {
                        appState.clearTransferHistory()
                    }
                    .font(.system(size: 10, weight: .medium))
                    .foregroundColor(DexoTheme.red)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(DexoTheme.innerBackground)
                    .cornerRadius(6)
                    .buttonStyle(.plain)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(10)
            .background(DexoTheme.cardBackground)
            .cornerRadius(10)
            .overlay(RoundedRectangle(cornerRadius: 10).stroke(DexoTheme.border, lineWidth: 1))
        }
    }

    // MARK: - Tab: Clipboard
    private var clipboardSettingsTab: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("CLIPBOARD SYNC")
                .font(.system(size: 10, weight: .bold))
                .foregroundColor(DexoTheme.textSecondary)

            VStack(spacing: 10) {
                CompactFeatureControl(
                    title: "Auto Clipboard Sync",
                    subtitle: appState.lastSyncedClipboardSnippet.map { "Synced: \"\($0.prefix(22))...\"" } ?? "Bidirectional text & links synchronization",
                    icon: "doc.on.clipboard.fill",
                    tintColor: DexoTheme.green,
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

                Divider().background(DexoTheme.borderSubtle)

                CompactFeatureControl(
                    title: "Enhanced Clipboard",
                    subtitle: "Rich HTML & multi-MIME paste compatibility across restricted apps",
                    icon: "sparkles",
                    tintColor: DexoTheme.purple,
                    badgeText: "BETA",
                    isEnabled: appState.isEnhancedClipboardEnabled,
                    onToggle: {
                        appState.setEnhancedClipboardEnabled(!appState.isEnhancedClipboardEnabled)
                    }
                )
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(10)
            .background(DexoTheme.cardBackground)
            .cornerRadius(10)
            .overlay(RoundedRectangle(cornerRadius: 10).stroke(DexoTheme.border, lineWidth: 1))
        }
    }

    // MARK: - Tab: Network
    private var networkSettingsTab: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("NETWORK & DISCOVERY")
                .font(.system(size: 10, weight: .bold))
                .foregroundColor(DexoTheme.textSecondary)

            VStack(spacing: 8) {
                HStack {
                    VStack(alignment: .leading, spacing: 1) {
                        Text("Local IP Address")
                            .font(.system(size: 11, weight: .semibold))
                            .foregroundColor(DexoTheme.textPrimary)
                        Text(appState.localIP)
                            .font(.system(size: 10, design: .monospaced))
                            .foregroundColor(DexoTheme.blue)
                    }
                    Spacer()
                    Button("Rescan Wi-Fi") {
                        appState.refreshDiscovery()
                    }
                    .font(.system(size: 10, weight: .medium))
                    .foregroundColor(DexoTheme.blue)
                    .buttonStyle(.plain)
                }

                Divider().background(DexoTheme.borderSubtle)

                HStack {
                    VStack(alignment: .leading, spacing: 1) {
                        Text("Ports")
                            .font(.system(size: 11, weight: .semibold))
                            .foregroundColor(DexoTheme.textPrimary)
                        Text("TCP: 52840 • UDP Beacon: 52841")
                            .font(.system(size: 9))
                            .foregroundColor(DexoTheme.textSecondary)
                    }
                    Spacer()
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(10)
            .background(DexoTheme.cardBackground)
            .cornerRadius(10)
            .overlay(RoundedRectangle(cornerRadius: 10).stroke(DexoTheme.border, lineWidth: 1))
        }
    }

    // MARK: - Tab: Storage
    private var storageSettingsTab: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("STORAGE")
                .font(.system(size: 10, weight: .bold))
                .foregroundColor(DexoTheme.textSecondary)

            VStack(alignment: .leading, spacing: 6) {
                Text("Received Files Directory")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundColor(DexoTheme.textPrimary)
                Text(appState.transferManager.downloadDirectory.path)
                    .font(.system(size: 9))
                    .foregroundColor(DexoTheme.textSecondary)

                Button("Open Directory in Finder") {
                    NSWorkspace.shared.open(appState.transferManager.downloadDirectory)
                }
                .font(.system(size: 10, weight: .semibold))
                .foregroundColor(.white)
                .padding(.horizontal, 10)
                .padding(.vertical, 5)
                .background(DexoTheme.blue)
                .cornerRadius(6)
                .buttonStyle(.plain)
                .padding(.top, 4)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(10)
            .background(DexoTheme.cardBackground)
            .cornerRadius(10)
            .overlay(RoundedRectangle(cornerRadius: 10).stroke(DexoTheme.border, lineWidth: 1))
        }
    }

    // MARK: - Tab: Advanced (Media Streaming & Remote Control)
    private var advancedSettingsTab: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("MEDIA STREAMING & REMOTE CONTROL")
                .font(.system(size: 10, weight: .bold))
                .foregroundColor(DexoTheme.textSecondary)

            VStack(spacing: 10) {
                // Camera Sharing
                CompactFeatureControl(
                    title: "Camera Sharing",
                    subtitle: appState.isCameraSharingEnabled ? "Streaming active or ready for camera requests" : "Stream camera feed securely over local Wi-Fi",
                    icon: "camera.fill",
                    tintColor: Color.teal,
                    isEnabled: appState.isCameraSharingEnabled,
                    onToggle: {
                        appState.setCameraSharingEnabled(!appState.isCameraSharingEnabled)
                    }
                )

                if appState.isCameraSharingEnabled && appState.connectionState.isConnected, appState.connectedDeviceName != nil {
                    HStack(spacing: 6) {
                        Button("View Remote Camera") {
                            appState.requestRemoteCameraStream()
                        }
                        .font(.system(size: 10, weight: .semibold))
                        .foregroundColor(Color.teal)
                        .padding(.vertical, 4)
                        .frame(maxWidth: .infinity)
                        .background(Color.teal.opacity(0.18))
                        .cornerRadius(6)
                        .buttonStyle(.plain)

                        Button("Stream Mac Camera") {
                            appState.startSharingMacCameraToRemote()
                        }
                        .font(.system(size: 10, weight: .semibold))
                        .foregroundColor(Color.teal)
                        .padding(.vertical, 4)
                        .frame(maxWidth: .infinity)
                        .background(Color.teal.opacity(0.12))
                        .cornerRadius(6)
                        .buttonStyle(.plain)
                    }
                }

                Divider().background(DexoTheme.borderSubtle)

                // Screen Sharing
                CompactFeatureControl(
                    title: "Screen Sharing",
                    subtitle: appState.isScreenSharingEnabled ? "Display mirroring active or ready" : "Mirror device display in real-time over local network",
                    icon: "display.2",
                    tintColor: DexoTheme.orange,
                    isEnabled: appState.isScreenSharingEnabled,
                    onToggle: {
                        appState.setScreenSharingEnabled(!appState.isScreenSharingEnabled)
                    }
                )

                if appState.isScreenSharingEnabled && appState.connectionState.isConnected, appState.connectedDeviceName != nil {
                    HStack(spacing: 6) {
                        Button("View Remote Screen") {
                            appState.requestRemoteScreenStream()
                        }
                        .font(.system(size: 10, weight: .semibold))
                        .foregroundColor(DexoTheme.orange)
                        .padding(.vertical, 4)
                        .frame(maxWidth: .infinity)
                        .background(DexoTheme.orange.opacity(0.18))
                        .cornerRadius(6)
                        .buttonStyle(.plain)

                        Button("Mirror Mac Screen") {
                            appState.startSharingMacScreenToRemote()
                        }
                        .font(.system(size: 10, weight: .semibold))
                        .foregroundColor(DexoTheme.orange)
                        .padding(.vertical, 4)
                        .frame(maxWidth: .infinity)
                        .background(DexoTheme.orange.opacity(0.12))
                        .cornerRadius(6)
                        .buttonStyle(.plain)
                    }
                }

                Divider().background(DexoTheme.borderSubtle)

                // Desktop Mode
                CompactFeatureControl(
                    title: "Desktop Mode",
                    subtitle: appState.isDesktopModeEnabled ? "Remote viewing and input control enabled" : "Remote desktop viewing & input control where supported",
                    icon: "desktopcomputer",
                    tintColor: DexoTheme.purple,
                    badgeText: "BETA",
                    isEnabled: appState.isDesktopModeEnabled,
                    onToggle: {
                        appState.setDesktopModeEnabled(!appState.isDesktopModeEnabled)
                    }
                )

                Divider().background(DexoTheme.borderSubtle)

                // Continuity
                CompactFeatureControl(
                    title: "Continuity (Handoff)",
                    subtitle: "Continue reading PDFs, docs & web across devices",
                    icon: "arrow.triangle.2.circlepath.circle.fill",
                    tintColor: DexoTheme.blue,
                    isEnabled: appState.continuityManager.isEnabled,
                    onToggle: {
                        appState.continuityManager.isEnabled.toggle()
                    },
                    actionTitle: "Send Activity",
                    actionIcon: "paperplane.fill",
                    onAction: {
                        showHandoffSheet = true
                    }
                )
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(10)
            .background(DexoTheme.cardBackground)
            .cornerRadius(10)
            .overlay(RoundedRectangle(cornerRadius: 10).stroke(DexoTheme.border, lineWidth: 1))
        }
    }

    // MARK: - Tab: About
    private var aboutSettingsTab: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("ABOUT DEXOLINK")
                .font(.system(size: 10, weight: .bold))
                .foregroundColor(DexoTheme.textSecondary)

            VStack(spacing: 12) {
                ZStack {
                    Circle()
                        .fill(DexoTheme.blueSubtle)
                        .frame(width: 48, height: 48)
                    Image(systemName: "link")
                        .font(.system(size: 20, weight: .bold))
                        .foregroundColor(DexoTheme.blue)
                }

                VStack(spacing: 3) {
                    Text("DexoLink for macOS")
                        .font(.system(size: 14, weight: .bold))
                        .foregroundColor(DexoTheme.textPrimary)
                    Text("Version 4.0.3")
                        .font(.system(size: 11))
                        .foregroundColor(DexoTheme.textSecondary)
                    Text("Seamless Cross-Platform Connectivity across Android, iOS, Windows & Mac")
                        .font(.system(size: 10))
                        .foregroundColor(DexoTheme.textSecondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 16)
                        .padding(.top, 2)
                }

                Divider().background(DexoTheme.borderSubtle)

                VStack(spacing: 4) {
                    Text("Developed with ❤️ by Rahul Mahaseth")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundColor(DexoTheme.textPrimary)
                    Link("Website: www.rahulmahaseth.com.np", destination: URL(string: "https://www.rahulmahaseth.com.np")!)
                        .font(.system(size: 10, weight: .medium))
                        .foregroundColor(DexoTheme.blue)
                    Text("GitHub: github.com/rahulmahasetha/dexolink")
                        .font(.system(size: 9))
                        .foregroundColor(DexoTheme.textSecondary)
                }
            }
            .padding(16)
            .frame(maxWidth: .infinity)
            .background(DexoTheme.cardBackground)
            .cornerRadius(12)
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(DexoTheme.border, lineWidth: 1))

            SoftwareUpdateSectionView(updateManager: appState.updateManager)
        }
    }

    private func generateQRCode() {
        guard appState.localIP != "Unavailable", !appState.localIP.isEmpty else {
            connectionQRCode = nil
            return
        }
        var components = URLComponents()
        components.scheme = "dexolink"
        components.host = "connect"
        components.queryItems = [
            URLQueryItem(name: "host", value: appState.localIP),
            URLQueryItem(name: "port", value: String(appState.discoveryService.listeningPort)),
            URLQueryItem(name: "name", value: Host.current().localizedName ?? "Mac")
        ]
        guard let payload = components.string else {
            connectionQRCode = nil
            return
        }
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(payload.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 8, y: 8)) else {
            connectionQRCode = nil
            return
        }
        let representation = NSCIImageRep(ciImage: output)
        let image = NSImage(size: representation.size)
        image.addRepresentation(representation)
        connectionQRCode = image
    }
}

// MARK: - SettingsSheetView
struct SettingsSheetView: View {
    @ObservedObject var appState: AppState
    @Binding var isPresented: Bool
    @State private var selectedTab: SettingsTab = .general

    var body: some View {
        SettingsDetailView(
            appState: appState,
            selectedTab: $selectedTab,
            onClose: { isPresented = false }
        )
        .frame(width: 660, height: 640)
        .background(DexoTheme.background)
        .preferredColorScheme(appState.appearanceMode.colorScheme)
    }
}

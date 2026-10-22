import SwiftUI

public enum iOSTab {
    case home
    case files
    case transfers
    case settings
    case qrConnect
}

public struct ContentView: View {
    @ObservedObject var appState = iOSAppState.shared
    @State private var currentTab: iOSTab = .home
    @State private var showingQuickSendPicker = false

    public init() {}

    public var body: some View {
        ZStack(alignment: .bottom) {
            Color(hex: "F3F6FC")
                .ignoresSafeArea()

            // Main Active Screen Content
            VStack(spacing: 0) {
                switch currentTab {
                case .home:
                    HomeView(appState: appState, onNavigateQR: {
                        withAnimation(.easeInOut(duration: 0.25)) {
                            currentTab = .qrConnect
                        }
                    })
                case .qrConnect:
                    QRConnectView(appState: appState, onBack: {
                        withAnimation(.easeInOut(duration: 0.25)) {
                            currentTab = .home
                        }
                    })
                case .files:
                    NavigationStack {
                        FilesListView(appState: appState)
                    }
                case .transfers:
                    NavigationStack {
                        TransferView(appState: appState)
                    }
                case .settings:
                    NavigationStack {
                        DevicesView(appState: appState)
                    }
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)

            // Floating Bottom Navigation Bar (hidden on QR Connect)
            if currentTab != .qrConnect {
                iOSFloatingNavBar(
                    selectedTab: $currentTab,
                    activeTransfersCount: appState.activeTransfer != nil ? 1 : 0,
                    onCenterPlusClick: {
                        showingQuickSendPicker = true
                    }
                )
                .padding(.horizontal, 20)
                .padding(.bottom, 10)
            }
        }
        .sheet(isPresented: $showingQuickSendPicker) {
            FilePickerBridge { url in
                appState.sendFile(url: url, targetDevice: appState.discoveredDevices.first)
            }
        }
    }
}

// Floating Bottom Nav Bar for iOS matching Mobile Design
struct iOSFloatingNavBar: View {
    @Binding var selectedTab: iOSTab
    var activeTransfersCount: Int
    var onCenterPlusClick: () -> Void

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 36)
                .fill(Color.white)
                .frame(height: 68)
                .shadow(color: Color.black.opacity(0.08), radius: 12, x: 0, y: 6)
                .overlay(
                    RoundedRectangle(cornerRadius: 36)
                        .stroke(Color(hex: "E2E8F0"), lineWidth: 1)
                )

            HStack {
                // 1. Home
                NavBarTabButton(
                    title: "Home",
                    icon: "house.fill",
                    isSelected: selectedTab == .home
                ) {
                    selectedTab = .home
                }

                Spacer()

                // 2. Files
                NavBarTabButton(
                    title: "Files",
                    icon: "folder.fill",
                    isSelected: selectedTab == .files
                ) {
                    selectedTab = .files
                }

                Spacer()

                // 3. Center Elevated Circular + Button
                Button(action: onCenterPlusClick) {
                    ZStack {
                        Circle()
                            .fill(Color(hex: "2563EB"))
                            .frame(width: 52, height: 52)
                            .shadow(color: Color(hex: "2563EB").opacity(0.35), radius: 6, x: 0, y: 4)

                        Image(systemName: "plus")
                            .font(.system(size: 22, weight: .bold))
                            .foregroundColor(.white)
                    }
                }
                .buttonStyle(.plain)

                Spacer()

                // 4. Transfers
                NavBarTabButton(
                    title: "Transfers",
                    icon: "arrow.up.arrow.down",
                    isSelected: selectedTab == .transfers,
                    badge: activeTransfersCount > 0 ? "\(activeTransfersCount)" : nil
                ) {
                    selectedTab = .transfers
                }

                Spacer()

                // 5. Settings
                NavBarTabButton(
                    title: "Settings",
                    icon: "gearshape.fill",
                    isSelected: selectedTab == .settings
                ) {
                    selectedTab = .settings
                }
            }
            .padding(.horizontal, 16)
        }
    }
}

// Nav Bar Tab Button Item
struct NavBarTabButton: View {
    let title: String
    let icon: String
    let isSelected: Bool
    var badge: String? = nil
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 3) {
                ZStack(alignment: .topTrailing) {
                    Image(systemName: icon)
                        .font(.system(size: 20))
                        .foregroundColor(isSelected ? Color(hex: "2563EB") : Color(hex: "64748B"))

                    if let b = badge {
                        Circle()
                            .fill(Color(hex: "EF4444"))
                            .frame(width: 14, height: 14)
                            .overlay(
                                Text(b)
                                    .font(.system(size: 9, weight: .bold))
                                    .foregroundColor(.white)
                            )
                            .offset(x: 8, y: -4)
                    }
                }

                Text(title)
                    .font(.system(size: 11, weight: isSelected ? .bold : .medium))
                    .foregroundColor(isSelected ? Color(hex: "2563EB") : Color(hex: "64748B"))
            }
            .frame(width: 54)
        }
        .buttonStyle(.plain)
    }
}

// Simple Files list view
struct FilesListView: View {
    @ObservedObject var appState: iOSAppState

    var body: some View {
        List {
            if appState.transferHistory.isEmpty {
                VStack(spacing: 12) {
                    Image(systemName: "folder")
                        .font(.system(size: 40))
                        .foregroundColor(Color(hex: "94A3B8"))
                    Text("No files transferred yet")
                        .font(.subheadline)
                        .foregroundColor(Color(hex: "64748B"))
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 40)
            } else {
                ForEach(appState.transferHistory) { item in
                    HStack(spacing: 12) {
                        Image(systemName: item.isIncoming ? "arrow.down.doc.fill" : "arrow.up.doc.fill")
                            .font(.system(size: 24))
                            .foregroundColor(Color(hex: "2563EB"))

                        VStack(alignment: .leading, spacing: 2) {
                            Text(item.fileName)
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundColor(Color(hex: "0F172A"))
                            Text("\(item.isIncoming ? "Received from" : "Sent to") \(item.peerName)")
                                .font(.caption)
                                .foregroundColor(Color(hex: "64748B"))
                        }

                        Spacer()

                        Text(formatByteCount(item.fileSize))
                            .font(.caption)
                            .foregroundColor(Color(hex: "64748B"))
                    }
                    .padding(.vertical, 4)
                }
            }
        }
        .navigationTitle("Files")
    }

    private func formatByteCount(_ bytes: Int64) -> String {
        let b = Double(bytes)
        if b >= 1024 * 1024 * 1024 {
            return String(format: "%.1f GB", b / (1024 * 1024 * 1024))
        } else if b >= 1024 * 1024 {
            return String(format: "%.1f MB", b / (1024 * 1024))
        } else if b >= 1024 {
            return String(format: "%.1f KB", b / 1024)
        } else {
            return "\(bytes) B"
        }
    }
}
